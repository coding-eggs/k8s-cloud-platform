package com.coding.auth.service;

import com.coding.auth.client.RegisteredClientReq;
import com.coding.auth.client.RegisteredClientRes;
import com.coding.auth.config.CustomClientSetting;
import com.coding.auth.config.RedisSessionConfig;
import com.coding.common.components.jwt.impl.JweTokenStrategy;
import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.nimbusds.jose.*;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKMatcher;
import com.nimbusds.jose.jwk.JWKSelector;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.EncryptedJWT;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.OAuth2TokenFormat;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.text.ParseException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.stream.Collectors;

import static com.coding.auth.config.CustomClientSetting.*;

@Service
public class ClientService {

    @Autowired
    private RegisteredClientRepository registeredClientRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;


    @Autowired
    private JweTokenStrategy<Object> jweTokenStrategy;

    @Autowired
    private JWKSource<SecurityContext> jwkSource;


    // ==================== CRUD ====================
    @Transactional
    public RegisteredClientRes create(RegisteredClientReq req) {
        validate(req);
        //自定义 client setting
        CustomClientSetting custom = buildCustomSettings(req);
        //client setting
        ClientSettings clientSettings = buildClientSettings(req, custom);
        //token setting
        TokenSettings tokenSettings = buildTokenSettings(req);

        // 加密secret：如果前端传来的是密文则先解密，再 BCrypt 编码
        String plainSecret = decryptClientSecret(req.getClientSecret(), req.getClientSecretKid());
        String encodedSecret = encodeSecret(plainSecret);

        RegisteredClient.Builder builder = RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId(req.getClientId())
                .clientName(req.getClientName())
                .clientIdIssuedAt(Instant.now())
                .clientSettings(clientSettings)
                .tokenSettings(tokenSettings);


        if (StringUtils.hasText(encodedSecret)) {
            builder.clientSecret(encodedSecret);
            if (req.getClientSecretExpiresAt() != null) {
                builder.clientSecretExpiresAt(req.getClientSecretExpiresAt().toInstant(ZoneOffset.of("+08:00")));
            }
        }

        //客户端认证方法（PKCE 开启时自动添加 NONE）
        Set<String> authMethods = new LinkedHashSet<>(req.getClientAuthenticationMethods() != null ? req.getClientAuthenticationMethods() : Collections.emptySet());
        if (Boolean.TRUE.equals(req.getRequireProofKey())) {
            authMethods.add(ClientAuthenticationMethod.NONE.getValue());
        }
        addAuthMethods(builder, authMethods);

        //grant type
        addGrantTypes(builder, req.getAuthorizationGrantTypes(), req.getRequireProofKey());
        //redirect uri
        addUris(builder, req.getRedirectUris());
        //登出后 重定向 uri
        addPostLogoutUris(builder, req.getPostLogoutRedirectUris());

        addScopes(builder, req.getScopes());

        RegisteredClient client = builder.build();
        registeredClientRepository.save(client);

        return toRes(client);
    }

