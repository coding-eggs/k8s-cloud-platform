package com.coding.common.models.k8s.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "卷键值映射项")
public class KeyToPathDTO {

    @Schema(description = "键名")
    private String key;

    @Schema(description = "挂载路径")
    private String path;

    @Schema(description = "文件权限（八进制整数，如 420=0644）")
    private Integer mode;

}
