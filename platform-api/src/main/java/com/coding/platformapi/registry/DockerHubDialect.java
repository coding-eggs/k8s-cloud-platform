package com.coding.platformapi.registry;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.platformapi.registry.BrowseFormat.TagInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriUtils;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Docker Hub 方言。设计见 docs/superpowers/specs/2026-10-14-registry-dockerhub-design.md §3。
 *
 * <h3>两个与其他档不同的地方</h3>
 * <ol>
 *   <li><b>同一条方言服务两种来源</b> —— 一个 Docker Hub 在平台里可以有两副面孔：
 *       <ul>
 *         <li><b>内置档</b>（{@code BuiltinRegistry}）：匿名，只答公共镜像，浏览第一层是 {@code library}；</li>
 *         <li><b>凭据行</b>（{@code type=DOCKERHUB}）：带用户名 + PAT，**只答"我名下"**
 *             （自己的仓库列表与 tag，含私有），浏览第一层多一个你自己的命名空间入口。</li>
 *       </ul>
 *       判据就是<b>这条 ref 有没有凭据</b>，不新增开关。
 *       ⚠️ 2026-10-14 的「只允许匿名」是当时的裁定（私有镜像该放自建 Harbor），
 *       <b>2026-10-15 用户撤销</b>：私有 Docker Hub 要能搜、能浏览、能推。
 *       那条代价（搜不到自己名下的私有仓库）随之消失。</li>
 *   <li><b>出站地址是写死的 {@code hub.docker.com}，不是 endpoint</b> —— Docker Hub 的 API 站点
 *       与它的 registry 名（{@code docker.io}）不是一回事。endpoint 只用于匹配。
 *       这不是绕过「出站地址不来自用户输入」那条边界，正是它的用法：用户连填都填不了。</li>
 * </ol>
 *
 * <p>⚠️ <b>走的是 registry 站点自身用的接口</b>（{@code hub.docker.com/v2/…}），**没有官方 SLA**
 * （分析文档 §7.5 前提 2）。选它是因为官方 OCI 路径 {@code registry-1.docker.io/v2/…/tags/list}
 * **没有搜索接口**（补全根本做不出来），且 {@code tags/list} 只给 tag 名，
 * 浏览页要的三列得逐 tag 再取 manifest（N+1）。
 * 代价就是：**必须缓存 + 静默降级**（spec §2.4 / §3.2）。
 */
@Slf4j
@Component
public class DockerHubDialect implements RegistryDialect {

    static final int SEARCH_PAGE_SIZE = 25;

    static final int PAGE_SIZE = 100;

    /** Docker 官方库的命名空间。单段输入按 Docker 的字面语义归到这里。 */
    static final String LIBRARY = "library";

    /**
     * 作为**凭据行**只接受 BASIC。
     *
     * <p>匿名那一份由**内置档**提供（它直接构造，不走 {@code CredentialService} 的校验）——
     * 所以这里放行 {@code NONE} 只会造出一个与内置档一模一样、候选完全重复的第二来源。
     * 声明的归属仍然在这一档自己身上（不回到 {@code CredentialService} 里手写清单，spec §3.7）。
     */
    private static final Set<String> AUTH_TYPES = Set.of("BASIC");

    private static final String API = "https://hub.docker.com/v2";

    /**
     * 换 token 的路径。⚠️ <b>必须走它</b>：站点 API **不认 Basic**（2026-10-15 实测：
     * 带一对错误凭据的 Basic 头照样 200，等于被当成匿名请求），只认登录换来的 JWT
     * （错误 Bearer → 401）。出站地址是写死的常量，不来自用户输入。
     */
    static final String LOGIN_PATH = "/users/login/";

    /** 探针只要"这个 PAT 看得见我的仓库吗"，一条就够 —— 别把整页仓库拉回来。 */
    static final int PROBE_PAGE_SIZE = 1;

    /** 换来的 token 缓存多久。站点 API 无 SLA，登录是额外一次往返（每次按键都登录既慢又易被限流）。 */
    static final Duration TOKEN_TTL = Duration.ofMinutes(20);

