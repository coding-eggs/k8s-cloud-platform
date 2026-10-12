package com.coding.platformapi.controllers;

import com.coding.common.models.k8s.dto.NodeAddressDTO;
import com.coding.common.models.k8s.dto.NodeDTO;
import com.coding.platformapi.metrics.MetricsService;
import com.coding.platformapi.services.NodeService;
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
 * 路由归属：{@code GET /nodes/addresses} 必须落到窄投影端点，而不是 {@code GET /nodes/{name}}
 * （后者会把它当成"名为 addresses 的节点"）。前半跳与后半跳各钉一条 —— 两跳 URL 同名是本仓约定
 * （backend-layering §7.3），任一跳落到模板上都拿不到数据，而失败模式是**静默**的（列表页 nodePort
 * 变不成链接）。
 */
class NodeAddressRouteTest {

    private final NodeService nodeService = mock(NodeService.class);
    private final MetricsService metricsService = mock(MetricsService.class);

    private MockMvc mvc() {
        return MockMvcBuilders.standaloneSetup(new NodeController(nodeService, metricsService)).build();
    }

    @Test
    void addresses_path_hits_the_narrow_projection() throws Exception {
        NodeAddressDTO a = new NodeAddressDTO();
        a.setName("node-1");
        a.setExternalIp("1.2.3.4");
        a.setReady(true);
        when(nodeService.addresses("c1")).thenReturn(List.of(a));
        // 若落到 /{name}，会走 nodeService.get("addresses", "c1") → null（下面这条即不成立）
        when(nodeService.get(anyString(), anyString())).thenReturn(null);

        mvc().perform(get("/nodes/addresses").param("clusterId", "c1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].name").value("node-1"))
                .andExpect(jsonPath("$.data[0].externalIp").value("1.2.3.4"));
    }

    @Test
    void node_detail_path_still_hits_the_node_service() throws Exception {
        NodeDTO node = new NodeDTO();
        node.setName("node-1");
        node.setInternalIp("10.0.0.11");
        when(nodeService.get("node-1", "c1")).thenReturn(node);

        mvc().perform(get("/nodes/node-1").param("clusterId", "c1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("node-1"));
    }
}
