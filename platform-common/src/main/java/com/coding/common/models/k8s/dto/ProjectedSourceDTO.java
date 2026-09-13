package com.coding.common.models.k8s.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "projected 卷的单个投影来源（serviceAccountToken / configMap / secret / downwardAPI / clusterTrustBundle / podCertificate 六选一）")
public class ProjectedSourceDTO {

    @Schema(description = "ServiceAccount Token 投影")
    private ServiceAccountTokenProjectionDTO serviceAccountToken;

    @Schema(description = "ConfigMap 投影")
    private ConfigMapProjectionDTO configMap;

    @Schema(description = "Secret 投影")
    private SecretProjectionDTO secret;

    @Schema(description = "DownwardAPI 投影")
    private DownwardAPIProjectionDTO downwardAPI;

    @Schema(description = "ClusterTrustBundle 投影（集群信任根 CA bundle，自动更新）")
    private ClusterTrustBundleProjectionDTO clusterTrustBundle;

    @Schema(description = "Pod 证书投影（Kubelet 代申请并自动轮换的 TLS 凭据）")
    private PodCertificateProjectionDTO podCertificate;
}