    /**
     * 指向同一个 registry 的三种写法。用户打哪种都算 ——
     * 做候选串前缀时**保留用户打的那个**，不替换成 docker.io。
     */
    static final List<String> HOST_ALIASES =
            List.of("docker.io", "index.docker.io", "registry-1.docker.io");

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final RegistryHttpClient http;

    /** username → 换来的 token（含过期时刻）。换一次用一阵，401 时丢掉重换。 */
    private final Map<String, CachedToken> tokens = new ConcurrentHashMap<>();

    public DockerHubDialect(RegistryHttpClient http) {
        this.http = http;
    }

    private record CachedToken(String value, Instant expiresAt) {
    }

    @Override
    public String type() {
        return "DOCKERHUB";
    }

    @Override
    public Set<String> authTypes() {
        return AUTH_TYPES;
    }

    // ==================== 输入切分（纯） ====================

    /**
     * 用户打的 host 前缀（**含末尾斜杠**），没打 host 时返回空串。
     *
     * <p>保留用户写的那个别名：他打 {@code registry-1.docker.io/ng} 就还他
     * {@code registry-1.docker.io/nginx}，不擅自改写成 {@code docker.io/nginx}。
     */
    public static String hostPrefixOf(String imagePrefix) {
        String p = imagePrefix == null ? "" : imagePrefix.trim();
        int slash = p.indexOf('/');
        if (slash < 0) {
            return "";
        }
        return HOST_ALIASES.contains(p.substring(0, slash).toLowerCase(Locale.ROOT))
                ? p.substring(0, slash + 1)
                : "";
    }

    /**
     * 无 host 的单段输入按 **Docker 的字面语义**归到官方库 ——
     * {@code nginx} 就是 {@code library/nginx}，与 {@code docker pull nginx} 一致。
     */
    public static String namespaceOf(String rest) {
        int slash = rest.indexOf('/');
        return slash < 0 ? LIBRARY : rest.substring(0, slash);
    }

    public static String repoOf(String rest) {
        int slash = rest.indexOf('/');
        return slash < 0 ? rest : rest.substring(slash + 1);
    }

    /**
     * 无 host 输入时剥掉 {@code library/} —— {@code nginx} 与 {@code library/nginx} 是同一个镜像，
     * 填短的更常见。用户显式打了 host 前缀就保留（他既然写全了，就别动他写的）。
     */
    public static String stripLibrary(String repoName, String hostPrefix) {
        if (!hostPrefix.isEmpty()) {
            return repoName;
        }
        String prefix = LIBRARY + "/";
        return repoName.startsWith(prefix) ? repoName.substring(prefix.length()) : repoName;
    }

    // ==================== URL 构造（纯） ====================

    public static String searchUrl(String keyword) {
        return API + "/search/repositories/?query="
                + UriUtils.encodeQueryParam(keyword, StandardCharsets.UTF_8)
                + "&page_size=" + SEARCH_PAGE_SIZE;
    }

    public static String loginUrl() {
        return API + LOGIN_PATH;
    }

    /** 「我的仓库」探针用的（只要一条）—— 与 {@link #repositoriesUrl} 只差 page_size。 */
    public static String repositoriesProbeUrl(String namespace) {
        return API + "/repositories/" + enc(namespace) + "/?page_size=" + PROBE_PAGE_SIZE;
    }

    public static String repositoriesUrl(String namespace) {
        return API + "/repositories/" + enc(namespace) + "/?page_size=" + PAGE_SIZE;
    }

    public static String tagsUrl(String namespace, String repo) {
        return API + "/repositories/" + enc(namespace) + "/" + enc(repo)
                + "/tags?page_size=" + PAGE_SIZE;
    }

    private static String enc(String s) {
        return UriUtils.encodePathSegment(s, StandardCharsets.UTF_8);
    }

    // ==================== 响应映射（纯） ====================

