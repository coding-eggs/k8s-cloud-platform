package com.coding.platformapi.services;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.k8s.dto.PrometheusRuleDTO;
import com.coding.data.mapper.k8s.K8sClusterMapper;
import com.coding.data.models.k8s.K8sCluster;
import com.coding.platformapi.k8s.K8sClient;
import com.coding.platformapi.metrics.PromRulesClient;
import com.coding.platformapi.metrics.RuleRuntimeMatcher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * PrometheusRule 业务层。与 ServiceMonitorService 一样是透传 —— 只多**一条**本平台自己的规则。
 *
 * <p><b>代打 {@code labels.namespace}</b>：Alertmanager 推到平台的 payload 里没有
 * 「这条规则是谁建的」这个字段，{@code labels.namespace} 是唯一能带过来的归属线索，
 * 而它是**规则作者自己填的**、不可信。平台在下发时覆写它，是唯一能保证归属可信的写法
 * （见 docs/superpowers/specs/2026-10-17-alerting-design.md 决策 5）。
 *
 * <p>只打 {@code alert} 规则：{@code record} 规则不产生告警，给它打 namespace 只会污染指标标签。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PrometheusRuleService {

    private final K8sClient k8s;
    private final K8sClusterMapper clusterMapper;
    private final PromRulesClient promRules;
    private final RuleNotifyBinder notifyBinder;

    /** 规则刚建、Prometheus 还没 reload 的宽限期。覆盖 prometheus-operator 的 reload 延迟。 */
    private static final Duration LOAD_GRACE = Duration.ofMinutes(5);

    public List<PrometheusRuleDTO> list(PrometheusRuleDTO query) {
        List<PrometheusRuleDTO> out = k8s.list(query);
        enrich(out, query.getClusterId());
        return out;
    }

    public PrometheusRuleDTO get(String name, String tenantId, String clusterId, String namespace) {
        PrometheusRuleDTO out = k8s.get(dto(name, tenantId, clusterId, namespace));
        if (out != null) {
            notifyBinder.fill(out);          // 平台侧字段不在 CR 里，得从绑定表补回来
            enrich(List.of(out), clusterId);
        }
        return out;
    }

    public String yaml(String name, String tenantId, String clusterId, String namespace) {
        return k8s.yaml(dto(name, tenantId, clusterId, namespace));
    }

    public PrometheusRuleDTO create(PrometheusRuleDTO body) {
        stampNamespace(body);
        notifyBinder.bind(body);            // ⚠️ 必须在剥离之前：绑定要用 body 上的 notifyChannel
        stripPlatformFields(body);
        return k8s.create(body);
    }

    public PrometheusRuleDTO update(PrometheusRuleDTO body) {
        stampNamespace(body);
        notifyBinder.bind(body);
        stripPlatformFields(body);
        return k8s.update(body);
    }

    public void delete(String name, String tenantId, String clusterId, String namespace) {
        k8s.delete(dto(name, tenantId, clusterId, namespace));
    }

    /**
     * 附着运行状态。**整个 list 只打 Prometheus 一次**（一次响应可能有几百 KB，
     * 不能按规则条数打）。任何失败都降级成"不附着"，**绝不让它影响 CRUD**
     * —— 与 ServiceMonitorService 的 relabelLabels/metricNames 同一口径。
     *
     * <p>⚠️ {@code match} 的第二个参数逐条传单元素 list：契约是「一个 CR 一份 runtime」，
     * 而 missing 判定要看这个 CR 自己的 {@code creationTime} —— 传整个 list 会让一条老规则的
     * missing 连累新规则。
     */
    private void enrich(List<PrometheusRuleDTO> rules, String clusterId) {
        if (rules == null || rules.isEmpty() || !StringUtils.hasText(clusterId)) {
            return;
        }
        K8sCluster cluster = clusterMapper.selectByPrimaryKey(clusterId);
        if (cluster == null || !StringUtils.hasText(cluster.getPrometheusUrl())) {
            return;                                     // 没配就不探，与 SM/PM 同口径
        }
        String base = cluster.getPrometheusUrl().replaceAll("/+$", "");
        try {
            List<PromRulesClient.RuleGroup> groups = promRules.groups(base);
            // ⚠️ 歧义必须**先在整个列表上算一次**：下面逐条 match（宽限期要看各自的 creationTime），
            //    单条调用时 match 内部的守卫看不全候选集 —— 那种情况下两份同前缀的 CR 会各自认领同一个文件。
            java.util.Set<String> ambiguous = RuleRuntimeMatcher.ambiguousPrefixes(rules);
            for (PrometheusRuleDTO r : rules) {
                boolean isAmbiguous = r.getNamespace() != null && r.getName() != null
                        && ambiguous.contains(RuleRuntimeMatcher.filePrefix(r));
                r.setRuntime(isAmbiguous
                        ? RuleRuntimeMatcher.unknown()
                        : RuleRuntimeMatcher.match(groups, List.of(r), LOAD_GRACE));
            }
        } catch (Exception e) {
            log.warn("拉取 Prometheus 规则状态失败（cluster={}）: {}", clusterId, e.getMessage());
        }
    }

    /**
     * 内容校验 + 代打 namespace 标。
     * <p>校验放在这里而不是让 K8s API server 拒：后者的报错是
     * {@code must be specified} 这种，用户不知道该改哪儿。
     */
    private void stampNamespace(PrometheusRuleDTO body) {
        if (body.getGroups() == null || body.getGroups().isEmpty()) {
            throw new CloudPlatformException(EnumResponseType.PROMETHEUS_RULE_INVALID,
                    "至少需要一组规则（groups）。");
        }
        for (PrometheusRuleDTO.Group g : body.getGroups()) {
            if (!StringUtils.hasText(g.getName())) {
                throw new CloudPlatformException(EnumResponseType.PROMETHEUS_RULE_INVALID,
                        "每一组都要有组名（groups[].name）。");
            }
            if (g.getRules() == null || g.getRules().isEmpty()) {
                throw new CloudPlatformException(EnumResponseType.PROMETHEUS_RULE_INVALID,
                        "组「" + g.getName() + "」里没有规则。");
            }
            for (PrometheusRuleDTO.Rule r : g.getRules()) {
                boolean hasAlert = StringUtils.hasText(r.getAlert());
                boolean hasRecord = StringUtils.hasText(r.getRecord());
                if (hasAlert == hasRecord) {
                    throw new CloudPlatformException(EnumResponseType.PROMETHEUS_RULE_INVALID,
                            "组「" + g.getName() + "」里有一条规则 alert 与 record "
                                    + (hasAlert ? "同时给了 —— 只能给一个。" : "都没给 —— 必须给一个。"));
                }
                if (!StringUtils.hasText(r.getExpr())) {
                    throw new CloudPlatformException(EnumResponseType.PROMETHEUS_RULE_INVALID,
                            "组「" + g.getName() + "」里的规则「"
                                    + (hasAlert ? r.getAlert() : r.getRecord()) + "」没有表达式（expr）。");
                }
                if (hasAlert) {
                    // 以平台的为准：先复制用户那份，再覆写 namespace（不动其它标签）
                    Map<String, String> labels = r.getLabels() != null
                            ? new LinkedHashMap<>(r.getLabels()) : new LinkedHashMap<>();
                    labels.put("namespace", body.getNamespace());
                    // 通知方式：选了才写。没选**不能写空串** —— 空值会变成一个无意义的
                    // platform_notify="" 标签，投递端还得区分「没写」与「写了空」。
                    if (StringUtils.hasText(body.getNotifyChannel())) {
                        labels.put("platform_notify", body.getNotifyChannel());
                    }
                    r.setLabels(labels);
                }
            }
        }
    }

    /**
     * 平台侧字段**不下发**：它们是平台自己的簿记（绑定已在 {@code RuleNotifyBinder} 落表），
     * K8s 那边只该看到 {@code labels.platform_notify} 这一份冗余展示。
     */
    private void stripPlatformFields(PrometheusRuleDTO body) {
        body.setNotifyChannel(null);
        body.setNotifyUserIds(null);
    }

    private PrometheusRuleDTO dto(String name, String tenantId, String clusterId, String namespace) {
        PrometheusRuleDTO d = new PrometheusRuleDTO();
        d.setName(name);
        d.setTenantId(tenantId);
        d.setClusterId(clusterId);
        d.setNamespace(namespace);
        return d;
    }

}
