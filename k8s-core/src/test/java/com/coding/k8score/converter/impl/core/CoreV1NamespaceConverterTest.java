package com.coding.k8score.converter.impl.core;

import com.coding.common.models.k8s.dto.NamespaceDTO;
import io.fabric8.kubernetes.api.model.Namespace;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CoreV1NamespaceConverterTest {

    private final CoreV1NamespaceConverter c = new CoreV1NamespaceConverter();

    private Namespace live(String name, Map<String, String> labels, Map<String, String> annotations, String phase) {
        return new NamespaceBuilder()
                .withNewMetadata()
                    .withName(name)
                    .withResourceVersion("1234")
                    .withCreationTimestamp("2026-09-01T00:00:00Z")
                    .withLabels(labels)
                    .withAnnotations(annotations)
                .endMetadata()
                .withNewStatus().withPhase(phase).endStatus()
                .build();
    }

    @Test
    void revert_maps_identity_phase_description_and_resource_version() {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("env", "prod");
        Map<String, String> ann = new LinkedHashMap<>();
        ann.put("description", "订单域");
        NamespaceDTO d = c.revert(live("ns1", labels, ann, "Active"));
        assertThat(d.getName()).isEqualTo("ns1");
        assertThat(d.getPhase()).isEqualTo("Active");
        assertThat(d.getCreationTimestamp()).isEqualTo("2026-09-01T00:00:00Z");
        assertThat(d.getResourceVersion()).isEqualTo("1234");
        assertThat(d.getDescription()).isEqualTo("订单域");
        assertThat(d.getLabels()).containsEntry("env", "prod");
    }

    @Test
    void revert_null_returns_null_and_missing_status_yields_null_phase() {
        assertThat(c.revert(null)).isNull();
        NamespaceDTO d = c.revert(live("ns2", Map.of(), Map.of(), null));
        assertThat(d.getPhase()).isNull();
        assertThat(d.getDescription()).isNull();
    }

    @Test
    void convert_stamps_managed_by_and_maps_description_to_annotation() {
        NamespaceDTO dto = new NamespaceDTO();
        dto.setName("ns3");
        dto.setDescription("支付域");
        dto.setLabels(new LinkedHashMap<>(Map.of("env", "dev")));
        Namespace out = c.convert(dto);
        assertThat(out.getMetadata().getName()).isEqualTo("ns3");
        assertThat(out.getMetadata().getLabels())
                .containsEntry("env", "dev")
                .containsEntry(CoreV1NamespaceConverter.MANAGED_BY_LABEL, CoreV1NamespaceConverter.MANAGED_BY_VALUE);
        assertThat(out.getMetadata().getAnnotations()).containsEntry("description", "支付域");
    }

    @Test
    void convert_with_blank_description_does_not_emit_empty_annotations_block() {
        NamespaceDTO dto = new NamespaceDTO();
        dto.setName("ns4");
        dto.setDescription("   ");
        assertThat(c.convert(dto).getMetadata().getAnnotations()).isNullOrEmpty();
    }

    @Test
    void convert_for_update_preserves_foreign_labels_and_annotations() {
        Map<String, String> liveLabels = new LinkedHashMap<>();
        liveLabels.put("kubernetes.io/metadata.name", "ns5");   // K8s 自动打的保留 label
        liveLabels.put("team", "sre");                            // 外部打的
        liveLabels.put(CoreV1NamespaceConverter.MANAGED_BY_LABEL, CoreV1NamespaceConverter.MANAGED_BY_VALUE);
        Map<String, String> liveAnn = new LinkedHashMap<>();
        liveAnn.put("field.cattle.io/description", "external-tool");  // 外部 annotation 必须存活
        liveAnn.put("description", "旧描述");
        Namespace live = live("ns5", liveLabels, liveAnn, "Active");

        NamespaceDTO dto = new NamespaceDTO();
        dto.setName("ns5");
        dto.setDescription("新描述");
        dto.setLabels(new LinkedHashMap<>(Map.of("env", "prod")));

        Namespace out = c.convertForUpdate(dto, live);
        assertThat(out.getMetadata().getAnnotations())
                .containsEntry("field.cattle.io/description", "external-tool")   // 外来存活
                .containsEntry("description", "新描述");                          // 覆盖
        assertThat(out.getMetadata().getLabels())
                .containsEntry("kubernetes.io/metadata.name", "ns5")              // 系统保留存活
                .containsEntry("team", "sre")                                     // 外部存活
                .containsEntry("env", "prod")                                     // DTO 新增
                .containsEntry(CoreV1NamespaceConverter.MANAGED_BY_LABEL, CoreV1NamespaceConverter.MANAGED_BY_VALUE);
    }

    @Test
    void convert_for_update_removes_description_when_cleared_but_keeps_others() {
        Map<String, String> liveAnn = new LinkedHashMap<>();
        liveAnn.put("description", "将被清空");
        liveAnn.put("keep", "me");
        Namespace live = live("ns6", new LinkedHashMap<>(), liveAnn, "Active");
        NamespaceDTO dto = new NamespaceDTO();
        dto.setName("ns6");
        Namespace out = c.convertForUpdate(dto, live);
        assertThat(out.getMetadata().getAnnotations())
                .doesNotContainKey("description")
                .containsEntry("keep", "me");
    }
}
