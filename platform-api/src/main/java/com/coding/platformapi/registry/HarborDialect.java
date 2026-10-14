package com.coding.platformapi.registry;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriUtils;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Harbor 方言。设计见 docs/superpowers/specs/2026-10-14-registry-design.md §2.3。
 *
 * <p><b>走 Harbor 自己的 REST API</b>，不走 Docker Registry V2 的 {@code _catalog} ——
 * Harbor 默认关闭 catalog，且它的权限模型在自己的 API 里（分析文档 §7.5）。
 *
 * <p>本类里凡是**不掺 IO** 的部分都是 {@code static} 且可单测（照 {@code CredentialProbe} 的样子）：
 * 输入切分、URL 构造、响应映射、面包屑。发请求那一层在 {@link RegistryHttpClient}。
 */
@Slf4j
@Component
public class HarborDialect implements RegistryDialect {

    static final int PAGE_SIZE = 100;

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final RegistryHttpClient http;

    public HarborDialect(RegistryHttpClient http) {
        this.http = http;
    }

    @Override
    public String type() {
        return "HARBOR";
    }

    // ==================== 两个动作 ====================

    @Override
    public List<String> suggest(CredentialRef cred, String imagePrefix) {
        // 补全**静默降级**：任何失败都退回空候选，绝不弹错、绝不阻塞输入（spec §5.3）
        try {
            String tagPrefix = tagPrefixOf(imagePrefix);
            List<String> segs = segmentsOf(imagePrefix, cred.endpoint());

            if (tagPrefix != null) {
                // 已进入某仓库 → 补 tag。段不足 2（还没写全 project/repo）时给不出候选。
                if (segs.size() < 2) {
                    return List.of();
                }
                String project = segs.get(0);
                // ⚠️ 最后一段上还挂着 `:tag`（segmentsOf 只按 `/` 切），拼 repo 前必须剥掉 ——
                // 否则请求会打到 `/repositories/app:1/artifacts`，真实 Harbor 回 404，
                // 而补全是静默降级 → tag 补全**恒返回空且无声**。
                List<String> rest = new ArrayList<>(segs.subList(1, segs.size()));
                int last = rest.size() - 1;
                String tail = rest.get(last);
                int colon = tail.indexOf(':');
                if (colon >= 0) {
                    rest.set(last, tail.substring(0, colon));
                }
                String repo = String.join("/", rest);
                List<TagInfo> tags = parseArtifacts(http.get(cred, artifactsUrl(cred, project, repo)));
                String prefix = tagPrefix.toLowerCase();
                return tagCandidates(cred, project, repo,
                        tags.stream().map(TagInfo::tag)
                            .filter(t -> t.toLowerCase().startsWith(prefix)).toList());
            }

            // 还没进仓库 → 补仓库，只认「host 之后正好一段」的形态
            String term = searchTermOf(imagePrefix, cred.endpoint());
            if (term == null || term.isEmpty()) {
                return List.of();
            }
            List<String> hits = parseSearchHits(http.get(cred, searchUrl(cred, term)));
            // Harbor 的命中形如 proj/repo；投影成用户输入框里的完整串时，把 proj 提出来
            return hits.stream()
                    .filter(h -> h.contains("/"))
                    .map(h -> host(cred) + "/" + h)
                    .toList();
        } catch (Exception e) {
            log.warn("镜像候选查询失败（静默降级为空）prefix={}", imagePrefix, e);
            return List.of();
        }
    }

    @Override
    public BrowseResult browse(CredentialRef cred, List<String> path) {
        // 浏览**不吞错**：用户在页面上主动点了一层，什么都不说等于页面坏了（spec §5.3）
        if (path == null || path.isEmpty()) {
            return mapProjects(cred, parseProjectNames(http.get(cred, projectsUrl(cred))));
        }
        if (path.size() == 1) {
            String project = path.get(0);
            List<String> repos = parseRepositoryNames(
                    http.get(cred, repositoriesUrl(cred, project)), project);
            return mapRepositories(cred, path, repos);
        }
        if (path.size() == 2) {
            return mapArtifacts(cred, path, parseArtifacts(
                    http.get(cred, artifactsUrl(cred, path.get(0), path.get(1)))));
        }
        return new BrowseResult(List.of(), List.of());
    }

    // ==================== 响应映射（纯） ====================

    public static List<String> parseProjectNames(String json) {
        try {
            HarborProject[] arr = JSON.readValue(json, HarborProject[].class);
            return Arrays.stream(arr).map(p -> p.name).filter(Objects::nonNull).toList();
        } catch (Exception e) {
            log.warn("解析 Harbor projects 响应失败，按空处理", e);
            return List.of();
        }
    }

