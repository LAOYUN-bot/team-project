package cn.zjnu.a02.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.zjnu.a02.ai.privacy.PiiRedactor;
import cn.zjnu.a02.common.util.CryptoUtils;
import cn.zjnu.a02.common.util.MaskingUtils;
import cn.zjnu.a02.platform.security.JwtService;

/**
 * 安全、隐私与鉴权单元测试（架构文档 §9，NFR-2 / NFR-3 / NFR-4）。
 *
 * <p>覆盖：JWT 签发与防篡改、字段级加密、脱敏管道（传模型前）、掩码工具。
 */
class SecurityAndPrivacyTest {

    private static final String SECRET = "unit-test-secret-key-0123456789abcdefghijklmnop";

    // ------------------------------------------------------------------
    // JWT
    // ------------------------------------------------------------------

    @Test
    @DisplayName("JWT：签发的令牌可被解析，且载荷字段正确")
    void issueAndParseToken() {
        JwtService jwt = new JwtService(SECRET, 120, new cn.zjnu.a02.common.util.JsonUtils());
        String token = jwt.issue(42L, "demo", "USER");

        JwtService.Claims claims = jwt.parse(token);
        assertTrue(claims != null, "合法令牌应解析成功");
        assertEquals(42L, claims.userId());
        assertEquals("demo", claims.username());
        assertEquals("USER", claims.role());
    }

    @Test
    @DisplayName("JWT：载荷被篡改后必须解析失败（防越权提权）")
    void tamperedPayloadRejected() {
        JwtService jwt = new JwtService(SECRET, 120, new cn.zjnu.a02.common.util.JsonUtils());
        String token = jwt.issue(42L, "demo", "USER");
        String[] parts = token.split("\\.");

        // 把角色从 USER 改成 ADMIN（保留原签名）
        String forgedPayload = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("{\"sub\":\"42\",\"usr\":\"demo\",\"role\":\"ADMIN\",\"iat\":1,\"exp\":9999999999}")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String forged = parts[0] + "." + forgedPayload + "." + parts[2];

        assertNull(jwt.parse(forged), "篡改后的令牌不应通过签名校验");
    }

    @Test
    @DisplayName("JWT：换用其他密钥签发的令牌必须被拒绝")
    void tokenFromOtherSecretRejected() {
        JwtService issuer = new JwtService(SECRET, 120, new cn.zjnu.a02.common.util.JsonUtils());
        JwtService verifier = new JwtService("another-secret-key-9876543210zyxwvutsrqponml",
                120, new cn.zjnu.a02.common.util.JsonUtils());
        assertNull(verifier.parse(issuer.issue(1L, "u", "USER")));
    }

    @Test
    @DisplayName("JWT：过期令牌必须被拒绝")
    void expiredTokenRejected() {
        JwtService jwt = new JwtService(SECRET, -1, new cn.zjnu.a02.common.util.JsonUtils());
        assertNull(jwt.parse(jwt.issue(1L, "u", "USER")), "TTL 为负应立即过期");
    }

    @Test
    @DisplayName("JWT：非法格式（空、乱码、段数不对）一律返回 null，不抛异常")
    void malformedTokenReturnsNull() {
        JwtService jwt = new JwtService(SECRET, 120, new cn.zjnu.a02.common.util.JsonUtils());
        assertNull(jwt.parse(null));
        assertNull(jwt.parse(""));
        assertNull(jwt.parse("not-a-token"));
        assertNull(jwt.parse("a.b"));
        assertNull(jwt.parse("a.b.c.d"));
    }

    // ------------------------------------------------------------------
    // 字段级加密（AES-GCM）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("字段加密：加解密可往返，且密文不包含明文")
    void fieldEncryptionRoundTrip() {
        var key = CryptoUtils.deriveKey("demo-field-key");
        String plain = "13800138000";
        String cipher = CryptoUtils.encrypt(key, plain);

        assertNotEquals(plain, cipher);
        assertFalse(cipher.contains(plain), "密文不得包含明文");
        assertEquals(plain, CryptoUtils.decrypt(key, cipher));
    }

    @Test
    @DisplayName("字段加密：相同明文两次加密产生不同密文（随机 IV，防重放比对）")
    void encryptionUsesRandomIv() {
        var key = CryptoUtils.deriveKey("demo-field-key");
        assertNotEquals(CryptoUtils.encrypt(key, "13800138000"), CryptoUtils.encrypt(key, "13800138000"));
    }

