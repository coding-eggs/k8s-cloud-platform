package com.coding.k8score.converter.impl.core;

import com.coding.common.models.k8s.dto.NamespaceDTO;
import com.coding.k8score.converter.CommonConverter;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.api.model.Namespace;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * core/v1 Namespace ⇄ NamespaceDTO。
 * <p>
 * 两个不变量：
 * <ul>
 *   <li>managed-by 标签：create 时盖章（平台创建标记）；update 只在线上已带该标签时保留、不强行补盖——
 *       编辑非平台 ns 不谎称"平台创建"（provenance 保持诚实）。provisioning 侧 ensureNamespace 自成一路，不经本类。</li>
 *   <li>description ⇄ annotations["description"]，且 <b>update 走 merge 而非替换</b> ——
 *       WorkloadConverter 的 effectiveAnnotations 是替换式（只写 description 一个键），
 *       对命名空间不安全：集群里常有第三方工具打的 annotation（field.cattle.io/* 等），
 *       替换会静默清掉它们。故 convertForUpdate 以线上 metadata 为底、只增删本类建模的键。</li>
 * </ul>
 */
public class CoreV1NamespaceConverter implements CommonConverter<Namespace, NamespaceDTO> {

    /** 与 k8s-core KubernetesClientFactory.MANAGED_BY_LABEL/_VALUE 同值（此处内联避免反向依赖工厂） */
    public static final String MANAGED_BY_LABEL = "app.kubernetes.io/managed-by";
    public static final String MANAGED_BY_VALUE = "k8s-cloud-platform";
    public static final String DESCRIPTION_ANNOTATION = "description";

    /** Calico 命名空间绑定池（CNI 读 ns annotation 圈定自动分配的 pool，分地址族；值=JSON 数组字符串） */
    public static final String ANNOTATION_IPV4_POOLS = "cni.projectcalico.org/ipv4pools";
    public static final String ANNOTATION_IPV6_POOLS = "cni.projectcalico.org/ipv6pools";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public Namespace convert(NamespaceDTO dto) {
        Map<String, String> labels = new LinkedHashMap<>();
        if (dto.getLabels() != null) {
            labels.putAll(dto.getLabels());
        }
        labels.put(MANAGED_BY_LABEL, MANAGED_BY_VALUE);   // 平台拥有标记

        NamespaceBuilder b = new NamespaceBuilder()
                .withNewMetadata()
                    .withName(dto.getName())
                    .withLabels(labels)
                .endMetadata();
        if (StringUtils.hasText(dto.getDescription())) {
            b.editMetadata().addToAnnotations(DESCRIPTION_ANNOTATION, dto.getDescription()).endMetadata();
        }
        if (dto.getIpv4Pools() != null && !dto.getIpv4Pools().isEmpty()) {
            b.editMetadata().addToAnnotations(ANNOTATION_IPV4_POOLS, poolListToJson(dto.getIpv4Pools())).endMetadata();
        }
        if (dto.getIpv6Pools() != null && !dto.getIpv6Pools().isEmpty()) {
            b.editMetadata().addToAnnotations(ANNOTATION_IPV6_POOLS, poolListToJson(dto.getIpv6Pools())).endMetadata();
        }
        return b.build();
    }

    @Override
    public NamespaceDTO revert(Namespace ns) {
        if (ns == null) {
            return null;
        }
        NamespaceDTO dto = new NamespaceDTO();
        if (ns.getMetadata() != null) {
            dto.setName(ns.getMetadata().getName());
            dto.setLabels(ns.getMetadata().getLabels());
            dto.setCreationTimestamp(ns.getMetadata().getCreationTimestamp());
            dto.setResourceVersion(ns.getMetadata().getResourceVersion());
            Map<String, String> ann = ns.getMetadata().getAnnotations();
            if (ann != null) {
                dto.setDescription(ann.get(DESCRIPTION_ANNOTATION));
                dto.setIpv4Pools(parsePoolList(ann.get(ANNOTATION_IPV4_POOLS)));
                dto.setIpv6Pools(parsePoolList(ann.get(ANNOTATION_IPV6_POOLS)));
            }
        }
        if (ns.getStatus() != null) {
            dto.setPhase(ns.getStatus().getPhase());
        }
        return dto;
    }

