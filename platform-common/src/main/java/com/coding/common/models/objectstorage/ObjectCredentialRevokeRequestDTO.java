package com.coding.common.models.objectstorage;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 吊销凭据：从 S3 配置里删 identity、删除对应 Secret，再滚动 S3 网关。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ObjectCredentialRevokeRequestDTO {
    private String clusterId;
    private String endpoint;
    private String identityName;
    private String secretName;
}
