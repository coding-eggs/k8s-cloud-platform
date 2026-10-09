package com.coding.k8score.converter.impl.gateway;

import com.coding.common.models.k8s.dto.GatewayClassDTO;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GatewayClassConverterTest {

    private final GatewayClassConverter c = new GatewayClassConverter();

    @SuppressWarnings("unchecked")
    private static Map<String, Object> specOf(GenericKubernetesResource res) {
        return (Map<String, Object>) res.getAdditionalProperties().get("spec");
    }

    private GenericKubernetesResource live(String name, Map<String, Object> spec) {
        GenericKubernetesResource res = new GenericKubernetesResource();
        res.setMetadata(new ObjectMetaBuilder().withName(name).build());
        res.setAdditionalProperty("spec", spec);
        return res;
    }

    @Test
    @SuppressWarnings("unchecked")
    void convert_emits_cluster_scoped_spec() {
        GatewayClassDTO dto = new GatewayClassDTO();
        dto.setName("istio");
        dto.setLabels(Map.of("app", "mesh"));
        dto.setControllerName("istio.io/gateway-controller");
        dto.setDescription("Istio 的默认 GatewayClass");
        GatewayClassDTO.ParametersRef ref = new GatewayClassDTO.ParametersRef();
        ref.setGroup("istio.io");
        ref.setKind("IstioOperator");
        ref.setName("mesh-config");
        ref.setNamespace("istio-system");
        dto.setParametersRef(ref);

        GenericKubernetesResource res = c.convert(dto);

        assertThat(res.getApiVersion()).isEqualTo("gateway.networking.k8s.io/v1");
        assertThat(res.getKind()).isEqualTo("GatewayClass");
        // cluster-scoped：无 namespace
        assertThat(res.getMetadata().getNamespace()).isNull();
        Map<String, Object> spec = specOf(res);
        assertThat(spec).containsEntry("controllerName", "istio.io/gateway-controller")
                .containsEntry("description", "Istio 的默认 GatewayClass");
        Map<String, Object> refMap = (Map<String, Object>) spec.get("parametersRef");
        assertThat(refMap).containsEntry("group", "istio.io")
                .containsEntry("kind", "IstioOperator")
                .containsEntry("name", "mesh-config")
                .containsEntry("namespace", "istio-system");
    }

    @Test
    void convert_omits_null_parametersRef_and_description() {
        GatewayClassDTO dto = new GatewayClassDTO();
        dto.setName("gc");
        dto.setControllerName("example.com/c");

        Map<String, Object> spec = specOf(c.convert(dto));
        assertThat(spec).containsKey("controllerName")
                .doesNotContainKeys("parametersRef", "description");
    }

    @Test
    void revert_reads_spec_and_status_conditions() {
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("controllerName", "istio.io/gateway-controller");
        spec.put("description", "desc");
        spec.put("parametersRef", Map.of("group", "g", "kind", "K", "name", "n"));
        GenericKubernetesResource res = live("istio", spec);
        res.getMetadata().setCreationTimestamp("2026-01-01T00:00:00Z");
        res.setAdditionalProperty("status", Map.of(
                "conditions", List.of(Map.of("type", "Accepted", "status", "True", "reason", "Accepted"))));

        GatewayClassDTO dto = c.revert(res);

        assertThat(dto.getName()).isEqualTo("istio");
        assertThat(dto.getCreationTime()).isEqualTo("2026-01-01T00:00:00Z");
        assertThat(dto.getControllerName()).isEqualTo("istio.io/gateway-controller");
        assertThat(dto.getDescription()).isEqualTo("desc");
        assertThat(dto.getParametersRef().getName()).isEqualTo("n");
        assertThat(dto.getParametersRef().getNamespace()).isNull();
        assertThat(dto.getConditions()).hasSize(1);
        assertThat(dto.getConditions().get(0).getType()).isEqualTo("Accepted");
    }

    @Test
    void revert_null_resource_returns_empty_dto() {
        GatewayClassDTO dto = c.revert(null);
        assertThat(dto.getName()).isNull();
        assertThat(dto.getControllerName()).isNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void convertForUpdate_clears_removed_parametersRef_but_keeps_unmodeled_spec_keys() {
        Map<String, Object> liveSpec = new LinkedHashMap<>();
        liveSpec.put("controllerName", "old/controller");
        liveSpec.put("parametersRef", Map.of("name", "old"));
        liveSpec.put("someFutureField", Map.of("a", 1)); // 未建模外部字段
        GenericKubernetesResource live = live("gc", liveSpec);

        GatewayClassDTO dto = new GatewayClassDTO();
        dto.setName("gc");
        dto.setControllerName("new/controller");
        dto.setParametersRef(null); // 用户清空 parametersRef

        Map<String, Object> spec = specOf(c.convertForUpdate(dto, live));

        assertThat(spec).containsEntry("controllerName", "new/controller")
                .doesNotContainKey("parametersRef")               // 清空 → 整键移除（不能留旧值）
                .containsEntry("someFutureField", Map.of("a", 1)); // 未建模键保留
    }

}
