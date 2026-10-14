package com.coding.k8sserver.pipeline;

import com.coding.k8score.factory.KubernetesOperationsFactory;
import io.fabric8.kubernetes.client.Watch;
import lombok.extern.slf4j.Slf4j;
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
 * **活动 watch 台账**。设计见 spec §4.1 —— 只在有运行在跑时开一条 watch，结束就关，空闲时零连接。
 *
 * <p>台账同时是**超时判定的数据源**：{@link #overdue} 只看它，**不打集群** ——
 * 这正是它不算"轮询"的理由。
 *
 * <p>⚠️ **为什么持有的是 {@link KubernetesOperationsFactory} 而不是一个 {@code PipelineRunOperations} 实例**：
 * `PipelineRunOperations` 不是 Spring bean（它由工厂按 {@code (client, converter)} 现场 new 出来，
 * 而 client 是**按集群**取的）。把它当构造参数注入会让上下文起不来，而且一个实例也只对一个集群有效。
 */
@Slf4j
@Component
public class PipelineRunWatchManager {

    /** 一条活动中的 watch。{@code startedAt} 用于超时判定（**只看台账，不打集群**）。 */
    public record ActiveRun(String clusterId, String namespace, String runName, Instant startedAt) { }

    private final KubernetesOperationsFactory operationsFactory;
    private final PipelineRunCallbackClient callback;

    /** key = clusterId + "/" + namespace + "/" + runName */
    private final Map<String, ActiveRun> active = new ConcurrentHashMap<>();
    private final Map<String, Watch> watches = new ConcurrentHashMap<>();
    /** 回调没被确认的（platform-api 重启/不可达）—— 节拍器会重投 */
    private final Set<String> pending = ConcurrentHashMap.newKeySet();

    public PipelineRunWatchManager(KubernetesOperationsFactory operationsFactory,
                                   PipelineRunCallbackClient callback) {
        this.operationsFactory = operationsFactory;
        this.callback = callback;
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
        return new LinkedHashSet<>(pending);
    }

    /**
     * watch 收到终结事件 → 回调。**不确认就进待补集合**，由节拍器重投
     * （platform-api 重启期间的回调会以这种方式补上）。
     */
    void onTerminated(String clusterId, String namespace, String runName) {
        String k = key(clusterId, namespace, runName);
        if (callback.finished(clusterId, namespace, runName)) {
            pending.remove(k);
            recordClosed(clusterId, namespace, runName);
        } else {
            pending.add(k);
        }
    }

    /** 节拍器重投待补的。 */
    void retryPending() {
        for (String k : List.copyOf(pending)) {
            String[] parts = k.split("/", 3);
            if (parts.length != 3) {
                pending.remove(k);
                continue;
            }
            if (callback.finished(parts[0], parts[1], parts[2])) {
                pending.remove(k);
                recordClosed(parts[0], parts[1], parts[2]);
            }
        }
    }

    /** 台账快照，给启动扫描用（它要补开 watch）。 */
    public Map<String, ActiveRun> snapshot() {
        return new LinkedHashMap<>(active);
    }
}
