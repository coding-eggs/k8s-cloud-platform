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

    @Test
    void convert_for_update_does_not_stamp_managed_by_on_foreign_namespace() {
        // 非平台 ns（线上无 managed-by）编辑后不得被补盖——provenance 保持诚实
        Map<String, String> liveLabels = new LinkedHashMap<>();
        liveLabels.put("team", "sre");   // 外部标签，无 managed-by
        Namespace live = live("ns7", liveLabels, new LinkedHashMap<>(), "Active");

        NamespaceDTO dto = new NamespaceDTO();
        dto.setName("ns7");
        dto.setDescription("新描述");
        dto.setLabels(new LinkedHashMap<>(Map.of("env", "prod")));

        Namespace out = c.convertForUpdate(dto, live);
        assertThat(out.getMetadata().getLabels())
                .doesNotContainKey(CoreV1NamespaceConverter.MANAGED_BY_LABEL)   // 未被补盖
                .containsEntry("team", "sre")                                   // 外部标签存活
                .containsEntry("env", "prod");                                   // DTO 新增
        assertThat(out.getMetadata().getAnnotations()).containsEntry("description", "新描述");
    }

    // ---------- Calico 绑定池（cni.projectcalico.org/ipv{4,6}pools） ----------

    @Test
    void convert_writes_pool_annotations_as_json_array_when_present() {
        NamespaceDTO dto = new NamespaceDTO();
        dto.setName("ns8");
        dto.setIpv4Pools(java.util.List.of("default-ipv4-pool", "biz-pool"));
        Namespace out = c.convert(dto);
        assertThat(out.getMetadata().getAnnotations())
                .containsEntry(CoreV1NamespaceConverter.ANNOTATION_IPV4_POOLS, "[\"default-ipv4-pool\",\"biz-pool\"]")
                .doesNotContainKey(CoreV1NamespaceConverter.ANNOTATION_IPV6_POOLS);
    }

    @Test
    void revert_parses_pool_annotations_json_and_comma_fallback() {
        Map<String, String> ann = new LinkedHashMap<>();
        ann.put(CoreV1NamespaceConverter.ANNOTATION_IPV4_POOLS, "[\"p1\",\"p2\"]");
        ann.put(CoreV1NamespaceConverter.ANNOTATION_IPV6_POOLS, "v6a,v6b"); // 旧逗号写法兼容
        NamespaceDTO d = c.revert(live("ns9", new LinkedHashMap<>(), ann, "Active"));
        assertThat(d.getIpv4Pools()).containsExactly("p1", "p2");
        assertThat(d.getIpv6Pools()).containsExactly("v6a", "v6b");
    }

    @Test
    void revert_no_pool_annotations_yields_null_lists() {
        NamespaceDTO d = c.revert(live("ns10", new LinkedHashMap<>(), new LinkedHashMap<>(), "Active"));
        assertThat(d.getIpv4Pools()).isNull();
        assertThat(d.getIpv6Pools()).isNull();
    }

    @Test
    void convert_for_update_overwrites_pools_and_empty_clears_binding() {
        Map<String, String> liveAnn = new LinkedHashMap<>();
        liveAnn.put(CoreV1NamespaceConverter.ANNOTATION_IPV4_POOLS, "[\"old-pool\"]");
        Namespace live = live("ns11", new LinkedHashMap<>(), liveAnn, "Active");

        // 非空 → 覆写
        NamespaceDTO dto = new NamespaceDTO();
        dto.setName("ns11");
        dto.setIpv4Pools(java.util.List.of("new-pool"));
        Namespace out = c.convertForUpdate(dto, live);
        assertThat(out.getMetadata().getAnnotations())
                .containsEntry(CoreV1NamespaceConverter.ANNOTATION_IPV4_POOLS, "[\"new-pool\"]");

        // 显式空列表 → 删除（恢复默认分配）；第三方 annotation 存活
        Map<String, String> liveAnn2 = new LinkedHashMap<>();
        liveAnn2.put(CoreV1NamespaceConverter.ANNOTATION_IPV6_POOLS, "[\"v6-old\"]");
        liveAnn2.put("keep", "me");
        NamespaceDTO dto2 = new NamespaceDTO();
        dto2.setName("ns11");
        dto2.setIpv6Pools(java.util.List.of()); // 显式空 = 主动清空
        Namespace out2 = c.convertForUpdate(dto2, live("ns11", new LinkedHashMap<>(), liveAnn2, "Active"));
        assertThat(out2.getMetadata().getAnnotations())
                .doesNotContainKey(CoreV1NamespaceConverter.ANNOTATION_IPV6_POOLS)
                .containsEntry("keep", "me");

        // null = 未传 → 保持现状（capability 未探测的客户端不能误清绑定）
        NamespaceDTO dto3 = new NamespaceDTO();
        dto3.setName("ns11");
        Namespace out3 = c.convertForUpdate(dto3, live("ns11", new LinkedHashMap<>(), liveAnn2, "Active"));
        assertThat(out3.getMetadata().getAnnotations())
                .containsEntry(CoreV1NamespaceConverter.ANNOTATION_IPV6_POOLS, "[\"v6-old\"]");
    }
}