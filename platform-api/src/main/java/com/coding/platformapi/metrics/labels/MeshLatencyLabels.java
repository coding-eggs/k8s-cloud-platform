package com.coding.platformapi.metrics.labels;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 延迟查询的标签：分位数计算要 `by (le)`，故比边多一个桶边界。 */
@Data
@EqualsAndHashCode(callSuper = true)
public class MeshLatencyLabels extends MeshEdgeLabels {

    @JsonProperty("le")
    private String le;
}
