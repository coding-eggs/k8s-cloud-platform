package com.coding.k8score.converter.impl.calico;

import com.coding.common.models.k8s.dto.IpoolDTO;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class IppoolConverterTest {

    private final IppoolConverter c = new IppoolConverter();

    @SuppressWarnings("unchecked")
    private static Map<String, Object> specOf(GenericKubernetesResource res) {
        return (Map<String, Object>) res.getAdditionalProperties().get("spec");
    }

    private IpoolDTO sample() {
        IpoolDTO d = new IpoolDTO();
        d.setName("default-ipv4-pool");
        d.setLabels(Map.of("team", "net"));
        d.setCidr("10.48.0.0/16");
        d.setBlockSize(26);
        d.setNodeSelector(List.of("projectcalico.org/node==worker"));
        d.setNatOutgoing(true);
        d.setDisabled(false);
        d.setIpv4hierarchicalPortAllocation(false);
        d.setBlocks(List.of("10.48.1.0/24"));
        return d;
    }

    @Test
    void convert_emits_cluster_scoped_spec_and_kind() {
        GenericKubernetesResource res = c.convert(sample());
        assertThat(res.getApiVersion()).isEqualTo("projectcalico.org/v3");
        assertThat(res.getKind()).isEqualTo("IPPool");
        // cluster-scoped：无 namespace
        assertThat(res.getMetadata().getNamespace()).isNull();
        Map<String, Object> spec = specOf(res);
        assertThat(spec).containsEntry("cidr", "10.48.0.0/16")
                .containsEntry("blockSize", 26)
                .containsEntry("natOutgoing", true)
                .containsEntry("disabled", false);
        assertThat(spec.get("nodeSelector")).isEqualTo(List.of("projectcalico.org/node==worker"));
        assertThat(spec.get("blocks")).isEqualTo(List.of("10.48.1.0/24"));
    }

    @Test
    void convert_omits_null_optionals() {
        IpoolDTO d = new IpoolDTO();
        d.setName("p");
        d.setCidr("192.168.0.0/24");
        Map<String, Object> spec = specOf(c.convert(d));
        assertThat(spec).containsKey("cidr")
                .doesNotContainKeys("blockSize", "nodeSelector", "natOutgoing", "disabled", "blocks");
    }

    @Test
    void revert_reads_spec_and_status_conditions() {
        GenericKubernetesResource res = new GenericKubernetesResource();
        res.setApiVersion("projectcalico.org/v3");
        res.setKind("IPPool");
        res.setMetadata(new ObjectMetaBuilder().withName("p1")
                .withLabels(Map.of("team", "net"))
                .withCreationTimestamp("2026-01-01T00:00:00Z").build());
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("cidr", "10.0.0.0/8");
        spec.put("blockSize", 24);
        spec.put("natOutgoing", true);
        spec.put("disabled", true);
        spec.put("nodeSelector", List.of("k==v"));
        res.setAdditionalProperty("spec", spec);
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("conditions", List.of(Map.of("type", "Available", "status", "True", "reason", "Ready")));
        res.setAdditionalProperty("status", status);

        IpoolDTO dto = c.revert(res);
        assertThat(dto.getName()).isEqualTo("p1");
        assertThat(dto.getCreationTime()).isEqualTo("2026-01-01T00:00:00Z");
        assertThat(dto.getCidr()).isEqualTo("10.0.0.0/8");
        assertThat(dto.getBlockSize()).isEqualTo(24);
        assertThat(dto.getNatOutgoing()).isTrue();
        assertThat(dto.getDisabled()).isTrue();
        assertThat(dto.getNodeSelector()).containsExactly("k==v");
        assertThat(dto.getConditions()).hasSize(1);
        assertThat(dto.getConditions().get(0).getType()).isEqualTo("Available");
    }

    @Test
    void revert_null_resource_returns_empty_dto() {
        IpoolDTO dto = c.revert(null);
        assertThat(dto.getName()).isNull();
        assertThat(dto.getCidr()).isNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void convertForUpdate_preserves_unmodeled_spec_fields_and_overrides_modeled() {
        // 线上 spec：建模字段旧值 + 外部字段（Calico 未来新增的 spec 键）
        Map<String, Object> liveSpec = new LinkedHashMap<>();
        liveSpec.put("cidr", "10.48.0.0/16");
        liveSpec.put("blockSize", 26);
        liveSpec.put("someFutureField", Map.of("a", 1)); // 未建模外部字段
        GenericKubernetesResource live = new GenericKubernetesResource();
        live.setMetadata(new ObjectMetaBuilder().withName("p1").build());
        live.setAdditionalProperty("spec", liveSpec);

        // dto：改 cidr、清空 blockSize（null）
        IpoolDTO dto = new IpoolDTO();
        dto.setName("p1");
        dto.setCidr("10.49.0.0/16");
        dto.setBlockSize(null);

        GenericKubernetesResource out = c.convertForUpdate(dto, live);
        Map<String, Object> spec = specOf(out);
        assertThat(spec).containsEntry("cidr", "10.49.0.0/16")   // 建模字段被覆盖
                .doesNotContainKey("blockSize")                    // dto null → 移除
                .containsEntry("someFutureField", Map.of("a", 1)); // 未建模外部字段原样保留
    }

}
