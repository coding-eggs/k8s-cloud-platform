package com.coding.platformapi.services;

import com.coding.common.exception.CloudPlatformException;
import com.coding.data.mapper.system.PlatformCredentialMapper;
import com.coding.data.models.system.PlatformCredential;
import com.coding.platformapi.cache.RedisJsonCache;
import com.coding.platformapi.credential.CredentialCipher;
import com.coding.platformapi.models.RegistryBrowseRequest;
import com.coding.platformapi.models.RegistryConnectionView;
import com.coding.platformapi.models.RegistrySuggestResponse;
import com.coding.platformapi.registry.BrowseResult;
import com.coding.platformapi.registry.CredentialRef;
import com.coding.platformapi.registry.ProbeSpec;
import com.coding.platformapi.registry.RegistryDialect;
import com.coding.platformapi.registry.RegistryDialectRegistry;
import com.coding.platformapi.registry.RegistrySuggestExecutor;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
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
        /** 同上：loader 在虚拟线程里跑，这个列表也要线程安全。 */
        final List<String> keys = java.util.Collections.synchronizedList(new ArrayList<>());
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
    com.coding.data.mapper.system.PlatformRegistryProjectMapper projectMapper =
            mock(com.coding.data.mapper.system.PlatformRegistryProjectMapper.class);
    CredentialCipher cipher = new CredentialCipher(KEY);

    /**
     * 记录被调用了几次 —— 用来钉「匹配不到时零调用」。
     *
     * <p>⚠️ **必须是线程安全的**：{@code RegistrySuggestExecutor} 是**并发**跑各连接的
     * （虚拟线程），而普通 {@code ArrayList.add} 的 {@code size++} 不是原子操作 ——
     * 两条线程同时加会互相覆盖，于是「两条连接都被问了」偶发地只记下一条。
     * 表现是这一类的用例随机挂在 `inputWithoutAHostAsksEveryConnection` /
     * `resultsAreMergedAndSortedByConnectionName`（都是两条连接），而且日志里**一条 warn 都没有**
     * （任务确实跑了，丢的是记录）。2026-10-15 全量跑时抓到。
     */
    final List<String> calls = java.util.Collections.synchronizedList(new ArrayList<>());

    RegistryDialect spyDialect = new RegistryDialect() {
        @Override public String type() { return "HARBOR"; }
        @Override public Set<String> authTypes() { return Set.of("BASIC", "NONE"); }
        @Override public ProbeSpec probeSpec(CredentialRef c) {
            return new ProbeSpec("https://harbor.example.com/v2/", "该地址不是 registry");
        }
        @Override public List<String> suggest(CredentialRef c, String p) {
            calls.add("suggest:" + p);
            return List.of("harbor.example.com/proj/app");
        }
        @Override public BrowseResult browse(CredentialRef c, List<String> p) {
            calls.add("browse:" + p);
            return new BrowseResult(List.of(), List.of());
        }
    };

    /** 显式带 builtin 开关 —— 「这条用例到底开没开内置 Docker Hub」必须一眼看得出来。 */
    private RegistryService service(RegistryDialectRegistry d, boolean builtin) {
        return new RegistryService(mapper, projectMapper, cipher, d, cache,
                new RegistrySuggestExecutor(), builtin);
    }

    RegistryService svc = service(new RegistryDialectRegistry(List.of(spyDialect)), false);

    /**
     * 回显自己 endpoint 的方言 —— 聚合测试要能分辨「这条候选是哪条连接给的」。
     * {@link #spyDialect} 对所有连接返回同一个串，分辨不出来。
     */
    private RegistryDialect echoDialectFor(String type) {
        return new RegistryDialect() {
        @Override public String type() { return type; }
        @Override public Set<String> authTypes() { return Set.of("BASIC", "NONE"); }
        @Override public ProbeSpec probeSpec(CredentialRef c) {
            return new ProbeSpec("https://harbor.example.com/v2/", "该地址不是 registry");
        }
        @Override public List<String> suggest(CredentialRef c, String p) {
            calls.add("suggest:" + c.id() + ":" + p);
            return List.of(c.endpoint() + "/x");
        }
        @Override public BrowseResult browse(CredentialRef c, List<String> p) {
            calls.add("browse:" + c.id());
            return new BrowseResult(List.of(), List.of());
        }
        };
    }

    RegistryService echoSvc = service(
            new RegistryDialectRegistry(List.of(echoDialectFor("HARBOR"), echoDialectFor("DOCKERHUB"))),
            false);

    /** 开着内置 Docker Hub 的那一个 —— 内置档的行为单独用几条用例钉。 */
    RegistryService withBuiltin = service(
            new RegistryDialectRegistry(List.of(echoDialectFor("HARBOR"), echoDialectFor("DOCKERHUB"))),
            true);

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

    /** 匿名的行：auth_type=NONE 且**两列密文都是 null** —— 这正是表注释预留的形态。 */
    private PlatformCredential anonymousRow(String id, String name, String type, String endpoint) {
        PlatformCredential c = new PlatformCredential();
        c.setId(id);
        c.setName(name);
        c.setType(type);
        c.setEndpoint(endpoint);
        c.setAuthType("NONE");
        c.setEnabled(1);
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

    /**
     * ⚠️ 护栏：**加一档 = 加枚举值 + 实现类 + UI 选项，不该还要改 RegistryService**。
     *
     * <p>本类曾经手写了一份 `REGISTRY_TYPES = List.of("HARBOR")` 作为第二个真相源 ——
     * 加一档时忘了改它，**全测绿但新档静默失效**（连接不出现在下拉里、补全永不匹配、browse 判「不存在」）。
     * 这条测试用「注册一个没人告诉过 RegistryService 的新方言」来钉住那条不变量。
     */
    @Test
    void aNewlyImplementedDialectIsRecognizedWithoutTouchingThisService() {
        RegistryDialect gitea = new RegistryDialect() {
            @Override public String type() { return "GITEA"; }
            @Override public Set<String> authTypes() { return Set.of("BASIC", "NONE"); }
            @Override public ProbeSpec probeSpec(CredentialRef c) {
                return new ProbeSpec("https://gitea.example.com/v2/", "该地址不是 registry");
            }
            @Override public List<String> suggest(CredentialRef c, String p) {
                calls.add("suggest:" + p);
                return List.of();
            }
            @Override public BrowseResult browse(CredentialRef c, List<String> p) {
                return new BrowseResult(List.of(), List.of());
            }
        };
        RegistryService svc2 = service(new RegistryDialectRegistry(List.of(spyDialect, gitea)), false);
        when(mapper.selectAll()).thenReturn(List.of(row("c9", "gitea-a", "GITEA", "gitea.example.com", 1)));

        assertThat(svc2.connections()).extracting(RegistryConnectionView::id).containsExactly("c9");
        assertThat(svc2.suggest("gitea.example.com/x").items()).isEmpty(); // 匹配得上，只是方言返回空
        assertThat(calls).as("新方言必须被分派到（说明它被认成了镜像仓库类型）").containsExactly("suggest:gitea.example.com/x");
    }

    // ---- 补全 ----

    @Test
    void suggestDispatchesToTheMatchedConnectionAndTagsEachCandidateWithItsSource() {
        when(mapper.selectAll()).thenReturn(List.of(row("c1", "harbor-prod", "HARBOR", "harbor.example.com", 1)));

        assertThat(svc.suggest("harbor.example.com/pro").items())
                .singleElement()
                .satisfies(i -> {
                    assertThat(i.value()).isEqualTo("harbor.example.com/proj/app");
                    // 来源连接名是唯一无法从候选串里推出来的信息：
                    // Harbor 的候选串自带 host，但「是哪个连接」推不出来。聚合之后必须有。
                    assertThat(i.connectionName()).isEqualTo("harbor-prod");
                });
        assertThat(calls).containsExactly("suggest:harbor.example.com/pro");
    }

    @Test
    void suggestSendsNoRequestWhenNothingMatches() {
        // ⚠️ Review Focus 3
        when(mapper.selectAll()).thenReturn(List.of(row("c1", "harbor-prod", "HARBOR", "harbor.example.com", 1)));

        // ⚠️ 契约在 2026-10-14 变了：**无 host 的输入现在要问全部连接**（聚合），
        // 所以「匹配不上」只能用**带 host** 的输入来表达。断言本身（零请求）没变。
        assertThat(svc.suggest("other.example.com/nginx:1.27").items()).isEmpty();
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

    /**
     * ⚠️ 一条解不开的密文（例如主密钥换过）**不该让所有人的补全全挂**。
     *
     * <p>本类曾经在每次 suggest（每按键一次）里解密**全部**启用的凭据 ——
     * 任何一条坏密文都会让 `suggest` 整个抛错；配合前端把业务错弹成红条，就是每打一个字一条红条。
     * 修法是**先按 host 匹配（不需要明文），只对命中的那条解密**。
     */
    @Test
    void oneBrokenCiphertextDoesNotBreakCompletionForEveryoneElse() {
        PlatformCredential broken = row("cb", "broken", "HARBOR", "broken.example.com", 1);
        broken.setSecretEncrypted("v1:bm90LWEtcmVhbC1jaXBoZXJ0ZXh0");
        when(mapper.selectAll()).thenReturn(List.of(
                broken,
                row("c1", "harbor-prod", "HARBOR", "harbor.example.com", 1)));

        assertThat(svc.suggest("harbor.example.com/pro").items())
                .extracting(RegistrySuggestResponse.Item::value)
                .containsExactly("harbor.example.com/proj/app");
    }

    // ---- 跨连接聚合（S3.1 的「无 host 一律空候选」在这里被反转） ----

    @Test
    void inputWithAHostOnlyAsksTheMatchingConnection() {
        when(mapper.selectAll()).thenReturn(List.of(
                row("c1", "harbor-prod", "HARBOR", "harbor.example.com", 1),
                row("c2", "other", "HARBOR", "other.example.com", 1)));

        echoSvc.suggest("harbor.example.com/proj/ap");

        assertThat(calls).containsExactly("suggest:c1:harbor.example.com/proj/ap");   // c2 一次都没被问
    }

    @Test
    void inputWithAHostThatMatchesNothingMakesZeroRequests() {
        when(mapper.selectAll()).thenReturn(List.of(
                row("c1", "harbor-prod", "HARBOR", "harbor.example.com", 1)));

        assertThat(echoSvc.suggest("other.example.com/ng").items()).isEmpty();
        assertThat(calls).as("零请求，不是「发了但返回空」").isEmpty();
    }

    @Test
    void inputWithoutAHostAsksEveryConnection() {
        when(mapper.selectAll()).thenReturn(List.of(
                row("c1", "harbor-prod", "HARBOR", "harbor.example.com", 1),
                row("c2", "other", "HARBOR", "other.example.com", 1)));

        echoSvc.suggest("ng");

        assertThat(calls).containsExactlyInAnyOrder("suggest:c1:ng", "suggest:c2:ng");
    }

    @Test
    void inputWithoutAHostWithNoConnectionsMakesZeroRequests() {
        when(mapper.selectAll()).thenReturn(List.of());

        assertThat(svc.suggest("ng").items()).isEmpty();
        assertThat(calls).isEmpty();
    }

    @Test
    void resultsAreMergedAndSortedByConnectionName() {
        when(mapper.selectAll()).thenReturn(List.of(
                row("c2", "zeta", "HARBOR", "zeta.example.com", 1),
                row("c1", "alpha", "HARBOR", "alpha.example.com", 1)));

        assertThat(echoSvc.suggest("ng").items())
                .extracting(RegistrySuggestResponse.Item::connectionName)
                .containsExactly("alpha", "zeta");     // 按连接名，不按 mapper 的返回顺序
    }

    @Test
    void oneFailingConnectionDoesNotSuppressTheOthers() {
        // ⭐ Review Focus #1：一条连接的密文解不开（例如主密钥换过），其余候选必须照常返回。
        // S3.1 终审修过一次放大效应 —— 那时是「解不开就让所有人的补全全挂」，
        // 改成「只解密命中的那一条」之后，要保证「只影响它自己」在聚合下仍然成立。
        PlatformCredential broken = row("bad", "broken", "HARBOR", "broken.example.com", 1);
        broken.setSecretEncrypted("v1:bm90LWEtcmVhbC1jaXBoZXJ0ZXh0");   // 本测试的 cipher 解不开
        PlatformCredential good = row("ok", "good", "HARBOR", "good.example.com", 1);
        good.setSecretEncrypted(cipher.encrypt("{\"username\":\"u\",\"secret\":\"p\"}"));
        when(mapper.selectAll()).thenReturn(List.of(broken, good));

        assertThat(echoSvc.suggest("ng").items())
                .extracting(RegistrySuggestResponse.Item::value)
                .containsExactly("good.example.com/x");
    }

    @Test
    void anonymousConnectionStillProducesCandidates() {
        // ⭐ 匿名行**不写密文**（CredentialService 有意为之，列注释当初就留了这个位）——
        // toRef 必须容忍 null 密文。不容忍的话，Docker Hub 这一档（只允许匿名）在补全上
        // **整条死掉且是静默的**：异常被执行器吞成空列表，日志里一条 WARN。
        when(mapper.selectAll()).thenReturn(List.of(
                anonymousRow("c1", "hub", "HARBOR", "harbor.example.com")));

        assertThat(echoSvc.suggest("ng").items())
                .extracting(RegistrySuggestResponse.Item::value)
                .containsExactly("harbor.example.com/x");
    }

    @Test
    void anonymousConnectionCanBeBrowsed() {
        // 同上：browse 在调方言**之前**就 toRef，所以匿名连接连第一层都进不去
        when(mapper.selectAll()).thenReturn(
                List.of(anonymousRow("c1", "hub", "HARBOR", "harbor.example.com")));

        RegistryBrowseRequest req = new RegistryBrowseRequest();
        req.setCredentialId("c1");

        assertThat(echoSvc.browse(req).children()).isEmpty();   // 方言桩返回空，但**不能抛**
    }

    // ---- 内置档（Docker Hub，2026-10-14 改成不进凭据表） ----

    @Test
    void builtinDockerHubAppearsInConnectionsWithoutAnyCredentialRow() {
        // 一条凭据都没有，下拉里也该有它 —— 这正是「不用建连接」的意思
        when(mapper.selectAll()).thenReturn(List.of());

        assertThat(withBuiltin.connections())
                .extracting(RegistryConnectionView::id, RegistryConnectionView::name,
                        RegistryConnectionView::type, RegistryConnectionView::endpoint)
                .containsExactly(tuple("builtin:dockerhub", "Docker Hub", "DOCKERHUB", "docker.io"));
    }

    @Test
    void builtinDockerHubParticipatesInHostlessSuggest() {
        when(mapper.selectAll()).thenReturn(List.of());

        // 无 host → 内置档也要被问到，候选的 connectionName 是它的显示名
        assertThat(withBuiltin.suggest("ng").items())
                .singleElement()
                .satisfies(i -> {
                    assertThat(i.connectionName()).isEqualTo("Docker Hub");
                    assertThat(calls).containsExactly("suggest:builtin:dockerhub:ng");
                });
    }

    @Test
    void builtinDockerHubMatchesAnExplicitDockerIoHost() {
        when(mapper.selectAll()).thenReturn(List.of());

        // 带 host 的显式写法：`docker.io` 要命中内置档（三个等效域名的归一化在匹配层）
        assertThat(withBuiltin.suggest("docker.io/ng").items())
                .singleElement()
                .satisfies(i -> assertThat(i.connectionName()).isEqualTo("Docker Hub"));
        assertThat(calls).containsExactly("suggest:builtin:dockerhub:docker.io/ng");
    }

    @Test
    void builtinDockerHubCanBeBrowsedByItsSyntheticId() {
        when(mapper.selectAll()).thenReturn(List.of());

        RegistryBrowseRequest req = new RegistryBrowseRequest();
        req.setCredentialId("builtin:dockerhub");     // ← 合成 id，不是 platform_credential.id

        // 走到方言即可 —— 用例的价值在「不抛」：以前这里走 mapper.selectById，必然 null → 抛
        assertThat(withBuiltin.browse(req).children()).isEmpty();
        assertThat(calls).containsExactly("browse:builtin:dockerhub");
    }

    @Test
    void anUnknownSourceIdStillFailsLoudlyOnBrowse() {
        when(mapper.selectAll()).thenReturn(List.of());

        RegistryBrowseRequest req = new RegistryBrowseRequest();
        req.setCredentialId("builtin:nope");
        req.setPath(List.of());

        assertThatThrownBy(() -> withBuiltin.browse(req))
                .isInstanceOf(CloudPlatformException.class);
    }

    @Test
    void disablingBuiltinDockerHubRemovesItFromEverywhere() {
        when(mapper.selectAll()).thenReturn(List.of());

        assertThat(echoSvc.connections()).isEmpty();
        assertThat(echoSvc.suggest("ng").items()).isEmpty();
        assertThat(calls).isEmpty();                   // 零来源 → 一次请求都不发

        RegistryBrowseRequest req = new RegistryBrowseRequest();
        req.setCredentialId("builtin:dockerhub");
        assertThatThrownBy(() -> echoSvc.browse(req)).isInstanceOf(CloudPlatformException.class);
    }

    @Test
    void aDockerHubCredentialRowIsASecondSourceNextToTheBuiltin() {
        // 2026-10-15 起两者**并存**：内置档（匿名）只答公共镜像，凭据行（带 PAT）只答"我名下" ——
        // 它们答的东西不同，所以下拉里出现两条不是重复，而是各答一半。
        // （2026-10-14 曾把同类型凭据行整条挡掉；那次裁定的反例见 DockerHubDialect 的类注释。）
        when(mapper.selectAll()).thenReturn(List.of(
                row("c9", "dockerhub-release", "DOCKERHUB", "docker.io", 1)));

        assertThat(withBuiltin.connections())
                .extracting(RegistryConnectionView::id)
                .containsExactly("builtin:dockerhub", "c9");
    }

    // ---- 浏览 ----
    @Test
    void browseDispatchesToTheNamedConnection() {
        when(mapper.selectAll()).thenReturn(
                List.of(row("c1", "harbor-prod", "HARBOR", "harbor.example.com", 1)));

        RegistryBrowseRequest req = new RegistryBrowseRequest();
        req.setCredentialId("c1");
        req.setPath(List.of("myproj"));

        svc.browse(req);

        assertThat(calls).containsExactly("browse:[myproj]");
    }

    @Test
    void browseCarriesTheHarborProjectOfAPlatformManagedCredential() {
        // 项目级 robot 没有「列出全部 project」的权限，Harbor 的浏览第一层要用台账里的 project
        // （见 CredentialRef#project）—— 不带的话会去调 GET /projects 被 403
        when(mapper.selectAll()).thenReturn(
                List.of(row("c1", "Harbor t-acme@c1", "HARBOR", "http://harbor.example.com", 1)));
        com.coding.data.models.system.PlatformRegistryProject p =
                new com.coding.data.models.system.PlatformRegistryProject();
        p.setCredentialId("c1");
        p.setProjectName("t-acme");
        when(projectMapper.selectAll()).thenReturn(List.of(p));
        RegistryDialect capture = new RegistryDialect() {
            @Override public String type() { return "HARBOR"; }
            @Override public Set<String> authTypes() { return Set.of("BASIC", "NONE"); }
            @Override public ProbeSpec probeSpec(CredentialRef c) { return new ProbeSpec("x", "x"); }
            @Override public List<String> suggest(CredentialRef c, String prefix) { return List.of(); }
            @Override public BrowseResult browse(CredentialRef c, List<String> path) {
                calls.add("project:" + c.project());
                return new BrowseResult(List.of(), List.of());
            }
        };
        RegistryService svc = service(new RegistryDialectRegistry(List.of(capture)), false);

        RegistryBrowseRequest req = new RegistryBrowseRequest();
        req.setCredentialId("c1");
        svc.browse(req);

        assertThat(calls).containsExactly("project:t-acme");
    }

    @Test
    void browseTreatsNullPathAsTheFirstLevel() {
        when(mapper.selectAll()).thenReturn(
                List.of(row("c1", "harbor-prod", "HARBOR", "harbor.example.com", 1)));

        RegistryBrowseRequest req = new RegistryBrowseRequest();
        req.setCredentialId("c1");
        req.setPath(null);

        svc.browse(req);

        assertThat(calls).containsExactly("browse:[]");
    }

    @Test
    void browseRejectsUnknownOrDisabledConnection() {
        when(mapper.selectAll()).thenReturn(List.of());

        RegistryBrowseRequest req = new RegistryBrowseRequest();
        req.setCredentialId("nope");

        assertThatThrownBy(() -> svc.browse(req)).isInstanceOf(CloudPlatformException.class);
        assertThat(calls).isEmpty();
    }

    @Test
    void browseRejectsAGitConnection() {
        when(mapper.selectAll()).thenReturn(
                List.of(row("c3", "gitlab", "GIT", "https://gitlab.example.com", 1)));

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
        when(mapper.selectAll()).thenReturn(
                List.of(row("c1", "harbor-prod", "HARBOR", "harbor.example.com", 1)));

        RegistryBrowseRequest req = new RegistryBrowseRequest();
        req.setCredentialId("c1");
        svc.browse(req);

        assertThat(cache.keys).isEmpty();
    }

    @Test
    void tenantCannotBrowseWithAnotherTenantsCredential() {
        PlatformCredential other = row("c2", "harbor-other", "HARBOR", "harbor.example.com", 1);
        other.setTenantId("t-other");
        other.setSource("HARBOR");
        other.setUsableByPipelines(1);
        when(mapper.selectAll()).thenReturn(List.of(other));

        RegistryBrowseRequest req = new RegistryBrowseRequest();
        req.setCredentialId("c2");
        req.setPath(List.of());
        assertThatThrownBy(() -> svc.browse(req, "tenant-1"))
                .hasMessageContaining("不存在或已停用");
    }

    @Test
    void tenantCanBrowseWithItsOwnCredential() {
        PlatformCredential own = row("c2", "harbor-own", "HARBOR", "harbor.example.com", 1);
        own.setTenantId("tenant-1");
        own.setSource("HARBOR");
        own.setUsableByPipelines(1);
        when(mapper.selectAll()).thenReturn(List.of(own));

        RegistryBrowseRequest req = new RegistryBrowseRequest();
        req.setCredentialId("c2");
        req.setPath(List.of());
        assertThat(svc.browse(req, "tenant-1").children()).isEmpty();
    }

    private static List<String> connectionViewFields() {
        return Arrays.stream(RegistryConnectionView.class.getDeclaredFields())
                .map(Field::getName).toList();
    }
}
