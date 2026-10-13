package com.coding.platformapi.models;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 测试连接。**只测已保存的凭据**（spec §5）：不支持「表单里先测再存」——
 * 那会让明文多一条传输路径，且未保存的凭据没有指纹、没有审计目标。
 */
@Data
public class CredentialTestRequest {

    private String id;

    @Schema(description = "GIT 类型必填：用于探测的**完整**仓库 URL（含 .git）。它是本动作的入参，不是连接的属性")
    private String probeUrl;
}
