package com.coding.platformapi.models;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 建桶（仅管理员）：给指定租户建一个桶。
 *
 * <p>桶名由平台加租户前缀（{@code t-<租户短码>-<name>}），跨租户不会撞名。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ObjectStorageCreateBucket {
    private String clusterId;
    private String tenantId;
    /** 用户可读的名字部分（小写字母/数字/连字符） */
    private String name;
    private String displayName;
}
