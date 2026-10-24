package com.coding.common.models.objectstorage;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 读取一组凭据（AK/SK）—— 只给 platform-api 在校验权限后取用。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ObjectCredentialGetRequestDTO {
    private String clusterId;
    private String secretName;
}
