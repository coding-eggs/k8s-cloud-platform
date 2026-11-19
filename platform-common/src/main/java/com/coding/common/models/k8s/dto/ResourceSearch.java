package com.coding.common.models.k8s.dto;

import java.util.List;
import java.util.Comparator;
import java.util.Locale;

public final class ResourceSearch {
    private ResourceSearch() {}
    public record Query(String clusterId, String tenantId, String namespace, String keyword, String kind) {
        public String term() {
            if (clusterId == null || clusterId.isBlank() || keyword == null || keyword.isBlank() || keyword.length() > 128)
                throw new IllegalArgumentException("请选择集群并输入资源名称");
            return keyword.strip().toLowerCase(Locale.ROOT);
        }
    }
    public record Hit(String name, String kind, String namespace, String id) {}
    public record Results(List<Hit> hits, boolean truncated, boolean partial) {}
    public static Comparator<Hit> order(String term) {
        return Comparator.<Hit>comparingInt(hit -> {
            String name = hit.name().toLowerCase(Locale.ROOT);
            if (name.equals(term)) return 0;
            return name.startsWith(term) ? 1 : 2;
        }).thenComparing(Hit::name).thenComparing(Hit::namespace).thenComparing(Hit::kind);
    }
}
