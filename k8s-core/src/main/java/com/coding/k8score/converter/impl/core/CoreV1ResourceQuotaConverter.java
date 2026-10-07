package com.coding.k8score.converter.impl.core;

import com.coding.common.models.k8s.dto.ResourceQuotaDTO;
import com.coding.common.models.k8s.dto.ResourceQuotaUsedDTO;
import com.coding.k8score.converter.CommonConverter;
import com.coding.k8score.util.QuantityUtil;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.ResourceQuota;
import io.fabric8.kubernetes.api.model.ResourceQuotaBuilder;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * core/v1 ResourceQuota ⇄ ResourceQuotaDTO。
 * <p>
 * hard 是普通 Map&lt;String,Quantity&gt;，故 overlay 比 ServiceMonitor 的 atomic list 简单（无需索引对齐）：
 * 以线上 hard 为底 → 6 个建模键 present→覆写 / absent→删除 → 未建模键（cpu/memory/services、count/*、limits.ephemeral-storage 等）存活。
 * 这一步是<b>必需</b>的：SSA 下 platform-system 拥有整个 hard map，省略一个已拥有的键 = 删除该键。
 * <p>
 * K8s 资源名一律全小写（persistentvolumeclaims 不是 persistentVolumeClaims）；带点号的是合法 key，无需转义。
 */
public class CoreV1ResourceQuotaConverter implements CommonConverter<ResourceQuota, ResourceQuotaDTO> {

    /** 建模的 hard 键（权威清单；测试逐条断言其语义）。值 = DTO 字段名映射目标 */
    public static final List<String> MODELED_HARD_KEYS = List.of(
            "pods",
            "limits.cpu", "limits.memory",
            "requests.cpu", "requests.memory",
            "persistentvolumeclaims");

    @Override
    public ResourceQuota convert(ResourceQuotaDTO dto) {
        ResourceQuotaBuilder b = new ResourceQuotaBuilder()
                .withNewMetadata()
                    .withName(dto.getName())
                    .withNamespace(dto.getNamespace())
                .endMetadata()
                .withNewSpec().endSpec();
        Map<String, Quantity> hard = toHard(dto);
        if (!hard.isEmpty()) {
            b.editSpec().withHard(hard).endSpec();
        }
        return b.build();
    }

    @Override
    public ResourceQuotaDTO revert(ResourceQuota rq) {
        if (rq == null) {
            return null;
        }
        ResourceQuotaDTO dto = new ResourceQuotaDTO();
        if (rq.getMetadata() != null) {
            dto.setName(rq.getMetadata().getName());
            dto.setNamespace(rq.getMetadata().getNamespace());
            dto.setResourceVersion(rq.getMetadata().getResourceVersion());
            dto.setCreationTime(rq.getMetadata().getCreationTimestamp());
            dto.setLabels(rq.getMetadata().getLabels());
        }
        if (rq.getSpec() != null) {
            fromHard(rq.getSpec().getHard(), dto);
        }
        if (rq.getStatus() != null && rq.getStatus().getUsed() != null) {
            dto.setUsed(usedFrom(rq.getStatus().getUsed()));
        }
        return dto;
    }

    /**
     * update 专用 fetch-overlay：以线上 hard 为底做键级增删改，绝不产出 status（那是 controller 的地盘）。
     */
    public ResourceQuota convertForUpdate(ResourceQuotaDTO dto, ResourceQuota live) {
        Map<String, Quantity> merged = new LinkedHashMap<>();
        if (live != null && live.getSpec() != null && live.getSpec().getHard() != null) {
            merged.putAll(live.getSpec().getHard());
        }
        MODELED_HARD_KEYS.forEach(merged::remove);   // 先清掉全部建模键，再按 DTO 回填 = present 覆写 / absent 删除
        merged.putAll(toHard(dto));
        ResourceQuotaBuilder b = new ResourceQuotaBuilder()
                .withNewMetadata().withName(dto.getName()).withNamespace(dto.getNamespace()).endMetadata()
                .withNewSpec().endSpec();
        if (!merged.isEmpty()) {
            b.editSpec().withHard(merged).endSpec();
        }
        return b.build();
    }

    /** DTO → hard（只写非 null 项；顺序与 MODELED_HARD_KEYS 一致便于比对） */
    private Map<String, Quantity> toHard(ResourceQuotaDTO dto) {
        Map<String, Quantity> hard = new LinkedHashMap<>();
        putCount(hard, "pods", dto.getPods());
        putDecimal(hard, "limits.cpu", dto.getLimitsCpu());
        putDecimal(hard, "limits.memory", dto.getLimitsMemory());
        putDecimal(hard, "requests.cpu", dto.getRequestsCpu());
        putDecimal(hard, "requests.memory", dto.getRequestsMemory());
        putCount(hard, "persistentvolumeclaims", dto.getPersistentVolumeClaims());
        return hard;
    }

    /** hard → DTO（未建模键忽略） */
    private void fromHard(Map<String, Quantity> hard, ResourceQuotaDTO dto) {
        Map<String, BigDecimal> base = QuantityUtil.toBaseMap(hard);
        if (base == null) {
            return;
        }
        dto.setLimitsCpu(base.get("limits.cpu"));
        dto.setLimitsMemory(base.get("limits.memory"));
        dto.setRequestsCpu(base.get("requests.cpu"));
        dto.setRequestsMemory(base.get("requests.memory"));
        dto.setPods(count(base, "pods"));
        dto.setPersistentVolumeClaims(count(base, "persistentvolumeclaims"));
    }

    private ResourceQuotaUsedDTO usedFrom(Map<String, Quantity> used) {
        Map<String, BigDecimal> base = QuantityUtil.toBaseMap(used);
        if (base == null) {
            return null;
        }
        ResourceQuotaUsedDTO u = new ResourceQuotaUsedDTO();
        u.setLimitsCpu(base.get("limits.cpu"));
        u.setLimitsMemory(base.get("limits.memory"));
        u.setRequestsCpu(base.get("requests.cpu"));
        u.setRequestsMemory(base.get("requests.memory"));
        u.setPods(count(base, "pods"));
        u.setPersistentVolumeClaims(count(base, "persistentvolumeclaims"));
        return u;
    }

    /** 计数项：基础单位 BigDecimal → Integer（null 安全） */
    private Integer count(Map<String, BigDecimal> base, String key) {
        BigDecimal v = base.get(key);
        return v == null ? null : v.intValue();
    }

    private void putDecimal(Map<String, Quantity> hard, String key, BigDecimal v) {
        if (v != null) {
            hard.put(key, QuantityUtil.fromBase(v));
        }
    }

    private void putCount(Map<String, Quantity> hard, String key, Integer v) {
        if (v != null) {
            hard.put(key, new Quantity(String.valueOf(v)));
        }
    }
}
