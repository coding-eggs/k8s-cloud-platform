package com.coding.auth.grant;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.jspecify.annotations.Nullable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.web.authentication.AuthenticationConverter;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

/**
 * 会话续期 grant 的认证转换器。
 *
 * <p>仅当 {@code grant_type = {@link SessionRenewalGrantType#VALUE}} 时生效，否则返回 null
 * 交由其他 converter 处理（不影响 authorization_code / refresh_token 等）。
 *
 * <p>构建未认证的 {@link SessionRenewalAuthenticationToken}：
 * <ul>
 *   <li>clientPrincipal —— 已认证的客户端（见 {@link #resolveClientPrincipal}）；</li>
 *   <li>endUserPrincipal —— 取自请求携带的有效会话中的用户身份（根凭证）。</li>
 * </ul>
 */
public class SessionRenewalAuthenticationConverter implements AuthenticationConverter {

    private final RegisteredClientRepository registeredClientRepository;

    public SessionRenewalAuthenticationConverter(RegisteredClientRepository registeredClientRepository) {
        Assert.notNull(registeredClientRepository, "registeredClientRepository cannot be null");
        this.registeredClientRepository = registeredClientRepository;
    }

    @Nullable
    @Override
    public Authentication convert(HttpServletRequest request) {
        // grant_type (REQUIRED)：非本 grant 直接放行
        String grantType = request.getParameter(OAuth2ParameterNames.GRANT_TYPE);
        if (!SessionRenewalGrantType.VALUE.equals(grantType)) {
            return null;
        }

        Authentication clientPrincipal = resolveClientPrincipal(request);

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

        // 目标租户上下文（可选参数）。空/空白 → null，签发 base token 不带租户；
        // 非法租户的校验放在签发 customizer 内（需要查库确认成员资格）。
        String tenantId = request.getParameter("tenant_id");
        return new SessionRenewalAuthenticationToken(sessionId, endUserPrincipal, clientPrincipal,
                (tenantId == null || tenantId.isBlank()) ? null : tenantId);
    }

    /**
     * 解析已认证客户端。
     *
     * <p>本 grant 的调用方是 PKCE 公共客户端（client_authentication_method=none）：SAS 的
     * {@code OAuth2ClientAuthenticationFilter} 对「grant_type=session-renewal、无 client_secret、
     * 无 code_verifier」的请求不会做任何客户端认证（其 PublicClientAuthenticationConverter 仅匹配
     * authorization_code + code + code_verifier），因此 SecurityContext 里通常是端用户登录认证
     * （或经会话授权后的其它非客户端认证），拿不到已认证的
     * {@link OAuth2ClientAuthenticationToken}。
     *
     * <p>按本 grant 的设计（会话 Cookie 即根凭证），此处直接依据请求的 {@code client_id} 从注册库
     * 构造已认证的公共客户端 principal。客户端是否被授权本 grant 类型仍由 provider 统一校验；
     * 若上游链路某天确实完成了标准客户端认证（如机密客户端带 secret 调用），优先沿用它。
     */
    private Authentication resolveClientPrincipal(HttpServletRequest request) {
        Authentication current = SecurityContextHolder.getContext().getAuthentication();
        if (current instanceof OAuth2ClientAuthenticationToken clientAuth && clientAuth.isAuthenticated()) {
            return clientAuth;
        }

        String clientId = request.getParameter(OAuth2ParameterNames.CLIENT_ID);
        if (!StringUtils.hasText(clientId)) {
            throw new OAuth2AuthenticationException(new OAuth2Error(OAuth2ErrorCodes.INVALID_CLIENT));
        }
        RegisteredClient registeredClient = this.registeredClientRepository.findByClientId(clientId);
        if (registeredClient == null
                || !registeredClient.getClientAuthenticationMethods().contains(ClientAuthenticationMethod.NONE)) {
            throw new OAuth2AuthenticationException(new OAuth2Error(OAuth2ErrorCodes.INVALID_CLIENT));
        }
        // 该构造器即「认证成功」形态（见 OAuth2ClientAuthenticationToken 第二构造器 setAuthenticated(true)）
        return new OAuth2ClientAuthenticationToken(registeredClient, ClientAuthenticationMethod.NONE, null);
    }
}
