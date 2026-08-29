package com.coding.data.models.system;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@NoArgsConstructor
@AllArgsConstructor
@Builder
@Data
public class TokenUserInfo {

    private String username;

    private String displayName;

    private String email;
    /**
     * 1正常 0禁用
     */
    private Byte status;

    private UserTenantInfo tenantInfo;

    /**
     * 平台域角色 code 列表（如 admin），与租户域 tenantInfo 并列、互不推导
     */
    private List<String> platformRoles;


}
