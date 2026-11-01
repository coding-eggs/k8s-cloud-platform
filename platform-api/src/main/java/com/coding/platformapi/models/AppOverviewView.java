package com.coding.platformapi.models;

import java.util.List;

/** 应用页首屏：服务（现读状态）、依赖、待发布与执行进度。 */
public record AppOverviewView(String appId, String status, Integer liveReleaseNo, List<ServiceRow> services,
                              List<DependencyRow> dependencies, List<String> pendingKeys,
                              AppExecutionView activeExecution, AppExecutionView lastExecution) {

    /** pending：ADDED / CHANGED / DELETED / null；state：READY / PENDING / FAILED / MISSING / UNKNOWN */
    public record ServiceRow(String key, String kind, String image, String liveImage, Integer replicas,
                             String state, String pending) {
    }

    public record DependencyRow(String key, String type, String ownership, String instanceId, String status) {
    }
}
