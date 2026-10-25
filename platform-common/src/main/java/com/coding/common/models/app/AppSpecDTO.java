package com.coding.common.models.app;

import lombok.Data;

import java.util.List;
import java.util.Map;

/** 应用的图定义（真相源）。画布只是它的可视化编辑器。 */
@Data
public class AppSpecDTO {

    private String namespace;

    /** 应用级设置（默认资源等），v1 可空。 */
    private Map<String, Object> settings;

    private List<AppNodeDTO> nodes;

    private List<AppEdgeDTO> edges;
}
