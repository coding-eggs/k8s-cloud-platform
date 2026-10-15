package com.coding.k8sserver.pipeline;

import com.coding.common.models.k8s.ResourceType;
import com.coding.common.models.k8s.dto.ConfigMapDTO;
import com.coding.common.models.pipeline.TektonSchedulingDTO;
import com.coding.k8score.factory.KubernetesOperationsFactory;
import com.coding.k8score.operations.NamespacedOperations;
import com.coding.k8score.pipeline.TektonScheduling;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 探这个集群的 Tekton 调度配置（{@code feature-flags} 的 {@code coschedule}），**进程内缓存**。
 *
 * <h2>⚠️ 为什么必须用 admin 凭据</h2>
 * 这份配置在 {@code tekton-pipelines} 命名空间，而**租户 SA 读不到**（也不该为一次探测去开，
 * 那是给所有租户扩权）。所以读取走 {@code getAdminNamespacedOperation} ——
 * 与 StorageClass 窄投影、PV、节点 IP 那三条同形。
 *
 * <h2>⚠️ 为什么缓存</h2>
 * 事实变得很慢（管理员改集群配置才变），而**创建运行的路径每条运行都要问它一次** ——
 * 不缓存等于给每条运行白加一次集群读。60s 足够：真改了配置，最多一分钟后就跟上。
 */
@Slf4j
@Component
public class TektonSchedulingProbe {

    private static final long TTL_MS = 60_000;

    private final KubernetesOperationsFactory operationsFactory;
    private final Map<String, Entry> cache = new ConcurrentHashMap<>();

    private record Entry(TektonSchedulingDTO value, long at) {
    }

    public TektonSchedulingProbe(KubernetesOperationsFactory operationsFactory) {
        this.operationsFactory = operationsFactory;
    }

    public TektonSchedulingDTO of(String clusterId) {
        long now = System.currentTimeMillis();
        Entry hit = cache.get(clusterId);
        if (hit != null && now - hit.at() < TTL_MS) {
            return hit.value();
        }
        TektonSchedulingDTO value = read(clusterId);
        cache.put(clusterId, new Entry(value, now));
        return value;
    }

    /** {@code coschedule=pipelineruns} 才为 true —— 「构建缓存能不能挂 PVC」的判据。 */
    public boolean allowsMultiplePvcWorkspaces(String clusterId) {
        return of(clusterId).multiPvcWorkspacesAllowed();
    }

    private TektonSchedulingDTO read(String clusterId) {
        Map<String, String> data = Map.of();
        try {
            NamespacedOperations<ConfigMapDTO> ops =
                    operationsFactory.getAdminNamespacedOperation(ResourceType.CONFIGMAP, clusterId);
            ConfigMapDTO cm = ops.get(TektonScheduling.NAMESPACE, TektonScheduling.FEATURE_FLAGS);
            if (cm != null && cm.getData() != null) {
                data = cm.getData();
            }
        } catch (Exception e) {
            // 读不到 → 按 Tekton 的默认值（workspaces）＝ 不支持。
            // 这是有意的 fail-closed：退化掉缓存只是慢，而在不支持的集群上挂两块 PVC workspace
            // 会让**运行直接判失败**（taskrun.go 的 ValidateOnlyOnePVCIsUsed）
            log.warn("读 Tekton feature-flags 失败（按默认 coschedule 处理，构建缓存会退化）：cluster={}",
                    clusterId, e);
        }
        return new TektonSchedulingDTO(TektonScheduling.coscheduleOf(data),
                TektonScheduling.allowsMultiplePvcWorkspaces(data));
    }
}
