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
}
