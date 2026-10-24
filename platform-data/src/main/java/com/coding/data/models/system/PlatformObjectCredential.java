package com.coding.data.models.system;

import lombok.Data;

import java.util.Date;

/**
 * 对象存储访问凭据（表 platform_object_credential）。
 *
 * <p>**secretKey 不落库**：只存 accessKey（可展示）与密钥所在的 K8s Secret 名，
 * 密钥本体由 k8s-server 写进 Secret，按权限现取。
 */
@Data
public class PlatformObjectCredential {

    private String id;

    private String clusterId;

    /** platform_object_bucket.id */
    private String bucketId;

    private String tenantId;

    /** SeaweedFS S3 配置里的 identity 名（如 bucket-t-acme-logs-1a2b3c4d） */
    private String identityName;

    private String accessKey;

    /** K8s Secret 名（platform-storage 命名空间），存 access_key / secret_key */
    private String secretName;

    /** ACTIVE / REVOKED */
    private String status;

    private String createdBy;

    private Date createdAt;

    private Date revokedAt;
}
