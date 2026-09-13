package com.coding.auth.grant;

import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.ClaimAccessor;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2Token;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.OidcScopes;
import org.springframework.security.oauth2.core.oidc.endpoint.OidcParameterNames;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AccessTokenAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.context.AuthorizationServerContextHolder;
import org.springframework.security.oauth2.server.authorization.settings.OAuth2TokenFormat;
import org.springframework.security.oauth2.server.authorization.token.DefaultOAuth2TokenContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenGenerator;
import org.springframework.util.Assert;

import java.security.Principal;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * 会话续期 grant 的认证提供者。
 *
 * <p>校验链（任一失败即抛出标准 OAuth2 错误码，由 token endpoint 的错误处理器统一渲染）：
 * <ol>
 *   <li>客户端已认证且类型正确 —— 否则 {@code invalid_client}；</li>
 *   <li>客户端被授权本 grant 类型 —— 否则 {@code unauthorized_client}；</li>
 *   <li>存在有效且已登录的会话 —— 否则 {@code invalid_grant}（SPA 据此跳转重新登录）。</li>
 * </ol>
 *
 * <p>通过后以会话中的用户身份为 principal 重新签发 access_token（授权 openid 时附带 id_token），
 * 并持久化 authorization。<b>不签发 refresh_token</b> —— 会话 Cookie 即根凭证。
 */
@Slf4j
public class SessionRenewalAuthenticationProvider implements AuthenticationProvider {

    private static final String ERROR_URI = "https://datatracker.ietf.org/doc/html/rfc6749#section-5.2";
    private static final OAuth2TokenType ID_TOKEN_TOKEN_TYPE = new OAuth2TokenType(OidcParameterNames.ID_TOKEN);

    private final OAuth2AuthorizationService authorizationService;
    private final OAuth2TokenGenerator<? extends OAuth2Token> tokenGenerator;

    public SessionRenewalAuthenticationProvider(OAuth2AuthorizationService authorizationService,
                                                OAuth2TokenGenerator<? extends OAuth2Token> tokenGenerator) {
        Assert.notNull(authorizationService, "authorizationService cannot be null");
        Assert.notNull(tokenGenerator, "tokenGenerator cannot be null");
        this.authorizationService = authorizationService;
        this.tokenGenerator = tokenGenerator;
    }

