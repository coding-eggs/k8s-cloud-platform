package com.coding.data.models.system;

import lombok.Data;
import org.jspecify.annotations.Nullable;
import org.springframework.security.core.GrantedAuthority;

@Data
public class SecurityRole implements GrantedAuthority {

    private String tenantName;

    private String roleCode;

    private String roleName;

    private String serviceAccount;


    @Override
    public @Nullable String getAuthority() {
        return tenantName + ":" + roleCode;
    }
}
