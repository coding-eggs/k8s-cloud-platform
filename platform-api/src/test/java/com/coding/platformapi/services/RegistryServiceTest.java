package com.coding.platformapi.services;

import com.coding.common.exception.CloudPlatformException;
import com.coding.data.mapper.system.PlatformCredentialMapper;
import com.coding.data.models.system.PlatformCredential;
import com.coding.platformapi.cache.RedisJsonCache;
import com.coding.platformapi.credential.CredentialCipher;
import com.coding.platformapi.models.RegistryBrowseRequest;
import com.coding.platformapi.models.RegistryConnectionView;
import com.coding.platformapi.registry.BrowseResult;
import com.coding.platformapi.registry.CredentialRef;
import com.coding.platformapi.registry.RegistryDialect;
import com.coding.platformapi.registry.RegistryDialectRegistry;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link RegistryService}：窄投影、只挑启用的连接、**匹配不到时不调方言**、suggest 走缓存。
 */
class RegistryServiceTest {

    private static final String KEY = "test-only-master-key-0123456789abcdef";

    /**
     * 直通的缓存桩：不真的缓存，但**记录 RedisJsonCache 收到的逻辑键**，并保证 loader 被调。
     *
     * <p>记的是**逻辑键**（不含前缀）—— 前缀由 {@link RedisJsonCache#get} 自己加，
     * 所以调用方带了前缀就会被记成「带了前缀的逻辑键」，那条断言正是用来钉这个的。
     */
    static class PassthroughCache extends RedisJsonCache {
        final List<String> keys = new ArrayList<>();
        PassthroughCache() { super(null, null); }
        @Override public <T> T get(String key, Duration ttl,
                                   tools.jackson.core.type.TypeReference<T> type,
                                   Supplier<T> loader) {
            keys.add(key);
            return loader.get();
        }
    }

    PassthroughCache cache = new PassthroughCache();

    PlatformCredentialMapper mapper = mock(PlatformCredentialMapper.class);
    CredentialCipher cipher = new CredentialCipher(KEY);

    /** 记录被调用了几次 —— 用来钉「匹配不到时零调用」 */
    final List<String> calls = new ArrayList<>();

    RegistryDialect spyDialect = new RegistryDialect() {
        @Override public String type() { return "HARBOR"; }
        @Override public List<String> suggest(CredentialRef c, String p) {
            calls.add("suggest:" + p);
            return List.of("harbor.example.com/proj/app");
        }
        @Override public BrowseResult browse(CredentialRef c, List<String> p) {
            calls.add("browse:" + p);
            return new BrowseResult(List.of(), List.of());
        }
    };

    RegistryService svc = new RegistryService(mapper, cipher,
            new RegistryDialectRegistry(List.of(spyDialect)), cache);

    private PlatformCredential row(String id, String name, String type, String endpoint, int enabled) {
        PlatformCredential c = new PlatformCredential();
        c.setId(id);
        c.setName(name);
        c.setType(type);
        c.setEndpoint(endpoint);
        c.setAuthType("BASIC");
        String json = "{\"username\":\"u\",\"secret\":\"p\"}";
        c.setSecretEncrypted(cipher.encrypt(json));
        c.setSecretFingerprint(CredentialCipher.fingerprint(json));
        c.setEnabled(enabled);
        return c;
    }

    // ---- 窄投影 ----

    @Test
    void connectionsProjectOnlyFourFieldsAndSkipDisabledAndGit() {
        when(mapper.selectAll()).thenReturn(List.of(
                row("c1", "harbor-prod", "HARBOR", "harbor.example.com", 1),
                row("c2", "harbor-old", "HARBOR", "old.example.com", 0),
                row("c3", "gitlab", "GIT", "https://gitlab.example.com", 1)));

        var items = svc.connections();

        assertThat(items).hasSize(1);
        assertThat(items.get(0).id()).isEqualTo("c1");
        assertThat(items.get(0).name()).isEqualTo("harbor-prod");
        assertThat(items.get(0).endpoint()).isEqualTo("harbor.example.com");
        // 只四个字段 —— 没有 username / secret / fingerprint / lastVerifyError
        assertThat(connectionViewFields()).doesNotContain("username", "secret", "fingerprint");
    }

    @Test
    void connectionsReturnsEmptyForGitOnly() {
        when(mapper.selectAll()).thenReturn(List.of(
                row("c3", "gitlab", "GIT", "https://gitlab.example.com", 1)));

        assertThat(svc.connections()).isEmpty();
    }

    // ---- 补全 ----

