package com.coding.k8sserver.controllers.namespace;

import com.coding.common.models.k8s.ResourceType;
import com.coding.common.models.k8s.dto.PrometheusRuleDTO;
import com.coding.k8score.factory.KubernetesOperationsFactory;
import com.coding.k8score.operations.NamespacedOperations;
import com.coding.k8sserver.components.AccessBoundary;
import com.coding.k8sserver.components.ResourceAccessResolver;
import com.coding.k8sserver.components.ResourceAccessResolver.AccessContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 六个标准端点的路由存在性与边界归属。上一批踩过「controller 没被扫到」，靠启动才发现 ——
 * 这条测试把六个端点逐个打一遍，缺一个当场红。
 */
class PrometheusRuleRouteTest {

    private final KubernetesOperationsFactory factory = mock(KubernetesOperationsFactory.class);
    private final ResourceAccessResolver resolver = mock(ResourceAccessResolver.class);
    @SuppressWarnings("unchecked")
    private final NamespacedOperations<PrometheusRuleDTO> ops = mock(NamespacedOperations.class);

    private static PrometheusRuleDTO rule() {
        PrometheusRuleDTO dto = new PrometheusRuleDTO();
        dto.setName("app-rules");
        dto.setNamespace("mon-app");
        return dto;
    }

    @BeforeEach
    void setUp() {
        when(resolver.resolveNamespacedAccess(null, "c1", "mon-app"))
                .thenReturn(new AccessContext("t1", false));
        doReturn(ops).when(factory).getNamespacedOperation(ResourceType.PROMETHEUS_RULE, "c1", "t1");
    }

    private MockMvc mvc() {
        return MockMvcBuilders.standaloneSetup(
                new PrometheusRuleController(factory, resolver)
        ).build();
    }

    @Test
    void list_endpoint_is_registered() throws Exception {
        when(ops.list("mon-app", null, null)).thenReturn(List.of(rule()));

        mvc().perform(post("/prometheusrules/list")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clusterId\":\"c1\",\"namespace\":\"mon-app\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].name").value("app-rules"));
    }

    @Test
    void get_endpoint_is_registered() throws Exception {
        when(ops.get("mon-app", "app-rules")).thenReturn(rule());

        mvc().perform(get("/prometheusrules/app-rules")
                        .param("clusterId", "c1").param("namespace", "mon-app"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("app-rules"));
    }

    @Test
    void yaml_endpoint_is_registered() throws Exception {
        when(ops.yaml("mon-app", "app-rules")).thenReturn("kind: PrometheusRule");

        mvc().perform(get("/prometheusrules/app-rules/yaml")
                        .param("clusterId", "c1").param("namespace", "mon-app"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value("kind: PrometheusRule"));
    }

    @Test
    void create_endpoint_is_registered() throws Exception {
        when(ops.create(any())).thenReturn(rule());

        mvc().perform(post("/prometheusrules")
                        .param("clusterId", "c1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"app-rules\",\"namespace\":\"mon-app\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("app-rules"));
    }

    @Test
    void update_endpoint_is_registered() throws Exception {
        when(ops.update(any())).thenReturn(rule());

        mvc().perform(put("/prometheusrules/app-rules")
                        .param("clusterId", "c1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"app-rules\",\"namespace\":\"mon-app\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("app-rules"));
    }

    @Test
    void delete_endpoint_is_registered() throws Exception {
        mvc().perform(delete("/prometheusrules/app-rules")
                        .param("clusterId", "c1").param("namespace", "mon-app"))
                .andExpect(status().isOk());
    }

    /** 边界是类属性：PrometheusRule 与 ServiceMonitor 一样走分配表（TENANT）。 */
    @Test
    void the_controller_declares_the_tenant_boundary() {
        assertThat(new PrometheusRuleController(factory, resolver).accessBoundary())
                .isEqualTo(AccessBoundary.TENANT);
    }
}
