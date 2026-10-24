package com.coding.platformapi.models;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 对象存储页面的查询入参：clusterId + （管理员可指定）tenantId。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ObjectStorageQuery {
    private String clusterId;
    /** 管理员代看某个租户时传；租户自己传会被服务端覆盖成自己的租户 */
    private String tenantId;
}
