package com.coding.k8score.converter.impl.core;

import com.coding.common.models.k8s.dto.LimitRangeDTO;
import com.coding.common.models.k8s.dto.LimitRangeItemDTO;
import com.coding.common.models.k8s.dto.ResourcePairDTO;
import io.fabric8.kubernetes.api.model.LimitRange;
import io.fabric8.kubernetes.api.model.LimitRangeBuilder;
import io.fabric8.kubernetes.api.model.LimitRangeItem;
import io.fabric8.kubernetes.api.model.Quantity;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CoreV1LimitRangeConverterTest {

    private final CoreV1LimitRangeConverter c = new CoreV1LimitRangeConverter();

    private LimitRange live(LimitRangeItem... items) {
        return new LimitRangeBuilder()
                .withNewMetadata().withName("default").withNamespace("ns1").endMetadata()
                .withNewSpec().withLimits(List.of(items)).endSpec()
                .build();
    }

    private LimitRangeItem containerItem(String maxCpu, String minCpu) {
        return new io.fabric8.kubernetes.api.model.LimitRangeItemBuilder()
                .withType("Container")
                .withMax(Map.of("cpu", Quantity.parse(maxCpu)))
                .withMin(Map.of("cpu", Quantity.parse(minCpu)))
                .build();
    }

    @Test
    void revert_maps_items_and_base_units() {
        LimitRangeItem item = new io.fabric8.kubernetes.api.model.LimitRangeItemBuilder()
                .withType("Container")
                .withMax(Map.of("cpu", Quantity.parse("2"), "memory", Quantity.parse("1Gi")))
                .build();
        LimitRangeDTO d = c.revert(live(item));
        assertThat(d.getLimits()).hasSize(1);
        LimitRangeItemDTO it = d.getLimits().get(0);
        assertThat(it.getType()).isEqualTo("Container");
        assertThat(it.getMax().getCpu()).isEqualByComparingTo("2");
        assertThat(it.getMax().getMemory()).isEqualByComparingTo("1073741824");
    }

    @Test
    void revert_treats_empty_maps_as_null_not_empty_pairs() {
        // fabric8 坑：LimitRangeItem.getMax()/getDefault() 返回空可变 map 而非 null
        LimitRangeItem item = new io.fabric8.kubernetes.api.model.LimitRangeItemBuilder()
                .withType("Pod").withMax(Map.of()).build();
        LimitRangeItemDTO it = c.revert(live(item)).getLimits().get(0);
        assertThat(it.getMax()).isNull();
        assertThat(it.getDefaultValue()).isNull();
    }

    @Test
    void revert_null_returns_null() {
        assertThat(c.revert(null)).isNull();
    }

    @Test
    void convert_skips_blank_types_and_writes_default_key() {
        LimitRangeDTO d = new LimitRangeDTO();
        d.setName("default");
        d.setNamespace("ns1");
        LimitRangeItemDTO blank = new LimitRangeItemDTO();
        LimitRangeItemDTO ok = new LimitRangeItemDTO();
        ok.setType("Container");
        ResourcePairDTO def = new ResourcePairDTO();
        def.setCpu(new BigDecimal("0.5"));
        ok.setDefaultValue(def);
        d.setLimits(List.of(blank, ok));
        List<LimitRangeItem> out = c.convert(d).getSpec().getLimits();
        assertThat(out).hasSize(1);
        assertThat(out.get(0).getType()).isEqualTo("Container");
        assertThat(out.get(0).getDefault()).containsEntry("cpu", Quantity.parse("0.5"));
    }

    @Test
    void convert_for_update_aligns_by_type_not_index() {
        LimitRange live = live(
                containerItem("4", "1"),                                                   // Container（旧值；DTO 也给 → 按 type 匹配覆写）
                new io.fabric8.kubernetes.api.model.LimitRangeItemBuilder()
                        .withType("Pod").withMax(Map.of("cpu", Quantity.parse("8"))).build(),
                new io.fabric8.kubernetes.api.model.LimitRangeItemBuilder()
                        .withType("ContainerFixed").withMin(Map.of("cpu", Quantity.parse("100m"))).build()); // 未建模

        LimitRangeDTO d = new LimitRangeDTO();
        d.setName("default");
        d.setNamespace("ns1");
        // 两个建模类型都给，且 DTO 顺序 Pod 在前（≠ live 的 Container 在前）→ 证明按 type 对齐、非按 index
        LimitRangeItemDTO pod = new LimitRangeItemDTO();
        pod.setType("Pod");
        ResourcePairDTO podMax = new ResourcePairDTO();
        podMax.setCpu(new BigDecimal("16"));
        pod.setMax(podMax);
        LimitRangeItemDTO container = new LimitRangeItemDTO();
        container.setType("Container");
        ResourcePairDTO cMax = new ResourcePairDTO();
        cMax.setCpu(new BigDecimal("2"));
        container.setMax(cMax);            // 只给 max → live 的 min 应被字段级删除
        d.setLimits(List.of(pod, container));

        List<LimitRangeItem> out = c.convertForUpdate(d, live).getSpec().getLimits();
        // 规则：建模类型按 DTO 顺序（Pod, Container）→ 未建模类型按 live 顺序追加（ContainerFixed）
        assertThat(out).extracting(LimitRangeItem::getType).containsExactly("Pod", "Container", "ContainerFixed");
        assertThat(out.get(0).getMax()).containsEntry("cpu", Quantity.parse("16"));           // Pod 按 type 匹配覆写（live[0] 本是 Container，index 对齐会错拿它）
        assertThat(out.get(1).getMax()).containsEntry("cpu", Quantity.parse("2"));            // Container 覆写
        assertThat(out.get(1).getMin()).isNullOrEmpty();                                      // Container.min DTO 未给 → 字段级删除
        assertThat(out.get(2).getMin()).containsEntry("cpu", Quantity.parse("100m"));         // ContainerFixed 整体存活
    }

    @Test
    void convert_for_update_drops_modeled_type_not_mentioned_in_dto() {
        // T11 场景：用户关掉 Container、保留 Pod → DTO 只含 Pod → Container 必须删（统一删除语义，非「保留未提及」）
        LimitRange live = live(containerItem("4", "1"),
                new io.fabric8.kubernetes.api.model.LimitRangeItemBuilder().withType("Pod")
                        .withMax(Map.of("cpu", Quantity.parse("8"))).build());
        LimitRangeDTO d = new LimitRangeDTO();
        d.setName("default");
        d.setNamespace("ns1");
        LimitRangeItemDTO pod = new LimitRangeItemDTO();
        pod.setType("Pod");
        ResourcePairDTO m = new ResourcePairDTO();
        m.setCpu(new BigDecimal("8"));
        pod.setMax(m);
        d.setLimits(List.of(pod));   // Container 未提及
        List<LimitRangeItem> out = c.convertForUpdate(d, live).getSpec().getLimits();
        assertThat(out).extracting(LimitRangeItem::getType).containsExactly("Pod");   // Container 删除
    }

    @Test
    void convert_for_update_null_field_removes_key_within_same_type() {
        LimitRange live = live(containerItem("4", "1"));   // Container 有 max.cpu + min.cpu
        LimitRangeDTO d = new LimitRangeDTO();
        d.setName("default");
        LimitRangeItemDTO it = new LimitRangeItemDTO();
        it.setType("Container");
        ResourcePairDTO max = new ResourcePairDTO();
        max.setCpu(new BigDecimal("2"));   // 只给 cpu
        it.setMax(max);                    // min 不给 → 删除
        d.setLimits(List.of(it));

        LimitRangeItem out = c.convertForUpdate(d, live).getSpec().getLimits().get(0);
        assertThat(out.getMax()).containsEntry("cpu", Quantity.parse("2"));
        assertThat(out.getMin()).isNullOrEmpty();   // 与 quota 对称：null = 删除
    }

    @Test
    void convert_for_update_drops_modeled_type_absent_from_dto() {
        LimitRange live = live(containerItem("4", "1"),
                new io.fabric8.kubernetes.api.model.LimitRangeItemBuilder().withType("Pod")
                        .withMax(Map.of("cpu", Quantity.parse("8"))).build());
        LimitRangeDTO d = new LimitRangeDTO();
        d.setName("default");
        d.setLimits(List.of());   // 用户清空全部建模类型
        List<LimitRangeItem> out = c.convertForUpdate(d, live).getSpec().getLimits();
        assertThat(out).extracting(LimitRangeItem::getType).doesNotContain("Container", "Pod");
    }
}
