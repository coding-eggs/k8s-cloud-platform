package com.coding.common.models.middleware;

import lombok.Data;

import java.util.List;

/** platform-api → k8s-server：批量读实例状态。 */
@Data
public class MiddlewareStatusRequestDTO {

    private String clusterId;

    private String code;

    private String namespace;

    private List<String> names;
}