    /**
     * 三个接口的响应都是 {@code {"count":N,"results":[…]}} —— 所以共用一层取 {@code results}。
     * 任何一个字段缺失/形状变了都**按空处理**：站点接口没有 SLA，不能让它把补全打挂。
     */
    private static List<Row> rows(String json) {
        try {
            Root root = JSON.readValue(json, Root.class);
            return root.results == null ? List.of() : root.results;
        } catch (Exception e) {
            log.warn("解析 Docker Hub 响应失败，按空处理", e);
            return List.of();
        }
    }

    /** 搜索命中，形如 {@code library/nginx} 或 {@code myteam/app}。 */
    public static List<String> parseSearchHits(String json) {
        return rows(json).stream().map(r -> r.repo_name).filter(Objects::nonNull).toList();
    }

    public static List<String> parseRepositoryNames(String json) {
        return rows(json).stream().map(r -> r.name).filter(Objects::nonNull).toList();
    }

    public static List<TagInfo> parseTags(String json) {
        return rows(json).stream()
                .filter(r -> r.name != null)
                .map(r -> new TagInfo(r.name, r.last_updated, r.full_size, r.digest))
                .toList();
    }

    @lombok.Data static class Root {
        private List<Row> results;
    }

    /** 三个接口的行字段并集 —— 各投影取自己那几个。 */
    @lombok.Data static class Row {
        private String name;
        private String repo_name;
        private String last_updated;
        private long full_size;
        private String digest;
    }

    // ==================== 两个动作 + 探测 ====================

    @Override
    public List<String> suggest(CredentialRef cred, String imagePrefix) {
        // 补全**静默降级**：任何失败都退回空候选（spec §2.5 / S3.1 spec §5.3）
        try {
            String p = imagePrefix == null ? "" : imagePrefix.trim();
            String hostPrefix = hostPrefixOf(p);
            String rest = p.substring(hostPrefix.length());
            String tagPrefix = tagPrefixOf(p);
            boolean authed = isAuthenticated(cred);

            if (tagPrefix != null) {
                // rest 上还挂着 `:<tag>`（hostPrefix 只切掉了 host）—— 先剥掉才拼得出 tags 的路径。
                // ⚠️ **无论 tagPrefix 空不空都要剥那个冒号**：`nginx:` 是「刚打下冒号」，
                // 此时 tagPrefix 是空串，不剥就会去请求 `library/nginx%3A`（404 → 静默空）。
                if (rest.length() <= tagPrefix.length() + 1) {
                    return List.of();
                }
                String repoPart = rest.substring(0, rest.length() - tagPrefix.length() - 1);
                if (repoPart.isEmpty()) {
                    return List.of();
                }
                String ns = namespaceOf(repoPart);
                // 带凭据的那一份**只答「我名下」**：别人的仓库（公开的）由内置档答 ——
                // 两个来源各答各的，候选既不重复也不缺
                if (authed && !cred.username().equalsIgnoreCase(ns)) {
                    return List.of();
                }
                List<TagInfo> tags = parseTags(fetch(cred, tagsUrl(ns, repoOf(repoPart))));
                String lower = tagPrefix.toLowerCase(Locale.ROOT);
                return tags.stream()
                        .map(TagInfo::name)
                        .filter(t -> t.toLowerCase(Locale.ROOT).startsWith(lower))
                        .map(t -> hostPrefix + repoPart + ":" + t)
                        .toList();
            }

            // 只打了 host（`docker.io` / `docker.io/` / `registry-1.docker.io`）：
            // 把整个 registry 的候选拉出来既慢又没意义
            if (rest.isEmpty() || HOST_ALIASES.contains(p.toLowerCase(Locale.ROOT))) {
                return List.of();
            }
            if (!authed) {
                return parseSearchHits(fetch(cred, searchUrl(rest))).stream()
                        .map(n -> hostPrefix + stripLibrary(n, hostPrefix))
                        .distinct()
                        .toList();
            }
            // 带凭据：**只列我名下的仓库**（含私有），按输入前缀过滤。
            // 无 host 的单段（`app`）与 `myuser/app` 都归到这里；别人的命名空间直接空。
            if (rest.contains("/") && !cred.username().equalsIgnoreCase(namespaceOf(rest))) {
                return List.of();
            }
            String repoPrefix = repoOf(rest).toLowerCase(Locale.ROOT);
            return parseRepositoryNames(fetch(cred, repositoriesUrl(cred.username()))).stream()
                    .filter(n -> n.toLowerCase(Locale.ROOT).startsWith(repoPrefix))
                    .map(n -> hostPrefix + cred.username() + "/" + n)
                    .distinct()
                    .toList();
        } catch (Exception e) {
            log.warn("Docker Hub 候选查询失败（静默降级为空）prefix={}", imagePrefix, e);
            return List.of();
        }
    }

