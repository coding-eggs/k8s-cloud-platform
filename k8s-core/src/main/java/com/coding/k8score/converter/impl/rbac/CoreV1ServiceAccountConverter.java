package com.coding.k8score.converter.impl.rbac;

import com.coding.common.models.k8s.dto.ServiceAccountDTO;
import com.coding.k8score.converter.CommonConverter;
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

        return dto;
    }

}