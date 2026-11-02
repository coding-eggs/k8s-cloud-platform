package com.coding.k8sserver.controllers.cluster;

import com.coding.common.models.k8s.ResourceType;
import com.coding.common.models.k8s.dto.VolumeSnapshotClassDTO;
import com.coding.k8score.factory.KubernetesOperationsFactory;
import com.coding.k8score.operations.ClusterOperations;
import com.coding.k8sserver.components.AccessBoundary;
import com.coding.k8sserver.components.ResourceAccessResolver;
import io.fabric8.kubernetes.client.KubernetesClientException;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code POST /volumesnapshotclasses/options}：租户创建/恢复快照前要知道快照类，但 /volumesnapshotclasses/** 是
 * PLATFORM 边界，所以走这条 UPSTREAM 窄投影。钉住三件事：不回 parameters、CRD 缺失不当错误、其它失败不吞。
 */
class VolumeSnapshotClassOptionsRouteTest {

    private final KubernetesOperationsFactory factory = mock(KubernetesOperationsFactory.class);
    private final ResourceAccessResolver resolver = mock(ResourceAccessResolver.class);

    @SuppressWarnings("unchecked")
    private final ClusterOperations<VolumeSnapshotClassDTO> ops = mock(ClusterOperations.class);

    private MockMvc mvc() {
        when(factory.<VolumeSnapshotClassDTO>getClusterOperation(ResourceType.VOLUME_SNAPSHOT_CLASS, "c1"))
                .thenReturn(ops);
        return MockMvcBuilders.standaloneSetup(
                new VolumeSnapshotClassOptionsController(factory, resolver),
                new VolumeSnapshotClassController(factory, resolver)
        ).build();
    }

    private static final String BODY = "{\"clusterId\":\"c1\"}";

    @Test
    void optionsNeverExposeParameters() throws Exception {
        VolumeSnapshotClassDTO cls = new VolumeSnapshotClassDTO();
        cls.setName("nfs-csi-snapclass");
        cls.setDriver("nfs.csi.k8s.io");
        cls.setDeletionPolicy("Delete");
        cls.setIsDefault(true);
        cls.setParameters(Map.of("csi.storage.k8s.io/snapshotter-secret-name", "s"));
        when(ops.list(any(), any())).thenReturn(List.of(cls));

        mvc().perform(post("/volumesnapshotclasses/options")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.crdInstalled").value(true))
                .andExpect(jsonPath("$.data.classes[0].name").value("nfs-csi-snapclass"))
                .andExpect(jsonPath("$.data.classes[0].driver").value("nfs.csi.k8s.io"))
                .andExpect(jsonPath("$.data.classes[0].deletionPolicy").value("Delete"))
                .andExpect(jsonPath("$.data.classes[0].isDefault").value(true))
                .andExpect(jsonPath("$.data.classes[0].parameters").doesNotExist());
        verify(resolver).assertClusterAccess("c1");
    }

    @Test
    void missingCrdIsNotAnError() throws Exception {
        when(ops.list(any(), any())).thenThrow(new KubernetesClientException("not found", 404, null));

        mvc().perform(post("/volumesnapshotclasses/options")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.crdInstalled").value(false))
                .andExpect(jsonPath("$.data.classes").isEmpty());
    }

    @Test
    void otherFailuresPropagate() {
        when(ops.list(any(), any())).thenThrow(new KubernetesClientException("boom", 500, null));
        MockMvc mvc = mvc();

        // standalone MockMvc 没有全局异常处理器：未被吞的异常会直接抛出来
        assertThatThrownBy(() -> mvc.perform(post("/volumesnapshotclasses/options")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY)))
                .hasRootCauseInstanceOf(KubernetesClientException.class);
    }

    @Test
    void boundaryIsUpstream() {
        assertThat(new VolumeSnapshotClassOptionsController(factory, resolver).accessBoundary())
                .isEqualTo(AccessBoundary.UPSTREAM);
    }
}
