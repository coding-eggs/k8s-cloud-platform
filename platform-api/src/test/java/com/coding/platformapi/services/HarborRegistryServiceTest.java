package com.coding.platformapi.services;

import com.coding.data.mapper.auth.PlatformTenantMapper;
import com.coding.data.mapper.system.PlatformComponentOperationMapper;
import com.coding.data.mapper.system.PlatformCredentialMapper;
import com.coding.data.mapper.system.PlatformRegistryProjectMapper;
import com.coding.data.models.auth.PlatformTenant;
import com.coding.data.models.k8s.K8sCluster;
import com.coding.data.models.system.PlatformComponentOperation;
import com.coding.data.models.system.PlatformCredential;
import com.coding.data.models.system.PlatformRegistryProject;
import com.coding.platformapi.registry.HarborAdminClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HarborRegistryServiceTest {

    private final PlatformComponentOperationMapper operations = mock(PlatformComponentOperationMapper.class);
    private final PlatformTenantMapper tenants = mock(PlatformTenantMapper.class);
    private final PlatformRegistryProjectMapper projects = mock(PlatformRegistryProjectMapper.class);
    private final PlatformCredentialMapper credentials = mock(PlatformCredentialMapper.class);
    private final CredentialService credentialService = mock(CredentialService.class);
    private final com.coding.platformapi.k8s.K8sComponentClient components =
            mock(com.coding.platformapi.k8s.K8sComponentClient.class);
    private final K8sProvisioningService provisioning = mock(K8sProvisioningService.class);
    private final HarborAdminClient harbor = mock(HarborAdminClient.class);
    /** robot 轮换后的拉取凭据重下发：这些用例只关心 Harbor 侧，重下发本身在它自己的测试里钉 */
    private final RegistryPullSecretService pullSecrets = mock(RegistryPullSecretService.class);

    private HarborRegistryService svc;

    @BeforeEach
    void setUp() {
        // 安装快照的解析已移到 HarborInstallLocator；这里仍用同一个 operations 桩喂它
        svc = new HarborRegistryService(tenants, projects, credentials, credentialService,
                components, provisioning,
                new com.coding.platformapi.registry.HarborInstallLocator(operations, provisioning),
                harbor, pullSecrets);
        when(operations.selectLatestSuccess("c1", "harbor")).thenReturn(successOperation());
        K8sCluster cluster = new K8sCluster();
        cluster.setClusterId("c1");
        cluster.setClusterName("test-cluster");
        when(provisioning.enabledClusters()).thenReturn(List.of(cluster));
        // harbor 是组件命名空间、不在租户分配表里：口令只能走 PLATFORM 端点读回
        when(components.harborAdminPassword("c1", "harbor", "platform-harbor-secrets"))
                .thenReturn("pw");
        // 平台共享项目默认已就绪：这些用例只关心租户路径
        when(projects.selectByClusterAndTenant("c1", null)).thenReturn(platformRow());
        when(credentials.selectById("cred-platform")).thenReturn(usableCredential("cred-platform"));
        when(credentialService.resolveSecret("cred-platform"))
                .thenReturn(new com.coding.platformapi.credential.CredentialSecret(
                        "robot$platform+ci", "pw"));
        // 默认 robot 可正常列仓库（需要「重建」的用例再覆盖）
        when(credentialService.resolveSecret(anyString()))
                .thenReturn(new com.coding.platformapi.credential.CredentialSecret("robot$any+ci", "pw"));
        when(harbor.probeRobot(anyString(), anyString(), anyString(), anyString())).thenReturn(null);
    }

    @Test
    void firstSyncCreatesProjectRobotAndCredential() {
        when(tenants.listAll()).thenReturn(List.of(tenant("tenant-1", "acme")));
        when(projects.selectByClusterAndTenant("c1", "tenant-1")).thenReturn(null);
        when(harbor.ensureProject(anyString(), anyString(), anyString(), eq("t-acme"))).thenReturn(7L);
        when(harbor.createRobot(anyString(), anyString(), anyString(), eq("t-acme")))
                .thenReturn(new HarborAdminClient.HarborRobot(42L, "robot$t-acme+ci", "s3cr3t"));
        when(credentialService.upsertManagedRegistry(anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString())).thenReturn("cred-1");

        HarborRegistryService.SyncResult result = svc.ensureAll("c1");

        assertThat(result.failed()).isZero();
        verify(harbor).createRobot(anyString(), anyString(), anyString(), eq("t-acme"));
        verify(credentialService).upsertManagedRegistry(eq("Harbor t-acme@c1"),
                eq("http://harbor.example.com"),
                eq("tenant-1"), eq("robot$t-acme+ci"), eq("s3cr3t"), anyString());
        verify(projects).insert(any());
    }

    @Test
    void secondSyncIsIdempotentAndDoesNotRotateTheSecret() {
        PlatformRegistryProject row = new PlatformRegistryProject();
        row.setId("p1");
        row.setClusterId("c1");
        row.setTenantId("tenant-1");
        row.setProjectName("t-acme");
        row.setCredentialId("cred-1");
        row.setRobotId(42L);
        row.setStatus("READY");
        row.setHost("harbor.example.com");
        when(tenants.listAll()).thenReturn(List.of(tenant("tenant-1", "acme")));
        when(projects.selectByClusterAndTenant("c1", "tenant-1")).thenReturn(row);
        PlatformCredential cred = new PlatformCredential();
        cred.setId("cred-1");
        cred.setSecretEncrypted("v1:xxx");
        cred.setEnabled(1);
        cred.setEndpoint("http://harbor.example.com");
        when(credentials.selectById("cred-1")).thenReturn(cred);

        HarborRegistryService.SyncResult result = svc.ensureAll("c1");

        assertThat(result.failed()).isZero();
        verify(harbor, never()).createRobot(anyString(), anyString(), anyString(), anyString());
        verify(credentialService, never()).upsertManagedRegistry(anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString());
    }

    @Test
    void aSchemeLessEndpointIsRepairedSoBrowsingStopsUsingHttps() {
        // 早期写的是 host:port（没有 scheme），HarborDialect 对无 scheme 的端点默认补 https，
        // 而平台装的 Harbor 只有 HTTP → 浏览镜像仓库报
        // "Unrecognized SSL message, plaintext connection"（2026-10-27 用户撞到）。
        // 行是 READY、凭据也可用，只有端点旧 —— 必须重做一次把端点修掉。
        PlatformRegistryProject row = new PlatformRegistryProject();
        row.setId("p1");
        row.setClusterId("c1");
        row.setTenantId("tenant-1");
        row.setProjectName("t-acme");
        row.setCredentialId("cred-1");
        row.setRobotId(42L);
        row.setStatus("READY");
        row.setHost("harbor.example.com");
        when(tenants.listAll()).thenReturn(List.of(tenant("tenant-1", "acme")));
        when(projects.selectByClusterAndTenant("c1", "tenant-1")).thenReturn(row);
        PlatformCredential old = new PlatformCredential();
        old.setId("cred-1");
        old.setSecretEncrypted("v1:xxx");
        old.setEnabled(1);
        old.setEndpoint("harbor.example.com");
        when(credentials.selectById("cred-1")).thenReturn(old);
        when(harbor.ensureProject(anyString(), anyString(), anyString(), eq("t-acme"))).thenReturn(7L);
        when(harbor.createRobot(anyString(), anyString(), anyString(), eq("t-acme")))
                .thenReturn(new HarborAdminClient.HarborRobot(43L, "robot$t-acme+ci", "new"));
        when(credentialService.upsertManagedRegistry(anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString())).thenReturn("cred-1");

        svc.ensureAll("c1");

        verify(credentialService).upsertManagedRegistry(eq("Harbor t-acme@c1"),
                eq("http://harbor.example.com"), eq("tenant-1"),
                eq("robot$t-acme+ci"), eq("new"), anyString());
    }

    @Test
    void aRobotThatCannotListItsProjectIsRebuilt() {
        // robot 的权限是**建的时候定死的**：凭据行、端点都对，也可能列不出仓库（早期只给了
        // pull/push）。同步要能自己发现并重建（新 robot 带 list 权限），
        // 而不是等用户点开镜像仓库挨 403（2026-10-28 用户撞到）
        PlatformRegistryProject row = new PlatformRegistryProject();
        row.setId("p1");
        row.setClusterId("c1");
        row.setTenantId("tenant-1");
        row.setProjectName("t-acme");
        row.setCredentialId("cred-1");
        row.setRobotId(42L);
        row.setStatus("READY");
        row.setHost("harbor.example.com");
        when(projects.selectByClusterAndTenant("c1", "tenant-1")).thenReturn(row);
        when(tenants.selectByPrimaryKey("tenant-1")).thenReturn(tenant("tenant-1", "acme"));
        when(credentials.selectById("cred-1")).thenReturn(usableCredential("cred-1"));
        when(credentialService.resolveSecret("cred-1"))
                .thenReturn(new com.coding.platformapi.credential.CredentialSecret(
                        "robot$t-acme+ci", "old"));
        when(harbor.probeRobot(anyString(), anyString(), anyString(), anyString()))
                .thenReturn("HTTP 403");
        when(harbor.ensureProject(anyString(), anyString(), anyString(), eq("t-acme"))).thenReturn(7L);
        when(harbor.createRobot(anyString(), anyString(), anyString(), eq("t-acme")))
                .thenReturn(new HarborAdminClient.HarborRobot(44L, "robot$t-acme+ci", "new"));
        when(credentialService.upsertManagedRegistry(anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString())).thenReturn("cred-1");

        HarborRegistryService.SyncItem item = svc.ensureTenant("c1", "tenant-1", false);

        verify(harbor).deleteRobotByName(anyString(), anyString(), anyString(), eq(7L), anyString());
        verify(harbor).createRobot(anyString(), anyString(), anyString(), eq("t-acme"));
        // 新 robot 仍然列不出（桩一直 403）→ 台账记 ERROR 并写明原因，不再谎报 READY
        assertThat(item.status()).isEqualTo("ERROR");
        assertThat(item.message()).contains("HTTP 403");
    }

    @Test
    void errorRowIsRetriedInsteadOfSkipped() {
        PlatformRegistryProject row = new PlatformRegistryProject();
        row.setId("p1");
        row.setClusterId("c1");
        row.setTenantId("tenant-1");
        row.setProjectName("t-acme");
        row.setCredentialId("cred-1");
        row.setRobotId(42L);
        row.setStatus("ERROR");
        row.setHost("harbor.example.com");
        when(tenants.selectByPrimaryKey("tenant-1")).thenReturn(tenant("tenant-1", "acme"));
        when(projects.selectByClusterAndTenant("c1", "tenant-1")).thenReturn(row);
        when(harbor.ensureProject(anyString(), anyString(), anyString(), eq("t-acme"))).thenReturn(7L);
        when(harbor.createRobot(anyString(), anyString(), anyString(), eq("t-acme")))
                .thenReturn(new HarborAdminClient.HarborRobot(44L, "robot$t-acme+ci", "fresh"));
        when(credentialService.upsertManagedRegistry(anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString())).thenReturn("cred-1");

        HarborRegistryService.SyncItem item = svc.ensureTenant("c1", "tenant-1", false);

        // 上一次失败的行不能因为「凭据行还在」就跳过：旧 robot 可能已经删掉了。
        // ⭐ 删除一律按 project + 名字（Harbor 重装后 id 会复用，陈旧 id 会误删）
        assertThat(item.status()).isEqualTo("READY");
        verify(harbor).deleteRobotByName(anyString(), anyString(), anyString(), eq(7L),
                eq("robot$t-acme+ci"));
        verify(harbor, never()).deleteRobot(anyString(), anyString(), anyString(), eq(42L));
        verify(harbor).createRobot(anyString(), anyString(), anyString(), eq("t-acme"));
    }

    @Test
    void forceRebuildDeletesTheOldRobotThenCreatesANewOne() {
        PlatformRegistryProject row = new PlatformRegistryProject();
        row.setId("p1");
        row.setClusterId("c1");
        row.setTenantId("tenant-1");
        row.setProjectName("t-acme");
        row.setCredentialId("cred-1");
        row.setRobotId(42L);
        row.setStatus("READY");
        row.setHost("harbor.example.com");
        when(tenants.listAll()).thenReturn(List.of(tenant("tenant-1", "acme")));
        when(tenants.selectByPrimaryKey("tenant-1")).thenReturn(tenant("tenant-1", "acme"));
        when(projects.selectByClusterAndTenant("c1", "tenant-1")).thenReturn(row);
        when(harbor.ensureProject(anyString(), anyString(), anyString(), eq("t-acme"))).thenReturn(7L);
        when(harbor.createRobot(anyString(), anyString(), anyString(), eq("t-acme")))
                .thenReturn(new HarborAdminClient.HarborRobot(43L, "robot$t-acme+ci", "new-secret"));
        when(credentialService.upsertManagedRegistry(anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString())).thenReturn("cred-1");

        HarborRegistryService.SyncItem item = svc.rebuild("c1", "tenant-1");

        assertThat(item.status()).isEqualTo("READY");
        verify(harbor).deleteRobotByName(anyString(), anyString(), anyString(), eq(7L),
                eq("robot$t-acme+ci"));
        verify(harbor, never()).deleteRobot(anyString(), anyString(), anyString(), eq(42L));
        verify(harbor).createRobot(anyString(), anyString(), anyString(), eq("t-acme"));
        verify(credentialService).upsertManagedRegistry(anyString(), anyString(), anyString(),
                anyString(), eq("new-secret"), anyString());
    }

    @Test
    void harborHostPrefersTheComposedExternalHostWithPort() {
        PlatformComponentOperation op = successOperation();
        op.setValuesSnapshot("{\"__platform\":{\"harbor\":{\"exposeMode\":\"nodePort\","
                + "\"host\":\"10.0.0.5\",\"nodePort\":30002,\"externalHost\":\"10.0.0.5:30002\"}}}");
        when(operations.selectLatestSuccess("c1", "harbor")).thenReturn(op);

        assertThat(svc.harborHost("c1")).isEqualTo("10.0.0.5:30002");
    }

    @Test
    void explicitHostIsUsedEvenBeforeTheSuccessRecordExists() {
        // 首次安装：钩子在写 SUCCESS 之前跑，selectLatestSuccess 还查不到本集群
        when(operations.selectLatestSuccess("c1", "harbor")).thenReturn(null);
        when(tenants.listAll()).thenReturn(List.of(tenant("tenant-1", "acme")));
        when(projects.selectByClusterAndTenant("c1", "tenant-1")).thenReturn(null);
        when(harbor.ensureProject(anyString(), anyString(), anyString(), eq("t-acme"))).thenReturn(7L);
        when(harbor.createRobot(anyString(), anyString(), anyString(), eq("t-acme")))
                .thenReturn(new HarborAdminClient.HarborRobot(42L, "robot$t-acme+ci", "s3cr3t"));
        when(credentialService.upsertManagedRegistry(anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString())).thenReturn("cred-1");

        // 平台共享项目在 setUp 里已就绪（host 一致），走普通同步即可
        HarborRegistryService.SyncResult result =
                svc.ensureAll("c1", "harbor.example.com", false, false);

        assertThat(result.failed()).isZero();
        assertThat(result.host()).isEqualTo("harbor.example.com");
        verify(credentialService).upsertManagedRegistry(eq("Harbor t-acme@c1"),
                eq("http://harbor.example.com"), eq("tenant-1"), eq("robot$t-acme+ci"),
                eq("s3cr3t"), anyString());
    }

    private static PlatformTenant tenant(String id, String sa) {
        PlatformTenant t = new PlatformTenant();
        t.setId(id);
        t.setName(sa);
        t.setServiceAccount(sa);
        t.setStatus((byte) 1);
        return t;
    }

    private static PlatformComponentOperation successOperation() {
        PlatformComponentOperation op = new PlatformComponentOperation();
        op.setClusterId("c1");
        op.setComponentCode("harbor");
        op.setStatus("SUCCESS");
        op.setValuesSnapshot("{\"__platform\":{\"harbor\":{\"host\":\"harbor.example.com\"}}}");
        return op;
    }

    /**
     * 租户也要能在镜像仓库页看到自己的项目（spec §6.4.1）——
     * 端点对租户开放后，服务端必须按租户帽收窄，否则等于把别家的项目台账也发下去。
     */
    @Test
    void listFiltersByTenantWhenAHatIsPresent() {
        PlatformRegistryProject mine = platformRow();
        mine.setId("mine");
        mine.setTenantId("tenant-1");
        mine.setProjectName("t-team-a");
        PlatformRegistryProject other = platformRow();
        other.setId("other");
        other.setTenantId("tenant-2");
        other.setProjectName("t-team-b");
        when(projects.selectByCluster("c1")).thenReturn(List.of(mine, other));
        PlatformTenant t1 = new PlatformTenant();
        t1.setId("tenant-1");
        t1.setName("团队一");
        when(tenants.selectByPrimaryKey("tenant-1")).thenReturn(t1);

        assertThat(svc.list("c1", "tenant-1"))
                .extracting(com.coding.platformapi.models.RegistryProjectView::getId)
                .containsExactly("mine");
        assertThat(svc.list("c1", null)).hasSize(2);
    }

    private static PlatformRegistryProject platformRow() {
        PlatformRegistryProject row = new PlatformRegistryProject();
        row.setId("platform-row");
        row.setClusterId("c1");
        row.setTenantId(null);
        row.setProjectName("platform");
        row.setCredentialId("cred-platform");
        row.setRobotId(40L);
        row.setStatus("READY");
        row.setHost("harbor.example.com");
        return row;
    }

    private static PlatformCredential usableCredential(String id) {
        PlatformCredential c = new PlatformCredential();
        c.setId(id);
        c.setSecretEncrypted("v1:x");
        c.setEnabled(1);
        // 端点必须与 HarborRegistryService 现在写的一致（带 scheme），否则会被当成旧数据重做
        c.setEndpoint("http://harbor.example.com");
        return c;
    }
}
