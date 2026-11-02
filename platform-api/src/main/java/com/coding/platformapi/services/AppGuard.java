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

    static final String LEGACY_READ_ONLY = "旧模型应用只能删除，请删除后按新方式重建";

    /**
     * 写入口用：在 {@link #require} 之上拒绝旧模型应用（画布时代建的，{@code spec_json} 非空）。
     *
     * <p>旧应用的对象台账按旧修订号记账，新发布的 apply 会按同一个修订号 {@code deleteByAppRevision}
     * 覆写台账，把画布建的对象变成孤儿（删除应用时再也 prune 不到）。所以旧应用只读，只能删除。
     */
    static PlatformApplication requireWritable(PlatformApplicationMapper apps, String appId, String tenantId) {
        PlatformApplication row = require(apps, appId, tenantId);
        if (isLegacy(row)) {
            throw new CloudPlatformException(EnumResponseType.ERROR, LEGACY_READ_ONLY);
        }
        return row;
    }

    static boolean isLegacy(PlatformApplication row) {
        return row.getSpecJson() != null && !row.getSpecJson().isBlank();
    }
}
