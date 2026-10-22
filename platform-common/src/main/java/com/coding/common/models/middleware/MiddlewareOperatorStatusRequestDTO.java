package com.coding.common.models.middleware;

import lombok.Data;

import java.util.List;

/** platform-api → k8s-server：批量判定 Operator 状态。codes 为空 = 全部已知中间件。 */
@Data
public class MiddlewareOperatorStatusRequestDTO {

    private String clusterId;

    private List<String> codes;
}
