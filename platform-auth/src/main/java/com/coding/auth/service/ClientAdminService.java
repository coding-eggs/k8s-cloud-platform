package com.coding.auth.service;

import com.coding.auth.client.ClientAdminRequest;
import com.coding.auth.client.ClientAdminView;
import com.coding.auth.client.ClientCreated;
import com.coding.auth.client.ClientSecrets;
import com.coding.auth.client.ClientType;
import com.coding.auth.client.OAuth2Scope;
import com.coding.auth.client.PlatformClientSettings;
import com.coding.auth.client.PlatformScopes;
import com.coding.auth.config.RedisSessionConfig;
import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static com.coding.auth.config.CustomClientSetting.*;
import static com.coding.auth.service.ClientTokenForms.invalid;

/**
 * OAuth 客户端管理（spec §4）。只经 {@code /internal/clients/**} 被 platform-api 调用。
 *
 * <p>注入的是原始 JDBC 仓库而不是主仓库：主仓库对停用的客户端返回 null，管理侧要能看到并启用它们。
 */
@Service
public class ClientAdminService {

    private final RegisteredClientRepository repository;
    private final JdbcTemplate jdbcTemplate;
    private final PasswordEncoder passwordEncoder;
    private final ExternalClientAssembler assembler;

    public ClientAdminService(@Qualifier("jdbcRegisteredClientRepository") RegisteredClientRepository repository,
                              JdbcTemplate jdbcTemplate, PasswordEncoder passwordEncoder, ClientSecretSealer sealer) {
        this.repository = repository;
        this.jdbcTemplate = jdbcTemplate;
        this.passwordEncoder = passwordEncoder;
        this.assembler = new ExternalClientAssembler(sealer, passwordEncoder);
    }

    public List<ClientAdminView> list() {
        return jdbcTemplate.queryForList("SELECT id FROM oauth2_registered_client ORDER BY client_id_issued_at", String.class)
                .stream().map(repository::findById).filter(Objects::nonNull).map(ClientViews::toView).toList();
    }

    public ClientAdminView get(String id) {
        return ClientViews.toView(load(id));
    }

    public List<OAuth2Scope> scopes() {
        return PlatformScopes.CATALOG;
    }

    public ClientCreated create(ClientAdminRequest req) {
        if (req.getType() == null) throw invalid("必须选择客户端类型");
        String clientId = StringUtils.hasText(req.getClientId()) ? req.getClientId().trim() : ClientTokenForms.randomClientId();
        if (repository.findByClientId(clientId) != null) throw invalid("client_id 已存在：" + clientId);
        ClientSecrets out = new ClientSecrets();
        RegisteredClient client = assembler.assemble(UUID.randomUUID().toString(), clientId, req.getType(), null, req, out);
        repository.save(client);
        return result(client, out);
    }

    /** 内置客户端只改三项；外部客户端 client_id 与类型不可改 */
    public ClientCreated update(ClientAdminRequest req) {
        RegisteredClient existing = load(req.getId());
        if (PlatformClientSettings.isBuiltin(existing)) {
            RegisteredClient saved = updateBuiltin(existing, req);
            repository.save(saved);
            return result(saved, new ClientSecrets());
        }
        ClientSecrets out = new ClientSecrets();
        RegisteredClient client = assembler.assemble(existing.getId(), existing.getClientId(),
                ClientViews.typeOf(existing), existing, req, out);
        repository.save(client);
        return result(client, out);
    }

    public void delete(String id) {
        RegisteredClient existing = load(id);
        if (PlatformClientSettings.isBuiltin(existing)) throw new CloudPlatformException(EnumResponseType.OAUTH_CLIENT_BUILTIN_LOCKED);
        jdbcTemplate.update("DELETE FROM oauth2_authorization_consent WHERE registered_client_id = ?", id);
        jdbcTemplate.update("DELETE FROM oauth2_registered_client WHERE id = ?", id);
    }

    public void setEnabled(String id, boolean enabled) {
        RegisteredClient existing = load(id);
        if (!enabled && PlatformClientSettings.isBuiltin(existing)) {
            throw new CloudPlatformException(EnumResponseType.OAUTH_CLIENT_BUILTIN_LOCKED);
        }
        repository.save(RegisteredClient.from(existing)
                .clientSettings(PlatformClientSettings.withEnabled(existing.getClientSettings(), enabled).build())
                .build());
    }

