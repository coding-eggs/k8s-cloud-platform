package com.coding.auth.service;

import com.coding.auth.client.ClientAdminRequest;
import com.coding.auth.client.ClientAdminView;
import com.coding.auth.client.ClientCreated;
import com.coding.auth.client.ClientSecrets;
import com.coding.auth.client.ClientType;
import com.coding.auth.client.PlatformClientSettings;
import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static com.coding.auth.config.CustomClientSetting.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ClientAdminServiceTest {

    /** 内存仓库：按 id / clientId 存取 */
    static class MemRepo implements RegisteredClientRepository {
        final Map<String, RegisteredClient> byId = new HashMap<>();

        public void save(RegisteredClient c) {
            byId.put(c.getId(), c);
        }

        public RegisteredClient findById(String id) {
            return byId.get(id);
        }

        public RegisteredClient findByClientId(String cid) {
            return byId.values().stream().filter(c -> c.getClientId().equals(cid)).findFirst().orElse(null);
        }
    }

    private final MemRepo repo = new MemRepo();
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ClientSecretSealer sealer = mock(ClientSecretSealer.class);
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);
    private final ClientAdminService svc;

    ClientAdminServiceTest() {
        when(sealer.seal(anyString())).thenAnswer(inv -> "sealed:" + inv.getArgument(0));
        svc = new ClientAdminService(repo, jdbc, encoder, sealer);
    }

    private static ClientAdminRequest confidential(Set<String> grants) {
        ClientAdminRequest r = new ClientAdminRequest();
        r.setClientName("外部系统");
        r.setType(ClientType.CONFIDENTIAL);
        r.setGrantTypes(grants);
        r.setScopes(Set.of("openid", "profile"));
        r.setRedirectUris(Set.of("https://rp.example.com/cb"));
        return r;
    }

    private static ClientAdminRequest publicClient() {
        ClientAdminRequest r = confidential(Set.of("authorization_code"));
        r.setType(ClientType.PUBLIC);
        return r;
    }

    private static void assertCode(Runnable call, EnumResponseType type) {
        assertThatThrownBy(call::run).isInstanceOf(CloudPlatformException.class)
                .satisfies(e -> assertThat(((CloudPlatformException) e).getCode()).isEqualTo(type.getCode()));
    }

    /** 迁移打过标的内置客户端（与 platform-web-client 同形） */
    private RegisteredClient seedBuiltin() {
        RegisteredClient c = RegisteredClient.withId("builtin-id").clientId("platform-web-client")
                .clientName("平台前端")
                .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(new AuthorizationGrantType("urn:platform:session-renewal"))
                .redirectUri("http://old/cb")
                .scope("openid").scope("profile")
                .clientSettings(ClientSettings.builder().requireProofKey(true).requireAuthorizationConsent(false)
                        .setting(PlatformClientSettings.BUILTIN_KEY, true).build())
                .tokenSettings(TokenSettings.builder().accessTokenTimeToLive(Duration.ofHours(1)).build())
                .build();
        repo.save(c);
        return c;
    }

    @Test
    void publicClientGetsNoSecretAndOnlyAuthCode() {
        ClientCreated created = svc.create(publicClient());
        RegisteredClient saved = repo.findByClientId(created.client().getClientId());
        assertThat(saved.getClientSecret()).isNull();
        assertThat(saved.getClientAuthenticationMethods()).containsExactly(ClientAuthenticationMethod.NONE);
        assertThat(created.secrets()).isNull();
        assertThat(created.client().getType()).isEqualTo(ClientType.PUBLIC);

        ClientAdminRequest withCc = publicClient();
        withCc.setGrantTypes(Set.of("authorization_code", "client_credentials"));
        assertCode(() -> svc.create(withCc), EnumResponseType.OAUTH_CLIENT_INVALID);

        ClientAdminRequest hmac = publicClient();
        hmac.setJwtType("JWS");
        hmac.setJwsSigAlg("HS256");
        assertCode(() -> svc.create(hmac), EnumResponseType.OAUTH_CLIENT_INVALID);
    }

    @Test
    void confidentialCreateReturnsSecretOnceAndViewHasNoSecret() {
        ClientCreated created = svc.create(confidential(Set.of("authorization_code", "refresh_token")));
        String plain = created.secrets().getClientSecret();
        assertThat(plain).isNotBlank();
        assertThat(created.client().getClientId()).matches("ext-[a-z0-9]{12}");
        RegisteredClient saved = repo.findByClientId(created.client().getClientId());
        assertThat(encoder.matches(plain, saved.getClientSecret())).isTrue();
        assertThat(saved.getClientAuthenticationMethods()).containsExactly(ClientAuthenticationMethod.CLIENT_SECRET_BASIC);
        assertThat(PlatformClientSettings.isBuiltin(saved)).isFalse();

        ClientAdminView view = svc.get(saved.getId());
        assertThat(view.isHasClientSecret()).isTrue();
        String json = JsonMapper.builder().build().writeValueAsString(view);
        assertThat(json).doesNotContain(plain).doesNotContain(saved.getClientSecret());
    }

    @Test
    void authCodeForcesPkce() {
        ClientCreated created = svc.create(confidential(Set.of("authorization_code")));
        assertThat(repo.findById(created.client().getId()).getClientSettings().isRequireProofKey()).isTrue();

        ClientAdminRequest noRedirect = confidential(Set.of("authorization_code"));
        noRedirect.setRedirectUris(Set.of());
        assertCode(() -> svc.create(noRedirect), EnumResponseType.OAUTH_CLIENT_INVALID);
    }

    @Test
    void refreshWithoutAuthCodeRejected() {
        assertCode(() -> svc.create(confidential(Set.of("refresh_token", "client_credentials"))),
                EnumResponseType.OAUTH_CLIENT_INVALID);
    }

    @Test
    void hs512GeneratesSixtyFourByteKeyStoredEncrypted() {
        ClientAdminRequest r = confidential(Set.of("client_credentials"));
        r.setRedirectUris(null);
        r.setJwtType("JWS");
        r.setJwsSigAlg("HS512");
        ClientCreated created = svc.create(r);
        String jws = created.secrets().getJwsSecret();
        assertThat(jws.getBytes(StandardCharsets.UTF_8)).hasSize(64);
        RegisteredClient saved = repo.findById(created.client().getId());
        assertThat((String) saved.getClientSettings().getSetting(CLIENT_SETTING_JWS_SECRET_KEY)).isEqualTo("sealed:" + jws);
        assertThat(created.client().isHasJwsSecret()).isTrue();
    }

    @Test
    void symmetricJweKeyLengthFollowsAlgorithm() {
        ClientAdminRequest r = confidential(Set.of("client_credentials"));
        r.setRedirectUris(null);
        r.setJwtType("JWE");
        r.setJweKeyAlg("A128KW");
        assertThat(svc.create(r).secrets().getJweSecret()).hasSize(16);
        r.setJweKeyAlg("dir");
        r.setJweEncMethod("A256GCM");
        assertThat(svc.create(r).secrets().getJweSecret()).hasSize(32);

        ClientAdminRequest asym = confidential(Set.of("client_credentials"));
        asym.setJwtType("JWE");
        asym.setJweKeyAlg("RSA-OAEP-256");
        assertCode(() -> svc.create(asym), EnumResponseType.OAUTH_CLIENT_INVALID);
        asym.setJweJwkUrl("https://rp.example.com/jwks");
        assertThat(svc.create(asym).secrets().getJweSecret()).isNull();
    }

    @Test
    void builtinDeleteAndDisableRejected() {
        seedBuiltin();
        assertCode(() -> svc.delete("builtin-id"), EnumResponseType.OAUTH_CLIENT_BUILTIN_LOCKED);
        assertCode(() -> svc.setEnabled("builtin-id", false), EnumResponseType.OAUTH_CLIENT_BUILTIN_LOCKED);
        assertThat(PlatformClientSettings.isEnabled(repo.findById("builtin-id"))).isTrue();
    }

    @Test
    void builtinUpdateOnlyTouchesThreeFields() {
        seedBuiltin();
        ClientAdminRequest r = confidential(Set.of("client_credentials"));
        r.setId("builtin-id");
        r.setClientId("hijack");
        r.setScopes(Set.of("openid"));
        r.setRequireAuthorizationConsent(true);
        r.setRedirectUris(Set.of("http://new/cb"));
        r.setPostLogoutRedirectUris(Set.of("http://new/"));
        r.setAccessTokenTimeToLive(1800L);
        r.setJwtType("JWS");
        r.setJwsSigAlg("HS256");
        svc.update(r);

        RegisteredClient after = repo.findById("builtin-id");
        assertThat(after.getClientId()).isEqualTo("platform-web-client");
        assertThat(after.getRedirectUris()).containsExactly("http://new/cb");
        assertThat(after.getPostLogoutRedirectUris()).containsExactly("http://new/");
        assertThat(after.getTokenSettings().getAccessTokenTimeToLive()).isEqualTo(Duration.ofSeconds(1800));
        assertThat(after.getAuthorizationGrantTypes()).extracting(AuthorizationGrantType::getValue)
                .containsExactlyInAnyOrder("authorization_code", "urn:platform:session-renewal");
        assertThat(after.getScopes()).containsExactlyInAnyOrder("openid", "profile");
        assertThat(after.getClientSettings().isRequireAuthorizationConsent()).isFalse();
        assertThat((Object) after.getClientSettings().getSetting(CLIENT_SETTING_JWS_SIG_ALG)).isNull();
        assertThat(PlatformClientSettings.isBuiltin(after)).isTrue();

        // 内置客户端的 token 不能比会话活得久
        r.setAccessTokenTimeToLive(28800L);
        assertCode(() -> svc.update(r), EnumResponseType.OAUTH_CLIENT_INVALID);
    }

    @Test
    void legacyScopesDroppedOnUpdate() {
        RegisteredClient legacy = RegisteredClient.withId("legacy-id").clientId("gateway-code-client")
                .clientSecret(encoder.encode("old"))
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .redirectUri("http://gw/cb")
                .scope("openid").scope("profile").scope("message.read")
                .clientSettings(ClientSettings.builder()
                        .setting(CLIENT_SETTING_JWT_TYPE_KEY, "JWS")
                        .setting(CLIENT_SETTING_JWS_SIG_ALG, "HS256")
                        .setting(CLIENT_SETTING_JWS_SECRET_KEY, "legacy-cipher")
                        .build())
                .build();
        repo.save(legacy);

        ClientAdminView view = svc.get("legacy-id");
        assertThat(view.getType()).isEqualTo(ClientType.CONFIDENTIAL);
        assertThat(view.getScopes()).contains("message.read");

        ClientAdminRequest r = new ClientAdminRequest();
        r.setId("legacy-id");
        r.setClientName("网关");
        ClientCreated out = svc.update(r);

        RegisteredClient after = repo.findById("legacy-id");
        assertThat(after.getScopes()).containsExactlyInAnyOrder("openid", "profile");
        assertThat((String) after.getClientSettings().getSetting(CLIENT_SETTING_JWS_SIG_ALG)).isEqualTo("HS256");
        assertThat((String) after.getClientSettings().getSetting(CLIENT_SETTING_JWS_SECRET_KEY)).isEqualTo("legacy-cipher");
        assertThat(after.getClientSecret()).isEqualTo(legacy.getClientSecret());
        assertThat(after.getClientAuthenticationMethods()).containsExactly(ClientAuthenticationMethod.CLIENT_SECRET_BASIC);
        assertThat(out.secrets()).isNull();

        ClientAdminRequest bad = new ClientAdminRequest();
        bad.setId("legacy-id");
        bad.setScopes(Set.of("message.read"));
        assertCode(() -> svc.update(bad), EnumResponseType.OAUTH_CLIENT_INVALID);
    }

    @Test
    void clientCredentialsAllowedForConfidential() {
        ClientAdminRequest r = confidential(Set.of("client_credentials"));
        r.setRedirectUris(null);
        r.setScopes(null);
        ClientCreated created = svc.create(r);
        RegisteredClient saved = repo.findById(created.client().getId());
        assertThat(saved.getAuthorizationGrantTypes()).containsExactly(AuthorizationGrantType.CLIENT_CREDENTIALS);
        assertThat(saved.getClientSettings().isRequireProofKey()).isFalse();
    }

    @Test
    void resetJwsSecretRotatesKid() {
        ClientAdminRequest r = confidential(Set.of("client_credentials"));
        r.setJwtType("JWS");
        r.setJwsSigAlg("HS256");
        ClientCreated created = svc.create(r);
        String id = created.client().getId();
        String kid1 = created.client().getJwsSecretKid();

        ClientSecrets again = svc.resetSecret(id, "jws");
        assertThat(again.getJwsSecret()).isNotEqualTo(created.secrets().getJwsSecret()).hasSize(32);
        assertThat(again.getClientSecret()).isNull();
        assertThat(svc.get(id).getJwsSecretKid()).isNotBlank().isNotEqualTo(kid1);

        ClientSecrets cs = svc.resetSecret(id, "client");
        assertThat(encoder.matches(cs.getClientSecret(), repo.findById(id).getClientSecret())).isTrue();

        assertCode(() -> svc.resetSecret(id, "jwe"), EnumResponseType.OAUTH_CLIENT_INVALID);
    }

    @Test
    void disableAndEnableExternal() {
        ClientCreated created = svc.create(confidential(Set.of("client_credentials")));
        String id = created.client().getId();
        svc.setEnabled(id, false);
        assertThat(svc.get(id).isEnabled()).isFalse();
        svc.setEnabled(id, true);
        assertThat(svc.get(id).isEnabled()).isTrue();
    }

    @Test
    void duplicateClientIdAndTtlRangeRejected() {
        seedBuiltin();
        ClientAdminRequest dup = confidential(Set.of("client_credentials"));
        dup.setClientId("platform-web-client");
        assertCode(() -> svc.create(dup), EnumResponseType.OAUTH_CLIENT_INVALID);

        ClientAdminRequest ttl = confidential(Set.of("client_credentials"));
        ttl.setAccessTokenTimeToLive(100L);
        assertCode(() -> svc.create(ttl), EnumResponseType.OAUTH_CLIENT_INVALID);
        ttl.setAccessTokenTimeToLive(86401L);
        assertCode(() -> svc.create(ttl), EnumResponseType.OAUTH_CLIENT_INVALID);
    }
}
