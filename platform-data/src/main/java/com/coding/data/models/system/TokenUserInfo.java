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


}
