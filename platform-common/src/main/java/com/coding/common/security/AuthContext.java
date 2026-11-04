package com.coding.platformapi.security;

import com.coding.data.models.system.TokenUserInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * 当前请求的 token 身份视图：从 SecurityContext 的 JWT {@code data} claim（{@link TokenUserInfo}）
 * 解出用户信息（含 tenantInfo）。与 k8s-server {@code ResourceAccessResolver#tokenTenantIdFrom} 同一
 * claim 解析方式（JsonMapper.convertValue），区别是这里返回完整 TokenUserInfo 且解析失败返回 null
 * （平台管理端存在合法的无租户 claim 的 admin/base token，调用方自行判定）。
 * <p>
 * dataKey 与 {@code PlatformJwtAuthenticationConverter} 用同一配置项（{@code jwt.data-key}，默认 data）；
 * {@code JwtProperties} 组件在 platform-api 未启用（jwt.enabled=false），故按 {@code @Value} 取值。
 */
@Slf4j
@Component
public class AuthContext {

    private final JsonMapper jsonMapper;

    private final String dataKey;

    public AuthContext(JsonMapper jsonMapper, @Value("${jwt.data-key:data}") String dataKey) {
        this.jsonMapper = jsonMapper;
        this.dataKey = dataKey;
    }

    /**
     * 当前 token 的用户信息；未认证 / principal 非 Jwt / 无或非法 data claim → null
     */
    public TokenUserInfo current() {
        return current(SecurityContextHolder.getContext().getAuthentication());
    }

    /** 显式 Authentication 版（测试与非 REST 线程可用） */
    public TokenUserInfo current(Authentication auth) {
        if (auth == null || !(auth.getPrincipal() instanceof Jwt jwt)) {
            return null;
        }
        try {
            return jsonMapper.convertValue(jwt.getClaim(dataKey), new TypeReference<>() {
            });
        } catch (Exception e) {
            log.debug("token 无有效 data claim：{}", e.getMessage());
            return null;
        }
    }

    /**
     * 当前 token 的租户帽（tenantInfo.tenantId）；base token（管理员代管态）/ 未认证 → null。
     */
    public String hatTenantId() {
        TokenUserInfo info = current();
        return info != null && info.getTenantInfo() != null ? info.getTenantInfo().getTenantId() : null;
    }

    /**
     * 是不是平台侧身份（{@code data.platformRoles} 非空）。
     *
     * <p>与 k8s-server 的 {@code PLATFORM_SCOPE} 权限同源（签发期都按 {@code platform_role.scope='PLATFORM'}
     * 过滤），是「平台能不能碰这个资源」的唯一判据 —— **与有没有租户帽无关**：
     * 管理员戴帽只是"替谁做"的标注，不该因此降级成租户权限（2026-10-31）。
     */
    public boolean isPlatformSide() {
        TokenUserInfo info = current();
        return info != null && info.getPlatformRoles() != null && !info.getPlatformRoles().isEmpty();
    }

    /**
     * 这次调用**以哪个租户的身份**去问 k8s-server（调有租户维度的资源时必须给一个）。
     *
     * <p>两条轨，与前端 {@code api/index.ts} 那条约定（"tenantId 为生效租户 id"）同一条规矩：
     * <ul>
     *   <li><b>自管轨</b>（token 带 tenantInfo）→ **以 token 为准**。请求里传别人的，k8s-server 会判
     *       {@code TENANT_MISMATCH}；这里干脆忽略请求值，不给它机会。</li>
     *   <li><b>代管轨</b>（平台侧身份，{@link #hatTenantId()} 为 null）→ 用**请求里显式指定的**
     *       那个租户（前端顶栏级联里选的那个）。没传就保持 null —— k8s-server 会按
     *       (cluster, namespace) 反查归属表补上（2026-10-31 起不再要求显式传参），
     *       这里**不猜一个租户**。</li>
     * </ul>
     */
    public String effectiveTenantId(String requestedTenantId) {
        String hat = hatTenantId();
        return hat != null ? hat : requestedTenantId;
    }
}
