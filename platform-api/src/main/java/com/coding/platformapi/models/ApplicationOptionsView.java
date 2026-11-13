package com.coding.platformapi.models;

import lombok.Data;

import java.util.List;

/** 新建应用表单的可选项。 */
@Data
public class ApplicationOptionsView {

    /** 当前租户在该集群分到的命名空间（请求带了 clusterId 时才有意义） */
    private List<String> namespaces;

    /**
     * 可选的「集群 + 命名空间」组合 —— 应用列表按租户跨集群展示（2026-11-13 起不挂顶栏），
     * 新建时在表单里一次选定集群和命名空间。按集群名、命名空间排序。
     */
    private List<Target> targets;

    @Data
    public static class Target {
        private String clusterId;
        private String clusterName;
        private String namespace;
    }
}
