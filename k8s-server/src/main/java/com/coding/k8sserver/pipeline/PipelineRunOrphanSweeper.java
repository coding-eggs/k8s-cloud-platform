package com.coding.k8sserver.pipeline;

import com.coding.common.models.k8s.ResourceType;
import com.coding.common.models.k8s.dto.PipelineRunDTO;
import com.coding.data.mapper.k8s.K8sClusterMapper;
import com.coding.data.mapper.system.PlatformPipelineRunMapper;
import com.coding.data.models.k8s.K8sCluster;
import com.coding.data.models.system.PlatformPipelineRun;
import com.coding.k8score.factory.KubernetesOperationsFactory;
import com.coding.k8score.operations.NamespacedOperations;
import com.coding.k8score.operations.pipelinerun.PipelineRunOperations;
import io.fabric8.kubernetes.client.KubernetesClientException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 流水线运行的**两个方向**的对账，都在这一个定时任务里。
 *
 * <h2>① 集群 → 库（每个集群只做一次）</h2>
 * 重启之后内存台账是空的：重启前正在跑的运行没有任何 watch，终结事件没人收，
 * 只能等跑满超时 —— 而正常 5 分钟就跑完的运行根本不会超时。所以每个集群的**第一拍**
 * 从集群反查一次「带平台标签且还在 RUNNING」的对象，把 watch 补上。
 *
 * <p>⚠️ 只在每个集群**成功扫过一次之前**做（{@link #bootSwept}）。永远每拍都扫就是
 * 「每 5 分钟轮询一遍所有集群」，正是本设计一直要避免的；而这件事过了启动窗口就没有意义了。
 * 扫不动的集群（连不上）**不进已扫集合**，下一拍再试 —— 它恢复后要能自己补上；
 * 没有 Tekton 的集群（404）算扫过，否则会每 5 分钟报一次。
 *
 * <h2>② 库 → 集群（每一拍）</h2>
 * 库里还说 RUNNING 的那些行，集群里到底还活着吗。它补的是另一个真实踩过的洞
 * （2026-10-20）：运行中升级 Tekton，集群对象被带走。平台的三条既有链路都以「对象还在」为前提 ——
 * <ul>
 *   <li>{@link PipelineRunWatchManager}：对象没了不会再发终结事件；</li>
 *   <li>取消：对不存在的对象是 no-op，接口还回 200；</li>
 *   <li>①只扫一遍，之后对象怎么变它都看不见。</li>
 * </ul>
 * 于是那行永远停在 RUNNING：删除按钮不出现，后端也拒删。这里从**库**出发反查集群，
 * 把「对象没了」「对象终结了但没归档」「对象还在跑但没人盯」三种情况各自归位。
 *
 * <p>这一半**不是轮询集群**：先查库（{@code status = 'RUNNING'}，常态 0~1 条），
 * 只有那些行才会去碰集群；台账正常时它一次集群调用都不发。
 *
 * <h2>三道防误判</h2>
 * <ol>
 *   <li><b>创建宽限期</b>：创建是「先落库、后建对象」两步，中间那一瞬没有对象 ——
 *       刚落的行先放过（默认 2 分钟），否则会把一条正在创建的运行标成失败。</li>
 *   <li><b>已终结 ≠ 已归档</b>：对象终结了但库还是 RUNNING 时，**直接补归档**，
 *       不能只补 watch —— watch 不补发已经发生的事件。</li>
 *   <li><b>单行/单集群失败不外溢</b>：一个集群连不上不能把其余行带走（与工作区回收同形）。</li>
 * </ol>
 */
@Slf4j
@Component
public class PipelineRunOrphanSweeper {

    private final PlatformPipelineRunMapper runMapper;
    private final K8sClusterMapper clusterMapper;
    private final KubernetesOperationsFactory operationsFactory;
    private final PipelineRunWatchManager watch;
    private final PipelineRunFinisher finisher;
    private final PipelineArchiveService archive;
    private final Duration grace;
    private final Boolean enabled;

    /** 已经成功做过「集群 → 库」补盯的集群；每个进程生命周期内只扫一次 */
    private final Set<String> bootSwept = ConcurrentHashMap.newKeySet();

    public PipelineRunOrphanSweeper(PlatformPipelineRunMapper runMapper,
                                    K8sClusterMapper clusterMapper,
                                    KubernetesOperationsFactory operationsFactory,
                                    PipelineRunWatchManager watch,
                                    PipelineRunFinisher finisher,
                                    PipelineArchiveService archive,
                                    @Value("${platform.pipeline.orphan-check.grace:2m}") Duration grace,
                                    @Value("${platform.pipeline.orphan-check.enabled:true}") Boolean enabled) {
        this.runMapper = runMapper;
        this.clusterMapper = clusterMapper;
        this.operationsFactory = operationsFactory;
        this.watch = watch;
        this.finisher = finisher;
        this.archive = archive;
        this.grace = grace;
        this.enabled = enabled;
    }

    /**
     * 启动后第一拍就开始跑（{@code fixedDelay} 不配 initialDelay 时首拍立即执行），
     * 所以「重启后补盯」不会等 5 分钟。
     */
    @Scheduled(fixedDelayString = "${platform.pipeline.orphan-check:5m}")
    public void scheduledSweep() {
        if (!Boolean.TRUE.equals(enabled)) {
            return;
        }
        sweep(Instant.now());
    }

    /** 可单测的入口。 */
    void sweep(Instant now) {
        if (!Boolean.TRUE.equals(enabled)) {
            return;
        }
        bootScan();                 // ① 集群 → 库：每个集群只做一次
        reconcileRunningRows(now);  // ② 库 → 集群：每一拍
    }

    // ---------------------------------------------------------------- ① 集群 → 库

    /** 逐个集群补盯；扫过的进集合，扫不动的下一拍再试。 */
    private void bootScan() {
        List<K8sCluster> clusters;
        try {
            clusters = clusterMapper.listAll();
        } catch (Exception e) {
            log.warn("启动补盯：取集群清单失败（下一拍再试）", e);
            return;
        }
        for (K8sCluster cluster : clusters) {
            if (Integer.valueOf(1).equals(cluster.getEnabled())
                    && cluster.getStatus().equals(K8sCluster.ClusterStatus.CONNECTED)) {
                String clusterId = cluster.getClusterId();
                if (bootSwept.contains(clusterId)) {
                    continue;
                }
                // 停用 / 已知连不上的集群先不碰：一次 connect 超时是 10s 起步，
                // 每 5 分钟去撞一遍会把调度线程白占掉。探活任务（2 分钟）会把它翻回 CONNECTED，
                // 那时这一拍自然就补上了 —— 所以这里**不进已扫集合**
                if (!usable(cluster)) {
                    continue;
                }
                try {
                    bootScanCluster(clusterId);
                    bootSwept.add(clusterId);
                } catch (Exception e) {
                    if (isNotFound(e)) {
                        // 没有 Tekton 的集群没有可补盯的对象 —— 算扫过，否则每 5 分钟报一次
                        log.info("启动补盯：集群没有 Tekton（跳过）cluster={}", clusterId);
                        bootSwept.add(clusterId);
                    } else {
                        // 集群暂时连不上：**不进已扫集合**，下一拍再试
                        log.warn("启动补盯失败（下一拍再试）：cluster={}", clusterId, e);
                    }
                }
            }
        }
    }

    private void bootScanCluster(String clusterId) {
        // listAll 已在 PipelineRunOperations 里覆写，带平台标签过滤 —— 别人的 run 扫不到
        for (PipelineRunDTO run : runOps(clusterId).listAll(null, null)) {
            if (!"RUNNING".equals(run.getStatus())) {
                continue;
            }
            if (isTracked(clusterId, run.getNamespace(), run.getName())) {
                continue;
            }
            log.info("启动补盯：补开 watch cluster={} {}/{}",
                    clusterId, run.getNamespace(), run.getName());
            watch.watch(clusterId, run.getNamespace(), run.getName(), startedAtOf(run));
        }
    }

    // ---------------------------------------------------------------- ② 库 → 集群

    private void reconcileRunningRows(Instant now) {
        List<PlatformPipelineRun> rows;
        try {
            rows = runMapper.listRunning();
        } catch (Exception e) {
            // 查库失败就跳过这一拍 —— 下一拍再来，别把异常抛到调度线程上
            log.warn("孤儿对账：查 RUNNING 运行失败（跳过这一拍）", e);
            return;
        }
        for (PlatformPipelineRun row : rows) {
            if (!settledEnough(row.getCreatedAt(), now, grace)) {
                continue;
            }
            try {
                reconcile(row);
            } catch (Exception e) {
                log.warn("孤儿对账失败：{}/{}", row.getNamespace(), row.getRunName(), e);
            }
        }
    }

    /** 一行一种归宿。 */
    void reconcile(PlatformPipelineRun row) {
        String clusterId = row.getClusterId();
        String namespace = row.getNamespace();
        String runName = row.getRunName();

        PipelineRunDTO object = runOps(clusterId).get(namespace, runName);
        if (object == null) {
            // 对象没了：没有日志可抓，只能如实结案（幂等；已归档的行不会被改）
            archive.markLost(runName);
            return;
        }
        if (!"RUNNING".equals(object.getStatus())) {
            // 对象终结了但库里还是 RUNNING（它终结时 k8s-server 正好不在/断线）——
            // 直接补归档。**不能只补 watch**：watch 只收新事件，不会补发已经发生的那次终结
            log.info("对账：运行已终结但库里仍在跑，补归档 {}/{}", namespace, runName);
            finisher.finish(clusterId, namespace, runName, false);
            return;
        }
        if (isTracked(clusterId, namespace, runName)) {
            return;
        }
        // 还在跑、但台账里没有（watch 开失败 / 台账被清）→ 补开。
        // startedAt 取对象上的 startTime，不是 now —— 否则超时时钟会被重置
        log.info("对账：运行还在跑但台账里没有，补开 watch {}/{}", namespace, runName);
        watch.watch(clusterId, namespace, runName, startedAtOf(object));
    }

    // ---------------------------------------------------------------- 共用

    private boolean isTracked(String clusterId, String namespace, String runName) {
        String key = clusterId + "/" + namespace + "/" + runName;
        return watch.snapshot().containsKey(key) || watch.pending().contains(key);
    }

    /** 404 = 这个集群没有 Tekton 的 CRD（不是「对象不存在」，那个由 GET 返回 null 表达）。 */
    private static boolean isNotFound(Throwable e) {
        return e instanceof KubernetesClientException kce && kce.getCode() == 404;
    }

    /** 停用或已知连不上的集群不打 —— 与工作区回收同一道闸。 */
    private static boolean usable(K8sCluster cluster) {
        return cluster != null
                && Integer.valueOf(1).equals(cluster.getEnabled())
                && K8sCluster.ClusterStatus.CONNECTED.equals(cluster.getStatus());
    }

    /**
     * 集群对象的 {@code status.startTime} 是 RFC3339；解析不了就退回 now（宁可晚判，不可误判）。
     *
     * <p>⚠️ 必须取对象上的时间：取 now 会把超时时钟重置到「发现它」的那一刻，
     * 于是一个已经跑了 40 分钟的运行还要再等一整个 timeout 才会被判超时。
     */
    static Instant startedAtOf(PipelineRunDTO run) {
        String raw = run.getStartTime();
        if (raw == null || raw.isBlank()) {
            return Instant.now();
        }
        try {
            return OffsetDateTime.parse(raw).toInstant();
        } catch (Exception e) {
            log.debug("运行 {}/{} 的 startTime 无法解析：{}", run.getNamespace(), run.getName(), raw);
            return Instant.now();
        }
    }

    /**
     * 纯函数：这行落库够久了吗。
     *
     * <p>创建是「先落库、再建对象」两步，中间那一瞬集群里没有对象 —— 不设宽限期的话，
     * 一条正在创建的运行会被判成「对象已丢失」，而它随后照样会跑起来（库却已经 FAILED）。
     * 创建时间读不到时按「够久」处理：一直跳过等于永远不修。
     */
    static boolean settledEnough(Date createdAt, Instant now, Duration grace) {
        if (createdAt == null) {
            return true;
        }
        return !createdAt.toInstant().plus(grace).isAfter(now);
    }

    /** 后台动作没有租户身份 —— 与原来的启动扫描一样走 admin client。 */
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
