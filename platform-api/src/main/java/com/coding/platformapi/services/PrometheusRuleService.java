package com.coding.platformapi.services;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.k8s.dto.PrometheusRuleDTO;
import com.coding.platformapi.k8s.K8sClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

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
@Service
@RequiredArgsConstructor
public class PrometheusRuleService {

    private final K8sClient k8s;

    public List<PrometheusRuleDTO> list(PrometheusRuleDTO query) {
        return k8s.list(query);
    }

    public PrometheusRuleDTO get(String name, String tenantId, String clusterId, String namespace) {
        return k8s.get(dto(name, tenantId, clusterId, namespace));
    }

    public String yaml(String name, String tenantId, String clusterId, String namespace) {
        return k8s.yaml(dto(name, tenantId, clusterId, namespace));
    }

    public PrometheusRuleDTO create(PrometheusRuleDTO body) {
        stampNamespace(body);
        return k8s.create(body);
    }

    public PrometheusRuleDTO update(PrometheusRuleDTO body) {
        stampNamespace(body);
        return k8s.update(body);
    }

    public void delete(String name, String tenantId, String clusterId, String namespace) {
        k8s.delete(dto(name, tenantId, clusterId, namespace));
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
                    r.setLabels(labels);
                }
            }
        }
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