    @Transactional
    public void update(RegisteredClientReq req) {
        validate(req);
        RegisteredClient existing = loadedOrThrow(req.getId());

        CustomClientSetting custom = buildCustomSettings(req);
        ClientSettings clientSettings = buildClientSettings(req, custom);
        TokenSettings tokenSettings = buildTokenSettings(req);

        // 更新时：如果前端传来 clientSecret 则先解密再编码；否则保留原值
        String encodedSecret = StringUtils.hasText(req.getClientSecret())
                ? encodeSecret(decryptClientSecret(req.getClientSecret(), req.getClientSecretKid()))
                : existing.getClientSecret();

        RegisteredClient.Builder builder = RegisteredClient.withId(existing.getId())
                .clientId(coalesce(req.getClientId(), existing.getClientId()))
                .clientName(coalesce(req.getClientName(), existing.getClientName()))
                .clientIdIssuedAt(existing.getClientIdIssuedAt())
                .clientSecret(encodedSecret)
                .clientSecretExpiresAt(updateSecretExpiry(req, existing))
                .clientSettings(clientSettings)
                .tokenSettings(tokenSettings);

        Set<String> grantTypes = coalesceSet(req.getAuthorizationGrantTypes(), grantTypeNames(existing));
        Set<String> uris = coalesceSet(req.getRedirectUris(), uriNames(existing));
        Set<String> postLogoutUris = coalesceSet(req.getPostLogoutRedirectUris(), postLogoutUriNames(existing));
        Set<String> scopes = coalesceSet(req.getScopes(), scopeNames(existing));

        // 认证方法：PKCE 开启时自动添加 NONE
        Set<String> authMethods = new LinkedHashSet<>(coalesceSet(req.getClientAuthenticationMethods(), authMethodNames(existing)));
        if (Boolean.TRUE.equals(req.getRequireProofKey())) {
            authMethods.add(ClientAuthenticationMethod.NONE.getValue());
        }

        addGrantTypes(builder, grantTypes, req.getRequireProofKey());
        addUris(builder, uris);
        addPostLogoutUris(builder, postLogoutUris);
        addAuthMethods(builder, authMethods);
        addScopes(builder, scopes);

        registeredClientRepository.save(builder.build());
    }

    public void delete(String id) {
        if (!StringUtils.hasText(id)) {
            throw new IllegalArgumentException("客户端ID不能为空");
        }
        // Spring Authorization Server stores clients in oauth2_registered_client table
        int rows = jdbcTemplate.update("DELETE FROM oauth2_registered_client WHERE id = ?", id);
        if (rows == 0) {
            throw new IllegalArgumentException("客户端不存在: " + id);
        }
    }

    public RegisteredClientRes getById(String id) {
        return toRes(loadedOrThrow(id));
    }

    public List<RegisteredClientRes> listAll() {
        // Read IDs via raw query, then resolve full objects through the repository
        List<String> ids = jdbcTemplate.queryForList(
                "SELECT id FROM oauth2_registered_client", String.class);
        if (ids.isEmpty()) return Collections.emptyList();

        return ids.stream()
                .map(registeredClientRepository::findById)
                .filter(Objects::nonNull)
                .map(this::toRes)
                .toList();
    }

    // ==================== 校验 ====================

    private void validate(RegisteredClientReq req) {
        Set<String> grantTypes = req.getAuthorizationGrantTypes();
        boolean hasAuthCode = grantTypes != null && grantTypes.contains(AuthorizationGrantType.AUTHORIZATION_CODE.getValue());
        boolean hasDeviceCode = grantTypes != null && grantTypes.contains("device_code");
        // id 为空表示创建，非空表示更新（更新时 Secret/认证方式可不填，保留原值）
        boolean isCreate = !StringUtils.hasText(req.getId());

        // 1. 授权码模式校验
        if (hasAuthCode) {
            if (isCreate && !StringUtils.hasText(req.getClientSecret()) && !req.getRequireProofKey()) {
                throw new CloudPlatformException(EnumResponseType.OAUTH2_CLIENT_AUTH_CODE_NEED_SECRET);
            }
            if (req.getRedirectUris() == null || req.getRedirectUris().isEmpty()) {
                throw new CloudPlatformException(EnumResponseType.OAUTH2_CLIENT_AUTH_CODE_NEED_REDIRECT);
            }
            // 传了 Secret 但没传过期时间，默认 30 天
            if (StringUtils.hasText(req.getClientSecret()) && req.getClientSecretExpiresAt() == null) {
                req.setClientSecretExpiresAt(LocalDateTime.now().plusDays(30));
            }
        }

        // 2. 认证方式校验（仅设备码模式可不填）
        Set<String> authMethods = req.getClientAuthenticationMethods();
        if (isCreate && !hasDeviceCode && (authMethods == null || authMethods.isEmpty())) {
            throw new CloudPlatformException(EnumResponseType.OAUTH2_CLIENT_AUTH_METHOD_REQUIRED);
        }

        // 3. JWT 自定义配置校验
        validateJwtConfig(req);

        // 4. Access Token 有效期必须严格小于会话空闲超时（根凭证）。
        //    否则 token 比 session 活得久：session 先死、token 变孤儿 → 用户活跃时反而被踢、被迫重登。
        //    （req 未填时后端默认 1h，天然满足约束，无需校验。）
        if (req.getAccessTokenTimeToLive() != null
                && req.getAccessTokenTimeToLive() >= RedisSessionConfig.SESSION_MAX_INACTIVE_SECONDS) {
            throw new CloudPlatformException(EnumResponseType.OAUTH2_CLIENT_TOKEN_TTL_EXCEEDS_SESSION,
                    "上限 " + RedisSessionConfig.SESSION_MAX_INACTIVE_SECONDS + "s，当前 "
                            + req.getAccessTokenTimeToLive() + "s");
        }
    }

