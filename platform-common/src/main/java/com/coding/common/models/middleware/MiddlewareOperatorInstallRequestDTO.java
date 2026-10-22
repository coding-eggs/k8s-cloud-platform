package com.coding.common.models.middleware;

import lombok.Data;

/** platform-api → k8s-server：安装/升级某款中间件的 Operator。 */
@Data
public class MiddlewareOperatorInstallRequestDTO {

    private String clusterId;

    private String code;
}
