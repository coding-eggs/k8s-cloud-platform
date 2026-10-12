package com.coding.platformapi.services;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.models.k8s.dto.WorkloadDTO;
import com.coding.common.models.k8s.dto.WorkloadRevisionDTO;
import com.coding.platformapi.k8s.K8sClient;
import com.coding.platformapi.k8s.K8sWorkloadClient;
import com.coding.platformapi.models.WorkloadImageRequest;
import com.coding.platformapi.models.WorkloadRollbackRequest;
import com.coding.platformapi.services.validation.WorkloadValidator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 列表页三个行内动作的平台侧规则（mock client，不触网）。
 *
 * <p>钉的是"什么请求不该被放行"：空容器名/空镜像、缺版本号 —— 这些是发出去必然失败或必然误伤别人的写入，
 * 在本层拒掉而不是让它们走到 K8s。K8s 侧仍会再判一次容器是否真的存在（那边读的是线上对象，才是权威）。
 */
class WorkloadServiceActionsTest {

    private final K8sClient k8s = mock(K8sClient.class);
    private final K8sWorkloadClient workloadClient = mock(K8sWorkloadClient.class);
    private final WorkloadValidator validator = mock(WorkloadValidator.class);
    private final GatewayService gatewayService = mock(GatewayService.class);
    private final WorkloadService svc = new WorkloadService(k8s, workloadClient, validator, gatewayService);

    private WorkloadImageRequest imageRequest(String containerName, String image) {
        WorkloadImageRequest r = new WorkloadImageRequest();
        r.setTenantId("t1");
        r.setClusterId("c1");
        r.setNamespace("team-a");
        r.setContainerName(containerName);
        r.setImage(image);
        return r;
    }

    private WorkloadRollbackRequest rollbackRequest(Long revision) {
        WorkloadRollbackRequest r = new WorkloadRollbackRequest();
        r.setTenantId("t1");
        r.setClusterId("c1");
        r.setNamespace("team-a");
        r.setRevision(revision);
        return r;
    }

    @Test
    void update_image_rejects_blank_container_name() {
        assertThatThrownBy(() -> svc.updateImage("web", imageRequest("  ", "nginx:1.26")))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("容器名");

        verify(workloadClient, never()).updateImage(anyString(), any(), any(), any(), any(), any());
    }

    @Test
    void update_image_rejects_blank_image() {
        assertThatThrownBy(() -> svc.updateImage("web", imageRequest("app", " ")))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("镜像");

        verify(workloadClient, never()).updateImage(anyString(), any(), any(), any(), any(), any());
    }

    /** 镜像值两端空格是复制粘贴的常见产物，去掉再写（K8s 会因 'nginx:1.26 ' 报错或拉不到） */
    @Test
    void update_image_trims_image_value() {
        when(workloadClient.updateImage(anyString(), any(), any(), any(), any(), any())).thenReturn(new WorkloadDTO());

        svc.updateImage("web", imageRequest("app", " nginx:1.26 "));

        verify(workloadClient).updateImage("web", "t1", "c1", "team-a", "app", "nginx:1.26");
    }

    @Test
    void rollback_requires_revision() {
        assertThatThrownBy(() -> svc.rollback("web", rollbackRequest(null)))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("版本");

        verify(workloadClient, never()).rollback(anyString(), any(), any(), any(), any());
    }

    @Test
    void rollback_passes_target_revision_through() {
        when(workloadClient.rollback(anyString(), any(), any(), any(), any())).thenReturn(new WorkloadDTO());

        svc.rollback("web", rollbackRequest(3L));

        verify(workloadClient).rollback("web", "t1", "c1", "team-a", 3L);
    }

    @Test
    void revisions_returns_what_the_cluster_says() {
        WorkloadRevisionDTO r = new WorkloadRevisionDTO();
        r.setRevision(2L);
        when(workloadClient.revisions(anyString(), any(), any(), any())).thenReturn(List.of(r));

        assertThat(svc.revisions("web", "t1", "c1", "team-a")).extracting(WorkloadRevisionDTO::getRevision)
                .containsExactly(2L);
    }
}
