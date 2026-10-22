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

    /** true = 不走 Operator（chart 安装，如 Nacos），不受 Operator 就绪门控 */
    private boolean chartBacked;

    private boolean operatorInstalled;

    private boolean operatorReady;

    private String chartVersion;

    private String operatorMessage;

    private int instanceCount;
}
