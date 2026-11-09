package com.coding.platformapi.metrics.labels;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 拓扑「哪些工作负载是 waypoint」查询的标签：{@code reporter="waypoint"} 的序列是从 waypoint Pod 上抓的，
 * 抓取目标标签 {@code namespace} / {@code pod} 就是那个 waypoint Pod 本身。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class WaypointPodLabels extends MetricLabels {

    private String namespace;

    private String pod;
}
