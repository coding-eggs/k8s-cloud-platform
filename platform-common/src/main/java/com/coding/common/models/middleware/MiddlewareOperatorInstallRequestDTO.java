package com.coding.common.models.middleware;

import lombok.Data;

/** platform-api → k8s-server：安装/升级某款中间件的 Operator。 */
@Data
public class MiddlewareOperatorInstallRequestDTO {

    private String clusterId;

    private String code;

    /** 镜像仓库：非空时 Operator chart 的默认镜像（含 RabbitMQ 等 workload 镜像）一并改写 */
    private String imageRegistryHost;
    private String imageRegistryProject;
}
