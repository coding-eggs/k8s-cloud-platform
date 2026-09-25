package com.coding.k8score.converter.impl.core;

import com.coding.common.models.k8s.dto.ResourceQuotaDTO;
import com.coding.common.models.k8s.dto.ResourceQuotaUsedDTO;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.ResourceQuota;
import io.fabric8.kubernetes.api.model.ResourceQuotaBuilder;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CoreV1ResourceQuotaConverterTest {

    private final CoreV1ResourceQuotaConverter c = new CoreV1ResourceQuotaConverter();

    private ResourceQuota withHard(Map<String, Quantity> hard) {
        return new ResourceQuotaBuilder()
                .withNewMetadata().withName("default").withNamespace("ns1")
                    .withResourceVersion("9").withCreationTimestamp("2026-09-01T00:00:00Z").endMetadata()
                .withNewSpec().withHard(hard).endSpec()
                .build();
    }

    @Test
    void revert_converts_hard_to_base_units() {
        Map<String, Quantity> hard = new LinkedHashMap<>();
        hard.put("cpu", Quantity.parse("1500m"));
        hard.put("memory", Quantity.parse("2Gi"));
        hard.put("pods", Quantity.parse("10"));
        ResourceQuotaDTO d = c.revert(withHard(hard));
        assertThat(d.getCpu()).isEqualByComparingTo("1.5");           // 1500m → 核
        assertThat(d.getMemory()).isEqualByComparingTo("2147483648"); // 2Gi → 字节
        assertThat(d.getPods()).isEqualTo(10);
        assertThat(d.getName()).isEqualTo("default");
        assertThat(d.getResourceVersion()).isEqualTo("9");
        assertThat(d.getCreationTime()).isEqualTo("2026-09-01T00:00:00Z");
    }

    @Test
    void revert_reads_status_used_and_drops_unmodeled_keys() {
        Map<String, Quantity> hard = new LinkedHashMap<>();
        hard.put("cpu", Quantity.parse("1"));
        hard.put("count/deployments.apps", Quantity.parse("3"));   // 未建模：revert 丢弃
        ResourceQuota rq = withHard(hard);
        rq.setStatus(new io.fabric8.kubernetes.api.model.ResourceQuotaStatusBuilder()
                .addToUsed("cpu", Quantity.parse("700m")).build());
        ResourceQuotaDTO d = c.revert(rq);
        assertThat(d.getUsed()).isNotNull();
        assertThat(d.getUsed().getCpu()).isEqualByComparingTo("0.7");
        assertThat(d.getCpu()).isEqualByComparingTo("1");
    }

    @Test
    void revert_null_returns_null() {
        assertThat(c.revert(null)).isNull();
    }

    @Test
    void convert_omits_null_fields_from_hard() {
        ResourceQuotaDTO d = new ResourceQuotaDTO();
        d.setName("default");
        d.setNamespace("ns1");
        d.setCpu(new BigDecimal("2"));
        ResourceQuota out = c.convert(d);
        assertThat(out.getSpec().getHard()).containsOnlyKeys("cpu");
        assertThat(out.getSpec().getHard().get("cpu")).isEqualTo(Quantity.parse("2"));
        assertThat(out.getStatus()).isNull();
    }

    @Test
    void convert_maps_dotted_and_lowercase_keys_exactly() {
        ResourceQuotaDTO d = new ResourceQuotaDTO();
        d.setName("default");
        d.setPersistentVolumeClaims(5);      // → hard.persistentvolumeclaims（全小写！）
        d.setLimitsCpu(new BigDecimal("4")); // → hard."limits.cpu"
        d.setRequestsMemory(new BigDecimal("1073741824"));
        Map<String, Quantity> hard = c.convert(d).getSpec().getHard();
        assertThat(hard).containsOnlyKeys("persistentvolumeclaims", "limits.cpu", "requests.memory");
        assertThat(hard.get("persistentvolumeclaims")).isEqualTo(Quantity.parse("5"));
    }

    @Test
    void convert_for_update_keeps_unmodeled_removes_cleared_modeled_overwrites_touched() {
        Map<String, Quantity> liveHard = new LinkedHashMap<>();
        liveHard.put("cpu", Quantity.parse("1"));
        liveHard.put("pods", Quantity.parse("5"));                       // 建模键，DTO 未给 → 应删除
        liveHard.put("count/deployments.apps", Quantity.parse("3"));     // 未建模 → 必须存活
        liveHard.put("services.nodeports", Quantity.parse("0"));         // 未建模 → 必须存活
        ResourceQuota live = withHard(liveHard);

        ResourceQuotaDTO d = new ResourceQuotaDTO();
        d.setName("default");
        d.setNamespace("ns1");
        d.setCpu(new BigDecimal("2"));                                   // 覆写
        d.setMemory(new BigDecimal("3221225472"));                       // 新增 3Gi

        Map<String, Quantity> merged = c.convertForUpdate(d, live).getSpec().getHard();
        assertThat(merged)
                .containsEntry("cpu", Quantity.parse("2"))
                .containsEntry("memory", Quantity.parse("3221225472"))
                .containsEntry("count/deployments.apps", Quantity.parse("3"))
                .containsEntry("services.nodeports", Quantity.parse("0"))
                .doesNotContainKey("pods");
    }

    @Test
    void convert_for_update_with_empty_dto_yields_empty_hard_but_keeps_unmodeled() {
        Map<String, Quantity> liveHard = new LinkedHashMap<>();
        liveHard.put("cpu", Quantity.parse("1"));
        liveHard.put("count/pods", Quantity.parse("9"));
        ResourceQuotaDTO d = new ResourceQuotaDTO();
        d.setName("default");
        Map<String, Quantity> merged = c.convertForUpdate(d, withHard(liveHard)).getSpec().getHard();
        assertThat(merged).doesNotContainKey("cpu").containsEntry("count/pods", Quantity.parse("9"));
    }

    @Test
    void convert_for_update_never_emits_status() {
        ResourceQuota live = withHard(Map.of("cpu", Quantity.parse("1")));
        live.setStatus(new io.fabric8.kubernetes.api.model.ResourceQuotaStatusBuilder()
                .addToUsed("cpu", Quantity.parse("1")).build());
        ResourceQuotaDTO d = new ResourceQuotaDTO();
        d.setName("default");
        assertThat(c.convertForUpdate(d, live).getStatus()).isNull();
    }
}
