package com.coding.k8score.converter.impl.core;

import com.coding.common.models.k8s.dto.PersistentVolumeClaimDTO;
import com.coding.k8score.converter.CommonConverter;
import com.coding.k8score.util.QuantityUtil;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaim;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaimCondition;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaimBuilder;
import io.fabric8.kubernetes.api.model.Quantity;

import java.math.BigDecimal;

/**
 * PersistentVolumeClaim ⇄ PVC DTO（基础表单字段）。
 * 规格（存储类/访问模式/容量）创建后不可变，更新仅重建 metadata 标签。
 */
public class CoreV1PvcConverter implements CommonConverter<PersistentVolumeClaim, PersistentVolumeClaimDTO> {

    @Override
    public PersistentVolumeClaim convert(PersistentVolumeClaimDTO in) {
        BigDecimal storageBytes = in.getStorage() != null ? in.getStorage() : new BigDecimal("1073741824"); // 缺省 1Gi
        var specBuilder = new io.fabric8.kubernetes.api.model.PersistentVolumeClaimSpecBuilder()
                .withStorageClassName(in.getStorageClassName())
                .withAccessModes(in.getAccessModes())
                .withNewResources()
                    .addToRequests("storage", QuantityUtil.fromBase(storageBytes))
                .endResources();
        if (in.getDataSourceRef() != null) {
            specBuilder.withDataSourceRef(new io.fabric8.kubernetes.api.model.TypedObjectReferenceBuilder()
                    .withApiGroup(in.getDataSourceRef().getApiGroup())
                    .withKind(in.getDataSourceRef().getKind())
                    .withName(in.getDataSourceRef().getName())
                    .withNamespace(in.getDataSourceRef().getNamespace())
                    .build());
        }
        return new PersistentVolumeClaimBuilder()
                .withNewMetadata()
                    .withName(in.getName())
                    .withNamespace(in.getNamespace())
                    .withLabels(in.getLabels())
                .endMetadata()
                .withSpec(specBuilder.build())
                .build();
    }

    @Override
    public PersistentVolumeClaimDTO revert(PersistentVolumeClaim pvc) {
        if (pvc == null) return null;
        PersistentVolumeClaimDTO dto = new PersistentVolumeClaimDTO();
        if (pvc.getMetadata() != null) {
            dto.setName(pvc.getMetadata().getName());
            dto.setNamespace(pvc.getMetadata().getNamespace());
            dto.setLabels(pvc.getMetadata().getLabels());
            // ⚠️ 注解必须带出来：工作区回收器只认 PVC 上的 `platform.pipeline/retain-until`，
            //    不映射的话它拿到的永远是 null → 一块过期卷都删不掉（2026-10-20 复审发现）。
            if (pvc.getMetadata().getAnnotations() != null) {
                dto.setAnnotations(new java.util.HashMap<>(pvc.getMetadata().getAnnotations()));
            }
            if (pvc.getMetadata().getCreationTimestamp() != null) {
                dto.setCreationTime(pvc.getMetadata().getCreationTimestamp());
            }
        }
        if (pvc.getSpec() != null) {
            dto.setStorageClassName(pvc.getSpec().getStorageClassName());
            dto.setAccessModes(pvc.getSpec().getAccessModes());
            if (pvc.getSpec().getResources() != null && pvc.getSpec().getResources().getRequests() != null) {
                Quantity storage = pvc.getSpec().getResources().getRequests().get("storage");
                if (storage != null) {
                    dto.setStorage(QuantityUtil.toBase(storage));
                }
            }
            io.fabric8.kubernetes.api.model.TypedObjectReference ref = pvc.getSpec().getDataSourceRef();
            if (ref != null) {
                PersistentVolumeClaimDTO.DataSourceRef dsr = new PersistentVolumeClaimDTO.DataSourceRef();
                dsr.setApiGroup(ref.getApiGroup());
                dsr.setKind(ref.getKind());
                dsr.setName(ref.getName());
                dsr.setNamespace(ref.getNamespace());
                dto.setDataSourceRef(dsr);
            }
        }
        if (pvc.getStatus() != null) {
            dto.setPhase(pvc.getStatus().getPhase());
            // ⚠️ 扩容的中间态只在 conditions 里，不在 phase 上 —— 扩容期间 phase 一直是 Bound。
            //    只放 status=True 的那些 type（False/Unknown 的放出去会让 UI 说不清"到底在不在扩"）
            if (pvc.getStatus().getConditions() != null) {
                dto.setConditions(pvc.getStatus().getConditions().stream()
                        .filter(c -> "True".equalsIgnoreCase(c.getStatus()))
                        .map(PersistentVolumeClaimCondition::getType)
                        .toList());
            }
        }
        return dto;
    }

}