    /**
     * 解析 {@code repositories[].name} 并**剥掉 {@code project/} 前缀**。
     *
     * <p>Harbor 回的是全名（{@code myproj/team/app}），而第二层只该显示 {@code team/app}。
     * 剥离放在这里而不是调用方，是为了只有一处做这件事 —— 两处各剥一次就容易对不上。
     */
    public static List<String> parseRepositoryNames(String json, String project) {
        try {
            HarborRepo[] arr = JSON.readValue(json, HarborRepo[].class);
            String prefix = project + "/";
            return Arrays.stream(arr)
                    .map(r -> r.name)
                    .filter(Objects::nonNull)
                    .map(n -> n.startsWith(prefix) ? n.substring(prefix.length()) : n)
                    .toList();
        } catch (Exception e) {
            log.warn("解析 Harbor repositories 响应失败，按空处理", e);
            return List.of();
        }
    }

    public static List<String> parseSearchHits(String json) {
        try {
            HarborSearch s = JSON.readValue(json, HarborSearch.class);
            if (s.repository == null) {
                return List.of();
            }
            return s.repository.stream().map(r -> r.repository_name).filter(Objects::nonNull).toList();
        } catch (Exception e) {
            log.warn("解析 Harbor search 响应失败，按空处理", e);
            return List.of();
        }
    }

    public static List<TagInfo> parseArtifacts(String json) {
        try {
            HarborArtifact[] arr = JSON.readValue(json, HarborArtifact[].class);
            List<TagInfo> out = new ArrayList<>();
            for (HarborArtifact a : arr) {
                String tag = (a.tags != null && a.tags.length > 0) ? a.tags[0].name : a.name;
                if (tag != null) {
                    // 真实 Harbor 的字段是 `digest`；`name` 在带 tag 的 artifact 上通常也是同一个值。
                    // 两者都没有时给 null（UI 显示「—」）—— 不造一个假 digest。
                    out.add(new TagInfo(tag, a.push_time, a.size,
                            a.digest != null ? a.digest : a.name));
                }
            }
            return out;
        } catch (Exception e) {
            log.warn("解析 Harbor artifacts 响应失败，按空处理", e);
            return List.of();
        }
    }

    /** Harbor {@code /api/v2.0/projects} 的一行（只取用得到的字段） */
    @lombok.Data static class HarborProject { private String name; }

    /** Harbor {@code .../repositories} 的一行；{@code name} 是 {@code project/repo} 全名 */
    @lombok.Data static class HarborRepo { private String name; }

    @lombok.Data static class HarborSearch {
        private List<SearchRepo> repository;
        @lombok.Data static class SearchRepo { private String repository_name; }
    }

    /**
     * Harbor {@code .../artifacts} 的一行。
     *
     * <p>⚠️ {@code name} 是 **digest**（{@code sha256:…}），人类可读的 tag 在 {@code tags[0].name} ——
     * 见 {@link #tagNameOf}。
     */
    @lombok.Data static class HarborArtifact {
        private String name;
        /** artifact 的摘要（Harbor 的真实字段名）；`name` 在带 tag 时通常同值 */
        private String digest;
        private Tag[] tags;
        private String push_time;
        private long size;
        @lombok.Data static class Tag { private String name; }
    }

    // ==================== 输入切分（纯） ====================

    /** 去掉 host 之后按 {@code /} 切的段。{@code harbor.example.com/proj/app} → {@code [proj, app]}。 */
    public static List<String> segmentsOf(String imagePrefix, String endpoint) {
        String s = imagePrefix == null ? "" : imagePrefix.trim();
        if (!s.startsWith(endpoint)) {
            return List.of();
        }
        String rest = s.substring(endpoint.length());
        while (rest.startsWith("/") || rest.startsWith(":")) {
            rest = rest.substring(1);
        }
        if (rest.isEmpty()) {
            return List.of();
        }
        return Arrays.stream(rest.split("/")).filter(x -> !x.isEmpty()).toList();
    }

    /** 有 {@code :} 时返回 tag 前缀，否则 {@code null}。 */
    public static String tagPrefixOf(String imagePrefix) {
        String s = imagePrefix == null ? "" : imagePrefix.trim();
        int slash = s.lastIndexOf('/');
        int colon = s.indexOf(':', slash + 1);
        return colon >= 0 ? s.substring(colon + 1) : null;
    }

