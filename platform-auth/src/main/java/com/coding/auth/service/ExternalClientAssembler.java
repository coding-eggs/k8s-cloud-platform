package com.coding.auth.service;

import com.coding.auth.client.ClientAdminRequest;
import com.coding.auth.client.ClientSecrets;
import com.coding.auth.client.ClientType;
import com.coding.auth.client.PlatformClientSettings;
import com.coding.auth.client.PlatformScopes;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.OAuth2TokenFormat;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.coding.auth.config.CustomClientSetting.*;
import static com.coding.auth.service.ClientTokenForms.invalid;

/**
 * 按规则组装外部客户端（spec §4.2）。创建与更新共用：更新时请求里为 null 的字段取库内值，
 * 库内值里目录外 / 类型不允许的部分（老行遗留）在这里被规整掉（spec §4.8）。
 */
final class ExternalClientAssembler {

    static final String AUTH_CODE = AuthorizationGrantType.AUTHORIZATION_CODE.getValue();
    static final String REFRESH = AuthorizationGrantType.REFRESH_TOKEN.getValue();
    static final String CLIENT_CREDENTIALS = AuthorizationGrantType.CLIENT_CREDENTIALS.getValue();

    private final ClientSecretSealer sealer;
    private final PasswordEncoder passwordEncoder;

    ExternalClientAssembler(ClientSecretSealer sealer, PasswordEncoder passwordEncoder) {
        this.sealer = sealer;
        this.passwordEncoder = passwordEncoder;
    }

    static Set<String> allowedGrants(ClientType type) {
        return type == ClientType.PUBLIC ? Set.of(AUTH_CODE) : Set.of(AUTH_CODE, REFRESH, CLIENT_CREDENTIALS);
    }

