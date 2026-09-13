package com.coding.k8sserver.components;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.data.mapper.auth.PlatformTenantMapper;
import com.coding.data.mapper.k8s.K8sClusterMapper;
import com.coding.data.models.system.TokenUserInfo;
import com.coding.data.models.system.UserTenantInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * 资源访问统一解析（命名空间域 /resources/** + 集群域边界）。
 * <p>
 * 双模身份：
 * <ul>
 *   <li>租户 token（带 tenantInfo claim）：tenantId 以签名字段为唯一来源，客户端不可伪造；
 *       显式参数若传入必须与 token 一致，否则 TENANT_MISMATCH</li>
 *   <li>admin token（无租户 claim）：tenantId 必须显式传参——代操作"替谁说话"</li>
 * </ul>
 * 边界校验两种模式统一且不可跳过：
 * <ul>
 *   <li>命名空间域：分配表三元组 (tenantId, clusterId, namespace) 必须存在</li>
 *   <li>集群域：clusterId 必须是平台已登记的集群</li>
 * </ul>
 * client 选择由 {@link AccessContext#adminMode()} 决定：租户→tenant client（最小权限，K8s RBAC 第二道闸），
 * admin→admin client。
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
            //admin 模式：显式参数是唯一身份来源
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
    public void assertNamespacedAccess(String tenantId, String clusterId, String namespace) {
        if (!StringUtils.hasText(tenantId) || !StringUtils.hasText(clusterId) || !StringUtils.hasText(namespace)) {
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
