package com.coding.platformapi.models;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 凭据列表项：只给 accessKey（secretKey 不落库）。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ObjectStorageCredentialView {
    private String id;
    private String bucketId;
    private String identityName;
    private String accessKey;
    private String status;
    private String createdBy;
    private java.util.Date createdAt;
}
