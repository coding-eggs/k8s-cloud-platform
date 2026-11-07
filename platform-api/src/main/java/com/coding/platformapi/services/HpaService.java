package com.coding.platformapi.services;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.k8s.dto.HpaDTO;
import com.coding.common.models.k8s.dto.WorkloadDTO;
import com.coding.common.models.k8s.dto.HpaCrossVersionObjectReferenceDTO;
import com.coding.platformapi.k8s.K8sClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.coding.common.models.k8s.dto.HpaMetricSpecDTO;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * HPA 业务层：create/update 前做「一负载一 HPA」硬校验（条目 3 后端半；前端禁选是 UX 半，双保险）；
 * list/get/yaml/delete 无业务规则，纯组装 DTO 后透传。
 * scaleTargetRef.namespace 不经 converter round-trip，比较只按 kind+name（HPA 自身 ns 内，list 已按 ns 圈定）。
 * 分层约定见 docs/development/backend-layering.md。
 */
@Service
@RequiredArgsConstructor
public class HpaService {

    private final K8sClient k8s;
    /** 资源指标（CPU/内存）看组件旗标 hpa-resource-metrics（metrics-server） */
    private final ComponentFeatureGuard featureGuard;
    /** custom / external 指标没有对应组件，仍按 capability 里的 API group 判 */
    private final ClusterCapabilityService capabilityService;

    static final String METRICS_RESOURCE = "resource";
    static final String METRICS_CUSTOM = "custom";
    static final String METRICS_EXTERNAL = "external";

    public List<HpaDTO> list(HpaDTO query) {
        return k8s.list(query);
    }

    public HpaDTO get(String name, String tenantId, String clusterId, String namespace) {
        return k8s.get(dto(name, tenantId, clusterId, namespace));
    }

    public String yaml(String name, String tenantId, String clusterId, String namespace) {
        return k8s.yaml(dto(name, tenantId, clusterId, namespace));
    }

    public void delete(String name, String tenantId, String clusterId, String namespace) {
        k8s.delete(dto(name, tenantId, clusterId, namespace));
    }

    public HpaDTO create(HpaDTO body) {
        validateSingleBinding(body, null);
        assertTargetNotManaged(body);
        requireMetricsSources(body, Set.of());
        return k8s.create(body);
    }

    public HpaDTO update(HpaDTO body) {
        validateSingleBinding(body, body.getName());
        assertTargetNotManaged(body);
        // 已在用的指标来源不追溯：只拦这次**新加**的来源（组件卸了也得能改 min/max 或删掉指标）
        HpaDTO existing = k8s.get(dto(body.getName(), body.getTenantId(), body.getClusterId(), body.getNamespace()));
        requireMetricsSources(body, existing == null ? Set.of() : metricsSourcesOf(existing));
        return k8s.update(body);
    }

    /**
     * 每类指标来源要有对应的集群能力，否则 HPA 建出来永远拿不到指标（status 里一行 FailedGetResourceMetric）。
     * 与前端 HpaEditorView 的 TYPE_GROUP 同一张映射。
     */
    private void requireMetricsSources(HpaDTO body, Set<String> alreadyUsed) {
        Set<String> added = new LinkedHashSet<>(metricsSourcesOf(body));
        added.removeAll(alreadyUsed);
        if (added.contains(METRICS_RESOURCE)) {
            featureGuard.require(body.getClusterId(), ComponentFeatureGuard.HPA_RESOURCE_METRICS, "按 CPU/内存指标伸缩");
        }
        requireGroup(body.getClusterId(), added, METRICS_CUSTOM, "custom.metrics.k8s.io", "Pods/Object 自定义指标");
        requireGroup(body.getClusterId(), added, METRICS_EXTERNAL, "external.metrics.k8s.io", "External 外部指标");
    }

