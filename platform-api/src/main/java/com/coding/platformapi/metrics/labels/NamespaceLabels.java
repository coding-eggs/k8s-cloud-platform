package com.coding.platformapi.metrics.labels;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * {@code sum by(namespace)} 查询的标签：读命名空间名作图例（一条序列 = 一个命名空间）。
 * <p>集群概览「资源明细」的 used 列靠它与平台侧 per-ns 表的行对齐 —— 两边的 key 都是裸命名空间名。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class NamespaceLabels extends MetricLabels {

    private String namespace;
}
