package com.coding.data.models.auth;

/** 角色族：PLATFORM=平台管理动作；TENANT=租户内管理动作。见 spec §3。 */
public enum RoleScope {
    PLATFORM, TENANT;

    public static boolean isValid(String v) {
        for (RoleScope s : values()) if (s.name().equals(v)) return true;
        return false;
    }
}