    /** @param which client | jws | jwe */
    public ClientSecrets resetSecret(String id, String which) {
        RegisteredClient existing = load(id);
        ClientSettings cs = existing.getClientSettings();
        ClientSecrets out = new ClientSecrets();
        RegisteredClient.Builder b = RegisteredClient.from(existing);
        switch (which == null ? "" : which) {
            case "client" -> {
                if (ClientViews.typeOf(existing) != ClientType.CONFIDENTIAL) throw invalid("公共客户端没有 client_secret");
                out.setClientSecret(ExternalClientAssembler.newClientSecret());
                b.clientSecret(passwordEncoder.encode(out.getClientSecret()));
            }
            case "jws" -> {
                String alg = ExternalClientAssembler.str(cs, CLIENT_SETTING_JWS_SIG_ALG);
                if (!ClientTokenForms.isHmac(alg)) throw invalid("当前签名算法不使用对称密钥");
                out.setJwsSecret(ClientTokenForms.randomAlnum(ClientTokenForms.jwsSecretBytes(alg)));
                b.clientSettings(reseal(cs, out.getJwsSecret(), CLIENT_SETTING_JWS_SECRET_KEY, CLIENT_SETTING_JWS_SECRET_KID_KEY));
            }
            case "jwe" -> {
                String keyAlg = ExternalClientAssembler.str(cs, CLIENT_SETTING_JWE_KEY_ALG_KEY);
                if (!ClientTokenForms.JWE.equals(ExternalClientAssembler.str(cs, CLIENT_SETTING_JWT_TYPE_KEY))
                        || !ClientTokenForms.isSymmetricJwe(keyAlg)) {
                    throw invalid("当前加密方式不使用对称密钥");
                }
                String enc = ExternalClientAssembler.str(cs, CLIENT_SETTING_JWE_ENC_METHOD_KEY);
                out.setJweSecret(ClientTokenForms.randomAlnum(ClientTokenForms.jweSecretBytes(keyAlg,
                        enc == null ? ClientTokenForms.DEFAULT_ENC : enc)));
                b.clientSettings(reseal(cs, out.getJweSecret(), CLIENT_SETTING_JWE_SECRET_KEY, CLIENT_SETTING_JWE_SECRET_KID_KEY));
            }
            default -> throw invalid("未知的密钥类别 " + which);
        }
        repository.save(b.build());
        return out;
    }

    private RegisteredClient updateBuiltin(RegisteredClient existing, ClientAdminRequest req) {
        RegisteredClient.Builder b = RegisteredClient.from(existing);
        if (req.getRedirectUris() != null) {
            if (req.getRedirectUris().isEmpty()) throw invalid("至少需要一个回调地址");
            b.redirectUris(s -> { s.clear(); s.addAll(req.getRedirectUris()); });
        }
        if (req.getPostLogoutRedirectUris() != null) {
            b.postLogoutRedirectUris(s -> { s.clear(); s.addAll(req.getPostLogoutRedirectUris()); });
        }
        if (req.getAccessTokenTimeToLive() != null) {
            long ttl = req.getAccessTokenTimeToLive();
            //平台前端靠会话续期：token 比会话活得久 → 会话先死、token 成孤儿
            if (ttl < 300 || ttl >= RedisSessionConfig.SESSION_MAX_INACTIVE_SECONDS) {
                throw invalid("内置客户端的 Access Token 有效期需在 300 秒到会话空闲超时（"
                        + RedisSessionConfig.SESSION_MAX_INACTIVE_SECONDS + " 秒）之间");
            }
            b.tokenSettings(TokenSettings.withSettings(existing.getTokenSettings().getSettings())
                    .accessTokenTimeToLive(Duration.ofSeconds(ttl)).build());
        }
        return b.build();
    }

    private ClientSettings reseal(ClientSettings cs, String plain, String secretKey, String kidKey) {
        Map<String, Object> s = new HashMap<>();
        assembler.sealInto(s, plain, secretKey, kidKey);
        return ClientSettings.withSettings(cs.getSettings()).settings(m -> m.putAll(s)).build();
    }

    private RegisteredClient load(String id) {
        RegisteredClient c = StringUtils.hasText(id) ? repository.findById(id) : null;
        if (c == null) throw new CloudPlatformException(EnumResponseType.OAUTH2_CLIENT_NOT_EXIST);
        return c;
    }

    private static ClientCreated result(RegisteredClient client, ClientSecrets out) {
        return new ClientCreated(ClientViews.toView(client), out.isEmpty() ? null : out);
    }
}
