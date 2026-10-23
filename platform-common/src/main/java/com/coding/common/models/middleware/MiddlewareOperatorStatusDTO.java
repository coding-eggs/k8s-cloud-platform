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

    /** 平台要装的 chart 版本（目录里钉死的那个） */
    private String chartVersion;

    /** 集群里实际在跑的那个 chart 版本（取自工作负载的 helm.sh/chart 标签；读不到为 null） */
    private String installedChartVersion;

    /** 已安装且版本与目录不一致 = 有升级可做 */
    private boolean upgradeAvailable;

    private String message;
}
