package com.coding.common.models.k8s.dto;

import com.coding.common.models.k8s.BaseResources;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * StorageClass DTO（集群级资源，无命名空间）。
 * 平台侧只读：供工作负载编辑器下拉选择 storageClassName；存储类本身由集群/基础设施管理。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class StorageClassDTO extends BaseResources {

    /** 供应方驱动（如 standard、kubernetes.io/aws-ebs） */
    private String provisioner;

    /** 回收策略：Retain / Delete */
    private String reclaimPolicy;

    /** 是否允许扩容（仅查询返回） */
    private Boolean allowVolumeExpansion;

    /** 创建时间（仅查询返回，ISO-8601 字符串） */
    private String creationTime;

    @Override
    public String getApiPath() {
        return "/resources/storageclasses";
    }

}
