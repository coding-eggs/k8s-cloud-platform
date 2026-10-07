package com.coding.k8score.converter.impl.calico;

import com.coding.common.models.k8s.dto.IpReservationDTO;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class IpReservationConverterTest {

    private final IpReservationConverter c = new IpReservationConverter();

    @SuppressWarnings("unchecked")
    private static Map<String, Object> specOf(GenericKubernetesResource res) {
        return (Map<String, Object>) res.getAdditionalProperties().get("spec");
    }

    private IpReservationDTO sample() {
        IpReservationDTO d = new IpReservationDTO();
        d.setName("vip-range-1");
        d.setLabels(Map.of("team", "net"));
        // 单 IP（/32）+ 范围（/28）两条
        d.setReservedCidrs(List.of("10.48.3.5/32", "10.48.3.64/28"));
        return d;
    }

    @Test
    void convert_emits_cluster_scoped_spec_and_kind() {
        GenericKubernetesResource res = c.convert(sample());
        assertThat(res.getApiVersion()).isEqualTo("projectcalico.org/v3");
        assertThat(res.getKind()).isEqualTo("IPReservation");
        // cluster-scoped：无 namespace
        assertThat(res.getMetadata().getNamespace()).isNull();
        Map<String, Object> spec = specOf(res);
        assertThat(spec).containsEntry("reservedCIDRs", List.of("10.48.3.5/32", "10.48.3.64/28"));
    }

    @Test
    void convert_blank_or_empty_cidrs_omits_reserved_field() {
        // 全空白 / 空列表 → spec 不含 reservedCIDRs（apiserver 侧即无保留项）
        IpReservationDTO d = new IpReservationDTO();
        d.setName("empty");
        d.setReservedCidrs(List.of("", "   "));
        assertThat(specOf(c.convert(d))).doesNotContainKey("reservedCIDRs");

        IpReservationDTO d2 = new IpReservationDTO();
        d2.setName("null");
        assertThat(specOf(c.convert(d2))).doesNotContainKey("reservedCIDRs");
    }

    @Test
    void revert_reads_reserved_cidrs() {
        GenericKubernetesResource res = new GenericKubernetesResource();
        res.setApiVersion("projectcalico.org/v3");
        res.setKind("IPReservation");
        res.setMetadata(new ObjectMetaBuilder().withName("r1")
                .withLabels(Map.of("team", "net"))
                .withCreationTimestamp("2026-01-01T00:00:00Z").build());
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("reservedCIDRs", List.of("fd00::5/128", "10.48.3.64/28"));
        res.setAdditionalProperty("spec", spec);

        IpReservationDTO dto = c.revert(res);
        assertThat(dto.getName()).isEqualTo("r1");
        assertThat(dto.getCreationTime()).isEqualTo("2026-01-01T00:00:00Z");
        assertThat(dto.getReservedCidrs()).containsExactly("fd00::5/128", "10.48.3.64/28");
    }

    @Test
    void revert_null_resource_returns_empty_dto() {
        IpReservationDTO dto = c.revert(null);
        assertThat(dto.getName()).isNull();
        assertThat(dto.getReservedCidrs()).isNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void convertForUpdate_preserves_unmodeled_spec_fields_and_overrides_modeled() {
        // 线上 spec：建模字段旧值 + 外部字段（Calico 未来新增的 spec 键）
        Map<String, Object> liveSpec = new LinkedHashMap<>();
        liveSpec.put("reservedCIDRs", List.of("10.48.3.5/32"));
        liveSpec.put("someFutureField", Map.of("a", 1)); // 未建模外部字段
        GenericKubernetesResource live = new GenericKubernetesResource();
        live.setMetadata(new ObjectMetaBuilder().withName("r1").build());
        live.setAdditionalProperty("spec", liveSpec);

        // dto：改为两条保留项
        IpReservationDTO dto = new IpReservationDTO();
        dto.setName("r1");
        dto.setReservedCidrs(List.of("10.48.3.7/32", "10.48.3.64/28"));

        GenericKubernetesResource out = c.convertForUpdate(dto, live);
        Map<String, Object> spec = specOf(out);
        assertThat(spec).containsEntry("reservedCIDRs", List.of("10.48.3.7/32", "10.48.3.64/28")) // 建模字段被覆盖
                .containsEntry("someFutureField", Map.of("a", 1)); // 未建模外部字段原样保留
    }

}
