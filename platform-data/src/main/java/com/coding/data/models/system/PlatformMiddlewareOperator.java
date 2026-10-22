package com.coding.data.models.system;

import lombok.Data;

import java.util.Date;

/** 某集群某款中间件的 Operator 安装状态（表 platform_middleware_operator）。 */
@Data
public class PlatformMiddlewareOperator {

    private String id;

    private String clusterId;

    /** mysql / redis / kafka / elasticsearch / postgresql / rabbitmq / nacos */
    private String code;

    /** 安装时钉死的 chart 版本 */
    private String chartVersion;

    /** NOT_INSTALLED / INSTALLING / READY / FAILED / DEGRADED */
    private String status;

    private String message;

    private Date installedAt;

    private Date updatedAt;
}
