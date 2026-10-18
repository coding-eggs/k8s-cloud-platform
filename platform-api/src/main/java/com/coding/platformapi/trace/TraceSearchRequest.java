package com.coding.platformapi.trace;

import lombok.Data;

/** trace 查询条件（Tempo TraceQL）。 */
@Data
public class TraceSearchRequest {
    /** 集群名（resource.cluster.name） */
    private String clusterName;
    /** 服务名（resource.service.name） */
    private String serviceName;
    /** unix 秒；空 = 最近 1 小时 */
    private Long start;
    private Long end;
    /** 默认 20，最大 200 */
    private Integer limit;
}
