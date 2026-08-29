package com.coding.common.models.k8s.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "容器生命周期钩子")
public class LifecycleDTO {

    @Schema(description = "启动后执行")
    private HandlerDTO postStart;

    @Schema(description = "停止前执行")
    private HandlerDTO preStop;

}
