package com.coding.platformapi.services;

import com.coding.data.mapper.system.PlatformCredentialMapper;
import com.coding.data.mapper.system.PlatformRegistryProjectMapper;
import com.coding.data.models.system.PlatformCredential;
import com.coding.data.models.system.PlatformRegistryProject;
import com.coding.platformapi.cache.RedisJsonCache;
import com.coding.platformapi.credential.CredentialCipher;
import com.coding.platformapi.models.RegistryBrowseRequest;
import com.coding.platformapi.registry.BrowseResult;
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

/**
 * 浏览根层要能看全：**公开读的项目 + 各个租户自己的项目**，并且后者要能点进去。
 *
 * <p>根层里公开读那批是平台在 {@code withPublicProjects} 并进去的（它们不是 Harbor 回的）；
 * 租户项目则来自「当前调用者可见的连接」——平台 robot 没有别家项目的权限，
 * 所以每一行要能对上**它自己那条 robot**，前端据此换凭据再往里翻。
 */
class RegistryBrowseBadgeTest {

    PlatformCredentialMapper mapper = mock(PlatformCredentialMapper.class);
    PlatformRegistryProjectMapper projectMapper = mock(PlatformRegistryProjectMapper.class);
    CredentialCipher cipher = new CredentialCipher("test-only-master-key-0123456789abcdef");
    RegistryService svc = new RegistryService(mapper, projectMapper, cipher,
            new RegistryDialectRegistry(List.of(new HarborDialect(mock(RegistryHttpClient.class)))),
            mock(RedisJsonCache.class), new RegistrySuggestExecutor(), true,
            mock(HarborInstallLocator.class));

    private PlatformCredential robot(String id, String project, String tenantId) {
        PlatformCredential c = new PlatformCredential();
        c.setId(id);
        c.setName("Harbor " + project + "@harbor.x");
        c.setType("HARBOR");
        c.setEndpoint("harbor.x");
        c.setAuthType("BASIC");
        c.setEnabled(1);
        c.setSource("HARBOR");
        c.setTenantId(tenantId);
        String json = "{\"username\":\"robot$" + project + "+ci\",\"secret\":\"p\"}";
        c.setSecretEncrypted(cipher.encrypt(json));
        return c;
    }

    private static PlatformRegistryProject ledger(String credentialId, String project) {
        PlatformRegistryProject p = new PlatformRegistryProject();
        p.setCredentialId(credentialId);
        p.setProjectName(project);
        return p;
    }

    @Test
    void rootLevelListsPublicProjectsAndEveryVisibleTenantProject() {
        PlatformCredential platformRobot = robot("c1", "platform", null);
        PlatformCredential tenantRobot = robot("c2", "t-default", "t1");
        when(mapper.selectAll()).thenReturn(List.of(platformRobot, tenantRobot));
        when(projectMapper.selectAll()).thenReturn(List.of(
                ledger("c1", "platform"), ledger("c2", "t-default")));

        RegistryBrowseRequest req = new RegistryBrowseRequest();
        req.setCredentialId("c1");
        req.setPath(List.of());

        BrowseResult r = svc.browse(req, null);

        // 公开读那批 + 租户自己的那个，一个都不少
        assertThat(r.children()).extracting(BrowseResult.Node::name)
                .contains("platform", "docker.io", "quay.io", "t-default");
        assertThat(r.children())
                .filteredOn(n -> n.name().equals("t-default")).singleElement()
                .satisfies(n -> assertThat(n.badge()).isEqualTo("租户"));
        assertThat(r.children())
                .filteredOn(n -> n.name().equals("platform")).singleElement()
                .satisfies(n -> assertThat(n.badge()).isEqualTo("公开读"));
        assertThat(r.children())
                .filteredOn(n -> n.name().equals("docker.io")).singleElement()
                .satisfies(n -> assertThat(n.badge()).isEqualTo("公开读"));
    }

    @Test
    void aTenantLookingAtTheRootDoesNotSeeOtherTenantsProjects() {
        PlatformCredential mine = robot("c2", "t-default", "t1");
        PlatformCredential other = robot("c3", "t-other", "t2");
        when(mapper.selectAll()).thenReturn(List.of(mine, other));
        when(projectMapper.selectAll()).thenReturn(List.of(
                ledger("c2", "t-default"), ledger("c3", "t-other")));

        RegistryBrowseRequest req = new RegistryBrowseRequest();
        req.setCredentialId("c2");
        req.setPath(List.of());

        BrowseResult r = svc.browse(req, "t1");

        assertThat(r.children()).extracting(BrowseResult.Node::name)
                .contains("t-default", "platform")
                .doesNotContain("t-other");
    }
}
