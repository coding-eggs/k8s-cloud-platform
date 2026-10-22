package com.coding.common.models.middleware;

import lombok.Data;

/** Operator 安装状态（k8s-server 判定，api 落库并展示）。 */
@Data
public class MiddlewareOperatorStatusDTO {

    private String code;

    /** CRD 或 release 任一侧存在即视为已装过 */
    private boolean installed;

    /** CRD 齐全且 Operator Deployment 就绪 */
    private boolean ready;

    private String chartVersion;

    private String message;
}
