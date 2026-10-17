package com.coding.common.models.k8s.dto;

import lombok.Data;

import java.util.Map;

/** 一个 release 的安装规格（由 platform-api 的 recipe 解析后下发）。 */
@Data
public class ReleaseInstallSpecDTO {
    private String releaseName;
    private String namespace;
    /** 内置 chart 的目录名，如 metrics-server */
    private String chartCode;
    /** 目标 chart 版本，如 1.0.0 */
    private String chartVersion;
    /** 平台契约值 + 用户可编辑值合并后的最终 values */
    private Map<String, Object> values;
}
