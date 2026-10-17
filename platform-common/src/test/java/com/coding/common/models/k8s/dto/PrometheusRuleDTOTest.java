package com.coding.common.models.k8s.dto;

import com.coding.common.models.k8s.ResourceType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PrometheusRuleDTOTest {

    /** apiPath 由 DTO 内置 —— 与 ServiceMonitorDTO/PersistentVolumeClaimDTO 同一范式。 */
    @Test
    void api_path_is_built_into_the_dto() {
        assertThat(new PrometheusRuleDTO().getApiPath()).isEqualTo("/prometheusrules");
    }

    /** ResourceType 必须登记，否则 KubernetesOperationsFactory 的 switch 抛未匹配。 */
    @Test
    void resource_type_is_registered() {
        assertThat(ResourceType.valueOf("PROMETHEUS_RULE")).isNotNull();
    }
}