    /** 补仓库时的搜索词 = host 之后唯一那一段；打了斜杠（已进仓库）或只打了 host 时为 {@code null}。 */
    public static String searchTermOf(String imagePrefix, String endpoint) {
        List<String> segs = segmentsOf(imagePrefix, endpoint);
        return segs.size() == 1 ? segs.get(0) : null;
    }

    // ==================== URL 构造（纯） ====================

    public static String projectsUrl(CredentialRef c) {
        return base(c) + "/api/v2.0/projects?page_size=" + PAGE_SIZE;
    }

    public static String repositoriesUrl(CredentialRef c, String project) {
        return base(c) + "/api/v2.0/projects/" + enc(project) + "/repositories?page_size=" + PAGE_SIZE;
    }

    public static String searchUrl(CredentialRef c, String keyword) {
        return base(c) + "/api/v2.0/search?q=" + enc(keyword);
    }

    public static String artifactsUrl(CredentialRef c, String project, String repository) {
        return base(c) + "/api/v2.0/projects/" + enc(project)
                + "/repositories/" + enc(repository) + "/artifacts?page_size=" + PAGE_SIZE;
    }

    private static String base(CredentialRef c) {
        String e = c.endpoint().trim();
        while (e.endsWith("/")) {
            e = e.substring(0, e.length() - 1);
        }
        return e.startsWith("http://") || e.startsWith("https://") ? e : "https://" + e;
    }

    private static String enc(String s) {
        return UriUtils.encodePathSegment(s, StandardCharsets.UTF_8);
    }

    // ==================== 候选串：**完整字符串**（纯） ====================

    public static List<String> repositoryCandidates(CredentialRef c, String project, List<String> repos) {
        List<String> out = new ArrayList<>();
        for (String r : repos) {
            String path = r.startsWith(project + "/") ? r.substring(project.length() + 1) : r;
            out.add(host(c) + "/" + project + "/" + path);
        }
        return out;
    }

    public static List<String> tagCandidates(CredentialRef c, String project, String repo, List<String> tags) {
        return tags.stream().map(t -> host(c) + "/" + project + "/" + repo + ":" + t).toList();
    }

    private static String host(CredentialRef c) {
        return c.endpoint().replaceFirst("^https?://", "").replaceAll("/+$", "");
    }

    // ==================== 浏览映射（纯） ====================

    public static BrowseResult mapProjects(CredentialRef c, List<String> names) {
        List<BrowseResult.Node> nodes = names.stream()
                .map(n -> new BrowseResult.Node(n, true, null, List.of()))
                .toList();
        return new BrowseResult(List.of(), nodes);
    }

    public static BrowseResult mapRepositories(CredentialRef c, List<String> path, List<String> repos) {
        String project = path.get(0);
        List<BrowseResult.Node> nodes = repos.stream()
                .map(r -> new BrowseResult.Node(r, true, null, List.of()))
                .toList();
        return new BrowseResult(List.of(new BrowseResult.Crumb(project, List.of(project))), nodes);
    }

    public static BrowseResult mapArtifacts(CredentialRef c, List<String> path, List<TagInfo> tags) {
        String project = path.get(0);
        String repo = path.get(1);
        List<BrowseResult.Node> nodes = tags.stream().map(t -> new BrowseResult.Node(
                t.tag(),
                false,
                host(c) + "/" + project + "/" + repo + ":" + t.tag(),
                List.of(new BrowseResult.Detail("推送时间", t.pushedAt() == null ? "—" : t.pushedAt()),
                        new BrowseResult.Detail("大小", formatSize(t.size())),
                        new BrowseResult.Detail("digest", t.digest() == null ? "—" : t.digest())))).toList();
        return new BrowseResult(List.of(
                new BrowseResult.Crumb(project, List.of(project)),
                new BrowseResult.Crumb(repo, List.of(project, repo))), nodes);
    }

    /** Harbor artifact 的人类可读 tag 在 {@code tags[0].name}；空数组时退回 artifact.name（那是 digest）。 */
    public static String tagNameOf(String[] tags, String fallback) {
        return tags != null && tags.length > 0 ? tags[0] : fallback;
    }

    public static String formatSize(long bytes) {
        if (bytes <= 0) {
            return "—";
        }
        if (bytes < 1024) {
            return bytes + " B";
        }
        double kb = bytes / 1024.0;
        if (kb < 1024) {
            return String.format("%.1f KB", kb);
        }
        return String.format("%.1f MB", kb / 1024.0);
    }

    /** 从 Harbor artifact 里挑出来的一行（响应映射的中间形态，便于单测） */
    public record TagInfo(String tag, String pushedAt, long size, String digest) {
    }
}
