package com.coding.common.models.k8s.dto;

import com.coding.common.models.k8s.BaseResources;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.util.List;

/**
 * PersistentVolumeClaim DTO（基础表单字段）。
 * 规格里只有**容量**能改，且只能往大改（K8s 的规则），还要存储类开了 allowVolumeExpansion ——
 * 两条校验在 platform-api 的 PvcService.update（S4.1）。存储类 / 访问模式仍然创建后不可变。
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

    /**
     * {@code status.conditions} 里 **status=True 的那些 type**（仅查询返回，如 {@code Resizing}、
     * {@code FileSystemResizePending}）。
     *
     * <p>⚠️ 扩容的中间态**不体现在 {@code phase} 上** —— 扩容期间 {@code phase} 一直是
     * {@code Bound}，只有条件里才看得见。不透出来的话，UI 上「正在扩容」看起来和「已经好了」
     * 一模一样，运维会把正常过程当成故障处置（S4.1.3）。
     */
    private List<String> conditions;

    /** 创建时间（仅查询返回，ISO-8601 字符串） */
    private String creationTime;

    @Override
    public String getApiPath() {
        return "/pvcs";
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
