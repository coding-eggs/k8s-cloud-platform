package com.coding.common.models.k8s.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "生命周期钩子处理器（无 tcpSocket）")
public class HandlerDTO {

    @Schema(description = "Exec 动作")
    private ExecActionDTO exec;

    @Schema(description = "HTTP GET 动作")
    private HttpGetActionDTO httpGet;

    @Schema(description = "Sleep 动作")
    private SleepActionDTO sleep;

}
