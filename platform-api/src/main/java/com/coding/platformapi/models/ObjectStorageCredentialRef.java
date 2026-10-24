package com.coding.platformapi.models;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 定位一组凭据（查看 / 吊销）。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ObjectStorageCredentialRef {
    private String clusterId;
    private String tenantId;
    private String credentialId;
}