    @Override
    public BrowseResult browse(CredentialRef cred, List<String> path) {
        // 浏览**不吞错**：用户主动点了一层，什么都不说等于页面坏了（S3.1 spec §5.3）
        if (path == null || path.isEmpty()) {
            List<BrowseResult.Node> first = new ArrayList<>();
            first.add(new BrowseResult.Node(LIBRARY, true, null,
                    List.of(new BrowseResult.Detail("说明", "Docker 官方镜像库"))));
            if (isAuthenticated(cred)) {
                // 带凭据的那一份：多一个「你自己的命名空间」入口（私有仓库在那里）
                first.add(new BrowseResult.Node(cred.username(), true, null,
                        List.of(new BrowseResult.Detail("说明", "你的仓库（含私有）"))));
            }
            return new BrowseResult(List.of(), first);
        }
        if (path.size() == 1) {
            String ns = path.get(0);
            List<String> repos = parseRepositoryNames(fetch(cred, repositoriesUrl(ns)));
            return new BrowseResult(
                    List.of(new BrowseResult.Crumb(ns, List.of(ns))),
                    repos.stream().map(r -> new BrowseResult.Node(r, true, null, List.of())).toList());
        }
        if (path.size() == 2) {
            String ns = path.get(0);
            String repo = path.get(1);
            List<TagInfo> tags = parseTags(fetch(cred, tagsUrl(ns, repo)));
            // 叶子的 copyText 与补全同一条规则：library 下剥前缀（spec §3.3）
            String copyName = (LIBRARY.equals(ns) ? "" : ns + "/") + repo;
            return new BrowseResult(
                    List.of(new BrowseResult.Crumb(ns, List.of(ns)),
                            new BrowseResult.Crumb(repo, List.of(ns, repo))),
                    tags.stream().map(t -> new BrowseResult.Node(
                            t.name(), false, copyName + ":" + t.name(),
                            List.of(new BrowseResult.Detail("推送时间",
                                            t.pushedAt() == null ? "—" : t.pushedAt()),
                                    new BrowseResult.Detail("大小", BrowseFormat.formatSize(t.size())),
                                    new BrowseResult.Detail("digest",
                                            t.digest() == null ? "—" : t.digest())))).toList());
        }
        return new BrowseResult(List.of(), List.of());
    }

    @Override
    public ProbeSpec probeSpec(CredentialRef cred) {
        if (isAuthenticated(cred)) {
            // 带凭据：探两件事 —— 登录换 token 成不成（凭据对不对）、这个 PAT 看不看得见我的仓库。
            // ⚠️ token 在这里就换掉（拿不到会当场抛，文案直指「用户名或 PAT 不对」），
            // 之后 CredentialProbeClient 带着它去 GET，那一跳的失败语义才不含糊。
            return new ProbeSpec(repositoriesProbeUrl(cred.username()),
                    "Docker Hub 站点 API 返回 404：地址可能被网络中间件改写了",
                    "Bearer " + token(cred));
        }
        // 匿名（内置档）：探的是「平台能不能出站访问 Docker Hub 的公共 API」。
        return new ProbeSpec(repositoriesUrl(LIBRARY),
                "Docker Hub 公共 API 返回 404：地址可能被网络中间件改写了");
    }

    /** 这条 ref 有没有凭据 —— 内置档恒为 false（它就是匿名那一份），凭据行恒为 true。 */
    private static boolean isAuthenticated(CredentialRef cred) {
        return cred != null && hasText(cred.username()) && hasText(cred.secret());
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }

