package com.coding.platformapi.services.overview;

import com.coding.common.constants.ProbeNamespaces;

import java.util.Set;

/**
 * 命名空间三类（spec §4）：平台系统优先于已分配 —— 系统命名空间曾被分给租户（如 kube-system 给了 default），
 * 那种不算「租户的」。
 */
public enum NamespaceCategory {
    SYSTEM, ALLOCATED, UNALLOCATED;

    /** 平台自身所在的命名空间 */
    static final String PLATFORM_NAMESPACE = "platform-system";

    public static NamespaceCategory of(String namespace, Set<String> allocated) {
        if (namespace == null) {
            return UNALLOCATED;
        }
        if (namespace.startsWith("kube-") || PLATFORM_NAMESPACE.equals(namespace)
                || ProbeNamespaces.DEFAULT.contains(namespace)) {
            return SYSTEM;
        }
        return allocated.contains(namespace) ? ALLOCATED : UNALLOCATED;
    }
}
