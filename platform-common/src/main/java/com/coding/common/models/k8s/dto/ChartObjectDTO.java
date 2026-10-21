package com.coding.common.models.k8s.dto;

import lombok.Data;

/** 渲染结果里的一个对象（只读展示）。 */
@Data
public class ChartObjectDTO {
    private String apiVersion;
    private String kind;
    private String name;
    private String namespace;
    private String scope;
}
