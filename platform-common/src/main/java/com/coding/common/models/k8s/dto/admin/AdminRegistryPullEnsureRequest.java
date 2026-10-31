package com.coding.common.models.k8s.dto.admin;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 确保某个命名空间里有一个 registry 拉取凭据（幂等）。
 *
 * <p>{@code dockerConfigJson} 是**已经在 platform-api 拼好**的 docker config
 * （{@code {"auths": {...}}}）—— k8s-server 不做业务判断，只落盘并挂到 {@code default} SA 上。
 */
@Data
@Schema(description = "确保命名空间的 registry 拉取凭据请求（幂等）")
public class AdminRegistryPullEnsureRequest {

    @Schema(description = "集群id", requiredMode = Schema.RequiredMode.REQUIRED)
    private String clusterId;

    @Schema(description = "命名空间", requiredMode = Schema.RequiredMode.REQUIRED)
    private String namespace;

    @Schema(description = "Secret 名（平台固定用 platform-registry-pull）", requiredMode = Schema.RequiredMode.REQUIRED)
    private String secretName;

    /** 明文 docker config json —— 只在内存里过一趟，落盘进 Secret。**不要打日志**。 */
    @Schema(description = "docker config json（明文，仅服务端内部传递）",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String dockerConfigJson;
}
