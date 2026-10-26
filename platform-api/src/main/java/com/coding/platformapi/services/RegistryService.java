package com.coding.platformapi.services;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.data.mapper.system.PlatformCredentialMapper;
import com.coding.data.models.system.PlatformCredential;
import com.coding.platformapi.cache.RedisJsonCache;
import com.coding.platformapi.credential.CredentialCipher;
import com.coding.platformapi.credential.CredentialSecret;
import com.coding.platformapi.models.RegistryBrowseRequest;
import com.coding.platformapi.models.RegistryConnectionView;
import com.coding.platformapi.models.RegistrySuggestResponse;
import com.coding.platformapi.registry.BrowseResult;
import com.coding.platformapi.registry.BuiltinRegistry;
import com.coding.platformapi.registry.CredentialRef;
import com.coding.platformapi.registry.RegistryDialectRegistry;
import com.coding.platformapi.registry.RegistrySuggestExecutor;
import com.coding.platformapi.registry.RegistryTargetResolver;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * 镜像仓库的三个只读入口。设计见 docs/superpowers/specs/2026-10-14-registry-design.md
 * 与 docs/superpowers/specs/2026-10-14-registry-dockerhub-design.md。
 *
 * <h3>本类守的三条边界</h3>
 * <ol>
 *   <li><b>出站地址只由来源的 endpoint 拼</b>（或方言自己写死的官方地址）—— 用户输入只用于**匹配**
 *       （{@link RegistryTargetResolver}），绝不参与构造目标地址。</li>
 *   <li><b>带 host 匹配不到就零请求</b> —— 直接返回空，不下沉到方言。
 *       ⚠️ <b>无 host 的输入不适用这条</b>：它要问**全部**来源（聚合，见 {@link #suggest} 的 javadoc）。</li>
 *   <li><b>「有哪些来源」只有 {@link #allSources} 一个真相源</b> —— 内置档与凭据行的分叉只在那里
 *       发生一次，下游只认 {@link Source} 这个形状。</li>
 * </ol>
 *
 * <p>本类**不标审计**：三个入口都是高频只读（补全甚至是每按键一次），逐次写审计等于淹掉审计表，
 * 且它不改任何状态。见 spec §8。
 */
@Slf4j
@Service
public class RegistryService {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final PlatformCredentialMapper mapper;

    private final CredentialCipher cipher;

    private final RegistryDialectRegistry dialects;

    private final RedisJsonCache cache;

    /** 补全的并发出口 —— 无 host 时一次输入要让 N 个来源各查一遍（spec §2.5）。 */
    private final RegistrySuggestExecutor suggestExecutor;

    /**
     * 内置的 Docker Hub 档开不开（{@code platform.registry.dockerhub.enabled}，默认 true）。
     *
     * <p>⚠️ <b>做成构造参数而不是 {@code @Value} 字段</b>：测试里直接 {@code new} 构造
     * （没有 Spring），字段注入会拿到 {@code false} 而把内置档悄悄关掉，症状是「补全没候选」；
     * 而反射塞值又让「这条用例到底开没开内置档」看不出来。写成参数就一目了然。
     */
    private final boolean dockerHubEnabled;

    public RegistryService(PlatformCredentialMapper mapper,
                           CredentialCipher cipher,
                           RegistryDialectRegistry dialects,
                           RedisJsonCache cache,
                           RegistrySuggestExecutor suggestExecutor,
                           @Value("${platform.registry.dockerhub.enabled:true}") boolean dockerHubEnabled) {
        this.mapper = mapper;
        this.cipher = cipher;
        this.dialects = dialects;
        this.cache = cache;
        this.suggestExecutor = suggestExecutor;
        this.dockerHubEnabled = dockerHubEnabled;
    }

    /**
     * 浏览页的连接下拉：**只四个字段**。列的是「本次可用的镜像仓库来源」——
     * 内置档（开关开着才有）+ 启用的镜像仓库凭据。
     */
    public List<RegistryConnectionView> connections() {
        return connections(null);
    }

    public List<RegistryConnectionView> connections(String hatTenantId) {
        return allSources(hatTenantId).stream()
                .map(s -> new RegistryConnectionView(s.id(), s.name(), s.type(), s.endpoint()))
                .toList();
    }

    /**
     * 补全。
     *
     * <h4>匹配模型（S3.1 的契约在这里被反转）</h4>
     * <ul>
     *   <li><b>带 host</b> → 只问匹配上的那一个来源（匹配不上则零请求）；</li>
     *   <li><b>不带 host</b>（{@code nginx} / {@code myteam/app}）→ <b>所有来源都问</b>，
     *       结果按来源名排序后合并。</li>
     * </ul>
     * S3.1 时无 host 一律返回空，因为当时只有 Harbor 一档、无法确定该归哪条连接 ——
     * 结论没错，但代价是 Docker Hub 档只能在用户手打 {@code docker.io/} 时生效，等于白做。
     * 聚合把「猜哪条」换成「都给」，那个只能猜的歧义就没有了。
     */
    public RegistrySuggestResponse suggest(String imagePrefix) {
        return suggest(imagePrefix, null);
    }

    public RegistrySuggestResponse suggest(String imagePrefix, String hatTenantId) {
        if (!StringUtils.hasText(imagePrefix)) {
            return new RegistrySuggestResponse(List.of());
        }
        String prefix = imagePrefix.trim();
        List<Source> sources = allSources(hatTenantId);
        if (sources.isEmpty()) {
            // ⚠️ 这条以前是**静默**的：「一个来源都没有」与「有来源但对端没回应」在日志里
            // 长得一模一样（后者至少还有 WARN），排查时只能去查库 —— 2026-10-14 真发生过。
            // 只在真的零来源时记：内置档默认开着，所以它只在被显式关掉且没有凭据时出现。
            log.info("镜像补全：当前没有任何镜像仓库来源"
                    + "（内置 Docker Hub 被 platform.registry.dockerhub.enabled 关掉，"
                    + "且没有启用的镜像仓库凭据）");
            return new RegistrySuggestResponse(List.of());
        }

        List<Source> targets = pickTargets(prefix, sources);
        if (targets.isEmpty()) {
            return new RegistrySuggestResponse(List.of());
        }
        // 排序在**发请求之前**做好 —— 结果按同一顺序拼回来，不需要二次匹配
        List<Source> ordered = targets.stream()
                .sorted(Comparator.comparing(Source::name,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();

        List<Callable<List<String>>> tasks = ordered.stream()
                .map(s -> (Callable<List<String>>) () -> cachedSuggest(s, prefix))
                .toList();
        List<List<String>> results = suggestExecutor.allOf(tasks);

        List<RegistrySuggestResponse.Item> items = new ArrayList<>();
        for (int i = 0; i < ordered.size(); i++) {
            String name = ordered.get(i).name();
            for (String value : results.get(i)) {
                items.add(new RegistrySuggestResponse.Item(value, name));
            }
        }
        return new RegistrySuggestResponse(items);
    }

    /**
     * 挑本次参与补全的来源。
     *
     * <p>⚠️ <b>带 host 且匹配不上时返回空列表 = 零请求</b>，这是 spec §2.6 的硬边界：
     * 用户输入里的 host 只用于匹配，**绝不参与构造目标地址**。
     */
    private List<Source> pickTargets(String prefix, List<Source> sources) {
        if (RegistryTargetResolver.hostOf(prefix).isEmpty()) {
            return sources;                               // 无 host：全给（聚合的意义所在）
        }
        CredentialRef hit = RegistryTargetResolver.match(prefix,
                sources.stream().map(Source::plainRef).toList());
        if (hit == null) {
            return List.of();
        }
        // 匹配层用 id 回报命中。内置档的 id 是合成值（builtin:dockerhub），与凭据 id 不会撞
        return sources.stream().filter(s -> s.id().equals(hit.id())).toList();
    }

    /**
     * 单个来源：解密 → 方言 → 缓存。
     *
     * <p>⚠️ <b>只解密参与本次查询的那一个</b> —— S3.1 终审修过一次放大效应：
     * 以前在每次 suggest（每按键一次）里解密**全部**凭据，任何一条密文解不开
     * （例如主密钥换过）就让**所有人**的补全全挂。现在解不开只会让那一个没候选，
     * 而聚合之后这一点更重要：一个坏来源不该拖垮别人的候选。
     *
     * <p>⚠️ key 传的是**逻辑键**：{@code RedisJsonCache} 自己会加 {@code platform:cache:} 前缀，
     * 这里再带一次就会变成双前缀 —— 缓存永不命中，而且没人看得出来。
     */
    private List<String> cachedSuggest(Source source, String prefix) {
        CredentialRef cred = toRef(source);
        return cache.get("registry:suggest:" + cred.id() + ":" + prefix,
                Duration.ofMinutes(2),
                new TypeReference<>() { },
                () -> dialects.of(cred.type()).suggest(cred, prefix));
    }

    /** 浏览。**不吞错** —— 用户在页面上主动点了一层，失败要说（spec §5.3）。 */
    public BrowseResult browse(RegistryBrowseRequest req) {
        return browse(req, null);
    }

    public BrowseResult browse(RegistryBrowseRequest req, String hatTenantId) {
        // ⚠️ 在 allSources() 里找，**不是** mapper.selectById —— 内置档没有凭据行，
        // 它是用合成 id（builtin:dockerhub）从下拉里点进来的
        Source source = allSources(hatTenantId).stream()
                .filter(s -> s.id().equals(req.getCredentialId()))
                .findFirst()
                .orElseThrow(() -> new CloudPlatformException(EnumResponseType.CREDENTIAL_NOT_EXIST,
                        "镜像仓库连接不存在或已停用"));
        List<String> path = req.getPath() == null ? List.of() : req.getPath();
        return dialects.of(source.type()).browse(toRef(source), path);
    }

    /**
     * 本次补全 / 浏览要问的**全部来源**。
     *
     * <p>⚠️ <b>这是「有哪些来源」的唯一真相源</b> —— 内置档与凭据行的分叉只在这里发生一次，
     * 下游（聚合 / 并发 / 排序 / 候选带来源名 / 浏览）只认 {@link Source} 这个形状，
     * 不需要知道有「内置」这回事。散成两处就是第二个真相源
     * （S3.1 终审删掉 {@code REGISTRY_TYPES} 时反对的那个形状）。
     */
    private List<Source> allSources(String hatTenantId) {
        List<Source> out = new ArrayList<>();
        if (dockerHubEnabled) {
            out.add(Source.builtin(BuiltinRegistry.DOCKER_HUB));
        }
        // 凭据表里启用的镜像仓库行。**不解密** —— 明文只在真正要用时（toRef）才解。
        // 租户帽下只给「自己的 + 手工平台级」——见 CredentialService.visibleTo（Harbor 租户隔离）
        mapper.selectAll().stream()
                .filter(c -> isRegistry(c) && enabled(c))
                .filter(c -> CredentialService.visibleTo(c, hatTenantId))
                .map(Source::from)
                .forEach(out::add);
        return out;
    }

    /**
     * 一个镜像仓库**来源** —— 内置档，或者一行凭据。
     *
     * @param ciphertext 凭据行的密文；**内置档恒为 null**（它没有凭据可言）
     */
    private record Source(String id, String name, String type, String endpoint, String ciphertext) {

        static Source builtin(BuiltinRegistry b) {
            return new Source(b.id(), b.displayName(), b.type(), b.endpoint(), null);
        }

        static Source from(PlatformCredential c) {
            return new Source(c.getId(), c.getName(), c.getType(), c.getEndpoint(),
                    c.getSecretEncrypted());
        }

        /** 只带匹配需要的字段（id / name / type / endpoint）的轻量 ref，**不含明文**。 */
        CredentialRef plainRef() {
            return new CredentialRef(id, name, type, endpoint, null, null);
        }
    }

    /**
     * 来源 → 带明文的 ref。
     *
     * <p>⚠️ <b>没有密文的来源有两个</b>：内置档，以及匿名的凭据行（{@code auth_type=NONE}）。
     * 两者都必须容忍 —— 否则「只允许匿名」的 Docker Hub 在补全与浏览上**整条死掉**，
     * 而补全那条还是**静默**的（异常被执行器吞成空列表，只剩一条 WARN）。2026-10-14 真发生过。
     */
    private CredentialRef toRef(Source source) {
        if (!StringUtils.hasText(source.ciphertext())) {
            return new CredentialRef(source.id(), source.name(), source.type(), source.endpoint(),
                    null, null);
        }
        CredentialSecret s = fromJson(cipher.decrypt(source.ciphertext()));
        return new CredentialRef(source.id(), source.name(), source.type(), source.endpoint(),
                s.getUsername(), s.getSecret());
    }

    /**
     * 是不是一个镜像仓库类的凭据行。
     *
     * <p>⚠️ **问分派表，不自己维护一份 type 清单** —— 手写清单是第二个真相源，
     * 加一档时忘了改它会「全测绿但新档静默失效」。见 {@link RegistryDialectRegistry#supports}。
     *
     * <p>⚠️ 2026-10-15 起**不再排掉内置档的 type**：Docker Hub 现在可以同时有内置档（匿名、公共）
     * 与凭据行（带 PAT、只答"我名下"），两者答的东西不同，所以并存不是重复而是各答一半。
     * 「谁答什么」由方言按这条 ref **有没有凭据**判（见 {@code DockerHubDialect}）。
     */
    private boolean isRegistry(PlatformCredential c) {
        return dialects.supports(c.getType());
    }

    private static boolean enabled(PlatformCredential c) {
        return c.getEnabled() != null && c.getEnabled() == 1;
    }

    private static CredentialSecret fromJson(String json) {
        try {
            return JSON.readValue(json, CredentialSecret.class);
        } catch (Exception e) {
            throw new CloudPlatformException(EnumResponseType.CREDENTIAL_CIPHERTEXT_INVALID,
                    "凭据内容无法解析（密文损坏或主密钥与写入时不一致）");
        }
    }
}