    private void validateJwtConfig(RegisteredClientReq req) {
        String jwtType = req.getJwtType();
        if (!StringUtils.hasText(jwtType)) return;

        if ("JWS".equals(jwtType)) {
            if (!StringUtils.hasText(req.getJwsSigAlg())) {
                throw new CloudPlatformException(EnumResponseType.OAUTH2_JWS_SIG_ALG_REQUIRED);
            }
            if (isSymmetricSignature(req.getJwsSigAlg()) && !StringUtils.hasText(req.getJwsSecret())) {
                throw new CloudPlatformException(EnumResponseType.OAUTH2_JWS_SYMMETRIC_NEED_SECRET);
            }
            // JWS 对称签名密钥长度校验
            if (isSymmetricSignature(req.getJwsSigAlg()) && StringUtils.hasText(req.getJwsSecret())) {
                //先对JwsSecret进行解密
                try {
                    int bytes = jweTokenStrategy.getData(req.getJwsSecret()).getBytes(StandardCharsets.UTF_8).length;
                    String alg = req.getJwsSigAlg();
                    if ("HS256".equals(alg) && bytes < 32) {
                        throw new CloudPlatformException(EnumResponseType.OAUTH2_JWS_KEY_LENGTH_INSUFFICIENT,
                                "HS256 要求密钥长度至少 32 字节（256 bit），当前 " + bytes + " 字节");
                    }
                    if ("HS384".equals(alg) && bytes < 48) {
                        throw new CloudPlatformException(EnumResponseType.OAUTH2_JWS_KEY_LENGTH_INSUFFICIENT,
                                "HS384 要求密钥长度至少 48 字节（384 bit），当前 " + bytes + " 字节");
                    }
                    if ("HS512".equals(alg) && bytes < 64) {
                        throw new CloudPlatformException(EnumResponseType.OAUTH2_JWS_KEY_LENGTH_INSUFFICIENT,
                                "HS512 要求密钥长度至少 64 字节（512 bit），当前 " + bytes + " 字节");
                    }

                } catch (ParseException | NoSuchAlgorithmException | JOSEException e) {
                    throw new CloudPlatformException(EnumResponseType.OAUTH2_JWS_SECRET_DECODE_ERROR);
                }

            }
        }

        if ("JWE".equals(jwtType)) {
            if (!StringUtils.hasText(req.getJweKeyAlg())) {
                throw new CloudPlatformException(EnumResponseType.OAUTH2_JWE_KEY_ALG_REQUIRED);
            }
            if (isAsymmetricKeyEncryption(req.getJweKeyAlg()) && !StringUtils.hasText(req.getJweJwkUrl())) {
                throw new CloudPlatformException(EnumResponseType.OAUTH2_JWE_ASYMMETRIC_NEED_JWK_URL);
            }
            if (isSymmetricKeyEncryption(req.getJweKeyAlg()) && !StringUtils.hasText(req.getJweSecret())) {
                throw new CloudPlatformException(EnumResponseType.OAUTH2_JWE_SYMMETRIC_NEED_SECRET);
            }
            // JWE 对称密钥长度校验（key wrap 算法）
            if (StringUtils.hasText(req.getJweSecret()) && !isDirectEncryption(req.getJweKeyAlg())) {

                //先对JweSecret进行解密
                try {
                    int bytes = jweTokenStrategy.getData(req.getJweSecret()).getBytes(StandardCharsets.UTF_8).length;
                    String alg = req.getJweKeyAlg();
                    if (("A128KW".equals(alg) || "A128GCMKW".equals(alg)) && bytes != 16) {
                        throw new CloudPlatformException(EnumResponseType.OAUTH2_JWE_KEY_LENGTH_MISMATCH,
                                "A128KW/A128GCMKW 要求密钥长度固定 16 字节（128 bit），当前 " + bytes + " 字节");
                    }
                    if (("A192KW".equals(alg) || "A192GCMKW".equals(alg)) && bytes != 24) {
                        throw new CloudPlatformException(EnumResponseType.OAUTH2_JWE_KEY_LENGTH_MISMATCH,
                                "A192KW/A192GCMKW 要求密钥长度固定 24 字节（192 bit），当前 " + bytes + " 字节");
                    }
                    if (("A256KW".equals(alg) || "A256GCMKW".equals(alg)) && bytes != 32) {
                        throw new CloudPlatformException(EnumResponseType.OAUTH2_JWE_KEY_LENGTH_MISMATCH,
                                "A256KW/A256GCMKW 要求密钥长度固定 32 字节（256 bit），当前 " + bytes + " 字节");
                    }

                } catch (ParseException | NoSuchAlgorithmException | JOSEException e) {
                    throw new CloudPlatformException(EnumResponseType.OAUTH2_JWS_SECRET_DECODE_ERROR);
                }
            }

            // dir 模式：根据内容加密算法校验密钥长度
            if (isDirectEncryption(req.getJweKeyAlg()) && StringUtils.hasText(req.getJweSecret())) {
                try {

                    int bytes = jweTokenStrategy.getData(req.getJweSecret()).getBytes(StandardCharsets.UTF_8).length;

                    String enc = req.getJweEncMethod();
                    if ("A128GCM".equals(enc) && bytes != 16) {
                        throw new CloudPlatformException(EnumResponseType.OAUTH2_JWE_KEY_LENGTH_MISMATCH,
                                "dir + A128GCM 要求密钥长度固定 16 字节（128 bit），当前 " + bytes + " 字节");
                    }
                    if ("A256GCM".equals(enc) && bytes != 32) {
                        throw new CloudPlatformException(EnumResponseType.OAUTH2_JWE_KEY_LENGTH_MISMATCH,
                                "dir + A256GCM 要求密钥长度固定 32 字节（256 bit），当前 " + bytes + " 字节");
                    }
                } catch (ParseException | NoSuchAlgorithmException | JOSEException e) {
                    throw new CloudPlatformException(EnumResponseType.OAUTH2_JWS_SECRET_DECODE_ERROR);
                }
            }
        }
    }

