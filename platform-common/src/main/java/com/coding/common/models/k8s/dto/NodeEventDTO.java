package com.coding.common.models.k8s.dto;

import lombok.Data;

/** 节点事件（involvedObject.kind=Node 的 v1 Event）。 */
@Data
public class NodeEventDTO {
    private String reason;
    private String message;
    /** Normal / Warning */
    private String type;
    private Integer count;
    private String firstTimestamp;
    private String lastTimestamp;
}
