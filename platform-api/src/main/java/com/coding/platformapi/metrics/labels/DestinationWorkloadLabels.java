package com.coding.platformapi.metrics.labels;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * {@code sum by (destination_workload)} 查询的标签：灰度按目标工作负载拆 stable / canary
 * （waypoint 按路由选后端后，上报的目标 Service 可能仍是 stable Service，按 Service 名分不开两个版本）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class DestinationWorkloadLabels extends MetricLabels {

    @JsonProperty("destination_workload")
    private String workload;
}
