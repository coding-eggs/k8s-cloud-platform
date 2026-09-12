package com.coding.platformapi.metrics.labels;

import lombok.Data;

/** 指标标签父类。本功能的 sum() 查询不产生 label，故当前无字段（业务只读 {@code PromResult.values}）。
 * <p>未来某条查询用 {@code by(xxx)} 需要读标签时，加子类补对应字段；多词标签（如 cluster_name）
 * 在子类里用 {@code @JsonProperty("cluster_name")} 显式映射，不用 class 级 naming（避免继承歧义）。 */
@Data
public class MetricLabels {
}
