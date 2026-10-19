package com.coding.platformapi.services;

import com.coding.common.models.k8s.dto.PrometheusRuleDTO;
import com.coding.data.models.k8s.AlertSource;
import com.coding.data.models.k8s.PlatformAlertEvent;
import com.coding.platformapi.k8s.K8sClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 「这条告警是哪个 PrometheusRule 的哪个组定义的」—— 反查的唯一实现。
 *
 * <p>Alertmanager 的 payload 里**没有 CR 名和组名**，所以只能拿 {@code (namespace, alertname)}
 * 去 K8s 对象里找。两处消费它：告警列表的「来源」列，与投递引擎按 CR 名找通知绑定。
 *
 * <p>反查覆盖所有规则（Helm 装的、kubectl 建的都行），代价是规则改名/删除之后历史告警查不到
 * —— 调用方一律降级，不抛。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RuleSourceResolver {

    private final K8sClient k8s;

    /**
     * 批量反查。key = {@link #key(String, String)}，value = 去重后的来源列表。
     *
     * <p><b>返回 {@code null} = 没做成反查</b>（没有 clusterId / 拉规则失败）——
     * 与「查了但没有匹配」的**空 map** 区分开：前者调用方应把「来源」留成 null，
     * 后者才是空数组。这个区分是 API 契约的一部分（前端据此显示「—」的不同含义），
     * 别为了少一个 null 判断把它抹掉。
     */
    public Map<String, List<AlertSource>> resolve(String clusterId, String tenantId,
                                                  List<PlatformAlertEvent> rows) {
        if (rows == null || rows.isEmpty() || !StringUtils.hasText(clusterId)) {
            return null;
        }
        List<PrometheusRuleDTO> rules;
        try {
            rules = loadRules(rows, clusterId, tenantId);
        } catch (Exception e) {
            log.warn("反查告警来源失败（cluster={}）: {}", clusterId, e.getMessage());
            return null;
        }

        // (namespace, alertname) → 来源；同一 CR/组可能重复（chart 里有同名规则写两次），去重
        Map<String, LinkedHashSet<AlertSource>> index = new HashMap<>();
        for (PrometheusRuleDTO cr : rules) {
            if (cr.getGroups() == null) {
                continue;
            }
            for (PrometheusRuleDTO.Group g : cr.getGroups()) {
                if (g.getRules() == null) {
                    continue;
                }
                for (PrometheusRuleDTO.Rule r : g.getRules()) {
                    if (!StringUtils.hasText(r.getAlert())) {
                        continue;                       // 只有 alert 规则会产生告警
                    }
                    index.computeIfAbsent(key(cr.getNamespace(), r.getAlert()),
                                    k -> new LinkedHashSet<>())
                            .add(new AlertSource(cr.getName(), g.getName()));
                }
            }
        }

        Map<String, List<AlertSource>> out = new HashMap<>();
        index.forEach((k, v) -> out.put(k, List.copyOf(v)));
        return out;
    }

    /** 单条查询：这条告警来自哪个 CR（找不到 / 反查失败都返回 null）。投递引擎用它找通知绑定。 */
    public String crNameOf(String clusterId, String tenantId, PlatformAlertEvent e) {
        if (e == null) return null;
        Map<String, List<AlertSource>> index = resolve(clusterId, tenantId, List.of(e));
        if (index == null) return null;
        List<AlertSource> hits = index.get(key(e.getNamespace(), e.getAlertname()));
        return hits == null || hits.isEmpty() ? null : hits.get(0).cr();
    }

    /**
     * 索引键：{@code namespace + '\0' + alertname}。
     * 用 {@code \0} 而不是 {@code "|"} —— 两者都可能出现在告警名里，分隔符撞了就串行。
     */
    public static String key(String namespace, String alertname) {
        return (namespace == null ? "" : namespace) + "\u0000" + (alertname == null ? "" : alertname);
    }

    /** 拉规则：租户按结果里出现的 namespace 逐个 list；平台管理员一次 listAll。 */
    private List<PrometheusRuleDTO> loadRules(List<PlatformAlertEvent> rows, String clusterId, String tenantId) {
        if (StringUtils.hasText(tenantId)) {
            List<PrometheusRuleDTO> out = new ArrayList<>();
            List<String> namespaces = rows.stream()
                    .map(PlatformAlertEvent::getNamespace)
                    .filter(StringUtils::hasText)
                    .distinct()
                    .toList();
            for (String ns : namespaces) {
                PrometheusRuleDTO q = new PrometheusRuleDTO();
                q.setClusterId(clusterId);
                q.setTenantId(tenantId);
                q.setNamespace(ns);
                out.addAll(k8s.list(q));
            }
            return out;
        }
        PrometheusRuleDTO q = new PrometheusRuleDTO();
        q.setClusterId(clusterId);
        return k8s.listAll(q);
    }
}
