package com.coding.platformapi.services;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.models.k8s.dto.HpaDTO;
import com.coding.common.models.k8s.dto.HpaCrossVersionObjectReferenceDTO;
import com.coding.platformapi.k8s.K8sResourceClient;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HpaServiceTest {

    private final K8sResourceClient k8s = mock(K8sResourceClient.class);
    private final HpaService svc = new HpaService(k8s);

    private HpaDTO hpa(String name, String kind, String targetName) {
        HpaDTO d = new HpaDTO();
        d.setName(name);
        d.setNamespace("ns1");
        d.setTenantId("t1");
        d.setClusterId("c1");
        if (kind != null) {
            HpaCrossVersionObjectReferenceDTO ref = new HpaCrossVersionObjectReferenceDTO();
            ref.setKind(kind);
            ref.setName(targetName);
            d.setScaleTargetRef(ref);
        }
        return d;
    }

    @Test
    void create_blocked_when_target_already_bound() {
        when(k8s.list(any(HpaDTO.class))).thenReturn(List.of(hpa("hpa-existing", "Deployment", "app")));
        when(k8s.create(any(HpaDTO.class))).thenAnswer(i -> i.getArgument(0));
        HpaDTO body = hpa("hpa-new", "Deployment", "app");
        assertThatThrownBy(() -> svc.create(body))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("hpa-existing");
        verify(k8s, never()).create(any(HpaDTO.class));
    }

    @Test
    void update_allowed_for_self() {
        when(k8s.list(any(HpaDTO.class))).thenReturn(List.of(hpa("hpa-1", "Deployment", "app")));
        when(k8s.update(any(HpaDTO.class))).thenAnswer(i -> i.getArgument(0));
        HpaDTO body = hpa("hpa-1", "Deployment", "app");
        assertThat(svc.update(body).getName()).isEqualTo("hpa-1");
    }

    @Test
    void update_blocked_when_bound_to_other_hpa() {
        when(k8s.list(any(HpaDTO.class))).thenReturn(List.of(hpa("hpa-other", "StatefulSet", "db")));
        when(k8s.update(any(HpaDTO.class))).thenAnswer(i -> i.getArgument(0));
        HpaDTO body = hpa("hpa-1", "StatefulSet", "db");
        assertThatThrownBy(() -> svc.update(body))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("hpa-other");
        verify(k8s, never()).update(any(HpaDTO.class));
    }

    @Test
    void kind_matched_case_insensitively() {
        // 线上存的是 "Deployment"，body 传 "deployment" —— 仍须判重
        when(k8s.list(any(HpaDTO.class))).thenReturn(List.of(hpa("hpa-existing", "Deployment", "app")));
        HpaDTO body = hpa("hpa-new", "deployment", "app");
        assertThatThrownBy(() -> svc.create(body)).isInstanceOf(CloudPlatformException.class);
    }

    @Test
    void null_scaleTargetRef_passthrough_without_list() {
        when(k8s.create(any(HpaDTO.class))).thenAnswer(i -> i.getArgument(0));
        HpaDTO body = hpa("hpa-x", null, null);
        assertThat(svc.create(body).getName()).isEqualTo("hpa-x");
        verify(k8s, never()).list(any(HpaDTO.class));
    }
}
