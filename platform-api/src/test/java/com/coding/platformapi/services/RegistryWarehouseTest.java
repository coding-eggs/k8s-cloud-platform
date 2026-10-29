package com.coding.platformapi.services;

import com.coding.data.mapper.system.PlatformCredentialMapper;
import com.coding.data.mapper.system.PlatformRegistryProjectMapper;
import com.coding.data.models.system.PlatformCredential;
import com.coding.data.models.system.PlatformRegistryProject;
import com.coding.platformapi.cache.RedisJsonCache;
import com.coding.platformapi.credential.CredentialCipher;
import com.coding.platformapi.models.RegistryWarehouseView;
import com.coding.platformapi.registry.DockerHubDialect;
import com.coding.platformapi.registry.HarborDialect;
import com.coding.platformapi.registry.HarborInstallLocator;
import com.coding.platformapi.registry.RegistryDialectRegistry;
import com.coding.platformapi.registry.RegistryHttpClient;
import com.coding.platformapi.registry.RegistrySuggestExecutor;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 仓库清单 = 安装记录 ∪ 凭据行 ∪ 内置 Docker Hub，按 host 去重。 */
class RegistryWarehouseTest {

    PlatformCredentialMapper mapper = mock(PlatformCredentialMapper.class);
    PlatformRegistryProjectMapper projectMapper = mock(PlatformRegistryProjectMapper.class);
    CredentialCipher cipher = new CredentialCipher("test-only-master-key-0123456789abcdef");
    HarborInstallLocator locator = mock(HarborInstallLocator.class);

    private RegistryService service(boolean dockerHubEnabled) {
        // 方言是必需的：allSources 用 dialects.supports(type) 判「这行是不是镜像仓库」
        return new RegistryService(mapper, projectMapper, cipher,
                new RegistryDialectRegistry(List.of(
                        new HarborDialect(mock(RegistryHttpClient.class)),
                        new DockerHubDialect(mock(RegistryHttpClient.class)))),
                mock(RedisJsonCache.class),
                new RegistrySuggestExecutor(), dockerHubEnabled, locator);
    }

    private static PlatformCredential cred(String id, String name, String type, String endpoint,
                                           String tenantId, String source) {
        PlatformCredential c = new PlatformCredential();
        c.setId(id);
        c.setName(name);
        c.setType(type);
        c.setEndpoint(endpoint);
        c.setAuthType("BASIC");
        c.setEnabled(1);
        c.setUsableByPipelines(1);
        c.setTenantId(tenantId);
        c.setSource(source);
        return c;
    }

    private static HarborInstallLocator.InstalledHarbor installed(String clusterId, String name,
                                                                  String host) {
        return new HarborInstallLocator.InstalledHarbor(clusterId, name,
                new HarborInstallLocator.Access(host, false), "nodePort");
    }

    @Test
    void groupsCredentialRowsByHostAndTagsThemAsCredentialSourced() {
        when(locator.all()).thenReturn(List.of());
        when(projectMapper.selectAll()).thenReturn(List.of());
        when(mapper.selectAll()).thenReturn(List.of(
                cred("c1", "corp-harbor", "HARBOR", "http://harbor.corp.x", null, "MANUAL"),
                cred("c2", "corp-harbor-2", "HARBOR", "harbor.corp.x:8443", null, "MANUAL")));

        List<RegistryWarehouseView> all = service(false).warehouses(null);

        assertThat(all).extracting(RegistryWarehouseView::host)
                .containsExactlyInAnyOrder("harbor.corp.x", "harbor.corp.x:8443");
        assertThat(all).allMatch(w -> w.origin().equals("CREDENTIAL"));
        assertThat(all).allMatch(w -> w.kind().equals("HARBOR"));
    }

    @Test
    void mergesTheInstalledHarborIntoAnInstalledWarehouse() {
        when(locator.all()).thenReturn(List.of(installed("cl1", "主集群", "harbor.ackcp.cloud")));
        when(projectMapper.selectAll()).thenReturn(List.of());
        when(mapper.selectAll()).thenReturn(List.of(
                cred("c1", "t-team-a", "HARBOR", "https://harbor.ackcp.cloud",
                        "t1", "HARBOR")));

        List<RegistryWarehouseView> all = service(false).warehouses(null);

        assertThat(all).hasSize(1);
        RegistryWarehouseView w = all.get(0);
        assertThat(w.origin()).isEqualTo("INSTALLED");
        assertThat(w.clusterId()).isEqualTo("cl1");
        assertThat(w.exposeMode()).isEqualTo("nodePort");
        assertThat(w.connectionCount()).isEqualTo(1);
    }

