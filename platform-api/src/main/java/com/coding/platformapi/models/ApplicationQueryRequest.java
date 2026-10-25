package com.coding.platformapi.models;

import lombok.Data;

/** 应用相关查询的通用入参（列/详情/操作轮询/版本/选项共用）。 */
@Data
public class ApplicationQueryRequest {

    private String clusterId;

    private String appId;

    private Long operationId;

    private String tenantId;
}
