package com.coding.common.models.k8s.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
@Schema(description = "Pod 模板（metadata + spec）")
public class PodTemplateDTO {

    @Schema(description = "标签")
    private Map<String, String> labels;

    @Schema(description = "注解")
    private Map<String, Object> annotations;

    @Schema(description = "固定 IP（Calico）：pod template 注解 cni.projectcalico.org/ipAddrs 的 JSON 数组，双栈 v4+v6；仅单副本 deployment/statefulset 允许")
    private List<String> staticIps;

    @Schema(description = "Pod 规格（完整 PodSpec）")
    private PodSpecDTO spec;

}
