package com.coding.common.models.k8s.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
@Schema(description = "Pod 规格（完整 PodSpec）")
public class PodSpecDTO {

    @Schema(description = "容器列表")
    private List<ContainerDTO> containers;

    @Schema(description = "初始化容器列表")
    private List<ContainerDTO> initContainers;

    @Schema(description = "重启策略（Always/OnFailure/Never）")
    private String restartPolicy;

    @Schema(description = "ServiceAccount 名称")
    private String serviceAccountName;

    @Schema(description = "指定节点名")
    private String nodeName;

    @Schema(description = "节点选择器")
    private Map<String, String> nodeSelector;

    @Schema(description = "亲和性")
    private AffinityDTO affinity;

    @Schema(description = "容忍度列表")
    private List<TolerationDTO> tolerations;

    @Schema(description = "卷列表")
    private List<VolumeDTO> volumes;

    @Schema(description = "镜像拉取密钥列表")
    private List<ImagePullSecretRefDTO> imagePullSecrets;

}
