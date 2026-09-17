package com.coding.common.models.k8s.dto;

import com.coding.common.models.k8s.BaseResources;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
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

    /** 请求容量（基础单位：字节） */
    private BigDecimal storage;

    /** 数据来源（可选）：从已有对象填充新卷，如 VolumeSnapshot / PersistentVolumeClaim。对应 spec.dataSourceRef */
    private DataSourceRef dataSourceRef;

    /** 状态：Pending / Bound / Lost（仅查询返回） */
    private String phase;

    /** 创建时间（仅查询返回，ISO-8601 字符串） */
    private String creationTime;

    @Override
    public String getApiPath() {
        return "/resources/persistentvolumeclaims";
    }

    /** spec.dataSourceRef：TypedObjectReference（apiGroup/kind/name 定位来源对象，namespace 可选）。 */
    @Data
    public static class DataSourceRef {
        private String apiGroup;
        private String kind;
        private String name;
        private String namespace;
    }

}
