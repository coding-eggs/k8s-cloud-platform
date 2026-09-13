package com.coding.common.models.k8s.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "Pod 文件选择器")
public class FileKeySelectorDTO {

    private String key;

    private Boolean optional;

    private String path;

    private String volumeName;
}
