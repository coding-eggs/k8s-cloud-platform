package com.coding.common.models.k8s.dto;

import lombok.Data;

import java.util.List;

/** 渲染预览：清单 + 对象列表 + 检查结果。 */
@Data
public class ChartPreviewResultDTO {
    private String manifest;
    private List<ChartObjectDTO> objects;
    private List<ChartViolationDTO> violations;
}
