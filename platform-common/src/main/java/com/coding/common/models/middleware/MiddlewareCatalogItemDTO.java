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

    private boolean operatorInstalled;

    private boolean operatorReady;

    private String chartVersion;

    private String operatorMessage;

    private int instanceCount;
}
