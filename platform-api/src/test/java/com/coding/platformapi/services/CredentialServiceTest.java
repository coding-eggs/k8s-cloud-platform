package com.coding.platformapi.services;

import com.coding.common.exception.CloudPlatformException;
import com.coding.data.mapper.auth.PlatformTenantMapper;
import com.coding.data.mapper.system.PlatformCredentialMapper;
import com.coding.data.mapper.system.PlatformRegistryProjectMapper;
import com.coding.data.models.system.PlatformCredential;
import com.coding.platformapi.credential.CredentialCipher;
import com.coding.platformapi.credential.CredentialProbeClient;
import com.coding.platformapi.credential.CredentialSecret;
import com.coding.platformapi.credential.ProbeResult;
import com.coding.platformapi.models.CredentialCreateRequest;
import com.coding.platformapi.models.CredentialTestRequest;
import com.coding.platformapi.models.CredentialUpdateRequest;
import com.coding.platformapi.models.CredentialView;
import com.coding.platformapi.registry.CredentialRef;
import com.coding.platformapi.registry.DockerHubDialect;
import com.coding.platformapi.registry.HarborDialect;
import com.coding.platformapi.registry.RegistryDialectRegistry;
import com.coding.platformapi.registry.RegistryHttpClient;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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
import static org.mockito.ArgumentMatchers.isNull;
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
    PlatformTenantMapper tenantMapper = mock(PlatformTenantMapper.class);
    PlatformRegistryProjectMapper projectMapper = mock(PlatformRegistryProjectMapper.class);
    CredentialProbeClient probeClient = mock(CredentialProbeClient.class);
    CredentialCipher cipher = new CredentialCipher(KEY);
    /** 校验「该类型允许哪种认证方式」要问它 —— 见 CredentialService.allowedAuthTypes */
    RegistryDialectRegistry dialects = new RegistryDialectRegistry(
            List.of(new HarborDialect(mock(RegistryHttpClient.class)),
                    new DockerHubDialect(mock(RegistryHttpClient.class))));
    CredentialService svc = new CredentialService(mapper, cipher, probeClient, dialects, tenantMapper, projectMapper,
            mock(com.coding.platformapi.registry.HarborInstallLocator.class));

    /**
     * 平台建的 Harbor 凭据是**项目级** robot，项目名记录在镜像项目台账里。
     * 渲染方（镜像节点）要靠它把 {@code <host>/<仓库>} 补成 {@code <host>/<项目>/<仓库>} ——
     * 不补就是 2026-10-29 那次实报：推到 {@code harbor.x/k8s-server}，Harbor 当成项目 k8s-server，400。
     */
    @Test
    void resolvedPipelineConnectionCarriesItsHarborProject() {
        PlatformCredential c = stored("cred-1", "Harbor platform@c1", "HARBOR",
                "https://harbor.ackcp.cloud", "robot$platform+ci", "s");
        c.setUsableByPipelines(1);
        c.setSource("HARBOR");
        when(mapper.selectById("cred-1")).thenReturn(c);
        var project = new com.coding.data.models.system.PlatformRegistryProject();
        project.setCredentialId("cred-1");
        project.setProjectName("platform");
        when(projectMapper.selectAll()).thenReturn(List.of(project));

        var ref = svc.resolveForPipeline("cred-1", "HARBOR", null);

        assertThat(ref.project()).isEqualTo("platform");
        assertThat(ref.host()).isEqualTo("harbor.ackcp.cloud");   // 镜像地址要的是 host，不是 URL
    }

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

    /** 匿名的行：{@code auth_type=NONE} 且**两列密文都是 null**（这正是表注释预留的形态）。 */
    private PlatformCredential anonymousStored(String id, String name, String type, String endpoint) {
        PlatformCredential c = new PlatformCredential();
        c.setId(id);
        c.setName(name);
        c.setType(type);
        c.setEndpoint(endpoint);
        c.setAuthType("NONE");
        c.setEnabled(1);
        return c;
    }

    // ---- 脱敏 ----

    @Test
    void listNeverExposesSecretOrUsername() {
        when(mapper.selectAll()).thenReturn(List.of(
                stored("c1", "harbor-prod", "HARBOR", "harbor.example.com", "robot$platform+ci", "s3cr3t")));

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
    void createAcceptsHarborType() {
        when(mapper.selectByName(anyString())).thenReturn(null);
        when(mapper.insert(any())).thenReturn(1);

        CredentialCreateRequest req = new CredentialCreateRequest();
        req.setName("harbor-prod");
        req.setType("HARBOR");
        req.setEndpoint("harbor.example.com");
        req.setAuthType("BASIC");
        req.setUsername("robot$platform+ci");
        req.setSecret("pw");

        assertThat(svc.create(req).getType()).isEqualTo("HARBOR");
    }

    @Test
    void createRejectsTheRetiredRegistryType() {
        // REGISTRY 在 S3.1 里被拆成具体的方言值（本批只落 HARBOR）。
        // 旧库里若真有 REGISTRY 行，它会变成非法类型 —— 这是有意为之，不是漏了兼容。
        CredentialCreateRequest req = new CredentialCreateRequest();
        req.setName("x");
        req.setType("REGISTRY");
        req.setEndpoint("harbor.example.com");
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

    // ---- 测试连接走方言 ----

    @Test
    void registryProbeUrlComesFromTheDialectNotFromTheRawEndpoint() {
        // 传进去的必须是**拼好的 URL**，不是 endpoint —— 否则「哪些档的探测地址不是 <endpoint>/v2/」
        // 这件事就只能由 CredentialService 自己判 type 才知道，那是第二个真相源（spec §4）
        PlatformCredential c = stored("c1", "h", "HARBOR", "harbor.example.com", "u", "p");
        when(mapper.selectById("c1")).thenReturn(c);
        when(probeClient.probeRegistry(anyString(), any(), any(), any(), any())).thenReturn(ProbeResult.pass());

        CredentialTestRequest req = new CredentialTestRequest();
        req.setId("c1");
        svc.test(req);

        var url = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(probeClient).probeRegistry(url.capture(), any(), any(), any(), any());
        assertThat(url.getValue()).isEqualTo("https://harbor.example.com/v2/");
    }

    @Test
    void dockerHubPrivateIsACredentialTypeAgain() {
        // 2026-10-14：Docker Hub 改成内置档、从凭据类型里移除（只允许匿名）。
        // **2026-10-15 用户撤销**：私有 Docker Hub 要能搜、能浏览、能推 —— 于是它回到凭据表。
        // 匿名那一份仍是内置档（不走这里），所以这一档作为**凭据行**必须带认证。
        when(mapper.selectByName(anyString())).thenReturn(null);
        when(mapper.insert(any())).thenReturn(1);

        CredentialCreateRequest req = new CredentialCreateRequest();
        req.setName("dockerhub-release");
        req.setType("DOCKERHUB");
        req.setEndpoint("docker.io");
        req.setAuthType("BASIC");
        req.setUsername("myuser");
        req.setSecret("pat-xyz");

        svc.create(req);

        ArgumentCaptor<PlatformCredential> cap = ArgumentCaptor.forClass(PlatformCredential.class);
        verify(mapper).insert(cap.capture());
        assertThat(cap.getValue().getType()).isEqualTo("DOCKERHUB");
        assertThat(cap.getValue().getEndpoint()).isEqualTo("docker.io");
    }

    @Test
    void aDockerHubCredentialCannotBeAnonymous() {
        // 匿名那一份是**内置档**；凭据行要是也匿名，下拉里就会多出一个与它一模一样、
        // 候选完全重复的来源。允许的认证方式由方言声明（authTypes），不在这里手写清单
        CredentialCreateRequest req = new CredentialCreateRequest();
        req.setName("hub");
        req.setType("DOCKERHUB");
        req.setEndpoint("docker.io");
        req.setAuthType("NONE");

        assertThatThrownBy(() -> svc.create(req))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("认证方式");
    }

    @Test
    void aDockerHubsEndpointIsNormalisedToDockerIo() {
        // 三个别名指向同一个 registry；统一存 docker.io（出站站点由方言写死，与 endpoint 无关）
        when(mapper.selectByName(anyString())).thenReturn(null);
        when(mapper.insert(any())).thenReturn(1);

        CredentialCreateRequest req = new CredentialCreateRequest();
        req.setName("hub-alias");
        req.setType("DOCKERHUB");
        req.setEndpoint("registry-1.docker.io/");
        req.setAuthType("BASIC");
        req.setUsername("myuser");
        req.setSecret("pat-xyz");

        svc.create(req);

        ArgumentCaptor<PlatformCredential> cap = ArgumentCaptor.forClass(PlatformCredential.class);
        verify(mapper).insert(cap.capture());
        assertThat(cap.getValue().getEndpoint()).isEqualTo("docker.io");
    }

    @Test
    void aDockerHubCredentialPointingSomewhereElseIsRefusedNotSilentlyRewritten() {
        // 静默改写会让管理员以为配的是个私有 registry，实际收到的是 Docker Hub 的连接
        CredentialCreateRequest req = new CredentialCreateRequest();
        req.setName("hub-wrong");
        req.setType("DOCKERHUB");
        req.setEndpoint("harbor.example.com");
        req.setAuthType("BASIC");
        req.setUsername("myuser");
        req.setSecret("pat-xyz");

        assertThatThrownBy(() -> svc.create(req))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("docker.io");
    }

    // ---- 匿名（authType=NONE） ----
    @Test
    void anonymousCredentialStoresNoCiphertext() {
        when(mapper.selectByName(anyString())).thenReturn(null);
        when(mapper.insert(any())).thenReturn(1);

        CredentialCreateRequest req = new CredentialCreateRequest();
        req.setName("public-harbor");
        req.setType("HARBOR");
        req.setEndpoint("harbor.example.com");
        req.setAuthType("NONE");

        CredentialView v = svc.create(req);

        assertThat(v.getAuthType()).isEqualTo("NONE");
        assertThat(v.getHasSecret()).isFalse();
        assertThat(v.getFingerprint()).isNull();

        var captor = org.mockito.ArgumentCaptor.forClass(PlatformCredential.class);
        verify(mapper).insert(captor.capture());
        // 不写密文，而不是写一份 username/secret 都是 null 的密文 ——
        // hasSecret 由「secret_encrypted 是否为空」直接得出，不用解密就能判断
        assertThat(captor.getValue().getSecretEncrypted()).isNull();
    }

    @Test
    void anonymousCredentialRejectsUsername() {
        CredentialCreateRequest req = new CredentialCreateRequest();
        req.setName("public-harbor");
        req.setType("HARBOR");
        req.setEndpoint("harbor.example.com");
        req.setAuthType("NONE");
        req.setUsername("someone");

        assertThatThrownBy(() -> svc.create(req))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("匿名");
    }

    @Test
    void switchingToAnonymousClearsTheStoredCredential() {
        // 钉住 S0.2 的死路：以前「留空 = 保持不变」让凭据一旦填了就再也清不掉
        PlatformCredential old = stored("c1", "old-name", "HARBOR", "harbor.example.com", "u", "p");
        when(mapper.selectById("c1")).thenReturn(old);
        when(mapper.selectByName("old-name")).thenReturn(old);

        CredentialUpdateRequest req = new CredentialUpdateRequest();
        req.setId("c1");
        req.setName("old-name");
        req.setType("HARBOR");
        req.setEndpoint("harbor.example.com");
        req.setAuthType("NONE");

        CredentialView v = svc.update(req);

        assertThat(v.getHasSecret()).isFalse();
        var captor = org.mockito.ArgumentCaptor.forClass(PlatformCredential.class);
        verify(mapper).update(captor.capture());
        assertThat(captor.getValue().getSecretEncrypted()).isNull();
    }

    @Test
    void switchingFromAnonymousToBasicRequiresBothFields() {
        PlatformCredential old = anonymousStored("c1", "n", "HARBOR", "harbor.example.com");
        when(mapper.selectById("c1")).thenReturn(old);
        when(mapper.selectByName("n")).thenReturn(old);

        CredentialUpdateRequest req = new CredentialUpdateRequest();
        req.setId("c1");
        req.setName("n");
        req.setType("HARBOR");
        req.setEndpoint("harbor.example.com");
        req.setAuthType("BASIC");
        req.setSecret("tok");   // 只填了 secret，没填 username

        assertThatThrownBy(() -> svc.update(req))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("重填");
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
                stored("c1", "harbor", "HARBOR", "harbor.example.com", "robot", "pw"));
        // 收到的已经是**拼好的 URL**（由 HarborDialect.probeSpec 给），不再是裸 endpoint
        when(probeClient.probeRegistry(eq("https://harbor.example.com/v2/"), eq("robot"), eq("pw"), any(), any()))
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
        PlatformCredential row = stored("c1", "harbor", "HARBOR", "harbor.example.com", "r", "pw");
        row.setLastVerifiedAt(new Date(1_700_000_000_000L));
        when(mapper.selectById("c1")).thenReturn(row);
        when(probeClient.probeRegistry(anyString(), anyString(), anyString(), any(), any()))
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

    // ---- 流水线里的逐条授权（S3.5 第 ① 片）----

    /** 带「允许在流水线里使用」开关的行。默认关 —— 与建表时的 DEFAULT 0 一致。 */
    private PlatformCredential storedWithPipelineFlag(String id, String name, String type,
                                                     String endpoint, boolean usableByPipelines) {
        PlatformCredential c = stored(id, name, type, endpoint, "u", "s");
        c.setUsableByPipelines(usableByPipelines ? 1 : 0);
        return c;
    }

    @Test
    void onlyConnectionsMarkedForPipelinesShowUp() {
        // ⭐ Review Focus #4 的一半：没被授权的连接**不出现**在下拉里
        when(mapper.selectAll()).thenReturn(List.of(
                storedWithPipelineFlag("c1", "git-a", "GIT", "https://a.example.com", true),
                storedWithPipelineFlag("c2", "git-b", "GIT", "https://b.example.com", false),
                storedWithPipelineFlag("c3", "harbor", "HARBOR", "harbor.example.com", true)));

        assertThat(svc.pipelineUsableConnections("GIT"))
                .extracting(CredentialService.ConnectionView::id).containsExactly("c1");
    }

    @Test
    void theProjectionCarriesOnlyIdNameEndpointAndProject() {
        when(mapper.selectAll()).thenReturn(List.of(
                storedWithPipelineFlag("c1", "git-a", "GIT", "https://a.example.com", true)));
        CredentialService.ConnectionView v = svc.pipelineUsableConnections("GIT").get(0);
        // id/name/endpoint + 项目名（Harbor 自动凭据才有；手工凭据为 null）
        assertThat(v.getClass().getDeclaredFields()).hasSize(4);
        assertThat(v.project()).isNull();
    }

    @Test
    void referencingAnUnauthorizedConnectionIsRefusedNotSilentlyAnonymous() {
        // ⭐ Review Focus #4：绕过前端直接打接口，也**不能**静默降级成"匿名去拉"
        when(mapper.selectById("c2")).thenReturn(
                storedWithPipelineFlag("c2", "git-b", "GIT", "https://b.example.com", false));

        assertThatThrownBy(() -> svc.assertUsableByPipelines("c2", "GIT"))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("未授权");
    }

    @Test
    void aWrongTypeIsAlsoRefused() {
        when(mapper.selectById("c3")).thenReturn(
                storedWithPipelineFlag("c3", "harbor", "HARBOR", "harbor.example.com", true));

        assertThatThrownBy(() -> svc.assertUsableByPipelines("c3", "GIT"))
                .isInstanceOf(CloudPlatformException.class);
    }

    @Test
    void aMissingConnectionIsRefused() {
        when(mapper.selectById("nope")).thenReturn(null);

        assertThatThrownBy(() -> svc.assertUsableByPipelines("nope", "GIT"))
                .isInstanceOf(CloudPlatformException.class);
    }

    @Test
    void theFlagIsEchoedInTheViewBecauseItIsASwitchNotASecret() {
        when(mapper.selectAll()).thenReturn(List.of(
                storedWithPipelineFlag("c1", "git-a", "GIT", "https://a.example.com", true)));

        assertThat(svc.list()).singleElement()
                .satisfies(v -> assertThat(v.getUsableByPipelines()).isEqualTo(1));
    }

    @Test
    void managedRegistryCredentialIsHarborSourcedTenantScopedAndPipelineUsable() {
        when(mapper.selectByName("Harbor t-acme@c1")).thenReturn(null);
        String id = svc.upsertManagedRegistry("Harbor t-acme@c1", "harbor.example.com", "tenant-1",
                "robot$t-acme+ci", "s3cr3t", "Harbor 自动创建");

        ArgumentCaptor<PlatformCredential> captor = ArgumentCaptor.forClass(PlatformCredential.class);
        verify(mapper).insert(captor.capture());
        PlatformCredential saved = captor.getValue();
        assertThat(saved.getType()).isEqualTo("HARBOR");
        assertThat(saved.getAuthType()).isEqualTo("BASIC");
        assertThat(saved.getSource()).isEqualTo("HARBOR");
        assertThat(saved.getTenantId()).isEqualTo("tenant-1");
        assertThat(saved.getUsableByPipelines()).isEqualTo(1);
        assertThat(saved.getEnabled()).isEqualTo(1);
        assertThat(saved.getSecretEncrypted()).startsWith("v1:");
        assertThat(id).isEqualTo(saved.getId());
    }

    /**
     * 租户只看自己租户的。
     *
     * <p>2026-10-30 收紧：平台级（`tenant_id = null`）的连接是管理员的，**不再**对租户可见 ——
     * 旧的"公共读"规则会让管理员建的私有 Docker Hub / 外部 Harbor 漏进每个租户。
     * 平台级只剩内置的 Docker Hub（不是凭据行，不走这里）。
     */
    @Test
    void tenantSeesOnlyItsOwnRegistryCredentials() {
        PlatformCredential mine = registryCredential("c1", "tenant-1", "HARBOR", "Harbor t-acme@c1");
        PlatformCredential other = registryCredential("c2", "t-other", "HARBOR", "Harbor t-other@c1");
        PlatformCredential manual = registryCredential("c3", null, "MANUAL", "dockerhub");
        PlatformCredential platformHarbor = registryCredential("c4", null, "HARBOR", "Harbor platform@c1");
        when(mapper.selectAll()).thenReturn(List.of(mine, other, manual, platformHarbor));

        assertThat(svc.pipelineUsableRegistryConnections("tenant-1"))
                .extracting(CredentialService.ConnectionView::name)
                .containsExactly("Harbor t-acme@c1");
        // 管理员（无帽）看全量
        assertThat(svc.pipelineUsableRegistryConnections(null)).hasSize(4);
    }

    @Test
    void anotherTenantsCredentialCannotBeReferencedByHand() {
        when(mapper.selectById("c2")).thenReturn(
                registryCredential("c2", "t-other", "HARBOR", "Harbor t-other@c1"));
        assertThatThrownBy(() -> svc.assertUsableByPipelines("c2", null, "tenant-1"))
                .hasMessageContaining("未授权");
        when(mapper.selectById("c4")).thenReturn(
                registryCredential("c4", null, "HARBOR", "Harbor platform@c1"));
        assertThatThrownBy(() -> svc.assertUsableByPipelines("c4", null, "tenant-1"))
                .hasMessageContaining("未授权");   // 平台共享的 HARBOR 凭据也不给租户
    }

    private static PlatformCredential registryCredential(String id, String tenantId,
                                                         String source, String name) {
        PlatformCredential c = new PlatformCredential();
        c.setId(id);
        c.setName(name);
        c.setType("HARBOR");
        c.setEndpoint("harbor.example.com");
        c.setAuthType("BASIC");
        c.setEnabled(1);
        c.setUsableByPipelines(1);
        c.setSecretEncrypted("v1:x");
        c.setTenantId(tenantId);
        c.setSource(source);
        return c;
    }
}
