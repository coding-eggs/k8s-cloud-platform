package com.coding.platformapi.models;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Thanos 安装参数（管理集群的 Prometheus recipe 用）。
 *
 * <p>凭证不在表单里：{@code secretName} 指向集群里已有、含 access key / secret key 的 Secret；
 * k8s-server 在集群内读它并拼出 {@code objstore.yml}。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ThanosInstallRequest {
    /** 凭证 Secret 所在命名空间（也是 Thanos 部署命名空间），默认 monitor */
    private String namespace;
    /** 含 access key / secret key 的 Secret 名 */
    private String secretName;
    /** MinIO 地址（host:port，可带 http(s)://） */
    private String endpoint;
    /** 桶名 */
    private String bucket;
    /** 自签证书时跳过校验 */
    private boolean insecureSkipVerify;
}
