package com.coding.common.components.jwt;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class PermissionClosure {
    private PermissionClosure() {}
    /** 合并平台族与租户族权限 code，去重、稳定顺序。 */
    public static List<String> merge(List<String> platformPermCodes, List<String> tenantPermCodes) {
        Set<String> out = new LinkedHashSet<>();
        if (platformPermCodes != null) out.addAll(platformPermCodes);
        if (tenantPermCodes != null) out.addAll(tenantPermCodes);
        return new ArrayList<>(out);
    }
}
