package com.coding.k8score.converter.impl.core;

import com.coding.common.models.k8s.dto.ConfigMapDTO;
import com.coding.k8score.converter.CommonConverter;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;

/**
 * ConfigMap ⇄ ConfigMapDTO（基础表单字段：name/namespace/labels/data）
 */
public class CoreV1ConfigMapConverter implements CommonConverter<ConfigMap, ConfigMapDTO> {

    @Override
    public ConfigMap convert(ConfigMapDTO in) {
        return new ConfigMapBuilder()
                .withNewMetadata()
                    .withName(in.getName())
                    .withNamespace(in.getNamespace())
                    .withLabels(in.getLabels())
                .endMetadata()
                .withData(in.getData())
                .withBinaryData(in.getBinaryData())
                .withImmutable(in.getImmutable())
                .build();
    }

    @Override
    public ConfigMapDTO revert(ConfigMap cm) {
        if (cm == null) return null;
        ConfigMapDTO dto = new ConfigMapDTO();
        if (cm.getMetadata() != null) {
            dto.setName(cm.getMetadata().getName());
            dto.setNamespace(cm.getMetadata().getNamespace());
            dto.setLabels(cm.getMetadata().getLabels());
            if (cm.getMetadata().getCreationTimestamp() != null) {
                dto.setCreationTime(cm.getMetadata().getCreationTimestamp());
            }
        }
        dto.setData(cm.getData());
        dto.setBinaryData(cm.getBinaryData());
        dto.setImmutable(cm.getImmutable());
        return dto;
    }

}