    private static boolean isDirectEncryption(String alg) {
        return JWEAlgorithm.DIR.equals(JWEAlgorithm.parse(alg));
    }

    private static boolean isSymmetricSignature(String alg) {
         return JWSAlgorithm.Family.HMAC_SHA.contains(JWSAlgorithm.parse(alg));
    }

    private static boolean isSymmetricKeyEncryption(String alg) {
        return JWEAlgorithm.Family.AES_KW.contains(JWEAlgorithm.parse(alg)) ||
                JWEAlgorithm.Family.AES_GCM_KW.contains(JWEAlgorithm.parse(alg)) ||
                JWEAlgorithm.DIR.equals(JWEAlgorithm.parse(alg));
    }

    private static boolean isAsymmetricKeyEncryption(String alg) {
        return JWEAlgorithm.Family.RSA.contains(JWEAlgorithm.parse(alg)) ||
                JWEAlgorithm.Family.ECDH_ES.contains(JWEAlgorithm.parse(alg));
    }

    // ==================== 转换 ====================

    private RegisteredClientRes toRes(RegisteredClient client) {
        if (client == null) return null;

        CustomClientSetting custom = parseCustomSettings(client.getClientSettings());

        RegisteredClientRes res = new RegisteredClientRes();
        res.setId(client.getId());
        res.setClientId(client.getClientId());
        res.setClientName(client.getClientName());
        res.setClientIdIssuedAt(client.getClientIdIssuedAt());
        res.setClientSecretExpiresAt(client.getClientSecretExpiresAt());
        res.setAuthorizationGrantTypes(grantTypeNames(client));
        res.setScopes(scopeNames(client));
        res.setRedirectUris(uriNames(client));
        res.setPostLogoutRedirectUris(postLogoutUriNames(client));
        res.setClientAuthenticationMethods(authMethodNames(client));

        // 返回脱敏的 clientSecret：如果客户端配置了 secret 则显示 ****，否则为 null
        res.setClientSecret(StringUtils.hasText(client.getClientSecret()) ? "****" : null);

        if (custom != null) {
            res.setJwtType(custom.getJwtType());
            res.setJweJwkUrl(custom.getJweJwkUrl());
            res.setJweKeyAlg(custom.getJweKeyAlg());
            res.setJweEncMethod(custom.getJweEncMethod());
            res.setJweSecret(custom.getJweSecret());
            res.setJweSecretKid(custom.getJweSecretKeyId());
            res.setJwsSigAlg(custom.getJwsSigAlg());
            res.setJwsSecret(custom.getJwsSecret());
            res.setJwsSecretKid(custom.getJwsSecretKeyId());
        }

        ClientSettings cs = client.getClientSettings();
        res.setRequireProofKey(cs.isRequireProofKey());
        res.setRequireAuthorizationConsent(cs.isRequireAuthorizationConsent());

        TokenSettings ts = client.getTokenSettings();
        if (ts.getAccessTokenTimeToLive() != null) {
            res.setAccessTokenTimeToLive(ts.getAccessTokenTimeToLive().getSeconds());
        }
        if (ts.getRefreshTokenTimeToLive() != null) {
            res.setRefreshTokenTimeToLive(ts.getRefreshTokenTimeToLive().getSeconds());
        }

        // 创建时间 = clientIdIssuedAt（RegisteredClient 无单独 createdAt）
        res.setCreatedAt(client.getClientIdIssuedAt());

        return res;
    }

