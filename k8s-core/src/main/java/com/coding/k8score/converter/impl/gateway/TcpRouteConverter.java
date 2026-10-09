package com.coding.k8score.converter.impl.gateway;

import com.coding.common.models.k8s.dto.TcpRouteDTO;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * TcpRouteDTO ⇄ GenericKubernetesResource（gateway.networking.k8s.io，命名空间级）。
 * <p>本类是最简形态：spec 只有 parentRefs + rules{name, backendRefs}，两者都<b>整体替换即无损</b>
 * —— rules 里没有嵌套 atomic 子列表，也没有未建模子字段（不像 HTTPRoute 需要按元素 overlay）。
 * 故 {@link #convertForUpdate} 只需保 spec 级未建模键（如 v1.6 的 {@code useDefaultGateways}）。
 *
 * <h2>CRD 版本是构造参数</h2>
 * L4 路由在 Gateway API v1.6 才 GA 到 {@code v1}，更早的集群只有 {@code v1alpha2}；两者在 CRD 里是
 * **同一结构的不同版本名**（v1alpha2 的 {@code TCPRouteRule} 在 v1.2+ 直接是 v1 类型的别名），
 * 所以<b>只有一个 converter</b>，版本只影响输出的 {@code apiVersion}。版本由 operations 传入
 * （见 {@code TcpRouteOperations} 的注释：本类持有它，是唯一来源）。
 */
public class TcpRouteConverter {

    public static final String GROUP = "gateway.networking.k8s.io";
    public static final String KIND = "TCPRoute";

    /** 建模的 spec 顶层键（overlay 时据此 set/clear）。 */
    private static final List<String> MODELED_SPEC_KEYS = List.of("parentRefs", "rules");

    private final String crdVersion;

    public TcpRouteConverter(String crdVersion) {
        this.crdVersion = crdVersion;
    }

    /** CRD 版本（{@code v1} / {@code v1alpha2}）—— operations 据此建 CRD context，避免两处各写一份。 */
    public String crdVersion() {
        return crdVersion;
    }

    /** 完整的 apiVersion（写入对象的 apiVersion 字段） */
    public String apiVersion() {
        return GROUP + "/" + crdVersion;
    }

    // ==================== 正向：DTO → CRD ====================

    public GenericKubernetesResource convert(TcpRouteDTO dto) {
        GenericKubernetesResource res = new GenericKubernetesResource();
        res.setApiVersion(apiVersion());
        res.setKind(KIND);
        res.setMetadata(new ObjectMetaBuilder()
                .withName(dto.getName())
                .withNamespace(dto.getNamespace())
                .withLabels(dto.getLabels())
                .build());

        Map<String, Object> spec = new LinkedHashMap<>();
        GatewaySpecUtil.putNonEmpty(spec, "parentRefs", GatewaySpecUtil.toParentRefMaps(dto.getParentRefs()));
        GatewaySpecUtil.putNonEmpty(spec, "rules", GatewaySpecUtil.toL4RuleMaps(dto.getRules()));

        res.setAdditionalProperty("spec", spec);
        return res;
    }

    // ==================== 反向：CRD → DTO ====================

    public TcpRouteDTO revert(GenericKubernetesResource res) {
        TcpRouteDTO dto = new TcpRouteDTO();
        if (res == null) {
            return dto;
        }
        if (res.getMetadata() != null) {
            dto.setName(res.getMetadata().getName());
            dto.setNamespace(res.getMetadata().getNamespace());
            dto.setLabels(res.getMetadata().getLabels());
            if (res.getMetadata().getCreationTimestamp() != null) {
                dto.setCreationTime(res.getMetadata().getCreationTimestamp());
            }
        }
        Map<String, Object> spec = GatewaySpecUtil.specOf(res);
        if (spec != null) {
            dto.setParentRefs(GatewaySpecUtil.parentRefs(spec));
            dto.setRules(GatewaySpecUtil.l4Rules(spec));
        }
        dto.setParentStatuses(GatewaySpecUtil.parentStatuses(GatewaySpecUtil.statusOf(res)));
        return dto;
    }

    // ==================== update：spec 级 fetch-overlay ====================

    /** update 专用：以线上 spec 为底，仅覆盖建模键（清空则移除），保住未建模的 spec 键。 */
    public GenericKubernetesResource convertForUpdate(TcpRouteDTO dto, GenericKubernetesResource live) {
        GenericKubernetesResource res = convert(dto);
        Map<String, Object> dtoSpec = GatewaySpecUtil.mapOf(res.getAdditionalProperties().get("spec"));
        if (dtoSpec == null) {
            dtoSpec = new LinkedHashMap<>();
        }
        Map<String, Object> outSpec = new LinkedHashMap<>();
        Map<String, Object> liveSpec = GatewaySpecUtil.specOf(live);
        if (liveSpec != null) {
            outSpec.putAll(liveSpec);
        }
        for (String key : MODELED_SPEC_KEYS) {
            if (dtoSpec.containsKey(key)) {
                outSpec.put(key, dtoSpec.get(key));
            } else {
                outSpec.remove(key);
            }
        }
        res.setAdditionalProperty("spec", outSpec);
        return res;
    }

}
