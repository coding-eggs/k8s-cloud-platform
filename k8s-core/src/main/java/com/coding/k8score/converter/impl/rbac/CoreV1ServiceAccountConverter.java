package com.coding.k8score.converter.impl.rbac;

import com.coding.common.models.k8s.dto.ImagePullSecretRefDTO;
import com.coding.common.models.k8s.dto.ServiceAccountDTO;
import com.coding.k8score.converter.CommonConverter;
import io.fabric8.kubernetes.api.model.LocalObjectReference;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.ServiceAccount;
import io.fabric8.kubernetes.client.extension.ExtensionAdapter;

import java.util.Map;

/**
 * v1 版本的 ServiceAccount DTO 转换器
 */
public class CoreV1ServiceAccountConverter implements CommonConverter<ServiceAccount, ServiceAccountDTO> {

    @Override
    public io.fabric8.kubernetes.api.model.ServiceAccount convert(ServiceAccountDTO dto) {
        ServiceAccount sa = new ServiceAccount();

        String ns = dto.getNamespace() != null ? dto.getNamespace() : "";
        sa.setMetadata(new ObjectMetaBuilder()
                .withName(dto.getName())
                .withNamespace(ns)
                .withLabels(dto.getLabels())
                .build());

        // imagePullSecrets：只有非空才写。fabric8 这个字段默认是空 ArrayList 且标了 NON_EMPTY，
        // 序列化时空列表会被整个省掉 —— 效果等同"不碰这个字段"（SSA 下不会去删别人挂的条目）。
        // 本平台只用它往 default SA 上**加**，不做清空。
        if (dto.getImagePullSecrets() != null) {
            sa.setImagePullSecrets(dto.getImagePullSecrets().stream()
                    .map(r -> {
                        LocalObjectReference ref = new LocalObjectReference();
                        ref.setName(r.getName());
                        return ref;
                    })
                    .toList());
        }

        return sa;
    }

    @Override
    public ServiceAccountDTO revert(ServiceAccount sa) {
        if (sa == null) return null;

        ServiceAccountDTO dto = new ServiceAccountDTO();

        var metadata = sa.getMetadata();
        if (metadata != null) {
            dto.setName(metadata.getName());
            dto.setNamespace(metadata.getNamespace());
            dto.setLabels(metadata.getLabels() != null ? Map.copyOf(metadata.getLabels()) : Map.of());
        }

        if (sa.getImagePullSecrets() != null) {
            dto.setImagePullSecrets(sa.getImagePullSecrets().stream()
                    .map(src -> {
                        ImagePullSecretRefDTO ref = new ImagePullSecretRefDTO();
                        ref.setName(src.getName());
                        return ref;
                    })
                    .toList());
        }

        return dto;
    }

}