    // ==================== 构建辅助 ====================

    private ClientSettings buildClientSettings(RegisteredClientReq req, CustomClientSetting custom) {
        Map<String, Object> settings = new LinkedHashMap<>();
        putIfNotBlank(settings, CLIENT_SETTING_JWT_TYPE_KEY, custom.getJwtType());
        putIfNotBlank(settings, CLIENT_SETTING_JWE_KEY_ALG_KEY, custom.getJweKeyAlg());
        putIfNotBlank(settings, CLIENT_SETTING_JWE_ENC_METHOD_KEY, custom.getJweEncMethod());
        putIfNotBlank(settings, CLIENT_SETTING_JWE_SECRET_KEY, custom.getJweSecret());
        putIfNotBlank(settings, CLIENT_SETTING_JWE_SECRET_KID_KEY, custom.getJweSecretKeyId());
        putIfNotBlank(settings, CLIENT_SETTING_JWS_SIG_ALG, custom.getJwsSigAlg());
        putIfNotBlank(settings, CLIENT_SETTING_JWS_SECRET_KEY, custom.getJwsSecret());
        putIfNotBlank(settings, CLIENT_SETTING_JWS_SECRET_KID_KEY, custom.getJwsSecretKeyId());

        ClientSettings.Builder builder = ClientSettings.builder();
        if (req.getRequireProofKey() != null) {
            builder.requireProofKey(req.getRequireProofKey());
        }
        if (req.getRequireAuthorizationConsent() != null) {
            builder.requireAuthorizationConsent(req.getRequireAuthorizationConsent());
        }
        if (StringUtils.hasText(custom.getJweJwkUrl())) {
            builder.jwkSetUrl(custom.getJweJwkUrl());
        }
        if (!settings.isEmpty()) {
            builder.settings(map -> map.putAll(settings));
        }

        return builder.build();
    }

