package com.coding.k8sserver.components;

import com.coding.common.components.jwt.PlatformJwtAuthenticationConverter;
import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.data.mapper.auth.PlatformTenantMapper;
import com.coding.data.mapper.k8s.K8sClusterMapper;
import com.coding.data.models.system.TokenUserInfo;
import com.coding.data.models.system.UserTenantInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * 资源访问统一解析（命名空间域 + 集群域边界）。
 * <p>
 * 双模身份：
 * <ul>
 *   <li>租户 token（带 tenantInfo claim）：tenantId 以签名字段为唯一来源，客户端不可伪造；
 *       显式参数若传入必须与 token 一致，否则 TENANT_MISMATCH</li>
 *   <li>admin token（无租户 claim）：tenantId 必须显式传参——代操作"替谁说话"；
 *       调用方还必须是<b>平台侧身份</b>（见 {@link #assertPlatformSide}）</li>
 * </ul>
 * 边界校验两种模式统一且不可跳过：
 * <ul>
 *   <li>命名空间域：分配表三元组 (tenantId, clusterId, namespace) 必须存在</li>
 *   <li>集群域：clusterId 必须是平台已登记的集群</li>
 * </ul>
 * client 选择由 {@link AccessContext#adminMode()} 决定：租户→tenant client（最小权限，K8s RBAC 第二道闸），
 * admin→admin client。
 *
 * <p><b>本类与 {@code BoundaryAuthorizationManager} 的分工</b>：后者只回答"这个端点能不能被这个调用方碰"
 * （读 controller 的 {@link AccessBoundary} 声明，PLATFORM 端点要求 PLATFORM_SCOPE）；
 * 本类回答"这个调用方能碰哪些 namespace、该用谁的凭据"。授权（细粒度"能不能做这件事"）不在这里，
 * 在 platform-api 的权限表。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ResourceAccessResolver {

    private final PlatformTenantMapper platformTenantMapper;

    private final K8sClusterMapper k8sClusterMapper;

    private final JsonMapper jsonMapper;

    /**解析结果：生效租户 + 是否 admin 模式（决定 client 选择） */
    public record AccessContext(String tenantId, boolean adminMode) {
    }

    /**
     * 命名空间域访问解析（REST）：身份取自当前 SecurityContext + 分配表边界校验，返回生效上下文
     */
    public AccessContext resolveNamespacedAccess(String explicitTenantId, String clusterId, String namespace) {
        return resolveNamespacedAccess(SecurityContextHolder.getContext().getAuthentication(),
                explicitTenantId, clusterId, namespace);
    }

    /**
     * 命名空间域访问解析（显式身份）：WS 握手跑在 Tomcat worker 线程、thread-local SecurityContext 为空，
     * 由 {@code HandshakeInterceptor} 在握手期捕获的 Authentication 显式传入。逻辑与 REST 版完全一致。
     */
    public AccessContext resolveNamespacedAccess(Authentication auth, String explicitTenantId, String clusterId, String namespace) {
        String tokenTenantId = tokenTenantIdFrom(auth);
        String tenantId;
        boolean adminMode;
        if (tokenTenantId != null) {
            //租户模式：token 为权威身份源（即使 token 同时带 admin authority，也以 token 租户为准）
            if (StringUtils.hasText(explicitTenantId) && !tokenTenantId.equals(explicitTenantId)) {
                log.warn("Tenant mismatch: token={}, request={}", tokenTenantId, explicitTenantId);
                throw new CloudPlatformException(EnumResponseType.TENANT_MISMATCH);
            }
            tenantId = tokenTenantId;
            adminMode = false;
        } else {
            //admin 模式：显式参数是唯一身份来源 —— 但前提是调用方确实是平台侧身份，否则任何
            //"无 tenantInfo 的 token"都能自称代任意租户操作并拿到 admin client（cluster-admin 凭据）。
            assertPlatformSide(auth);
            if (!StringUtils.hasText(explicitTenantId)) {
                throw new CloudPlatformException(EnumResponseType.ERROR, "管理员代操作需显式指定 tenantId");
            }
            tenantId = explicitTenantId;
            adminMode = true;
        }
        assertNamespacedAccess(tenantId, clusterId, namespace);
        return new AccessContext(tenantId, adminMode);
    }

    /**命名空间边界：(tenantId, clusterId, namespace) 三元组必须在分配表中存在 */
    public void assertNamespacedAccess(String tenantId, String clusterId, String namespace) {        if (!StringUtils.hasText(tenantId) || !StringUtils.hasText(clusterId) || !StringUtils.hasText(namespace)) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "缺少租户/集群/命名空间参数");
        }
        if (!platformTenantMapper.hasNamespaceAccess(tenantId, clusterId, namespace)) {
            log.warn("Blocked: tenant {} has no allocation for ns {} in cluster {}", tenantId, namespace, clusterId);
            throw new CloudPlatformException(EnumResponseType.NAMESPACE_NOT_ACCESSIBLE);
        }
    }

    /**集群域边界：clusterId 必须是平台已登记的集群 */
    public void assertClusterAccess(String clusterId) {
        if (!StringUtils.hasText(clusterId) || k8sClusterMapper.selectByPrimaryKey(clusterId) == null) {
            throw new CloudPlatformException(EnumResponseType.CLUSTER_NOT_EXIST);
        }
    }

    /**
     * 平台级命名空间访问解析：<b>不做分配表校验</b>，只校验集群已登记，返回无租户身份的 admin 模式。
     * <p>
     * 专供命名空间自身的约束资源（ResourceQuota / LimitRange）——这类对象由平台在「尚未分配给任何租户」
     * 的命名空间上创建，走 {@link #resolveNamespacedAccess} 的租户边界会被分配表直接拒。
     * <p>
     * <b>谁能进入此模式</b>：调用方必须是平台侧身份（{@link #assertPlatformSide}）；对应的
     * {@code ResourceQuotaController} / {@code LimitRangeController} 还声明了
     * {@link AccessBoundary#PLATFORM}，由 {@code BoundaryAuthorizationManager} 在过滤器链上再要求一次
     * {@code PLATFORM_SCOPE}。两处是同一条判据的两个位置（前者与 HTTP 入口无关，后者覆盖整个端点），
     * 有意保留 —— 历史上前者完全缺失，只靠"路径挂在 {@code /admin/**} 下"隐式保证。
     */
    public AccessContext resolvePlatformNamespacedAccess(String clusterId) {
        assertPlatformSide(SecurityContextHolder.getContext().getAuthentication());
        assertClusterAccess(clusterId);
        return new AccessContext(null, true);
    }

    /**
     * 要求调用方是平台侧身份：token 的 {@code data.platformRoles} 非空（签发期已按
     * {@code platform_role.scope='PLATFORM'} 过滤，所以租户成员恒为空）。
     * <p>
     * 这是"谁能拿到 admin client"的唯一守门点，与端点声明无关 —— 因此即使在过滤器链之外
     * （WS worker 线程、内部调用）也成立。
     */
    private void assertPlatformSide(Authentication auth) {
        if (auth == null || !auth.isAuthenticated()) {
            throw new CloudPlatformException(EnumResponseType.USER_UN_LOGIN);
        }
        boolean platformSide = auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(PlatformJwtAuthenticationConverter.PLATFORM_SCOPE_AUTHORITY::equals);
        if (!platformSide) {
            log.warn("Blocked: user={} 无平台侧身份（data.platformRoles 为空），不得进入 admin 代操作模式",
                    auth.getName());
            throw new CloudPlatformException(EnumResponseType.NON_AUTH_ENTRY_POINT);
        }
    }

    /**从给定 Authentication 的 JWT data claim 提取租户身份；admin token / 无 claim → null（走 admin 模式）。
     * auth 为 null（如 WS worker 线程未捕获到握手身份）→ USER_UN_LOGIN，而非 NPE。 */
    private String tokenTenantIdFrom(Authentication auth) {
        if (auth == null || !(auth.getPrincipal() instanceof Jwt jwt)) {
            throw new CloudPlatformException(EnumResponseType.USER_UN_LOGIN);
        }
        try {
            TokenUserInfo userInfo = jsonMapper.convertValue(jwt.getClaim("data"), new TypeReference<>() {});
            UserTenantInfo tenantInfo = userInfo != null ? userInfo.getTenantInfo() : null;
            return tenantInfo != null && StringUtils.hasText(tenantInfo.getTenantId())
                    ? tenantInfo.getTenantId() : null;
        } catch (Exception e) {
            log.debug("token 无租户 claim，按 admin 模式处理: {}", e.getMessage());
            return null;
        }
    }

}
