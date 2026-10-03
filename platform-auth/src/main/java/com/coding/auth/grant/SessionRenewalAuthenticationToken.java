package com.coding.auth.grant;


import lombok.Getter;
import org.jspecify.annotations.Nullable;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationGrantAuthenticationToken;

import java.util.Collections;

/**
 * 会话续期 grant 的载体 token（未认证）。
 *
 * <p>由 {@link SessionRenewalAuthenticationConverter} 从请求中构建：
 * <ul>
 *   <li>{@code clientPrincipal} —— 已认证的客户端（来自 SecurityContext，基类构造器要求非空）；</li>
 *   <li>{@code endUserPrincipal} —— 有效会话中的用户身份（决定 token 的 sub / data claim），可能为空。</li>
 * </ul>
 * provider 校验通过后返回的是另一个已认证的 {@code OAuth2AccessTokenAuthenticationToken}，
 * 本载体保持未认证状态即可。
 */
@Getter
public class SessionRenewalAuthenticationToken extends OAuth2AuthorizationGrantAuthenticationToken {

    /** 会话 ID（用于日志与滑动续期关联）；无有效会话时为空 */
    private final String sessionId;

    /** 会话中已认证的用户身份；空表示无有效登录会话 */
    @Nullable
    private final Authentication endUserPrincipal;

    /** 目标租户上下文 id；可空（空=base token 无租户） */
    @Nullable
    private final String tenantId;

    public SessionRenewalAuthenticationToken(String sessionId,
                                             @Nullable Authentication endUserPrincipal,
                                             Authentication clientPrincipal,
                                             @Nullable String tenantId) {
        super(SessionRenewalGrantType.INSTANCE, clientPrincipal, Collections.emptyMap());
        this.sessionId = sessionId;
        this.endUserPrincipal = endUserPrincipal;
        this.tenantId = (tenantId == null || tenantId.isBlank()) ? null : tenantId;
    }
}
