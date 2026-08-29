package com.coding.common.models.k8s.dto;

import com.coding.common.models.k8s.BaseResources;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

/**
 * PersistentVolumeClaim DTO（基础表单字段）。
 * 规格（存储类 / 访问模式 / 容量）创建后不可变，更新仅支持标签。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class PersistentVolumeClaimDTO extends BaseResources {

    private String storageClassName;

    /** ReadWriteOnce / ReadWriteMany / ReadOnlyMany / ReadWriteMany */
    private List<String> accessModes;

    /** 请求容量（如 1Gi、100Mi） */
    private String storage;

    /** 状态：Pending / Bound / Lost（仅查询返回） */
    private String phase;

    /** 创建时间（仅查询返回，ISO-8601 字符串） */
    private String creationTime;

    @Override
    public String getApiPath() {
        return "/resources/persistentvolumeclaims";
    }

}
