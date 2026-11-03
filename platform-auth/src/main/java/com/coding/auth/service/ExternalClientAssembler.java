package com.coding.auth.service;

import com.coding.auth.client.ClientAdminRequest;
import com.coding.auth.client.ClientSecrets;
import com.coding.auth.client.ClientType;
import com.coding.auth.client.PlatformClientSettings;
import com.coding.auth.client.PlatformScopes;
import com.coding.auth.grant.SessionRenewalGrantType;
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
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.coding.auth.config.CustomClientSetting.*;
import static com.coding.auth.service.ClientTokenForms.invalid;

/**
 * 按规则组装外部客户端（spec §4.2）。创建与更新共用：更新时请求里为 null 的字段取库内值。
 *
 * <p>能力与旧客户端管理页对齐（可以不配，但不删）：认证方式四选、授权码可关 PKCE、设备码 / token 交换 /
 * 会话续期、全部 scope、RS/ES/HS 签名与含 GCMKW 的 JWE。老行里库内已有、但不在可选项里的值原样保留，
 * 不在保存时静默丢弃。只有密钥改为服务端生成，且不再设过期时间。
 */
final class ExternalClientAssembler {

    static final String AUTH_CODE = AuthorizationGrantType.AUTHORIZATION_CODE.getValue();
    static final String REFRESH = AuthorizationGrantType.REFRESH_TOKEN.getValue();
    static final String CLIENT_CREDENTIALS = AuthorizationGrantType.CLIENT_CREDENTIALS.getValue();
    static final String DEVICE_CODE = AuthorizationGrantType.DEVICE_CODE.getValue();
    static final String TOKEN_EXCHANGE = AuthorizationGrantType.TOKEN_EXCHANGE.getValue();
    static final String SESSION_RENEWAL = SessionRenewalGrantType.VALUE;

    static final String BASIC = ClientAuthenticationMethod.CLIENT_SECRET_BASIC.getValue();
    static final String POST = ClientAuthenticationMethod.CLIENT_SECRET_POST.getValue();
    static final String PRIVATE_KEY_JWT = ClientAuthenticationMethod.PRIVATE_KEY_JWT.getValue();
    static final String NONE = ClientAuthenticationMethod.NONE.getValue();

    private final ClientSecretSealer sealer;
    private final PasswordEncoder passwordEncoder;

