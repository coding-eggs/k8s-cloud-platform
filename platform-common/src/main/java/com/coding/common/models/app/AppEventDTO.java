package com.coding.common.models.app;

import lombok.Data;

/**
 * 一条 Kubernetes 事件（core/v1 Event 的精简视图）。
 *
 * <p>排序与展示时间都用 {@code lastTimestamp}（可能为空时退到 {@code metadata.creationTimestamp}），
 * {@code count} 是同一事件被聚合的次数。
 */
@Data
public class AppEventDTO {

    /** Normal / Warning */
    private String type;

    private String reason;

    private String message;

    /** 事件挂在谁身上：Pod / ReplicaSet / Deployment / StatefulSet / Service ... */
    private String objectKind;

    private String objectName;

    private Integer count;

    private String lastTimestamp;

    private String firstTimestamp;

    /**
     * 这条事件属于哪个服务（应用里的节点 key）。由 platform-api 按「对象名前缀」回填，
     * 不是应用自己的对象（或对不上任何服务）时为 null。k8s-server 不填。
     */
    private String serviceKey;
}
