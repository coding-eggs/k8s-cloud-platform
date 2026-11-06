package com.coding.k8sserver.kube;

import com.coding.k8score.operations.ServerSideApply;
import com.coding.k8sserver.helm.HelmResult;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.apiextensions.v1.CustomResourceDefinition;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.KubernetesClientTimeoutException;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.fabric8.kubernetes.client.utils.KubernetesSerialization;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.representer.Representer;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.resolver.Resolver;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * 把一整份多文档 YAML 清单直接写进集群 / 从集群删掉 / 等它就绪 —— 全部走 fabric8，不依赖 kubectl 二进制。
 *
 * <p><b>为什么不用 kubectl</b>（2026-11-06 用户要求去掉）：k8s-server 是集群适配器，要对接多个版本的集群；
 * kubectl 官方只保证与集群相差一个小版本以内可用，而镜像里钉死的是某一个版本。fabric8 直接走 REST，
 * 资源的复数名与作用域靠集群的 API 发现现查（{@code Handlers#getResourceDefinitionContext}），
 * 版本适配与其它资源页一致。
 *
 * <p>与 kubectl 行为对齐的三条硬约束（2026-11-06 实测得出，改动前先读）：
 * <ol>
 *   <li><b>YAML 1.1 解析</b>：用 snakeyaml（YAML 1.1）而不是 fabric8/Jackson 自带的（YAML 1.2）。
 *       后者把 {@code defaultMode: 0644} 解析成字符串 "0644"（kubectl 是八进制 420）、
 *       {@code on/off/yes/no} 解析成字符串（kubectl 是布尔），第三方 chart 会被 apiserver 拒绝。
 *       时间戳不解析（kubectl 也是原样当字符串）。</li>
 *   <li><b>通用模型</b>：一律转成 {@link GenericKubernetesResource}，不走类型化模型 ——
 *       类型化模型会改写原文（CRD 的 {@code maximum: 2147483647} → {@code 2.147483647E9}），通用模型原样保留、
 *       未知字段也不丢。</li>
 *   <li><b>缺命名空间</b>：命名空间级对象没写 namespace 时用调用方给的默认命名空间
 *       （kubectl 不带 -n 会静默落进 default；fabric8 直接报错 —— 两者都不对）。</li>
 * </ol>
 *
 * <p>单次请求超时 {@link #REQUEST_TIMEOUT}（fabric8 默认约 10s，1.5MB 的 CRD 在远端集群上可能不够）。
 * 每次调用现建一个客户端、用完关掉：调用频率低（组件安装 / 应用发布），换来不与长连接客户端共享超时配置。
 */
@Slf4j
@Component
public class ManifestApplier {

    /** 单次 API 请求超时（用户 2026-11-06 定为 30s） */
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    /** 等 CRD Established 的总时长（与原 kubectl wait --timeout=120s 一致） */
    static final Duration CRD_WAIT_TIMEOUT = Duration.ofSeconds(120);

    /** snakeyaml 默认只收 3MB 字符；Calico 的 CRD 清单渲染出来约 3.5MB，放到 64MB */
    private static final int CODE_POINT_LIMIT = 64 * 1024 * 1024;

    private static final int LOG_TAIL_LINES = 100;

    private static final KubernetesSerialization SERIALIZATION = new KubernetesSerialization();

    /** 写入清单（server-side apply，平台 fieldManager + forceConflicts）。 */
    public HelmResult applyServerSide(String kubeconfig, String manifest, String defaultNamespace) {
        return apply(kubeconfig, manifest, defaultNamespace, false);
    }

    /**
     * {@code dryRun=true} 只让 apiserver 校验、不落盘（排障与真集群验证用）。
     *
     * <p>逐个对象写，<b>某个失败不中断</b>，最后汇总（与 kubectl apply -f 一致：能写的都写上，再整体报错）。
     */
    HelmResult apply(String kubeconfig, String manifest, String defaultNamespace, boolean dryRun) {
        List<GenericKubernetesResource> objects;
        try {
            objects = parse(manifest);
        } catch (RuntimeException e) {
            return new HelmResult(false, -1, "清单解析失败：" + e.getMessage());
        }
        List<String> applied = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        try (KubernetesClient client = open(kubeconfig)) {
            for (GenericKubernetesResource obj : objects) {
                String id = idOf(obj);
                try {
                    Resource<GenericKubernetesResource> res = resource(client, obj, defaultNamespace);
                    if (dryRun) {
                        res.dryRun().fieldManager(ServerSideApply.FIELD_MANAGER).forceConflicts().serverSideApply();
                    } else {
                        res.fieldManager(ServerSideApply.FIELD_MANAGER).forceConflicts().serverSideApply();
                    }
                    applied.add(id);
                } catch (RuntimeException e) {
                    errors.add(id + "：" + message(e, obj));
                }
            }
        } catch (RuntimeException e) {
            return new HelmResult(false, -1, "连接集群失败：" + message(e));
        }
        return summary(applied.size() + " 个对象已写入（server-side apply）", errors);
    }

    /** 删除清单里列出的对象；不存在的对象不算失败（等价 kubectl delete --ignore-not-found）。 */
    public HelmResult deleteManifest(String kubeconfig, String manifest, String defaultNamespace) {
        List<GenericKubernetesResource> objects;
        try {
            objects = parse(manifest);
        } catch (RuntimeException e) {
            return new HelmResult(false, -1, "清单解析失败：" + e.getMessage());
        }
        List<String> errors = new ArrayList<>();
        int deleted = 0;
        try (KubernetesClient client = open(kubeconfig)) {
            for (GenericKubernetesResource obj : objects) {
                try {
                    // 不存在时 fabric8 返回空列表、不抛 —— 天然就是 ignore-not-found
                    if (!resource(client, obj, defaultNamespace).delete().isEmpty()) {
                        deleted++;
                    }
                } catch (RuntimeException e) {
                    errors.add(idOf(obj) + "：" + message(e, obj));
                }
            }
        } catch (RuntimeException e) {
            return new HelmResult(false, -1, "连接集群失败：" + message(e));
        }
        return summary(deleted + " 个对象已删除", errors);
    }

    /** 等这些 CRD 都变成 Established（否则紧跟着装的 CR 会报 no matches for kind）。总时长 120s。 */
    public HelmResult waitCrdsEstablished(String kubeconfig, List<String> crds) {
        long deadline = System.nanoTime() + CRD_WAIT_TIMEOUT.toNanos();
        try (KubernetesClient client = open(kubeconfig)) {
            for (String name : crds) {
                long left = deadline - System.nanoTime();
                if (left <= 0) {
                    return new HelmResult(false, -1, "等待 CRD Established 超时（120s）：" + name);
                }
                try {
                    client.apiextensions().v1().customResourceDefinitions().withName(name)
                            .waitUntilCondition(ManifestApplier::established, left, TimeUnit.NANOSECONDS);
                } catch (KubernetesClientTimeoutException e) {
                    return new HelmResult(false, -1, "等待 CRD Established 超时（120s）：" + name);
                }
            }
        } catch (RuntimeException e) {
            return new HelmResult(false, -1, "等待 CRD 失败：" + message(e));
        }
        return new HelmResult(true, 0, crds.size() + " 个 CRD 已 Established");
    }

    /**
     * 等一个 Job 结束。成功（Complete）→ ok；失败（Failed）<b>立刻返回</b>（kubectl wait 只认 complete，
     * Job 失败时要干等到超时）；超时 → ok=false。
     */
    public HelmResult waitJob(String kubeconfig, String job, String namespace, Duration timeout) {
        try (KubernetesClient client = open(kubeconfig)) {
            Job done = client.batch().v1().jobs().inNamespace(namespace).withName(job)
                    .waitUntilCondition(j -> hasCondition(j, "Complete") || hasCondition(j, "Failed"),
                            timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (hasCondition(done, "Complete")) {
                return new HelmResult(true, 0, "Job 已完成：" + job);
            }
            return new HelmResult(false, 1, "Job 失败：" + job + failedReason(done));
        } catch (KubernetesClientTimeoutException e) {
            return new HelmResult(false, -1, "等待 Job 超时（" + timeout.toSeconds() + "s）：" + job);
        } catch (RuntimeException e) {
            return new HelmResult(false, -1, "等待 Job 失败：" + message(e));
        }
    }

    /** Job 最新一个 Pod 的日志尾 100 行（初始化失败时给人看）；取不到返回空串，不抛。 */
    public String jobLogs(String kubeconfig, String job, String namespace) {
        try (KubernetesClient client = open(kubeconfig)) {
            List<Pod> pods = client.pods().inNamespace(namespace).withLabel("job-name", job).list().getItems();
            return pods.stream()
                    .max(Comparator.comparing(p -> String.valueOf(p.getMetadata().getCreationTimestamp())))
                    .map(p -> client.pods().inNamespace(namespace).withName(p.getMetadata().getName())
                            .tailingLines(LOG_TAIL_LINES).getLog())
                    .orElse("");
        } catch (RuntimeException e) {
            log.debug("取 Job 日志失败 job={} ns={}: {}", job, namespace, e.getMessage());
            return "";
        }
    }

    // ------------------------------------------------------------------ 解析

    /**
     * 多文档 YAML → 通用对象列表。跳过空文档 / 纯注释文档 / 没有 kind 的文档；{@code kind: XxxList} 展开 items
     * （kubectl 同样会展开）。
     */
    static List<GenericKubernetesResource> parse(String manifest) {
        List<GenericKubernetesResource> out = new ArrayList<>();
        if (manifest == null || manifest.isBlank()) {
            return out;
        }
        for (Object doc : yaml().loadAll(manifest)) {
            collect(doc, out);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static void collect(Object doc, List<GenericKubernetesResource> out) {
        if (!(doc instanceof Map<?, ?> map) || map.get("kind") == null) {
            return;
        }
        String kind = String.valueOf(map.get("kind"));
        if (kind.endsWith("List") && map.get("items") instanceof List<?> items) {
            items.forEach(item -> collect(item, out));
            return;
        }
        out.add(SERIALIZATION.convertValue((Map<String, Object>) map, GenericKubernetesResource.class));
    }

    /** YAML 1.1（与 kubectl 一致），但<b>不</b>解析时间戳 —— kubectl 也把它们原样当字符串。 */
    private static Yaml yaml() {
        LoaderOptions options = new LoaderOptions();
        options.setCodePointLimit(CODE_POINT_LIMIT);
        options.setMaxAliasesForCollections(1000);
        return new Yaml(new SafeConstructor(options), new Representer(new DumperOptions()),
                new DumperOptions(), options, new NoTimestampResolver());
    }

    /** 去掉时间戳隐式解析的 Resolver（其余 YAML 1.1 规则不变）。 */
    static final class NoTimestampResolver extends Resolver {
        @Override
        public void addImplicitResolver(org.yaml.snakeyaml.nodes.Tag tag, Pattern regexp, String first, int limit) {
            if (org.yaml.snakeyaml.nodes.Tag.TIMESTAMP.equals(tag)) {
                return;
            }
            super.addImplicitResolver(tag, regexp, first, limit);
        }
    }

    // ------------------------------------------------------------------ 内部

    private static KubernetesClient open(String kubeconfig) {
        Config config = Config.fromKubeconfig(kubeconfig);
        config.setRequestTimeout((int) REQUEST_TIMEOUT.toMillis());
        return new KubernetesClientBuilder().withConfig(config).build();
    }

    /**
     * 命名空间级对象缺 namespace → 用默认命名空间；集群级对象（CRD、ClusterRole、FelixConfiguration…）
     * 由 fabric8 按 API 发现到的作用域忽略 namespace。
     */
    private static Resource<GenericKubernetesResource> resource(KubernetesClient client,
                                                               GenericKubernetesResource obj,
                                                               String defaultNamespace) {
        boolean missingNs = obj.getMetadata() == null || obj.getMetadata().getNamespace() == null
                || obj.getMetadata().getNamespace().isBlank();
        if (missingNs && defaultNamespace != null && !defaultNamespace.isBlank()) {
            return client.resource(obj).inNamespace(defaultNamespace);
        }
        return client.resource(obj);
    }

    private static boolean established(CustomResourceDefinition crd) {
        return crd != null && crd.getStatus() != null && crd.getStatus().getConditions() != null
                && crd.getStatus().getConditions().stream()
                .anyMatch(c -> "Established".equals(c.getType()) && "True".equals(c.getStatus()));
    }

    private static boolean hasCondition(Job job, String type) {
        return job != null && job.getStatus() != null && job.getStatus().getConditions() != null
                && job.getStatus().getConditions().stream()
                .anyMatch(c -> type.equals(c.getType()) && "True".equals(c.getStatus()));
    }

    private static String failedReason(Job job) {
        if (job == null || job.getStatus() == null || job.getStatus().getConditions() == null) {
            return "";
        }
        return job.getStatus().getConditions().stream()
                .filter(c -> "Failed".equals(c.getType()))
                .findFirst()
                .map(c -> "（" + c.getReason() + (c.getMessage() == null ? "" : "：" + c.getMessage()) + "）")
                .orElse("");
    }

    private static String idOf(GenericKubernetesResource obj) {
        String name = obj.getMetadata() == null ? "?" : obj.getMetadata().getName();
        return obj.getKind() + "/" + name;
    }

    /**
     * 单个对象的报错。集群里没有这种资源类型时 fabric8 的原文会把整个对象 dump 出来
     * （"Could not find a registered handler for item: [...]"），换成人能读的一句。
     */
    static String message(RuntimeException e, GenericKubernetesResource obj) {
        String raw = String.valueOf(e.getMessage());
        if (raw.startsWith("Could not find a registered handler")) {
            return "集群里没有这种资源类型 " + obj.getApiVersion() + " " + obj.getKind()
                    + "（对应的 CRD 可能还没装，或该 API 版本已被集群移除）";
        }
        return message(e);
    }

    /** apiserver 的 Status.message 比 fabric8 包装后的整段异常文本可读 */
    private static String message(RuntimeException e) {
        if (e instanceof KubernetesClientException kce && kce.getStatus() != null
                && kce.getStatus().getMessage() != null) {
            return kce.getStatus().getMessage();
        }
        return String.valueOf(e.getMessage());
    }

    private static HelmResult summary(String okText, List<String> errors) {
        if (errors.isEmpty()) {
            return new HelmResult(true, 0, okText);
        }
        return new HelmResult(false, 1, String.join("\n", errors));
    }
}
