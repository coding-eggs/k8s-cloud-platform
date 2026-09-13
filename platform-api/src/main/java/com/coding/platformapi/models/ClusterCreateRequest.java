package com.coding.platformapi.models;

import com.coding.data.models.k8s.K8sCluster;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "集群纳管请求")
public class ClusterCreateRequest {

    @Schema(description = "集群名称", requiredMode = Schema.RequiredMode.REQUIRED, example = "prod-cluster-1")
    private String clusterName;

    @Schema(description = "kubeconfig 明文（.kube/config 文件内容）", requiredMode = Schema.RequiredMode.REQUIRED)
    private String kubeconfig;

    @Schema(description = "集群描述")
    private String description;

    @Schema(description = "集群版本", example = "v1.29.0")
    private String version;

    @Schema(description = "Istio 版本", example = "1.21.0")
    private String istioVersion;

    @Schema(description = "Calico 版本", example = "3.27.0")
    private String calicoVersion;

    @Schema(description = "容器运行时", example = "containerd")
    private String containerRuntime;

    @Schema(description = "IP栈类型：IPV4/IPV6/IPV4_AND_IPV6（不填默认 IPV4）", example = "IPV4")
    private K8sCluster.IPStack ipStack;

    @Schema(description = "Prometheus 地址（仅记录，本监控功能不用）")
    private String prometheusUrl;

    @Schema(description = "Grafana 地址（仅记录）")
    private String grafanaUrl;
}
