package com.coding.common.models.k8s.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.Map;

@Data
@Schema(description = "Pod 证书投影（Kubelet 自动生成密钥并向 signer 申请证书，自动轮换；credentialBundlePath 与 keyPath+certificateChainPath 二选一）")
public class PodCertificateProjectionDTO {

    @Schema(description = "CSR 指向的签名者名（必填）")
    private String signerName;

    @Schema(description = "Kubelet 生成的密钥类型：RSA3072/RSA4096/ECDSAP256/ECDSAP384/ECDSAP521/ED25519（必填）")
    private String keyType;

    @Schema(description = "凭据 bundle 写入路径（单文件：PKCS#8 私钥 + 证书链，推荐）")
    private String credentialBundlePath;

    @Schema(description = "私钥写入路径（与 certificateChainPath 配套，非推荐）")
    private String keyPath;

    @Schema(description = "证书链写入路径（与 keyPath 配套，非推荐）")
    private String certificateChainPath;

    @Schema(description = "证书最大有效期秒数（缺省 86400；下限 3600，上限 7862400）")
    private Integer maxExpirationSeconds;

    @Schema(description = "透传给 signer 的附加注解（key 须带域名前缀）")
    private Map<String, String> userAnnotations;

}
