package com.coding.common.models.app;

import lombok.Data;

import java.util.List;

/** k8s-server → platform-api：合并后的清单 + 对象列表 + 问题（ERROR 阻断部署）。 */
@Data
public class AppRenderResultDTO {

    private String manifest;

    private List<AppObjectRefDTO> objects;

    private List<AppProblemDTO> problems;
}
