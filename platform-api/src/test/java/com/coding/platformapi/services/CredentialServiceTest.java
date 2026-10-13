package com.coding.platformapi.services;

import com.coding.common.exception.CloudPlatformException;
import com.coding.data.mapper.system.PlatformCredentialMapper;
import com.coding.data.models.system.PlatformCredential;
import com.coding.platformapi.credential.CredentialCipher;
import com.coding.platformapi.credential.CredentialProbeClient;
import com.coding.platformapi.credential.CredentialSecret;
import com.coding.platformapi.credential.ProbeResult;
import com.coding.platformapi.models.CredentialCreateRequest;
import com.coding.platformapi.models.CredentialTestRequest;
import com.coding.platformapi.models.CredentialUpdateRequest;
import com.coding.platformapi.models.CredentialView;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link CredentialService}：脱敏契约、留空=保持不变、写回测试结果。
 *
 * <p>外加一条**结构性钉子**（{@link #viewDtoHasNoCredentialField()}）—— 见其 javadoc。
 */
class CredentialServiceTest {

    private static final String KEY = "test-only-master-key-0123456789abcdef";

    PlatformCredentialMapper mapper = mock(PlatformCredentialMapper.class);
    CredentialProbeClient probeClient = mock(CredentialProbeClient.class);
    CredentialCipher cipher = new CredentialCipher(KEY);
    CredentialService svc = new CredentialService(mapper, cipher, probeClient);

    private PlatformCredential stored(String id, String name, String type, String endpoint,
                                     String username, String secret) {
        PlatformCredential c = new PlatformCredential();
        c.setId(id);
        c.setName(name);
        c.setType(type);
        c.setEndpoint(endpoint);
        c.setAuthType("BASIC");
        String json = "{\"username\":\"" + username + "\",\"secret\":\"" + secret + "\"}";
        c.setSecretEncrypted(cipher.encrypt(json));
        c.setSecretFingerprint(CredentialCipher.fingerprint(json));
        c.setEnabled(1);
        return c;
    }

    // ---- 脱敏 ----

    @Test
    void listNeverExposesSecretOrUsername() {
        when(mapper.selectAll()).thenReturn(List.of(
                stored("c1", "harbor-prod", "REGISTRY", "harbor.example.com", "robot$platform+ci", "s3cr3t")));

        CredentialView v = svc.list().get(0);

        assertThat(v.getHasSecret()).isTrue();
        assertThat(v.getFingerprint()).hasSize(16);
        assertThat(v.getEndpoint()).isEqualTo("harbor.example.com");
        // 指纹不能等于任何形式的明文
        assertThat(v.getFingerprint()).doesNotContain("s3cr3t").doesNotContain("robot");
    }

    @Test
    void createResponseIsAlsoRedacted() {
        when(mapper.selectByName(anyString())).thenReturn(null);
        when(mapper.insert(any())).thenReturn(1);

        CredentialCreateRequest req = new CredentialCreateRequest();
        req.setName("gitlab");
        req.setType("GIT");
        req.setEndpoint("https://gitlab.example.com");
        req.setAuthType("BASIC");
        req.setUsername("ci-bot");
        req.setSecret("glpat-xxxxxxxxxxxx");

        CredentialView v = svc.create(req);

        assertThat(v.getHasSecret()).isTrue();
        assertThat(v.getFingerprint()).hasSize(16);
        // 响应对象上根本没有承载明文的字段可断言 —— 由下面那条反射钉子保证
    }

    @Test
    void storedCiphertextIsNotThePlaintext() {
        when(mapper.selectByName(anyString())).thenReturn(null);
        when(mapper.insert(any())).thenReturn(1);

        CredentialCreateRequest req = new CredentialCreateRequest();
        req.setName("gitlab");
        req.setType("GIT");
        req.setEndpoint("https://gitlab.example.com");
        req.setAuthType("BASIC");
        req.setUsername("ci-bot");
        req.setSecret("glpat-xxxxxxxxxxxx");

        svc.create(req);

        var captor = org.mockito.ArgumentCaptor.forClass(PlatformCredential.class);
        verify(mapper).insert(captor.capture());
        PlatformCredential written = captor.getValue();
        assertThat(written.getSecretEncrypted()).startsWith("v1:").doesNotContain("glpat-xxxxxxxxxxxx");
        // 落库后仍能解回原文（这是本批唯一一条「加密真的能往返」的端到端证据）
        assertThat(cipher.decrypt(written.getSecretEncrypted())).contains("glpat-xxxxxxxxxxxx");
    }

    // ---- 参数校验 ----

    @Test
    void createRejectsUnknownType() {
        CredentialCreateRequest req = new CredentialCreateRequest();
        req.setName("x");
        req.setType("SFTP");
        req.setEndpoint("h");
        req.setAuthType("BASIC");
        req.setUsername("u");
        req.setSecret("s");

        assertThatThrownBy(() -> svc.create(req)).isInstanceOf(CloudPlatformException.class);
    }

    @Test
    void createRejectsBlankSecret() {
        CredentialCreateRequest req = new CredentialCreateRequest();
        req.setName("x");
        req.setType("GIT");
        req.setEndpoint("https://g.example.com/r.git");
        req.setAuthType("BASIC");
        req.setUsername("u");
        req.setSecret("   ");

        assertThatThrownBy(() -> svc.create(req)).isInstanceOf(CloudPlatformException.class);
    }

    @Test
    void createRejectsDuplicateName() {
        when(mapper.selectByName("dup")).thenReturn(stored("other", "dup", "GIT", "h", "u", "s"));

        CredentialCreateRequest req = new CredentialCreateRequest();
        req.setName("dup");
        req.setType("GIT");
        req.setEndpoint("https://g.example.com/r.git");
        req.setAuthType("BASIC");
        req.setUsername("u");
        req.setSecret("s");

        assertThatThrownBy(() -> svc.create(req)).isInstanceOf(CloudPlatformException.class);
        verify(mapper, never()).insert(any());
    }

    // ---- 留空 = 保持不变 ----

    @Test
    void updateWithBlankSecretKeepsOldCiphertext() {
        PlatformCredential old = stored("c1", "gitlab", "GIT", "https://g.example.com",
                "ci-bot", "old-secret");
        String oldCipher = old.getSecretEncrypted();
        when(mapper.selectById("c1")).thenReturn(old);

        CredentialUpdateRequest req = new CredentialUpdateRequest();
        req.setId("c1");
        req.setName("gitlab");
        req.setType("GIT");
        req.setEndpoint("https://g.example.com");
        req.setAuthType("BASIC");
        req.setDescription("改了描述");
        req.setEnabled(true);

        svc.update(req);

        var captor = org.mockito.ArgumentCaptor.forClass(PlatformCredential.class);
        verify(mapper).update(captor.capture());
        assertThat(captor.getValue().getSecretEncrypted()).isEqualTo(oldCipher);
        assertThat(captor.getValue().getDescription()).isEqualTo("改了描述");
    }

    @Test
    void updateWithNewSecretRotatesIt() {
        PlatformCredential old = stored("c1", "gitlab", "GIT", "https://g.example.com",
                "ci-bot", "old-secret");
        String oldFingerprint = old.getSecretFingerprint();
        when(mapper.selectById("c1")).thenReturn(old);

        CredentialUpdateRequest req = new CredentialUpdateRequest();
        req.setId("c1");
        req.setName("gitlab");
        req.setType("GIT");
        req.setEndpoint("https://g.example.com");
        req.setAuthType("BASIC");
        req.setSecret("new-secret");
        req.setEnabled(true);

        svc.update(req);

        var captor = org.mockito.ArgumentCaptor.forClass(PlatformCredential.class);
        verify(mapper).update(captor.capture());
        assertThat(captor.getValue().getSecretFingerprint()).isNotEqualTo(oldFingerprint);
        assertThat(cipher.decrypt(captor.getValue().getSecretEncrypted())).contains("new-secret");
    }

    @Test
    void updateUnknownIdFails() {
        when(mapper.selectById("nope")).thenReturn(null);

        CredentialUpdateRequest req = new CredentialUpdateRequest();
        req.setId("nope");

        assertThatThrownBy(() -> svc.update(req)).isInstanceOf(CloudPlatformException.class);
    }

    @Test
    void deleteUnknownIdFails() {
        when(mapper.selectById("nope")).thenReturn(null);

        assertThatThrownBy(() -> svc.delete("nope")).isInstanceOf(CloudPlatformException.class);
        verify(mapper, never()).deleteById(anyString());
    }

    // ---- 测试连接 ----

    @Test
    void testGitUsesDecryptedCredentialAndProbeUrl() {
        when(mapper.selectById("c1")).thenReturn(
                stored("c1", "gitlab", "GIT", "https://g.example.com", "ci-bot", "tok"));
        when(probeClient.probeGit(eq("https://g.example.com/r.git"), eq("ci-bot"), eq("tok")))
                .thenReturn(ProbeResult.pass());

        CredentialTestRequest req = new CredentialTestRequest();
        req.setId("c1");
        req.setProbeUrl("https://g.example.com/r.git");

        assertThat(svc.test(req).getOk()).isTrue();
        verify(mapper).updateVerifyResult(eq("c1"), eq(true), any(), any(Date.class));
    }

    @Test
    void testGitRequiresProbeUrl() {
        when(mapper.selectById("c1")).thenReturn(
                stored("c1", "gitlab", "GIT", "https://g.example.com", "ci-bot", "tok"));

        CredentialTestRequest req = new CredentialTestRequest();
        req.setId("c1");

        assertThatThrownBy(() -> svc.test(req)).isInstanceOf(CloudPlatformException.class);
        verify(probeClient, never()).probeGit(anyString(), anyString(), anyString());
    }

    @Test
    void testRegistryDoesNotNeedProbeUrl() {
        when(mapper.selectById("c1")).thenReturn(
                stored("c1", "harbor", "REGISTRY", "harbor.example.com", "robot", "pw"));
        when(probeClient.probeRegistry("harbor.example.com", "robot", "pw"))
                .thenReturn(ProbeResult.fail("凭据不通过，或该凭据无权访问此 registry"));

        CredentialTestRequest req = new CredentialTestRequest();
        req.setId("c1");

        var result = svc.test(req);

        assertThat(result.getOk()).isFalse();
        assertThat(result.getMessage()).doesNotContain("密码");
        verify(mapper).updateVerifyResult(eq("c1"), eq(false), anyString(), any(Date.class));
    }

    @Test
    void testFailureKeepsLastVerifiedAt() {
        PlatformCredential row = stored("c1", "harbor", "REGISTRY", "harbor.example.com", "r", "pw");
        row.setLastVerifiedAt(new Date(1_700_000_000_000L));
        when(mapper.selectById("c1")).thenReturn(row);
        when(probeClient.probeRegistry(anyString(), anyString(), anyString()))
                .thenReturn(ProbeResult.fail("凭据不通过"));

        CredentialTestRequest req = new CredentialTestRequest();
        req.setId("c1");
        svc.test(req);

        // ok=false 的写回不碰 last_verified_at（那条 SQL 走 otherwise 分支）
        verify(mapper).updateVerifyResult(eq("c1"), eq(false), anyString(), any(Date.class));
    }

    // ---- resolveSecret（下游消费方的入口，§11） ----

    @Test
    void resolveSecretReturnsPlaintextForConsumers() {
        when(mapper.selectById("c1")).thenReturn(
                stored("c1", "gitlab", "GIT", "https://g.example.com", "ci-bot", "tok"));

        CredentialSecret s = svc.resolveSecret("c1");

        assertThat(s.getUsername()).isEqualTo("ci-bot");
        assertThat(s.getSecret()).isEqualTo("tok");
    }

    // ---- 结构性钉子 ----

    /**
     * ⚠️ **结构性钉子**，不是行为测试。
     *
     * <p>「永不回显」这条契约无法靠逐个断言守住 —— 它是**否定性**的（「字段不存在」），
     * 而将来有人给 {@link CredentialView} 加一个 {@code username} 字段时，
     * 既有的断言全都还是绿的。所以这里改成反射扫描：**任何名字含 secret / password / username
     * 的字段都会让本测试失败**。
     *
     * <p><b>唯一的例外是 {@code Boolean hasSecret}</b> —— 它是「是否已配置」的标志，不是内容，
     * 而本设计正是靠它来代替「回显用户名」。判据里带上类型，是为了让「改个名塞字符串」
     * 也挡得住（例如有人把明文放进一个 String 版的 hasSecret）。
     *
     * <p>本测试**能**挡住的回归：给响应 DTO 加了承载敏感信息的字段。
     *
     * <p>本测试**挡不住**的：把明文塞进一个完全改名的普通字段（如 {@code extra}）——
     * 那只能靠 code review。
     */
    @Test
    void viewDtoHasNoCredentialField() {
        List<String> banned = List.of("secret", "password", "passwd", "username", "credential");

        List<String> offenders = Arrays.stream(CredentialView.class.getDeclaredFields())
                .filter(f -> !Modifier.isStatic(f.getModifiers()))
                .filter(f -> banned.stream().anyMatch(f.getName().toLowerCase(Locale.ROOT)::contains))
                .filter(f -> !("hasSecret".equalsIgnoreCase(f.getName()) && f.getType() == Boolean.class))
                .map(Field::getName)
                .toList();

        assertThat(offenders)
                .as("CredentialView 不得出现承载凭据内容的字段（spec「永不回显」）；"
                  + "确实需要新增派生字段时，改的是本条测试的 banned 列表，不是加字段了事")
                .isEmpty();
    }
}
