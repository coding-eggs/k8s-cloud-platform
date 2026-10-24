package com.coding.k8sserver.middleware;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.middleware.MiddlewareCreateRequestDTO;
import com.coding.common.models.middleware.MiddlewareDeleteRequestDTO;
import com.coding.common.models.middleware.MiddlewareDetailRequestDTO;
import com.coding.common.models.middleware.MiddlewareInstanceLiveDTO;
import com.coding.common.models.middleware.MiddlewareScaleRequestDTO;
import com.coding.common.models.middleware.MiddlewareStatusRequestDTO;
import com.coding.common.models.middleware.MiddlewareStatusResultDTO;
import com.coding.k8score.factory.KubernetesClientFactory;
import com.coding.k8score.operations.ServerSideApply;
import com.coding.k8sserver.components.ResourceAccessResolver;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaim;
import io.fabric8.kubernetes.client.utils.Serialization;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.ResourceQuota;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 中间件实例的执行层：创建/扩容/删除 CR，写凭据 Secret，读实时状态。
 *
 * <p><b>身份</b>：{@link ResourceAccessResolver#resolveNamespacedAccessOrPlatform} ——
 * 租户 token 用租户 client（K8s RBAC 第二道闸）+ 分配表；管理员不带租户时用 admin client（不受分配表限制）。
 *
 * <p><b>配额预检用 admin client 读 ResourceQuota</b>：租户 RBAC 模板里没有 `resourcequotas`
 * （2026-10-22 复审实锤：`kubectl auth can-i list resourcequotas` 对租户 SA 是 no），
 * 用租户 client 读会先 403，用户看到的是权限错误而不是「配额不足」。
 */
@Service
@Slf4j
public class MiddlewareInstanceService {

    private final ResourceAccessResolver accessResolver;
    private final KubernetesClientFactory clients;
    private final NacosChartInstaller nacos;
    private final Map<String, MiddlewareAdapter> adapters;

    public MiddlewareInstanceService(ResourceAccessResolver accessResolver,
                                     KubernetesClientFactory clients,
                                     NacosChartInstaller nacos,
                                     List<MiddlewareAdapter> adapters) {
        this.accessResolver = accessResolver;
        this.clients = clients;
        this.nacos = nacos;
        this.adapters = adapters.stream()
                .collect(Collectors.toMap(MiddlewareAdapter::code, Function.identity()));
    }

    /**
     * 详情页的实时对象：CR（含 YAML）+ 该实例的 Pod / PVC / Service / 工作负载 / 凭据 Secret / 事件。
     * 查询口径与 {@link #status} 一致，避免「列表说 READY、详情说没对象」。
     *
     * <p>每块独立 try/catch：租户 SA 的 K8s RBAC 缺某一项（典型是 `events` 没在模板里）时，
     * 只把那一块记进 {@code warnings}，其余照常返回 —— 否则一个 403 会让整页变成"读不到"。
     */
    public MiddlewareInstanceLiveDTO detail(MiddlewareDetailRequestDTO req) {
        var ctx = accessResolver.resolveNamespacedAccessOrPlatform(
                req.getTenantId(), req.getClusterId(), req.getNamespace());
        KubernetesClient client = clientOf(req.getClusterId(), ctx);
        MiddlewareAdapter adapter = adapter(req.getCode());

        MiddlewareInstanceLiveDTO out = new MiddlewareInstanceLiveDTO();
        List<String> warnings = new ArrayList<>();
        out.setWarnings(warnings);

        // 一个实例可能有多个 CR（Redis 哨兵两个、Kafka 两个、ES 勾了 Kibana 也是两个）：
        // 适配器 contexts 里第一个能找到的是主 CR，其余作为「附属 CR」展示
        GenericKubernetesResource live = null;
        List<MiddlewareInstanceLiveDTO.CrView> related = new ArrayList<>();
        for (CustomResourceDefinitionContext crd : adapter.contexts()) {
            var found = client.genericKubernetesResources(crd)
                    .inNamespace(req.getNamespace()).withName(req.getName()).get();
            if (found == null) {
                continue;
            }
            if (live == null) {
                live = found;
            } else {
                related.add(toCrView(found));
            }
        }
        out.setRelatedCrs(related);
        out.setConditions(conditionsOf(live));
        if (live != null) {
            out.setKind(live.getKind());
            out.setApiVersion(live.getApiVersion());
            if (live.getMetadata() != null) {
                out.setName(live.getMetadata().getName());
                out.setCreatedAt(live.getMetadata().getCreationTimestamp());
            }
            out.setYaml(Serialization.asYaml(live));
            // 可选插件（IK）以 CR 为准回显；适配器不适用时保持 null，前端退回台账
            out.setIkInstalled(adapter.ikInstalled(live));
        }

        String selector = adapter.statusSelector(req.getName(),
                live == null ? null : live.getKind(), req.getInstanceId());
        block(warnings, "Pod", () -> out.setPods(client.pods().inNamespace(req.getNamespace())
                .withLabelSelector(selector)
                .list().getItems().stream().map(MiddlewareInstanceService::toPodView).toList()));

        block(warnings, "PVC", () -> out.setPvcs(client.persistentVolumeClaims().inNamespace(req.getNamespace())
                .withLabelSelector(adapter.pvcSelector(req.getName(), req.getInstanceId()))
                .list().getItems().stream().map(MiddlewareInstanceService::toPvcView).toList()));

        block(warnings, "Service", () -> {
            // Service 名都比实例名多一段后缀（-master/-haproxy/-kafka-bootstrap…）；Nacos 固定叫 nacos-cs
            String prefix = req.getName();
            out.setServices(client.services().inNamespace(req.getNamespace()).list().getItems().stream()
                    .filter(s -> s.getMetadata() != null && s.getMetadata().getName() != null)
                    .filter(s -> s.getMetadata().getName().startsWith(prefix)
                            || ("nacos".equals(req.getCode()) && "nacos-cs".equals(s.getMetadata().getName())))
                    .map(MiddlewareInstanceService::toServiceView).toList());
        });

        block(warnings, "工作负载", () -> {
            List<MiddlewareInstanceLiveDTO.WorkloadView> workloads = new ArrayList<>();
            client.apps().deployments().inNamespace(req.getNamespace()).list().getItems().stream()
                    .filter(d -> belongsToInstance(d.getMetadata(), req.getName(), req.getInstanceId()))
                    .forEach(d -> workloads.add(workloadView("Deployment", d.getMetadata(),
                            d.getSpec() == null ? null : d.getSpec().getReplicas(),
                            d.getStatus() == null ? null : d.getStatus().getReadyReplicas(),
                            d.getSpec() == null || d.getSpec().getTemplate() == null ? null : d.getSpec().getTemplate().getSpec())));
            client.apps().statefulSets().inNamespace(req.getNamespace()).list().getItems().stream()
                    .filter(s -> belongsToInstance(s.getMetadata(), req.getName(), req.getInstanceId()))
                    .forEach(s -> workloads.add(workloadView("StatefulSet", s.getMetadata(),
                            s.getSpec() == null ? null : s.getSpec().getReplicas(),
                            s.getStatus() == null ? null : s.getStatus().getReadyReplicas(),
                            s.getSpec() == null || s.getSpec().getTemplate() == null ? null : s.getSpec().getTemplate().getSpec())));
            out.setWorkloads(workloads);
        });

        block(warnings, "Secret", () -> out.setSecrets(
                client.secrets().inNamespace(req.getNamespace()).list().getItems().stream()
                        .filter(s -> belongsToInstance(s.getMetadata(), req.getName(), req.getInstanceId()))
                        .map(MiddlewareInstanceService::toSecretView).toList()));

        block(warnings, "事件", () -> out.setEvents(eventsOf(client, req.getNamespace(), req.getName())));
        return out;
    }

    /** 单块读取失败只影响该块：把原因记进 warnings，其余字段照常返回。 */
    private static void block(List<String> warnings, String label, Runnable read) {
        try {
            read.run();
        } catch (RuntimeException e) {
            warnings.add(label + "读取失败：" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }
    }

    /**
     * 集群对象是否属于这个中间件实例。三条判据（任一成立即可）：
     * <ul>
     *   <li>名字是实例名或以 {@code 实例名-} 开头（Operator 的 {@code <name>-pxc}/{@code -es-default}/{@code -1}）；</li>
     *   <li>ownerReferences 里有以实例名命名的属主（ECK 的 Kibana Deployment 是 {@code <name>-kb}，但属主是 Kibana CR）；</li>
     *   <li>带 Helm 的 {@code app.kubernetes.io/instance} 或平台的 {@code platform.coding/middleware-instance} 标签。</li>
     * </ul>
     * 只按前缀匹配会把 {@code mw-redis-abc12345x} 也算进来，所以要求跟着 {@code -}。
     */
    static boolean belongsToInstance(io.fabric8.kubernetes.api.model.ObjectMeta meta, String name, String instanceId) {
        if (meta == null || name == null) {
            return false;
        }
        String objectName = meta.getName();
        if (objectName != null && (objectName.equals(name) || objectName.startsWith(name + "-"))) {
            return true;
        }
        Map<String, String> labels = meta.getLabels();
        if (labels != null) {
            if (name.equals(labels.get("app.kubernetes.io/instance"))) {
                return true;
            }
            if (instanceId != null && instanceId.equals(labels.get("platform.coding/middleware-instance"))) {
                return true;
            }
        }
        if (meta.getOwnerReferences() != null) {
            return meta.getOwnerReferences().stream()
                    .anyMatch(ref -> name.equals(ref.getName()));
        }
        return false;
    }

    /** CR 的 status.conditions（Operator 的就绪/初始化/失败信号都在这里）。 */
    static List<MiddlewareInstanceLiveDTO.ConditionView> conditionsOf(GenericKubernetesResource res) {
        List<MiddlewareInstanceLiveDTO.ConditionView> out = new ArrayList<>();
        if (res == null) {
            return out;
        }
        Object status = res.getAdditionalProperties().get("status");
        if (!(status instanceof Map<?, ?> st) || !(st.get("conditions") instanceof List<?> conditions)) {
            return out;
        }
        for (Object c : conditions) {
            if (!(c instanceof Map<?, ?> cm)) {
                continue;
            }
            MiddlewareInstanceLiveDTO.ConditionView v = new MiddlewareInstanceLiveDTO.ConditionView();
            v.setType(text(cm.get("type")));
            v.setStatus(text(cm.get("status")));
            v.setReason(text(cm.get("reason")));
            v.setMessage(text(cm.get("message")));
            v.setLastTransitionTime(text(cm.get("lastTransitionTime")));
            out.add(v);
        }
        return out;
    }

    /** 与该实例相关的最近事件（CR 本体、它建出来的 Pod/工作负载/Service 上的都算），最多 50 条。 */
    private static List<MiddlewareInstanceLiveDTO.EventView> eventsOf(
            KubernetesClient client, String namespace, String name) {
        return client.v1().events().inNamespace(namespace).list().getItems().stream()
                .filter(e -> e.getInvolvedObject() != null && e.getInvolvedObject().getName() != null)
                .filter(e -> {
                    String n = e.getInvolvedObject().getName();
                    return n.equals(name) || n.startsWith(name + "-");
                })
                .sorted((a, b) -> stampOf(b).compareTo(stampOf(a)))
                .limit(50)
                .map(e -> {
                    MiddlewareInstanceLiveDTO.EventView v = new MiddlewareInstanceLiveDTO.EventView();
                    v.setType(e.getType());
                    v.setReason(e.getReason());
                    v.setMessage(e.getMessage());
                    v.setObject(e.getInvolvedObject().getKind() + "/" + e.getInvolvedObject().getName());
                    v.setCount(e.getCount());
                    v.setLastTimestamp(stampOf(e));
                    return v;
                })
                .toList();
    }

    /** 事件的排序/展示时间：core v1 的 lastTimestamp 可能为空，退到 metadata.creationTimestamp。 */
    private static String stampOf(io.fabric8.kubernetes.api.model.Event e) {
        if (e.getLastTimestamp() != null) {
            return e.getLastTimestamp();
        }
        if (e.getMetadata() != null && e.getMetadata().getCreationTimestamp() != null) {
            return e.getMetadata().getCreationTimestamp();
        }
        return "";
    }

    private static MiddlewareInstanceLiveDTO.WorkloadView workloadView(
            String kind, io.fabric8.kubernetes.api.model.ObjectMeta meta,
            Integer desired, Integer ready, io.fabric8.kubernetes.api.model.PodSpec podSpec) {
        MiddlewareInstanceLiveDTO.WorkloadView v = new MiddlewareInstanceLiveDTO.WorkloadView();
        v.setKind(kind);
        v.setName(meta == null ? null : meta.getName());
        v.setApiVersion("apps/v1");
        v.setDesiredReplicas(desired);
        v.setReadyReplicas(ready);
        v.setCreatedAt(meta == null ? null : meta.getCreationTimestamp());
        if (podSpec != null && podSpec.getContainers() != null) {
            v.setImages(podSpec.getContainers().stream()
                    .map(c -> c.getName() + ": " + c.getImage())
                    .collect(Collectors.joining(", ")));
        }
        return v;
    }

    private static MiddlewareInstanceLiveDTO.SecretView toSecretView(Secret secret) {
        MiddlewareInstanceLiveDTO.SecretView v = new MiddlewareInstanceLiveDTO.SecretView();
        if (secret.getMetadata() != null) {
            v.setName(secret.getMetadata().getName());
            v.setCreatedAt(secret.getMetadata().getCreationTimestamp());
        }
        v.setType(secret.getType());
        // 只给键名，不回传值：凭据本身要走连接方式卡片里的 Secret 引用
        v.setKeys(secret.getData() == null ? null : String.join(", ", secret.getData().keySet()));
        return v;
    }

    private static MiddlewareInstanceLiveDTO.CrView toCrView(GenericKubernetesResource res) {
        MiddlewareInstanceLiveDTO.CrView v = new MiddlewareInstanceLiveDTO.CrView();
        v.setKind(res.getKind());
        v.setApiVersion(res.getApiVersion());
        if (res.getMetadata() != null) {
            v.setName(res.getMetadata().getName());
            v.setCreatedAt(res.getMetadata().getCreationTimestamp());
        }
        v.setYaml(Serialization.asYaml(res));
        return v;
    }

    private static String text(Object o) {
        return o == null ? null : String.valueOf(o);
    }


    private static MiddlewareInstanceLiveDTO.PodView toPodView(Pod pod) {
        MiddlewareInstanceLiveDTO.PodView v = new MiddlewareInstanceLiveDTO.PodView();
        v.setName(pod.getMetadata() == null ? "" : pod.getMetadata().getName());
        v.setCreatedAt(pod.getMetadata() == null ? null : pod.getMetadata().getCreationTimestamp());
        v.setNode(pod.getSpec() == null ? null : pod.getSpec().getNodeName());
        if (pod.getStatus() != null) {
            v.setPhase(pod.getStatus().getPhase());
            int total = pod.getStatus().getContainerStatuses() == null ? 0
                    : pod.getStatus().getContainerStatuses().size();
            int ready = pod.getStatus().getContainerStatuses() == null ? 0
                    : (int) pod.getStatus().getContainerStatuses().stream().filter(c -> c.getReady() != null && c.getReady()).count();
            int restarts = pod.getStatus().getContainerStatuses() == null ? 0
                    : pod.getStatus().getContainerStatuses().stream()
                            .mapToInt(c -> c.getRestartCount() == null ? 0 : c.getRestartCount()).sum();
            v.setReady(total > 0 && ready == total);
            v.setReadyText(ready + "/" + total);
            v.setRestarts(restarts);
        }
        return v;
    }

    private static MiddlewareInstanceLiveDTO.PvcView toPvcView(PersistentVolumeClaim pvc) {
        MiddlewareInstanceLiveDTO.PvcView v = new MiddlewareInstanceLiveDTO.PvcView();
        v.setName(pvc.getMetadata() == null ? "" : pvc.getMetadata().getName());
        v.setCreatedAt(pvc.getMetadata() == null ? null : pvc.getMetadata().getCreationTimestamp());
        if (pvc.getStatus() != null) {
            v.setPhase(pvc.getStatus().getPhase());
            Quantity cap = pvc.getStatus().getCapacity() == null ? null
                    : pvc.getStatus().getCapacity().get("storage");
            v.setCapacity(cap == null ? null : cap.toString());
        }
        v.setStorageClass(pvc.getSpec() == null ? null : pvc.getSpec().getStorageClassName());
        return v;
    }

    private static MiddlewareInstanceLiveDTO.ServiceView toServiceView(
            io.fabric8.kubernetes.api.model.Service svc) {
        MiddlewareInstanceLiveDTO.ServiceView v = new MiddlewareInstanceLiveDTO.ServiceView();
        v.setName(svc.getMetadata().getName());
        if (svc.getSpec() != null) {
            v.setType(svc.getSpec().getType());
            v.setClusterIp(svc.getSpec().getClusterIP());
            v.setPorts(svc.getSpec().getPorts() == null ? "" : svc.getSpec().getPorts().stream()
                    .map(p -> p.getPort() + "/" + p.getProtocol()).toList().stream()
                    .collect(java.util.stream.Collectors.joining(",")));
        }
        return v;
    }

    public void create(MiddlewareCreateRequestDTO req) {
        var ctx = accessResolver.resolveNamespacedAccessOrPlatform(
                req.getTenantId(), req.getClusterId(), req.getNamespace());
        KubernetesClient client = clientOf(req.getClusterId(), ctx);
        assertQuota(req);

        if (isNacos(req.getCode())) {
            nacos.create(req);
            createNacosMetricsResources(client, req);
            return;
        }

        MiddlewareAdapter adapter = adapter(req.getCode());
        List<MiddlewareAdapter.CrSpec> created = new ArrayList<>();
        try {
            if (adapter.usesPlatformPassword()) {
                writeSecret(client, req);
            }
            for (MiddlewareAdapter.CrSpec spec : adapter.buildCreate(req)) {
                client.genericKubernetesResources(spec.context())
                        .inNamespace(req.getNamespace())
                        .resource(spec.resource())
                        .create();
                created.add(spec);
            }
            createMetricsResources(client, adapter, req, created);
        } catch (RuntimeException e) {
            // 部分失败回滚：SENTINEL 是两个 CR，第二个失败时不能把第一个和 Secret 留在集群里
            created.forEach(s -> deleteQuietly(client, s.context(), req.getNamespace(),
                    s.resource().getMetadata().getName()));
            if (adapter.usesPlatformPassword()) {
                deleteSecretQuietly(client, req.getNamespace(), secretName(req));
            }
            throw e;
        }
    }

    public void scale(MiddlewareScaleRequestDTO req) {
        if (req.getStorageSize() != null && !req.getStorageSize().isBlank()) {
            throw new CloudPlatformException(EnumResponseType.ERROR,
                    "暂不支持扩容存储（PVC 扩容依赖各中间件的 Operator 能力），请重建实例或手工扩卷");
        }
        var ctx = accessResolver.resolveNamespacedAccessOrPlatform(
                req.getTenantId(), req.getClusterId(), req.getNamespace());
        KubernetesClient client = clientOf(req.getClusterId(), ctx);
        boolean enableMetrics = Boolean.TRUE.equals(req.getMetricsEnabled());
        if (isNacos(req.getCode())) {
            if (enableMetrics) {
                // chart 实例：重新 upgrade 一次（values 里带 actuator 环境变量），Pod 会滚动重启
                nacos.enableMetrics(req);
                ensureNacosMetricsResources(client, req);
                return;
            }
            nacos.scale(req.getClusterId(), req.getCode());   // 直接抛出"暂不支持"
            return;
        }
        MiddlewareAdapter adapter = adapter(req.getCode());
        boolean hasScale = hasScaleFields(req);

        boolean found = false;
        for (CustomResourceDefinitionContext crd : adapter.contexts()) {
            var live = client.genericKubernetesResources(crd)
                    .inNamespace(req.getNamespace())
                    .withName(req.getName())
                    .get();
            if (live == null) {
                continue;
            }
            found = true;
            boolean changed = false;
            if (hasScale) {
                adapter.applyScale(live, crd, req);
                changed = true;
            }
            if (enableMetrics) {
                // 只补采集相关字段（exporter/metricsConfig），不碰规格 —— 避免空值把资源字段冲掉
                changed |= adapter.ensureMetrics(live, crd, req);
            }
            if (!changed) {
                continue;
            }
            stripApplyMetadata(live);
            // 与仓内其它 CR 更新一致：SSA + forceConflicts（fetch-overlay 已保留未建模字段）
            client.genericKubernetesResources(crd)
                    .inNamespace(req.getNamespace())
                    .resource(live)
                    .fieldManager(ServerSideApply.FIELD_MANAGER)
                    .forceConflicts()
                    .serverSideApply();
        }
        if (!found) {
            throw new CloudPlatformException(EnumResponseType.RESOURCE_NOT_EXIST);
        }
        if (enableMetrics) {
            ensureMetricsResources(client, adapter, req);
        }
    }

    /** 请求里有没有真正的规格改动（只有 metricsEnabled 时不能走 applyScale —— 空资源字段会冲掉原值）。 */
    private static boolean hasScaleFields(MiddlewareScaleRequestDTO req) {
        return req.getReplicas() != null
                || notBlank(req.getCpuRequest()) || notBlank(req.getCpuLimit())
                || notBlank(req.getMemoryRequest()) || notBlank(req.getMemoryLimit());
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    /** 编辑时补开业务指标：建（或补齐）Service + ServiceMonitor，幂等。 */
    private void ensureMetricsResources(KubernetesClient client, MiddlewareAdapter adapter,
                                        MiddlewareScaleRequestDTO req) {
        MiddlewareAdapter.MetricsSpec spec = adapter.metricsSpec(req.getName(), null, req.getInstanceId());
        if (spec == null || !monitoringAvailable(client)) {
            return;
        }
        MiddlewareCreateRequestDTO create = new MiddlewareCreateRequestDTO();
        create.setName(req.getName());
        create.setNamespace(req.getNamespace());
        create.setInstanceId(req.getInstanceId());
        putMetricsResources(client, create, spec);
    }

    /** Nacos（chart）补开指标时也走同一套采集资源。 */
    private void ensureNacosMetricsResources(KubernetesClient client, MiddlewareScaleRequestDTO req) {
        MiddlewareCreateRequestDTO create = new MiddlewareCreateRequestDTO();
        create.setName(req.getName());
        create.setNamespace(req.getNamespace());
        create.setInstanceId(req.getInstanceId());
        create.setMetricsEnabled(true);
        createNacosMetricsResources(client, create);
    }

    /** createOrReplace：已经存在的 Service/ServiceMonitor 直接补齐（幂等），不报冲突。 */
    private void putMetricsResources(KubernetesClient client, MiddlewareCreateRequestDTO create,
                                     MiddlewareAdapter.MetricsSpec spec) {
        try {
            for (MiddlewareAdapter.CrSpec s : MetricsResources.build(create, spec)) {
                client.genericKubernetesResources(s.context())
                        .inNamespace(create.getNamespace()).resource(s.resource()).createOrReplace();
            }
        } catch (RuntimeException e) {
            log.warn("中间件 {} 的采集资源创建失败（业务指标将不可用）：{}", create.getName(), e.getMessage());
        }
    }

    public void delete(MiddlewareDeleteRequestDTO req) {
        var ctx = accessResolver.resolveNamespacedAccessOrPlatform(
                req.getTenantId(), req.getClusterId(), req.getNamespace());
        KubernetesClient client = clientOf(req.getClusterId(), ctx);

        if (isNacos(req.getCode())) {
            nacos.delete(req.getClusterId(), req.getNamespace(), req.getName());
            deleteMetricsResourcesQuietly(client, req.getNamespace(), req.getName());
            if (!req.isKeepPvc()) {
                // chart 的 PVC 没有 labels（volumeClaimTemplates 只写了 name），只能按名字前缀找：
                // STS 卷名 = <claimName>-<stsName>-<ordinal>，claimName 固定是 data
                String prefix = "data-" + req.getName() + "-";
                client.persistentVolumeClaims().inNamespace(req.getNamespace()).list().getItems().stream()
                        .filter(p -> p.getMetadata() != null && p.getMetadata().getName() != null
                                && p.getMetadata().getName().startsWith(prefix))
                        .forEach(p -> client.persistentVolumeClaims().inNamespace(req.getNamespace())
                                .withName(p.getMetadata().getName()).delete());
            }
            return;
        }

        MiddlewareAdapter adapter = adapter(req.getCode());
        for (CustomResourceDefinitionContext crd : adapter.contexts()) {
            deleteQuietly(client, crd, req.getNamespace(), req.getName());
        }
        // 采集资源跟随实例（keepPvc 只影响数据卷）
        deleteMetricsResourcesQuietly(client, req.getNamespace(), req.getName());
        if (!req.isKeepPvc()) {
            // PVC 的标签由各适配器决定：Redis 用平台标签，MySQL/Kafka/ES 用各自 Operator 的标签
            client.persistentVolumeClaims().inNamespace(req.getNamespace())
                    .withLabel(adapter.pvcSelector(req.getName(), req.getInstanceId()))
                    .delete();
            if (adapter.usesPlatformPassword()) {
                deleteSecretQuietly(client, req.getNamespace(), secretName(req));
            }
        }
        // keepPvc=true 时保留 Secret：数据卷还在，凭据也还给它留着
    }

    public List<MiddlewareStatusResultDTO> status(MiddlewareStatusRequestDTO req) {
        var ctx = accessResolver.resolveNamespacedAccessOrPlatform(
                req.getTenantId(), req.getClusterId(), req.getNamespace());
        KubernetesClient client = clientOf(req.getClusterId(), ctx);

        if (isNacos(req.getCode())) {
            return nacosStatus(client, req);
        }

        MiddlewareAdapter adapter = adapter(req.getCode());

        List<MiddlewareStatusResultDTO> out = new ArrayList<>();
        for (String name : req.getNames()) {
            GenericKubernetesResource live = null;
            for (CustomResourceDefinitionContext crd : adapter.contexts()) {
                var found = client.genericKubernetesResources(crd)
                        .inNamespace(req.getNamespace()).withName(name).get();
                if (found != null) {
                    live = found;
                    break;
                }
            }
            MiddlewareStatusResultDTO dto = new MiddlewareStatusResultDTO();
            dto.setName(name);
            if (live == null) {
                dto.setStatus("FAILED");
                dto.setMessage("实例对象不存在（可能已被删除）");
                out.add(dto);
                continue;
            }
            List<Pod> pods = client.pods().inNamespace(req.getNamespace())
                    // ⚠️ 必须用 withLabelSelector（整串 selector）；withLabel 单参是「按 key 存在」
                    .withLabelSelector(adapter.statusSelector(name, live.getKind(), instanceIdOf(req, name)))
                    .list().getItems();
            long ready = pods.stream().filter(MiddlewareInstanceService::podReady).count();
            dto.setReplicas(pods.size());
            dto.setReadyReplicas((int) ready);
            // 判定同时看 Pod 数与 CR 信号：只看「现存 Pod 是否全就绪」会把
            // 「3 个 broker 只起来 2 个」判成 READY（真集群验证踩过）
            var verdict = MiddlewareStatusEvaluator.evaluate(pods.size(), ready,
                    adapter.desiredPods(live, live.getKind()),
                    crInitializing(live), crFailureMessage(live));
            dto.setStatus(verdict.status());
            dto.setMessage(verdict.message());
            // 附属组件（如 Kibana）出问题 → 降级，不谎报运行中
            String accessory = adapter.accessoryProblem(client, req.getNamespace(), name);
            if (accessory != null && !"FAILED".equals(verdict.status())) {
                dto.setStatus("DEGRADED");
                dto.setMessage(verdict.message() + "；" + accessory);
            }
            out.add(dto);
        }
        return out;
    }

    /** 从 CR 的 status 里读硬失败：ECK 的 health=red、以及各类 Operator 的 Ready/Available=False condition。 */
    private static String crFailureMessage(GenericKubernetesResource res) {
        Object status = res.getAdditionalProperties().get("status");
        if (!(status instanceof Map<?, ?> st)) {
            return null;
        }
        if ("red".equals(st.get("health"))) {
            return "Operator 报告健康状况为 red";
        }
        if (st.get("conditions") instanceof List<?> conditions) {
            for (Object c : conditions) {
                if (!(c instanceof Map<?, ?> cm)) {
                    continue;
                }
                boolean bad = "False".equals(cm.get("status"))
                        && ("Ready".equals(cm.get("type")) || "Available".equals(cm.get("type")));
                if (bad) {
                    Object msg = cm.get("message");
                    String text = msg == null ? "" : String.valueOf(msg).trim();
                    if (text.isEmpty()) {
                        // 空消息的 Ready=False 只是阶段信号（Percona bootstrap 期间就是这样），不当硬失败
                        continue;
                    }
                    return text;
                }
            }
        }
        return null;
    }

    /** CR 是否处在初始化阶段（Operator 在 bootstrap / 滚动中）。 */
    private static boolean crInitializing(GenericKubernetesResource res) {
        Object status = res.getAdditionalProperties().get("status");
        if (!(status instanceof Map<?, ?> st) || !(st.get("conditions") instanceof List<?> conditions)) {
            return false;
        }
        for (Object c : conditions) {
            if (c instanceof Map<?, ?> cm
                    && "Initializing".equals(cm.get("type"))
                    && "True".equals(cm.get("status"))) {
                return true;
            }
        }
        return false;
    }

    // ==================== 内部 ====================

    private static boolean isNacos(String code) {
        return "nacos".equals(code);
    }

    private static String instanceIdOf(MiddlewareStatusRequestDTO req, String name) {
        return req.getInstanceIds() == null ? null : req.getInstanceIds().get(name);
    }

    /**
     * SSA 前必须去掉 {@code metadata.managedFields}：把 GET 到的对象原样回写会被 apiserver 拒
     * （{@code metadata.managedFields must be nil}，真集群验证踩过）。
     */
    static void stripApplyMetadata(GenericKubernetesResource live) {
        if (live.getMetadata() != null) {
            live.getMetadata().setManagedFields(null);
        }
    }

    /** Nacos 不是 CR：有没有对象看 Service 在不在，就绪看 Pod。 */
    private List<MiddlewareStatusResultDTO> nacosStatus(KubernetesClient client, MiddlewareStatusRequestDTO req) {
        List<MiddlewareStatusResultDTO> out = new ArrayList<>();
        for (String name : req.getNames()) {
            MiddlewareStatusResultDTO dto = new MiddlewareStatusResultDTO();
            dto.setName(name);
            // 不能按 Service 名判断：chart 的 Service 固定叫 nacos-cs，与 release 名无关
            boolean exists = nacos.exists(req.getClusterId(), req.getNamespace(), name);
            if (!exists) {
                dto.setStatus("FAILED");
                dto.setMessage("实例不存在（release 可能已被删除）");
                out.add(dto);
                continue;
            }
            List<Pod> pods = client.pods().inNamespace(req.getNamespace())
                    .withLabel("app.kubernetes.io/instance=" + name).list().getItems();
            long ready = pods.stream().filter(MiddlewareInstanceService::podReady).count();
            dto.setReplicas(pods.size());
            dto.setReadyReplicas((int) ready);
            if (pods.isEmpty()) {
                dto.setStatus("CREATING");
                dto.setMessage("等待 Pod 创建");
            } else if (ready == pods.size()) {
                dto.setStatus("READY");
                dto.setMessage("运行中");
            } else {
                dto.setStatus("DEGRADED");
                dto.setMessage(ready + "/" + pods.size() + " 就绪");
            }
            out.add(dto);
        }
        return out;
    }

    private KubernetesClient clientOf(String clusterId, ResourceAccessResolver.AccessContext ctx) {
        KubernetesClient client = ctx.adminMode()
                ? clients.getAdminClient(clusterId)
                : clients.getTenantClient(clusterId, ctx.tenantId());
        if (client == null) {
            throw new CloudPlatformException(EnumResponseType.ERROR,
                    "租户上下文不可用（租户可能已停用），请联系管理员");
        }
        return client;
    }

    private MiddlewareAdapter adapter(String code) {
        MiddlewareAdapter a = adapters.get(code);
        if (a == null) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "不支持的中间件：" + code);
        }
        return a;
    }

    private void writeSecret(KubernetesClient client, MiddlewareCreateRequestDTO req) {
        if (req.getPassword() == null || req.getPassword().isBlank()) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "缺少初始密码");
        }
        Secret secret = new SecretBuilder()
                .withNewMetadata()
                .withName(secretName(req))
                .withNamespace(req.getNamespace())
                .addToLabels("platform.coding/managed-by", "middleware")
                .addToLabels("platform.coding/middleware-instance", req.getInstanceId())
                .endMetadata()
                .addToStringData("password", req.getPassword())
                .build();
        client.secrets().inNamespace(req.getNamespace()).resource(secret).createOrReplace();
    }

    private void deleteSecretQuietly(KubernetesClient client, String namespace, String name) {
        try {
            client.secrets().inNamespace(namespace).withName(name).delete();
        } catch (RuntimeException ignored) {
            // 清理是尽力而为：Secret 不存在或已删都不影响主流程
        }
    }

    private static void deleteQuietly(KubernetesClient client, CustomResourceDefinitionContext crd,
                                      String namespace, String name) {
        try {
            client.genericKubernetesResources(crd).inNamespace(namespace).withName(name).delete();
        } catch (RuntimeException ignored) {
            // 同上
        }
    }

    /**
     * 业务指标的采集资源：`<name>-metrics` 的 Service + ServiceMonitor。
     *
     * <p><b>best-effort</b>：建失败只记日志、不阻断建实例 —— 监控问题不该挡住一个刚建好的数据库；
     * 详情页业务指标没数据就是它的表现。核心 CR 的失败仍走原来的回滚。
     */
    private void createMetricsResources(KubernetesClient client, MiddlewareAdapter adapter,
                                        MiddlewareCreateRequestDTO req,
                                        List<MiddlewareAdapter.CrSpec> created) {
        if (!Boolean.TRUE.equals(req.getMetricsEnabled())) {
            return;   // 业务指标默认不采
        }
        MiddlewareAdapter.MetricsSpec spec;
        try {
            String kind = created.isEmpty() ? null : created.get(0).resource().getKind();
            spec = adapter.metricsSpec(req.getName(), kind, req.getInstanceId());
        } catch (RuntimeException e) {
            log.warn("中间件 {} 的指标规格生成失败：{}", req.getName(), e.getMessage());
            return;
        }
        if (spec == null) {
            return;
        }
        if (!monitoringAvailable(client)) {
            log.info("集群没有 ServiceMonitor CRD，跳过 {} 的采集资源", req.getName());
            return;
        }
        List<MiddlewareAdapter.CrSpec> made = new ArrayList<>();
        try {
            for (MiddlewareAdapter.CrSpec s : adapter.buildMetricsResources(req, spec)) {
                client.genericKubernetesResources(s.context())
                        .inNamespace(req.getNamespace()).resource(s.resource()).create();
                made.add(s);
            }
            created.addAll(made);
        } catch (RuntimeException e) {
            // 半套比没有更糟（Service 在、ServiceMonitor 没有）：已建的一并撤掉
            made.forEach(s -> deleteQuietly(client, s.context(), req.getNamespace(),
                    s.resource().getMetadata().getName()));
            log.warn("中间件 {} 的采集资源创建失败（业务指标将不可用）：{}", req.getName(), e.getMessage());
        }
    }

    /**
     * Nacos 走 chart（不是适配器），指标资源的端口/路径在这里单独给：
     * 8848 + {@code /nacos/actuator/prometheus}（chart 里按开关打开 actuator 的 prometheus 端点）。
     */
    private void createNacosMetricsResources(KubernetesClient client, MiddlewareCreateRequestDTO req) {
        if (!Boolean.TRUE.equals(req.getMetricsEnabled()) || !monitoringAvailable(client)) {
            return;
        }
        MiddlewareAdapter.MetricsSpec spec = new MiddlewareAdapter.MetricsSpec(
                8848, "/nacos/actuator/prometheus", "http",
                "app.kubernetes.io/instance=" + req.getName());
        List<MiddlewareAdapter.CrSpec> made = new ArrayList<>();
        try {
            for (MiddlewareAdapter.CrSpec s : MetricsResources.build(req, spec)) {
                client.genericKubernetesResources(s.context())
                        .inNamespace(req.getNamespace()).resource(s.resource()).create();
                made.add(s);
            }
        } catch (RuntimeException e) {
            made.forEach(s -> deleteQuietly(client, s.context(), req.getNamespace(),
                    s.resource().getMetadata().getName()));
            log.warn("Nacos 实例 {} 的采集资源创建失败（业务指标将不可用）：{}", req.getName(), e.getMessage());
        }
    }

    private static boolean monitoringAvailable(KubernetesClient client) {        try {
            return client.apiextensions().v1().customResourceDefinitions()
                    .withName("servicemonitors.monitoring.coreos.com").get() != null;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static void deleteMetricsResourcesQuietly(KubernetesClient client, String namespace, String name) {
        // 与 MetricsResources 的命名保持一致
        String resourceName = MetricsResources.resourceName(name);
        deleteQuietly(client, MetricsResources.SERVICE_MONITOR, namespace, resourceName);
        try {
            client.services().inNamespace(namespace).withName(resourceName).delete();
        } catch (RuntimeException ignored) {
            // 采集资源是附属物：删不到（或集群没有该 CRD）都不影响实例删除
        }
    }

    private static String secretName(MiddlewareCreateRequestDTO req) {
        return req.getName() + "-credentials";
    }

    private static String secretName(MiddlewareDeleteRequestDTO req) {
        return req.getName() + "-credentials";
    }

    /** 配额预检：admin client 读 ResourceQuota（租户 SA 没有 resourcequotas 权限）。 */
    private void assertQuota(MiddlewareCreateRequestDTO req) {
        KubernetesClient admin = clients.getAdminClient(req.getClusterId());
        List<ResourceQuota> quotas = admin.resourceQuotas()
                .inNamespace(req.getNamespace()).list().getItems();
        if (quotas.isEmpty()) {
            return;
        }
        int n = quotaPodCount(req);
        BigDecimal needCpu = amount(req.getCpuRequest()).multiply(BigDecimal.valueOf(n));
        BigDecimal needMem = amount(req.getMemoryRequest()).multiply(BigDecimal.valueOf(n));

        for (ResourceQuota q : quotas) {
            if (q.getSpec() == null || q.getSpec().getHard() == null) {
                continue;
            }
            Map<String, Quantity> used = q.getStatus() == null || q.getStatus().getUsed() == null
                    ? Map.of() : q.getStatus().getUsed();
            checkQuota(q.getSpec().getHard().get("requests.cpu"), used.get("requests.cpu"), needCpu, "CPU");
            checkQuota(q.getSpec().getHard().get("requests.memory"), used.get("requests.memory"), needMem, "内存");
            // limits 配额也要看：只配 limits 的命名空间，requests 检查会放行
            BigDecimal needCpuLimit = amount(req.getCpuLimit()).multiply(BigDecimal.valueOf(n));
            BigDecimal needMemLimit = amount(req.getMemoryLimit()).multiply(BigDecimal.valueOf(n));
            checkQuota(q.getSpec().getHard().get("limits.cpu"), used.get("limits.cpu"), needCpuLimit, "CPU 上限");
            checkQuota(q.getSpec().getHard().get("limits.memory"), used.get("limits.memory"), needMemLimit, "内存上限");
        }
    }

    /** 实际要起的 Pod 数：MySQL 每个数据节点还带一个 HAProxy；哨兵另加固定 3 个哨兵。 */
    private static int quotaPodCount(MiddlewareCreateRequestDTO req) {
        int base = replicas(req);
        if ("SENTINEL".equals(req.getMode())) {
            return base + 3;
        }
        if ("mysql".equals(req.getCode())) {
            return base * 2;
        }
        return base;
    }

    private static void checkQuota(Quantity hard, Quantity used, BigDecimal need, String label) {
        if (hard == null || need.signum() <= 0) {
            return;
        }
        BigDecimal remaining = hard.getNumericalAmount().subtract(amount(used));
        if (need.compareTo(remaining) > 0) {
            throw new CloudPlatformException(EnumResponseType.ERROR,
                    "命名空间配额不足：" + label + " 需要 " + need.toPlainString()
                            + "，剩余 " + remaining.toPlainString());
        }
    }

    private static BigDecimal amount(Quantity q) {
        return q == null ? BigDecimal.ZERO : q.getNumericalAmount();
    }

    private static BigDecimal amount(String q) {
        return q == null || q.isBlank() ? BigDecimal.ZERO : new Quantity(q).getNumericalAmount();
    }

    private static int replicas(MiddlewareCreateRequestDTO req) {
        return req.getReplicas() == null ? 1 : Math.max(1, req.getReplicas());
    }

    private static boolean podReady(Pod pod) {
        if (pod.getStatus() == null || pod.getStatus().getConditions() == null) {
            return false;
        }
        return pod.getStatus().getConditions().stream()
                .anyMatch(c -> "Ready".equals(c.getType()) && "True".equals(c.getStatus()))
                && Objects.equals(pod.getStatus().getPhase(), "Running");
    }
}
