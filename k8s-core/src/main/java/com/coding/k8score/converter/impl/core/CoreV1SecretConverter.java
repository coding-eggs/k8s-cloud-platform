package com.coding.k8score.converter.impl.core;

import com.coding.common.models.k8s.dto.SecretDTO;
import com.coding.k8score.converter.CommonConverter;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Secret ⇄ SecretDTO（基础表单字段：name/namespace/labels/type/data）。
 * DTO 内 data 为明文；创建走 stringData 由 apiserver 编码，查询时 base64 解码回明文。
 */
public class CoreV1SecretConverter implements CommonConverter<Secret, SecretDTO> {

    @Override
    public Secret convert(SecretDTO in) {
        return new SecretBuilder()
                .withNewMetadata()
                    .withName(in.getName())
                    .withNamespace(in.getNamespace())
                    .withLabels(in.getLabels())
                .endMetadata()
                .withType(in.getType())
                //明文交给 apiserver 做 base64，避免平台侧重复编码
                .withStringData(in.getData())
                .withImmutable(in.getImmutable())
                .build();
    }

    @Override
    public SecretDTO revert(Secret secret) {
        if (secret == null) return null;
        SecretDTO dto = new SecretDTO();
        if (secret.getMetadata() != null) {
            dto.setName(secret.getMetadata().getName());
            dto.setNamespace(secret.getMetadata().getNamespace());
            dto.setLabels(secret.getMetadata().getLabels());
            if (secret.getMetadata().getCreationTimestamp() != null) {
                dto.setCreationTime(secret.getMetadata().getCreationTimestamp());
            }
        }
        dto.setType(secret.getType());
        dto.setImmutable(secret.getImmutable());
        Map<String, String> data = new LinkedHashMap<>();
        if (secret.getData() != null) {
            secret.getData().forEach((key, value) ->
                    data.put(key, new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8)));
        }
        dto.setData(data);
        return dto;
    }

}
