package com.coding.platformapi.models;

import lombok.Data;

/** 应用事件查询（/application/events）。 */
@Data
public class AppEventsRequest {

    private String appId;

    private String tenantId;

    /** 返回条数上限；空 = 200（k8s-server 再夹到 1~500） */
    private Integer limit;

    /** true = 只要 Warning；由服务端在映射完 serviceKey 之后过滤 */
    private Boolean warningsOnly;
}
