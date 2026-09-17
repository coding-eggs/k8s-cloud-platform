package com.coding.k8score.converter.impl.core;

import com.coding.common.models.k8s.dto.PersistentVolumeDTO;
import com.coding.k8score.converter.CommonConverter;
import com.coding.k8score.util.QuantityUtil;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.PersistentVolume;
import io.fabric8.kubernetes.api.model.Quantity;

import java.math.BigDecimal;
import java.util.Map;

/**
 * core/v1 版本的 PersistentVolume DTO 转换器（集群级资源）。平台侧只读，供 PVC 详情查看绑定的 PV。
 */
public class CoreV1PersistentVolumeConverter implements CommonConverter<PersistentVolume, PersistentVolumeDTO> {

    @Override
    public PersistentVolume convert(PersistentVolumeDTO dto) {
        // 平台侧不暴露 PV 写操作；此方法仅为满足接口，按 DTO 字段最小重建。
        var spec = new io.fabric8.kubernetes.api.model.PersistentVolumeSpec();
        spec.setAccessModes(dto.getAccessModes());
        spec.setStorageClassName(dto.getStorageClassName());
        spec.setPersistentVolumeReclaimPolicy(dto.getReclaimPolicy());
        if (dto.getCapacity() != null) {
            Map<String, Quantity> capacity = new java.util.LinkedHashMap<>();
            capacity.put("storage", QuantityUtil.fromBase(dto.getCapacity()));
            spec.setCapacity(capacity);
        }
        PersistentVolume pv = new PersistentVolume();
        pv.setMetadata(new ObjectMetaBuilder().withName(dto.getName()).withLabels(dto.getLabels()).build());
        pv.setSpec(spec);
        return pv;
    }

    @Override
    public PersistentVolumeDTO revert(PersistentVolume pv) {
        if (pv == null) {
            return null;
        }
        PersistentVolumeDTO dto = new PersistentVolumeDTO();
        var metadata = pv.getMetadata();
        if (metadata != null) {
            dto.setName(metadata.getName());
            dto.setLabels(metadata.getLabels() != null ? Map.copyOf(metadata.getLabels()) : Map.of());
            if (metadata.getCreationTimestamp() != null) {
                dto.setCreationTime(metadata.getCreationTimestamp());
            }
        }
        var spec = pv.getSpec();
        if (spec != null) {
            if (spec.getCapacity() != null && spec.getCapacity().get("storage") != null) {
                dto.setCapacity(QuantityUtil.toBase(spec.getCapacity().get("storage")));
            }
            dto.setAccessModes(spec.getAccessModes());
            dto.setStorageClassName(spec.getStorageClassName());
            dto.setReclaimPolicy(spec.getPersistentVolumeReclaimPolicy());
            if (spec.getClaimRef() != null) {
                dto.setClaimNamespace(spec.getClaimRef().getNamespace());
                dto.setClaimName(spec.getClaimRef().getName());
            }
        }
        if (pv.getStatus() != null) {
            dto.setPhase(pv.getStatus().getPhase());
        }
        return dto;
    }

}
