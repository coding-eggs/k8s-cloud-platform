package com.coding.platformapi.models;

import lombok.Data;

/** 应用相关查询的通用入参（列表 / 详情 / 选项 / 概览 / 发布查询共用）。 */
@Data
public class ApplicationQueryRequest {

    private String clusterId;

    private String appId;

    private String tenantId;
}
