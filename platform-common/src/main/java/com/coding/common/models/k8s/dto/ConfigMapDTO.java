package com.coding.common.models.k8s.dto;

import com.coding.common.models.k8s.BaseResources;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ConfigMap DTO（基础表单字段）
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ConfigMapDTO extends BaseResources {

    /** key-value 数据（string data） */
    private Map<String, String> data = new LinkedHashMap<>();

    /** 二进制数据（值为 base64 字符串，对应 K8s binaryData；key 不得与 data 重叠） */
    private Map<String, String> binaryData = new LinkedHashMap<>();

    /** 不可变：true 后 data/binaryData 不可再修改（仅可改标签等 metadata） */
    private Boolean immutable;

    /** 创建时间（仅查询返回，ISO-8601 字符串） */
    private String creationTime;

    @Override
    public String getApiPath() {
        return "/resources/configmaps";
    }

}
