package com.coding.k8score.converter.impl.core;

import com.coding.common.models.k8s.dto.StorageClassDTO;
import com.coding.k8score.converter.CommonConverter;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.storage.StorageClass;

import java.util.Map;

/**
 * storage/v1 版本的 StorageClass DTO 转换器（集群级资源）。
 */
public class CoreV1StorageClassConverter implements CommonConverter<StorageClass, StorageClassDTO> {

    @Override
    public StorageClass convert(StorageClassDTO dto) {
        StorageClass sc = new StorageClass();
        sc.setMetadata(new ObjectMetaBuilder()
                .withName(dto.getName())
                .withLabels(dto.getLabels())
                .build());
        sc.setProvisioner(dto.getProvisioner());
        sc.setReclaimPolicy(dto.getReclaimPolicy());
        sc.setAllowVolumeExpansion(dto.getAllowVolumeExpansion());
        return sc;
    }

    @Override
    public StorageClassDTO revert(StorageClass sc) {
        if (sc == null) {
            return null;
        }
        StorageClassDTO dto = new StorageClassDTO();
        var metadata = sc.getMetadata();
        if (metadata != null) {
            dto.setName(metadata.getName());
            dto.setLabels(metadata.getLabels() != null ? Map.copyOf(metadata.getLabels()) : Map.of());
            if (metadata.getCreationTimestamp() != null) {
                dto.setCreationTime(metadata.getCreationTimestamp());
            }
        }
        dto.setProvisioner(sc.getProvisioner());
        dto.setReclaimPolicy(sc.getReclaimPolicy());
        dto.setAllowVolumeExpansion(sc.getAllowVolumeExpansion());
        return dto;
    }

}
