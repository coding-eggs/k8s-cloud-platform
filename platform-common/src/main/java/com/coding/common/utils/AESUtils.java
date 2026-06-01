package com.coding.common.utils;

import lombok.extern.slf4j.Slf4j;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

@Slf4j
public class AESUtils {

    private static final String ALGORITHM = "AES/CBC/PKCS5Padding";
    private static final int ITERATIONS = 65536;
    private static final int KEY_LENGTH = 256;        // AES-256
    private static final int SALT_LENGTH = 16;
    private static final int IV_LENGTH = 16;

    /**
     * 加密字符串
     */
    public static String encrypt(String plainText, String masterPassword) {
        if (plainText == null || plainText.trim().isEmpty()) {
            return null;
        }

        try {
            byte[] salt = generateRandomBytes(SALT_LENGTH);
            byte[] iv = generateRandomBytes(IV_LENGTH);

            SecretKey secretKey = deriveKey(masterPassword, salt);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new IvParameterSpec(iv));

            byte[] encryptedBytes = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));

            // 格式： salt + iv + encrypted
            byte[] combined = new byte[SALT_LENGTH + IV_LENGTH + encryptedBytes.length];
            System.arraycopy(salt, 0, combined, 0, SALT_LENGTH);
            System.arraycopy(iv, 0, combined, SALT_LENGTH, IV_LENGTH);
            System.arraycopy(encryptedBytes, 0, combined, SALT_LENGTH + IV_LENGTH, encryptedBytes.length);

            return Base64.getEncoder().encodeToString(combined);

        } catch (Exception e) {
            log.error("加密失败", e);
            throw new RuntimeException("数据加密失败", e);
        }
    }

    /**
     * 解密字符串
     */
    public static String decrypt(String encryptedText, String masterPassword) {
        if (encryptedText == null || encryptedText.trim().isEmpty()) {
            return null;
        }

        try {
            byte[] combined = Base64.getDecoder().decode(encryptedText);

            if (combined.length < SALT_LENGTH + IV_LENGTH) {
                throw new IllegalArgumentException("加密数据格式不正确");
            }

            byte[] salt = new byte[SALT_LENGTH];
            byte[] iv = new byte[IV_LENGTH];
            byte[] encrypted = new byte[combined.length - SALT_LENGTH - IV_LENGTH];

            System.arraycopy(combined, 0, salt, 0, SALT_LENGTH);
            System.arraycopy(combined, SALT_LENGTH, iv, 0, IV_LENGTH);
            System.arraycopy(combined, SALT_LENGTH + IV_LENGTH, encrypted, 0, encrypted.length);

            SecretKey secretKey = deriveKey(masterPassword, salt);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new IvParameterSpec(iv));

            byte[] decryptedBytes = cipher.doFinal(encrypted);
            return new String(decryptedBytes, StandardCharsets.UTF_8);

        } catch (Exception e) {
            log.error("解密失败", e);
            throw new RuntimeException("数据解密失败，请检查密钥或数据是否被篡改", e);
        }
    }

    /**
     * 使用 PBKDF2 派生密钥（更安全）
     */
    private static SecretKey deriveKey(String password, byte[] salt) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, ITERATIONS, KEY_LENGTH);
        SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        byte[] keyBytes = factory.generateSecret(spec).getEncoded();
        return new SecretKeySpec(keyBytes, "AES");
    }

    private static byte[] generateRandomBytes(int length) {
        byte[] bytes = new byte[length];
        new SecureRandom().nextBytes(bytes);
        return bytes;
    }


    /**
     * 生成一个强随机密钥（建议在初始化项目时调用一次，生成后配置到配置文件中）
     */
    public static String generateStrongKey() {
        byte[] key = new byte[32]; // 256位
        new SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }

}
