package com.coding.k8score.converter.impl.core;

import com.coding.common.models.k8s.dto.LimitRangeDTO;
import com.coding.common.models.k8s.dto.LimitRangeItemDTO;
import com.coding.common.models.k8s.dto.ResourcePairDTO;
import com.coding.k8score.converter.CommonConverter;
import com.coding.k8score.util.QuantityUtil;
import io.fabric8.kubernetes.api.model.LimitRange;
import io.fabric8.kubernetes.api.model.LimitRangeBuilder;
import io.fabric8.kubernetes.api.model.LimitRangeItem;
import io.fabric8.kubernetes.api.model.LimitRangeItemBuilder;
import io.fabric8.kubernetes.api.model.Quantity;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * core/v1 LimitRange ⇄ LimitRangeDTO。
 * <p>
 * spec.limits 是 {@code List<LimitRangeItem>} 且 K8s 侧无 patchMergeKey，属 SSA 下的 atomic list：
 * 省略某项 = 删除该项。故 update 必须 fetch-overlay，且<b>按 type 判别式对齐</b>（Container/Pod/
 * PersistentVolumeClaim），而非 ServiceMonitor 端点那样的按 index 对齐——LimitRange 的语义身份是 type，
 * 与列表位置无关，index 对齐会在用户增删行时错位覆写错误的类型。
 * <p>
 * 字段级（5 个 map）语义与 ResourceQuota.hard 对称：DTO present→覆写、DTO null（或空 map）→删除；
 * 一个建模类型不在 DTO 列表内即被删除。⚠️ fabric8 7.6.1 坑：LimitRangeItem 的 max/min/_default/
 * defaultRequest/maxLimitRequestRatio 字段初始化为<b>空 LinkedHashMap 而非 null</b>，
 * 故 revert 必须把空 map 当 null（见 {@link #pairFrom}），否则每个 item 都带 5 对幽灵空值。
 * <p>
 * 顺序契约（测试钉死）：建模类型按 DTO 顺序在前，未建模类型（如 ContainerFixed）按 live 顺序追加在后，
 * 且未建模类型恒原样存活。
 */
public class CoreV1LimitRangeConverter implements CommonConverter<LimitRange, LimitRangeDTO> {

    /** 建模类型；其余（ContainerFixed 等）由 overlay 原样保留 */
    public static final List<String> MODELED_TYPES = List.of("Container", "Pod", "PersistentVolumeClaim");

    /** 每个 item 内建模的字段（present→覆写、absent→删除） */
    public static final List<String> MODELED_ITEM_FIELDS =
            List.of("max", "min", "default", "defaultRequest", "maxLimitRequestRatio");

    @Override
    public LimitRange convert(LimitRangeDTO dto) {
        List<LimitRangeItem> items = new ArrayList<>();
        if (dto.getLimits() != null) {
            for (LimitRangeItemDTO item : dto.getLimits()) {
                if (!StringUtils.hasText(item.getType())) {
                    continue;   // 跳过 type 空白项
                }
                items.add(toItem(item, null));
            }
        }
        return new LimitRangeBuilder()
                .withNewMetadata().withName(dto.getName()).withNamespace(dto.getNamespace()).endMetadata()
                .withNewSpec().withLimits(items).endSpec()
                .build();
    }

    @Override
    public LimitRangeDTO revert(LimitRange lr) {
        if (lr == null) {
            return null;
        }
        LimitRangeDTO dto = new LimitRangeDTO();
        if (lr.getMetadata() != null) {
            dto.setName(lr.getMetadata().getName());
            dto.setNamespace(lr.getMetadata().getNamespace());
            dto.setResourceVersion(lr.getMetadata().getResourceVersion());
            dto.setCreationTime(lr.getMetadata().getCreationTimestamp());
            dto.setLabels(lr.getMetadata().getLabels());
        }
        if (lr.getSpec() != null && lr.getSpec().getLimits() != null) {
            List<LimitRangeItemDTO> items = new ArrayList<>();
            for (LimitRangeItem item : lr.getSpec().getLimits()) {
                items.add(fromItem(item));
            }
            dto.setLimits(items);
        }
        return dto;
    }

    /**
     * update 专用 fetch-overlay：以线上对象为底，按 type 对齐做字段级覆写/删除（统一删除语义）。
     * <p>DTO 的 limits 即「用户想要的建模类型全集」。结果 = [DTO 顺序的建模类型（每个以 live 同 type item 为底做
     * 5 字段 present→覆写/absent→删除，live 无同 type 则全新构建）] ++ [live 中的未建模类型，按 live 顺序原样追加]。
     * <ul>
     *   <li>DTO 出现但 live 没有的建模类型 → 新增。</li>
     *   <li><b>live 有而 DTO 未提及的建模类型 → 删除</b>（不保留：步骤 2 只发射 DTO 里的项，未提及者不入结果）。
     *       这是 T11 单类型开关的命脉——用户关掉 Container、保留 Pod（DTO 非空只含 Pod）时，Container 必须被删；
     *       若按「保留未提及」则删除动作被静默吞掉。</li>
     *   <li>未建模类型（如 ContainerFixed）恒存活。空 DTO（{@code null}/size 0）自然推出「全建模类型删除、仅未建模存活」。</li>
     * </ul>
     * 绝不产出 status（LimitRange 无 status 子资源）。
     */
    public LimitRange convertForUpdate(LimitRangeDTO dto, LimitRange live) {
        List<LimitRangeItem> liveItems = new ArrayList<>();
        if (live != null && live.getSpec() != null && live.getSpec().getLimits() != null) {
            liveItems = live.getSpec().getLimits();
        }
        Map<String, LimitRangeItem> liveByType = new LinkedHashMap<>();
        for (LimitRangeItem item : liveItems) {
            if (StringUtils.hasText(item.getType())) {
                liveByType.putIfAbsent(item.getType(), item);
            }
        }

        List<LimitRangeItem> merged = new ArrayList<>();
        List<LimitRangeItemDTO> dtoLimits = dto.getLimits();
        if (dtoLimits != null) {
            for (LimitRangeItemDTO item : dtoLimits) {
                if (!StringUtils.hasText(item.getType())) {
                    continue;   // 跳过 type 空白项
                }
                // 命中 live 同 type → 以其为底做字段级覆写/删除；否则全新构建（新增建模类型）
                merged.add(toItem(item, liveByType.get(item.getType())));
            }
        }
        // 追加 live 的未建模类型（按 live 顺序）；live 有而 DTO 未发射的建模类型由此被「删除」（不追加）
        for (LimitRangeItem item : liveItems) {
            if (!MODELED_TYPES.contains(item.getType())) {
                merged.add(item);
            }
        }

        return new LimitRangeBuilder()
                .withNewMetadata().withName(dto.getName()).withNamespace(dto.getNamespace()).endMetadata()
                .withNewSpec().withLimits(merged).endSpec()
                .build();
    }

    // ---------- item ⇄ DTO ----------

    /**
     * DTO item → fabric8 item。base 非空时以线上 item 为底（保留 additionalProperties 等），
     * 5 个建模字段一律按 DTO 覆写：present→withXxx(map)、null→withXxx(null)（删除）。
     */
    private LimitRangeItem toItem(LimitRangeItemDTO d, LimitRangeItem base) {
        LimitRangeItemBuilder b = base != null ? base.toBuilder() : new LimitRangeItemBuilder();
        b.withType(d.getType());
        b.withMax(pairToMap(d.getMax()));
        b.withMin(pairToMap(d.getMin()));
        b.withDefault(pairToMap(d.getDefaultValue()));
        b.withDefaultRequest(pairToMap(d.getDefaultRequest()));
        b.withMaxLimitRequestRatio(pairToMap(d.getMaxLimitRequestRatio()));
        return b.build();
    }

    private LimitRangeItemDTO fromItem(LimitRangeItem item) {
        LimitRangeItemDTO dto = new LimitRangeItemDTO();
        dto.setType(item.getType());
        dto.setMax(pairFrom(item.getMax()));
        dto.setMin(pairFrom(item.getMin()));
        dto.setDefaultValue(pairFrom(item.getDefault()));
        dto.setDefaultRequest(pairFrom(item.getDefaultRequest()));
        dto.setMaxLimitRequestRatio(pairFrom(item.getMaxLimitRequestRatio()));
        return dto;
    }

    /** ResourcePairDTO → map（仅非 null 的 cpu/memory，基础单位）；pair 为 null → null（overlay 语义 = 删除该字段）。 */
    private Map<String, Quantity> pairToMap(ResourcePairDTO pair) {
        if (pair == null) {
            return null;
        }
        Map<String, Quantity> m = new LinkedHashMap<>();
        BigDecimal cpu = pair.getCpu();
        if (cpu != null) {
            m.put("cpu", QuantityUtil.fromBase(cpu));
        }
        BigDecimal memory = pair.getMemory();
        if (memory != null) {
            m.put("memory", QuantityUtil.fromBase(memory));
        }
        return m;
    }

    /**
     * map → ResourcePairDTO。<b>空 map 视为 null</b>（fabric8 7.6.1 坑：字段默认是空 LinkedHashMap 而非 null），
     * 否则每个 item 反序列化都带 5 对幽灵空值。
     */
    private ResourcePairDTO pairFrom(Map<String, Quantity> m) {
        if (m == null || m.isEmpty()) {
            return null;
        }
        ResourcePairDTO pair = new ResourcePairDTO();
        pair.setCpu(QuantityUtil.toBase(m.get("cpu")));
        pair.setMemory(QuantityUtil.toBase(m.get("memory")));
        return pair;
    }
}
