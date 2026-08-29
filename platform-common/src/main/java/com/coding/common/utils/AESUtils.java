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

    public static void main(String[] args) {

        String config = """
                apiVersion: v1
                clusters:
                - cluster:
                    certificate-authority-data: LS0tLS1CRUdJTiBDRVJUSUZJQ0FURS0tLS0tCk1JSURCVENDQWUyZ0F3SUJBZ0lJUEN0R3dYMUtTRXd3RFFZSktvWklodmNOQVFFTEJRQXdGVEVUTUJFR0ExVUUKQXhNS2EzVmlaWEp1WlhSbGN6QWVGdzB5TmpBNE1qa3dOekE1TWpSYUZ3MHpOakE0TWpZd056RTBNalJhTUJVeApFekFSQmdOVkJBTVRDbXQxWW1WeWJtVjBaWE13Z2dFaU1BMEdDU3FHU0liM0RRRUJBUVVBQTRJQkR3QXdnZ0VLCkFvSUJBUURaRDU0WmZ2bGEyd1YzdU9GcXVkV3cwZUYvdUw1L2RuWXJ0Vzd1K0REZlBDMFpiTmM5QUdsUEh5ZGUKVjh2ZjdZVVBKNno4d2FUN3RyeGtySWVRYUJ0MXpjODE5QnM3ZDdHbVJQMFRkTjJKcDJHUlN5UFhadHdkbzk5bQpTdG5xOUU2cDJVdWp1Q1N2N1o4UjRjek9rM0ZGbFVrRzZCeUZJelEwM0xTck0zSEJHUzZ5cW1CVTVtZ1cweFY2CjFhL1l6d1JLV29RbXNFaXd5V1FJYzZRYnh6OGRIb3ZITlAxTEM2R1B2VmMycXZXTm9EVVpNTHpZZ0UxRnhQNTcKYXlIZjVTbjFlWnU2RXgzMGluNHdoTFQ1T241eWtjcXE4VGtkOUUyKzhPalBwR1BpUkJzYUhUQmltR3JGbTBZVgpFRHFKK0RuUUU5OGtrYWo4YVZNZzhUZXJJcUhUQWdNQkFBR2pXVEJYTUE0R0ExVWREd0VCL3dRRUF3SUNwREFQCkJnTlZIUk1CQWY4RUJUQURBUUgvTUIwR0ExVWREZ1FXQkJRS1pjLzBZYmM4UzBoUjIwRlNPRUZnQ09TZ056QVYKQmdOVkhSRUVEakFNZ2dwcmRXSmxjbTVsZEdWek1BMEdDU3FHU0liM0RRRUJDd1VBQTRJQkFRRENoMjlQdTI4bAorWDNnYzBpSlgxYUk0eUx3bDhwRFRudmh5UWdwVE1DQTZMUGN2cTg0RW1GdngvcmNrNmtxSXNUck1KVzZEbU5ICmtjRytXTHBQYmNVWTY5Rll6YUVYZ3FIZ2hZWjBTeVgxYzlidFo4TFJ5RkNjVjVub0FNekZBWTNnODJHSGtucnkKbWp6eGx0WUo1aHd5UHhaWm1QRXVhTTRBTnJjY3Y1MnVhM1hRbzRUTGdXRjdnSklpTHRDTGMybmh3Wjl6K1dPdwpSVHFkZHBNMjRncnZXenhUdUhQeWFRZkdidEZzVXlMbi9jUFN0NXR3MTRUd0xDWEJtbjZNSmtneVQ3U2tXRE4wCnNGRWlBYnBHZkMxNFZpOUl1cXVVRHc0RHlzNkxOckM5djhiTFdpM0M3U0ZuM1R0Sk1iaVUxUXp3endxUThDT0wKUTY3L05jMmlaZ0ppCi0tLS0tRU5EIENFUlRJRklDQVRFLS0tLS0K
                    server: https://192.168.85.180:6443
                  name: kubernetes
                contexts:
                - context:
                    cluster: kubernetes
                    user: kubernetes-admin
                  name: kubernetes-admin@kubernetes
                current-context: kubernetes-admin@kubernetes
                kind: Config
                users:
                - name: kubernetes-admin
                  user:
                    client-certificate-data: LS0tLS1CRUdJTiBDRVJUSUZJQ0FURS0tLS0tCk1JSURLVENDQWhHZ0F3SUJBZ0lJSzBEaFoxMEMrbWt3RFFZSktvWklodmNOQVFFTEJRQXdGVEVUTUJFR0ExVUUKQXhNS2EzVmlaWEp1WlhSbGN6QWVGdzB5TmpBNE1qa3dOekE1TWpSYUZ3MHlOekE0TWprd056RTBNalJhTUR3eApIekFkQmdOVkJBb1RGbXQxWW1WaFpHMDZZMngxYzNSbGNpMWhaRzFwYm5NeEdUQVhCZ05WQkFNVEVHdDFZbVZ5CmJtVjBaWE10WVdSdGFXNHdnZ0VpTUEwR0NTcUdTSWIzRFFFQkFRVUFBNElCRHdBd2dnRUtBb0lCQVFDYWlyNmYKcEc1MFQ3THJCRTIxZG03b0FjNUwwSStySTBQUlNxc29qWlJqdG5TekV6ZHo2cWVoYy9WL0x6bkJPbnZzVEhreQplMnpTS2R5azZpS0psSDNOcFR5eG9KQUdqN0JiQzB1eWRUR21SZytBQzl0UEhNNkcxa1hDMjdrYWdmMGNkNHpFCk45Q2RFci9wZjFtVk9vOWY3dXA2QXRDWktwaGhTRTRGLzhad3JwaHlyeEk1cUpKR25ZUk1kMjRYc21tTTFDRnoKbjZBT1dHWkxHTHFNcFlKbjdOUTlsM2Q2TjFyRCtNNitlOTN2Q01zelllWmdyOUZxUGR0czRsUmx3TUZxRmV4QQoxRmw0QmZoL1MxMG5ja1IrS01RUFk4b3JCdDRKRCtwdmZUYnBRakVFeElNcllHdndzb05uVWp5bWowc2VadndiCjUvUklNaXI2Wk9zdGZVNDlBZ01CQUFHalZqQlVNQTRHQTFVZER3RUIvd1FFQXdJRm9EQVRCZ05WSFNVRUREQUsKQmdnckJnRUZCUWNEQWpBTUJnTlZIUk1CQWY4RUFqQUFNQjhHQTFVZEl3UVlNQmFBRkFwbHovUmh0enhMU0ZIYgpRVkk0UVdBSTVLQTNNQTBHQ1NxR1NJYjNEUUVCQ3dVQUE0SUJBUUJMazBJMVNaeVd0T3BYTHNXb1BVZVUvZEZDCmlyalk3SWlGMjVpWTZwMHdNZzdCNlg1T3MwY05rQlB1RVM2UmpUbEN2YkZ6NlQzMW5aY01qV2VMdHBweStYd2EKQjNRaGRzTFVmT1R2VGxOMHU4dnBSOUZCTnJiODBTVEF3TDhvZmlEakxYOEF4NnNVSE9GNFZoOGkwTkxsV3JwYgphODFJMkxZeHg2R2cyeWpoSXNZeEY1RVlKeGh1QThSa0JJWEVpeElwb1VsRjRIV2RTNDlxbGliS2Raem8rRnh4CkYvUkJsc1VQOEQ1dFdPamdQS2RwNGMrNGpmYXpDbENBNGxkbXU5YlNBZFhDTTBOSmltc1dDMjZtUS9XbE5xKzIKSm5uRkhoTDNGOHFmVlR4LzV6SGlrZUpLalFwcDhHaVEvbmZQKzBHL3U4ZTFwNlBwM29iTE1GUGM3YnFHCi0tLS0tRU5EIENFUlRJRklDQVRFLS0tLS0K
                    client-key-data: LS0tLS1CRUdJTiBSU0EgUFJJVkFURSBLRVktLS0tLQpNSUlFcEFJQkFBS0NBUUVBbW9xK242UnVkRSt5NndSTnRYWnU2QUhPUzlDUHF5TkQwVXFyS0kyVVk3WjBzeE0zCmMrcW5vWFAxZnk4NXdUcDc3RXg1TW50czBpbmNwT29paVpSOXphVThzYUNRQm8rd1d3dExzblV4cGtZUGdBdmIKVHh6T2h0WkZ3dHU1R29IOUhIZU14RGZRblJLLzZYOVpsVHFQWCs3cWVnTFFtU3FZWVVoT0JmL0djSzZZY3E4UwpPYWlTUnAyRVRIZHVGN0pwak5RaGM1K2dEbGhtU3hpNmpLV0NaK3pVUFpkM2VqZGF3L2pPdm52ZDd3akxNMkhtCllLL1JhajNiYk9KVVpjREJhaFhzUU5SWmVBWDRmMHRkSjNKRWZpakVEMlBLS3diZUNRL3FiMzAyNlVJeEJNU0QKSzJCcjhMS0RaMUk4cG85TEhtYjhHK2YwU0RJcSttVHJMWDFPUFFJREFRQUJBb0lCQUFHRVlDbjRDUTFEWlFkMgpCZjlFaWdMbE1SSlRVUWJodEdMTXpWbkVXWGVrUTlHNmdLTHRtcEppclloYTRkUU1SdHVWb1hXRjZJUHRwa1ovClVRMmZhbGErSFUxOHp4aVNpVCtZSUpRdCtqTURCY1hKenQyeW1IdVNoVmp3a1dSbmNHcE5EZHNRc09zVUhFRjQKNkRjblVEcVRPRFI1dFZBWUx1RjZ2bkhveGoxdHBFeXkreGJQNStvN3pOZkNLTzdWN200cmxOY3Vxa1Z6TXlBMwpvVHcyQm92ZmxHWmdoVjkzcHpCcVFySWIvUGdPS0JQNGRubHVwVU5QVzNMZzlXa2ltT2RZVVp1K2VEWi9odVF6CnBpTytNcWEzb1pOczBvV0JEZ0YxUGdTSHdHMDNUdzlyaHFSMjViWS94YWVnb1dkRkFTVUhlZWFTRlo4QkZLeTEKbkJUcVM1OENnWUVBd0E1clo3WHgyeXF3cXBpSHFEY1pUQ1gzWVBUbE5SKzJvZDE3L0RCUHNXOFVkTjdzenBqVQo4b1RhWUd1eUpDNGVWUGNMSy9GU0txcEk5Tm5MRjgwSS8rQm5yYUV1cDRXcTh5dWxuUzVlNzhVcnhZWllnUXY1CkRnNEI2a3pWSGhieFhsNmtmdHJNeFNiNWUrZFB4bmhWWVN4YnFuMDdmT2tWZUhVb2VWMTBGRnNDZ1lFQXpmN2IKQTVaVkZGWHZVamY4V0VFUGg3ZS9ZeW95cWFTVEtBMlZ6SXpHM1BwaS9rU1Q5dWVEWm0zSHE1ak41cVRCbUNaYQpJZWZiZE0wUmdSa2prZzI2dVpMNHM0Y3prWEhEUXRPejAwSUQycjFBd0Fwbi9md2UrY0dvRm5QQWgyVzBuZy9TClppWDBzMUlnd0pIR0ZJK3FqcXIvUkduSkgvNlEvTHE2UFYwUFMwY0NnWUVBZ01mcWUwT0Vab3JNcGNmYmh3OVQKTmoxckVLZUdIa1lpcDQwbGV5aEY5OXRkSXpUc0kyaFZ1ajBkVlhSQm9vTU9NYmpwMDlCZ1lWMkh5QzlXUUtKcQp1M0VVOUZkVk9sMm1FS0tlemJQdlV1V3FnU3Vob3Y2TlJVQmRSbWU0ckJHRVRkdzJmeDFtRzNrVDUySm1tdFhMCm9ub2xDVUFxS0lyK3E2UWJ2SzNPaldzQ2dZRUFqak9GaXpkbTZnL2NrcHhWVmJNci9sdHBBaEtxUUpOOGhtVEMKYVI4eFZIQ0FqOWhWY0ZjblRUa3hZaEtzNkxBSmVIZlpOcGl3MVRPUEJ0aU14cEUxdWs5cjRick9iWFBrQUNqVwpkVnFZQUpNdXRLTTZZSWwvOUplcjZqdmp4MVA5V0RQQjRZUUlKUlhueUNlWlhxNUNaaFJDL1Q0dGVpbW9ldDZJCkhENDJoWmNDZ1lBSU1CNjcrUE5URlF0ck1BVWRZdHlxNWJlV05nMnVnbWFRZE1HQnd4RXF3SVlEeG9xY3laSm0KMUhJSnA3bmNJTFlNSGJXTXUwQ3BOMFIwV0M1ZWp3NGtXMlhWOUtuZDFBU0pMYW5ndEZaSW1JaTlDVVpPMkt5bQo0V1VQN3VKTHZWVWVpeXlHSFFMZFJKeXhTWWNlVndWUUZEOURUVlJYdEdaekJubnlMRXd4eUE9PQotLS0tLUVORCBSU0EgUFJJVkFURSBLRVktLS0tLQo=""";
        String encrypt = AESUtils.encrypt(config, "daXs1znnIStfQCVFyC8cvuS9OQZRTgeBJLLrrvu/hUM=");
        System.out.println(encrypt);
    }
}
