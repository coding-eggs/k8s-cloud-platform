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
    private final SecretService secrets = mock(SecretService.class);
    private final K8sProvisioningService provisioning = mock(K8sProvisioningService.class);
    private final HarborAdminClient harbor = mock(HarborAdminClient.class);

    private HarborRegistryService svc;

    @BeforeEach
    void setUp() {
        svc = new HarborRegistryService(operations, tenants, projects, credentials, credentialService,
                secrets, provisioning, harbor);
        when(operations.selectLatestSuccess("c1", "harbor")).thenReturn(successOperation());
        K8sCluster cluster = new K8sCluster();
        cluster.setClusterId("c1");
        cluster.setClusterName("test-cluster");
        when(provisioning.enabledClusters()).thenReturn(List.of(cluster));
        when(secrets.get("platform-harbor-secrets", null, "c1", "harbor"))
                .thenReturn(secretWithPassword());
        // 平台共享项目默认已就绪：这些用例只关心租户路径
        when(projects.selectByClusterAndTenant("c1", null)).thenReturn(platformRow());
        when(credentials.selectById("cred-platform")).thenReturn(usableCredential("cred-platform"));
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
        verify(credentialService).upsertManagedRegistry(eq("Harbor t-acme@c1"), eq("harbor.example.com"),
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
        when(credentials.selectById("cred-1")).thenReturn(cred);

        HarborRegistryService.SyncResult result = svc.ensureAll("c1");

        assertThat(result.failed()).isZero();
        verify(harbor, never()).createRobot(anyString(), anyString(), anyString(), anyString());
        verify(credentialService, never()).upsertManagedRegistry(anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString());
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
                svc.ensureAll("c1", "harbor.example.com", false);

        assertThat(result.failed()).isZero();
        assertThat(result.host()).isEqualTo("harbor.example.com");
        verify(credentialService).upsertManagedRegistry(eq("Harbor t-acme@c1"),
                eq("harbor.example.com"), eq("tenant-1"), eq("robot$t-acme+ci"), eq("s3cr3t"),
                anyString());
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

    private static com.coding.common.models.k8s.dto.SecretDTO secretWithPassword() {
        var dto = new com.coding.common.models.k8s.dto.SecretDTO();
        dto.setData(java.util.Map.of("HARBOR_ADMIN_PASSWORD", "pw", "secretKey", "0123456789abcdef"));
        return dto;
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
        return c;
    }
}
