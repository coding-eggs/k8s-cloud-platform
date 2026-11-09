package com.coding.platformapi.models;

import java.util.List;
import java.util.Map;

/**
 * 应用页首屏：服务（现读状态）、依赖、待发布与执行进度、应用设置。
 *
 * <p>pendingKeys 里的 {@code @app}（{@code AppSettings.KEY}）表示应用设置有待发布改动。
 */
public record AppOverviewView(String appId, String status, Integer liveReleaseNo,
                              List<ServiceRow> services,
                              List<DependencyRow> dependencies, List<String> pendingKeys,
                              AppExecutionView activeExecution, AppExecutionView lastExecution,
                              AppSettingsView settings) {

    /**
     * state：READY / PENDING / FAILED / MISSING / UNKNOWN 为线上现读状态；
     * NOT_RELEASED 表示该服务只存在于期望配置，还没有发布到线上。
     * pending：ADDED / CHANGED / DELETED / null。
     * desired / version：编辑表单的初值与乐观锁；线上有、台账无行的孤儿服务 desired 取线上快照，version 为 null。
     * newTag：镜像仓库里比线上 tag 更晚推送的 tag（见 AppImageUpdates）；没有、不适用或没查成为 null。
     * ingress：网关方式分到的端口及其 listener 状态；没分端口为 null。
     * nodePort：线上 Service 为 NodePort 类型时实际的 nodePort（现读）；不是 NodePort 或读不到为 null。
     * canary：线上正在灰度时的灰度信息；不在灰度为 null。
     */
    public record ServiceRow(String key, String kind, String image, String liveImage, Integer replicas,
                             String state, String pending, Map<String, Object> desired, Integer version,
                             String newTag, Ingress ingress, Integer nodePort, Canary canary) {
    }

    /**
     * 入口灰度：权重、header 规则（没开为 null）、灰度版本镜像与副本数、灰度工作负载的现读状态
     * （READY / PENDING / FAILED / MISSING / UNKNOWN）。
     */
    public record Canary(int weight, String headerName, String headerValue, String image, int replicas,
                         String state, boolean external) {
    }

    /**
     * 网关端口 + 命名空间 ListenerSet 里对应 listener（{@code p<端口>}）的状态。
     * programmed / conflicted 为 null = 状态未知（集群读不到、或 listener 还没下发）。
     */
    public record Ingress(Integer port, String protocol, Boolean programmed, Boolean conflicted,
                          String reason, String message) {
    }

    /**
     * initStatus / initLog：最近一次初始化的状态与日志（没跑过为 null）；
     * external：EXTERNAL 依赖的连接信息（只含地址与 Secret 名/键，不含口令）；
     * code：中间件依赖的实例类型（mysql / redis ...，实例读不到时为 null），EXTERNAL 恒为 null——
     * 前端据此判断该依赖有哪些可引用字段、能否初始化。
     */
    public record DependencyRow(String key, String type, String ownership, String instanceId, String status,
                                String initStatus, String initLog, Map<String, Object> external, String code) {
    }
}
