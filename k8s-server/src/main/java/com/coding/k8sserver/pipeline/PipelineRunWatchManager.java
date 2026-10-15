package com.coding.k8sserver.pipeline;

import com.coding.common.models.k8s.ResourceType;
import com.coding.common.models.k8s.dto.PipelineRunDTO;
import com.coding.common.models.pipeline.PipelineRunSnapshotDTO;
import com.coding.k8score.factory.KubernetesOperationsFactory;
import com.coding.k8score.operations.NamespacedOperations;
import com.coding.k8score.operations.pipelinerun.PipelineRunOperations;
import io.fabric8.kubernetes.client.Watch;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * **按需 watch 台账**。设计见 spec §4.1 —— 只在有运行在跑时开一条 watch，结束就关，空闲时零连接。
 *
 * <p>台账同时是**超时判定的数据源**：{@link #overdue} 只看它，**不打集群** ——
 * 这正是它不算"轮询"的理由。
 *
 * <h2>终结时它做四件事</h2>
 * <ol>
 *   <li>**抓快照**（状态 + 每个节点的状态与日志）—— 集群对象、TaskRun、Pod 都还在，快照就在手上；</li>
 *   <li>**自己落库**（{@link PipelineArchiveService}，k8s-server 本来就有 platform-data 与事务）；</li>
 *   <li>**落库成功了才删对象** —— 删 PipelineRun 会级联带走 TaskRun 与 **Pod**，
 *       这就是「k8s 不留 Completed Pod」那一步；随后删掉这次运行的凭据 Secret；</li>
 *   <li>关掉这条 watch（空闲时零连接）。</li>
 * </ol>
 * 落库没成功就不删：**对象与 Pod 都留着**（那份日志还在），由节拍器重投。
 * 这个取舍是刻意的 —— 宁可多留一会儿，也不要「日志还没落库就把唯一副本删了」。
 */
@Slf4j
@Component
public class PipelineRunWatchManager {

    /** 一条活动中的 watch。{@code startedAt} 用于超时判定（**只看台账，不打集群**）。 */
    public record ActiveRun(String clusterId, String namespace, String runName, Instant startedAt) { }

    private final KubernetesOperationsFactory operationsFactory;
    private final PipelineRunFinisher finisher;

    /** key = clusterId + "/" + namespace + "/" + runName */
    private final Map<String, ActiveRun> active = new ConcurrentHashMap<>();
    private final Map<String, Watch> watches = new ConcurrentHashMap<>();
    /** 回调没被确认的（platform-api 重启/不可达）—— 节拍器会重投。值 = 这次是平台判的超时 */
    /** 终结了但**还没归档成功**的（落库失败 / 抓快照失败）—— 节拍器会重试。值 = 这次是平台判的超时 */
    private final Map<String, Boolean> pending = new ConcurrentHashMap<>();

    public PipelineRunWatchManager(KubernetesOperationsFactory operationsFactory,
                                   PipelineRunFinisher finisher) {
        this.operationsFactory = operationsFactory;
        this.finisher = finisher;
    }

    private static String key(String clusterId, String namespace, String runName) {
        return clusterId + "/" + namespace + "/" + runName;
    }

    public void recordOpened(String clusterId, String namespace, String runName, Instant startedAt) {
        active.put(key(clusterId, namespace, runName),
                new ActiveRun(clusterId, namespace, runName, startedAt));
    }

    public void recordClosed(String clusterId, String namespace, String runName) {
        String k = key(clusterId, namespace, runName);
        active.remove(k);
        Watch w = watches.remove(k);
        if (w != null) {
            w.close();                                // 连接随运行结束而结束
        }
    }

    /**
     * 开一条 watch 盯住这次运行（创建成功之后调）。
     *
     * <p>⚠️ 这是**按需**的全部含义：一条连接、只收这一个对象的事件、运行结束就关。
     */
    public void watch(String clusterId, String namespace, String runName, Instant startedAt) {
        recordOpened(clusterId, namespace, runName, startedAt);
        try {
            Watch w = runOps(clusterId).watchUntilTerminal(namespace, runName,
                    () -> onTerminated(clusterId, namespace, runName, false));
            watches.put(key(clusterId, namespace, runName), w);
        } catch (Exception e) {
            // 开不起来不致命：启动扫描与节拍器还兜着；台账里那条仍在，会被判超时
            log.warn("开运行 watch 失败（台账保留，靠节拍器兜）{}/{}", namespace, runName, e);
        }
    }

    public List<ActiveRun> activeRuns() {
        return new ArrayList<>(active.values());
    }

    /** 超过 {@code timeout} 还没结束的。**只看台账**。 */
    public List<ActiveRun> overdue(Instant now, Duration timeout) {
        return active.values().stream()
                .filter(r -> r.startedAt() != null && r.startedAt().plus(timeout).isBefore(now))
                .toList();
    }

    public Set<String> pending() {
        return new LinkedHashSet<>(pending.keySet());
    }

    /** watch 收到终结事件 → 回调。 */
    void onTerminated(String clusterId, String namespace, String runName) {
        onTerminated(clusterId, namespace, runName, false);
    }

    /** **平台判定超时**而终结的（节拍器发的）—— 快照的状态会被改写成 TIMEOUT。 */
    void onTerminatedAsTimeout(String clusterId, String namespace, String runName) {
        onTerminated(clusterId, namespace, runName, true);
    }

    private void onTerminated(String clusterId, String namespace, String runName, boolean timedOut) {
        String k = key(clusterId, namespace, runName);
        // 抓快照 → 落库 → 删对象，全在 k8s-server 本地做完（见 PipelineRunFinisher）。
        // ⚠️ 落库失败时它**不删对象** —— 那份日志的唯一副本还在集群里，等下一次重试。
        if (finisher.finish(clusterId, namespace, runName, timedOut)) {
            pending.remove(k);
            recordClosed(clusterId, namespace, runName);
        } else {
            pending.put(k, timedOut);
        }
    }

    /** 节拍器重投待补的：**都是本地动作**，没有 HTTP。 */
    void retryPending() {
        for (Map.Entry<String, Boolean> e : Map.copyOf(pending).entrySet()) {
            String[] parts = e.getKey().split("/", 3);
            if (parts.length != 3) {
                pending.remove(e.getKey());
                continue;
            }
            onTerminated(parts[0], parts[1], parts[2], e.getValue());
        }
    }

    /** 台账快照，给启动扫描用（它要补开 watch）。 */
    public Map<String, ActiveRun> snapshot() {
        return new LinkedHashMap<>(active);
    }

    /**
     * 拿某个集群的 PipelineRun 操作（admin client —— 后台动作没有租户身份，租户 SA 也没有集群范围权限）。
     *
     * <p>用带兜底的 `instanceof` 而不是裸转型：`NamespacedOperations` 上没有 `snapshot` / `watchUntilTerminal`
     * （它们是 Tekton 专有的，塞进通用接口更糟），所以这里留一道**响亮**的兜底。
     */
    private PipelineRunOperations runOps(String clusterId) {
        NamespacedOperations<PipelineRunDTO> ops =
                operationsFactory.getAdminNamespacedOperation(ResourceType.PIPELINE_RUN, clusterId);
        if (!(ops instanceof PipelineRunOperations runOps)) {
            throw new IllegalStateException("PIPELINE_RUN 的操作实现不是 PipelineRunOperations："
                    + ops.getClass().getName());
        }
        return runOps;
    }
}
