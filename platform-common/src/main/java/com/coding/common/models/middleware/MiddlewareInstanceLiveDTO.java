package com.coding.common.models.middleware;

import lombok.Data;

import java.util.List;

/**
 * 实例在集群里的实时对象（详情页「资源」与「YAML」两块）。
 *
 * <p>只读快照：CR 本体 + 该实例的 Pod / PVC / Service。查询口径与状态判定一致
 * （CR 按适配器 contexts 找、Pod 用 statusSelector、PVC 用 pvcSelector），
 * 这样详情页看到的和列表状态不会对不上。
 */
@Data
public class MiddlewareInstanceLiveDTO {

    /** CR 的 kind / name / apiVersion（找不到 CR 时为 null） */
    private String kind;
    private String name;
    private String apiVersion;
    private String createdAt;

    /** CR 的 YAML（找不到为 null） */
    private String yaml;

    private List<PodView> pods;
    private List<PvcView> pvcs;
    private List<ServiceView> services;

    /**
     * CR 里是否装了 IK 分词器（只有 ES 有这一项）。
     * null = 不适用或 CR 没读到；前端据此优先于台账 spec_json 回显「已安装」。
     */
    private Boolean ikInstalled;

    @Data
    public static class PodView {
        private String name;
        private String phase;
        private boolean ready;
        /** 就绪容器数/总容器数，如 "1/2" */
        private String readyText;
        private int restarts;
        private String node;
        private String createdAt;
    }

    @Data
    public static class PvcView {
        private String name;
        private String phase;
        private String capacity;
        private String storageClass;
        private String createdAt;
    }

    @Data
    public static class ServiceView {
        private String name;
        private String type;
        private String clusterIp;
        /** 端口摘要，如 "6379/TCP,26379/TCP" */
        private String ports;
    }
}