    /**
     * 带凭据就带 Bearer（先换 token），否则匿名。
     *
     * <p>⚠️ <b>不能走 Basic</b>：站点 API 会**忽略** Basic 头（实测：一对错误凭据 + Basic 照样 200），
     * 于是"私有仓库"静默退化成匿名 —— 而补全那条又正好是静默降级的，症状是"什么都没有"。
     */
    private String fetch(CredentialRef cred, String url) {
        if (!isAuthenticated(cred)) {
            return http.get(cred, url);
        }
        String user = cred.username();
        try {
            return http.getWithBearer(url, token(cred));
        } catch (CloudPlatformException e) {
            // 401 也可能是缓存里的 token 过期了 —— 丢掉它，下一次重新换
            tokens.remove(user);
            throw e;
        }
    }

    /** 换 token（带缓存）。站点 API 无 SLA，登录是额外一次往返。 */
    private String token(CredentialRef cred) {
        String user = cred.username();
        CachedToken cached = tokens.get(user);
        if (cached != null && cached.expiresAt().isAfter(Instant.now())) {
            return cached.value();
        }
        synchronized (tokens) {
            cached = tokens.get(user);
            if (cached != null && cached.expiresAt().isAfter(Instant.now())) {
                return cached.value();
            }
            String json = http.postJson(loginUrl(),
                    Map.of("username", user, "password", cred.secret() == null ? "" : cred.secret()),
                    "Docker Hub 用户名或访问令牌（PAT）不对");
            String jwt = parseToken(json);
            if (!hasText(jwt)) {
                throw new CloudPlatformException(EnumResponseType.CREDENTIAL_INVALID,
                        "Docker Hub 登录成功但响应里没有 token（站点接口可能变了）");
            }
            tokens.put(user, new CachedToken(jwt, Instant.now().plus(TOKEN_TTL)));
            return jwt;
        }
    }

    /** 登录响应里的 token；解析不了返回 null（站点接口没有 SLA，不能让它把补全打挂）。 */
    public static String parseToken(String json) {
        try {
            return JSON.readValue(json, LoginRoot.class).token;
        } catch (Exception e) {
            log.warn("解析 Docker Hub 登录响应失败", e);
            return null;
        }
    }

    /**
     * Docker Hub 的 endpoint 是**恒定的**（{@code docker.io}）：它没有"用户自己的地址"，
     * 而出站用的两个站点（{@code hub.docker.com} 与 registry 名）都由本档写死。
     * 所以三个别名统一存成 {@code docker.io}，填别的**直接拒** —— 不静默改写，
     * 否则管理员会以为配的是个私有 registry。
     */
    @Override
    public String normalizeEndpoint(String endpoint) {
        String e = endpoint == null ? "" : endpoint.trim().toLowerCase(Locale.ROOT);
        while (e.endsWith("/")) {
            e = e.substring(0, e.length() - 1);
        }
        if (HOST_ALIASES.contains(e)) {
            return "docker.io";
        }
        throw new CloudPlatformException(EnumResponseType.CREDENTIAL_INVALID,
                "Docker Hub 连接的地址只能是 " + String.join(" / ", HOST_ALIASES) + "，收到：" + endpoint);
    }

    @lombok.Data static class LoginRoot {
        private String token;
    }

    /**
     * 有 {@code :} 时返回 tag 前缀，否则 {@code null}。
     *
     * <p>与 {@code HarborDialect} 那份同款，但**有意各写一份** —— 两档对 {@code :} 的处理
     * 将来可能分叉（Docker Hub 的引用不带 host 是常态，Harbor 的必须带 host）。
     */
    private static String tagPrefixOf(String imagePrefix) {
        String s = imagePrefix == null ? "" : imagePrefix.trim();
        int slash = s.lastIndexOf('/');
        int colon = s.indexOf(':', slash + 1);
        return colon >= 0 ? s.substring(colon + 1) : null;
    }
}
