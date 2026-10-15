package com.coding.k8score.operations.pipelinerun;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.k8s.dto.PipelineRunDTO;
import com.coding.k8score.converter.impl.pipeline.PipelineRunConverter;
import com.coding.k8score.operations.NamespacedOperations;
import com.coding.k8score.pipeline.PipelineCanvasOperations;
import com.coding.k8score.pipeline.PipelineRenderer;
import io.fabric8.kubernetes.api.model.EmptyDirVolumeSource;
import io.fabric8.kubernetes.api.model.ListOptions;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaim;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaimSpec;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaimVolumeSource;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.SecretVolumeSource;
import io.fabric8.kubernetes.api.model.VolumeResourceRequirements;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.Watch;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.kubernetes.client.WatcherException;
import io.fabric8.kubernetes.client.utils.Serialization;
import io.fabric8.tekton.v1.PipelineRun;
import io.fabric8.tekton.v1.PipelineRunSpec;
import io.fabric8.tekton.v1.PipelineSpec;
import io.fabric8.tekton.v1.StepState;
import io.fabric8.tekton.v1.TaskRun;
import io.fabric8.tekton.v1.PipelineRunStatus;
import io.fabric8.tekton.v1.TaskRunStatus;
import com.coding.common.models.pipeline.PipelineCacheStatusDTO;
import com.coding.common.models.pipeline.PipelineConnectionRef;
import com.coding.common.models.pipeline.PipelineRunSnapshotDTO;
import com.coding.common.models.pipeline.PipelineStepRefDTO;
import io.fabric8.tekton.v1.PipelineSpec;
import io.fabric8.tekton.v1.PipelineWorkspaceDeclaration;
import io.fabric8.tekton.v1.WorkspaceBinding;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Tekton {@code PipelineRun} 操作（{@code tekton.dev/v1}，命名空间级、租户域）。
 *
 * <h2>⚠️ 本类最重要的不变量：只碰平台自己创建的 run</h2>
 * 集群里可能有**别人手工建的** {@code PipelineRun}（运维调试、别的 CI 系统）。
 * 平台会**删掉**归档完的 run —— 不带过滤的话就是删别人的东西，这是本批最容易造成事故的一处。
 * 所以 {@link #MANAGED_SELECTOR} 出现在每一个读与每一个写上，并且
 * {@link #assertManaged} 是最后一道闸（spec §4.1.1）。
 *
 * <p>⚠️ 读单对象的三条路（get / delete / yaml / cancel）走的是「先 get 再判标签」而不是
 * 带 selector 的 get —— K8s 的单对象 get 不支持 labelSelector。判标签这一步不能省：
 * 少了它，「看不见别人的 run」就只是 list 的假象，直接按名字打过来照样能删。
 */
@Slf4j
@RequiredArgsConstructor
public class PipelineRunOperations implements NamespacedOperations<PipelineRunDTO> {

    public static final String MANAGED_LABEL = "app.kubernetes.io/managed-by";
    public static final String MANAGED_VALUE = "platform-system";
    public static final String MANAGED_SELECTOR = MANAGED_LABEL + "=" + MANAGED_VALUE;

    /**
     * 隐式共享 workspace 每次运行要的那块临时 PVC。
     *
     * <p>⚠️ 有意**不做成配置项**：它是一次运行的工作区大小，不是集群策略；平台也没有给用户
     * 「选 StorageClass / 定配额」的入口（spec 已定不做）。真要调的时候是改这里一行。
     * 用完即删（PipelineRun 被归档删除时级联）。
     */
    static final String WORKSPACE_STORAGE = "1Gi";

    /**
     * 存本次运行凭据的那个 Secret 的名字。**由 k8s-server 在创建 PipelineRun 之前建好** ——
     * 两边必须算出同一个名字，所以它是这里的一个常量式方法，不是两处各自的拼接。
     */
    public static String credsSecretName(String runName) {
        return "platform-pipeline-creds-" + runName;
    }

    /** 依赖缓存卷的名字。**每个命名空间一块**，平台建、**跨运行保留**（跑完不删）。 */
    public static final String CACHE_PVC = "platform-pipeline-cache";

    /** 缓存卷大小（用户 2026-10-15 定）。这是缓存不是仓库：满了就构建自己失败，不会咬到别人。 */
    public static final String CACHE_STORAGE = "10Gi";

    /**
     * 等缓存卷绑定的上限，**只在刚创建那一次等**。
     *
     * <p>等到了这次就能用上缓存；等不到就这一次先不用（下次再说）。
     * 给不了共享卷的集群（只有块存储的）会一直 Pending —— 那时之后每次都是立即返回「不可用」。
     */
    private static final long CACHE_BIND_WAIT_MS = 8_000;

    /**
     * 这次运行到底用不用得上缓存卷 —— 看渲染出来的 spec 里**有没有声明** {@code cache} workspace。
     * 没声明的运行连一次 GET 都不发（绝大多数流水线用不到缓存）。
     */
    private static boolean declaresCache(PipelineRun pr) {
        PipelineSpec spec = pr.getSpec() == null ? null : pr.getSpec().getPipelineSpec();
        if (spec == null || spec.getWorkspaces() == null) {
            return false;
        }
        return spec.getWorkspaces().stream()
                .anyMatch(w -> PipelineRenderer.CACHE_WORKSPACE.equals(w.getName()));
    }

    /**
     * 确保命名空间里那块缓存卷**可用**，可用返回 claim 名，否则 {@code null}（调用方退化成空目录）。
     *
     * <p>⚠️ 一律按 {@code ReadWriteMany} 建 —— 「共享缓存」的前提就是多个 Pod 能同时挂它。
     * 只有块存储（RWO）的集群会让它一直 Pending：那时这里**永远返回 null**，
     * 也就是缓存自动关闭、每次都重下，而不是让某次运行卡在挂载上或直接判失败（用户 2026-10-15 定的降级）。
     */
    private String ensureCachePvc(String namespace) {
        PersistentVolumeClaim pvc = client.persistentVolumeClaims().inNamespace(namespace)
                .withName(CACHE_PVC).get();
        if (pvc == null) {
            pvc = createCachePvc(namespace);
            pvc = pvc == null ? null : waitForBound(namespace);
        }
        if (pvc == null || pvc.getMetadata() == null
                || pvc.getMetadata().getDeletionTimestamp() != null) {
            // 删除中（刚点过「清缓存」）：名字还被占着，这次不带缓存，下次运行会重建一块空的
            return null;
        }
        return isBound(pvc) ? CACHE_PVC : null;
    }

    /**
     * 构建缓存现在什么状态（页面上的「构建缓存」那一行读它）。
     *
     * <p>⚠️ 三种「不可用」要分清楚，因为**用户能做的事不一样**：
     * 没建（等第一次用到缓存的运行自动建）、正在删（刚清过，下次运行重建）、
     * 绑不上（这个集群的存储不支持共享卷 —— 那就不该建，得靠集群侧解决）。
     */
    public PipelineCacheStatusDTO cacheStatus(String namespace) {
        PersistentVolumeClaim pvc = client.persistentVolumeClaims().inNamespace(namespace)
                .withName(CACHE_PVC).get();
        if (pvc == null) {
            return new PipelineCacheStatusDTO(false, false, CACHE_PVC, CACHE_STORAGE,
                    "还没建 —— 第一次用到缓存的运行会自动建一块 " + CACHE_STORAGE + " 的共享卷");
        }
        if (pvc.getMetadata() != null && pvc.getMetadata().getDeletionTimestamp() != null) {
            return new PipelineCacheStatusDTO(false, true, CACHE_PVC, CACHE_STORAGE,
                    "正在删除（刚清过）—— 下次运行会重建一块空的");
        }
        if (!isBound(pvc)) {
            return new PipelineCacheStatusDTO(false, true, CACHE_PVC, CACHE_STORAGE,
                    "卷绑不上（当前 " + phaseOf(pvc) + "）—— 这个集群的存储大概不支持共享卷"
                            + "（ReadWriteMany），所以缓存自动关闭：照样能跑，只是每次都重下依赖");
        }
        return new PipelineCacheStatusDTO(true, true, CACHE_PVC, capacityOf(pvc), null);
    }

    /**
     * 清掉这个命名空间的构建缓存（删那块卷，下次运行自动重建一块空的）。
     *
     * <p>⚠️ 删的是**卷**而不是里面的文件：要精确清目录就得起一个 Pod 去 {@code rm -rf}，
     * 那比删卷重得多。代价是「正在跑的运行会失去自己的缓存挂载点」—— 所以调用方要先确认。
     * 删不掉（还有 Pod 挂着）就让它抛，别假装成功。
     */
    public void clearCache(String namespace) {
        client.persistentVolumeClaims().inNamespace(namespace).withName(CACHE_PVC).delete();
    }

    private static String phaseOf(PersistentVolumeClaim pvc) {
        return pvc.getStatus() == null ? "Pending" : String.valueOf(pvc.getStatus().getPhase());
    }

    private static String capacityOf(PersistentVolumeClaim pvc) {
        if (pvc.getStatus() == null || pvc.getStatus().getCapacity() == null) {
            return CACHE_STORAGE;
        }
        Quantity q = pvc.getStatus().getCapacity().get("storage");
        // Quantity 的 toString 不保证是原串，拼 amount+format 才是稳定的 "10Gi"
        return q == null ? CACHE_STORAGE : q.getAmount() + (q.getFormat() == null ? "" : q.getFormat());
    }

    /** 见 {@link #CACHE_BIND_WAIT_MS}。 */
    private PersistentVolumeClaim waitForBound(String namespace) {        long deadline = System.currentTimeMillis() + CACHE_BIND_WAIT_MS;
        PersistentVolumeClaim pvc = null;
        while (System.currentTimeMillis() < deadline) {
            pvc = client.persistentVolumeClaims().inNamespace(namespace).withName(CACHE_PVC).get();
            if (pvc != null && isBound(pvc)) {
                return pvc;
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return pvc;
            }
        }
        log.info("构建缓存卷在 {}ms 内没绑定上（这个集群可能给不了共享卷）—— 本次运行不带缓存：{}/{}",
                CACHE_BIND_WAIT_MS, namespace, CACHE_PVC);
        return pvc;
    }

    private static boolean isBound(PersistentVolumeClaim pvc) {
        return pvc.getStatus() != null && "Bound".equals(pvc.getStatus().getPhase());
    }

    private PersistentVolumeClaim createCachePvc(String namespace) {
        VolumeResourceRequirements res = new VolumeResourceRequirements();
        res.setRequests(Map.of("storage", new Quantity(CACHE_STORAGE)));
        PersistentVolumeClaimSpec spec = new PersistentVolumeClaimSpec();
        spec.setAccessModes(List.of("ReadWriteMany"));
        spec.setResources(res);

        PersistentVolumeClaim pvc = new PersistentVolumeClaim();
        // ⚠️ 与 PipelineRun 的 metadata 同理：模型不保证初始化嵌套对象
        pvc.setMetadata(new ObjectMeta());
        pvc.getMetadata().setName(CACHE_PVC);
        pvc.getMetadata().setNamespace(namespace);
        pvc.getMetadata().setLabels(managedLabels(Map.of()));
        pvc.setSpec(spec);
        try {
            return client.persistentVolumeClaims().inNamespace(namespace).resource(pvc).create();
        } catch (KubernetesClientException e) {
            if (e.getCode() == 409) {
                // 并发两条运行同时建（AlreadyExists）—— 不是错，拿现成的那块
                return client.persistentVolumeClaims().inNamespace(namespace).withName(CACHE_PVC).get();
            }
            log.warn("创建构建缓存卷失败（本次运行不带缓存）{}/{}：{}", namespace, CACHE_PVC, apiMessage(e));
            return null;
        }
    }

    private final KubernetesClient client;
    private final PipelineRunConverter converter;
    /** 画布渲染 —— platform-api 不碰 fabric8，所以创建时收到的是画布、渲染在这里做（spec §7 的改定块）。 */
    private final PipelineCanvasOperations canvasOps;

    @Override
    public String apiVersion() {
        return "tekton.dev/v1";
    }

    /** 调用方的标签 + 平台标签。**平台标签最后写** —— 调用方覆盖不了它。 */
    public static Map<String, String> managedLabels(Map<String, String> extra) {
        Map<String, String> out = new HashMap<>(extra == null ? Map.of() : extra);
        out.put(MANAGED_LABEL, MANAGED_VALUE);
        return out;
    }

    /** 不是平台创建的一律拒 —— 读、删、取消都必须先过这里。 */
    public static void assertManaged(Map<String, String> labels, String name) {
        if (labels == null || !MANAGED_VALUE.equals(labels.get(MANAGED_LABEL))) {
            throw new CloudPlatformException(EnumResponseType.PIPELINE_INVALID,
                    "该 PipelineRun 不是平台创建的，平台不会碰它：" + name);
        }
    }

    /** 平台标签与调用方给的 selector 用逗号连接（K8s 的 AND 语义）。 */
    private static String withManaged(String labelSelector) {
        return StringUtils.hasText(labelSelector)
                ? MANAGED_SELECTOR + "," + labelSelector
                : MANAGED_SELECTOR;
    }

    @Override
    public List<PipelineRunDTO> list(String namespace, String labelSelector, String fieldSelector) {
        String ns = StringUtils.hasText(namespace) ? namespace : "";
        ListOptions options = new ListOptions();
        options.setLabelSelector(withManaged(labelSelector));
        if (StringUtils.hasText(fieldSelector)) {
            options.setFieldSelector(fieldSelector);
        }
        return client.resources(PipelineRun.class).inNamespace(ns).list(options).getItems()
                .stream().map(converter::toDto).toList();
    }

    /**
     * 集群级 list：**给启动扫描用** —— 找「带平台标签且还在 running」的 run。
     * 未实现会在接口默认实现里抛 {@code OPERATION_NOT_SUPPORTED}，本类覆写它。
     */
    @Override
    public List<PipelineRunDTO> listAll(String labelSelector, String fieldSelector) {
        ListOptions options = new ListOptions();
        options.setLabelSelector(withManaged(labelSelector));
        if (StringUtils.hasText(fieldSelector)) {
            options.setFieldSelector(fieldSelector);
        }
        return client.resources(PipelineRun.class).inAnyNamespace().list(options).getItems()
                .stream().map(converter::toDto).toList();
    }

    @Override
    public PipelineRunDTO get(String namespace, String name) {
        PipelineRun pr = fetch(namespace, name);
        if (pr == null) {
            return null;
        }
        assertManaged(pr.getMetadata().getLabels(), name);
        return converter.toDto(pr);
    }

    @Override
    public void delete(String namespace, String name) {
        // 先 get 再删 —— 必须确认它带平台标签。级联：删 PipelineRun 会带走 TaskRun 与 **Pod**
        PipelineRun pr = fetch(namespace, name);
        if (pr == null) {
            return;                                   // 幂等：已经没了就是成功
        }
        assertManaged(pr.getMetadata().getLabels(), name);
        client.resources(PipelineRun.class).inNamespace(namespace).withName(name).delete();
    }

    @Override
    public String yaml(String namespace, String name) {
        PipelineRun pr = fetch(namespace, name);
        if (pr == null) {
            return null;
        }
        assertManaged(pr.getMetadata().getLabels(), name);
        return Serialization.asYaml(pr);
    }

    /**
     * 创建。**接收画布、在这里渲染**成内联的 {@code pipelineSpec}（集群里不留 {@code Pipeline} 对象，
     * spec §3.2）并**打上平台标签**（{@link #managedLabels}）。
     *
     * <p>⚠️ 渲染放在这一步而不是 platform-api：那边不依赖 k8s-core/fabric8（spec §7 的改定块）。
     *
     * @param dto 用 {@code pipelineCanvasJson} 传平台自己的画布 JSON
     */
    @Override
    public PipelineRunDTO create(PipelineRunDTO dto) {
        if (!StringUtils.hasText(dto.getPipelineCanvasJson())) {
            throw new CloudPlatformException(EnumResponseType.PIPELINE_INVALID,
                    "创建流水线运行必须带画布");
        }
        // ① 先把凭据建成 Secret（有连接才建）—— Pod 起来时要读得到它
        // ⚠️ 两次 K8s 调用都要把**集群的原话**带出来：以前任何失败都塌成 k8s-server 兜底的那句
        //    「服务端异常」，用户在页面上看不到任何线索（2026-10-15 真环境撞到过）
        try {
            ensureCredsSecret(dto);
        } catch (KubernetesClientException e) {
            throw new CloudPlatformException(EnumResponseType.K8S_OPERATION_REJECTED,
                    "创建凭据 Secret 失败：" + apiMessage(e));
        }

        // ② 再建 PipelineRun
        // ⚠️ 带缓存卷的运行要**先确认那块卷可用**（见 ensureCachePvc）：先按「没有缓存卷」构造一份，
        //    确认真用到了缓存 workspace 才去碰集群里那块卷 —— 不用的运行连一次 GET 都不发。
        PipelineRun pr = buildRun(dto, null);
        if (declaresCache(pr)) {
            pr = buildRun(dto, ensureCachePvc(dto.getNamespace()));
        }
        PipelineRun created;
        try {
            created = client.resources(PipelineRun.class).inNamespace(dto.getNamespace())
                    .resource(pr).create();
        } catch (KubernetesClientException e) {
            throw new CloudPlatformException(EnumResponseType.K8S_OPERATION_REJECTED,
                    "创建 PipelineRun 失败：" + apiMessage(e));
        }
        return converter.toDto(created);
    }

    /**
     * K8s 客户端异常的**原话**：优先取 {@code Status.message}（API server 给的那一句，
     * 例如 {@code pipelineruns.tekton.dev "x" is forbidden: ...}），拿不到才退回异常消息。
     */
    static String apiMessage(KubernetesClientException e) {
        if (e.getStatus() != null && e.getStatus().getMessage() != null) {
            return e.getStatus().getMessage();
        }
        return e.getMessage() == null ? e.toString() : e.getMessage();
    }

    /**
     * 把画布渲染成一个可以交给集群的 {@code PipelineRun}。**纯构造、不碰 client**（所以可单测）。
     *
     * <p>⚠️ <b>隐式共享 workspace 必须在这里绑定</b>，只在 {@code PipelineSpec} 上声明是不够的：
     * Tekton 对**每个非 optional 的 pipeline workspace** 都要求 PipelineRun 给出绑定，
     * 缺了会报 {@code pipeline requires workspace with name "ws" be provided by pipelinerun} ——
     * 而创建本身会成功（webhook 只校验 PipelineRun 自己那份列表），随后 reconciler 把这条运行
     * 标成 Failed：Pod 不创建、没有日志。也就是说「跑得起来」的全部前提就在这一个 setter 上。
     *
     * <p>用 {@code volumeClaimTemplate}（每次运行现开一块临时 PVC）而**不是 emptyDir**：
     * Tekton 的 emptyDir workspace **不跨 TaskRun 共享**，而我们要的正是
     * 「git 克隆的源码给下游所有节点用」（spec §3.3）。
     */
    PipelineRun buildRun(PipelineRunDTO dto) {
        return buildRun(dto, null);
    }

    /**
     * @param cachePvc 依赖缓存卷的 claim 名；{@code null} = 这个集群上不可用 →
     *                 缓存 workspace 退化成**本次运行自己的空目录**（能跑，只是不跨运行复用）
     */
    PipelineRun buildRun(PipelineRunDTO dto, String cachePvc) {
        // ⚠️ 带上本次运行的连接：镜像节点要靠连接的 endpoint 拼目标地址，
        //    解析不出连接它就**拒渲染**（宁可不出来，也不产出必然失败的脚本）。
        Map<String, PipelineConnectionRef> byId = new java.util.LinkedHashMap<>();
        if (dto.getConnections() != null) {
            for (PipelineConnectionRef c : dto.getConnections()) {
                byId.put(c.id(), c);
            }
        }
        PipelineSpec spec = canvasOps.render(dto.getPipelineCanvasJson(), byId);

        PipelineRun pr = new PipelineRun();
        // ⚠️ tekton-model 不初始化 metadata —— 不先 setMetadata 就 NPE
        pr.setMetadata(new ObjectMeta());
        pr.getMetadata().setName(dto.getName());
        pr.getMetadata().setNamespace(dto.getNamespace());
        pr.getMetadata().setLabels(managedLabels(dto.getLabels()));

        PipelineRunSpec runSpec = new PipelineRunSpec();
        runSpec.setPipelineSpec(spec);
        runSpec.setWorkspaces(bindWorkspaces(spec, dto.getName(), cachePvc));
        // ⚠️ 有意**不设** spec.timeouts —— 超时由平台判定（spec §4.2）。让 Tekton 自己超时
        // 会删掉 Pod，那批运行的日志必然丢失，与「日志进库」直接冲突。
        pr.setSpec(runSpec);
        return pr;
    }

    /**
     * 按本次运行引用到的连接建一个 Secret：`config.json`（推送凭据）+ `git-credentials`（.netrc）。
     *
     * <p>⚠️ 凭据**只以文件形式进 Pod**（挂成 workspace），绝不写进 step 的 script ——
     * 后者会让凭据进 PipelineRun 的 spec，而预览页把渲染结果原样给用户看。
     *
     * <p>用 `createOrReplace`：重投 / 重建同一次运行时不会因为「已存在」而失败。
     */
    private void ensureCredsSecret(PipelineRunDTO dto) {
        List<PipelineConnectionRef> conns = dto.getConnections();
        if (conns == null || conns.isEmpty()) {
            return;                       // 没引用连接就不建，Pod 里也就没有 creds 这个 workspace
        }
        Map<String, String> data = new java.util.HashMap<>();
        data.put("config.json", dockerConfigJson(conns));
        String netrc = netrc(conns);
        if (!netrc.isEmpty()) {
            data.put("git-credentials", netrc);
        }

        Map<String, String> encoded = new java.util.LinkedHashMap<>();
        data.forEach((k, v) -> encoded.put(k, java.util.Base64.getEncoder()
                .encodeToString(v.getBytes(java.nio.charset.StandardCharsets.UTF_8))));

        Secret secret = new SecretBuilder()
                .withNewMetadata()
                .withName(credsSecretName(dto.getName()))
                .withNamespace(dto.getNamespace())
                .withLabels(managedLabels(dto.getLabels()))
                .endMetadata()
                .withType("Opaque")
                .addToData(encoded)
                .build();
        client.secrets().inNamespace(dto.getNamespace()).resource(secret).createOrReplace();
    }

    /** docker 的 config.json：`{"auths":{"<host>":{"auth":"base64(user:pass)"}}}` */
    private static String dockerConfigJson(List<PipelineConnectionRef> conns) {
        Map<String, Object> auths = new java.util.LinkedHashMap<>();
        for (PipelineConnectionRef c : conns) {
            if (c.username() == null || c.secret() == null || c.endpoint() == null) {
                continue;                 // 匿名连接没有可写的东西
            }
            auths.put(hostOf(c.endpoint()), Map.of("auth", java.util.Base64.getEncoder().encodeToString(
                    (c.username() + ":" + c.secret()).getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        }
        return Serialization.asJson(Map.of("auths", auths));
    }

    /** git 用的 .netrc（每行一个 machine） */
    private static String netrc(List<PipelineConnectionRef> conns) {
        StringBuilder out = new StringBuilder();
        for (PipelineConnectionRef c : conns) {
            if (c.username() == null || c.secret() == null || c.endpoint() == null) {
                continue;
            }
            out.append("machine ").append(hostOf(c.endpoint()))
               .append(" login ").append(c.username())
               .append(" password ").append(c.secret()).append('\n');
        }
        return out.toString();
    }

    /** endpoint → host（可能是 `https://gitlab.example.com`，也可能是裸的 `harbor.example.com`）。 */
    static String hostOf(String endpoint) {
        String e = endpoint == null ? "" : endpoint.trim();
        int scheme = e.indexOf("://");
        if (scheme >= 0) {
            e = e.substring(scheme + 3);
        }
        int slash = e.indexOf('/');
        return slash >= 0 ? e.substring(0, slash) : e;
    }

    /**
     * 给 spec 里声明的每个 workspace 配绑定 —— **一个都不能漏**（缺了 Tekton 直接拒绝）。
     *
     * <p>三种绑定：隐式共享的那个现开一块 PVC（每次运行一块，跑完随运行一起消失）；
     * 凭据那个**绑到 k8s-server 刚建的 Secret** 上（凭据以文件形式挂进 Pod，绝不进 spec）；
     * 缓存那个绑到**长期保留的那块 PVC**（拿不到就退化成空目录）。
     */
    private static List<WorkspaceBinding> bindWorkspaces(PipelineSpec spec, String runName,
                                                         String cachePvc) {
        if (spec.getWorkspaces() == null || spec.getWorkspaces().isEmpty()) {
            return List.of();
        }
        List<WorkspaceBinding> out = new ArrayList<>(spec.getWorkspaces().size());
        for (PipelineWorkspaceDeclaration decl : spec.getWorkspaces()) {
            WorkspaceBinding b = new WorkspaceBinding();
            b.setName(decl.getName());

            if (PipelineRenderer.CREDS_WORKSPACE.equals(decl.getName())) {
                SecretVolumeSource secret = new SecretVolumeSource();
                secret.setSecretName(credsSecretName(runName));
                b.setSecret(secret);
                out.add(b);
                continue;
            }

            if (PipelineRenderer.CACHE_WORKSPACE.equals(decl.getName())) {
                if (cachePvc == null) {
                    // 退化：这个集群给不了共享卷（或它还没绑上）→ 给本次运行一个自己的空目录。
                    // 工具照写不误（/cache 存在），只是**不跨运行复用** —— 有意的降级：
                    // 宁可每次都重下，也不要为了缓存把整条流水线卡住或判失败
                    b.setEmptyDir(new EmptyDirVolumeSource());
                } else {
                    PersistentVolumeClaimVolumeSource src = new PersistentVolumeClaimVolumeSource();
                    src.setClaimName(cachePvc);
                    b.setPersistentVolumeClaim(src);
                }
                out.add(b);
                continue;
            }

            VolumeResourceRequirements res = new VolumeResourceRequirements();
            res.setRequests(Map.of("storage", new Quantity(WORKSPACE_STORAGE)));
            PersistentVolumeClaimSpec pvcSpec = new PersistentVolumeClaimSpec();
            pvcSpec.setAccessModes(List.of("ReadWriteOnce"));
            pvcSpec.setResources(res);
            PersistentVolumeClaim pvc = new PersistentVolumeClaim();
            // ⚠️ 与 PipelineRun 的 metadata 同理：模型不保证初始化嵌套对象
            pvc.setSpec(pvcSpec);

            b.setVolumeClaimTemplate(pvc);
            out.add(b);
        }
        return out;
    }

    @Override
    public PipelineRunDTO update(PipelineRunDTO resource) {
        // 流水线运行**不可编辑**（改了就不是同一次运行了）。要改就取消重跑。
        throw new CloudPlatformException(EnumResponseType.OPERATION_NOT_SUPPORTED,
                "流水线运行不支持编辑 —— 取消后重新触发一次");
    }

    /**
     * 取消：**patch {@code spec.status}**，不是删对象 —— 删了历史就没了（spec §4.2）。
     *
     * <p>⚠️ 具体取值（v1 里是 {@code "Cancelled"} 还是要用别的）**见 spec §11 待实测** ——
     * 全量测试绿不了这一条，只能在真集群上验。
     */
    @Override
    public void cancel(String namespace, String name) {
        PipelineRun pr = fetch(namespace, name);
        if (pr == null) {
            return;
        }
        assertManaged(pr.getMetadata().getLabels(), name);
        client.resources(PipelineRun.class).inNamespace(namespace).withName(name)
                .edit(p -> {
                    p.getSpec().setStatus("Cancelled");
                    return p;
                });
    }

    /**
     * 删掉这次运行的凭据 Secret（{@link #credsSecretName}）。
     *
     * <p>⚠️ <b>它必须在归档之后、与运行对象同一处删</b>：Secret 里是 base64 的 git token /
     * registry 认证（可解），而集群里每跑一次就留一份 —— 此前没有任何东西回收它，
     * 真环境里攒了三个（2026-10-15）。
     *
     * <p>没引用过连接的运行**根本没有这个 Secret**，删不存在的对象按「已经达到目的」处理，
     * 不往外抛（否则每次没凭据的运行都会记一条没意义的告警）。
     */
    public void deleteCredsSecret(String namespace, String runName) {
        try {
            client.secrets().inNamespace(namespace).withName(credsSecretName(runName)).delete();
        } catch (KubernetesClientException e) {
            if (e.getCode() != 404) {
                throw e;
            }
        }
    }

    /**
     * 一次运行的每个 TaskRun 名（归档时要按它去取 Pod 与日志）。
     */
    public List<String> childTaskRunNames(String namespace, String runName) {
        PipelineRun pr = fetch(namespace, runName);
        if (pr == null || pr.getStatus() == null || pr.getStatus().getChildReferences() == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        pr.getStatus().getChildReferences().forEach(ref -> {
            if (ref.getName() != null) {
                out.add(ref.getName());
            }
        });
        return out;
    }

    /**
     * 盯住一次运行，**终结时回调一次**，然后返回这条 watch（调用方负责关）。
     *
     * <p>⚠️ 按需 watch 的全部形态就在这里：只在有运行在跑时开一条，
     * 用 {@code fieldSelector} 只收这一个对象的事件（一条连接按住整个命名空间的事件是浪费）。
     * 运行结束 → 回调 → 调用方关掉它 → **空闲时零连接**（spec §4.1）。
     *
     * @param onTerminal 终结时调用**一次**（重复事件由调用方按台账去重）
     */
    public Watch watchUntilTerminal(String namespace, String runName, Runnable onTerminal) {
        return client.resources(PipelineRun.class).inNamespace(namespace)
                .withField("metadata.name", runName)
                .watch(new Watcher<PipelineRun>() {
                    @Override
                    public void eventReceived(Action action, PipelineRun item) {
                        if (item == null || action == Action.ERROR) {
                            return;
                        }
                        PipelineRunStatus st = item.getStatus();
                        String status = PipelineRunConverter.mapConditions(
                                st == null ? null : st.getConditions());
                        if (!"RUNNING".equals(status)) {
                            onTerminal.run();
                        }
                    }

                    @Override
                    public void onClose(WatcherException cause) {
                        // 断线不在这里处理：台账仍在，节拍器与启动扫描负责补
                        if (cause != null) {
                            log.debug("运行 watch 关闭（{}）：{}", runName, cause.getMessage());
                        }
                    }
                });
    }

    /**
     * 一次终结的运行的**完整快照**：状态 + 每个节点的状态与日志。**抓完就能删对象了。**
     *
     * <p>⚠️ 它由 k8s-server 调（watch 是它开的，收到终结事件时集群对象、TaskRun、Pod 都还在）。
     * 快照**不出进程**：k8s-server 自己把它写进平台库（{@code PipelineArchiveService}，
     * 它本来就有 platform-data 与事务）—— 见那个类的 javadoc：这条改定让「免认证入站端点 +
     * 共享密钥 + ack/待补/重投」整条消失，入站攻击面归零。
     *
     * @param maxLogBytes 单步日志上限。**交给 API 侧截**（{@code limitBytes}）而不是全读进来再切 ——
     *                    一次构建的日志可以是几百 MB。代价是「有没有被截断」只能靠长度推断
     * @return 对象已经不在时返回 null（重投 / 他方已删）
     */
    public PipelineRunSnapshotDTO snapshot(String clusterId, String namespace, String runName, int maxLogBytes) {
        PipelineRun pr = fetch(namespace, runName);
        if (pr == null) {
            return null;
        }
        assertManaged(pr.getMetadata().getLabels(), runName);

        PipelineRunStatus st = pr.getStatus();
        String status = PipelineRunConverter.mapConditions(st == null ? null : st.getConditions());

        List<PipelineStepRefDTO> refs = steps(namespace, runName);
        List<PipelineRunSnapshotDTO.StepLog> steps = new ArrayList<>();
        for (PipelineStepRefDTO step : refs) {
            StringBuilder log = new StringBuilder();
            boolean truncated = false;
            int size = 0;
            if (step.podName() != null && !step.podName().isBlank()) {
                for (String container : step.containers()) {
                    String part = podLog(namespace, step.podName(), container, maxLogBytes);
                    log.append(part);
                    size += part.length();
                    // limitBytes 是 API 侧截的，拿不到「原始有多长」—— 到顶就按截断记
                    truncated = truncated || part.length() >= maxLogBytes;
                }
            }
            steps.add(new PipelineRunSnapshotDTO.StepLog(
                    step.nodeName(), step.status(), log.toString(), truncated, size));
        }

        return new PipelineRunSnapshotDTO(
                clusterId, namespace, runName, status,
                // 「为什么失败」优先用失败那个节点的原话 —— 顶层那句 `Failed` 排障时等于没有信息
                failureReasonOf(refs, reasonOf(st)),
                st == null ? null : st.getStartTime(),
                st == null ? null : st.getCompletionTime(), steps,
                // 对象马上就会被删 —— 「当年跑的是什么」只有这一刻能抓下来
                Serialization.asYaml(pr));
    }

    /** 读一个容器的日志并**在 API 侧截断**（一次构建的日志可能是几百 MB，不能全读进内存）。 */
    private String podLog(String namespace, String podName, String container, int maxLogBytes) {
        try {
            String text = client.pods().inNamespace(namespace).withName(podName)
                    .inContainer(container)
                    .limitBytes(maxLogBytes)
                    .getLog();
            return text == null ? "" : text;
        } catch (Exception e) {
            // ⚠️ 竞态：抓到一半 Pod 没了 / 容器还没起来 —— 抓到多少算多少，**不整条丢**
            log.debug("抓运行日志失败（按已抓到的部分记）：{}/{}", podName, container, e);
            return "";
        }
    }

    private static String reasonOf(PipelineRunStatus st) {
        if (st == null || st.getConditions() == null) {
            return null;
        }
        return st.getConditions().stream()
                .filter(c -> "Succeeded".equals(c.getType()))
                .map(io.fabric8.knative.pkg.apis.Condition::getReason)
                .findFirst().orElse(null);
    }

    /**
     * 运行「为什么是这个结果」—— **能拿到具体报错就用具体的**。
     *
     * <p>⚠️ PipelineRun 自己的 condition reason 是**统计口径**：失败时就是一句 {@code Failed}
     * （message 也只是 {@code Tasks Completed: 1 (Failed: 1, ...)}），排障时等于没有信息。
     * 真因写在**失败那个节点**的 TaskRun condition message 上，例如
     * {@code [User error] declared workspace "ws" is required but has not been bound}。
     * 而对象一归档就被删，所以这句话只有抓快照这一刻能拿到（2026-10-15 真跑撞到：
     * 用户在运行历史里只看到一句 Failed）。
     *
     * @param fallback Tekton 自己给的 reason（没有更具体的就用它，绝不编一个）
     */
    static String failureReasonOf(List<PipelineStepRefDTO> steps, String fallback) {
        if (steps == null) {
            return fallback;
        }
        return steps.stream()
                .filter(s -> !"SUCCEEDED".equals(s.status()) && !"RUNNING".equals(s.status()))
                .map(PipelineStepRefDTO::message)
                .filter(m -> m != null && !m.isBlank())
                .findFirst().orElse(fallback);
    }

    /** TaskRun 的 Succeeded condition 上的 message —— Tekton 把「为什么失败」写在这里。 */
    private static String messageOf(TaskRunStatus st) {
        if (st == null || st.getConditions() == null) {
            return null;
        }
        return st.getConditions().stream()
                .filter(c -> "Succeeded".equals(c.getType()))
                .map(io.fabric8.knative.pkg.apis.Condition::getMessage)
                .filter(m -> m != null && !m.isBlank())
                .findFirst().orElse(null);
    }

    /**
     * 一条运行里每个节点在集群上的落点（Pod + 容器）。
     *
     * <p>⚠️ <b>「run → TaskRun → Pod → 容器」这套解析放在这里</b>，不放在 platform-api：
     * 那是 Tekton 的结构知识，而 platform-api 不依赖 k8s-core（spec §7 的改定块）。
     * 回的是**已经解析好的落点**，调用方拿着 podName + containers 去走既有的 Pod 日志通道。
     */
    public List<PipelineStepRefDTO> steps(String namespace, String runName) {
        PipelineRun pr = fetch(namespace, runName);
        if (pr == null) {
            return List.of();
        }
        assertManaged(pr.getMetadata().getLabels(), runName);

        List<PipelineStepRefDTO> out = new ArrayList<>();
        for (String taskRunName : childTaskRunNames(namespace, runName)) {
            TaskRun tr = client.resources(TaskRun.class).inNamespace(namespace)
                    .withName(taskRunName).get();
            if (tr != null) {
                out.add(toStepRef(taskRunName, tr));
            }
        }
        return out;
    }

    /**
     * TaskRun → 落点。**纯函数**（不碰 client），所以可单测。
     *
     * <p>节点名取 TaskRun 名：平台的 PipelineTask 名就是画布节点 id，Tekton 给子对象起名时
     * 用的是那个名字（可能带后缀），所以 {@code childReferences} 里的名字才是准的 ——
     * 这里直接用它。
     */
    static PipelineStepRefDTO toStepRef(String nodeName, TaskRun tr) {
        TaskRunStatus st = tr.getStatus();
        String status = PipelineRunConverter.mapConditions(st == null ? null : st.getConditions());
        String podName = st == null ? null : st.getPodName();
        List<String> containers = (st == null || st.getSteps() == null) ? List.of()
                : st.getSteps().stream()
                        .map(StepState::getContainer)
                        .filter(c -> c != null && !c.isBlank())
                        .toList();
        return new PipelineStepRefDTO(nodeName, status, podName, containers, messageOf(st));
    }

    private PipelineRun fetch(String namespace, String name) {
        return client.resources(PipelineRun.class).inNamespace(namespace).withName(name).get();
    }
}
