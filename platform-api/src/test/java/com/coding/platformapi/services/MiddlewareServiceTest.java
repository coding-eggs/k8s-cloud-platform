package com.coding.platformapi.services;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.models.middleware.MiddlewareCreateRequestDTO;
import com.coding.common.models.middleware.MiddlewareOperatorStatusDTO;
import com.coding.data.mapper.system.PlatformMiddlewareInstanceMapper;
import com.coding.data.mapper.system.PlatformMiddlewareOperationMapper;
import com.coding.data.mapper.system.PlatformMiddlewareOperatorMapper;
import com.coding.data.models.system.PlatformMiddlewareInstance;
import com.coding.data.models.system.PlatformMiddlewareOperation;
import com.coding.platformapi.k8s.K8sMiddlewareClient;
import com.coding.platformapi.middleware.MiddlewareCatalog;
import com.coding.platformapi.models.MiddlewareCreateRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MiddlewareServiceTest {

    private final PlatformMiddlewareInstanceMapper instances = mock(PlatformMiddlewareInstanceMapper.class);
    private final PlatformMiddlewareOperationMapper operations = mock(PlatformMiddlewareOperationMapper.class);
    private final PlatformMiddlewareOperatorMapper operators = mock(PlatformMiddlewareOperatorMapper.class);
    private final K8sMiddlewareClient client = mock(K8sMiddlewareClient.class);

    private final MiddlewareService service = new MiddlewareService(
            instances, operations, operators, client, new MiddlewareCatalog(),
            JsonMapper.builder().build());

    @BeforeEach
    void opsGetIds() {
        doAnswer(inv -> {
            inv.getArgument(0, PlatformMiddlewareOperation.class).setId(1L);
            return 1;
        }).when(operations).insert(any());
    }

    private MiddlewareCreateRequest createRequest() {
        MiddlewareCreateRequest r = new MiddlewareCreateRequest();
        r.setClusterId("c1");
        r.setCode("redis");
        r.setMode("STANDALONE");
        r.setNamespace("demo");
        r.setReplicas(1);
        r.setCpuRequest("100m");
        r.setCpuLimit("500m");
        r.setMemoryRequest("128Mi");
        r.setMemoryLimit("512Mi");
        r.setStorageSize("10Gi");
        return r;
    }

    private MiddlewareOperatorStatusDTO status(String code, boolean installed, boolean ready) {
        MiddlewareOperatorStatusDTO s = new MiddlewareOperatorStatusDTO();
        s.setCode(code);
        s.setInstalled(installed);
        s.setReady(ready);
        s.setChartVersion("0.27.0");
        s.setMessage(ready ? "运行中" : "CRD 未就绪");
        return s;
    }

    @Test
    void createRejectsWhenOperatorIsNotReady() {
        when(client.operatorStatus(any())).thenReturn(List.of(status("redis", false, false)));

        assertThatThrownBy(() -> service.create(createRequest(), "admin", null))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("Operator");
    }

    @Test
    void createGeneratesNameAndPasswordButStoresNoPassword() {
        when(client.operatorStatus(any())).thenReturn(List.of(status("redis", true, true)));
        when(client.create(anyString(), any())).thenReturn(true);

        Long opId = service.create(createRequest(), "admin", "t1");

        assertThat(opId).isEqualTo(1L);

        ArgumentCaptor<MiddlewareCreateRequestDTO> sent = ArgumentCaptor.forClass(MiddlewareCreateRequestDTO.class);
        verify(client).create(anyString(), sent.capture());
        assertThat(sent.getValue().getName()).startsWith("mw-redis-");
        assertThat(sent.getValue().getPassword()).isNotBlank();

        ArgumentCaptor<PlatformMiddlewareInstance> saved = ArgumentCaptor.forClass(PlatformMiddlewareInstance.class);
        verify(instances).insert(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo("CREATING");
        assertThat(saved.getValue().getTenantId()).isEqualTo("t1");
        assertThat(saved.getValue().getSpecJson()).doesNotContain(sent.getValue().getPassword());
    }

    @Test
    void createMarksInstanceFailedWhenK8sRejects() {
        when(client.operatorStatus(any())).thenReturn(List.of(status("redis", true, true)));
        when(client.create(anyString(), any())).thenThrow(new CloudPlatformException(
                com.coding.common.exception.EnumResponseType.ERROR, "命名空间配额不足"));

        assertThatThrownBy(() -> service.create(createRequest(), "admin", null))
                .hasMessageContaining("配额");

        ArgumentCaptor<PlatformMiddlewareInstance> saved = ArgumentCaptor.forClass(PlatformMiddlewareInstance.class);
        verify(instances).updateById(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo("FAILED");
    }

    @Test
    void catalogMergesOperatorStatusIntoItems() {
        when(client.operatorStatus(any())).thenReturn(List.of(status("redis", true, true)));
        when(instances.selectByCluster("c1", null)).thenReturn(List.of());

        var items = service.catalog("c1");

        assertThat(items).anyMatch(i -> i.getCode().equals("redis")
                && i.isOperatorReady() && "0.27.0".equals(i.getChartVersion()));
        assertThat(items).anyMatch(i -> i.getCode().equals("mysql") && i.getModes().isEmpty());
    }

    @Test
    void tenantCanOnlySeeItsOwnInstances() {
        PlatformMiddlewareInstance mine = new PlatformMiddlewareInstance();
        mine.setId("mwi_1");
        mine.setTenantId("t1");
        PlatformMiddlewareInstance other = new PlatformMiddlewareInstance();
        other.setId("mwi_2");
        other.setTenantId("t2");
        when(instances.selectByCluster("c1", null)).thenReturn(List.of(mine, other));

        assertThat(service.list("c1", null, "t1"))
                .extracting(PlatformMiddlewareInstance::getId).containsExactly("mwi_1");
    }

    @Test
    void getRejectsAnotherTenantsInstance() {
        PlatformMiddlewareInstance other = new PlatformMiddlewareInstance();
        other.setId("mwi_2");
        other.setTenantId("t2");
        when(instances.selectByPrimaryKey("mwi_2")).thenReturn(other);

        assertThatThrownBy(() -> service.get("mwi_2", "t1"))
                .hasMessageContaining("无权");
    }
}
