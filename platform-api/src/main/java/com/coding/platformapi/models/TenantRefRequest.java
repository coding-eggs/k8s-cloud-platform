package com.coding.platformapi.models;

import lombok.Data;

/** 平台管理员代管态下显式指定租户的请求体（token 带 tenantInfo 时该值被忽略）。 */
@Data
public class TenantRefRequest {
    private String tenantId;
}
