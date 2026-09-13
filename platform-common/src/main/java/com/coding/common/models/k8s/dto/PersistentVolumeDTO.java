package com.coding.common.models.k8s.dto;

import com.coding.common.models.k8s.BaseResources;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

/**
 * PersistentVolume DTO（集群级资源，无命名空间）。
 * 平台侧只读：供 PVC 详情查看绑定的 PV；PV 本身由集群/存储供应方管理。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class PersistentVolumeDTO extends BaseResources {

    /** 容量（如 10Gi，来自 spec.capacity.storage） */
    private String capacity;

    /** ReadWriteOnce / ReadWriteMany / ReadOnlyMany / ReadWriteOncePod */
    private List<String> accessModes;

    /** 存储类名称 */
    private String storageClassName;

    /** 回收策略：Retain / Delete / Recycle */
    private String reclaimPolicy;

    /** 状态：Available / Bound / Released / Failed（仅查询返回） */
    private String phase;

    /** 绑定的 PVC 命名空间（来自 spec.claimRef，仅查询返回） */
    private String claimNamespace;

    /** 绑定的 PVC 名称（来自 spec.claimRef，仅查询返回） */
    private String claimName;

    /** 创建时间（仅查询返回，ISO-8601 字符串） */
    private String creationTime;

    @Override
    public String getApiPath() {
        return "/resources/persistentvolumes";
    }

}
