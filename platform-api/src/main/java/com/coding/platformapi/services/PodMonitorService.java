package com.coding.platformapi.services;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.k8s.dto.PodDTO;
import com.coding.common.models.k8s.dto.PodMonitorDTO;
import com.coding.data.mapper.k8s.K8sClusterMapper;
import com.coding.data.models.k8s.K8sCluster;
import com.coding.platformapi.k8s.K8sResourceClient;
import com.coding.platformapi.metrics.PromDiscoveryClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * PodMonitor 业务层：create/update 先解析目标 Pod 绑定（podRef → matchLabels），通过再透传 k8s-server。
 * list/get/yaml/delete 无业务规则，controller 直连 client。
 * 另提供 {@link #relabelLabels} —— 编辑态从集群 Prometheus discovery 拉取 Relabeling/MetricRelabeling 的 sourceLabels 候选；
 * {@link #metricNames} —— 编辑态 scoped 到本 PM 活跃 target 的 {@code __name__}（指标名）候选，供 MetricRelabeling regex 选值。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PodMonitorService {

    private final K8sResourceClient k8s;
    private final K8sClusterMapper clusterMapper;
    private final PromDiscoveryClient promDiscovery;

    public PodMonitorDTO create(PodMonitorDTO body) {
        resolveSelector(body);
        return k8s.create(body);
    }

    public PodMonitorDTO update(PodMonitorDTO body) {
        resolveSelector(body);
        return k8s.update(body);
    }

    /**
     * 解析目标 Pod 绑定：有 podRef（name + namespace）→ 反查该 Pod 的 labels 覆盖 body.matchLabels；
     * 无 podRef → matchLabels 原样保留（编辑回填 / 未改动）。podRef 为平台侧字段，解析后剥离、绝不下发 k8s-server / 不落库。
     * 语义：选择器反映「创建时所选 Pod 的 labels」——同一工作负载的 Pod 共享模板 labels，故通常可泛化到该工作负载全部 Pod。
     */
    private void resolveSelector(PodMonitorDTO body) {
        PodMonitorDTO.PodRef ref = body.getPodRef();
        body.setPodRef(null); // 无论是否解析都剥离
        if (ref == null || !StringUtils.hasText(ref.getName())) {
            return;
        }
        body.setMatchLabels(resolveFromPod(body, ref.getName(), ref.getNamespace()));
    }

    /** 绑定 Pod：取该 Pod 的 metadata.labels 作为选择器；无 labels 则无法生成 */
    private Map<String, String> resolveFromPod(PodMonitorDTO ctx, String name, String namespace) {
        PodDTO pq = new PodDTO();
        pq.setTenantId(ctx.getTenantId());
        pq.setClusterId(ctx.getClusterId());
        pq.setNamespace(StringUtils.hasText(namespace) ? namespace : ctx.getNamespace());
        pq.setName(name);
        PodDTO pod = k8s.get(pq);
        if (pod == null) {
            throw new CloudPlatformException(EnumResponseType.RESOURCE_NOT_EXIST, "Pod「" + name + "」不存在");
        }
        Map<String, String> labels = pod.getLabels();
        if (labels == null || labels.isEmpty()) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "Pod「" + name + "」无 labels，无法生成选择器");
        }
        return new LinkedHashMap<>(labels);
    }

    /**
     * Relabeling / MetricRelabeling 的 sourceLabels 候选（编辑态）。返回两组：
     * <ul>
     *   <li>{@code relabeling}：服务发现原始标签名（discoveredLabels，__meta_* / __address__ …）—— Relabeling(relabel_configs) 作用于它。</li>
     *   <li>{@code metricRelabeling}：最终目标标签名（labels）—— MetricRelabeling(metric_relabel_configs) 作用于它 + __name__（前端补）。</li>
     * </ul>
     * 流程：按 clusterId 取 prometheusUrl（空→返回空组）→ scrape_pools 里筛 {@code podMonitor/{namespace}/{name}/} 前缀的 pool（多端点全取）→ 各 pool 标签并集、排序。
     * Prometheus 不可达/异常一律降级为空组（建议下拉不因拉取失败而报错），仅记 warn。
     */
    public Map<String, List<String>> relabelLabels(String clusterId, String namespace, String name) {
        if (!StringUtils.hasText(clusterId) || !StringUtils.hasText(name)) {
            return emptyLabelSets();
        }
        K8sCluster cluster = clusterMapper.selectByPrimaryKey(clusterId);
        if (cluster == null || !StringUtils.hasText(cluster.getPrometheusUrl())) {
            return emptyLabelSets();
        }
        String base = cluster.getPrometheusUrl().replaceAll("/+$", "");
        try {
            List<String> pools = promDiscovery.scrapePools(base);
            String prefix = "podMonitor/" + namespace + "/" + name + "/";
            Set<String> discovered = new LinkedHashSet<>();
            Set<String> relabeled = new LinkedHashSet<>();
            for (String p : pools) {
                if (p != null && p.startsWith(prefix)) {
                    PromDiscoveryClient.TargetLabelSets sets = promDiscovery.labelSets(base, p);
                    discovered.addAll(sets.discovered());
                    relabeled.addAll(sets.relabeled());
                }
            }
            Map<String, List<String>> out = new LinkedHashMap<>();
            out.put("relabeling", discovered.stream().sorted().collect(Collectors.toList()));
            out.put("metricRelabeling", relabeled.stream().sorted().collect(Collectors.toList()));
            return out;
        } catch (Exception e) {
            log.warn("拉取 PodMonitor 标签候选失败（cluster={} pm={}/{}）: {}", clusterId, namespace, name, e.getMessage());
            return emptyLabelSets();
        }
    }

    private Map<String, List<String>> emptyLabelSets() {
        Map<String, List<String>> m = new LinkedHashMap<>();
        m.put("relabeling", List.of());
        m.put("metricRelabeling", List.of());
        return m;
    }

    /**
     * MetricRelabeling 的 {@code __name__}（指标名）候选（编辑态）。scoped 到本 PM 的活跃 target：
     * scrape_pools 筛 {@code podMonitor/{namespace}/{name}/} 前缀 → 各 pool 取 (job, instance) →
     * 拼精确 match[] selector（{@code {job="J",instance="I"}}）→ /api/v1/label/__name__/values?match[]=… 收窄到本 PM 的真实端点，避免串到别家指标。
     * 无活跃 target / Prometheus 不可达 → 返回空（不做全局回退），仅记 warn。
     */
    public List<String> metricNames(String clusterId, String namespace, String name) {
        if (!StringUtils.hasText(clusterId) || !StringUtils.hasText(name)) {
            return List.of();
        }
        K8sCluster cluster = clusterMapper.selectByPrimaryKey(clusterId);
        if (cluster == null || !StringUtils.hasText(cluster.getPrometheusUrl())) {
            return List.of();
        }
        String base = cluster.getPrometheusUrl().replaceAll("/+$", "");
        try {
            List<String> pools = promDiscovery.scrapePools(base);
            String prefix = "podMonitor/" + namespace + "/" + name + "/";
            Set<PromDiscoveryClient.TargetRef> refs = new LinkedHashSet<>();
            for (String p : pools) {
                if (p != null && p.startsWith(prefix)) {
                    refs.addAll(promDiscovery.targetRefs(base, p));
                }
            }
            if (refs.isEmpty()) {
                return List.of();
            }
            List<String> matchers = new ArrayList<>();
            for (PromDiscoveryClient.TargetRef r : refs) {
                matchers.add(toMatcher(r));
            }
            return promDiscovery.metricNames(base, matchers).stream().sorted().collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("拉取 PodMonitor 指标名候选失败（cluster={} pm={}/{}）: {}", clusterId, namespace, name, e.getMessage());
            return List.of();
        }
    }

    /** TargetRef → 精确 match[] selector：{@code {job="J",instance="I"}}（值做 label-value 转义）。 */
    private String toMatcher(PromDiscoveryClient.TargetRef r) {
        StringBuilder sb = new StringBuilder("{");
        if (StringUtils.hasText(r.job())) {
            sb.append("job=\"").append(escape(r.job())).append("\",");
        }
        sb.append("instance=\"").append(escape(r.instance())).append("\"}");
        return sb.toString();
    }

    /** Prometheus label value 转义：反斜杠 / 双引号 / 换行。 */
    private String escape(String v) {
        return v.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

}
