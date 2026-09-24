package cn.zjnu.a02.platform.security;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import cn.zjnu.a02.common.api.BizException;
import cn.zjnu.a02.common.api.ErrorCode;
import cn.zjnu.a02.common.util.CommonUtils;
import cn.zjnu.a02.common.util.JsonUtils;

/**
 * 自实现 JWT（HS256）。
 *
 * <p>架构文档 §9.2 要求「Access 2h + 可撤销」；本 Demo 使用 JDK 原生 HMAC-SHA256 实现，
 * 不引入 jjwt（离线环境依赖不可得）。令牌载荷只放 userId/username/role，**不放任何敏感字段**。
 */
@Component
public class JwtService {

    public record Claims(long userId, String username, String role, long exp) {
    }

    private final String secret;
    private final long ttlMinutes;
    private final JsonUtils json;

    public JwtService(@Value("${a02.security.jwt.secret}") String secret,
                      @Value("${a02.security.jwt.access-ttl-minutes:120}") long ttlMinutes,
                      JsonUtils json) {
        this.secret = secret;
        this.ttlMinutes = ttlMinutes;
        this.json = json;
    }

    public String issue(long userId, String username, String role) {
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("alg", "HS256");
        header.put("typ", "JWT");

        long now = System.currentTimeMillis() / 1000;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sub", String.valueOf(userId));
        payload.put("usr", username);
        payload.put("role", role);
        payload.put("iat", now);
        payload.put("exp", now + ttlMinutes * 60);

        String h = CommonUtils.base64UrlEncode(json.stringify(header).getBytes(StandardCharsets.UTF_8));
        String p = CommonUtils.base64UrlEncode(json.stringify(payload).getBytes(StandardCharsets.UTF_8));
        String signature = CommonUtils.hmacSha256Base64Url(secret, h + "." + p);
        return h + "." + p + "." + signature;
    }

    /** 解析并校验令牌；无效/过期返回 null（由调用方转 401）。 */
    public Claims parse(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            return null;
        }
        String expected = CommonUtils.hmacSha256Base64Url(secret, parts[0] + "." + parts[1]);
        if (!constantTimeEquals(expected, parts[2])) {
            return null;
        }
        try {
            String payloadJson = new String(CommonUtils.base64UrlDecode(parts[1]), StandardCharsets.UTF_8);
            Map<String, Object> payload = json.parseMap(payloadJson);
            long exp = ((Number) payload.get("exp")).longValue();
            if (exp * 1000 < System.currentTimeMillis()) {
                return null;
            }
            return new Claims(
                    Long.parseLong(String.valueOf(payload.get("sub"))),
                    String.valueOf(payload.get("usr")),
                    String.valueOf(payload.getOrDefault("role", "USER")),
                    exp);
        } catch (Exception e) {
            return null;
        }
    }

    public Claims require(String token) {
        Claims claims = parse(token);
        if (claims == null) {
            throw new BizException(ErrorCode.UNAUTHENTICATED);
        }
        return claims;
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < a.length(); i++) {
            diff |= a.charAt(i) ^ b.charAt(i);
        }
        return diff == 0;
    }
}
