package com.coding.platformapi.credential;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.utils.AESUtils;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 凭据密文的加解密与指纹。设计见 docs/superpowers/specs/2026-10-13-credential-management-design.md §3。
 *
 * <h3>复用算法，不复用主密钥</h3>
 * 算法复用 {@link AESUtils}（kubeconfig 的先例），但**主密钥是独立配的**
 * （{@code platform.credential.aes-key}）。理由：轮换边界独立；且不扩散
 * {@code k8s.cloud.kubeconfig.aes-key} 那个把默认密钥硬编码在 application.yaml 里的坏味道。
 *
 * <h3>为什么密钥缺失要拒绝启动</h3>
 * 有默认值的话，「忘了配环境变量」会**静默地**用那把公开的默认密钥加密真实凭据 ——
 * 事后无法察觉，因为一切看起来都正常。这里取 fail-closed。
 *
 * <h3>为什么密文带 v1: 前缀</h3>
 * {@link AESUtils} 自身没有版本概念。加前缀是零成本，而将来换算法时能识别旧密文。
 * 遇到不认识的前缀**必须抛**，绝不静默当明文或按 v1 硬解 —— 那会把「密钥配错了」
 * 伪装成「凭据错了」，把排查引到完全错的方向。
 */
@Slf4j
@Component
public class CredentialCipher {

    static final String PREFIX_V1 = "v1:";

    /** 指纹取 SHA-256 的前 16 位 hex（8 字节） */
    static final int FINGERPRINT_CHARS = 16;

    private final String aesKey;

    public CredentialCipher(@Value("${platform.credential.aes-key:}") String aesKey) {
        this.aesKey = aesKey;
    }

    /**
     * 启动期校验。方法名刻意不叫 init 之类，就是为了让单测能直接调它而不起 Spring 上下文。
     */
    @PostConstruct
    public void afterPropertiesSet() {
        if (!StringUtils.hasText(aesKey)) {
            throw new IllegalStateException(
                    "凭据加密主密钥未配置：请设置 platform.credential.aes-key（环境变量 CREDENTIAL_AES_KEY）。"
                  + "可用 AESUtils.generateStrongKey() 生成一把。"
                  + "本模块有意不提供默认密钥 —— 否则忘配时会静默地用公开默认值加密真实凭据。");
        }
    }

    /** 加密认证字段 JSON → {@code "v1:" + base64} */
    public String encrypt(String plainJson) {
        return PREFIX_V1 + AESUtils.encrypt(plainJson, aesKey);
    }

    /** 解密；前缀不认识或密钥不对都抛，不降级。 */
    public String decrypt(String stored) {
        if (!StringUtils.hasText(stored)) {
            throw new CloudPlatformException(EnumResponseType.CREDENTIAL_CIPHERTEXT_INVALID,
                    "凭据密文为空");
        }
        if (!stored.startsWith(PREFIX_V1)) {
            throw new CloudPlatformException(EnumResponseType.CREDENTIAL_CIPHERTEXT_INVALID,
                    "凭据密文缺少 " + PREFIX_V1 + " 前缀，拒绝按旧格式猜测");
        }
        try {
            return AESUtils.decrypt(stored.substring(PREFIX_V1.length()), aesKey);
        } catch (RuntimeException e) {
            // AESUtils 抛的是 RuntimeException("数据解密失败…")：转成业务码，别让它冒成 500
            throw new CloudPlatformException(EnumResponseType.CREDENTIAL_CIPHERTEXT_INVALID,
                    "凭据解密失败（密文被篡改，或主密钥与写入时不一致）");
        }
    }

    /**
     * 指纹 = SHA-256(明文 JSON) 前 16 位 hex。
     *
     * <p><b>取明文而非密文</b>：{@link AESUtils} 每次加密用随机 salt + IV，密文每次都不同，
     * 拿密文做指纹毫无意义。指纹的用途是区分「改了凭据」与「只改了描述」。
     */
    public static String fingerprint(String plainJson) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(plainJson.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(FINGERPRINT_CHARS);
            for (int i = 0; i < FINGERPRINT_CHARS / 2; i++) {
                sb.append(String.format("%02x", digest[i]));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
