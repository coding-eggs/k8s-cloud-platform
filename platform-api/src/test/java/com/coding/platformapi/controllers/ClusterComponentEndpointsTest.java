package com.coding.platformapi.controllers;

import com.coding.platformapi.models.ComponentFlagDTO;
import com.coding.platformapi.models.ComponentStatusDTO;
import com.coding.platformapi.services.ClusterComponentService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 三条组件端点的<b>路径与委托</b>。端点很薄，但路径写错只有
 * {@code PermissionCrossCheckRunner} 在真启动时才会兜住 —— 单测里先把这一格钉住。
 *
 * <p>三跳的权限码见 {@code V2026_10_17_4__cluster_components.sql}：
 * {@code /cluster/components} 与 {@code /cluster/components/refresh} 是
 * {@code platform:cluster:manage}；{@code /cluster/component-flags} <b>两行同一路径</b>
 * （管理员 + 租户只读码），少一行租户就吃 403。
 */
class ClusterComponentEndpointsTest {

    private final ClusterComponentService svc = mock(ClusterComponentService.class);

    private MockMvc mvc() {
        ClusterController controller = new ClusterController();
        ReflectionTestUtils.setField(controller, "componentService", svc);
        return MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void theFullProjectionPathDelegates() throws Exception {
        ComponentStatusDTO s = new ComponentStatusDTO();
        s.setCode("metrics-server");
        s.setState("NOT_INSTALLED");
        when(svc.status("c1")).thenReturn(List.of(s));

        mvc().perform(post("/cluster/components").contentType("application/json")
                        .content("{\"clusterId\":\"c1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].code").value("metrics-server"))
                .andExpect(jsonPath("$.data[0].state").value("NOT_INSTALLED"));

        verify(svc).status("c1");
    }

    @Test
    void theFlagsPathDelegates() throws Exception {
        when(svc.flags("c1")).thenReturn(Map.of("ci",
                new ComponentFlagDTO(false, "缺 Tekton Pipelines", List.of("Tekton Pipelines"))));

        mvc().perform(post("/cluster/component-flags").contentType("application/json")
                        .content("{\"clusterId\":\"c1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ci.available").value(false))
                .andExpect(jsonPath("$.data.ci.missing[0]").value("Tekton Pipelines"));

        verify(svc).flags("c1");
    }

    @Test
    void theRefreshPathDelegates() throws Exception {
        mvc().perform(post("/cluster/components/refresh").contentType("application/json")
                        .content("{\"clusterId\":\"c1\"}"))
                .andExpect(status().isOk());

        verify(svc).refresh("c1");
    }
}
