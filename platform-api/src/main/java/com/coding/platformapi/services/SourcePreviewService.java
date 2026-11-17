package com.coding.platformapi.services;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.data.mapper.system.PlatformPipelineRunMapper;
import com.coding.data.models.system.PlatformPipelineRun;
import com.coding.platformapi.models.PipelineSourceRefDTO;
import com.coding.platformapi.outbound.OutboundProxy;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.LsRemoteCommand;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevObject;
import org.eclipse.jgit.revwalk.RevTag;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.HttpTransport;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.eclipse.jgit.transport.http.HttpConnection;
import org.eclipse.jgit.transport.http.HttpConnectionFactory2;
import org.eclipse.jgit.transport.http.JDKHttpConnectionFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 触发前解析 git 节点实际会拉到的 commit（并把结果留成运行行上的 {@code source_json}）。
 *
 * <h2>为什么在 platform-api</h2>
 * 凭据明文与出站代理本来就在这一层（「测试连接」{@code CredentialProbeClient} 就是从 api
 * 进程访问 git host 的），而「基线是哪次运行」是业务判断。k8s-server 保持零业务 ——
 * 它只在渲染时接收 {@code pins}（节点 id → sha），不解释含义。
 *
 * <h2>三个不要</h2>
 * <ul>
 *   <li><b>不自己去查凭据表</b>：一律走 {@link CredentialService#resolveForPipeline} ——
 *       它同时校验类型、租户可见、是否勾了「允许在流水线里使用」。预览不能成为凭据读取旁路。</li>
 *   <li><b>不落盘凭据</b>：用 JGit 的 {@code UsernamePasswordCredentialsProvider}（只在内存），
 *       临时仓库里不写 {@code .netrc}；用完递归删。</li>
 *   <li><b>不假装知道</b>：仓库不可达 / 分支不存在 / 服务端不允许按 sha 浅取 —— 都如实回
 *       {@code error}/{@code warning}，让运行照旧可走，而不是猜一个提交。</li>
 * </ul>
 */
@Slf4j
@Service
public class SourcePreviewService {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** 完整 commit sha：钉住值只接受这一种形状（短 sha 在预览里给，不能当 pin 用）。 */
    private static final Pattern FULL_SHA = Pattern.compile("^[0-9a-f]{40}$");

    /** 找基线时最多看最近多少次成功运行（足够覆盖「上一次真正跑成功的那条」）。 */
    private static final int RECENT_RUNS = 20;

    private final CredentialService credentials;
    private final PlatformPipelineRunMapper runMapper;
    /** 单位：秒（JGit 的 timeout 就是秒）。 */
    private final int timeoutSeconds;

    public SourcePreviewService(CredentialService credentials,
                                PlatformPipelineRunMapper runMapper,
                                OutboundProxy proxy,
                                @Value("${platform.pipeline.preview.timeout-ms:30000}") int timeoutMs) {
        this.credentials = credentials;
        this.runMapper = runMapper;
        this.timeoutSeconds = Math.max(5, timeoutMs / 1000);
        // ⚠️ JGit 的代理只能从**连接工厂**拿（Transport 上没有 setProxy）。这里装一个
        //    「按 OutboundProxy 白名单选代理」的工厂 —— 与「测试连接」同一条出站规则：
        //    白名单里的外网 git host 走代理，内网直连。全局只有 platform-api 用 JGit。
        HttpTransport.setConnectionFactory(new ProxyAwareConnectionFactory(proxy.selector()));
    }

    // ------------------------------------------------------------------ 对外

    /**
     * 触发前预览：逐个 git 节点解析「分支/标签 → 实际 commit」。
     *
     * <p>⚠️ <b>P1 只做 ls-remote</b>（不下载对象）：depth-1 取一个 commit 会把那棵快照全下下来，
     * 对普通仓库是几秒、对 monorepo 是几十秒甚至几百 MB —— 打开触发框不该付这个代价。
     * 提交标题/文件级 diff 会在 P2 里和「查看变更」那一次拉取一起做（同一个 commit 只取一次）。
     *
     * @param pipelineId 找基线用（最近一次成功运行的 source_json）；可为 null（新流水线）
     * @param canvasJson **裁剪后**的画布（只含本次真要跑的节点）
     */
    public List<PipelineSourceRefDTO> preview(String pipelineId, String canvasJson, String callerTenantId) {
        return resolve(pipelineId, canvasJson, callerTenantId, Map.of(), false);
    }

    /**
     * 解析每个 git 节点的源码信息。
     *
     * @param pins        本次运行**钉住**的提交（节点 id → 完整 sha）。非空时该节点不再 ls-remote，
     *                    直接按这个 sha 记录 —— 用户看到的就是这次真正构建的那个。
     * @param withDetails 是否浅取提交对象（标题/作者/父提交）。**只有预览要**：触发是运行的关键路径，
     *                    为一个展示用的标题去下载整棵树不值得（大仓库会拖到几十秒）——
     *                    触发时只做 ls-remote（轻），有 pin 时连网络都不走。
     */
    public List<PipelineSourceRefDTO> resolve(String pipelineId, String canvasJson,
                                              String callerTenantId, Map<String, String> pins,
                                              boolean withDetails) {
        List<PipelineSourceRefDTO> out = new ArrayList<>();
        for (GitNode node : gitNodes(canvasJson)) {
            String pin = pins == null ? null : pins.get(node.nodeId());
            out.add(resolveOne(pipelineId, node, callerTenantId, pin, withDetails));
        }
        return out;
    }

    /** 触发时落库用：任何失败都不抛出（预览不是运行的前提）。 */
    public String sourceJsonOrNull(String pipelineId, String canvasJson,
                                   String callerTenantId, Map<String, String> pins) {
        try {
            List<PipelineSourceRefDTO> sources = resolve(pipelineId, canvasJson, callerTenantId,
                    pins, false);
            return sources.isEmpty() ? null : JSON.writeValueAsString(sources);
        } catch (RuntimeException e) {
            log.warn("解析流水线源码信息失败（本次运行不记录 commit）：{}", e.toString());
            return null;
        }
    }

    /** 钉住值形状校验；不合法直接拒（不会静默丢掉一个用户以为生效的 pin）。 */
    public static boolean isFullSha(String s) {
        return s != null && FULL_SHA.matcher(s.trim()).matches();
    }

    // ------------------------------------------------------------------ 单节点

    private PipelineSourceRefDTO resolveOne(String pipelineId, GitNode node,
                                            String callerTenantId, String pin, boolean withDetails) {
        String repo = node.repo();
        if (!StringUtils.hasText(repo)) {
            return PipelineSourceRefDTO.failed(node.nodeId(), "", node.revision(), "这个节点没有填仓库地址");
        }
        CredentialsProvider cred;
        try {
            cred = credentialsFor(node.connectionId(), callerTenantId);
        } catch (RuntimeException e) {
            return PipelineSourceRefDTO.failed(node.nodeId(), repo, node.revision(),
                    "连接不可用：" + messageOf(e));
        }

        String url = normalizeRepo(repo);
        String sha;
        String kind;
        try {
            if (StringUtils.hasText(pin)) {
                sha = pin.trim();
                kind = "pinned";
            } else {
                Resolved resolved = resolveRef(url, node.revision(), cred);
                sha = resolved.sha();
                kind = resolved.kind();
            }
        } catch (Exception e) {
            return PipelineSourceRefDTO.failed(node.nodeId(), repo, node.revision(),
                    "解析仓库失败：" + messageOf(e));
        }

        CommitInfo info = withDetails ? readCommit(url, sha, cred) : null;
        String warning = withDetails && info == null
                ? "取不到提交信息（可能是不允许按 sha 浅取的仓库）" : null;

        Baseline base = baseline(pipelineId, node.nodeId(), info == null ? null : info.parentSha());
        return new PipelineSourceRefDTO(node.nodeId(), repo, node.revision(), kind, sha,
                shortSha(sha), info == null ? null : info.subject(),
                info == null ? null : info.author(),
                info == null ? null : info.committedAt(),
                base.sha(), base.kind(), base.runName(), warning, null);
    }

    private CredentialsProvider credentialsFor(String connectionId, String callerTenantId) {
        if (!StringUtils.hasText(connectionId)) {
            return null; // 匿名拉取（公开仓库）
        }
        com.coding.common.models.pipeline.PipelineConnectionRef ref =
                credentials.resolveForPipeline(connectionId, "GIT", callerTenantId);
        if (!StringUtils.hasText(ref.username()) || !StringUtils.hasText(ref.secret())) {
            return null; // 匿名连接（没存密钥）
        }
        return new UsernamePasswordCredentialsProvider(ref.username(), ref.secret());
    }

    /** 分支/标签 → 实际 commit。默认分支取 {@code HEAD}；优先 heads，其次 tags（与 git clone --branch 同序）。 */
    private Resolved resolveRef(String url, String revision, CredentialsProvider cred) throws Exception {
        LsRemoteCommand cmd = Git.lsRemoteRepository()
                .setRemote(url)
                // ⚠️ **不要** setHeads/setTags：那会把返回的 ref 过滤成只有 heads/tags，
                //    默认分支靠的 `HEAD` 就不在里面了（setRefSpecs 只匹配 refs/heads/* 与 refs/tags/*）。
                //    全量返回没问题 —— ls-remote 只有一行一个 ref，不下载对象。
                .setTimeout(timeoutSeconds);
        if (cred != null) {
            cmd.setCredentialsProvider(cred);
        }
        Map<String, Ref> refs = cmd.callAsMap();

        if (!StringUtils.hasText(revision)) {
            Ref head = refs.get("HEAD");
            if (head == null) {
                throw new IllegalStateException("仓库没有报告默认分支（HEAD）");
            }
            return new Resolved(commitIdOf(head), "default");
        }
        String rev = revision.trim();
        Ref branch = refs.get("refs/heads/" + rev);
        if (branch != null) {
            return new Resolved(commitIdOf(branch), "branch");
        }
        Ref tag = refs.get("refs/tags/" + rev);
        if (tag != null) {
            // 附注标签：ref 指向 tag 对象，取 peeled 才是 commit
            return new Resolved(commitIdOf(tag), "tag");
        }
        throw new IllegalStateException("分支/标签不存在：" + rev);
    }

    private static String commitIdOf(Ref ref) {
        ObjectId id = ref.getPeeledObjectId() != null ? ref.getPeeledObjectId() : ref.getObjectId();
        return id == null ? null : id.name();
    }

    /**
     * 浅取一个 commit 并读元数据（标题/作者/时间/父提交）。
     *
     * <p>失败返回 null（调用方只给 warning，不影响 sha）：最常见的失败是自建源禁止按 sha
     * 浅取（{@code uploadpack.allowReachableSHA1InWant=false}）—— 那种源上「钉住」仍会在
     * 运行时由 git 节点自己核对/报错，预览这边不该因此挡住运行。
     */
    private CommitInfo readCommit(String url, String sha, CredentialsProvider cred) {
        Path dir = null;
        Git git = null;
        try {
            dir = Files.createTempDirectory("pipe-preview-");
            git = Git.init().setDirectory(dir.toFile()).call();
            var fetch = git.fetch()
                    .setRemote(url)
                    .setRefSpecs(new RefSpec("+" + sha + ":refs/preview/head"))
                    .setDepth(1)
                    .setTimeout(timeoutSeconds);
            if (cred != null) {
                fetch.setCredentialsProvider(cred);
            }
            fetch.call();

            try (RevWalk walk = new RevWalk(git.getRepository())) {
                RevObject any = walk.parseAny(ObjectId.fromString(sha));
                if (any instanceof RevTag tag) {
                    any = walk.peel(tag);
                }
                RevCommit commit = walk.parseCommit(any);
                String parent = commit.getParentCount() > 0 ? commit.getParent(0).name() : null;
                return new CommitInfo(commit.getShortMessage(), commit.getAuthorIdent().getName(),
                        Instant.ofEpochSecond(commit.getCommitTime()).toString(), parent);
            }
        } catch (Exception e) {
            log.info("浅取提交信息失败（不影响运行）：{} {}", sha, messageOf(e));
            return null;
        } finally {
            if (git != null) {
                git.close();
            }
            deleteQuietly(dir);
        }
    }

    // ------------------------------------------------------------------ 基线

    /**
     * 对比基线：**最近一次成功运行**里同一个 git 节点的 sha；没有则退回父提交
     * （「最后一个提交改了什么」）。两者都没有就 {@code none}。
     */
    private Baseline baseline(String pipelineId, String nodeId, String parentSha) {
        if (StringUtils.hasText(pipelineId)) {
            List<PlatformPipelineRun> recent;
            try {
                recent = runMapper.listRecentSucceeded(pipelineId, RECENT_RUNS);
            } catch (RuntimeException e) {
                log.warn("取最近成功运行失败（基线留空）：{}", e.toString());
                recent = List.of();
            }
            for (PlatformPipelineRun run : recent) {
                String sha = shaOfNode(run.getSourceJson(), nodeId);
                if (StringUtils.hasText(sha)) {
                    return new Baseline(sha, "last-success", run.getRunName());
                }
            }
        }
        if (StringUtils.hasText(parentSha)) {
            return new Baseline(parentSha, "parent", null);
        }
        return new Baseline(null, "none", null);
    }

    /** {@code source_json} 里某个节点的 sha；解析不了就当没有（老数据/手工改过）。 */
    static String shaOfNode(String sourceJson, String nodeId) {
        if (!StringUtils.hasText(sourceJson)) {
            return null;
        }
        try {
            JsonNode arr = JSON.readTree(sourceJson);
            if (!arr.isArray()) {
                return null;
            }
            for (JsonNode n : arr) {
                if (nodeId.equals(n.path("nodeId").asString(null))) {
                    return n.path("sha").asString(null);
                }
            }
        } catch (RuntimeException e) {
            return null;
        }
        return null;
    }

    // ------------------------------------------------------------------ 画布读取

    /** 画布上的一个 git 节点（只取解析需要的四个字段）。 */
    record GitNode(String nodeId, String repo, String revision, String connectionId) {
    }

    /** 画布 JSON 里的 git 节点。⚠️ 字段名与 k8s-core 的 GitCloneNode 表单是跨模块契约。 */
    List<GitNode> gitNodes(String canvasJson) {
        if (!StringUtils.hasText(canvasJson)) {
            return List.of();
        }
        JsonNode nodes;
        try {
            nodes = JSON.readTree(canvasJson).path("nodes");
        } catch (RuntimeException e) {
            throw new CloudPlatformException(EnumResponseType.PIPELINE_CANVAS_INVALID,
                    "画布内容无法解析");
        }
        if (!nodes.isArray()) {
            return List.of();
        }
        List<GitNode> out = new ArrayList<>();
        for (JsonNode node : nodes) {
            if (!"git".equals(node.path("type").asString(null))) {
                continue;
            }
            JsonNode cfg = node.path("config");
            out.add(new GitNode(node.path("id").asString(null),
                    cfg.path("repo").asString(""),
                    cfg.path("revision").asString(""),
                    cfg.path("connectionId").asString("")));
        }
        return out;
    }

    // ------------------------------------------------------------------ 小工具

    /** 与 CredentialProbe 同一条约定：用户手输 host 是常态，缺 scheme 补 https。 */
    private static String normalizeRepo(String repo) {
        String r = repo.trim();
        return r.contains("://") ? r : "https://" + r;
    }

    private static String shortSha(String sha) {
        return sha == null || sha.length() < 7 ? sha : sha.substring(0, 7);
    }

    private static String messageOf(Throwable e) {
        String m = e.getMessage();
        return StringUtils.hasText(m) ? m : e.getClass().getSimpleName();
    }

    private static void deleteQuietly(Path dir) {
        if (dir == null) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignore) {
                    // 临时目录残留不影响正确性；下次系统清理会带走
                }
            });
        } catch (IOException ignore) {
            // 同上
        }
    }

    private record Resolved(String sha, String kind) {
    }

    private record CommitInfo(String subject, String author, String committedAt, String parentSha) {
    }

    private record Baseline(String sha, String kind, String runName) {
    }

    /**
     * JGit 的 HTTP 连接工厂：**按 OutboundProxy 的白名单选代理**，其余直连。
     *
     * <p>JGit 的 {@code Transport} 没有 setProxy，代理只能从连接工厂这一层给。实现
     * {@link HttpConnectionFactory2} 是为了保住 git 协议 v2（只实现基础接口会退回 v0）。
     */
    private static final class ProxyAwareConnectionFactory implements HttpConnectionFactory2 {

        private final JDKHttpConnectionFactory delegate = new JDKHttpConnectionFactory();
        private final ProxySelector selector;

        private ProxyAwareConnectionFactory(ProxySelector selector) {
            this.selector = selector;
        }

        @Override
        public HttpConnection create(URL url) throws IOException {
            Proxy proxy = proxyFor(url);
            return proxy == null ? delegate.create(url) : delegate.create(url, proxy);
        }

        @Override
        public HttpConnection create(URL url, Proxy proxy) throws IOException {
            return delegate.create(url, proxy);
        }

        @Override
        public GitSession newSession() {
            return delegate.newSession();
        }

        private Proxy proxyFor(URL url) {
            try {
                List<Proxy> found = selector.select(url.toURI());
                if (found == null || found.isEmpty()) {
                    return null;
                }
                Proxy p = found.get(0);
                return p == null || p.type() == Proxy.Type.DIRECT ? null : p;
            } catch (Exception e) {
                return null; // 选择器异常 = 直连（与白名单为空同义）
            }
        }
    }
}
