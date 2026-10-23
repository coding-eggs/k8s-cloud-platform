package com.coding.common.models.middleware;

import lombok.Data;

import java.util.List;
import java.util.Map;

/** platform-api → k8s-server：批量读实例状态。 */
@Data
public class MiddlewareStatusRequestDTO {

    private String clusterId;

    private String code;

    private String namespace;

    private List<String> names;

    /**
     * 实例名 → 实例 id。用来按平台自己的标签
     * {@code platform.coding/middleware-instance=<id>} 找 Pod：
     * Redis 哨兵形态的数据 Pod 与哨兵 Pod 的 app 标签不同，按名字拼集合式 selector
     * 在 fabric8 里不可靠（真集群验证：只命中 2 个）。
     */
    private Map<String, String> instanceIds;

    /** 管理员代管轨的租户 */
    private String tenantId;
}
