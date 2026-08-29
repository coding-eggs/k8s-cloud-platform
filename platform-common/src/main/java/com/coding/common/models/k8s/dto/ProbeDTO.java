package com.coding.common.models.k8s.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "探针定义")
public class ProbeDTO {

    @Schema(description = "HTTP GET 动作")
    private HttpGetActionDTO httpGet;

    @Schema(description = "TCP Socket 动作")
    private TCPSocketActionDTO tcpSocket;

    @Schema(description = "Exec 动作")
    private ExecActionDTO exec;

    @Schema(description = "首次探测延迟（秒）")
    private Integer initialDelaySeconds;

    @Schema(description = "探测周期（秒）")
    private Integer periodSeconds;

    @Schema(description = "探测超时（秒）")
    private Integer timeoutSeconds;

    @Schema(description = "成功阈值")
    private Integer successThreshold;

    @Schema(description = "失败阈值")
    private Integer failureThreshold;

}
