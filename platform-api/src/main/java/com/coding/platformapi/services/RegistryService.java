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
import com.coding.platformapi.registry.CredentialRef;
import com.coding.platformapi.registry.RegistryDialectRegistry;
import com.coding.platformapi.registry.RegistrySuggestExecutor;
import com.coding.platformapi.registry.RegistryTargetResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
 * 镜像仓库的三个只读入口。设计见 docs/superpowers/specs/2026-10-14-registry-design.md。
 *
 * <h3>本类守的两条边界</h3>
 * <ol>
 *   <li><b>出站地址只由凭据的 endpoint 拼</b>（或方言自己写死的官方地址）—— 用户输入只用于**匹配**连接
 *       （{@link RegistryTargetResolver}），绝不参与构造目标地址。</li>
 *   <li><b>带 host 匹配不到就零请求</b> —— 直接返回空，不下沉到方言。
 *       ⚠️ <b>无 host 的输入不适用这条</b>：它要问**全部**启用的镜像仓库连接（聚合，见
 *       {@link #suggest} 的 javadoc）—— 那是 2026-10-14 有意反转 S3.1 的契约。</li>
 * </ol>
 *
 * <p>本类**不标审计**：三个入口都是高频只读（补全甚至是每按键一次），逐次写审计等于淹掉审计表，
 * 且它不改任何状态。见 spec §8。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RegistryService {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final PlatformCredentialMapper mapper;

    private final CredentialCipher cipher;

    private final RegistryDialectRegistry dialects;

    private final RedisJsonCache cache;

    /** 补全的并发出口 —— 无 host 时一次输入要让 N 条连接各查一遍（spec §2.5）。 */
    private final RegistrySuggestExecutor suggestExecutor;

    /** 浏览页的连接下拉：**只四个字段**，只列启用的镜像仓库类连接。 */
    public List<RegistryConnectionView> connections() {
        return enabledRegistryRows().stream()
                .map(c -> new RegistryConnectionView(c.getId(), c.getName(), c.getType(), c.getEndpoint()))
                .toList();
    }

    /**
     * 补全。
     *
     * <h4>匹配模型（S3.1 的契约在这里被反转）</h4>
     * <ul>
     *   <li><b>带 host</b> → 只问匹配上的那一条（匹配不上则零请求）；</li>
     *   <li><b>不带 host</b>（{@code nginx} / {@code myteam/app}）→ <b>所有启用的镜像仓库连接都问</b>，
     *       结果按连接名排序后合并。</li>
     * </ul>
     * S3.1 时无 host 一律返回空，因为当时只有 Harbor 一档、无法确定该归哪条连接 ——
     * 结论没错，但代价是 Docker Hub 档只能在用户手打 {@code docker.io/} 时生效，等于白做。
     * 聚合把「猜哪条」换成「都给」，那个只能猜的歧义就没有了。
     */
    public RegistrySuggestResponse suggest(String imagePrefix) {
        if (!StringUtils.hasText(imagePrefix)) {
            return new RegistrySuggestResponse(List.of());
        }
        String prefix = imagePrefix.trim();
        List<PlatformCredential> rows = enabledRegistryRows();
        if (rows.isEmpty()) {
            return new RegistrySuggestResponse(List.of());
        }

        List<PlatformCredential> targets = pickTargets(prefix, rows);
        if (targets.isEmpty()) {
            return new RegistrySuggestResponse(List.of());
        }
        // 排序在**发请求之前**做好 —— 结果按同一顺序拼回来，不需要二次匹配
        List<PlatformCredential> ordered = targets.stream()
                .sorted(Comparator.comparing(PlatformCredential::getName,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();

        List<Callable<List<String>>> tasks = ordered.stream()
                .map(c -> (Callable<List<String>>) () -> cachedSuggest(c, prefix))
                .toList();
        List<List<String>> results = suggestExecutor.allOf(tasks);

        List<RegistrySuggestResponse.Item> items = new ArrayList<>();
        for (int i = 0; i < ordered.size(); i++) {
            String name = ordered.get(i).getName();
            for (String value : results.get(i)) {
                items.add(new RegistrySuggestResponse.Item(value, name));
            }
        }
        return new RegistrySuggestResponse(items);
    }

    /**
     * 挑本次参与补全的连接。
     *
     * <p>⚠️ <b>带 host 且匹配不上时返回空列表 = 零请求</b>，这是 spec §2.6 的硬边界：
     * 用户输入里的 host 只用于匹配，**绝不参与构造目标地址**。
     */
    private List<PlatformCredential> pickTargets(String prefix, List<PlatformCredential> rows) {
        if (RegistryTargetResolver.hostOf(prefix).isEmpty()) {
            return rows;                                  // 无 host：全给（聚合的意义所在）
        }
        CredentialRef hit = RegistryTargetResolver.match(prefix,
                rows.stream().map(RegistryService::toPlainRef).toList());
        if (hit == null) {
            return List.of();
        }
        return rows.stream().filter(r -> r.getId().equals(hit.id())).toList();
    }

    /**
     * 单条连接：解密 → 方言 → 缓存。
     *
     * <p>⚠️ <b>只解密参与本次查询的那一条</b> —— S3.1 终审修过一次放大效应：
     * 以前在每次 suggest（每按键一次）里解密**全部**凭据，任何一条密文解不开
     * （例如主密钥换过）就让**所有人**的补全全挂。现在解不开只会让那一条没候选，
     * 而聚合之后这一点更重要：一条坏连接不该拖垮别人的候选。
     *
     * <p>⚠️ key 传的是**逻辑键**：{@code RedisJsonCache} 自己会加 {@code platform:cache:} 前缀，
     * 这里再带一次就会变成双前缀 —— 缓存永不命中，而且没人看得出来。
     */
    private List<String> cachedSuggest(PlatformCredential row, String prefix) {
        CredentialRef cred = toRef(row);
        return cache.get("registry:suggest:" + cred.id() + ":" + prefix,
                Duration.ofMinutes(2),
                new TypeReference<>() { },
                () -> dialects.of(cred.type()).suggest(cred, prefix));
    }

    /** 浏览。**不吞错** —— 用户在页面上主动点了一层，失败要说（spec §5.3）。 */
    public BrowseResult browse(RegistryBrowseRequest req) {
        PlatformCredential row = mapper.selectById(req.getCredentialId());
        if (row == null || !isRegistry(row) || !enabled(row)) {
            throw new CloudPlatformException(EnumResponseType.CREDENTIAL_NOT_EXIST,
                    "镜像仓库连接不存在或已停用");
        }
        CredentialRef cred = toRef(row);
        List<String> path = req.getPath() == null ? List.of() : req.getPath();
        return dialects.of(cred.type()).browse(cred, path);
    }

    /** 启用中的镜像仓库类连接。**不解密** —— 明文只在真正要用时（{@link #toRef}）才解。 */
    private List<PlatformCredential> enabledRegistryRows() {
        return mapper.selectAll().stream()
                .filter(c -> isRegistry(c) && enabled(c))
                .toList();
    }

    /** 只带匹配需要的字段（id / name / type / endpoint）的轻量 ref，**不含明文**。 */
    private static CredentialRef toPlainRef(PlatformCredential c) {
        return new CredentialRef(c.getId(), c.getName(), c.getType(), c.getEndpoint(), null, null);
    }

    private CredentialRef toRef(PlatformCredential c) {
        CredentialSecret s = fromJson(cipher.decrypt(c.getSecretEncrypted()));
        return new CredentialRef(c.getId(), c.getName(), c.getType(), c.getEndpoint(),
                s.getUsername(), s.getSecret());
    }

    /**
     * 是不是一个镜像仓库类的连接。
     *
     * <p>⚠️ **问分派表，不自己维护一份 type 清单** —— 手写清单是第二个真相源，
     * 加一档时忘了改它会「全测绿但新档静默失效」。见 {@link RegistryDialectRegistry#supports}。
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
