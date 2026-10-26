package com.coding.common.models.middleware;

import lombok.Data;

/**
 * platform-api → k8s-server：修改实例的鉴权口令（目前只有 Milvus 用）。
 *
 * <p>**旧口令不回传**：k8s-server 自己从鉴权 Secret 里读，平台不接触明文。
 */
@Data
public class MiddlewarePasswordRequestDTO {

    private String clusterId;

    private String code;

    private String namespace;

    private String name;

    private String instanceId;

    /** 新口令（平台生成或用户填写） */
    private String newPassword;

    /** 管理员代管轨的租户 */
    private String tenantId;
}