    private TokenSettings buildTokenSettings(RegisteredClientReq req) {
        Duration accessTokenTtl = req.getAccessTokenTimeToLive() != null
                ? Duration.ofSeconds(req.getAccessTokenTimeToLive()) : Duration.ofHours(1);
        Duration refreshTokenTtl = req.getRefreshTokenTimeToLive() != null
                ? Duration.ofSeconds(req.getRefreshTokenTimeToLive()) : Duration.ofDays(1);

        return TokenSettings.builder()
                .accessTokenFormat(OAuth2TokenFormat.SELF_CONTAINED)
                .accessTokenTimeToLive(accessTokenTtl)
                .refreshTokenTimeToLive(refreshTokenTtl)
                .reuseRefreshTokens(false)
                .build();
    }

    private CustomClientSetting buildCustomSettings(RegisteredClientReq req) {
        return CustomClientSetting.builder()
                .jwtType(req.getJwtType())
                .jweJwkUrl(req.getJweJwkUrl())
                .jweKeyAlg(req.getJweKeyAlg())
                .jweEncMethod(req.getJweEncMethod())
                .jweSecret(req.getJweSecret())
                .jweSecretKeyId(req.getJweSecretKid())
                .jwsSigAlg(req.getJwsSigAlg())
                .jwsSecret(req.getJwsSecret())
                .jwsSecretKeyId(req.getJwsSecretKid())
                .build();
    }

    private CustomClientSetting parseCustomSettings(ClientSettings cs) {
        return CustomClientSetting.builder()
                .jwtType(cs.getSetting(CLIENT_SETTING_JWT_TYPE_KEY))
                .jweKeyAlg(cs.getSetting(CLIENT_SETTING_JWE_KEY_ALG_KEY))
                .jweEncMethod(cs.getSetting(CLIENT_SETTING_JWE_ENC_METHOD_KEY))
                .jweJwkUrl(cs.getJwkSetUrl())
                .jweSecret(cs.getSetting(CLIENT_SETTING_JWE_SECRET_KEY))
                .jweSecretKeyId(cs.getSetting(CLIENT_SETTING_JWE_SECRET_KID_KEY))
                .jwsSigAlg(cs.getSetting(CLIENT_SETTING_JWS_SIG_ALG))
                .jwsSecret(cs.getSetting(CLIENT_SETTING_JWS_SECRET_KEY))
                .jwsSecretKeyId(cs.getSetting(CLIENT_SETTING_JWS_SECRET_KID_KEY))
                .build();
    }

    // ==================== Builder helpers ====================