    /**
     * update 专用：以线上对象为底合并 metadata（label/annotation 的键级 overlay）。
     * description：present→覆写、absent→删除；managed-by：仅当线上已带则保留（不补盖，provenance 诚实）；
     * 其余 label / annotation 键一律存活。
     */
    public Namespace convertForUpdate(NamespaceDTO dto, Namespace live) {
        Map<String, String> labels = new LinkedHashMap<>();
        Map<String, String> annotations = new LinkedHashMap<>();
        if (live != null && live.getMetadata() != null) {
            if (live.getMetadata().getLabels() != null) {
                labels.putAll(live.getMetadata().getLabels());
            }
            if (live.getMetadata().getAnnotations() != null) {
                annotations.putAll(live.getMetadata().getAnnotations());
            }
        }
        // labels：DTO 全量覆盖建模域；managed-by 归本类独占、只反映"线上是否已纳管"——
        // 平台创建的 ns 编辑后保留标签；非平台 ns 编辑不补盖（provenance 保持诚实）
        boolean liveManaged = labels.containsKey(MANAGED_BY_LABEL);
        labels.remove(MANAGED_BY_LABEL);
        if (dto.getLabels() != null) {
            dto.getLabels().forEach((k, v) -> {
                if (!MANAGED_BY_LABEL.equals(k)) {
                    labels.put(k, v);
                }
            });
        }
        if (liveManaged) {
            labels.put(MANAGED_BY_LABEL, MANAGED_BY_VALUE);
        }
        // annotation：description 有值→覆写；无值→删除（清空描述）
        if (StringUtils.hasText(dto.getDescription())) {
            annotations.put(DESCRIPTION_ANNOTATION, dto.getDescription());
        } else {
            annotations.remove(DESCRIPTION_ANNOTATION);
        }
        // Calico 绑定池：非空→覆写；空→删除（恢复默认分配）
        overlayPools(annotations, ANNOTATION_IPV4_POOLS, dto.getIpv4Pools());
        overlayPools(annotations, ANNOTATION_IPV6_POOLS, dto.getIpv6Pools());
        return new NamespaceBuilder()
                .withNewMetadata()
                    .withName(dto.getName())
                    .withLabels(labels)
                    .withAnnotations(annotations.isEmpty() ? null : annotations)
                .endMetadata()
                .build();
    }

    // ---------- Calico 绑定池注解辅助 ----------

    /** 非空→覆写 JSON 数组；显式空列表→删除该键（恢复 Calico 默认分配）；null=未传→保持现状（防 capability 未探测时误清绑定） */
    private static void overlayPools(Map<String, String> annotations, String key, List<String> pools) {
        if (pools == null) return;
        if (!pools.isEmpty()) {
            annotations.put(key, poolListToJson(pools));
        } else {
            annotations.remove(key);
        }
    }

    private static String poolListToJson(List<String> pools) {
        try {
            return MAPPER.writeValueAsString(pools);
        } catch (Exception e) {
            throw new IllegalStateException("绑定池序列化失败: " + pools, e);
        }
    }

    /** 绑定池注解 → 列表：JSON 数组（官方格式）；解析失败按逗号分隔兼容旧写法；空/非法 → null */
    private static List<String> parsePoolList(String raw) {
        if (!StringUtils.hasText(raw)) return null;
        String s = raw.trim();
        try {
            List<?> arr = MAPPER.readValue(s, List.class);
            List<String> out = new ArrayList<>();
            for (Object o : arr) {
                if (o != null && StringUtils.hasText(String.valueOf(o))) out.add(String.valueOf(o).trim());
            }
            return out.isEmpty() ? null : out;
        } catch (Exception notJson) {
            List<String> out = new ArrayList<>();
            for (String p : s.split(",")) {
                if (StringUtils.hasText(p.trim())) out.add(p.trim());
            }
            return out.isEmpty() ? null : out;
        }
    }
}
