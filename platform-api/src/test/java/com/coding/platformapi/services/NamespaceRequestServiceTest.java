package com.coding.platformapi.services;

import com.coding.common.models.k8s.dto.admin.NamespaceRecoveryPreview;
import com.coding.common.models.k8s.dto.ResourceQuotaDTO;
import com.coding.common.security.AuthContext;
import com.coding.data.models.auth.NamespaceRequest;
import com.coding.data.mapper.auth.PlatformTenantNamespaceMapper;
import com.coding.data.models.auth.PlatformTenantNamespace;
import com.coding.platformapi.k8s.K8sLifecycleClient;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class NamespaceRequestServiceTest {
    private final AuthContext auth = mock(AuthContext.class);
    private final PlatformTenantNamespaceMapper allocations = mock(PlatformTenantNamespaceMapper.class);
    private final K8sLifecycleClient k8s = mock(K8sLifecycleClient.class);
    private final NamespaceService namespaces = mock(NamespaceService.class);
    private final NamespaceRequestService service = new NamespaceRequestService(auth, null, allocations,
            null, namespaces, k8s, null);

    @Test void appliesMultipleApprovedLimitsInOneQuotaUpdate() {
        var request = new NamespaceRequest();
        request.setClusterId("cluster");
        request.setNamespace("demo");
        request.setQuotaItems(List.of(
                new NamespaceRequest.QuotaItem("pods", BigDecimal.TEN, BigDecimal.valueOf(30), BigDecimal.valueOf(20)),
                new NamespaceRequest.QuotaItem("requestsCpu", BigDecimal.ONE, BigDecimal.valueOf(3), BigDecimal.valueOf(2))));
        var live = new ResourceQuotaDTO();
        live.setPods(10);
        live.setRequestsCpu(BigDecimal.ONE);
        live.setPersistentVolumeClaims(4);
        when(namespaces.quotaGet("cluster", "demo")).thenReturn(live);
        service.applyQuota(request);
        verify(namespaces).quotaUpsert(argThat(update -> update.getQuota().getPods() == 20
                && BigDecimal.valueOf(2).equals(update.getQuota().getRequestsCpu())
                && update.getQuota().getPersistentVolumeClaims() == 4));
        service.applyQuota(request);
        verify(namespaces, times(1)).quotaUpsert(any());
    }

    @Test void staleLimitPreventsWritingAnyOfTheRequestedItems() {
        var request = new NamespaceRequest();
        request.setClusterId("cluster");
        request.setNamespace("demo");
        request.setQuotaItems(List.of(
                new NamespaceRequest.QuotaItem("pods", BigDecimal.TEN, BigDecimal.valueOf(30), BigDecimal.valueOf(20)),
                new NamespaceRequest.QuotaItem("requestsCpu", BigDecimal.ONE, BigDecimal.valueOf(4), BigDecimal.valueOf(3))));
        var live = new ResourceQuotaDTO();
        live.setPods(10);
        live.setRequestsCpu(BigDecimal.TWO);
        when(namespaces.quotaGet("cluster", "demo")).thenReturn(live);
        assertThatThrownBy(() -> service.applyQuota(request)).hasMessageContaining("requestsCpu");
        assertThat(live.getPods()).isEqualTo(10);
        verify(namespaces, never()).quotaUpsert(any());
    }

    @Test void legacySingleItemUsesTheSameQuotaUpdate() {
        var request = new NamespaceRequest();
        request.setClusterId("cluster");
        request.setNamespace("demo");
        request.setQuotaKey("persistentVolumeClaims");
        request.setBaseline(BigDecimal.valueOf(4));
        request.setTargetValue(BigDecimal.TEN);
        request.setApprovedValue(BigDecimal.valueOf(6));
        var live = new ResourceQuotaDTO();
        live.setPersistentVolumeClaims(4);
        when(namespaces.quotaGet("cluster", "demo")).thenReturn(live);
        service.applyQuota(request);
        verify(namespaces).quotaUpsert(argThat(update -> update.getQuota().getPersistentVolumeClaims() == 6));
    }

    private void expiredSpace() {
        var row = new PlatformTenantNamespace();
        row.setId("allocation");
        row.setClusterId("cluster");
        row.setNamespace("demo");
        row.setLifecycle("EXPIRED");
        row.setExpiresAt(1L);
        row.setNamespaceUid("original");
        when(auth.isPlatformSide()).thenReturn(true);
        when(allocations.lock("allocation")).thenReturn(row);
        when(allocations.selectByPrimaryKey("allocation")).thenReturn(row);
        when(allocations.selectByClusterAndNs("cluster", "demo")).thenReturn(List.of(row));
        when(k8s.recoveryPreview("cluster", "demo")).thenReturn(new NamespaceRecoveryPreview("original", 3, List.of(), List.of()));
    }

    @Test void resourceRecoveryRequiresTheOriginalUid() {
        expiredSpace();
        assertThatThrownBy(() -> service.reclaim(new NamespaceRequestService.Recovery("allocation","replacement",""),false))
                .isInstanceOf(IllegalArgumentException.class);
        verify(allocations, never()).transition(anyString(), anyString(), any());
        service.reclaim(new NamespaceRequestService.Recovery("allocation","original",""),false);
        verify(allocations).transition("allocation","STOPPING",null);
    }

    @Test void deletionRequiresBothThePlatformIdentityAndTheExactSpaceName() {
        expiredSpace();
        var request = new NamespaceRequestService.Recovery("allocation","original","demo");
        when(auth.isPlatformSide()).thenReturn(false);
        assertThatThrownBy(() -> service.reclaim(request,true)).isInstanceOf(IllegalArgumentException.class);
        when(auth.isPlatformSide()).thenReturn(true);
        assertThatThrownBy(() -> service.reclaim(new NamespaceRequestService.Recovery("allocation","original","other"),true))
                .isInstanceOf(IllegalArgumentException.class);
        verify(allocations, never()).transition(anyString(), anyString(), any());
        service.reclaim(request,true);
        verify(allocations).transition("allocation","DELETING",null);
    }
}
