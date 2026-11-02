package com.coding.platformapi.models;

import java.util.List;
import java.util.Map;

/** 应用页首屏：服务（现读状态）、依赖、待发布与执行进度。 */
public record AppOverviewView(String appId, String status, Integer liveReleaseNo, List<ServiceRow> services,
                              List<DependencyRow> dependencies, List<String> pendingKeys,
                              AppExecutionView activeExecution, AppExecutionView lastExecution) {

    /**
     * state：READY / PENDING / FAILED / MISSING / UNKNOWN 为线上现读状态；
     * NOT_RELEASED 表示该服务只存在于期望配置，还没有发布到线上。
     * pending：ADDED / CHANGED / DELETED / null。
     * desired / version：编辑表单的初值与乐观锁；线上有、台账无行的孤儿服务 desired 取线上快照，version 为 null。
     */
    public record ServiceRow(String key, String kind, String image, String liveImage, Integer replicas,
                             String state, String pending, Map<String, Object> desired, Integer version) {
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
