package com.coding.platformapi.trace;

import lombok.Data;

/** trace 查询条件（Tempo TraceQL）。 */
@Data
public class TraceSearchRequest {
    /**
     * 租户 id：平台管理员用顶栏/筛选器选中的租户；租户用户会被后端强制成自己的租户。
     * 解析成 {@code platform_tenant_namespace} 的 (集群, 命名空间) 条件。
     */
    private String tenantId;
    /** 集群名（resource.cluster.name） */
    private String clusterName;
    /** 命名空间（resource.k8s.namespace.name） */
    private String namespace;
    /** 服务名（resource.service.name） */
    private String serviceName;
    /** unix 秒；空 = 最近 1 小时 */
    private Long start;
    private Long end;
    /** 默认 20，最大 200 */
    private Integer limit;
    /** 只看包含 error 状态的 span 的 trace（TraceQL {@code status = error}） */
    private Boolean errorsOnly;
    /** 只看耗时超过该毫秒数的 trace（TraceQL {@code duration > Nms}） */
    private Integer minDurationMs;
}
