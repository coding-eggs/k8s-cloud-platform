package com.coding.common.models.k8s.dto;

import lombok.Data;

/**
 * 租户概览里「一个集群」的小计行（{@link TenantOverviewDTO#getClusters()}）。
 * <p>{@code namespaceCount} 是**范围**（分配表里该集群下属于本租户的命名空间数），
 * 不因集群取不到数而变；{@code available=false} 时 {@code total} 为 null（本页少算这一块，并显式标记）。
 */
@Data
public class TenantClusterSummaryDTO {

    private String clusterId;

    /** 集群名（取自 {@code K8sClusterMapper}）；集群已不存在时为 null。 */
    private String clusterName;

    /** 该集群下属于本租户的命名空间数（=范围，来自分配表）。 */
    private int namespaceCount;

    /** 本次请求是否取到该集群的切片。 */
    private boolean available;

    /** 该集群的切片小计；{@code available=false} 时为 null。 */
    private ClusterTotalDTO total;
}