    @Test
    void adminAlsoSeesInstalledHarborsThatHaveNoCredentialsYet() {
        when(locator.all()).thenReturn(List.of(installed("cl1", "主集群", "harbor.ackcp.cloud")));
        when(projectMapper.selectAll()).thenReturn(List.of());
        when(mapper.selectAll()).thenReturn(List.of());

        assertThat(service(false).warehouses(null))
                .extracting(RegistryWarehouseView::origin).containsExactly("INSTALLED");
    }

    @Test
    void tenantDoesNotSeeAnInstalledHarborWithNoVisibleCredential() {
        when(locator.all()).thenReturn(List.of(installed("cl1", "主集群", "harbor.ackcp.cloud")));
        when(projectMapper.selectAll()).thenReturn(List.of());
        // 只有平台共享 robot（tenant_id = null + source = HARBOR）—— 对租户不可见
        when(mapper.selectAll()).thenReturn(List.of(
                cred("c1", "platform", "HARBOR", "harbor.ackcp.cloud", null, "HARBOR")));

        assertThat(service(false).warehouses("t1")).isEmpty();
    }

    @Test
    void tenantProjectCountOnlyCountsItsOwnRows() {
        when(locator.all()).thenReturn(List.of(installed("cl1", "主集群", "harbor.ackcp.cloud")));
        when(mapper.selectAll()).thenReturn(List.of(
                cred("c1", "t-team-a", "HARBOR", "harbor.ackcp.cloud", "t1", "HARBOR")));
        PlatformRegistryProject mine = new PlatformRegistryProject();
        mine.setHost("harbor.ackcp.cloud");
        mine.setTenantId("t1");
        PlatformRegistryProject other = new PlatformRegistryProject();
        other.setHost("harbor.ackcp.cloud");
        other.setTenantId("t2");
        when(projectMapper.selectAll()).thenReturn(List.of(mine, other));

        assertThat(service(false).warehouses("t1").get(0).projectCount()).isEqualTo(1);
        assertThat(service(false).warehouses(null).get(0).projectCount()).isEqualTo(2);
    }

    /**
     * 平台级只剩**内置的默认 Docker Hub** 对租户可见 —— 管理员建的平台级连接不出现。
     */
    @Test
    void tenantSeesTheBuiltinDockerHubButNoPlatformLevelConnections() {
        when(locator.all()).thenReturn(List.of());
        when(projectMapper.selectAll()).thenReturn(List.of());
        when(mapper.selectAll()).thenReturn(List.of(
                cred("pub", "corp-hub", "HARBOR", "harbor.corp.x", null, "MANUAL"),
                cred("hub", "my-dockerhub", "DOCKERHUB", "docker.io", null, "MANUAL")));

        assertThat(service(true).warehouses("t1"))
                .extracting(RegistryWarehouseView::origin)
                .containsExactly("BUILTIN");
    }

    @Test
    void builtinDockerHubAppearsOnlyWhenItsSwitchIsOn() {
        when(locator.all()).thenReturn(List.of());
        when(projectMapper.selectAll()).thenReturn(List.of());
        when(mapper.selectAll()).thenReturn(List.of());

        // 其余用例一律把内置档关掉：它恒在，开着会让「按 host 聚合」的断言都被它带偏
        List<RegistryWarehouseView> on = service(true).warehouses(null);
        assertThat(on).hasSize(1);
        assertThat(on.get(0).id()).isEqualTo("builtin:dockerhub");
        assertThat(on.get(0).kind()).isEqualTo("DOCKER");
        assertThat(on.get(0).origin()).isEqualTo("BUILTIN");
        assertThat(on.get(0).host()).isEqualTo("docker.io");

        assertThat(service(false).warehouses(null)).isEmpty();
    }

    @Test
    void aCredentialWithAVerifyErrorMarksTheWarehouseAsError() {
        when(locator.all()).thenReturn(List.of());
        when(projectMapper.selectAll()).thenReturn(List.of());
        PlatformCredential bad = cred("c1", "corp", "HARBOR", "harbor.corp.x", null, "MANUAL");
        bad.setLastVerifyError("401 Unauthorized");
        when(mapper.selectAll()).thenReturn(List.of(bad));

        RegistryWarehouseView w = service(false).warehouses(null).get(0);
        assertThat(w.health()).isEqualTo("ERROR");
        assertThat(w.message()).isEqualTo("401 Unauthorized");
    }
}
