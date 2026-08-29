package com.coding.k8score.converter.impl.core;

import com.coding.common.models.k8s.dto.PersistentVolumeClaimDTO;
import com.coding.k8score.converter.CommonConverter;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaim;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaimBuilder;
import io.fabric8.kubernetes.api.model.Quantity;
import org.springframework.util.StringUtils;

/**
 * PersistentVolumeClaim ⇄ PVC DTO（基础表单字段）。
 * 规格（存储类/访问模式/容量）创建后不可变，更新仅重建 metadata 标签。
 */
public class CoreV1PvcConverter implements CommonConverter<PersistentVolumeClaim, PersistentVolumeClaimDTO> {

    @Override
    public PersistentVolumeClaim convert(PersistentVolumeClaimDTO in) {
        String storage = StringUtils.hasText(in.getStorage()) ? in.getStorage() : "1Gi";
        return new PersistentVolumeClaimBuilder()
                .withNewMetadata()
                    .withName(in.getName())
                    .withNamespace(in.getNamespace())
                    .withLabels(in.getLabels())
                .endMetadata()
                .withNewSpec()
                    .withStorageClassName(in.getStorageClassName())
                    .withAccessModes(in.getAccessModes())
                    .withNewResources()
                        .addToRequests("storage", new Quantity(storage))
                    .endResources()
                .endSpec()
                .build();
    }

    @Override
    public PersistentVolumeClaimDTO revert(PersistentVolumeClaim pvc) {
        PersistentVolumeClaimDTO dto = new PersistentVolumeClaimDTO();
        if (pvc.getMetadata() != null) {
            dto.setName(pvc.getMetadata().getName());
            dto.setNamespace(pvc.getMetadata().getNamespace());
            dto.setLabels(pvc.getMetadata().getLabels());
            if (pvc.getMetadata().getCreationTimestamp() != null) {
                dto.setCreationTime(pvc.getMetadata().getCreationTimestamp().toString());
            }
        }
        if (pvc.getSpec() != null) {
            dto.setStorageClassName(pvc.getSpec().getStorageClassName());
            dto.setAccessModes(pvc.getSpec().getAccessModes());
            if (pvc.getSpec().getResources() != null && pvc.getSpec().getResources().getRequests() != null) {
                Quantity storage = pvc.getSpec().getResources().getRequests().get("storage");
                if (storage != null) {
                    dto.setStorage(storage.toString());
                }
            }
        }
        if (pvc.getStatus() != null) {
            dto.setPhase(pvc.getStatus().getPhase());
        }
        return dto;
    }

}
