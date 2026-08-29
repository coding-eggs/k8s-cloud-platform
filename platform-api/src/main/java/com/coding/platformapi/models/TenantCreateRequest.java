package com.coding.platformapi.models;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "创建租户请求")
public class TenantCreateRequest {

    @Schema(description = "租户名称", requiredMode = Schema.RequiredMode.REQUIRED, example = "coding-test")
    private String name;

    @Schema(description = "租户标识（K8s SA 裸名，小写字母/数字/-，≤60 字符）", requiredMode = Schema.RequiredMode.REQUIRED, example = "coding-test")
    private String serviceAccount;

    @Schema(description = "状态：1 启用（默认），0 禁用")
    private Integer status;
}
