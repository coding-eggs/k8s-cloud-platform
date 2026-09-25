package com.coding.platformapi.models;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 租户成员行视图（/tenant/member/list 返回）：
 * owner = 该成员持有内置 tenant-admin 角色
 */
@Data
@Schema(description = "租户成员视图")
public class TenantMemberView {

    @Schema(description = "用户id")
    private String userId;

    @Schema(description = "用户名")
    private String username;

    @Schema(description = "显示名")
    private String displayName;

    @Schema(description = "该成员在本租户内被授予的角色 id 列表")
    private List<String> roleIds;

    @Schema(description = "是否租户管理员（持有 tenant-admin）")
    private boolean owner;
}