    @Test
    @DisplayName("字段加密：使用错误密钥解密必须失败，而不是返回错误明文")
    void decryptionWithWrongKeyFails() {
        String cipher = CryptoUtils.encrypt(CryptoUtils.deriveKey("key-a"), "secret-data");
        boolean failed = false;
        try {
            CryptoUtils.decrypt(CryptoUtils.deriveKey("key-b"), cipher);
        } catch (RuntimeException e) {
            failed = true;
        }
        assertTrue(failed, "错误密钥解密应抛出异常（GCM 完整性校验）");
    }

    // ------------------------------------------------------------------
    // 传模型前脱敏（NFR-3：敏感信息不上传公网模型）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("脱敏：上下文中的姓名、手机号、邮箱被掩码")
    void redactsSensitiveContextFields() {
        PiiRedactor redactor = new PiiRedactor();
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("name", "张三丰");
        context.put("phone", "13800138000");
        context.put("email", "zhangsan@example.com");
        context.put("targetJobName", "Java 后端开发工程师"); // 不应被掩码

        Map<String, Object> safe = redactor.redact(context);

        assertFalse(String.valueOf(safe.get("name")).contains("三丰"), "姓名未脱敏：" + safe.get("name"));
        assertEquals("138****8000", safe.get("phone"));
        assertEquals("z***@example.com", safe.get("email"));
        assertEquals("Java 后端开发工程师", safe.get("targetJobName"), "岗位名不应被误脱敏");
    }

    @Test
    @DisplayName("脱敏：自由文本中的手机号、邮箱、身份证号、院校被替换")
    void redactsFreeText() {
        PiiRedactor redactor = new PiiRedactor();
        String text = "我叫张三，电话 13800138000，邮箱 a.b@test.com，"
                + "身份证 110101199001011234，就读于浙江大学。";
        String safe = redactor.redactText(text);

        assertFalse(safe.contains("13800138000"), "手机号未脱敏：" + safe);
        assertFalse(safe.contains("a.b@test.com"), "邮箱未脱敏：" + safe);
        assertFalse(safe.contains("110101199001011234"), "身份证号未脱敏：" + safe);
        assertFalse(safe.contains("浙江大学"), "院校未泛化：" + safe);
        assertTrue(safe.contains("本科院校"), "院校应泛化为层级：" + safe);
    }

    @Test
    @DisplayName("脱敏：嵌套结构与数组内的敏感信息同样被处理")
    void redactsNestedStructures() {
        PiiRedactor redactor = new PiiRedactor();
        Map<String, Object> nested = Map.of(
                "user", Map.of("name", "李四", "phone", "13900139000"),
                "notes", java.util.List.of("联系 13700137000"));

        Map<String, Object> safe = redactor.redact(nested);
        String serialized = safe.toString();
        assertFalse(serialized.contains("13900139000"), "嵌套字段未脱敏");
        assertFalse(serialized.contains("13700137000"), "数组文本未脱敏");
    }

    @Test
    @DisplayName("脱敏：null 与空文本安全处理，不抛异常")
    void redactionHandlesNullSafely() {
        PiiRedactor redactor = new PiiRedactor();
        assertNull(redactor.redactText(null));
        assertTrue(redactor.redact(null).isEmpty());
    }

    // ------------------------------------------------------------------
    // 掩码工具
    // ------------------------------------------------------------------

    @Test
    @DisplayName("掩码：姓名保留首尾、手机保留前3后4、邮箱保留首字母")
    void maskingRules() {
        assertEquals("张*丰", MaskingUtils.maskName("张三丰"));
        assertEquals("李*", MaskingUtils.maskName("李四"));
        assertEquals("王", MaskingUtils.maskName("王"));
        assertEquals("138****8000", MaskingUtils.maskPhone("13800138000"));
        assertEquals("a***@example.com", MaskingUtils.maskEmail("abc@example.com"));
        assertNull(MaskingUtils.maskName(null));
    }

    @Test
    @DisplayName("掩码：异常输入不被破坏（短号码、无 @ 的邮箱原样返回）")
    void maskingHandlesEdgeInputs() {
        assertEquals("123", MaskingUtils.maskPhone("123"));
        assertEquals("not-an-email", MaskingUtils.maskEmail("not-an-email"));
    }
}
