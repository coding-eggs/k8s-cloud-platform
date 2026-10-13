package com.coding.platformapi.credential;

import com.coding.common.exception.CloudPlatformException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link CredentialCipher}：加密往返、版本前缀、指纹、主密钥缺失的 fail-closed。
 *
 * <p>主密钥是**独立于 kubeconfig 那把**的（spec §3.1），所以本测试全部用测试专用密钥，不读配置。
 */
class CredentialCipherTest {

    private static final String KEY = "test-only-master-key-0123456789abcdef";

    CredentialCipher cipher = new CredentialCipher(KEY);

    @Test
    void encryptThenDecryptRoundTrips() {
        String plain = "{\"username\":\"robot$platform+ci\",\"secret\":\"s3cr3t\"}";

        assertThat(cipher.decrypt(cipher.encrypt(plain))).isEqualTo(plain);
    }

    @Test
    void ciphertextCarriesV1Prefix() {
        assertThat(cipher.encrypt("{\"secret\":\"x\"}")).startsWith("v1:");
    }

    @Test
    void samePlaintextEncryptsDifferentlyEachTime() {
        String plain = "{\"secret\":\"x\"}";

        // AESUtils 每次随机 salt + IV —— 指纹必须取明文，否则完全没有意义（spec §2.3）
        assertThat(cipher.encrypt(plain)).isNotEqualTo(cipher.encrypt(plain));
    }

    @Test
    void decryptRejectsUnknownPrefixInsteadOfGuessing() {
        // 静默降级会把「密钥配错了」伪装成「凭据错了」（spec §3.2）
        assertThatThrownBy(() -> cipher.decrypt("v9:whatever"))
                .isInstanceOf(CloudPlatformException.class);
        assertThatThrownBy(() -> cipher.decrypt("plaintext-without-prefix"))
                .isInstanceOf(CloudPlatformException.class);
    }

    @Test
    void decryptWithWrongKeyNeverYieldsThePlaintext() {
        String plain = "{\"secret\":\"x\"}";
        String stored = cipher.encrypt(plain);
        CredentialCipher other = new CredentialCipher("a-totally-different-master-key");

        // 不能写成 assertThatThrownBy：AES-CBC + PKCS5 下，错密钥解出的填充**有约 1/256 的概率碰巧合法**，
        // 那时 decrypt 会返回垃圾而不抛 —— 断言「必抛」就是一条约 0.4% 概率假红的 flaky 测试。
        // 真正要钉的不变量是「错密钥绝不静默地解出原文」，这条对两种分支都成立、且完全确定。
        try {
            assertThat(other.decrypt(stored)).isNotEqualTo(plain);
        } catch (CloudPlatformException expected) {
            // 常见分支：填充校验失败 → 转成业务码。这里吞掉是**刻意的**，见上面的注释。
        }
    }

    @Test
    void fingerprintIsStableAndIndependentOfAesRandomness() {
        String plain = "{\"username\":\"u\",\"secret\":\"s\"}";

        assertThat(CredentialCipher.fingerprint(plain))
                .hasSize(16)
                .isEqualTo(CredentialCipher.fingerprint(plain));
        assertThat(CredentialCipher.fingerprint(plain))
                .isNotEqualTo(CredentialCipher.fingerprint("{\"username\":\"u\",\"secret\":\"t\"}"));
    }

    @Test
    void blankOrMissingMasterKeyRefusesToStart() {
        // 关键回归：不能像 kubeconfig 那把密钥一样有硬编码默认值（spec §3.3）——
        // 否则「忘了配环境变量」会静默地用公开默认值加密真实凭据。
        assertThatThrownBy(() -> new CredentialCipher("").afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("platform.credential.aes-key");
        assertThatThrownBy(() -> new CredentialCipher(null).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void presentMasterKeyStarts() {
        assertThatCode(() -> new CredentialCipher(KEY).afterPropertiesSet()).doesNotThrowAnyException();
    }
}
