package com.coding.k8sserver.pipeline;

import com.coding.common.models.k8s.ResourceType;
import com.coding.common.models.k8s.dto.PipelineRunDTO;
import com.coding.common.models.pipeline.PipelineRunSnapshotDTO;
import com.coding.k8score.factory.KubernetesOperationsFactory;
import com.coding.k8score.operations.NamespacedOperations;
import com.coding.k8score.operations.pipelinerun.PipelineRunOperations;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * **一次运行终结时要做的全部事情**，只写在这一处：抓快照 → 落库 → 删对象
 * （删 PipelineRun 会级联带走 TaskRun 与 **Pod**）。
 *
 * <p>两条路径都走它：watch 收到终结事件、平台判超时。用户取消走 {@link #cancel}。
 *
 * <h2>⚠️ 顺序是它的全部价值</h2>
 * <b>落库成功了才删对象</b> —— 删了会把那份日志的唯一副本一起带走。
 * 落库失败就**不删**，由调用方记下来重试（对象与 Pod 都还在，日志也还在）。
 *
 * <p>它在 k8s-server 而不是 platform-api，是因为抓快照的人就是开 watch 的人；
 * 让 platform-api 反过来取，就得为「没有用户 token 的后台调用」另造一套机器身份 ——
 * 而那个身份问题在这一版里被整条消掉了（见 {@link PipelineArchiveService}）。
 */
@Slf4j
@Component
public class PipelineRunFinisher {

    private final KubernetesOperationsFactory operationsFactory;
    private final PipelineArchiveService archive;
    private final int maxLogBytes;

    public PipelineRunFinisher(KubernetesOperationsFactory operationsFactory,
                               PipelineArchiveService archive,
                               @Value("${platform.pipeline.log.max-bytes:1048576}") int maxLogBytes) {
        this.operationsFactory = operationsFactory;
        this.archive = archive;
        this.maxLogBytes = maxLogBytes;
    }

    /**
     * 归档一条终结了的运行。
     *
     * @param timedOut 平台判定超时而终结的。⚠️ Tekton 那边它**还在跑**（平台是「先抓日志、再取消」），
     *                 所以快照状态要在这里改写成 {@code TIMEOUT} —— 光看对象的 condition
     *                 分不出「用户取消」与「平台判超时」，而运行历史里两者必须不一样。
     * @return true = 全部做完（可以从台账里摘掉了）；false = 中途失败，**对象没删**，等重试
     */
    public boolean finish(String clusterId, String namespace, String runName, boolean timedOut) {
        PipelineRunSnapshotDTO snapshot;
        try {
            snapshot = runOps(clusterId).snapshot(clusterId, namespace, runName, maxLogBytes);
            if (snapshot != null && timedOut) {
                snapshot = new PipelineRunSnapshotDTO(snapshot.clusterId(), snapshot.namespace(),
                        snapshot.runName(), "TIMEOUT", snapshot.reason(),
                        snapshot.startTime(), snapshot.completionTime(), snapshot.steps(),
                        snapshot.runYaml());
            }
        } catch (Exception e) {
            log.warn("抓运行快照失败（对象先留着，等重试）{}/{}", namespace, runName, e);
            return false;
        }

        if (snapshot != null) {
            try {
                archive.persist(snapshot);              // ← 落库（幂等、事务）
            } catch (Exception e) {
                log.warn("归档落库失败（**对象不删**，等重试）{}/{}", namespace, runName, e);
                return false;
            }
        }
        // 落库成功（或本来就没这条运行）→ 才删。删本身幂等，重投再删一次也无妨
        deleteObject(clusterId, namespace, runName);
        return true;
    }

    /**
     * 取消一次运行：**先抓日志、再取消**（spec §4.2）。
     * Tekton 取消时会自己把 Pod 删掉，顺序反了那次运行的日志就没了。
     *
     * <p>与归档的区别很清楚：这里**只抓快照落库、不删对象** ——
     * 取消之后对象还在（历史不能丢），它由后续的终止事件走 {@link #finish} 归档掉。
     */
    public void cancel(String clusterId, String namespace, String runName) {
        try {
            PipelineRunSnapshotDTO snapshot = runOps(clusterId)
                    .snapshot(clusterId, namespace, runName, maxLogBytes);
            if (snapshot != null) {
                archive.persist(snapshot);
            }
        } catch (Exception e) {
            // 抓不到就照常取消 —— 用户点的是「取消」，不能因为抢抓失败而卡住；
            // 代价是那次运行的日志可能不全（spec §4.2 已写明这条取舍）
            log.warn("取消前抢抓日志失败（继续取消）{}/{}", namespace, runName, e);
        }
        runOps(clusterId).cancel(namespace, runName);
    }

    private void deleteObject(String clusterId, String namespace, String runName) {
        try {
            operationsFactory.getAdminNamespacedOperation(ResourceType.PIPELINE_RUN, clusterId)
                    .delete(namespace, runName);
        } catch (Exception e) {
            log.warn("删运行对象失败（下次重投再试）{}/{}", namespace, runName, e);
        }
    }

    /**
     * 拿某个集群的 PipelineRun 操作（admin —— 后台动作没有租户身份，租户 SA 也没有集群范围权限）。
     *
     * <p>用带兜底的 {@code instanceof} 而不是裸转型：{@code snapshot} / {@code cancel}
     * 之外的 Tekton 专有方法不适合塞进通用接口，所以这里留一道**响亮**的兜底。
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
