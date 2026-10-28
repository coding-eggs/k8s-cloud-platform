package com.coding.common.models.k8s.dto;

import lombok.Data;

/** 「列出内置 chart 用到的镜像」的查询参数（镜像仓库可空 = 不改写）。 */
@Data
public class ImageRegistryQueryDTO {
    private String clusterId;
    private String imageRegistryHost;
    private String imageRegistryProject;
}
