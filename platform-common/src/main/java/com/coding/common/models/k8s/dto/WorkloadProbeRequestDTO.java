package com.coding.common.models.k8s.dto;

import lombok.Data;

import java.util.List;

/** {@code POST /workload-probes} 的请求体。clusterId 在 body 里而不是 query —— 与既有派生查询同形。 */
@Data
public class WorkloadProbeRequestDTO {
    private String clusterId;
    private List<WorkloadProbeTargetDTO> targets;
}
