package com.coding.platformapi.metrics.labels;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 拓扑的边：两端的工作负载 + 命名空间。
 * <p>⚠️ 命名空间必须带 —— 同名 workload 跨命名空间很常见，只按 workload 名分组会把两条边粘成一个节点。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class MeshEdgeLabels extends MetricLabels {

    @JsonProperty("source_workload")
    private String sourceWorkload;

    @JsonProperty("destination_workload")
    private String destinationWorkload;

    @JsonProperty("source_workload_namespace")
    private String sourceNamespace;

    @JsonProperty("destination_workload_namespace")
    private String destinationNamespace;
}
