package com.coding.platformapi.services;

import com.coding.data.mapper.auth.PlatformTenantNamespaceMapper;
import com.coding.data.mapper.system.PlatformCredentialMapper;
import com.coding.data.mapper.system.PlatformRegistryProjectMapper;
import com.coding.data.models.system.PlatformCredential;
import com.coding.data.models.system.PlatformRegistryProject;
import com.coding.platformapi.credential.CredentialSecret;
import com.coding.platformapi.k8s.K8sLifecycleClient;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 拼 docker config 的规则（spec 2026-10-31 §6.3 / §6.4）：
 * <ol>
 *   <li>{@code auths} 的 key 是**去掉 scheme 的 host**（与流水线那份同一套规则）；</li>
 *   <li>同 host 只留一条，**平台 robot 优先** —— containerd 对同一 host 只取第一条匹配；</li>
 *   <li>匿名凭据（无用户名/口令）不进 —— 写进去等于一个必然 401 的条目；</li>
 *   <li>一条能用的都没有时返回 {@code null}（调用方据此跳过，不写空 Secret）。</li>
 * </ol>
 */
class RegistryPullSecretServiceTest {

    private final PlatformRegistryProjectMapper projects = mock(PlatformRegistryProjectMapper.class);
    private final PlatformCredentialMapper credentials = mock(PlatformCredentialMapper.class);
    private final CredentialService credentialService = mock(CredentialService.class);
    private final PlatformTenantNamespaceMapper allocations = mock(PlatformTenantNamespaceMapper.class);
    private final K8sLifecycleClient k8s = mock(K8sLifecycleClient.class);

    private final RegistryPullSecretService service = new RegistryPullSecretService(
            projects, credentials, credentialService, allocations, k8s);

    private final JsonMapper json = JsonMapper.builder().build();

    // ---------- 造数据 ----------

    private PlatformCredential credential(String id, String endpoint) {
        PlatformCredential c = new PlatformCredential();
        c.setId(id);
        c.setEndpoint(endpoint);
        c.setEnabled(1);
        return c;
    }

    private PlatformRegistryProject robotProject(String credentialId, String status) {
        PlatformRegistryProject p = new PlatformRegistryProject();
        p.setCredentialId(credentialId);
        p.setStatus(status);
        return p;
    }

    private void resolveReturns(String id, String user, String secret) {
        when(credentialService.resolveSecret(id)).thenReturn(new CredentialSecret(user, secret));
    }

    private JsonNode authsOf(String dockerConfigJson) {
        return json.readTree(dockerConfigJson).get("auths");
    }

    // ---------- ① 多个 registry 合成一个 Secret ----------

    @Test
    void oneSecretHoldsSeveralRegistries() {
        when(projects.selectByClusterAndTenant("c1", "t1")).thenReturn(robotProject("harbor", "READY"));
        when(credentials.selectById("harbor")).thenReturn(credential("harbor", "https://harbor.example.com"));
        when(credentials.selectById("dockerhub")).thenReturn(credential("dockerhub", "docker.io"));
        resolveReturns("harbor", "robot$t-1+ci", "s1");
        resolveReturns("dockerhub", "bob", "s2");
        when(credentials.listEnabledRegistryByTenant("t1"))
                .thenReturn(List.of(credential("harbor", "https://harbor.example.com"),
                        credential("dockerhub", "docker.io")));

        JsonNode auths = authsOf(service.dockerConfigJson("c1", "t1"));

        assertThat(auths.has("harbor.example.com")).as("scheme 必须被去掉").isTrue();
        assertThat(auths.has("docker.io")).isTrue();
        assertThat(auths.get("harbor.example.com").get("username").asString()).isEqualTo("robot$t-1+ci");
    }

    // ---------- ② 同 host：平台 robot 优先 ----------

    @Test
    void thePlatformRobotWinsWhenTwoCredentialsShareAHost() {
        when(projects.selectByClusterAndTenant("c1", "t1")).thenReturn(robotProject("harbor", "READY"));
        when(credentials.selectById("harbor")).thenReturn(credential("harbor", "harbor.example.com"));
        when(credentials.selectById("manual")).thenReturn(credential("manual", "https://harbor.example.com"));
        resolveReturns("harbor", "robot", "s1");
        resolveReturns("manual", "someone-else", "s2");
        when(credentials.listEnabledRegistryByTenant("t1"))
                .thenReturn(List.of(credential("manual", "https://harbor.example.com")));

        JsonNode auths = authsOf(service.dockerConfigJson("c1", "t1"));

        assertThat(auths.size()).isEqualTo(1);
        assertThat(auths.get("harbor.example.com").get("username").asString())
                .as("先到先得：平台 robot 放过之后同 host 的手工凭据必须被跳过").isEqualTo("robot");
    }

    // ---------- ③ 匿名凭据不进 ----------

    @Test
    void anonymousCredentialsAreNotWritten() {
        when(projects.selectByClusterAndTenant("c1", "t1")).thenReturn(null);
        when(credentials.selectById("anon")).thenReturn(credential("anon", "docker.io"));
        resolveReturns("anon", null, null);
        when(credentials.listEnabledRegistryByTenant("t1"))
                .thenReturn(List.of(credential("anon", "docker.io")));

        assertThat(service.dockerConfigJson("c1", "t1"))
                .as("只有匿名凭据 = 没有可写的东西").isNull();
    }

    // ---------- ④ 一条都没有 ----------

    @Test
    void noUsableCredentialYieldsNull() {
        when(projects.selectByClusterAndTenant("c1", "t1")).thenReturn(null);
        when(credentials.listEnabledRegistryByTenant("t1")).thenReturn(List.of());

        assertThat(service.dockerConfigJson("c1", "t1")).isNull();
    }

    @Test
    void aProjectThatIsNotReadyDoesNotShipItsRobot() {
        when(projects.selectByClusterAndTenant("c1", "t1")).thenReturn(robotProject("harbor", "ERROR"));
        when(credentials.listEnabledRegistryByTenant("t1")).thenReturn(List.of());

        assertThat(service.dockerConfigJson("c1", "t1"))
                .as("不是 READY 就不该把那条 robot 发出去").isNull();
    }

    // ---------- ⑤ 重下发按命名空间铺开 ----------

    @Test
    void resyncTenantCoversEveryAllocatedNamespace() {
        when(projects.selectByClusterAndTenant("c1", "t1")).thenReturn(robotProject("harbor", "READY"));
        when(credentials.selectById("harbor")).thenReturn(credential("harbor", "harbor.example.com"));
        resolveReturns("harbor", "robot", "s1");
        when(credentials.listEnabledRegistryByTenant("t1")).thenReturn(List.of());
        com.coding.data.models.auth.PlatformTenantNamespace a =
                new com.coding.data.models.auth.PlatformTenantNamespace();
        a.setNamespace("ns-a");
        com.coding.data.models.auth.PlatformTenantNamespace b =
                new com.coding.data.models.auth.PlatformTenantNamespace();
        b.setNamespace("ns-b");
        when(allocations.listByTenantAndCluster("t1", "c1")).thenReturn(List.of(a, b));

        int applied = service.resyncTenant("c1", "t1");

        assertThat(applied).isEqualTo(2);
        verify(k8s).ensureRegistryPullSecret(eq("c1"), eq("ns-a"),
                eq(RegistryPullSecretService.SECRET_NAME), anyString());
        verify(k8s).ensureRegistryPullSecret(eq("c1"), eq("ns-b"),
                eq(RegistryPullSecretService.SECRET_NAME), anyString());
    }
}
