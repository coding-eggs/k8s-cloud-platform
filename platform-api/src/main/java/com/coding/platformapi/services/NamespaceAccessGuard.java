package com.coding.platformapi.services;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.data.mapper.auth.PlatformTenantNamespaceMapper;
import com.coding.platformapi.security.AuthContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 「这个租户能不能碰这个命名空间」—— **唯一一处实现**。
 *
 * <p>为什么单列一个类而不是各自写一遍：这是一条**安全规则**，重复实现意味着改一处漏一处；
 * 而它又太窄（两个依赖、一个判断），塞进任何一个业务 service 都会让另一个反向依赖它。
 *
 * <p><b>平台管理员（无租户帽）不过滤</b> —— 与 {@code PersistentVolumeService} 同一条判断。
 */
@Service
@RequiredArgsConstructor
public class NamespaceAccessGuard {

    private final PlatformTenantNamespaceMapper allocationMapper;
    private final AuthContext auth;

    /**
     * @throws CloudPlatformException 带租户帽、但该租户在 (clusterId, namespace) 上没有分配行
     */
    public void assertAccessible(String clusterId, String namespace) {
        String tenantId = auth.hatTenantId();
        if (tenantId == null) {
            return;
        }
        if (allocationMapper.selectByTenantClusterNs(tenantId, clusterId, namespace) == null) {
            throw new CloudPlatformException(EnumResponseType.NAMESPACE_NOT_ACCESSIBLE,
                    "无权访问该命名空间：" + namespace);
        }
    }
}
