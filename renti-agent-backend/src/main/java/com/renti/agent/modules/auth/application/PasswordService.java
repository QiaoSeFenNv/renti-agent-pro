package com.renti.agent.modules.auth.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.Base64;
import java.util.HexFormat;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * 密码与令牌摘要服务。
 *
 * <p>新密码一律 BCrypt；同时兼容旧系统迁移用户的
 * PBKDF2-HMAC-SHA256（base64url 摘要 + 独立盐 + 迭代次数）校验。</p>
 */
@Slf4j
@Service
public class PasswordService {

    public static final String ALGO_BCRYPT = "bcrypt";
    public static final String ALGO_PBKDF2 = "pbkdf2";

    private final BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder();
    private final SecureRandom secureRandom = new SecureRandom();

    /** BCrypt 加密 */
    public String hash(String rawPassword) {
        return bcrypt.encode(rawPassword);
    }

    /** 按算法标记校验密码 */
    public boolean verify(String rawPassword, String algo, String hash, String salt, Integer iterations) {
        if (ALGO_PBKDF2.equalsIgnoreCase(algo)) {
            return verifyLegacyPbkdf2(rawPassword, hash, salt, iterations);
        }
        return bcrypt.matches(rawPassword, hash);
    }

    /** 旧系统 PBKDF2 校验：base64url(pbkdf2_hmac_sha256(password, salt, iterations)) == hash */
    private boolean verifyLegacyPbkdf2(String rawPassword, String expectedHash, String salt, Integer iterations) {
        if (expectedHash == null || salt == null || iterations == null || iterations <= 0) {
            return false;
        }
        try {
            byte[] saltBytes = Base64.getUrlDecoder().decode(padBase64(salt));
            var spec = new PBEKeySpec(rawPassword.toCharArray(), saltBytes, iterations, 256);
            byte[] digest = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(spec)
                    .getEncoded();
            String actual = Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
            return MessageDigest.isEqual(
                    actual.getBytes(StandardCharsets.US_ASCII),
                    stripPadding(expectedHash).getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException | InvalidKeySpecException | IllegalArgumentException exception) {
            log.warn("Legacy password verification failed to run: {}", exception.getMessage());
            return false;
        }
    }

    /** 生成随机会话 token（返回给 Cookie 的原文） */
    public String generateToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** 生成 6 位数字验证码 */
    public String generateVerificationCode() {
        return String.format("%06d", secureRandom.nextInt(1_000_000));
    }

    /** token 的 SHA-256 十六进制摘要（数据库存储形态） */
    public String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private String padBase64(String value) {
        String clean = stripPadding(value);
        int remainder = clean.length() % 4;
        return remainder == 0 ? clean : clean + "=".repeat(4 - remainder);
    }

    private String stripPadding(String value) {
        return value.replace("=", "").trim();
    }
}
