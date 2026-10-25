package com.coding.common.models.app;

import lombok.Data;

/** 渲染出的一个对象身份（kind/namespace/name 足够做去重、prune 差集与状态查询）。 */
@Data
public class AppObjectRefDTO {

    private String apiVersion;

    private String kind;

    private String namespace;

    private String name;
}
