package com.coding.platformapi.models;

import lombok.Data;

/**
 * {@code POST /middleware/instance/password} 的请求体：改实例的鉴权口令（目前只有 Milvus）。
 *
 * <p>旧口令不需要用户提供 —— k8s-server 从鉴权 Secret 里读。新口令留空则平台生成。
 */
@Data
public class MiddlewarePasswordChangeRequest {

    private String instanceId;

    /** 新口令；留空 = 平台生成（32 位随机，存进 Secret，不回显） */
    private String newPassword;

    /** 仅管理员代管轨使用；租户轨以 token 为准 */
    private String tenantId;
}