    private void addGrantTypes(RegisteredClient.Builder b, Set<String> vals, boolean pkce) {
        if (vals == null || vals.isEmpty()) return;
        for (String v : vals) {
            b.authorizationGrantType(new AuthorizationGrantType(v));
        }
        // authorization_code 模式自动添加 REFRESH_TOKEN
        if (vals.contains(AuthorizationGrantType.AUTHORIZATION_CODE.getValue()) && !pkce) {
            b.authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN);
        }
    }

    private void addAuthMethods(RegisteredClient.Builder b, Set<String> vals) {
        if (vals == null || vals.isEmpty()) return;
        for (String v : vals) {
            b.clientAuthenticationMethod(new ClientAuthenticationMethod(v));
        }
        // device_code 模式自动添加 NONE 认证方式
        if (vals.contains("device_code")) {
            b.clientAuthenticationMethod(ClientAuthenticationMethod.NONE);
        }
    }

    private void addUris(RegisteredClient.Builder b, Set<String> vals) {
        if (vals == null || vals.isEmpty()) return;
        for (String v : vals) {
            b.redirectUri(v);
        }
    }

    private void addPostLogoutUris(RegisteredClient.Builder b, Set<String> vals) {
        if (vals == null || vals.isEmpty()) return;
        for (String v : vals) b.postLogoutRedirectUri(v);
    }

    private void addScopes(RegisteredClient.Builder b, Set<String> vals) {
        if (vals == null || vals.isEmpty()) return;
        for (String v : vals) b.scope(v);
    }

    // ==================== extractors ====================

    private Set<String> grantTypeNames(RegisteredClient rc) {
        return rc.getAuthorizationGrantTypes().stream()
                .map(AuthorizationGrantType::getValue).collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private Set<String> uriNames(RegisteredClient rc) {
        return new LinkedHashSet<>(rc.getRedirectUris());
    }

    private Set<String> postLogoutUriNames(RegisteredClient rc) {
        return rc.getPostLogoutRedirectUris() != null
                ? new LinkedHashSet<>(rc.getPostLogoutRedirectUris()) : Collections.emptySet();
    }

    private Set<String> authMethodNames(RegisteredClient rc) {
        return rc.getClientAuthenticationMethods().stream()
                .map(Object::toString).collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private Set<String> scopeNames(RegisteredClient rc) {
        return new LinkedHashSet<>(rc.getScopes());
    }

    // ==================== 工具方法 ====================


    private Instant updateSecretExpiry(RegisteredClientReq req, RegisteredClient existing) {
        if (req.getClientSecretExpiresAt() != null) {
            return req.getClientSecretExpiresAt().toInstant(ZoneOffset.of("+08:00"));
        }
        if (!StringUtils.hasText(req.getClientSecret())) {
            return existing.getClientSecretExpiresAt();
        }
        return null;
    }

    private String encodeSecret(String rawSecret) {
        if (!StringUtils.hasText(rawSecret)) return null;
        return passwordEncoder.encode(rawSecret);
    }

    /**
     * 解密前端传来的 clientSecret（如果是 JWE 密文则解密，否则直接返回明文）
     */
    private String decryptClientSecret(String secret, String kid) {
        if (!StringUtils.hasText(secret)) return null;
        try {
            // 尝试通过 kid 找到对应的私钥来解密
            if (StringUtils.hasText(kid)) {
                List<JWK> jwks = jwkSource.get(new JWKSelector(new JWKMatcher.Builder()
                        .keyID(kid)
                        .build()
                ), null);
                if (jwks != null && !jwks.isEmpty()) {
                    EncryptedJWT encJwt = (EncryptedJWT) jweTokenStrategy.getJWT(secret);
                    return encJwt.getPayload().toString();
                }
            }
            // 无法解密或没有 kid，直接返回（可能是明文）
            return secret;
        } catch (ParseException | NoSuchAlgorithmException | JOSEException e) {
            // 解密失败说明可能是明文，直接返回
            return secret;
        }
    }

    private RegisteredClient loadedOrThrow(String id) {
        //根据id查询 client
        RegisteredClient client = registeredClientRepository.findById(id);
        if (client == null) throw new CloudPlatformException(EnumResponseType.OAUTH2_CLIENT_NOT_EXIST);
        return client;
    }

    private String coalesce(String a, String b) {
        return StringUtils.hasText(a) ? a : b;
    }

    private Set<String> coalesceSet(Set<String> a, Set<String> b) {
        return (a == null || a.isEmpty()) ? b : a;
    }

    private void putIfNotBlank(Map<String, Object> map, String key, String value) {
        if (StringUtils.hasText(value)) map.put(key, value);
    }
}
