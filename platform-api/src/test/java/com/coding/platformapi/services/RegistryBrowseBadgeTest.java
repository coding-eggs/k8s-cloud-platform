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
 * 浏览根层的**归属标记**：可点击的项目要能一眼分出「公开读」与「租户」。
 *
 * <p>根层里那批公开读项目（platform + 各上游）是平台在 {@code withPublicProjects} 里并进去的，
 * 不是从 Harbor 查来的 —— 所以标记也只能在那里打。
 */
class RegistryBrowseBadgeTest {

    PlatformCredentialMapper mapper = mock(PlatformCredentialMapper.class);
    PlatformRegistryProjectMapper projectMapper = mock(PlatformRegistryProjectMapper.class);
    CredentialCipher cipher = new CredentialCipher("test-only-master-key-0123456789abcdef");
    RegistryService svc = new RegistryService(mapper, projectMapper, cipher,
            new RegistryDialectRegistry(List.of(new HarborDialect(mock(RegistryHttpClient.class)))),
            mock(RedisJsonCache.class), new RegistrySuggestExecutor(), true,
            mock(HarborInstallLocator.class));

    @Test
    void rootLevelMarksPublicProjectsAndTheRobotsOwnProject() {
        PlatformCredential c = new PlatformCredential();
        c.setId("c1");
        c.setName("Harbor t-default@harbor.x");
        c.setType("HARBOR");
        c.setEndpoint("harbor.x");
        c.setAuthType("BASIC");
        c.setEnabled(1);
        c.setSource("HARBOR");
        c.setTenantId("t1");
        String json = "{\"username\":\"robot$t-default+ci\",\"secret\":\"p\"}";
        c.setSecretEncrypted(cipher.encrypt(json));
        when(mapper.selectAll()).thenReturn(List.of(c));

        PlatformRegistryProject p = new PlatformRegistryProject();
        p.setCredentialId("c1");
        p.setProjectName("t-default");
        when(projectMapper.selectAll()).thenReturn(List.of(p));

        RegistryBrowseRequest req = new RegistryBrowseRequest();
        req.setCredentialId("c1");
        req.setPath(List.of());

        BrowseResult r = svc.browse(req, null);

        assertThat(r.children()).extracting(BrowseResult.Node::name)
                .contains("t-default", "platform", "docker.io", "quay.io");
        assertThat(r.children())
                .filteredOn(n -> n.name().equals("t-default")).singleElement()
                .satisfies(n -> assertThat(n.badge()).isEqualTo("租户"));
        assertThat(r.children())
                .filteredOn(n -> n.name().equals("platform")).singleElement()
                .satisfies(n -> assertThat(n.badge()).isEqualTo("公开读"));
        assertThat(r.children())
                .filteredOn(n -> !n.name().equals("t-default"))
                .allSatisfy(n -> assertThat(n.badge()).isEqualTo("公开读"));
    }
}
