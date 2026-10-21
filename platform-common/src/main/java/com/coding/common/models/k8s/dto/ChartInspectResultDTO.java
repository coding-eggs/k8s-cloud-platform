package com.coding.common.models.k8s.dto;

import lombok.Data;

/** chart 元信息：digest + 默认 values（上架与表单初始值用）。 */
@Data
public class ChartInspectResultDTO {
    private String digest;
    private String defaultValues;
}