    @Test
    void suggestDispatchesToTheMatchedConnection() {
        when(mapper.selectAll()).thenReturn(List.of(row("c1", "harbor-prod", "HARBOR", "harbor.example.com", 1)));

        assertThat(svc.suggest("harbor.example.com/pro").items())
                .containsExactly("harbor.example.com/proj/app");
        assertThat(calls).containsExactly("suggest:harbor.example.com/pro");
    }

    @Test
    void suggestSendsNoRequestWhenNothingMatches() {
        // ⚠️ Review Focus 3
        when(mapper.selectAll()).thenReturn(List.of(row("c1", "harbor-prod", "HARBOR", "harbor.example.com", 1)));

        assertThat(svc.suggest("nginx:1.27").items()).isEmpty();
        assertThat(svc.suggest("unknown.example.com/x").items()).isEmpty();
        assertThat(calls).as("匹配不到连接时不得调用方言（即不得发请求）").isEmpty();
    }

    @Test
    void suggestIgnoresDisabledConnections() {
        when(mapper.selectAll()).thenReturn(List.of(row("c2", "harbor-old", "HARBOR", "harbor.example.com", 0)));

        assertThat(svc.suggest("harbor.example.com/pro").items()).isEmpty();
        assertThat(calls).isEmpty();
    }

    @Test
    void suggestReturnsEmptyForBlankInputWithoutTouchingTheMapper() {
        assertThat(svc.suggest("   ").items()).isEmpty();
        assertThat(svc.suggest(null).items()).isEmpty();
        assertThat(calls).isEmpty();
    }

    // ---- 浏览 ----

    @Test
    void browseDispatchesToTheNamedConnection() {
        when(mapper.selectById("c1")).thenReturn(row("c1", "harbor-prod", "HARBOR", "harbor.example.com", 1));

        RegistryBrowseRequest req = new RegistryBrowseRequest();
        req.setCredentialId("c1");
        req.setPath(List.of("myproj"));

        svc.browse(req);

        assertThat(calls).containsExactly("browse:[myproj]");
    }

    @Test
    void browseTreatsNullPathAsTheFirstLevel() {
        when(mapper.selectById("c1")).thenReturn(row("c1", "harbor-prod", "HARBOR", "harbor.example.com", 1));

        RegistryBrowseRequest req = new RegistryBrowseRequest();
        req.setCredentialId("c1");
        req.setPath(null);

        svc.browse(req);

        assertThat(calls).containsExactly("browse:[]");
    }

    @Test
    void browseRejectsUnknownOrDisabledConnection() {
        when(mapper.selectById("nope")).thenReturn(null);

        RegistryBrowseRequest req = new RegistryBrowseRequest();
        req.setCredentialId("nope");

        assertThatThrownBy(() -> svc.browse(req)).isInstanceOf(CloudPlatformException.class);
        assertThat(calls).isEmpty();
    }

    @Test
    void browseRejectsAGitConnection() {
        when(mapper.selectById("c3")).thenReturn(row("c3", "gitlab", "GIT", "https://gitlab.example.com", 1));

        RegistryBrowseRequest req = new RegistryBrowseRequest();
        req.setCredentialId("c3");

        assertThatThrownBy(() -> svc.browse(req)).isInstanceOf(CloudPlatformException.class);
        assertThat(calls).isEmpty();
    }

    // ---- 缓存（只包 suggest） ----

    @Test
    void suggestGoesThroughTheCacheKeyedByConnectionAndPrefix() {
        when(mapper.selectAll()).thenReturn(List.of(row("c1", "harbor-prod", "HARBOR", "harbor.example.com", 1)));

        svc.suggest("harbor.example.com/pro");

        assertThat(cache.keys).hasSize(1);
        // ⚠️ 断言的是 RedisJsonCache **收到的逻辑键**（它自己会加 `platform:cache:` 前缀）。
        // 调用方**不能**自带前缀 —— 带了实际键会变成 platform:cache:platform:cache:…（双前缀），
        // 缓存永不命中且没人看得出来。所以这里两件事一起钉：键的构成 + 不含前缀。
        assertThat(cache.keys.get(0)).isEqualTo("registry:suggest:c1:harbor.example.com/pro");
        assertThat(cache.keys.get(0)).doesNotContain("platform:cache:");
    }

    @Test
    void browseDoesNotUseTheCache() {
        when(mapper.selectById("c1")).thenReturn(row("c1", "harbor-prod", "HARBOR", "harbor.example.com", 1));

        RegistryBrowseRequest req = new RegistryBrowseRequest();
        req.setCredentialId("c1");
        svc.browse(req);

        assertThat(cache.keys).isEmpty();
    }

    private static List<String> connectionViewFields() {
        return Arrays.stream(RegistryConnectionView.class.getDeclaredFields())
                .map(Field::getName).toList();
    }
}
