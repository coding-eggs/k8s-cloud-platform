package com.coding.common.models.objectstorage;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 一组 S3 凭据。secretKey 不落库，只在按需读取时出现在响应里。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ObjectCredentialDTO {
    private String accessKey;
    private String secretKey;
}
