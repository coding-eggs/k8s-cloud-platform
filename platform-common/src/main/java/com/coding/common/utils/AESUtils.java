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
                    certificate-authority-data: LS0tLS1CRUdJTiBDRVJUSUZJQ0FURS0tLS0tCk1JSURCVENDQWUyZ0F3SUJBZ0lJUmh3Y0lpbGVnQ3N3RFFZSktvWklodmNOQVFFTEJRQXdGVEVUTUJFR0ExVUUKQXhNS2EzVmlaWEp1WlhSbGN6QWVGdzB5TmpBNU1ERXdPVFF4TkROYUZ3MHpOakE0TWprd09UUTJORE5hTUJVeApFekFSQmdOVkJBTVRDbXQxWW1WeWJtVjBaWE13Z2dFaU1BMEdDU3FHU0liM0RRRUJBUVVBQTRJQkR3QXdnZ0VLCkFvSUJBUUR0TVNMcC9oamhVZlUwZk4wOURvNGp3YURSRUJHYTNDaDRSWHVDeGxVRVpqRWZYRzFvcVUrbkw4K0UKNmNabW4rUHRIMlYwVXVXZGNqdTE4ZnloRGJGQnJVNlRqWnZJVFhrV2d0dDFyQW02VXJEM1NYRlJvTGpGbGtuUwpFVENIZnpmRCtOdXZnRzBHdWd5ZGtHellSWno4alV2U0VadGtDczc0amxzWGR4c0Eydzc4MFFiSXZiWGFDVTNsCjFFSkczdmJlbTJkMjRQcW9BSy92SDNxZ2g1by84VWJvK1ZVUTc3RHNYUUJCVnJjWVdFZUdLbHhPNzh0UDdEeHAKZllDVzlWbmtXSmZXL1lUV3IveEFKNVk1dVhOZVVUSllTY1NSWEhia3pIUWJOcUdrU01makFPNVQvVDluRmtvSQpuTnhqQzFvbEtTWWRaY1lmdXhxYzBqblFjeGg1QWdNQkFBR2pXVEJYTUE0R0ExVWREd0VCL3dRRUF3SUNwREFQCkJnTlZIUk1CQWY4RUJUQURBUUgvTUIwR0ExVWREZ1FXQkJSNmhRVzNmNytuOWRYN2l6RHFpRVNGN2lmVVdEQVYKQmdOVkhSRUVEakFNZ2dwcmRXSmxjbTVsZEdWek1BMEdDU3FHU0liM0RRRUJDd1VBQTRJQkFRRGZ4NzM0UE8zWAozeXZaMWZVSkY2MlorVFB3ek9CMDF6NlZ0TTRXRm9LU1JpcGo0cit5bUtyRUdFd1RTeExBWCtraSs3d3RYcXhGCmF6ajF6M2ZPQlFiVzRpTDFQY3V2a2dpZy80dGhQM1grbHVNRFp5R3ltQnlYbFNnV0svcGo0NDBGS0FPQktpaTcKQzU1UGtYUGFNRW1YUXd0WG9YWk56cVE0RncxWG9JdXBXZGJzZlZNY0RzOTJyS0FRb2FxT1NBOG9aYVMyTXJyeQptdTBPY3lqVmQ0ZUxSYWdsSTR3b2xuV0kvTDVaczM3QkNrTW1rZXM2TkdOaW52NWpyWHZDdytNWm1QLzhrVC9TCkdPOWRxaWlVOG5tU1IzN2NqOUVYeVFlT0xkcFpBZTMyWnQ5UkxhQzNGRWdhR2J6aFhqbGdMZ29CdFBQSDMzL00KS3Z5VTdzNUZacmUwCi0tLS0tRU5EIENFUlRJRklDQVRFLS0tLS0K
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
                    client-certificate-data: LS0tLS1CRUdJTiBDRVJUSUZJQ0FURS0tLS0tCk1JSURLVENDQWhHZ0F3SUJBZ0lJQ1dHWDdpRk9RaFF3RFFZSktvWklodmNOQVFFTEJRQXdGVEVUTUJFR0ExVUUKQXhNS2EzVmlaWEp1WlhSbGN6QWVGdzB5TmpBNU1ERXdPVFF4TkROYUZ3MHlOekE1TURFd09UUTJORE5hTUR3eApIekFkQmdOVkJBb1RGbXQxWW1WaFpHMDZZMngxYzNSbGNpMWhaRzFwYm5NeEdUQVhCZ05WQkFNVEVHdDFZbVZ5CmJtVjBaWE10WVdSdGFXNHdnZ0VpTUEwR0NTcUdTSWIzRFFFQkFRVUFBNElCRHdBd2dnRUtBb0lCQVFDdjZ0UVUKSnk3bUdjWmg4UGZnVXFYbnNQVDRCS3NvbUJKTXM1cC9HaEE0OG53SlRkd2QvQnRRQnZYUUdDUXVvN3F3UmNEQQpSRUpWTU9EU3lMbEdlTEpkdjVJeDFtc2UzeXBDS0dUNWptYXpmMFFVNGJNRVFIaWhBQUt1UHdpbmhWMjUyRStMCkFQWlhKY04zYnJYTUxVMTBhb1l2QmpFV1JZcGN5RjI1TnRrUEFHUnRQdTkvazVaVHREMXlZMnhIcldKL3l2SVIKemRwT0VLbFZXTExwaEhmMXlOdGx5UWVoQVo0b0FkdTJjenJkYXdsQ2lyRkdtd1E0Z0N2SEFYbjI4a0FRM1I5TQpSazU5aDF6YXFhcy80SWRaQkJXYU55UUEweHpSVGZMa3FQdDIvQlNzNUdsRGl2NHdEZ0NLclJHY3FBcUNkdTNYCkxVaHhwRU10QkV4RlJzaXJBZ01CQUFHalZqQlVNQTRHQTFVZER3RUIvd1FFQXdJRm9EQVRCZ05WSFNVRUREQUsKQmdnckJnRUZCUWNEQWpBTUJnTlZIUk1CQWY4RUFqQUFNQjhHQTFVZEl3UVlNQmFBRkhxRkJiZC92NmYxMWZ1TApNT3FJUklYdUo5UllNQTBHQ1NxR1NJYjNEUUVCQ3dVQUE0SUJBUUJ1VFBlMVVtZm5IcGFLYktRcGt0eWJEdVNvCmtiQi9Kb0lMclE0TmZoeXZnZUt0L3Nsc1pObjN2Rlp6dkUrbHJtdXg1U3JEeTVvUjNFMmE1TDVlOFduQVZ4WE4Kd2RwUkUrZzdId1VHKzYySHRPMFpXeFBRbURocE1vMkFXQy92MnprTHkyS0lPQnRQMHMrY0E0TTBaSlhmNGFWRwpnV1gwcFc4cXNDNVRRL3E2cnJWRE9tV1QzTWpHRE11aEMvQUV1S3FGVHpJZHR3SExobU9EWFdyRTUzbCthcjA5Ck5GMDJGOHFmZ2VRYXZoM0lQSVdwc29wMHJ4YjU2NHlrb29seDVqRlBya2FrNk85UFJoRmE5Rlh3N2laY0dTbGkKNll0Vms3WENaL0lQcWRwbE9CRURGTmpUTzlOYjhIbnliaE9XOGYwcEpPc1pLd0tSK0dZd3YrSkswQ1dUCi0tLS0tRU5EIENFUlRJRklDQVRFLS0tLS0K
                    client-key-data: LS0tLS1CRUdJTiBSU0EgUFJJVkFURSBLRVktLS0tLQpNSUlFcEFJQkFBS0NBUUVBcityVUZDY3U1aG5HWWZEMzRGS2w1N0QwK0FTcktKZ1NUTE9hZnhvUU9QSjhDVTNjCkhmd2JVQWIxMEJna0xxTzZzRVhBd0VSQ1ZURGcwc2k1Um5peVhiK1NNZFpySHQ4cVFpaGsrWTVtczM5RUZPR3oKQkVCNG9RQUNyajhJcDRWZHVkaFBpd0QyVnlYRGQyNjF6QzFOZEdxR0x3WXhGa1dLWE1oZHVUYlpEd0JrYlQ3dgpmNU9XVTdROWNtTnNSNjFpZjhyeUVjM2FUaENwVlZpeTZZUjM5Y2piWmNrSG9RR2VLQUhidG5NNjNXc0pRb3F4ClJwc0VPSUFyeHdGNTl2SkFFTjBmVEVaT2ZZZGMycW1yUCtDSFdRUVZtamNrQU5NYzBVM3k1S2o3ZHZ3VXJPUnAKUTRyK01BNEFpcTBSbktnS2duYnQxeTFJY2FSRExRUk1SVWJJcXdJREFRQUJBb0lCQUM5cmU1T2JKRmMrWjIyTApTU1ljNFQvZFFZdUJwcW1ncEg0c2c5N3pKYUJVTFA5TkQ2SzlqSmM3NlJNWkR0ZkxwczFSWUMzenVIWENZVGd3CmgxRHB4QnJsUXdGUUxUdjdLQ2NCUUliTXpmNWd0ZmR6QjVDRDJJQjl3anM2SHBrWnFEUXFqUGFKWWQ0SC9mSzUKMHlPZ3FwcFJCSzYwV3BNSVY4Mis3UlM2SmpTV2toSUZIaFRXWlFWcmEyekVXd04xR3ZSeGh0dXJRMFlpOFdTawo5dkc1Zlk0WEpSM05YZkR5RU5WNlkvK3ZTUmxwOE1lNHJBZUR0U2VEMjRGRE9vZFBDWENiZ1JGZjVvMXBTK2RPCkFNY2VCcFZ1RUVpdEpPTFM5U2o1QXExTzZBOG1HZWhScE9scHJIVlQ5b3UvUjhOcGN6UlBIRTVCek1jbU8rSzEKZm52YkhzRUNnWUVBMHF5OXhzVHdzZmhXQlIyRUs3eTBNYmMzTFhiNUswenA1VFMvc0hrc1Z1aVJmNkVTeXpwVwo3aDB6ajlyT2VtaE1Dek9FQ1ZCcFdqL2ZacHJnS0JwdGJWUWZzSmhQM3g0THRSSXpYSXczOHhWV0hQQ3BKVWJ6ClVua1pVZ0VodWtTa2hEczg4d0gxUEF4LzI4eFIxSDR6L3pKa2hOblNGRmVncXhHNHZxWnhZSEVDZ1lFQTFjUEQKSm5DcXNMYVpBME1xeUhGM29sQThiUGxsNWw2SGJieS9yWUxNdE1CSmlyUzA2QTdva1dLNFhVWjBGRXIrcktjTApzOTdsWnVybm5mRjFvZERKanVVZ3BLOGpOckdYaEdxQXpEeVBGRUg5UGV0TGJkK1ZFdy9kREp4dzQ3cHBTVHFCCjZZRFEwM1NybnNxNy9RN210OG9jaTRSRVdieGJtU0xsd0JhQnlOc0NnWUFMZ3U3WHRwa2tVRnA2NnhMMnhOZmYKdzVBYlJ0MzBsTDVQRE9QUWc3NTlmVFcrRUpJVFVydS9SUTgxTkJLR003NjcrZk9rQXFYUERhQnFYZG9UdHVYMQp6RnZ5N1UrbjlGOVZaSW96NjJGL1FkSXp3SjZ0YjhRSjVKNFNrZ2RDdzA5dC9rS2xVSjBTeStnTW5ZeDNIUEpBClpvT0JrdGxjelREMVNBR2RRVUQrc1FLQmdRQzNiSXBJc3NhVWFhcDBWUzhoM2RORThNcFl5OHYzVjhlbmp4MSsKV2swakVCaEtyL0xIYVB1QXZRL3I0YWQ3UGpxM2xZTUNMZEgwZEw2WUZYZlZpTjFiQXhyMmxOMkhjZTNNanNMRAovWGJjY0I5SUFWMnhBdmZjNm5ESUFIa1J2NFBXZFNEQ1oybEkrTVRHdGJtSFZFRC9GYXRXd0FFU2F3RENMMktyClorU25ud0tCZ1FDalVkb2xRT3JINTlmbGZ0bmhvN0tZT0V6bjZCVzNjcmtpbUxTMnZORWdyeTRTQ2ZSbVk1QkYKNzZySkNzVUl1dHdNaWFPZjNvbUNiMzBDVkZGNHRwcURZZmk2UUhvRXNtN1h3cGZvTng5MmhtMktHNlYyVGtIRQpsbnUwRGpKakdvQmVkemdjYzFwVVE5RmluUXdqVDVHdjZpZ0VNb1VCajJyYzhPOUJiQXdBY2c9PQotLS0tLUVORCBSU0EgUFJJVkFURSBLRVktLS0tLQo=""";
        String encrypt = AESUtils.encrypt(config, "daXs1znnIStfQCVFyC8cvuS9OQZRTgeBJLLrrvu/hUM=");
        System.out.println(encrypt);
    }
}
