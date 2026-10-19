package com.coding.platformapi.notify;

import com.coding.data.mapper.auth.PlatformTenantMapper;
import com.coding.data.mapper.auth.PlatformTenantNamespaceMapper;
import com.coding.data.mapper.k8s.K8sClusterMapper;
import com.coding.data.models.auth.PlatformTenant;
import com.coding.data.models.auth.PlatformTenantNamespace;
import com.coding.data.models.k8s.K8sCluster;
import com.coding.data.models.k8s.PlatformAlertEvent;
import com.coding.platformapi.metrics.PromRulesClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 算出「这条告警属于谁、规则长什么样」—— 告警列表的归属列与通知正文共用同一份口径。
 *
 * <p>两个入口：
 * <ul>
 *   <li>{@link #ownership} —— 只要集群名 + 租户（列表/详情用，零外部调用）；</li>
 *   <li>{@link #build} —— 再加 PromQL 表达式（通知正文用，会打一次 Prometheus）。</li>
 * </ul>
 * 两处都**永不抛**：拿不到就留空，页面/邮件少一行，而不是整条链路 500。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AlertNotifyContextService {

    private final K8sClusterMapper clusters;
    private final PlatformTenantMapper tenants;
    private final PlatformTenantNamespaceMapper allocs;
    private final PromRulesClient promRules;

    /** 只算归属（集群名 + 租户 id/名）。命名空间 → 租户的映射**只查一次**分配表。 */
    public NotifyContext ownership(String clusterId, List<PlatformAlertEvent> events) {
        if (events == null || events.isEmpty()) {
            return NotifyContext.EMPTY;
        }
        String clusterName = null;
        try {
            K8sCluster cluster = clusters.selectByPrimaryKey(clusterId);
            if (cluster != null) {
                clusterName = cluster.getClusterName();
            }
        } catch (Exception e) {
            log.warn("读集群信息失败（cluster={}）: {}", clusterId, e.getMessage());
        }

        Map<String, String> nsToTenant = new HashMap<>();
        try {
            List<PlatformTenantNamespace> rows = allocs.listByCluster(clusterId);
            if (rows != null) {
                for (PlatformTenantNamespace r : rows) {
                    if (StringUtils.hasText(r.getNamespace()) && StringUtils.hasText(r.getTenantId())) {
                        nsToTenant.putIfAbsent(r.getNamespace(), r.getTenantId());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("读命名空间分配失败（cluster={}）: {}", clusterId, e.getMessage());
        }

        Map<Long, String> tenantIds = new HashMap<>();
        Map<Long, String> tenantNames = new HashMap<>();
        Map<String, String> nameCache = new HashMap<>();
        for (PlatformAlertEvent e : events) {
            if (e == null || e.getId() == null || !StringUtils.hasText(e.getNamespace())) {
                continue;
            }
            String tenantId = nsToTenant.get(e.getNamespace());
            if (tenantId == null) {
                continue;                      // 没分配给任何租户：不编一个出来
            }
            tenantIds.put(e.getId(), tenantId);
            tenantNames.put(e.getId(), nameCache.computeIfAbsent(tenantId, this::tenantName));
        }
        return new NotifyContext(clusterName, tenantIds, tenantNames, Map.of());
    }

    /** 在 {@link #ownership} 之上补每条告警的 PromQL 表达式（一次 Prometheus 调用覆盖整批）。 */
    public NotifyContext build(String clusterId, List<PlatformAlertEvent> events) {
        NotifyContext base = ownership(clusterId, events);
        if (events == null || events.isEmpty()) {
            return base;
        }
        Map<String, String> byAlertname = expressions(clusterId);
        if (byAlertname.isEmpty()) {
            return base;
        }
        Map<Long, String> perEvent = new HashMap<>();
        for (PlatformAlertEvent e : events) {
            if (e == null || e.getId() == null || !StringUtils.hasText(e.getAlertname())) {
                continue;
            }
            String expr = byAlertname.get(e.getAlertname());
            if (expr != null) {
                perEvent.put(e.getId(), expr);
            }
        }
        return new NotifyContext(base.clusterName(), base.tenantIds(), base.tenantNames(), perEvent);
    }

    /** alertname → 表达式。集群没配 prometheusUrl 或查失败 → 空 map（正文少一行表达式）。 */
    private Map<String, String> expressions(String clusterId) {
        try {
            K8sCluster cluster = clusters.selectByPrimaryKey(clusterId);
            if (cluster == null || !StringUtils.hasText(cluster.getPrometheusUrl())) {
                return Map.of();
            }
            String base = cluster.getPrometheusUrl().replaceAll("/+$", "");
            return promRules.alertExpressions(base);
        } catch (Exception e) {
            log.warn("拉取规则表达式失败（cluster={}），通知里不写表达式: {}", clusterId, e.getMessage());
            return Map.of();
        }
    }

    /** 租户显示名；查不到就回 id（宁可难看，也不要在邮件里留空）。 */
    private String tenantName(String tenantId) {
        try {
            PlatformTenant t = tenants.selectByPrimaryKey(tenantId);
            if (t != null && StringUtils.hasText(t.getName())) {
                return t.getName();
            }
        } catch (Exception e) {
            log.warn("读租户信息失败（tenant={}）: {}", tenantId, e.getMessage());
        }
        return tenantId;
    }
}
