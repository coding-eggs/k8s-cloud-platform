package com.coding.common.models.k8s.dto;

import lombok.Data;

/** 一条静态检查结果：BLOCK 阻断安装，WARN 只提示。 */
@Data
public class ChartViolationDTO {
    private String severity;
    private String type;
    private String object;
    private String message;
}
