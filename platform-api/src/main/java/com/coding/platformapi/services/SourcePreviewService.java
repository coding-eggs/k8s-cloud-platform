package com.coding.platformapi.services;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.data.mapper.system.PlatformPipelineRunMapper;
import com.coding.data.models.system.PlatformPipelineRun;
import com.coding.platformapi.models.PipelineFileChangeDTO;
import com.coding.platformapi.models.PipelineFilePatchDTO;
import com.coding.platformapi.models.PipelineSourceRefDTO;
import com.coding.platformapi.outbound.OutboundProxy;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.LsRemoteCommand;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.Edit;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.patch.FileHeader;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
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
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.filter.PathFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 触发前解析 git 节点实际会拉到的 commit（并把结果留成运行行上的 {@code source_json}），
 * 外加按需算出两个提交之间的**文件级变更清单**。
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
 *
 * <h2>两档开销，别混用</h2>
 * <ul>
 *   <li><b>只用 ls-remote</b>（打开触发框、触发时落库）：一行一个 ref，不下载对象，通常 &lt;1s；</li>
 *   <li><b>浅取 + diff</b>（用户点「查看变更」）：会把目标提交的整棵快照下下来 ——
 *       普通仓库几秒，monorepo 可能几十秒几百 MB。**只能由用户主动触发**，不能放在
 *       打开对话框或触发的关键路径上。</li>
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
    /** 单位：秒（JGit 的 timeout 就是秒）。**每个 JGit 命令**一份，不是整次请求一份。 */
    private final int timeoutSeconds;
    /** 读提交头部（找 parent 行）时最多看这么多字节 —— 头部就那么几行。 */
    private static final int PARENT_PROBE_BYTES = 64 * 1024;

    /** 单个文件的补丁最多回多少行 / 多少字节（超了标 truncated，界面说清楚）。 */
    private static final int MAX_PATCH_LINES = 400;
    private static final int MAX_PATCH_BYTES = 512 * 1024;

    /** 一次最多回多少条文件变更（多的只算总数，界面标「另有 N 个未显示」）。 */
    private final int maxFiles;

    public SourcePreviewService(CredentialService credentials,
                                PlatformPipelineRunMapper runMapper,
                                OutboundProxy proxy,
                                @Value("${platform.pipeline.preview.timeout-ms:30000}") int timeoutMs,
                                @Value("${platform.pipeline.preview.max-files:200}") int maxFiles) {
        this.credentials = credentials;
        this.runMapper = runMapper;
        this.timeoutSeconds = Math.max(5, timeoutMs / 1000);
        this.maxFiles = Math.max(1, maxFiles);
        // ⚠️ JGit 的代理只能从**连接工厂**拿（Transport 上没有 setProxy）。这里装一个
        //    「按 OutboundProxy 白名单选代理」的工厂 —— 与「测试连接」同一条出站规则：
        //    白名单里的外网 git host 走代理，内网直连。全局只有 platform-api 用 JGit。
        HttpTransport.setConnectionFactory(new ProxyAwareConnectionFactory(proxy.selector()));
    }

    // ------------------------------------------------------------------ 对外

    /**
     * 触发前预览：逐个 git 节点解析「分支/标签 → 实际 commit」，**不下载对象**。
     *
     * <p>打开触发框走这一档：只 ls-remote（一行一个 ref）。提交标题与文件清单要浅取整棵树，
     * 不该在打开对话框时付这个代价 —— 用户点「查看变更」才走 {@link #preview(String, String, String, boolean)}。
     *
     * @param pipelineId 找基线用（最近一次成功运行的 source_json）；可为 null（新流水线）
     * @param canvasJson **裁剪后**的画布（只含本次真要跑的节点）
     */
    public List<PipelineSourceRefDTO> preview(String pipelineId, String canvasJson, String callerTenantId) {
        return resolve(pipelineId, canvasJson, callerTenantId, Map.of(), false);
    }

    /**
     * 同上，但 {@code withDiff = true} 时浅取提交对象并算文件级变更（用户主动点「查看变更」）。
     *
     * <p>⚠️ 这一档会把目标提交（以及基线提交）的整棵快照下载到临时目录 —— 慢、且吃带宽。
     * 失败只降级成「没有变更清单」，sha 与运行不受影响。
     */
    public List<PipelineSourceRefDTO> preview(String pipelineId, String canvasJson,
                                              String callerTenantId, boolean withDiff) {
        return resolve(pipelineId, canvasJson, callerTenantId, Map.of(), withDiff);
    }

    /**
     * 解析每个 git 节点的源码信息。
     *
     * @param pins        本次运行**钉住**的提交（节点 id → 完整 sha）。非空时该节点不再 ls-remote，
     *                    直接按这个 sha 记录 —— 用户看到的就是这次真正构建的那个。
     * @param withDiff    是否浅取提交对象（标题/作者/父提交）并算变更清单。**只有用户点「查看变更」才要**：
     *                    触发是运行的关键路径，为一个展示用的标题去下载整棵树不值得。
     */
    public List<PipelineSourceRefDTO> resolve(String pipelineId, String canvasJson,
                                              String callerTenantId, Map<String, String> pins,
                                              boolean withDiff) {
        List<GitNode> nodes = gitNodes(canvasJson);
        if (nodes.isEmpty()) {
            return List.of();
        }
        // ⚠️ **并行**：每个节点是一次网络往返，超时是按**每条命令**算的（默认 30s）。
        //    串行的话 N 个 git 节点最坏就是 N × 30s —— 界面上就是「一直在解析仓库」。
        //    虚拟线程：这活全是阻塞 IO，不值得占一个平台线程池。
        List<PipelineSourceRefDTO> out = new ArrayList<>(nodes.size());
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<PipelineSourceRefDTO>> futures = new ArrayList<>(nodes.size());
            for (GitNode node : nodes) {
                String pin = pins == null ? null : pins.get(node.nodeId());
                futures.add(pool.submit(
                        () -> resolveOne(pipelineId, node, callerTenantId, pin, withDiff)));
            }
            for (int i = 0; i < nodes.size(); i++) {
                GitNode node = nodes.get(i);
                PipelineSourceRefDTO dto;
                try {
                    dto = futures.get(i).get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    dto = PipelineSourceRefDTO.failed(node.nodeId(), node.repo(), node.revision(),
                            "解析被中断");
                } catch (ExecutionException e) {
                    // resolveOne 自己兜异常；走到这里说明是没料到的运行时错误 —— 也别让整次预览塌掉
                    dto = PipelineSourceRefDTO.failed(node.nodeId(), node.repo(), node.revision(),
                            "解析出错：" + messageOf(e.getCause() == null ? e : e.getCause()));
                }
                if (dto.error() != null) {
                    // 这一条**必须打**：失败只回给前端的话，运维在日志里看不到任何痕迹
                    log.warn("解析 git 节点源码失败：pipeline={} node={} repo={} err={}",
                            pipelineId, dto.nodeId(), dto.repo(), dto.error());
                }
                out.add(dto);
            }
        }
        return out;
    }

    /** 触发时落库用：任何失败都不抛出（预览不是运行的前提）。走轻的那一档，不下载对象。 */
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

    /**
     * 一次历史运行的变更清单：按落库的 {@code sha/baseSha} 现算。
     *
     * <p>为什么不落库清单：一次格式化能改几千个文件，体积不可控；而 sha 是廉价的、
     * 且永远够用来重算。代价是凭据/仓库当时可用 —— 都不可用时如实回原因。
     *
     * @param target 从运行行的 {@code source_json} + {@code canvas_json} 拼出来（见
     *               {@code PipelineRunService#diff}）
     */
    public PipelineSourceRefDTO diff(DiffTarget target, String callerTenantId) {
        CredentialsProvider cred;
        try {
            cred = credentialsFor(target.connectionId(), callerTenantId);
        } catch (RuntimeException e) {
            return PipelineSourceRefDTO.resolved(target.nodeId(), target.repo(), target.revision(),
                    target.kind(), target.sha(), shortSha(target.sha()),
                    target.baseSha(), target.baseKind(), target.baseRunName(),
                    "连接不可用：" + messageOf(e));
        }

        RepoSession session;
        try {
            session = new RepoSession(normalizeRepo(target.repo()), cred, timeoutSeconds);
        } catch (RuntimeException e) {
            return PipelineSourceRefDTO.resolved(target.nodeId(), target.repo(), target.revision(),
                    target.kind(), target.sha(), shortSha(target.sha()),
                    target.baseSha(), target.baseKind(), target.baseRunName(),
                    "创建临时仓库失败：" + messageOf(e));
        }
        try {
            CommitInfo info = session.readCommit(target.sha());
            DiffResult diff = null;
            String warning = info == null ? "取不到提交信息（可能是不允许按 sha 浅取的仓库）" : null;
            if (info != null && StringUtils.hasText(target.baseSha())
                    && !target.baseSha().equals(target.sha())) {
                try {
                    diff = session.diff(target.baseSha(), target.sha(), maxFiles);
                } catch (Exception e) {
                    warning = "变更清单不可用：" + messageOf(e);
                }
            }
            return new PipelineSourceRefDTO(target.nodeId(), target.repo(), target.revision(),
                    target.kind(), target.sha(), shortSha(target.sha()),
                    info == null ? null : info.subject(),
                    info == null ? null : info.author(),
                    info == null ? null : info.committedAt(),
                    target.baseSha(), target.baseKind(), target.baseRunName(),
                    diff == null ? null : diff.files(), diff == null ? 0 : diff.fileCount(),
                    diff == null ? 0 : diff.additions(), diff == null ? 0 : diff.deletions(),
                    diff != null && diff.truncated(),
                    warning, null);
        } finally {
            session.close();
        }
    }

    /** 钉住值形状校验；不合法直接拒（不会静默丢掉一个用户以为生效的 pin）。 */
    public static boolean isFullSha(String s) {
        return s != null && FULL_SHA.matcher(s.trim()).matches();
    }

    /**
     * 单个文件的补丁正文 —— 变更清单里点开某一行时才走这里。
     *
     * <p>注意代价：JGit 要先把两棵树取进来才比得出来，所以**和 {@link #diff} 一样重**
     * （差别只在返回体）。这也是它不跟着清单一起返回的原因。
     */
    public PipelineFilePatchDTO patch(String repo, String connectionId, String sha, String baseSha,
                                      String path, String callerTenantId) {
        if (!StringUtils.hasText(repo) || !StringUtils.hasText(path)) {
            return PipelineFilePatchDTO.failed(path, "缺少仓库地址或文件路径");
        }
        if (!isFullSha(sha) || !isFullSha(baseSha)) {
            return PipelineFilePatchDTO.failed(path, "提交 sha 不完整，取不到补丁");
        }
        if (sha.equals(baseSha)) {
            return PipelineFilePatchDTO.failed(path, "与基线是同一个提交，没有变更");
        }
        CredentialsProvider cred;
        try {
            cred = credentialsFor(connectionId, callerTenantId);
        } catch (RuntimeException e) {
            return PipelineFilePatchDTO.failed(path, "连接不可用：" + messageOf(e));
        }
        RepoSession session;
        try {
            session = new RepoSession(normalizeRepo(repo), cred, timeoutSeconds);
        } catch (RuntimeException e) {
            return PipelineFilePatchDTO.failed(path, "创建临时仓库失败：" + messageOf(e));
        }
        try {
            if (session.readCommit(sha) == null) {
                return PipelineFilePatchDTO.failed(path,
                        "取不到提交信息（可能是不允许按 sha 浅取的仓库）");
            }
            PatchText p = session.patchOf(baseSha, sha, path, MAX_PATCH_LINES);
            String error = p.text() == null && !p.binary()
                    ? "这个文件在两个提交之间没有变化" : null;
            return new PipelineFilePatchDTO(path, p.text(), p.truncated(), p.binary(), error);
        } catch (Exception e) {
            return PipelineFilePatchDTO.failed(path, "取不到补丁：" + messageOf(e));
        } finally {
            session.close();
        }
    }

    /** 历史运行按需算 diff 需要的全部输入（都来自运行行，不来自今天的流水线定义）。 */
    public record DiffTarget(String nodeId, String repo, String revision, String connectionId,
                             String kind, String sha, String baseSha, String baseKind,
                             String baseRunName) {
    }

    // ------------------------------------------------------------------ 单节点

    private PipelineSourceRefDTO resolveOne(String pipelineId, GitNode node,
                                            String callerTenantId, String pin, boolean withDiff) {
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

        // 轻的那一档到此为止：基线只能来自库里记录的上次成功运行（父提交要浅取才知道）
        if (!withDiff) {
            Baseline base = baseline(pipelineId, node.nodeId(), null);
            return PipelineSourceRefDTO.resolved(node.nodeId(), repo, node.revision(), kind, sha,
                    shortSha(sha), base.sha(), base.kind(), base.runName(), null);
        }

        // 重的那一档：一个节点一个临时仓库，两棵树只下一次
        RepoSession session;
        try {
            session = new RepoSession(url, cred, timeoutSeconds);
        } catch (RuntimeException e) {
            Baseline base = baseline(pipelineId, node.nodeId(), null);
            return PipelineSourceRefDTO.resolved(node.nodeId(), repo, node.revision(), kind, sha,
                    shortSha(sha), base.sha(), base.kind(), base.runName(),
                    "创建临时仓库失败：" + messageOf(e));
        }
        try {
            CommitInfo info = session.readCommit(sha);
            Baseline base = baseline(pipelineId, node.nodeId(), info == null ? null : info.parentSha());
            DiffResult diff = null;
            String warning = info == null
                    ? "取不到提交信息（可能是不允许按 sha 浅取的仓库）" : null;
            // 与基线相同 = 这次没有新代码，没必要再下一棵树
            if (info != null && StringUtils.hasText(base.sha()) && !base.sha().equals(sha)) {
                try {
                    diff = session.diff(base.sha(), sha, maxFiles);
                } catch (Exception e) {
                    warning = "变更清单不可用：" + messageOf(e);
                }
            }
            return new PipelineSourceRefDTO(node.nodeId(), repo, node.revision(), kind, sha,
                    shortSha(sha),
                    info == null ? null : info.subject(),
                    info == null ? null : info.author(),
                    info == null ? null : info.committedAt(),
                    base.sha(), base.kind(), base.runName(),
                    diff == null ? null : diff.files(), diff == null ? 0 : diff.fileCount(),
                    diff == null ? 0 : diff.additions(), diff == null ? 0 : diff.deletions(),
                    diff != null && diff.truncated(),
                    warning, null);
        } finally {
            session.close();
        }
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

    // ------------------------------------------------------------------ 临时仓库

    /**
     * 一个节点一个临时仓库：浅取提交对象、读元数据、算 diff 都在这里，
     * 两棵树只下一遍（{@code readCommit} 先下的那棵 head 树，diff 直接复用）。
     *
     * <p>{@code close()} 递归删目录；凭据只在内存（不写 {@code .netrc}）。
     */
    private static final class RepoSession implements AutoCloseable {

        private final Git git;
        private final Path dir;
        private final String url;
        private final CredentialsProvider cred;
        private final int timeoutSeconds;

        private RepoSession(String url, CredentialsProvider cred, int timeoutSeconds) {
            this.url = url;
            this.cred = cred;
            this.timeoutSeconds = timeoutSeconds;
            Path created = null;
            Git opened = null;
            try {
                created = Files.createTempDirectory("pipe-preview-");
                opened = Git.init().setDirectory(created.toFile()).call();
            } catch (Exception e) {
                deleteQuietly(created);
                throw new IllegalStateException(e.getMessage(), e);
            }
            this.dir = created;
            this.git = opened;
        }

        /** 按 sha 浅取到一个临时 ref —— git 协议允许 depth 1 只取那一个提交（整棵树）。 */
        private void fetch(String sha, String refName) throws Exception {
            var fetch = git.fetch()
                    .setRemote(url)
                    .setRefSpecs(new RefSpec("+" + sha + ":" + refName))
                    .setDepth(1)
                    .setTimeout(timeoutSeconds);
            if (cred != null) {
                fetch.setCredentialsProvider(cred);
            }
            fetch.call();
        }

        /**
         * 浅取一个 commit 并读元数据（标题/作者/时间/父提交）。
         *
         * <p>失败返回 null（调用方只给 warning，不影响 sha）：最常见的失败是自建源禁止按 sha
         * 浅取（{@code uploadpack.allowReachableSHA1InWant=false}）—— 那种源上「钉住」仍会在
         * 运行时由 git 节点自己核对/报错，预览这边不该因此挡住运行。
         */
        private CommitInfo readCommit(String sha) {
            try {
                fetch(sha, "refs/preview/head");
                try (RevWalk walk = new RevWalk(git.getRepository())) {
                    RevObject any = walk.parseAny(ObjectId.fromString(sha));
                    if (any instanceof RevTag tag) {
                        any = walk.peel(tag);
                    }
                    RevCommit commit = walk.parseCommit(any);
                    return new CommitInfo(commit.getShortMessage(), commit.getAuthorIdent().getName(),
                            Instant.ofEpochSecond(commit.getCommitTime()).toString(),
                            firstParentOf(git.getRepository(), commit));
                }
            } catch (Exception e) {
                log.info("浅取提交信息失败（不影响运行）：{} {}", sha, e.toString());
                return null;
            }
        }

        /**
         * 两个提交之间的文件级变更（{@code git diff --name-status} + 增删行数）。
         *
         * <p>depth 1 够用：{@code diff A B} 只需要两棵树，不需要共同祖先 ——
         * 所以「相差几个提交」这类需要完整历史的统计这里**不提供**。
         *
         * <p>前置：head 已经由 {@link #readCommit} 取进来（同一个临时仓库）。
         */
        private DiffResult diff(String baseSha, String headSha, int maxFiles) throws Exception {
            fetch(baseSha, "refs/preview/base");
            Repository repo = git.getRepository();
            try (ObjectReader reader = repo.newObjectReader();
                 RevWalk walk = new RevWalk(repo);
                 DiffFormatter df = new DiffFormatter(OutputStream.nullOutputStream())) {
                RevCommit base = walk.parseCommit(ObjectId.fromString(baseSha));
                RevCommit head = walk.parseCommit(ObjectId.fromString(headSha));
                CanonicalTreeParser a = new CanonicalTreeParser();
                a.reset(reader, base.getTree());
                CanonicalTreeParser b = new CanonicalTreeParser();
                b.reset(reader, head.getTree());

                df.setRepository(repo);
                // 改名要认成 R 而不是「一堆 A + 一堆 D」—— 否则界面会把一次 mv 读成两份代码
                df.setDetectRenames(true);

                List<DiffEntry> entries = df.scan(a, b);
                List<PipelineFileChangeDTO> files = new ArrayList<>();
                int adds = 0;
                int dels = 0;
                for (DiffEntry entry : entries) {
                    String status = statusOf(entry);
                    // 删除的文件没有新路径（JGit 给的是 /dev/null），改名要带上原路径
                    String path = "D".equals(status) ? entry.getOldPath() : entry.getNewPath();
                    String oldPath = ("R".equals(status) || "C".equals(status))
                            ? entry.getOldPath() : null;

                    FileHeader fh = df.toFileHeader(entry);
                    boolean binary = fh.getPatchType() == FileHeader.PatchType.BINARY;
                    int add = 0;
                    int del = 0;
                    if (!binary) {
                        for (Edit edit : fh.toEditList()) {
                            switch (edit.getType()) {
                                case INSERT -> add += edit.getLengthB();
                                case DELETE -> del += edit.getLengthA();
                                case REPLACE -> {
                                    add += edit.getLengthB();
                                    del += edit.getLengthA();
                                }
                                default -> { /* EMPTY：没有可数的行 */ }
                            }
                        }
                    }
                    adds += add;
                    dels += del;
                    // 行数统计照常累加（要的是总数），只是列表不再追加
                    if (files.size() < maxFiles) {
                        files.add(new PipelineFileChangeDTO(path, status, oldPath, add, del, binary));
                    }
                }
                return new DiffResult(files, entries.size(), adds, dels, entries.size() > maxFiles);
            }
        }

        /**
         * 这个提交的第一个父提交（合并提交取第一条）。
         *
         * <p>⚠️ 不能读 {@code RevCommit.getParentCount()}：depth 1 取下来的提交是 **shallow 边界提交**，
         * JGit（与 git CLI 的 {@code rev-parse HEAD^} 一样）把它当成「没有父提交」。而
         * 「首次运行的基线 = 上一提交」正需要这个 id —— 所以直接读提交对象的原始字节里那行
         * {@code parent <sha>}。只多读几十字节，比再下载一整棵父树便宜得多。
         */
        private static String firstParentOf(Repository repo, ObjectId commitId) {
            try (ObjectReader reader = repo.newObjectReader()) {
                ObjectLoader loader = reader.open(commitId, Constants.OBJ_COMMIT);
                String raw = new String(loader.getCachedBytes(PARENT_PROBE_BYTES), StandardCharsets.UTF_8);
                // 头部到第一个空行为止，后面是提交说明 —— 只在头部里找 parent
                return raw.lines()
                        .takeWhile(line -> !line.isBlank())
                        .filter(line -> line.startsWith("parent "))
                        .findFirst()
                        .map(line -> line.substring("parent ".length()).trim())
                        .orElse(null);
            } catch (Exception e) {
                log.info("读父提交失败（基线退回上次成功运行）：{}", e.toString());
                return null;
            }
        }

        /**
         * 单个文件的补丁正文（点开某个文件时按需取）。
         *
         * <p>和 {@link #diff} 用同一套两棵树，只是加了 {@link PathFilter} 并把补丁**写出来**
         * 而不是只数行。行数/字节数上限由常量控制，超出明确标 {@code truncated}
         * —— 截断要让人看得见，不能悄悄少几行。
         */
        private PatchText patchOf(String baseSha, String headSha, String path, int maxLines)
                throws Exception {
            fetch(baseSha, "refs/preview/base");
            Repository repo = git.getRepository();
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            try (ObjectReader reader = repo.newObjectReader();
                 RevWalk walk = new RevWalk(repo);
                 DiffFormatter df = new DiffFormatter(buf)) {
                RevCommit base = walk.parseCommit(ObjectId.fromString(baseSha));
                RevCommit head = walk.parseCommit(ObjectId.fromString(headSha));
                CanonicalTreeParser a = new CanonicalTreeParser();
                a.reset(reader, base.getTree());
                CanonicalTreeParser b = new CanonicalTreeParser();
                b.reset(reader, head.getTree());

                df.setRepository(repo);
                df.setDetectRenames(true);
                df.setPathFilter(PathFilter.create(path));

                List<DiffEntry> entries = df.scan(a, b);
                if (entries.isEmpty()) {
                    return new PatchText(null, false, false);
                }
                // 二进制没有可读的补丁：别把「Binary files differ」当成内容给人看
                if (df.toFileHeader(entries.get(0)).getPatchType() == FileHeader.PatchType.BINARY) {
                    return new PatchText(null, false, true);
                }
                df.format(entries);
            }

            String raw = buf.toString(StandardCharsets.UTF_8);
            boolean truncated = buf.size() > MAX_PATCH_BYTES;
            if (truncated) {
                // 先按字节砍到上限附近，再按行切一次 —— 免得把多字节字符砍成半个
                raw = raw.substring(0, Math.min(raw.length(), MAX_PATCH_BYTES));
                raw = raw.substring(0, raw.lastIndexOf('\n') + 1);
            }
            List<String> lines = raw.lines().toList();
            if (lines.size() > maxLines) {
                return new PatchText(String.join("\n", lines.subList(0, maxLines)), true, false);
            }
            return new PatchText(raw, truncated, false);
        }

        private static String statusOf(DiffEntry entry) {
            return switch (entry.getChangeType()) {
                case ADD -> "A";
                case MODIFY -> "M";
                case DELETE -> "D";
                case RENAME -> "R";
                case COPY -> "C";
            };
        }

        @Override
        public void close() {
            try {
                git.close();
            } catch (RuntimeException ignore) {
                // 关不掉也照删目录
            }
            deleteQuietly(dir);
        }
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

    private record DiffResult(List<PipelineFileChangeDTO> files, int fileCount,
                              int additions, int deletions, boolean truncated) {
    }

    /** 一个文件的补丁：正文 + 截断/二进制标记。 */
    private record PatchText(String text, boolean truncated, boolean binary) {
    }

    /**
     * JGit 的 HTTP 连接工厂：**按 OutboundProxy 的白名单选代理**，其余直连。
     *
     * <p>JGit 的 {@code Transport} 没有 setProxy，代理只能从连接工厂这一层给。实现
     * {@link HttpConnectionFactory2} 是为了保住 git 协议 v2（只实现基础接口会退回 v0）。
     *
     * <p>⚠️ <b>必须实现的是两个参数的 {@code create(url, proxy)}</b>：JGit 6.10 的
     * {@code TransportHttp} 在构造时取 {@code ProxySelector.getDefault()} 自己先算一遍代理，
     * 再调 {@code factory.create(u, proxy)} —— 默认选择器不知道我们的白名单，传进来的永远是
     * {@code DIRECT}。只实现 {@code create(url)} 是**死代码**（2026-11-18 实测：github 直连超时，
     * 报 {@code connection failed}，日志里看不出任何代理痕迹）。
     */
    private static final class ProxyAwareConnectionFactory implements HttpConnectionFactory2 {

        private final JDKHttpConnectionFactory delegate = new JDKHttpConnectionFactory();
        private final ProxySelector selector;

        private ProxyAwareConnectionFactory(ProxySelector selector) {
            this.selector = selector;
        }

        @Override
        public HttpConnection create(URL url) throws IOException {
            Proxy chosen = proxyFor(url);
            return chosen == null ? delegate.create(url) : delegate.create(url, chosen);
        }

        @Override
        public HttpConnection create(URL url, Proxy proxy) throws IOException {
            // 白名单命中才覆盖 JGit 传进来的（通常是 DIRECT）；没命中就照它说的直连
            Proxy chosen = proxyFor(url);
            if (chosen != null) {
                log.debug("git 出站走代理：{} -> {}", url.getHost(), chosen.address());
            }
            return delegate.create(url, chosen == null ? proxy : chosen);
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
