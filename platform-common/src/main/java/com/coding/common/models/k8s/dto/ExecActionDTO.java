package com.coding.common.models.k8s.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Data
@Schema(description = "Exec 动作")
public class ExecActionDTO {

    @Schema(description = "执行的命令列表")
    private List<String> command;

}
