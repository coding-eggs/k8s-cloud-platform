package com.coding.platformapi.services;

import com.coding.common.exception.CloudPlatformException;
import com.coding.data.mapper.auth.PlatformTenantNamespaceMapper;
import com.coding.data.models.auth.PlatformTenantNamespace;
import com.coding.platformapi.security.AuthContext;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 「这个租户能不能碰这个命名空间」—— 全平台**唯一一处**实现（spec §5.2：零新机制，
 * 走既有的分配表）。
 *
 * <p>它被流水线的「定义」与「运行」两族端点共用，所以规则本身在这里钉一次就够 ——
 * 上层只要断言「问过了」。
 */
class NamespaceAccessGuardTest {

    private final PlatformTenantNamespaceMapper allocationMapper = mock(PlatformTenantNamespaceMapper.class);
    private final AuthContext auth = mock(AuthContext.class);

    private final NamespaceAccessGuard guard = new NamespaceAccessGuard(allocationMapper, auth);

    @Test
    void aTenantHoldingTheAllocationPasses() {
        when(auth.hatTenantId()).thenReturn("t1");
        when(allocationMapper.selectByTenantClusterNs("t1", "c1", "ns1"))
                .thenReturn(new PlatformTenantNamespace());

        assertThatCode(() -> guard.assertAccessible("c1", "ns1")).doesNotThrowAnyException();
    }

    @Test
    void aTenantWithoutTheAllocationIsRefused() {
        when(auth.hatTenantId()).thenReturn("t1");
        when(allocationMapper.selectByTenantClusterNs("t1", "c1", "ns1")).thenReturn(null);

        assertThatThrownBy(() -> guard.assertAccessible("c1", "ns1"))
                .isInstanceOf(CloudPlatformException.class);
    }

    @Test
    void platformAdminWithoutAHatDoesNotEvenQuery() {
        // 无租户帽 = 平台管理员代操作 —— 与 PersistentVolumeService 同一条判断
        when(auth.hatTenantId()).thenReturn(null);

        assertThatCode(() -> guard.assertAccessible("c1", "ns1")).doesNotThrowAnyException();
        verify(allocationMapper, never()).selectByTenantClusterNs(any(), anyString(), anyString());
    }

    @Test
    void aPlatformAdminWearingAHatIsStillNotBoundByTheAllocationTable() {
        // ⭐ 2026-10-31 平台身份优先：帽子只决定「替谁做」的标注，不决定权限。
        //    否则管理员切到租户视图后会被归属表挡住 —— 而修那张表恰恰是他的工作。
        when(auth.isPlatformSide()).thenReturn(true);
        when(auth.hatTenantId()).thenReturn("t1");
        when(allocationMapper.selectByTenantClusterNs("t1", "c1", "ns1")).thenReturn(null);

        assertThatCode(() -> guard.assertAccessible("c1", "ns1")).doesNotThrowAnyException();
        verify(allocationMapper, never()).selectByTenantClusterNs(any(), anyString(), anyString());
    }
}
