package com.coding.k8score.operations.core;

import com.coding.common.models.k8s.dto.AbnormalPodDTO;
import com.coding.common.models.k8s.dto.ClusterAggregateDTO;
import com.coding.common.models.k8s.dto.ClusterTotalDTO;
import com.coding.common.models.k8s.dto.NamespaceStatDTO;
import com.coding.common.models.k8s.dto.NodeHealthDTO;
import com.coding.common.models.k8s.dto.ResourceCapacityDTO;
import com.coding.common.models.k8s.dto.StorageStatDTO;
import com.coding.common.models.k8s.dto.UnhealthyWorkloadDTO;
import com.coding.k8score.util.QuantityUtil;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.Node;
import io.fabric8.kubernetes.api.model.PersistentVolume;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaim;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.ResourceQuota;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.apps.DaemonSet;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.StatefulSet;
import io.fabric8.kubernetes.client.KubernetesClient;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 集群级资源聚合（集群概览页的数据面）。{@link CoreV1NodeOperations#listPodStats()} 的兄弟，同一条范式：
 * <b>{@code .inAnyNamespace()} 一次 pass + 内存聚合 → typed DTO</b>，所有 K8s 语义收在 k8s-core，
 * k8s-server 只做边界透传、platform-api 只做展示规则。
 *
 * <h2>为什么要一次 pass，而不是按需查</h2>
 * 「总量 / per-ns 明细 / 异常 Pod / 节点健康 / 存储」看着是五件事，其实是<i>同一份内存数据的五个切面</i>：
 * 一次把对象列到手，剩下的都是 {@code Map} 归并。反过来做（每段一个端点、或每命名空间一次 list）
 * 会让 API 调用次数随命名空间数量线性增长 —— 大集群上是 O(ns) 次 round trip。故本类的
 * {@link #aggregate()} 固定 <b>9 次 list</b>（pods、3 种 workload、services、quotas、PVC、PV、nodes），
 * <b>与命名空间数量无关</b>；这条约束有单测盯着（{@code ClusterAggregationOperationsTest}）。
 *
 * <h2>为什么这份聚合留在适配器侧（2026-10-10 与用户确认，有意为之）</h2>
 * 本仓库的立场是「k8s-server = 适配器，唯一的业务是 RBAC 边界隔离」；按那条立场，"平台的概览视图"本该在
 * platform-api。这里有意留在适配器侧，理由<b>只有规模这一条</b>：它要读 7 族全量对象（大集群上是几十万对象），
 * 搬上去就得把全量 DTO 搬过 api↔k8s-server 那一跳 —— {@code K8sServerGateway} 会把整个响应体缓冲成 String，
 * k8s-server 还要为每个对象跑一遍 converter；而在这里直接读 fabric8 对象、回一个 <b>几 KB</b> 的
 * {@link ClusterAggregateDTO}。而且 api 侧当时并不具备所需的跨命名空间出口（见下）。
 * <p><b>换句话说：留在这儿的不是策略，是「读一次集群状态并汇总」这件事本身。</b>
 * 配套的判据边界（新增代码时守这条）：<b>任何引用租户 / 分配表 / 权限模板 / 产品命名约定 / 产品阈值的逻辑
 * 都不许进本类</b> —— 那些属 platform-api（如：哪些段进首屏、TTL、降级、能力 flag、Top-N、给谁看）。
 * <p>反方意见与何时该改判：若目标集群规模在几千 Pod 量级，把 7 族对象搬过这一跳只是几 MB，
 * 那时"聚合归业务侧、适配器只剩薄 CRUD"更划算。届时需要的改动见
 * {@code docs/development/backend-layering.md} §5.1（需为 workloads / services / resourcequotas / PVC
 * 各补一个 {@code listAll} 覆写 + 对应权限行）。
 *
 * <h2>性能与降级</h2>
 * 9 次全量 list 在大集群上不便宜，故结果在 platform-api 侧有短 TTL 缓存（45s，同 B7 CalicoService），
 * 且 per-ns 明细是懒加载（概览首屏只吃总量段）。<b>本类不吞异常</b>：任一次 list 失败即整体抛错 ——
 * 半个快照比没有快照更危险（「0 个 Pending PVC」与「读不到 PVC」在运维上是相反结论），
 * 降级由调用方整段做成 null。
 *
 * <h2>口径（与用户敲定，勿各处重写）</h2>
 * <ul>
 *   <li><b>allocated</b> = Σ {@code pod.spec.containers[].resources.requests}（只算应用容器，与
 *       {@link CoreV1NodeOperations#aggregate(List)} 同口径 —— 节点页的「请求分配率」与本页要对得上；
 *       init 容器按 K8s 调度语义取 max 而非求和，此处有意不引入第二套算法）。</li>
 *   <li><b>异常 Pod</b>：{@code phase ∈ {Pending, Failed, Unknown}}，或任一容器（含 init）waiting ∈
 *       {CrashLoopBackOff, ImagePullBackOff, ErrImagePull} / terminated ∈ {OOMKilled, Error}；
 *       命中多个时取最严重的那个作 reason（{@link #abnormalReason(Pod)}）。</li>
 *   <li><b>Ready</b>：{@code conditions[type=Ready].status == "True"}；缺失/Unknown 一律算未 Ready。</li>
 *   <li><b>单位</b>：cpu=核、memory=字节，{@link BigDecimal} 基础单位，Quantity 走 {@link QuantityUtil}。</li>
 * </ul>
 */
@Slf4j
public class ClusterAggregationOperations {

    /**
     * 异常 Pod 明细封顶：首屏要回答的是「现在该处理什么」，不是全量审计。
     * 完整条数另外给（{@link ClusterAggregateDTO#getAbnormalPodTotal()}），UI 提示「仅显示前 N 条」。
     */
    static final int ABNORMAL_POD_CAP = 200;

    /** 不健康工作负载明细封顶：同上（同一个意图：首屏给「该看什么」）。 */
    static final int UNHEALTHY_WORKLOAD_CAP = 200;

    /** 慢聚合告警阈值（毫秒），见 {@link #warnIfSlow}。 */
    private static final long SLOW_AGGREGATE_MILLIS = 5_000;

    /** 工作负载 kind（K8s 原生 PascalCase，与 pod owner 的写法一致） */
    private static final String KIND_DEPLOYMENT = "Deployment";
    private static final String KIND_STATEFULSET = "StatefulSet";

    private static final String READY = "Ready";

    /** 节点 pressure 条件类型（status=True 即命中） */
    private static final List<String> PRESSURE_TYPES = List.of("MemoryPressure", "DiskPressure", "PIDPressure");

    /** ResourceQuota 里 CPU / 内存对应的 key —— 与平台配额编辑器的口径同一对（见 CoreV1ResourceQuotaConverter） */
    private static final String QUOTA_CPU = "requests.cpu";
    private static final String QUOTA_MEMORY = "requests.memory";

    private final KubernetesClient client;

    public ClusterAggregationOperations(KubernetesClient client) {
        this.client = client;
    }

    /**
     * 全集群聚合。固定 9 次 list（见类注释），与命名空间数量无关。
     * <p>admin client（cluster-admin kubeconfig）下 {@code .inAnyNamespace()} 可读全部命名空间，
     * 含 kube-system 等非租户命名空间 —— 这是集群级概览要的视野。
     */
    public ClusterAggregateDTO aggregate() {
        ClusterObjects objects = new ClusterObjects(
                client.pods().inAnyNamespace().list().getItems(),
                client.apps().deployments().inAnyNamespace().list().getItems(),
                client.apps().statefulSets().inAnyNamespace().list().getItems(),
                client.apps().daemonSets().inAnyNamespace().list().getItems(),
                client.services().inAnyNamespace().list().getItems(),
                client.resourceQuotas().inAnyNamespace().list().getItems(),
                client.persistentVolumeClaims().inAnyNamespace().list().getItems(),
                client.persistentVolumes().list().getItems(),
                client.nodes().list().getItems());
        long start = System.nanoTime();
        ClusterAggregateDTO out = aggregate(objects);
        warnIfSlow(objects, (System.nanoTime() - start) / 1_000_000);
        return out;
    }

    /**
     * 慢聚合告警（性能可观测性）：大集群上这 9 次全量 list 本来就慢，而 platform-api 侧有 45s TTL 缓存兜着
     * ——「慢」在接口耗时上因此看不出来。留一条<b>带对象规模</b>的日志，用来判断缓存是否生效、
     * 以及哪一族大到需要改成窄查询（分页/只取必要字段）。阈值取 5s：低于它的聚合不值一提，
     * 高于它说明这次没吃到缓存或集群真的很大。
     */
    private static void warnIfSlow(ClusterObjects o, long millis) {
        if (millis < SLOW_AGGREGATE_MILLIS) {
            return;
        }
        log.warn("集群资源聚合耗时 {} ms（pods={} deployments={} statefulsets={} daemonsets={} services={} "
                        + "resourcequotas={} pvcs={} pvs={} nodes={}）",
                millis, orEmpty(o.pods()).size(), orEmpty(o.deployments()).size(), orEmpty(o.statefulSets()).size(),
                orEmpty(o.daemonSets()).size(), orEmpty(o.services()).size(), orEmpty(o.quotas()).size(),
                orEmpty(o.pvcs()).size(), orEmpty(o.pvs()).size(), orEmpty(o.nodes()).size());
    }

    /** 包内可见：一次 pass 的原始对象快照，单测直接构造，不依赖 client。 */
    record ClusterObjects(List<Pod> pods,
                          List<Deployment> deployments,
                          List<StatefulSet> statefulSets,
                          List<DaemonSet> daemonSets,
                          List<Service> services,
                          List<ResourceQuota> quotas,
                          List<PersistentVolumeClaim> pvcs,
                          List<PersistentVolume> pvs,
                          List<Node> nodes) {
    }

    /** 纯内存聚合；包内可见便于单测（不依赖 client）。now = 计算 ageSeconds 的基准（unix 秒）。 */
    ClusterAggregateDTO aggregate(ClusterObjects objects) {
        return aggregate(objects, Instant.now().getEpochSecond());
    }

    ClusterAggregateDTO aggregate(ClusterObjects objects, long nowEpochSecond) {
        // TreeMap：per-ns 行按 namespace 升序输出（前端表格的稳定初序，且与 Thanos by(namespace) 结果对齐）
        Map<String, NsAcc> byNs = new TreeMap<>();

        // ---- pods：requests/limits + 异常清单（同一 pass，不额外查一次）----
        List<AbnormalPodDTO> abnormal = new ArrayList<>();
        for (Pod pod : orEmpty(objects.pods())) {
            String ns = namespaceOf(pod);
            if (ns == null) {
                continue;
            }
            NsAcc acc = byNs.computeIfAbsent(ns, k -> new NsAcc());
            acc.podCount++;
            addPodResources(acc, pod);
            AbnormalPodDTO ab = abnormalOf(pod, nowEpochSecond);
            if (ab != null) {
                abnormal.add(ab);
            }
        }

        // ---- workloads / services：per-ns 计数（Deployment/Sts 顺带归出不健康项）----
        List<UnhealthyWorkloadDTO> unhealthy = new ArrayList<>();
        countWorkloads(objects.deployments(), byNs, unhealthy, KIND_DEPLOYMENT);
        countWorkloads(objects.statefulSets(), byNs, unhealthy, KIND_STATEFULSET);
        countByNamespace(objects.daemonSets()).forEach((ns, n) -> byNs.computeIfAbsent(ns, k -> new NsAcc()).dsCount = n);
        countByNamespace(objects.services()).forEach((ns, n) -> byNs.computeIfAbsent(ns, k -> new NsAcc()).svcCount = n);

        // ---- PVC：per-ns 计数 + 状态归类 ----
        int pvcBound = 0;
        int pvcPending = 0;
        int pvcLost = 0;
        for (PersistentVolumeClaim pvc : orEmpty(objects.pvcs())) {
            String ns = namespaceOf(pvc);
            if (ns != null) {
                byNs.computeIfAbsent(ns, k -> new NsAcc()).pvcCount++;
            }
            String phase = phaseOf(pvc);
            if ("Bound".equalsIgnoreCase(phase)) {
                pvcBound++;
            } else if ("Pending".equalsIgnoreCase(phase)) {
                pvcPending++;
            } else if ("Lost".equalsIgnoreCase(phase)) {
                pvcLost++;
            }
        }

        // ---- ResourceQuota：per-ns 配额（hard/used）----
        for (ResourceQuota rq : orEmpty(objects.quotas())) {
            String ns = namespaceOf(rq);
            if (ns == null) {
                continue;
            }
            applyQuota(byNs.computeIfAbsent(ns, k -> new NsAcc()), rq);
        }

        // ---- nodes：健康投影 + allocatable 合计（cluster-scoped，1 次 list）----
        List<NodeHealthDTO> nodeHealth = new ArrayList<>();
        BigDecimal cpuAllocatable = BigDecimal.ZERO;
        BigDecimal memAllocatable = BigDecimal.ZERO;
        for (Node node : orEmpty(objects.nodes())) {
            nodeHealth.add(healthOf(node));
            cpuAllocatable = add(cpuAllocatable, allocatableOf(node, "cpu"));
            memAllocatable = add(memAllocatable, allocatableOf(node, "memory"));
        }
        nodeHealth.sort(Comparator.comparing(NodeHealthDTO::getName, Comparator.nullsLast(Comparator.naturalOrder())));

        ResourceCapacityDTO nodeCapacity = new ResourceCapacityDTO();
        nodeCapacity.setCpuAllocatable(cpuAllocatable);
        nodeCapacity.setMemoryAllocatable(memAllocatable);

        // ---- PV：容量（total vs bound）----
        StorageStatDTO storage = new StorageStatDTO();
        BigDecimal pvCapacity = BigDecimal.ZERO;
        BigDecimal pvBound = BigDecimal.ZERO;
        for (PersistentVolume pv : orEmpty(objects.pvs())) {
            storage.setPvCount(storage.getPvCount() + 1);
            BigDecimal cap = capacityOf(pv);
            pvCapacity = add(pvCapacity, cap);
            if ("Bound".equalsIgnoreCase(phaseOf(pv))) {
                pvBound = add(pvBound, cap);
            }
        }
        storage.setPvCapacityBytes(pvCapacity);
        storage.setPvBoundBytes(pvBound);
        storage.setPvcBound(pvcBound);
        storage.setPvcPending(pvcPending);
        storage.setPvcLost(pvcLost);

        ClusterAggregateDTO out = new ClusterAggregateDTO();
        out.setNamespaces(byNs.entrySet().stream().map(e -> e.getValue().toDTO(e.getKey())).toList());
        out.setTotal(totalOf(byNs, storage));
        out.setAbnormalPods(sortAndCap(abnormal));
        out.setAbnormalPodTotal(abnormal.size());
        out.setAbnormalPodReasonCounts(reasonCounts(abnormal));
        out.setUnhealthyWorkloads(sortAndCapWorkloads(unhealthy));
        out.setUnhealthyWorkloadTotal(unhealthy.size());
        out.setNodeHealth(nodeHealth);
        out.setNodeCapacity(nodeCapacity);
        out.setStorage(storage);
        return out;
    }

    // ==================== workloads ====================

    /**
     * 一批工作负载（Deployment / StatefulSet）：per-ns 计数 + 顺带归出「副本不足」的。
     * <p>与 pods 同一思路 —— 对象已经列在手上了，判定不该另开一遍扫描。
     */
    private static void countWorkloads(List<? extends HasMetadata> items, Map<String, NsAcc> byNs,
                                       List<UnhealthyWorkloadDTO> unhealthy, String kind) {
        for (HasMetadata item : orEmpty(items)) {
            String ns = namespaceOf(item);
            if (ns != null) {
                NsAcc acc = byNs.computeIfAbsent(ns, k -> new NsAcc());
                if (KIND_DEPLOYMENT.equals(kind)) {
                    acc.deployCount++;
                } else {
                    acc.stsCount++;
                }
            }
            UnhealthyWorkloadDTO bad = unhealthyOf(item, kind);
            if (bad != null) {
                unhealthy.add(bad);
            }
        }
    }

    /**
     * 不健康工作负载投影（口径 §3：{@code status.readyReplicas < spec.replicas}）；健康返回 null。
     * <p>两个缺省值的取法：{@code spec.replicas} 省略即 <b>1</b>（K8s 默认值），{@code readyReplicas} 缺席按
     * <b>0</b> 计 —— 于是「刚创建还没起来」会正确地进清单，而 {@code replicas: 0}（有意缩容到零）不会。
     * <p>只处理 Deployment / StatefulSet：DaemonSet 没有 replicas 语义（它的期望数是节点数）。
     */
    static UnhealthyWorkloadDTO unhealthyOf(HasMetadata item, String kind) {
        int replicas;
        Integer ready;
        if (item instanceof Deployment d) {
            replicas = d.getSpec() != null ? replicasOrOne(d.getSpec().getReplicas()) : 1;
            ready = d.getStatus() != null ? d.getStatus().getReadyReplicas() : null;
        } else if (item instanceof StatefulSet s) {
            replicas = s.getSpec() != null ? replicasOrOne(s.getSpec().getReplicas()) : 1;
            ready = s.getStatus() != null ? s.getStatus().getReadyReplicas() : null;
        } else {
            return null;
        }
        int readyReplicas = ready == null ? 0 : Math.max(ready, 0);
        if (readyReplicas >= replicas) {
            return null;
        }
        UnhealthyWorkloadDTO dto = new UnhealthyWorkloadDTO();
        dto.setNamespace(namespaceOf(item));
        dto.setName(item.getMetadata() != null ? item.getMetadata().getName() : null);
        dto.setKind(kind);
        dto.setReadyReplicas(readyReplicas);
        dto.setReplicas(replicas);
        return dto;
    }

    /** {@code spec.replicas} 省略即 1；负值不合法，按 0 计以免凭空造出「副本不足」。 */
    private static int replicasOrOne(Integer replicas) {
        return replicas == null ? 1 : Math.max(replicas, 0);
    }

    /** 缺得最多的排前面 → ns/name 定序（结果稳定，单测可断言顺序）。 */
    private static List<UnhealthyWorkloadDTO> sortAndCapWorkloads(List<UnhealthyWorkloadDTO> list) {
        return list.stream()
                .sorted(Comparator.comparingInt((UnhealthyWorkloadDTO w) -> w.getReplicas() - w.getReadyReplicas()).reversed()
                        .thenComparing(UnhealthyWorkloadDTO::getNamespace, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(UnhealthyWorkloadDTO::getName, Comparator.nullsLast(Comparator.naturalOrder())))
                .limit(UNHEALTHY_WORKLOAD_CAP)
                .toList();
    }

    // ==================== pods ====================

    /** 累加一个 pod 的 requests/limits（只算 {@code spec.containers}，口径同节点页）。 */
    private static void addPodResources(NsAcc acc, Pod pod) {
        if (pod.getSpec() == null || pod.getSpec().getContainers() == null) {
            return;
        }
        for (Container c : pod.getSpec().getContainers()) {
            var res = c.getResources();
            if (res == null) {
                continue;
            }
            acc.cpuRequest = add(acc.cpuRequest, base(res.getRequests(), "cpu"));
            acc.memRequest = add(acc.memRequest, base(res.getRequests(), "memory"));
            acc.cpuLimit = add(acc.cpuLimit, base(res.getLimits(), "cpu"));
            acc.memLimit = add(acc.memLimit, base(res.getLimits(), "memory"));
        }
    }

    /** 异常 Pod 投影；健康返回 null。 */
    static AbnormalPodDTO abnormalOf(Pod pod, long nowEpochSecond) {
        String reason = abnormalReason(pod);
        if (reason == null) {
            return null;
        }
        AbnormalPodDTO dto = new AbnormalPodDTO();
        dto.setNamespace(namespaceOf(pod));
        dto.setName(pod.getMetadata() != null ? pod.getMetadata().getName() : null);
        dto.setPhase(pod.getStatus() != null ? pod.getStatus().getPhase() : null);
        dto.setReason(reason);
        dto.setRestarts(restartsOf(pod));
        dto.setNode(pod.getSpec() != null ? pod.getSpec().getNodeName() : null);
        dto.setAgeSeconds(ageSecondsOf(pod, nowEpochSecond));
        return dto;
    }

    /**
     * 异常判定的唯一出处（口径见类注释）。返回归组用的 reason（最严重那个），健康返回 null。
     * <p>init 容器用同一判据：卡在 {@code Init:CrashLoopBackOff} 的 Pod 其 phase 也是 Pending，
     * 不查 init 就只能笼统归到「Pending」—— 而那正是最该被一眼看见的一种。
     */
    static String abnormalReason(Pod pod) {
        var st = pod.getStatus();
        if (st == null) {
            return null;
        }
        String best = null;
        String phase = st.getPhase();
        if ("Pending".equals(phase) || "Failed".equals(phase) || "Unknown".equals(phase)) {
            best = phase;
        }
        best = worse(best, worstContainerReason(st.getContainerStatuses()));
        return worse(best, worstContainerReason(st.getInitContainerStatuses()));
    }

    /** 一组容器状态里最严重的异常 reason；都没有返回 null。 */
    private static String worstContainerReason(List<ContainerStatus> statuses) {
        String best = null;
        for (ContainerStatus cs : orEmpty(statuses)) {
            String r = containerReason(cs);
            if (r != null && severity(r) > severity(best)) {
                best = r;
            }
        }
        return best;
    }

    /** 单容器：waiting 的拉镜像/崩溃重启、terminated 的 OOM/Error；其余（Running/Completed）返回 null。 */
    private static String containerReason(ContainerStatus cs) {
        if (cs == null || cs.getState() == null) {
            return null;
        }
        var waiting = cs.getState().getWaiting();
        if (waiting != null) {
            String r = waiting.getReason();
            if ("CrashLoopBackOff".equals(r) || "ImagePullBackOff".equals(r) || "ErrImagePull".equals(r)) {
                return r;
            }
        }
        var terminated = cs.getState().getTerminated();
        if (terminated != null) {
            String r = terminated.getReason();
            // "Completed"（正常退出）不在此列 —— 那是 Job/CronJob 的成功态
            if ("OOMKilled".equals(r) || "Error".equals(r)) {
                return r;
            }
        }
        return null;
    }

    /** 取更严重的那个（null 视为最轻）。 */
    private static String worse(String a, String b) {
        if (b == null) {
            return a;
        }
        return severity(b) > severity(a) ? b : a;
    }

    /** 严重度：既用于「一个 pod 命中多个异常时取哪个」，也用于明细清单排序。 */
    private static int severity(String reason) {
        return switch (reason == null ? "" : reason) {
            case "CrashLoopBackOff" -> 100;
            case "OOMKilled" -> 90;
            case "ImagePullBackOff" -> 80;
            case "ErrImagePull" -> 70;
            case "Error" -> 60;
            case "Failed" -> 50;
            case "Unknown" -> 40;
            case "Pending" -> 30;
            default -> 0;
        };
    }

    /** 严重度降序 → 重启次数降序 → ns/name 升序（结果稳定，单测可断言顺序）。 */
    private static List<AbnormalPodDTO> sortAndCap(List<AbnormalPodDTO> abnormal) {
        return abnormal.stream()
                .sorted(Comparator.comparingInt((AbnormalPodDTO p) -> severity(p.getReason())).reversed()
                        .thenComparing(Comparator.comparingInt(AbnormalPodDTO::getRestarts).reversed())
                        .thenComparing(AbnormalPodDTO::getNamespace, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(AbnormalPodDTO::getName, Comparator.nullsLast(Comparator.naturalOrder())))
                .limit(ABNORMAL_POD_CAP)
                .toList();
    }

    /**
     * 异常 Pod 按 reason 分组计数（<b>全量</b>，在封顶之前算）。
     * <p>顺序：严重度降序 → 计数降序（结果稳定，首屏 chip 的顺序不随 pod 名单抖动）。
     */
    private static Map<String, Integer> reasonCounts(List<AbnormalPodDTO> abnormal) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (AbnormalPodDTO p : abnormal) {
            counts.merge(p.getReason() == null ? "Unknown" : p.getReason(), 1, Integer::sum);
        }
        Map<String, Integer> out = new LinkedHashMap<>();
        counts.entrySet().stream()
                .sorted(Comparator.comparingInt((Map.Entry<String, Integer> e) -> severity(e.getKey())).reversed()
                        .thenComparing(Map.Entry::getValue, Comparator.<Integer>reverseOrder()))
                .forEach(e -> out.put(e.getKey(), e.getValue()));
        return out;
    }

    /** Σ 全部容器（含 init）重启次数。 */
    private static int restartsOf(Pod pod) {
        var st = pod.getStatus();
        if (st == null) {
            return 0;
        }
        int n = 0;
        for (ContainerStatus cs : orEmpty(st.getContainerStatuses())) {
            n += cs.getRestartCount() == null ? 0 : cs.getRestartCount();
        }
        for (ContainerStatus cs : orEmpty(st.getInitContainerStatuses())) {
            n += cs.getRestartCount() == null ? 0 : cs.getRestartCount();
        }
        return n;
    }

    /** creationTimestamp（RFC3339）→ 至今秒数；解析失败返回 null（不猜）。 */
    private static Long ageSecondsOf(Pod pod, long nowEpochSecond) {
        String ts = pod.getMetadata() != null ? pod.getMetadata().getCreationTimestamp() : null;
        if (ts == null || ts.isBlank()) {
            return null;
        }
        try {
            return Duration.between(OffsetDateTime.parse(ts).toInstant(), Instant.ofEpochSecond(nowEpochSecond)).getSeconds();
        } catch (Exception e) {
            return null;
        }
    }

    // ==================== nodes ====================

    /**
     * 节点健康投影：Ready + pressure 类型 + kubelet 版本。
     * <p><b>为什么不复用 {@code CoreV1NodeConverter} → NodeDTO</b>：NodeDTO 是节点页的完整契约
     * （角色/污点/容量/地址/多版本…），概览只要 4 个字段；为它引一整个 converter 反而把「概览」
     * 和「节点页」耦上。<b>但判据只有这一处</b>：Ready = {@code conditions[type=Ready].status == "True"}
     * 的读法必须与转换器一致（那边把它映射成 {@code NodeDTO.status}），改口径时两处一起看。
     */
    static NodeHealthDTO healthOf(Node node) {
        NodeHealthDTO dto = new NodeHealthDTO();
        dto.setName(node.getMetadata() != null ? node.getMetadata().getName() : null);
        List<String> pressures = new ArrayList<>();
        boolean ready = false;
        if (node.getStatus() != null) {
            for (var c : orEmpty(node.getStatus().getConditions())) {
                if (READY.equals(c.getType())) {
                    ready = "True".equals(c.getStatus());
                } else if (PRESSURE_TYPES.contains(c.getType()) && "True".equals(c.getStatus())) {
                    pressures.add(c.getType());
                }
            }
            if (node.getStatus().getNodeInfo() != null) {
                dto.setVersion(node.getStatus().getNodeInfo().getKubeletVersion());
            }
        }
        dto.setReady(ready);
        dto.setPressures(pressures);
        return dto;
    }

    private static BigDecimal allocatableOf(Node node, String key) {
        if (node.getStatus() == null || node.getStatus().getAllocatable() == null) {
            return null;
        }
        return QuantityUtil.toBase(node.getStatus().getAllocatable().get(key));
    }

    // ==================== storage ====================

    private static BigDecimal capacityOf(PersistentVolume pv) {
        if (pv.getSpec() == null || pv.getSpec().getCapacity() == null) {
            return null;
        }
        return QuantityUtil.toBase(pv.getSpec().getCapacity().get("storage"));
    }

    private static String phaseOf(PersistentVolume pv) {
        return pv.getStatus() != null ? pv.getStatus().getPhase() : null;
    }

    private static String phaseOf(PersistentVolumeClaim pvc) {
        return pvc.getStatus() != null ? pvc.getStatus().getPhase() : null;
    }

    // ==================== quota ====================

    /**
     * 配额落到 per-ns 行。<b>hard 取 spec、used 取 status</b> —— 与 {@code CoreV1ResourceQuotaConverter}
     * （命名空间页 / 配额编辑器）同一口径：spec 是期望值且创建即写入，status 只有控制器算得出；
     * 两边取同一份数据，概览表的配额列才不会和命名空间页对不上。
     */
    private static void applyQuota(NsAcc acc, ResourceQuota rq) {
        acc.quotaCpuHard = merge(acc.quotaCpuHard, rq.getSpec() == null ? null : base(rq.getSpec().getHard(), QUOTA_CPU));
        acc.quotaMemHard = merge(acc.quotaMemHard, rq.getSpec() == null ? null : base(rq.getSpec().getHard(), QUOTA_MEMORY));
        var st = rq.getStatus();
        acc.quotaCpuUsed = merge(acc.quotaCpuUsed, st == null ? null : base(st.getUsed(), QUOTA_CPU));
        acc.quotaMemUsed = merge(acc.quotaMemUsed, st == null ? null : base(st.getUsed(), QUOTA_MEMORY));
    }

    /**
     * 配额用的合并：两边都缺席 → <b>null</b>（保持「没有配额」而不是 0）。
     * 若这里退化成 {@link #add}，「只限 pod 数、没限 CPU」的 quota 会把 CPU 列显示成 0 ——
     * 读起来是「CPU 配额为 0（全被禁）」，与事实相反。故与累加语义分开。
     */
    private static BigDecimal merge(BigDecimal a, BigDecimal b) {
        if (b == null) {
            return a;
        }
        return a == null ? b : a.add(b);
    }

    // ==================== 通用小工具 ====================

    /** 按 metadata.namespace 计数（无 namespace 的条目忽略）。 */
    private static Map<String, Integer> countByNamespace(List<? extends HasMetadata> items) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (HasMetadata item : orEmpty(items)) {
            String ns = namespaceOf(item);
            if (ns != null) {
                out.merge(ns, 1, Integer::sum);
            }
        }
        return out;
    }

    private static String namespaceOf(HasMetadata item) {
        if (item == null || item.getMetadata() == null) {
            return null;
        }
        String ns = item.getMetadata().getNamespace();
        return ns == null || ns.isBlank() ? null : ns;
    }

    private static BigDecimal base(Map<String, Quantity> quantities, String key) {
        return quantities == null ? null : QuantityUtil.toBase(quantities.get(key));
    }

    /** null-safe 相加（null 当 0；两侧都 null 时返回 0 而不是 null —— 合计值不存在「未知」语义）。 */
    private static BigDecimal add(BigDecimal a, BigDecimal b) {
        if (a == null) {
            a = BigDecimal.ZERO;
        }
        return b == null ? a : a.add(b);
    }

    private static <T> List<T> orEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }

    /** 总量行 = 各 per-ns 行之和 + 集群级的 PV 计数/命名空间数。 */
    private static ClusterTotalDTO totalOf(Map<String, NsAcc> byNs, StorageStatDTO storage) {
        ClusterTotalDTO t = new ClusterTotalDTO();
        t.setCpuRequest(BigDecimal.ZERO);
        t.setMemRequest(BigDecimal.ZERO);
        t.setCpuLimit(BigDecimal.ZERO);
        t.setMemLimit(BigDecimal.ZERO);
        for (NsAcc a : byNs.values()) {
            t.setCpuRequest(add(t.getCpuRequest(), a.cpuRequest));
            t.setMemRequest(add(t.getMemRequest(), a.memRequest));
            t.setCpuLimit(add(t.getCpuLimit(), a.cpuLimit));
            t.setMemLimit(add(t.getMemLimit(), a.memLimit));
            t.setPodCount(t.getPodCount() + a.podCount);
            t.setDeploymentCount(t.getDeploymentCount() + a.deployCount);
            t.setStatefulsetCount(t.getStatefulsetCount() + a.stsCount);
            t.setDaemonsetCount(t.getDaemonsetCount() + a.dsCount);
            t.setServiceCount(t.getServiceCount() + a.svcCount);
            t.setPvcCount(t.getPvcCount() + a.pvcCount);
        }
        t.setNamespaceCount(byNs.size());
        t.setPvCount(storage.getPvCount());
        return t;
    }

    /** 可变累加器（逐对象归并的阶段用；最后一次性转成 DTO）。 */
    private static final class NsAcc {
        private int podCount;
        private int deployCount;
        private int stsCount;
        private int dsCount;
        private int svcCount;
        private int pvcCount;
        private BigDecimal cpuRequest;
        private BigDecimal memRequest;
        private BigDecimal cpuLimit;
        private BigDecimal memLimit;
        private BigDecimal quotaCpuHard;
        private BigDecimal quotaMemHard;
        private BigDecimal quotaCpuUsed;
        private BigDecimal quotaMemUsed;

        private NamespaceStatDTO toDTO(String namespace) {
            NamespaceStatDTO d = new NamespaceStatDTO();
            d.setNamespace(namespace);
            d.setPodCount(podCount);
            d.setDeployCount(deployCount);
            d.setStsCount(stsCount);
            d.setDsCount(dsCount);
            d.setSvcCount(svcCount);
            d.setPvcCount(pvcCount);
            d.setCpuRequest(cpuRequest);
            d.setMemRequest(memRequest);
            d.setCpuLimit(cpuLimit);
            d.setMemLimit(memLimit);
            d.setQuotaCpuHard(quotaCpuHard);
            d.setQuotaMemHard(quotaMemHard);
            d.setQuotaCpuUsed(quotaCpuUsed);
            d.setQuotaMemUsed(quotaMemUsed);
            return d;
        }
    }
}
