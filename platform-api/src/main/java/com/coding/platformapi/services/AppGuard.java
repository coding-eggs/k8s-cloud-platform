package com.coding.platformapi.services;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.data.mapper.system.PlatformApplicationMapper;
import com.coding.data.models.system.PlatformApplication;

/** 应用归属校验。tenantId 非空 = 租户轨，必须等于台账归属；null = 管理员轨（权限码已在上层判过）。 */
final class AppGuard {

    private AppGuard() {
    }

    static PlatformApplication require(PlatformApplicationMapper apps, String appId, String tenantId) {
        PlatformApplication row = appId == null ? null : apps.selectById(appId);
        if (row == null || row.getDeletedAt() != null
                || (tenantId != null && !tenantId.equals(row.getTenantId()))) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "应用不存在或已删除");
        }
        return row;
    }
}
