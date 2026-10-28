package com.coding.common.models.middleware;

import lombok.Data;

/** k8s-server → platform-api：单个实例的实时状态（由 CR/工作负载推导）。 */
@Data
public class MiddlewareStatusResultDTO {

    private String name;

    /** READY / DEGRADED / FAILED / CREATING */
    private String status;

    private String message;

    private Integer readyReplicas;

    private Integer replicas;

    /**
     * 实际部署的版本（列表 / 详情的「版本」列）。
     *
     * <p>只有 ES/Kafka/Milvus 有版本下拉，台账里记的就是它；其余中间件的版本由 Operator/chart
     * 决定，台账里是空的，只能从线上推（CR 里的显式版本，或首个 Pod 的镜像 tag）。
     * null = 推不出来（还没有 Pod / 镜像没有 tag），调用方保留台账值。
     */
    private String version;
}