    @Override
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {
        SessionRenewalAuthenticationToken renewalAuthentication = (SessionRenewalAuthenticationToken) authentication;

        // 1. 客户端身份
        OAuth2ClientAuthenticationToken clientPrincipal = getAuthenticatedClientElseThrowInvalidClient(renewalAuthentication);
        RegisteredClient registeredClient = clientPrincipal.getRegisteredClient();
        if (log.isTraceEnabled()) {
            log.trace("session-renewal: retrieved registered client '{}'", registeredClient.getClientId());
        }

        // 2. 客户端必须被授权本 grant 类型
        if (!registeredClient.getAuthorizationGrantTypes().contains(SessionRenewalGrantType.INSTANCE)) {
            log.debug("Invalid request: grant_type '{}' not allowed for client '{}'",
                    SessionRenewalGrantType.VALUE, registeredClient.getClientId());
            throw new OAuth2AuthenticationException(OAuth2ErrorCodes.UNAUTHORIZED_CLIENT);
        }

        // 3. 会话中的用户身份（根凭证）
        Authentication endUserPrincipal = renewalAuthentication.getEndUserPrincipal();
        if (endUserPrincipal == null || !endUserPrincipal.isAuthenticated()) {
            log.info("session-renewal rejected: no valid authenticated session for client '{}'",
                    registeredClient.getClientId());
            throw new OAuth2AuthenticationException(OAuth2ErrorCodes.INVALID_GRANT);
        }

        String principalName = endUserPrincipal.getName();
        Set<String> authorizedScopes = registeredClient.getScopes();

        // 4. token context —— principal 必须是端用户，保证 sub / data claim 正确
        DefaultOAuth2TokenContext.Builder tokenContextBuilder = DefaultOAuth2TokenContext.builder()
                .registeredClient(registeredClient)
                .principal(endUserPrincipal)
                .authorizationServerContext(AuthorizationServerContextHolder.getContext())
                .authorizedScopes(authorizedScopes)
                .authorizationGrantType(SessionRenewalGrantType.INSTANCE)
                .authorizationGrant(renewalAuthentication);

        OAuth2Authorization.Builder authorizationBuilder = OAuth2Authorization.withRegisteredClient(registeredClient)
                .principalName(principalName)
                .authorizedScopes(authorizedScopes)
                .attribute(Principal.class.getName(), endUserPrincipal);

        // 5. access token
        OAuth2TokenContext accessTokenContext = tokenContextBuilder.tokenType(OAuth2TokenType.ACCESS_TOKEN).build();
        OAuth2Token generatedAccessToken = this.tokenGenerator.generate(accessTokenContext);
        if (generatedAccessToken == null) {
            throw new OAuth2AuthenticationException(new OAuth2Error(OAuth2ErrorCodes.SERVER_ERROR,
                    "The token generator failed to generate the access token.", ERROR_URI));
        }
        OAuth2AccessToken accessToken = toAccessToken(authorizationBuilder, generatedAccessToken, accessTokenContext);

        // 6. id_token（仅当授权了 openid）
        Map<String, Object> additionalParameters = Collections.emptyMap();
        if (authorizedScopes.contains(OidcScopes.OPENID)) {
            OAuth2TokenContext idTokenContext = tokenContextBuilder
                    .tokenType(ID_TOKEN_TOKEN_TYPE)
                    .authorization(authorizationBuilder.build())
                    .build();
            OAuth2Token generatedIdToken = this.tokenGenerator.generate(idTokenContext);
            if (!(generatedIdToken instanceof Jwt)) {
                throw new OAuth2AuthenticationException(new OAuth2Error(OAuth2ErrorCodes.SERVER_ERROR,
                        "The token generator failed to generate the ID token.", ERROR_URI));
            }
            OidcIdToken idToken = new OidcIdToken(generatedIdToken.getTokenValue(), generatedIdToken.getIssuedAt(),
                    generatedIdToken.getExpiresAt(), ((Jwt) generatedIdToken).getClaims());
            authorizationBuilder.token(idToken,
                    (metadata) -> metadata.put(OAuth2Authorization.Token.CLAIMS_METADATA_NAME, idToken.getClaims()));
            additionalParameters = new HashMap<>();
            additionalParameters.put(OidcParameterNames.ID_TOKEN, idToken.getTokenValue());
        }

        // 7. 持久化（不签发 refresh_token —— 会话 Cookie 即根凭证）
        OAuth2Authorization authorization = authorizationBuilder.build();
        this.authorizationService.save(authorization);
        log.debug("session-renewal: issued access token for user '{}' / client '{}'",
                principalName, registeredClient.getClientId());

        return new OAuth2AccessTokenAuthenticationToken(registeredClient, clientPrincipal, accessToken,
                null, additionalParameters);
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return SessionRenewalAuthenticationToken.class.isAssignableFrom(authentication);
    }

    /** 复刻 SAS 包内 {@code OAuth2AuthenticationProviderUtils.getAuthenticatedClientElseThrowInvalidClient}（该类为包私有） */
    private static OAuth2ClientAuthenticationToken getAuthenticatedClientElseThrowInvalidClient(Authentication authentication) {
        if (authentication.getPrincipal() instanceof OAuth2ClientAuthenticationToken clientPrincipal
                && clientPrincipal.isAuthenticated()) {
            return clientPrincipal;
        }
        throw new OAuth2AuthenticationException(OAuth2ErrorCodes.INVALID_CLIENT);
    }

    /** 复刻 SAS 包内 {@code OAuth2AuthenticationProviderUtils.accessToken}（去掉与本 grant 无关的 DPoP 分支） */
    private static OAuth2AccessToken toAccessToken(OAuth2Authorization.Builder builder, OAuth2Token token,
                                                   OAuth2TokenContext accessTokenContext) {
        OAuth2AccessToken accessToken = new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER,
                token.getTokenValue(), token.getIssuedAt(), token.getExpiresAt(),
                accessTokenContext.getAuthorizedScopes());
        OAuth2TokenFormat accessTokenFormat = accessTokenContext.getRegisteredClient()
                .getTokenSettings().getAccessTokenFormat();
        builder.token(accessToken, (metadata) -> {
            if (token instanceof ClaimAccessor claimAccessor) {
                metadata.put(OAuth2Authorization.Token.CLAIMS_METADATA_NAME, claimAccessor.getClaims());
            }
            metadata.put(OAuth2Authorization.Token.INVALIDATED_METADATA_NAME, false);
            metadata.put(OAuth2TokenFormat.class.getName(), accessTokenFormat.getValue());
        });
        return accessToken;
    }
}
