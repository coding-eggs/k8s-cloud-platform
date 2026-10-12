package com.coding.k8sserver.controllers.cluster;

import com.coding.common.models.k8s.dto.NodeAddressDTO;
import com.coding.common.models.k8s.dto.NodeDTO;
import com.coding.k8score.factory.KubernetesClientFactory;
import com.coding.k8score.factory.KubernetesOperationsFactory;
import com.coding.k8score.operations.core.CoreV1NodeOperations;
import com.coding.k8sserver.components.ResourceAccessResolver;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 路由归属：{@code /nodes/addresses} 必须落到 {@link NodeAddressController}（窄投影），
 * 不能落到 {@link NodeController} 的 {@code /nodes/{name}} 上（那会把它当成"名为 addresses 的节点"）。
 *
 * <p>为什么要专门钉一条：这不是 Spring 的默认行为里显而易见的一格 —— 两个 controller 声明同一个
 * {@code /nodes} 前缀，字面路径（{@code /addresses}）与模板（{@code /{name}}）谁先匹配决定了这个页面上
 * "能不能拿节点 IP"。实测（本测试）字面路径优先，故按此依赖；一旦有人把 {@code /addresses} 改成
 * 参数化路径、或把窄投影挪进 {@code NodeController}，这里会当场红。
 *
 * <p>反向也钉一条：{@code /nodes/{name}} 不能被新端点抢走。
 */
class NodeAddressRouteTest {

    private final KubernetesOperationsFactory factory = mock(KubernetesOperationsFactory.class);
    private final ResourceAccessResolver resolver = mock(ResourceAccessResolver.class);
    private final CoreV1NodeOperations ops = mock(CoreV1NodeOperations.class);

    private MockMvc mvc() {
        when(factory.getNodeOperation(anyString())).thenReturn(ops);
        return MockMvcBuilders.standaloneSetup(
                new NodeAddressController(factory, resolver),
                new NodeController(factory, resolver, mock(KubernetesClientFactory.class))
        ).build();
    }

    @Test
    void addresses_path_hits_the_narrow_projection() throws Exception {
        NodeAddressDTO a = new NodeAddressDTO();
        a.setName("node-1");
        a.setInternalIp("10.0.0.11");
        when(ops.addresses()).thenReturn(List.of(a));
        // 若落到 NodeController.get，会走 ops.get("addresses") → 返回 null（下面这条就不会成立）
        when(ops.get(anyString())).thenReturn(null);

        mvc().perform(get("/nodes/addresses").param("clusterId", "c1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].name").value("node-1"))
                .andExpect(jsonPath("$.data[0].internalIp").value("10.0.0.11"));
    }

    @Test
    void node_detail_path_still_hits_the_node_controller() throws Exception {
        NodeDTO node = new NodeDTO();
        node.setName("node-1");
        when(ops.get("node-1")).thenReturn(node);

        mvc().perform(get("/nodes/node-1").param("clusterId", "c1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("node-1"));
    }
}
