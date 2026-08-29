package com.coding.common.models.k8s.dto;

import com.coding.common.models.k8s.BaseResources;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Secret DTO（基础表单字段）。
 * data 值为明文，base64 编解码在 k8s-core 转换器内完成。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class SecretDTO extends BaseResources {

    /** Secret 类型（Opaque / tls / kubernetes.io/dockerconfigjson …），缺省 Opaque；创建后不可变 */
    private String type = "Opaque";

    /** key-value 数据（明文） */
    private Map<String, String> data = new LinkedHashMap<>();

    /** 创建时间（仅查询返回，ISO-8601 字符串） */
    private String creationTime;

    @Override
    public String getApiPath() {
        return "/resources/secrets";
    }

}
