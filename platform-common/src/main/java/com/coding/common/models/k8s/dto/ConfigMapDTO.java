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

    /** key-value 数据（基础表单只暴露 string data；binaryData 暂不开放） */
    private Map<String, String> data = new LinkedHashMap<>();

    /** 创建时间（仅查询返回，ISO-8601 字符串） */
    private String creationTime;

    @Override
    public String getApiPath() {
        return "/resources/configmaps";
    }

}
