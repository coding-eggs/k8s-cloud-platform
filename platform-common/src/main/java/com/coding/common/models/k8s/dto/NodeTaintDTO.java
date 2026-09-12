package com.coding.common.models.k8s.dto;

import lombok.Data;

/** 节点 Taint（spec.taints[]）。 */
@Data
public class NodeTaintDTO {
    private String key;
    private String value;
    /** NoSchedule / PreferNoSchedule / NoExecute */
    private String effect;
}
