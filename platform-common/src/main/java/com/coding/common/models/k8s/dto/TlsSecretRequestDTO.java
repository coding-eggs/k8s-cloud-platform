package com.coding.common.models.k8s.dto;

import lombok.Data;

/**
 * 把一对 PEM 证书/私钥写成 {@code kubernetes.io/tls} Secret（platform-api → k8s-server）。
 *
 * <p>证书只在这一次请求里出现：不落 values、不进操作快照、不回显（与外部库口令同一条规矩）。
 * 用于 Harbor 的 HTTPS（{@code harbor/harbor-tls} 或 {@code istio-system/harbor-tls}）。
 */
@Data
public class TlsSecretRequestDTO {
    private String clusterId;
    private String namespace;
    private String secretName;
    /** 证书链（PEM，含中间证书，顺序：域名证书在前） */
    private String certPem;
    /** 私钥（PEM，不能带口令） */
    private String keyPem;
}
