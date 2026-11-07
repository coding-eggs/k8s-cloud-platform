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

    /** 集群默认存储类注解：PVC 未指定 {@code storageClassName} 时用它。一个集群至多一个（平台设置时清掉其它）。 */
    public static final String DEFAULT_CLASS_ANNOTATION = "storageclass.kubernetes.io/is-default-class";

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
            Map<String, String> annotations = metadata.getAnnotations();
            dto.setIsDefault(annotations != null && "true".equalsIgnoreCase(annotations.get(DEFAULT_CLASS_ANNOTATION)));
            if (metadata.getCreationTimestamp() != null) {
                dto.setCreationTime(metadata.getCreationTimestamp());
            }
        }
        dto.setProvisioner(sc.getProvisioner());
        dto.setReclaimPolicy(sc.getReclaimPolicy());
        dto.setParameters(sc.getParameters() != null ? Map.copyOf(sc.getParameters()) : Map.of());
        dto.setAllowVolumeExpansion(sc.getAllowVolumeExpansion());
        return dto;
    }

}
