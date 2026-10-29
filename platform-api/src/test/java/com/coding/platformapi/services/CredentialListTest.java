package com.coding.platformapi.services;

import com.coding.data.mapper.auth.PlatformTenantMapper;
import com.coding.data.mapper.system.PlatformCredentialMapper;
import com.coding.data.mapper.system.PlatformRegistryProjectMapper;
import com.coding.data.models.system.PlatformCredential;
import com.coding.platformapi.credential.CredentialCipher;
import com.coding.platformapi.credential.CredentialProbeClient;
import com.coding.platformapi.models.CredentialView;
import com.coding.platformapi.registry.DockerHubDialect;
import com.coding.platformapi.registry.HarborDialect;
import com.coding.platformapi.registry.RegistryDialectRegistry;
import com.coding.platformapi.registry.RegistryHttpClient;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 列表按类型过滤 + 按租户可见性过滤（spec §6.2 / §6.4.1）。 */
class CredentialListTest {

    PlatformCredentialMapper mapper = mock(PlatformCredentialMapper.class);
    PlatformTenantMapper tenantMapper = mock(PlatformTenantMapper.class);
    PlatformRegistryProjectMapper projectMapper = mock(PlatformRegistryProjectMapper.class);
    CredentialService svc = new CredentialService(mapper,
            new CredentialCipher("test-only-master-key-0123456789abcdef"),
            mock(CredentialProbeClient.class),
            new RegistryDialectRegistry(List.of(new HarborDialect(mock(RegistryHttpClient.class)),
                    new DockerHubDialect(mock(RegistryHttpClient.class)))),
            tenantMapper, projectMapper,
            mock(com.coding.platformapi.registry.HarborInstallLocator.class));

    private static PlatformCredential cred(String id, String type, String endpoint,
                                           String tenantId, String source) {
        PlatformCredential c = new PlatformCredential();
        c.setId(id);
        c.setName(id);
        c.setType(type);
        c.setEndpoint(endpoint);
        c.setAuthType("BASIC");
        c.setEnabled(1);
        c.setUsableByPipelines(1);
        c.setTenantId(tenantId);
        c.setSource(source);
        return c;
    }

    @Test
    void filtersByType() {
        when(tenantMapper.listAll()).thenReturn(List.of());
        when(projectMapper.selectAll()).thenReturn(List.of());
        when(mapper.selectAll()).thenReturn(List.of(
                cred("g1", "GIT", "gitlab.corp", null, "MANUAL"),
                cred("h1", "HARBOR", "harbor.corp", null, "MANUAL")));

        List<CredentialView> git = svc.list("GIT", null);

        assertThat(git).extracting(CredentialView::getId).containsExactly("g1");
    }

    @Test
    void tenantSeesItsOwnPlusPlatformManualButNotTheSharedRobot() {
        when(tenantMapper.listAll()).thenReturn(List.of());
        when(projectMapper.selectAll()).thenReturn(List.of());
        when(mapper.selectAll()).thenReturn(List.of(
                cred("mine", "HARBOR", "harbor.corp", "t1", "MANUAL"),
                cred("public", "HARBOR", "public.corp", null, "MANUAL"),
                cred("robot", "HARBOR", "harbor.ackcp.cloud", null, "HARBOR"),
                cred("other", "HARBOR", "other.corp", "t2", "MANUAL")));

        assertThat(svc.list(null, "t1")).extracting(CredentialView::getId)
                .containsExactlyInAnyOrder("mine", "public");
        assertThat(svc.list(null, null)).hasSize(4);
    }

    @Test
    void hostIsDerivedFromTheEndpoint() {
        when(tenantMapper.listAll()).thenReturn(List.of());
        when(projectMapper.selectAll()).thenReturn(List.of());
        when(mapper.selectAll()).thenReturn(List.of(
                cred("h1", "HARBOR", "https://harbor.corp:8443/v2", null, "MANUAL")));

        assertThat(svc.list(null, null).get(0).getHost()).isEqualTo("harbor.corp:8443");
    }
}
