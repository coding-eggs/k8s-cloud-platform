package com.coding.data.models.system;

import lombok.Data;

@Data
public class UserTenantInfo {

    private String userId;

    private String tenantId;

    private String tenantName;

    private String clusterId;

    private String serviceAccount;

}
