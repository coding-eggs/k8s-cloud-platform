package com.coding.k8sserver.pipeline;

import com.coding.common.models.k8s.ResourceType;
import com.coding.common.models.k8s.dto.PipelineRunDTO;
import com.coding.data.mapper.k8s.K8sClusterMapper;
import com.coding.data.models.k8s.K8sCluster;
import com.coding.k8score.factory.KubernetesOperationsFactory;
import com.coding.k8score.operations.NamespacedOperations;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 启动时扫一次：集群里还有**带平台标签且还在 running** 的 run 吗？有就补开 watch。
 *
 * <p>补的是「k8s-server 重启时正好有运行在跑」这个洞（spec §4.1.2）。
 * <b>这是初始化，不是轮询</b> —— 它只在启动时发生一次。
 *
 * <p>⚠️ 三点与直觉不同的地方：
 * <ul>
 *   <li><b>要逐个集群扫</b> —— `listAll` 是「某个集群里跨全部命名空间」，不是「全部集群」。
 *       集群清单来自平台库（{@code K8sClusterMapper}），与请求路径用的是同一份。</li>
 *   <li><b>用 admin client</b> —— 启动扫描没有租户身份；租户 SA 也没有集群范围的 list 权限。</li>
 *   <li><b>startedAt 取集群对象上的 startTime</b>，不是 {@code Instant.now()}。
 *       取 now 会把超时时钟重置到启动那一刻，于是一个已经跑了 40 分钟的运行还要再等一整个
 *       {@code timeout} 才会被判定超时 —— 那不是「跑太久了」的语义。</li>
 * </ul>
 */
@Slf4j
@Component
public class PipelineRunStartupSweep {

    private final KubernetesOperationsFactory operationsFactory;
    private final K8sClusterMapper clusterMapper;
    private final PipelineRunWatchManager watch;

    public PipelineRunStartupSweep(KubernetesOperationsFactory operationsFactory,
                                   K8sClusterMapper clusterMapper,
                                   PipelineRunWatchManager watch) {
        this.operationsFactory = operationsFactory;
        this.clusterMapper = clusterMapper;
        this.watch = watch;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void sweep() {
        List<K8sCluster> clusters;
        try {
            clusters = clusterMapper.listAll();
        } catch (Exception e) {
            log.warn("流水线运行启动扫描：取集群清单失败（不影响启动）", e);
            return;
        }
        for (K8sCluster cluster : clusters) {
            // ⚠️ 逐个集群单独兜异常 —— 一个集群连不上不能把其余集群的补盯一起带走
            try {
                sweepOne(cluster.getClusterId());
            } catch (Exception e) {
                log.warn("流水线运行启动扫描失败：cluster={}（不影响启动）", cluster.getClusterId(), e);
            }
        }
    }

    private void sweepOne(String clusterId) {
        NamespacedOperations<PipelineRunDTO> ops =
                operationsFactory.getAdminNamespacedOperation(ResourceType.PIPELINE_RUN, clusterId);
        // listAll 已在 PipelineRunOperations 里覆写，带平台标签过滤 —— 别人的 run 扫不到
        for (PipelineRunDTO run : ops.listAll(null, null)) {
            if (!"RUNNING".equals(run.getStatus())) {
                continue;
            }
            log.info("启动扫描：补盯一个还在跑的运行 cluster={} {}/{}",
                    clusterId, run.getNamespace(), run.getName());
            watch.recordOpened(clusterId, run.getNamespace(), run.getName(), startedAtOf(run));
        }
    }

    /** 集群对象的 {@code status.startTime} 是 RFC3339；解析不了就退回 now（宁可晚判，不可误判）。 */
    private static Instant startedAtOf(PipelineRunDTO run) {
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
}
