package com.coding.common.models.k8s.dto;

import lombok.Data;

/** 节点 Condition（status.conditions[]）。 */
@Data
public class NodeConditionDTO {
    /** Ready / MemoryPressure / DiskPressure / PIDPressure / NetworkUnavailable ... */
    private String type;
    /** True / False / Unknown */
    private String status;
    private String reason;
    private String message;
    private String lastHeartbeatTime;
    private String lastTransitionTime;
}
