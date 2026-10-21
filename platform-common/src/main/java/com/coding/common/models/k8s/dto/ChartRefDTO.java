package com.coding.common.models.k8s.dto;

import lombok.Data;

/** chart 引用（应用商店 / Operator 安装共用）。 */
@Data
public class ChartRefDTO {
    /** HARBOR（OCI）或 REPO（HTTP chart repo） */
    private String source;
    /** source=HARBOR 时是 oci:// 地址；否则是 repo URL */
    private String ref;
    private String chartName;
    private String version;
    /** 期望的 sha256（"sha256:..."）；空 = 不校验（管理员直装） */
    private String digest;
    private String username;
    private String password;
}
