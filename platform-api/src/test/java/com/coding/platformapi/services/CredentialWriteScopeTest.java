package com.coding.platformapi.services;

import com.coding.common.exception.CloudPlatformException;
import com.coding.data.mapper.auth.PlatformTenantMapper;
import com.coding.data.mapper.system.PlatformCredentialMapper;
import com.coding.data.mapper.system.PlatformRegistryProjectMapper;
import com.coding.data.models.system.PlatformCredential;
import com.coding.platformapi.credential.CredentialCipher;
import com.coding.platformapi.credential.CredentialProbeClient;
import com.coding.platformapi.models.CredentialCreateRequest;
import com.coding.platformapi.models.CredentialUpdateRequest;
import com.coding.platformapi.registry.DockerHubDialect;
import com.coding.platformapi.registry.HarborDialect;
import com.coding.platformapi.registry.HarborInstallLocator;
import com.coding.platformapi.registry.RegistryDialectRegistry;
import com.coding.platformapi.registry.RegistryHttpClient;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 写侧三条校验（spec §6.4.2）：归属由服务端定、内部 Harbor 拒建、不能改别人的行。 */
class CredentialWriteScopeTest {

    PlatformCredentialMapper mapper = mock(PlatformCredentialMapper.class);
    PlatformTenantMapper tenantMapper = mock(PlatformTenantMapper.class);
    PlatformRegistryProjectMapper projectMapper = mock(PlatformRegistryProjectMapper.class);
    HarborInstallLocator locator = mock(HarborInstallLocator.class);
    CredentialCipher cipher = new CredentialCipher("test-only-master-key-0123456789abcdef");
    CredentialService svc = new CredentialService(mapper,
            cipher,
            mock(CredentialProbeClient.class),
            new RegistryDialectRegistry(List.of(new HarborDialect(mock(RegistryHttpClient.class)),
                    new DockerHubDialect(mock(RegistryHttpClient.class)))),
            tenantMapper, projectMapper, locator);

    private static CredentialCreateRequest createReq(String type, String endpoint) {
        CredentialCreateRequest r = new CredentialCreateRequest();
        r.setName("n-" + endpoint);
        r.setType(type);
        r.setEndpoint(endpoint);
        r.setAuthType("BASIC");
        r.setUsername("u");
        r.setSecret("p");
        return r;
    }

    private PlatformCredential existing(String id, String tenantId, String source) {
        PlatformCredential c = new PlatformCredential();
        c.setId(id);
        c.setName("old");
        c.setType("HARBOR");
        c.setEndpoint("harbor.corp.x");
        c.setAuthType("BASIC");
        c.setEnabled(1);
        c.setUsableByPipelines(1);
        c.setSource(source);
        c.setTenantId(tenantId);
        // 已有一份**真密文**：「留空 = 保持不变」的语义要它才成立（update 会解密旧值沿用）
        String json = "{\"username\":\"u\",\"secret\":\"p\"}";
        c.setSecretEncrypted(cipher.encrypt(json));
        c.setSecretFingerprint(CredentialCipher.fingerprint(json));
        return c;
    }

    private static CredentialUpdateRequest updateReq() {
        CredentialUpdateRequest req = new CredentialUpdateRequest();
        req.setId("c1");
        req.setName("old");
        req.setType("HARBOR");
        req.setEndpoint("harbor.corp.x");
        req.setAuthType("BASIC");
        return req;
    }

    @Test
    void createStampsTheCallersTenant() {
        when(mapper.selectByName(any())).thenReturn(null);
        when(locator.isPlatformHost(any())).thenReturn(false);

        svc.create(createReq("HARBOR", "harbor.corp.x"), "t1");

        ArgumentCaptor<PlatformCredential> saved = ArgumentCaptor.forClass(PlatformCredential.class);
        verify(mapper).insert(saved.capture());
        assertThat(saved.getValue().getTenantId()).isEqualTo("t1");
    }

    @Test
    void adminCreatesPlatformLevelRows() {
        when(mapper.selectByName(any())).thenReturn(null);
        when(locator.isPlatformHost(any())).thenReturn(false);

        svc.create(createReq("HARBOR", "harbor.corp.x"), null);

        ArgumentCaptor<PlatformCredential> saved = ArgumentCaptor.forClass(PlatformCredential.class);
        verify(mapper).insert(saved.capture());
        assertThat(saved.getValue().getTenantId()).isNull();
    }

    @Test
    void refusesToCreateAConnectionPointingAtThePlatformHarbor() {
        when(locator.isPlatformHost("https://harbor.ackcp.cloud")).thenReturn(true);

        assertThatThrownBy(() -> svc.create(createReq("HARBOR", "https://harbor.ackcp.cloud"), "t1"))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("平台 Harbor");
        verify(mapper, never()).insert(any());
    }

    @Test
    void refusesToUpdateAnotherTenantsRow() {
        when(mapper.selectById("c1")).thenReturn(existing("c1", "t2", "MANUAL"));

        assertThatThrownBy(() -> svc.update(updateReq(), "t1"))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("别的租户");
        verify(mapper, never()).update(any());
    }

    @Test
    void refusesToTouchMachineManagedHarborRows() {
        when(mapper.selectById("c1")).thenReturn(existing("c1", "t1", "HARBOR"));

        assertThatThrownBy(() -> svc.update(updateReq(), "t1"))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("自动维护");
        assertThatThrownBy(() -> svc.delete("c1", "t1"))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("自动维护");
    }

    @Test
    void updateKeepsTheOriginalOwnerEvenWhenTheBodyOmitsEverything() {
        when(mapper.selectById("c1")).thenReturn(existing("c1", "t1", "MANUAL"));
        when(mapper.selectByName("old")).thenReturn(existing("c1", "t1", "MANUAL"));
        when(locator.isPlatformHost(any())).thenReturn(false);

        svc.update(updateReq(), "t1");

        ArgumentCaptor<PlatformCredential> saved = ArgumentCaptor.forClass(PlatformCredential.class);
        verify(mapper).update(saved.capture());
        assertThat(saved.getValue().getTenantId()).isEqualTo("t1");
        assertThat(saved.getValue().getUsableByPipelines()).isEqualTo(1);
    }
}
