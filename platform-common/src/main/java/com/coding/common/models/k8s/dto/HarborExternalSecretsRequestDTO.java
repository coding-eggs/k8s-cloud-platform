package com.coding.common.models.k8s.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Harbor 使用外部 PostgreSQL / Redis 时的口令 Secret（S3.6）。
 *
 * <p>口令由 platform-api 随安装请求传入、由 k8s-server 写进 Secret，**不进 values 快照**。
 * 编辑时口令留空 = 沿用已有 Secret（k8s-server 会拒绝「留空且 Secret 不存在」）。
 */
@Data
@NoArgsConstructor
public class HarborExternalSecretsRequestDTO {
    private String clusterId;
    /** Secret 所在命名空间（= Harbor 的 release 命名空间） */
    private String namespace;

    private boolean databaseExternal;
    /** 键名：password */
    private String databaseSecretName;
    private String databasePassword;

    private boolean redisExternal;
    /** 键名：REDIS_PASSWORD（可选 REDIS_USERNAME） */
    private String redisSecretName;
    private String redisUsername;
    private String redisPassword;
}
