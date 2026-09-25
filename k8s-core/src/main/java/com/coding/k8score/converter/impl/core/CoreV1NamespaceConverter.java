package com.coding.k8score.converter.impl.core;

import com.coding.common.models.k8s.dto.NamespaceDTO;
import com.coding.k8score.converter.CommonConverter;
import io.fabric8.kubernetes.api.model.Namespace;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * core/v1 Namespace ⇄ NamespaceDTO。
 * <p>
 * 两个不变量：
 * <ul>
 *   <li>managed-by 标签只在此盖章（D3）；provisioning 侧 ensureNamespace 自成一路，不经本类。</li>
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
            }
        }
        if (ns.getStatus() != null) {
            dto.setPhase(ns.getStatus().getPhase());
        }
        return dto;
    }

    /**
     * update 专用：以线上对象为底合并 metadata（label/annotation 的键级 overlay）。
     * 建模键（description、managed-by）present→覆写、absent→删除；其余键一律存活。
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
        // labels：DTO 全量覆盖建模域，但 managed-by 由本类独占
        labels.remove(MANAGED_BY_LABEL);
        if (dto.getLabels() != null) {
            dto.getLabels().forEach((k, v) -> {
                if (!MANAGED_BY_LABEL.equals(k)) {
                    labels.put(k, v);
                }
            });
        }
        labels.put(MANAGED_BY_LABEL, MANAGED_BY_VALUE);
        // annotation：description 有值→覆写；无值→删除（清空描述）
        if (StringUtils.hasText(dto.getDescription())) {
            annotations.put(DESCRIPTION_ANNOTATION, dto.getDescription());
        } else {
            annotations.remove(DESCRIPTION_ANNOTATION);
        }
        return new NamespaceBuilder()
                .withNewMetadata()
                    .withName(dto.getName())
                    .withLabels(labels)
                    .withAnnotations(annotations.isEmpty() ? null : annotations)
                .endMetadata()
                .build();
    }
}
