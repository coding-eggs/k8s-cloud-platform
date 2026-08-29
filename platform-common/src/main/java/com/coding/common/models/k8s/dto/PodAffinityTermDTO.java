package com.coding.common.models.k8s.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
@Schema(description = "Pod 亲和性条件项")
public class PodAffinityTermDTO {

    @Schema(description = "命名空间列表")
    private List<String> namespaces;

    @Schema(description = "拓扑键")
    private String topologyKey;

    @Schema(description = "标签匹配")
    private Map<String, String> matchLabels;

}
