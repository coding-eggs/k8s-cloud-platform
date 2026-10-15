package com.coding.k8sserver.controllers.cluster;

import com.coding.common.models.k8s.ResourceType;
import com.coding.common.models.k8s.dto.StorageClassDTO;
import com.coding.k8score.factory.KubernetesOperationsFactory;
import com.coding.k8score.operations.ClusterOperations;
import com.coding.k8sserver.components.AccessBoundary;
import com.coding.k8sserver.components.ResourceAccessResolver;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 路由与边界：{@code POST /storageclasses/provisioners} 必须落到**窄投影**那个 controller。
 *
 * <p><b>为什么专门钉这两条</b>：CI 的能力门控对租户答不答得出真话，全看这里 ——
 * <ol>
 *   <li><b>路由</b>：它必须走到 {@link StorageClassProvisionerController}，而不是同前缀下
 *       {@link StorageClassController} 的 CRUD（后者边界是 {@code PLATFORM}，租户一调就 403）；</li>
 *   <li><b>边界</b>：必须是 {@code UPSTREAM} —— 声明 {@code PLATFORM} 就等于把租户挡在门外，
 *       而那正是 2026-10-15 修掉的那个 bug（页面永远说"没有可动态供应的 StorageClass"）。</li>
 * </ol>
 */
class StorageClassProvisionerRouteTest {

    private final KubernetesOperationsFactory factory = mock(KubernetesOperationsFactory.class);
    private final ResourceAccessResolver resolver = mock(ResourceAccessResolver.class);

    @SuppressWarnings("unchecked")
    private final ClusterOperations<StorageClassDTO> ops = mock(ClusterOperations.class);

    private MockMvc mvc() {
        when(factory.<StorageClassDTO>getClusterOperation(ResourceType.STORAGE_CLASS, "c1"))
                .thenReturn(ops);
        return MockMvcBuilders.standaloneSetup(
                new StorageClassProvisionerController(factory, resolver),
                new StorageClassController(factory, resolver)
        ).build();
    }

    @Test
    void the_narrow_projection_is_reachable_and_returns_only_name_and_provisioner() throws Exception {
        StorageClassDTO sc = new StorageClassDTO();
        sc.setName("local-path");
        sc.setProvisioner("rancher.io/local-path");
        when(ops.list(any(), any())).thenReturn(List.of(sc));

        mvc().perform(post("/storageclasses/provisioners")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clusterId\":\"c1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].name").value("local-path"))
                .andExpect(jsonPath("$.data[0].provisioner").value("rancher.io/local-path"));
    }

    @Test
    void the_boundary_is_upstream_so_tenants_can_ask() {
        // 声明成 PLATFORM（集群级资源的默认档）就等于只能平台管理员问 —— 而流水线页面是租户在用的。
        // 收窄责任在上游：platform-api 的 /pipeline/capability 挂 tenant:pipeline:read
        assertThat(new StorageClassProvisionerController(factory, resolver).accessBoundary())
                .isEqualTo(AccessBoundary.UPSTREAM);
    }
}
