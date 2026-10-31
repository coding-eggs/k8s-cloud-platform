package com.coding.k8sserver.components;

import com.coding.common.components.jwt.PlatformJwtAuthenticationConverter;
import com.coding.common.exception.CloudPlatformException;
import com.coding.data.mapper.auth.PlatformTenantMapper;
import com.coding.data.mapper.k8s.K8sClusterMapper;
import com.coding.data.models.k8s.K8sCluster;
import com.coding.k8sserver.components.ResourceAccessResolver.AccessContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 边界解析的三条不变式（2026-10-31 收窄后）：
 * <ol>
 *   <li><b>平台身份优先</b>：token 有 {@code PLATFORM_SCOPE} 就用 admin client，<b>戴不戴租户帽子都一样</b>
 *       —— 否则租户把角色模板改坏会把管理员一起锁在外面；</li>
 *   <li><b>平台路径不查分配表</b>：能不能碰一个 ns 由身份决定，不由那张归属表决定；</li>
 *   <li><b>租户路径一点不能松</b>：没有 {@code PLATFORM_SCOPE} 就必须过分配表三元组。</li>
 * </ol>
 */
class ResourceAccessResolverTest {

    private final PlatformTenantMapper tenants = mock(PlatformTenantMapper.class);
    private final K8sClusterMapper clusters = mock(K8sClusterMapper.class);
    private final ResourceAccessResolver resolver =
            new ResourceAccessResolver(tenants, clusters, JsonMapper.builder().build());

    @BeforeEach
    void aRegisteredCluster() {
        // 平台路径只校验「集群已登记」，不碰分配表
        when(clusters.selectByPrimaryKey(anyString())).thenReturn(new K8sCluster());
    }

    // ---------- 造身份 ----------

    /** 平台侧 token（有 PLATFORM_SCOPE）；hat 为 null = 平台视图 */
    private JwtAuthenticationToken platformToken(String hatTenantId) {
        return token(hatTenantId, true);
    }

    /** 纯租户 token（无 PLATFORM_SCOPE） */
    private JwtAuthenticationToken tenantToken(String tenantId) {
        return token(tenantId, false);
    }

    private JwtAuthenticationToken token(String tenantId, boolean platformSide) {
        Map<String, Object> data = tenantId == null
                ? Map.of("platformRoles", List.of("admin"))
                : Map.of("tenantInfo", Map.of("tenantId", tenantId), "platformRoles", List.of());
        Jwt jwt = Jwt.withTokenValue("t")
                .header("alg", "none")
                .claim("data", data)
                .build();
        return platformSide
                ? new JwtAuthenticationToken(jwt, List.of(
                        new SimpleGrantedAuthority(PlatformJwtAuthenticationConverter.PLATFORM_SCOPE_AUTHORITY)))
                : new JwtAuthenticationToken(jwt, List.of());
    }

    // ---------- ① 平台身份优先 ----------

    @Test
    void aPlatformAdminWearingATenantHatStillGetsTheAdminClient() {
        // ⭐ 这是与旧语义的反转点：旧代码「即使 token 同时带 admin authority，也以 token 租户为准」
        //    → 管理员切到租户视图后用的是租户 SA，租户把 RoleBinding 改坏时管理员也被锁在外面。
        when(tenants.hasNamespaceAccess(anyString(), anyString(), anyString())).thenReturn(false);

        AccessContext ctx = resolver.resolveNamespacedAccess(platformToken("t1"), null, "c1", "ns-a");

        assertThat(ctx.adminMode()).as("平台身份优先：戴帽子也必须走 admin client").isTrue();
        assertThat(ctx.tenantId()).as("帽子仍是归属标注").isEqualTo("t1");
        verify(tenants, never()).hasNamespaceAccess(anyString(), anyString(), anyString());
    }

    @Test
    void aPlatformAdminWithoutAHatDoesNotNeedAnAllocationRow() {
        // 未分配给任何租户的 ns（kube-system 之类）也必须能管 —— 这正是「代管」的含义
        when(tenants.hasNamespaceAccess(anyString(), anyString(), anyString())).thenReturn(false);

        AccessContext ctx = resolver.resolveNamespacedAccess(platformToken(null), "t9", "c1", "kube-system");

        assertThat(ctx.adminMode()).isTrue();
        assertThat(ctx.tenantId()).isEqualTo("t9");
        verify(tenants, never()).hasNamespaceAccess(anyString(), anyString(), anyString());
    }

    @Test
    void theNamespaceOwnerIsFilledInWhenNobodySaysWhoItIsFor() {
        when(tenants.selectTenantIdByClusterAndNs("c1", "ns-a")).thenReturn("t1");

        AccessContext ctx = resolver.resolveNamespacedAccess(platformToken(null), null, "c1", "ns-a");

        assertThat(ctx.adminMode()).isTrue();
        assertThat(ctx.tenantId()).as("没人说替谁做时就按归属表反查").isEqualTo("t1");
    }

    @Test
    void anUnownedNamespaceIsLegitimateForAPlatformAdmin() {
        when(tenants.selectTenantIdByClusterAndNs("c1", "kube-system")).thenReturn(null);

        AccessContext ctx = resolver.resolveNamespacedAccess(platformToken(null), null, "c1", "kube-system");

        assertThat(ctx.adminMode()).isTrue();
        assertThat(ctx.tenantId()).as("没有归属是合法状态，不是错误").isNull();
    }

    // ---------- ② 租户路径一点不能松 ----------

    @Test
    void aTenantStillNeedsAnAllocationRow() {
        when(tenants.hasNamespaceAccess("t1", "c1", "ns-a")).thenReturn(false);
        JwtAuthenticationToken auth = tenantToken("t1");

        assertThatThrownBy(() -> resolver.resolveNamespacedAccess(auth, null, "c1", "ns-a"))
                .isInstanceOf(CloudPlatformException.class);
    }

    @Test
    void aTenantWithAnAllocationUsesTheTenantClient() {
        when(tenants.hasNamespaceAccess("t1", "c1", "ns-a")).thenReturn(true);

        AccessContext ctx = resolver.resolveNamespacedAccess(tenantToken("t1"), null, "c1", "ns-a");

        assertThat(ctx.adminMode()).isFalse();
        assertThat(ctx.tenantId()).isEqualTo("t1");
    }

    @Test
    void aTenantCannotClaimToBeSomeoneElse() {
        JwtAuthenticationToken auth = tenantToken("t1");

        assertThatThrownBy(() -> resolver.resolveNamespacedAccess(auth, "t2", "c1", "ns-a"))
                .isInstanceOf(CloudPlatformException.class);
    }

    @Test
    void aTokenWithNeitherPlatformSideNorTenantIsRejected() {
        JwtAuthenticationToken auth = tenantToken(null);

        assertThatThrownBy(() -> resolver.resolveNamespacedAccess(auth, "t1", "c1", "ns-a"))
                .isInstanceOf(CloudPlatformException.class);
    }
}
