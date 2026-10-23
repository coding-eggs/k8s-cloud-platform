package com.coding.common.models.middleware;

import lombok.Data;

import java.util.List;

/**
 * 实例在集群里的实时对象（详情页「资源」与「YAML」两块）。
 *
 * <p>只读快照：CR 本体 + 该实例的 Pod / PVC / Service / 工作负载 / 凭据 Secret / 事件。
 * 查询口径与状态判定一致（CR 按适配器 contexts 找、Pod 用 statusSelector、PVC 用 pvcSelector），
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

    /** Operator / Helm 为该实例建的工作负载（StatefulSet / Deployment） */
    private List<WorkloadView> workloads;

    /** 该实例的凭据 Secret（只给名字、类型与键名，不回传值） */
    private List<SecretView> secrets;

    /** 同一实例的其它 CR（如 ES 勾选的 Kibana CR） */
    private List<CrView> relatedCrs;

    /** 主 CR 的 status.conditions（DEGRADED 时看这里） */
    private List<ConditionView> conditions;

    /** 与该实例相关的最近事件（按时间倒序，最多 50 条） */
    private List<EventView> events;

    /** 某一块读失败（多为租户 SA 的 K8s RBAC 缺该项）的说明；其余块照常返回 */
    private List<String> warnings;

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

    @Data
    public static class WorkloadView {
        /** StatefulSet / Deployment */
        private String kind;
        private String name;
        private String apiVersion;
        /** 期望副本数（读不到为 null） */
        private Integer desiredReplicas;
        /** 就绪副本数 */
        private Integer readyReplicas;
        /** 容器镜像摘要，如 "elasticsearch: docker.elastic.co/...:9.5.0" */
        private String images;
        private String createdAt;
    }

    @Data
    public static class SecretView {
        private String name;
        /** 类型，如 Opaque / kubernetes.io/basic-auth */
        private String type;
        /** 键名（不含值），如 "password"、"elastic" */
        private String keys;
        private String createdAt;
    }

    @Data
    public static class CrView {
        private String kind;
        private String name;
        private String apiVersion;
        private String createdAt;
        private String yaml;
    }

    @Data
    public static class ConditionView {
        private String type;
        private String status;
        private String reason;
        private String message;
        private String lastTransitionTime;
    }

    @Data
    public static class EventView {
        /** Normal / Warning */
        private String type;
        private String reason;
        private String message;
        /** 涉及对象（kind/name） */
        private String object;
        private Integer count;
        private String lastTimestamp;
    }
}
