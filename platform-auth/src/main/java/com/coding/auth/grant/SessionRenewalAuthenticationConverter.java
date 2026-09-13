package com.coding.auth.grant;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.lang.Nullable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.web.authentication.AuthenticationConverter;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;

/**
 * 会话续期 grant 的认证转换器。
 *
 * <p>仅当 {@code grant_type = {@link SessionRenewalGrantType#VALUE}} 时生效，否则返回 null
 * 交由其他 converter 处理（不影响 authorization_code / refresh_token 等）。
 *
 * <p>构建未认证的 {@link SessionRenewalAuthenticationToken}：
 * <ul>
 *   <li>clientPrincipal —— 取自 SecurityContext（token endpoint 已先完成客户端认证）；</li>
 *   <li>endUserPrincipal —— 取自请求携带的有效会话中的用户身份（根凭证）。</li>
 * </ul>
 */
public class SessionRenewalAuthenticationConverter implements AuthenticationConverter {

    @Nullable
    @Override
    public Authentication convert(HttpServletRequest request) {
        // grant_type (REQUIRED)：非本 grant 直接放行
        String grantType = request.getParameter(OAuth2ParameterNames.GRANT_TYPE);
        if (!SessionRenewalGrantType.VALUE.equals(grantType)) {
            return null;
        }

        // 客户端身份（token endpoint 已先完成客户端认证）
        Authentication clientPrincipal = SecurityContextHolder.getContext().getAuthentication();
        if (clientPrincipal == null) {
            throw new OAuth2AuthenticationException(new OAuth2Error(OAuth2ErrorCodes.INVALID_CLIENT));
        }

        // 从有效会话中读取用户身份（根凭证）。无会话 / 会话内无登录态时 endUserPrincipal 为空，
        // 由 provider 统一以 INVALID_GRANT 拒绝。
        String sessionId = null;
        Authentication endUserPrincipal = null;
        HttpSession session = request.getSession(false);
        if (session != null) {
            sessionId = session.getId();
            Object contextAttr = session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
            if (contextAttr instanceof SecurityContext securityContext && securityContext.getAuthentication() != null) {
                endUserPrincipal = securityContext.getAuthentication();
            }
        }

        return new SessionRenewalAuthenticationToken(sessionId, endUserPrincipal, clientPrincipal);
    }
}
