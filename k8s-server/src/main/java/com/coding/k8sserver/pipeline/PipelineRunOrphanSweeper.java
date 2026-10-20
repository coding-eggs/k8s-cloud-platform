package com.coding.k8sserver.pipeline;

import com.coding.common.models.k8s.ResourceType;
import com.coding.common.models.k8s.dto.PipelineRunDTO;
import com.coding.data.mapper.system.PlatformPipelineRunMapper;
import com.coding.data.models.system.PlatformPipelineRun;
import com.coding.k8score.factory.KubernetesOperationsFactory;
import com.coding.k8score.operations.NamespacedOperations;
import com.coding.k8score.operations.pipelinerun.PipelineRunOperations;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;

/**
 * 低频对账：**库里还说 RUNNING 的那些行，集群里到底还活着吗**。
 *
 * <p>⭐ 它补的是一个真实踩过的洞（2026-10-20）：运行中升级 Tekton，集群对象被带走。
 * 平台的三条既有链路都以「对象还在」为前提 ——
 * <ul>
 *   <li>{@link PipelineRunWatchManager}：对象没了不会再发终结事件；</li>
 *   <li>{@link PipelineRunStartupSweep}：只列集群里**还在**的对象；</li>
 *   <li>取消：对不存在的对象是 no-op，接口还回 200。</li>
 * </ul>
 * 于是那行永远停在 RUNNING：删除按钮不出现，后端也拒删。这里从**库**出发反查集群，
 * 把「对象没了」「对象终结了但没归档」「对象还在跑但没人盯」三种情况各自归位。
 *
 * <h2>为什么不是「轮询集群」</h2>
 * 它先查库（{@code status = 'RUNNING'}，常态 0~1 条），只有那些行才会去碰集群；
 * 台账正常时它一次集群调用都不发。间隔也远大于节拍器（默认 5 分钟），
 * 与 spec 里「节拍器只看台账、不打集群」的取舍不冲突。
 *
 * <h2>三道防误判</h2>
 * <ol>
 *   <li><b>创建宽限期</b>：创建是「先落库、后建对象」两步，中间那一瞬没有对象 ——
 *       刚落的行先放过（默认 2 分钟），否则会把一条正在创建的运行标成失败。</li>
 *   <li><b>已终结 ≠ 已归档</b>：对象终结了但库还是 RUNNING 时，**直接补归档**，
 *       不能只补 watch —— watch 不补发已经发生的事件。</li>
 *   <li><b>单行失败不外溢</b>：一个集群连不上不能把其余行带走（与启动扫描、工作区回收同形）。</li>
 * </ol>
 */
@Slf4j
@Component
public class PipelineRunOrphanSweeper {

    private final PlatformPipelineRunMapper runMapper;
    private final KubernetesOperationsFactory operationsFactory;
    private final PipelineRunWatchManager watch;
    private final PipelineRunFinisher finisher;
    private final PipelineArchiveService archive;
    private final Duration grace;
    private final Boolean enabled;

    public PipelineRunOrphanSweeper(PlatformPipelineRunMapper runMapper,
                                    KubernetesOperationsFactory operationsFactory,
                                    PipelineRunWatchManager watch,
                                    PipelineRunFinisher finisher,
                                    PipelineArchiveService archive,
                                    @Value("${platform.pipeline.orphan-check.grace:2m}") Duration grace,
                                    @Value("${platform.pipeline.orphan-check.enabled:true}") Boolean enabled) {
        this.runMapper = runMapper;
        this.operationsFactory = operationsFactory;
        this.watch = watch;
        this.finisher = finisher;
        this.archive = archive;
        this.grace = grace;
        this.enabled = enabled;
    }

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
        // 还在跑、但台账里没有（k8s-server 重启过 / watch 开失败）→ 补开。
        // startedAt 取对象上的 startTime，不是 now —— 否则超时时钟会被重置
        log.info("对账：运行还在跑但台账里没有，补开 watch {}/{}", namespace, runName);
        watch.watch(clusterId, namespace, runName, PipelineRunStartupSweep.startedAtOf(object));
    }

    private boolean isTracked(String clusterId, String namespace, String runName) {
        String key = clusterId + "/" + namespace + "/" + runName;
        return watch.snapshot().containsKey(key) || watch.pending().contains(key);
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

    /** 后台动作没有租户身份 —— 与启动扫描一样走 admin client。 */
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
