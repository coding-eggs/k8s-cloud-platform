package com.coding.auth.service;

import com.coding.auth.client.ClientAdminView;
import com.coding.auth.client.ClientType;
import com.coding.auth.client.PlatformClientSettings;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.util.StringUtils;

import java.util.LinkedHashSet;
import java.util.stream.Collectors;

import static com.coding.auth.config.CustomClientSetting.*;

/** RegisteredClient → 管理视图。只给「有没有密钥」，不给任何密钥材料 */
final class ClientViews {

    private ClientViews() {
    }

    /** 老行可能同时挂着 basic 和 none：只要有任何需要密钥的认证方式就算机密客户端 */
    static ClientType typeOf(RegisteredClient c) {
        boolean secretAuth = c.getClientAuthenticationMethods().stream()
                .anyMatch(m -> !ClientAuthenticationMethod.NONE.equals(m));
        return secretAuth ? ClientType.CONFIDENTIAL : ClientType.PUBLIC;
    }

    static ClientAdminView toView(RegisteredClient c) {
        ClientSettings cs = c.getClientSettings();
        TokenSettings ts = c.getTokenSettings();
        ClientAdminView v = new ClientAdminView();
        v.setId(c.getId());
        v.setClientId(c.getClientId());
        v.setClientName(c.getClientName());
        v.setType(typeOf(c));
        v.setBuiltin(PlatformClientSettings.isBuiltin(c));
        v.setEnabled(PlatformClientSettings.isEnabled(c));
        v.setGrantTypes(c.getAuthorizationGrantTypes().stream().map(AuthorizationGrantType::getValue)
                .collect(Collectors.toCollection(LinkedHashSet::new)));
        v.setClientAuthenticationMethods(c.getClientAuthenticationMethods().stream().map(ClientAuthenticationMethod::getValue)
                .collect(Collectors.toCollection(LinkedHashSet::new)));
        v.setScopes(new LinkedHashSet<>(c.getScopes()));
        v.setRedirectUris(new LinkedHashSet<>(c.getRedirectUris()));
        v.setPostLogoutRedirectUris(new LinkedHashSet<>(c.getPostLogoutRedirectUris()));
        v.setRequireProofKey(cs.isRequireProofKey());
        v.setRequireAuthorizationConsent(cs.isRequireAuthorizationConsent());
        v.setAccessTokenTimeToLive(ts.getAccessTokenTimeToLive().toSeconds());
        v.setRefreshTokenTimeToLive(ts.getRefreshTokenTimeToLive() != null ? ts.getRefreshTokenTimeToLive().toSeconds() : null);
        v.setJwtType(StringUtils.hasText(cs.getSetting(CLIENT_SETTING_JWT_TYPE_KEY))
                ? cs.getSetting(CLIENT_SETTING_JWT_TYPE_KEY) : ClientTokenForms.JWS);
        v.setJwsSigAlg(StringUtils.hasText(cs.getSetting(CLIENT_SETTING_JWS_SIG_ALG))
                ? cs.getSetting(CLIENT_SETTING_JWS_SIG_ALG) : ClientTokenForms.DEFAULT_JWS_ALG);
        v.setJweKeyAlg(cs.getSetting(CLIENT_SETTING_JWE_KEY_ALG_KEY));
        v.setJweEncMethod(cs.getSetting(CLIENT_SETTING_JWE_ENC_METHOD_KEY));
        v.setJweJwkUrl(cs.getJwkSetUrl());
        v.setHasClientSecret(StringUtils.hasText(c.getClientSecret()));
        v.setHasJwsSecret(StringUtils.hasText(cs.getSetting(CLIENT_SETTING_JWS_SECRET_KEY)));
        v.setHasJweSecret(StringUtils.hasText(cs.getSetting(CLIENT_SETTING_JWE_SECRET_KEY)));
        v.setJwsSecretKid(cs.getSetting(CLIENT_SETTING_JWS_SECRET_KID_KEY));
        v.setJweSecretKid(cs.getSetting(CLIENT_SETTING_JWE_SECRET_KID_KEY));
        v.setClientIdIssuedAt(c.getClientIdIssuedAt());
        return v;
    }
}
