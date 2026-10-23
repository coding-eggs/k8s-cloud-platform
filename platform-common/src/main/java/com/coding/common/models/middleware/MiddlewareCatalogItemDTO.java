package com.coding.common.models.middleware;

import lombok.Data;

import java.util.List;

/** 商店卡片（固定清单的一项 + 本集群的 Operator 状态与实例数）。 */
@Data
public class MiddlewareCatalogItemDTO {

    private String code;

    private String name;

    private String description;

    private List<MiddlewareModeDTO> modes;

    /** 平台支持的可选版本（第一项为默认）；空 = 版本由实现固定，表单不展示版本字段 */
    private List<String> versions;

    /** true = 不走 Operator（chart 安装，如 Nacos），不受 Operator 就绪门控 */
    private boolean chartBacked;

    private boolean operatorInstalled;

    private boolean operatorReady;

    private String chartVersion;

    private String operatorMessage;

    private int instanceCount;
}
