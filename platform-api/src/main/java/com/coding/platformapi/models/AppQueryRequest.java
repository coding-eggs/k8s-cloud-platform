package com.coding.platformapi.models;

import lombok.Data;

/** 应用列表/详情的查询体。 */
@Data
public class AppQueryRequest {
    private String clusterId;
    /** 空 = 该集群全部 */
    private String namespace;
    private String appId;
}