    ExternalClientAssembler(ClientSecretSealer sealer, PasswordEncoder passwordEncoder) {
        this.sealer = sealer;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * SAS 只对「PKCE 换 token」请求做公共客户端认证，所以设备码 / 客户端凭据 / token 交换都要求客户端能认证自己（机密）；
     * SAS 也不给公共客户端发 refresh_token。会话续期按 client_id 认客户端，两类都行。
     */
    static Set<String> allowedGrants(ClientType type) {
        return type == ClientType.PUBLIC ? Set.of(AUTH_CODE, SESSION_RENEWAL)
                : Set.of(AUTH_CODE, REFRESH, CLIENT_CREDENTIALS, DEVICE_CODE, TOKEN_EXCHANGE, SESSION_RENEWAL);
    }

    static Set<String> allowedAuthMethods(ClientType type) {
        return type == ClientType.PUBLIC ? Set.of(NONE) : Set.of(BASIC, POST, PRIVATE_KEY_JWT, NONE);
    }

    static boolean needsSecret(Collection<String> methods) {
        return methods.contains(BASIC) || methods.contains(POST);
    }

    /** @param existing 更新时的库内行，创建时为 null；新生成的明文密钥写进 out */
    RegisteredClient assemble(String id, String clientId, ClientType type, RegisteredClient existing,
                              ClientAdminRequest req, ClientSecrets out) {
        Set<String> grants = choose(req.getGrantTypes(), existing == null ? null : existing.getAuthorizationGrantTypes()
                .stream().map(AuthorizationGrantType::getValue).toList(), allowedGrants(type), type + " 客户端的授权方式只能是 ");
        if (grants.isEmpty()) throw invalid("至少选择一种授权方式");
        boolean authCode = grants.contains(AUTH_CODE);
        if (grants.contains(REFRESH) && !authCode && !grants.contains(DEVICE_CODE)) {
            throw invalid("refresh_token 必须与授权码或设备码一起使用");
        }

        Set<String> methods = choose(req.getClientAuthenticationMethods(), existing == null ? null
                : existing.getClientAuthenticationMethods().stream().map(ClientAuthenticationMethod::getValue).toList(),
                allowedAuthMethods(type), type + " 客户端的认证方式只能是 ");
        if (methods.isEmpty()) methods = new LinkedHashSet<>(Set.of(type == ClientType.PUBLIC ? NONE : BASIC));
        if (type == ClientType.CONFIDENTIAL && methods.stream().allMatch(NONE::equals)) {
            throw invalid("机密客户端至少需要一种能认证自己的方式（client_secret_basic / client_secret_post / private_key_jwt）");
        }

        Set<String> scopeChoices = new LinkedHashSet<>();
        PlatformScopes.CATALOG.forEach(sc -> scopeChoices.add(sc.getName()));
        Set<String> scopes = choose(req.getScopes(), existing == null ? null : existing.getScopes(), scopeChoices,
                "不支持的 scope，可选 ");

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
        if (accessTtl < 60 || accessTtl > 86400) throw invalid("Access Token 有效期需在 60~86400 秒之间");
        if (refreshTtl < 300) throw invalid("Refresh Token 有效期不能小于 300 秒");

        boolean pkce = false;
        if (authCode) {
            pkce = req.getRequireProofKey() != null ? req.getRequireProofKey() : old == null || old.isRequireProofKey();
            if (!pkce && type == ClientType.PUBLIC) throw invalid("公共客户端没有密钥，授权码必须用 PKCE");
        }

        String jwkUrl = firstText(req.getJweJwkUrl(), old == null ? null : old.getJwkSetUrl(), null);
        if (methods.contains(PRIVATE_KEY_JWT) && jwkUrl == null) {
            throw invalid("private_key_jwt 认证必须填写对方的公钥地址（JWK Set URL）");
        }
        ClientSettings.Builder settings = tokenForm(type, old, req, jwkUrl, out)
                .requireProofKey(pkce)
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
        if (needsSecret(methods)) {
            String hashed = existing == null ? null : existing.getClientSecret();
            if (!StringUtils.hasText(hashed)) {
                out.setClientSecret(newClientSecret());
                hashed = passwordEncoder.encode(out.getClientSecret());
            }
            b.clientSecret(hashed);
        }
        methods.forEach(m -> b.clientAuthenticationMethod(new ClientAuthenticationMethod(m)));
        grants.forEach(g -> b.authorizationGrantType(new AuthorizationGrantType(g)));
        scopes.forEach(b::scope);
        redirects.forEach(b::redirectUri);
        postLogout.forEach(b::postLogoutRedirectUri);
        return b.build();
    }

    /** token 形态；对称算法沿用库内密钥（算法没变时），否则新生成 */
    private ClientSettings.Builder tokenForm(ClientType type, ClientSettings old, ClientAdminRequest req, String jwkUrl,
                                             ClientSecrets out) {
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
            }
        }
        //private_key_jwt 认证与非对称 JWE 共用对方的同一个 JWK Set
        if (jwkUrl != null) b.jwkSetUrl(jwkUrl);
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

    /**
     * 请求给了就按请求（只许可选项 + 这一行库内本来就有的值），没给就原样沿用库内值。
     * 老行里的非常规值（旧页面写进去的）因此既不会被静默删掉，也不会挡住保存。
     */
    private static Set<String> choose(Collection<String> fromReq, Collection<String> fromDb,
                                      Set<String> choices, String error) {
        if (fromReq == null) return fromDb == null ? new LinkedHashSet<>() : new LinkedHashSet<>(fromDb);
        Set<String> ok = new LinkedHashSet<>(choices);
        if (fromDb != null) ok.addAll(fromDb);
        for (String v : fromReq) {
            if (!ok.contains(v)) throw invalid(error + choices + "（收到 " + v + "）");
        }
        return new LinkedHashSet<>(fromReq);
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
