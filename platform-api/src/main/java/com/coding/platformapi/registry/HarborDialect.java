package com.coding.platformapi.registry;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

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

    @Override
    public String type() {
        return "HARBOR";
    }

    // ==================== 两个动作（Task 7 接上出站后实现） ====================

    @Override
    public List<String> suggest(CredentialRef cred, String imagePrefix) {
        throw new UnsupportedOperationException("Task 7 接上 RegistryHttpClient 后实现");
    }

    @Override
    public BrowseResult browse(CredentialRef cred, List<String> path) {
        throw new UnsupportedOperationException("Task 7 接上 RegistryHttpClient 后实现");
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
                        new BrowseResult.Detail("大小", formatSize(t.size()))))).toList();
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
    public record TagInfo(String tag, String pushedAt, long size) {
    }
}
