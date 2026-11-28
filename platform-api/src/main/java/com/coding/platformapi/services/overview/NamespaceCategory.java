package com.coding.platformapi.services.overview;

import com.coding.platformapi.configs.NamespaceProtectionProperties;

import java.util.Set;

/** 系统分类优先，系统名单与命名空间写保护共用配置。 */
public enum NamespaceCategory {
    SYSTEM, ALLOCATED, UNALLOCATED;

    public static NamespaceCategory of(String namespace, Set<String> allocated, NamespaceProtectionProperties protection) {
        if (namespace == null) {
            return UNALLOCATED;
        }
        if (protection.isProtected(namespace)) {
            return SYSTEM;
        }
        return allocated.contains(namespace) ? ALLOCATED : UNALLOCATED;
    }
}
