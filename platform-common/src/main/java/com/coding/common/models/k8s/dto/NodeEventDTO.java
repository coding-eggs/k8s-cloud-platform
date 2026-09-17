package com.coding.common.models.k8s.dto;

import lombok.Data;

/** 节点相关事件：节点自身（involvedObject=Node）+ 该节点 kubelet 上报的事件（reportingInstance/source.host 匹配节点身份）。 */
@Data
public class NodeEventDTO {
    private String reason;
    private String message;
    /** Normal / Warning */
    private String type;
    private Integer count;
    private String firstTimestamp;
    private String lastTimestamp;
    /** 涉及对象类型（involvedObject.kind）：Node / Pod / … */
    private String kind;
    /** 涉及对象名（involvedObject.name） */
    private String objectName;
    /** 涉及对象命名空间（Pod 等 namespaced 对象才有） */
    private String namespace;
}
