package cn.zjnu.a02.platform.privacy;

import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import cn.zjnu.a02.common.util.CryptoUtils;

/**
 * 敏感字段列加密器（架构文档 §9.2）。
 *
 * <p>手机号、邮箱等字段以 AES-GCM 密文入库；密钥来自环境变量 {@code FIELD_AES_KEY}，
 * **不入库、不入 Git**。读取时按需解密，接口默认只回传掩码值。
 */
@Component
public class FieldEncryptor {

    private final SecretKeySpec key;

    public FieldEncryptor(@Value("${a02.security.crypto.field-key}") String fieldKey) {
        this.key = CryptoUtils.deriveKey(fieldKey);
    }

    public String encrypt(String plain) {
        return CryptoUtils.encrypt(key, plain);
    }

    public String decrypt(String cipher) {
        return CryptoUtils.decrypt(key, cipher);
    }
}