    /** @param existing 更新时的库内行，创建时为 null；新生成的明文密钥写进 out */
    RegisteredClient assemble(String id, String clientId, ClientType type, RegisteredClient existing,
                              ClientAdminRequest req, ClientSecrets out) {
        Set<String> allowed = allowedGrants(type);
        Set<String> grants;
        if (req.getGrantTypes() != null) {
            grants = new LinkedHashSet<>(req.getGrantTypes());
            if (!allowed.containsAll(grants)) throw invalid(type + " 客户端只能使用 " + allowed);
        } else {
            grants = existing == null ? Set.of() : existing.getAuthorizationGrantTypes().stream()
                    .map(AuthorizationGrantType::getValue).filter(allowed::contains)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        }
        if (grants.isEmpty()) throw invalid("至少选择一种授权方式");
        boolean authCode = grants.contains(AUTH_CODE);
        if (grants.contains(REFRESH) && !authCode) throw invalid("refresh_token 必须与 authorization_code 一起使用");

        Set<String> scopes;
        if (req.getScopes() != null) {
            scopes = new LinkedHashSet<>(req.getScopes());
            scopes.stream().filter(s -> !PlatformScopes.contains(s)).findFirst()
                    .ifPresent(s -> { throw invalid("不支持的 scope " + s); });
        } else {
            scopes = existing == null ? Set.of() : existing.getScopes().stream().filter(PlatformScopes::contains)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        }

        Set<String> redirects = pick(req.getRedirectUris(), existing == null ? null : existing.getRedirectUris());
        if (authCode && redirects.isEmpty()) throw invalid("授权码模式至少需要一个回调地址");
        Set<String> postLogout = pick(req.getPostLogoutRedirectUris(),
                existing == null ? null : existing.getPostLogoutRedirectUris());

        ClientSettings old = existing == null ? null : existing.getClientSettings();
        boolean consent = req.getRequireAuthorizationConsent() != null ? req.getRequireAuthorizationConsent()
                : old == null || old.isRequireAuthorizationConsent();
        long accessTtl = ttl(req.getAccessTokenTimeToLive(),
                existing == null ? null : existing.getTokenSettings().getAccessTokenTimeToLive(), 3600);
        long refreshTtl = ttl(req.getRefreshTokenTimeToLive(),
                existing == null ? null : existing.getTokenSettings().getRefreshTokenTimeToLive(), 604800);
        if (accessTtl < 300 || accessTtl > 86400) throw invalid("Access Token 有效期需在 300~86400 秒之间");
        if (refreshTtl < 3600 || refreshTtl > 2592000) throw invalid("Refresh Token 有效期需在 3600~2592000 秒之间");

        ClientSettings.Builder settings = tokenForm(type, old, req, out)
                .requireProofKey(authCode)
                .requireAuthorizationConsent(consent)
                .setting(PlatformClientSettings.ENABLED_KEY, existing == null || PlatformClientSettings.isEnabled(existing));

        RegisteredClient.Builder b = RegisteredClient.withId(id)
                .clientId(clientId)
                .clientName(firstText(req.getClientName(), existing == null ? null : existing.getClientName(), clientId))
                .clientIdIssuedAt(existing == null ? Instant.now() : existing.getClientIdIssuedAt())
                .clientSettings(settings.build())
                .tokenSettings(TokenSettings.builder()
                        .accessTokenFormat(OAuth2TokenFormat.SELF_CONTAINED)
                        .accessTokenTimeToLive(Duration.ofSeconds(accessTtl))
                        .refreshTokenTimeToLive(Duration.ofSeconds(refreshTtl))
                        .reuseRefreshTokens(false)
                        .build());
        if (type == ClientType.CONFIDENTIAL) {
            String hashed = existing == null ? null : existing.getClientSecret();
            if (!StringUtils.hasText(hashed)) {
                out.setClientSecret(newClientSecret());
                hashed = passwordEncoder.encode(out.getClientSecret());
            }
            b.clientSecret(hashed).clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC);
        } else {
            b.clientAuthenticationMethod(ClientAuthenticationMethod.NONE);
        }
        grants.forEach(g -> b.authorizationGrantType(new AuthorizationGrantType(g)));
        scopes.forEach(b::scope);
        redirects.forEach(b::redirectUri);
        postLogout.forEach(b::postLogoutRedirectUri);
        return b.build();
    }

    /** token 形态；对称算法沿用库内密钥（算法没变时），否则新生成 */
    private ClientSettings.Builder tokenForm(ClientType type, ClientSettings old, ClientAdminRequest req, ClientSecrets out) {
        String jwtType = firstText(req.getJwtType(), str(old, CLIENT_SETTING_JWT_TYPE_KEY), ClientTokenForms.JWS);
        if (!ClientTokenForms.JWS.equals(jwtType) && !ClientTokenForms.JWE.equals(jwtType)) throw invalid("token 类型只能是 JWS 或 JWE");
        String jwsAlg = firstText(req.getJwsSigAlg(), str(old, CLIENT_SETTING_JWS_SIG_ALG), ClientTokenForms.DEFAULT_JWS_ALG);
        ClientTokenForms.checkJwsAlg(jwsAlg);
        boolean isPublic = type == ClientType.PUBLIC;
        if (isPublic && ClientTokenForms.isHmac(jwsAlg)) throw invalid("公共客户端无处保管对称密钥，不能用 HMAC 签名");

        Map<String, Object> s = new LinkedHashMap<>();
        s.put(CLIENT_SETTING_JWT_TYPE_KEY, jwtType);
        s.put(CLIENT_SETTING_JWS_SIG_ALG, jwsAlg);
        if (ClientTokenForms.isHmac(jwsAlg)) {
            boolean keep = jwsAlg.equals(str(old, CLIENT_SETTING_JWS_SIG_ALG)) && str(old, CLIENT_SETTING_JWS_SECRET_KEY) != null;
            if (keep) {
                copy(old, s, CLIENT_SETTING_JWS_SECRET_KEY, CLIENT_SETTING_JWS_SECRET_KID_KEY);
            } else {
                out.setJwsSecret(ClientTokenForms.randomAlnum(ClientTokenForms.jwsSecretBytes(jwsAlg)));
                sealInto(s, out.getJwsSecret(), CLIENT_SETTING_JWS_SECRET_KEY, CLIENT_SETTING_JWS_SECRET_KID_KEY);
            }
        }
        ClientSettings.Builder b = ClientSettings.builder();
        if (ClientTokenForms.JWE.equals(jwtType)) {
            String keyAlg = firstText(req.getJweKeyAlg(), str(old, CLIENT_SETTING_JWE_KEY_ALG_KEY), null);
            String enc = firstText(req.getJweEncMethod(), str(old, CLIENT_SETTING_JWE_ENC_METHOD_KEY), ClientTokenForms.DEFAULT_ENC);
            String jwkUrl = firstText(req.getJweJwkUrl(), old == null ? null : old.getJwkSetUrl(), null);
            ClientTokenForms.checkJwe(keyAlg, enc, jwkUrl);
            s.put(CLIENT_SETTING_JWE_KEY_ALG_KEY, keyAlg);
            s.put(CLIENT_SETTING_JWE_ENC_METHOD_KEY, enc);
            if (ClientTokenForms.isSymmetricJwe(keyAlg)) {
                if (isPublic) throw invalid("公共客户端无处保管对称密钥，不能用对称 JWE");
                boolean keep = keyAlg.equals(str(old, CLIENT_SETTING_JWE_KEY_ALG_KEY))
                        && (!"dir".equals(keyAlg) || enc.equals(str(old, CLIENT_SETTING_JWE_ENC_METHOD_KEY)))
                        && str(old, CLIENT_SETTING_JWE_SECRET_KEY) != null;
                if (keep) {
                    copy(old, s, CLIENT_SETTING_JWE_SECRET_KEY, CLIENT_SETTING_JWE_SECRET_KID_KEY);
                } else {
                    out.setJweSecret(ClientTokenForms.randomAlnum(ClientTokenForms.jweSecretBytes(keyAlg, enc)));
                    sealInto(s, out.getJweSecret(), CLIENT_SETTING_JWE_SECRET_KEY, CLIENT_SETTING_JWE_SECRET_KID_KEY);
                }
            } else {
                b.jwkSetUrl(jwkUrl);
            }
        }
        return b.settings(m -> m.putAll(s));
    }

    void sealInto(Map<String, Object> s, String plain, String secretKey, String kidKey) {
        s.put(secretKey, sealer.seal(plain));
        //kid 标识「第几把密钥」，重置即换，对方据此确认拿到的是不是最新那把
        s.put(kidKey, UUID.randomUUID().toString());
    }

    static String newClientSecret() {
        byte[] bytes = new byte[32];
        new java.security.SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static void copy(ClientSettings old, Map<String, Object> s, String... keys) {
        for (String k : keys) {
            String v = str(old, k);
            if (v != null) s.put(k, v);
        }
    }

    static String str(ClientSettings cs, String key) {
        if (cs == null) return null;
        Object v = cs.getSetting(key);
        return v instanceof String t && StringUtils.hasText(t) ? t : null;
    }

    private static Set<String> pick(Set<String> fromReq, Set<String> fromDb) {
        if (fromReq != null) return new LinkedHashSet<>(fromReq);
        return fromDb == null ? Set.of() : new LinkedHashSet<>(fromDb);
    }

    private static long ttl(Long fromReq, Duration fromDb, long dflt) {
        if (fromReq != null) return fromReq;
        return fromDb != null ? fromDb.toSeconds() : dflt;
    }

    private static String firstText(String a, String b, String c) {
        return StringUtils.hasText(a) ? a : StringUtils.hasText(b) ? b : c;
    }
}
