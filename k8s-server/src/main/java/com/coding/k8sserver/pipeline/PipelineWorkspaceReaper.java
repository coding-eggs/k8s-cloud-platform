package com.coding.k8sserver.pipeline;

import com.coding.common.models.k8s.ResourceType;
import com.coding.common.models.k8s.dto.PersistentVolumeClaimDTO;
import com.coding.data.mapper.k8s.K8sClusterMapper;
import com.coding.data.models.k8s.K8sCluster;
import com.coding.k8score.factory.KubernetesOperationsFactory;
import com.coding.k8score.operations.NamespacedOperations;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 保留中的工作区 PVC 的 TTL 回收。
 *
 * <p>只认**注解**、不查库：{@code retain-until} 在每次运行创建（含重试）时都会刷新，
 * 而平台侧的超时上限是 30 分钟、远小于 24 小时 —— 一块已过期的卷不可能还挂着活跃运行。
 * 这条推理要留着：以后会有人想在这里加一次 DB 查询。
 *
 * <p>⚠️ 只碰带 {@code platform.pipeline/role=workspace} 标签的卷（选择器 + 纯函数双重过滤）。
 */
@Slf4j
@Component
public class PipelineWorkspaceReaper {

    static final String RETAIN_UNTIL = "platform.pipeline/retain-until";
    private static final String ROLE_SELECTOR = "platform.pipeline/role=workspace";

    private final KubernetesOperationsFactory factory;
    private final K8sClusterMapper clusterMapper;

    public PipelineWorkspaceReaper(KubernetesOperationsFactory factory, K8sClusterMapper clusterMapper) {
        this.factory = factory;
        this.clusterMapper = clusterMapper;
    }

    @Scheduled(fixedDelayString = "${platform.pipeline.workspace.reap-interval:10m}")
    public void scheduledSweep() {
        sweep(Instant.now());
    }

    void sweep(Instant now) {
        List<K8sCluster> clusters;
        try {
            clusters = clusterMapper.listAll();
        } catch (Exception e) {
            log.warn("工作区回收：取集群清单失败（跳过这一拍）", e);
            return;
        }
        for (K8sCluster cluster : clusters) {
            // 一个集群连不上不能把其余集群带走（与启动扫描同形）
            try {
                reapOne(cluster.getClusterId(), now);
            } catch (Exception e) {
                log.warn("工作区回收失败：cluster={}", cluster.getClusterId(), e);
            }
        }
    }

    private void reapOne(String clusterId, Instant now) {
        NamespacedOperations<PersistentVolumeClaimDTO> ops =
                factory.getAdminNamespacedOperation(ResourceType.PERSISTENT_VOLUME_CLAIM, clusterId);
        List<PersistentVolumeClaimDTO> pvcs = ops.listAll(ROLE_SELECTOR, null);
        for (PersistentVolumeClaimDTO pvc : expired(now, pvcs)) {
            try {
                ops.delete(pvc.getNamespace(), pvc.getName());
                log.info("工作区已过期，删除：{}/{}", pvc.getNamespace(), pvc.getName());
            } catch (Exception e) {
                log.warn("删过期工作区失败（下一拍再试）：{}/{}", pvc.getNamespace(), pvc.getName(), e);
            }
        }
    }

    /** 纯函数：哪些卷已经过了保留期。看不懂的（缺注解/解析失败）一律留着。 */
    static List<PersistentVolumeClaimDTO> expired(Instant now, List<PersistentVolumeClaimDTO> pvcs) {
        if (pvcs == null || pvcs.isEmpty()) {
            return List.of();
        }
        List<PersistentVolumeClaimDTO> out = new ArrayList<>();
        for (PersistentVolumeClaimDTO pvc : pvcs) {
            Object raw = pvc.getAnnotations() == null ? null : pvc.getAnnotations().get(RETAIN_UNTIL);
            if (raw == null) {
                continue;
            }
            try {
                if (Instant.parse(String.valueOf(raw)).isBefore(now)) {
                    out.add(pvc);
                }
            } catch (Exception ignore) {
                // 解析不了的时间不是「过期」，是「不知道」——不删
            }
        }
        return out;
    }
}