    private void requireGroup(String clusterId, Set<String> added, String source, String group, String label) {
        if (added.contains(source) && capabilityService.versionsOf(clusterId, group).isEmpty()) {
            throw new CloudPlatformException(EnumResponseType.OPERATION_NOT_SUPPORTED,
                    "无法按" + label + "伸缩：该集群未探测到 " + group + "（需要安装对应的 metrics adapter）");
        }
    }

    /** 指标类型 → 来源。空列表 = K8s 默认 CPU 80%，即资源指标 */
    static Set<String> metricsSourcesOf(HpaDTO hpa) {
        List<HpaMetricSpecDTO> metrics = hpa.getMetrics();
        if (metrics == null || metrics.isEmpty()) {
            return Set.of(METRICS_RESOURCE);
        }
        Set<String> out = new LinkedHashSet<>();
        for (HpaMetricSpecDTO m : metrics) {
            String t = m.getType() == null ? "" : m.getType();
            switch (t) {
                case "Resource", "ContainerResource" -> out.add(METRICS_RESOURCE);
                case "Pods", "Object" -> out.add(METRICS_CUSTOM);
                case "External" -> out.add(METRICS_EXTERNAL);
                default -> { /* 未知类型交给 apiserver 判 */ }
            }
        }
        return out;
    }

    /** HPA 是"另一个对象"，但把受管工作负载的副本数交给 HPA 等于绕开伸缩禁令 —— 目标受管就拒。 */
    private void assertTargetNotManaged(HpaDTO body) {
        var ref = body.getScaleTargetRef();
        if (ref == null || !StringUtils.hasText(ref.getName()) || !StringUtils.hasText(ref.getKind())) {
            return;
        }
        if (!java.util.Set.of("Deployment", "StatefulSet", "DaemonSet", "ReplicaSet").contains(ref.getKind())) {
            return;
        }
        WorkloadDTO q = new WorkloadDTO();
        q.setName(ref.getName());
        q.setTenantId(body.getTenantId());
        q.setClusterId(body.getClusterId());
        q.setNamespace(body.getNamespace());
        WorkloadDTO target;
        try {
            target = k8s.get(q);
        } catch (RuntimeException e) {
            return;   // 读不到（kind 不匹配/已删除）就交给 apiserver 判定
        }
        if (target != null && target.getManagedBy() != null) {
            throw new CloudPlatformException(EnumResponseType.ERROR,
                    "该工作负载由平台组件或中间件实例管理，不能为它创建/修改 HPA");
        }
    }

    /**查询 DTO：apiPath 内置于 DTO */
    private HpaDTO dto(String name, String tenantId, String clusterId, String namespace) {
        HpaDTO d = new HpaDTO();
        d.setName(name);
        d.setTenantId(tenantId);
        d.setClusterId(clusterId);
        d.setNamespace(namespace);
        return d;
    }

    /** 目标 ref 缺失 → 放行（真非法由 apiserver 拒；本校验只管重复绑定） */
    private void validateSingleBinding(HpaDTO body, String excludeName) {
        HpaCrossVersionObjectReferenceDTO ref = body.getScaleTargetRef();
        if (ref == null || !StringUtils.hasText(ref.getName())) {
            return;
        }
        HpaDTO q = new HpaDTO();
        q.setTenantId(body.getTenantId());
        q.setClusterId(body.getClusterId());
        q.setNamespace(body.getNamespace());
        for (HpaDTO existing : k8s.list(q)) {
            HpaCrossVersionObjectReferenceDTO er = existing.getScaleTargetRef();
            if (er == null || !ref.getName().equals(er.getName())) {
                continue;
            }
            if (ref.getKind() == null ? er.getKind() == null : ref.getKind().equalsIgnoreCase(er.getKind())) {
                if (excludeName == null || !excludeName.equals(existing.getName())) {
                    throw new CloudPlatformException(EnumResponseType.HPA_TARGET_ALREADY_BOUND,
                            "工作负载「" + ref.getKind() + "/" + ref.getName() + "」已绑定 HPA「" + existing.getName()
                                    + "」，一个工作负载只能绑定一个 HPA");
                }
            }
        }
    }
}
