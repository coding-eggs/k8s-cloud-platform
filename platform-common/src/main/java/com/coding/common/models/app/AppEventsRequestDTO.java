package com.coding.common.models.app;

import lombok.Data;

/**
 * platform-api → k8s-server：读一个应用在本命名空间里的 Kubernetes 事件。
 *
 * <p>归属口径是平台标签 {@code platform.coding/app=<appId>}：工作负载、Pod、Service 都带它，
 * 再按「工作负载名 + '-'」的前缀把 Deployment 建出来的 ReplicaSet 一并收进来。
 */
@Data
public class AppEventsRequestDTO {

    private String clusterId;

    private String namespace;

    /** 平台应用 id（{@code platform.coding/app} 标签的值） */
    private String appId;

    /** 返回条数上限；空 = 200（服务端再夹到 1~500） */
    private Integer limit;
}
