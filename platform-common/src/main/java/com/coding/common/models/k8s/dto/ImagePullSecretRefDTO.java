package com.coding.common.models.k8s.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "镜像拉取密钥引用")
public class ImagePullSecretRefDTO {

    @Schema(description = "Secret 名称")
    private String name;

}
