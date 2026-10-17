package com.coding.platformapi.metrics.labels;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 错误子集查询的标签：在 {@link MeshEdgeLabels} 之外多一个响应码（按 `5..` 过滤时其实恒为 5xx，留着便于排障）。 */
@Data
@EqualsAndHashCode(callSuper = true)
public class MeshErrorLabels extends MeshEdgeLabels {

    @JsonProperty("response_code")
    private String responseCode;
}
