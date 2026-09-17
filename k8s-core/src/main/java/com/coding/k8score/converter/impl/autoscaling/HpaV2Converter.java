package com.coding.k8score.converter.impl.autoscaling;

import com.coding.common.models.k8s.dto.HpaBehaviorDTO;
import com.coding.common.models.k8s.dto.HpaContainerResourceMetricDTO;
import com.coding.common.models.k8s.dto.HpaCrossVersionObjectReferenceDTO;
import com.coding.common.models.k8s.dto.HpaDTO;
import com.coding.common.models.k8s.dto.HpaExternalMetricDTO;
import com.coding.common.models.k8s.dto.HpaMetricSpecDTO;
import com.coding.common.models.k8s.dto.HpaMetricTargetDTO;
import com.coding.common.models.k8s.dto.HpaObjectMetricDTO;
import com.coding.common.models.k8s.dto.HpaPodsMetricDTO;
import com.coding.common.models.k8s.dto.HpaResourceMetricDTO;
import com.coding.common.models.k8s.dto.HpaScalingPolicyDTO;
import com.coding.common.models.k8s.dto.HpaScalingRulesDTO;
import com.coding.k8score.converter.CommonConverter;
import com.coding.k8score.util.QuantityUtil;
import io.fabric8.kubernetes.api.model.LabelSelector;
import io.fabric8.kubernetes.api.model.LabelSelectorBuilder;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.autoscaling.v2.ContainerResourceMetricSource;
import io.fabric8.kubernetes.api.model.autoscaling.v2.CrossVersionObjectReference;
import io.fabric8.kubernetes.api.model.autoscaling.v2.ExternalMetricSource;
import io.fabric8.kubernetes.api.model.autoscaling.v2.HPAScalingPolicy;
import io.fabric8.kubernetes.api.model.autoscaling.v2.HPAScalingRules;
import io.fabric8.kubernetes.api.model.autoscaling.v2.HorizontalPodAutoscaler;
import io.fabric8.kubernetes.api.model.autoscaling.v2.HorizontalPodAutoscalerBehavior;
import io.fabric8.kubernetes.api.model.autoscaling.v2.HorizontalPodAutoscalerBuilder;
import io.fabric8.kubernetes.api.model.autoscaling.v2.HorizontalPodAutoscalerSpec;
import io.fabric8.kubernetes.api.model.autoscaling.v2.MetricIdentifier;
import io.fabric8.kubernetes.api.model.autoscaling.v2.MetricSpec;
import io.fabric8.kubernetes.api.model.autoscaling.v2.MetricTarget;
import io.fabric8.kubernetes.api.model.autoscaling.v2.ObjectMetricSource;
import io.fabric8.kubernetes.api.model.autoscaling.v2.PodsMetricSource;
import io.fabric8.kubernetes.api.model.autoscaling.v2.ResourceMetricSource;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * HPA ⇄ HpaDTO（autoscaling/v2，全保真：五类 metrics + behavior）。
 * <p>v1 见 {@link HpaV1Converter}；两者由 {@code KubernetesOperationsFactory.build()} 按集群 capability 选择。
 */
public class HpaV2Converter implements CommonConverter<HorizontalPodAutoscaler, HpaDTO> {

    @Override
    public HorizontalPodAutoscaler convert(HpaDTO in) {
        HorizontalPodAutoscalerSpec spec = new HorizontalPodAutoscalerSpec();
        spec.setMinReplicas(in.getMinReplicas());
        spec.setMaxReplicas(in.getMaxReplicas());
        spec.setScaleTargetRef(toCrossVersionRef(in.getScaleTargetRef()));
        spec.setMetrics(toMetricSpecs(in.getMetrics()));
        spec.setBehavior(toBehavior(in.getBehavior()));

        return new HorizontalPodAutoscalerBuilder()
                .withNewMetadata()
                    .withName(in.getName())
                    .withNamespace(in.getNamespace())
                    .withLabels(in.getLabels())
                .endMetadata()
                .withSpec(spec)
                .build();
    }

    @Override
    public HpaDTO revert(HorizontalPodAutoscaler hpa) {
        if (hpa == null) return null;
        HpaDTO dto = new HpaDTO();
        if (hpa.getMetadata() != null) {
            dto.setName(hpa.getMetadata().getName());
            dto.setNamespace(hpa.getMetadata().getNamespace());
            dto.setLabels(hpa.getMetadata().getLabels());
            if (hpa.getMetadata().getCreationTimestamp() != null) {
                dto.setCreationTime(hpa.getMetadata().getCreationTimestamp());
            }
        }
        HorizontalPodAutoscalerSpec spec = hpa.getSpec();
        if (spec != null) {
            dto.setMinReplicas(spec.getMinReplicas());
            dto.setMaxReplicas(spec.getMaxReplicas());
            dto.setScaleTargetRef(fromCrossVersionRef(spec.getScaleTargetRef()));
            dto.setMetrics(fromMetricSpecs(spec.getMetrics()));
            dto.setBehavior(fromBehavior(spec.getBehavior()));
        }
        if (hpa.getStatus() != null) {
            dto.setCurrentReplicas(hpa.getStatus().getCurrentReplicas());
        }
        return dto;
    }

    // ---------- DTO → K8s ----------

    private CrossVersionObjectReference toCrossVersionRef(HpaCrossVersionObjectReferenceDTO ref) {
        if (ref == null) {
            return null;
        }
        CrossVersionObjectReference r = new CrossVersionObjectReference();
        r.setApiVersion(ref.getApiVersion());
        r.setKind(ref.getKind());
        r.setName(ref.getName());
        return r;
    }

    private List<MetricSpec> toMetricSpecs(List<HpaMetricSpecDTO> metrics) {
        if (metrics == null || metrics.isEmpty()) {
            return null;
        }
        List<MetricSpec> out = new ArrayList<>();
        for (HpaMetricSpecDTO m : metrics) {
            MetricSpec ms = new MetricSpec();
            ms.setType(m.getType());
            if (m.getResource() != null) {
                ResourceMetricSource r = new ResourceMetricSource();
                r.setName(m.getResource().getName());
                r.setTarget(toMetricTarget(m.getResource().getTarget()));
                ms.setResource(r);
            } else if (m.getContainerResource() != null) {
                ContainerResourceMetricSource cr = new ContainerResourceMetricSource();
                cr.setContainer(m.getContainerResource().getContainer());
                cr.setName(m.getContainerResource().getName());
                cr.setTarget(toMetricTarget(m.getContainerResource().getTarget()));
                ms.setContainerResource(cr);
            } else if (m.getPods() != null) {
                PodsMetricSource p = new PodsMetricSource();
                p.setMetric(toMetricIdentifier(m.getPods().getMetricName(), null));
                p.setTarget(toMetricTarget(m.getPods().getTarget()));
                ms.setPods(p);
            } else if (m.getObject() != null) {
                ObjectMetricSource o = new ObjectMetricSource();
                o.setDescribedObject(toCrossVersionRef(m.getObject().getDescribedObject()));
                o.setMetric(toMetricIdentifier(m.getObject().getMetricName(), m.getObject().getSelector()));
                o.setTarget(toMetricTarget(m.getObject().getTarget()));
                ms.setObject(o);
            } else if (m.getExternal() != null) {
                ExternalMetricSource e = new ExternalMetricSource();
                e.setMetric(toMetricIdentifier(m.getExternal().getMetricName(), m.getExternal().getMetricSelector()));
                e.setTarget(toMetricTarget(m.getExternal().getTarget()));
                ms.setExternal(e);
            }
            out.add(ms);
        }
        return out;
    }

    private MetricIdentifier toMetricIdentifier(String name, Map<String, String> selector) {
        if (!StringUtils.hasText(name) && (selector == null || selector.isEmpty())) {
            return null;
        }
        MetricIdentifier mi = new MetricIdentifier();
        mi.setName(name);
        if (selector != null && !selector.isEmpty()) {
            mi.setSelector(new LabelSelectorBuilder().withMatchLabels(selector).build());
        }
        return mi;
    }

    private MetricTarget toMetricTarget(HpaMetricTargetDTO t) {
        if (t == null) {
            return null;
        }
        MetricTarget mt = new MetricTarget();
        mt.setType(t.getType());
        mt.setAverageUtilization(t.getAverageUtilization());
        if (t.getValue() != null) {
            mt.setValue(QuantityUtil.fromBase(t.getValue()));
        }
        if (t.getAverageValue() != null) {
            mt.setAverageValue(QuantityUtil.fromBase(t.getAverageValue()));
        }
        return mt;
    }

    private HorizontalPodAutoscalerBehavior toBehavior(HpaBehaviorDTO b) {
        if (b == null || (b.getScaleUp() == null && b.getScaleDown() == null)) {
            return null;
        }
        HorizontalPodAutoscalerBehavior bh = new HorizontalPodAutoscalerBehavior();
        bh.setScaleUp(toScalingRules(b.getScaleUp()));
        bh.setScaleDown(toScalingRules(b.getScaleDown()));
        return bh;
    }

    private HPAScalingRules toScalingRules(HpaScalingRulesDTO r) {
        if (r == null) {
            return null;
        }
        HPAScalingRules rules = new HPAScalingRules();
        rules.setStabilizationWindowSeconds(r.getStabilizationWindowSeconds());
        rules.setSelectPolicy(r.getSelectPolicy());
        if (r.getPolicies() != null && !r.getPolicies().isEmpty()) {
            List<HPAScalingPolicy> ps = new ArrayList<>();
            for (HpaScalingPolicyDTO p : r.getPolicies()) {
                HPAScalingPolicy pol = new HPAScalingPolicy();
                pol.setType(p.getType());
                pol.setValue(p.getValue());
                pol.setPeriodSeconds(p.getPeriodSeconds());
                ps.add(pol);
            }
            rules.setPolicies(ps);
        }
        return rules;
    }

    // ---------- K8s → DTO ----------

    private HpaCrossVersionObjectReferenceDTO fromCrossVersionRef(CrossVersionObjectReference r) {
        if (r == null) {
            return null;
        }
        HpaCrossVersionObjectReferenceDTO d = new HpaCrossVersionObjectReferenceDTO();
        d.setApiVersion(r.getApiVersion());
        d.setKind(r.getKind());
        d.setName(r.getName());
        return d;
    }

    private List<HpaMetricSpecDTO> fromMetricSpecs(List<MetricSpec> specs) {
        if (specs == null || specs.isEmpty()) {
            return null;
        }
        List<HpaMetricSpecDTO> out = new ArrayList<>();
        for (MetricSpec ms : specs) {
            HpaMetricSpecDTO d = new HpaMetricSpecDTO();
            d.setType(ms.getType());
            if (ms.getResource() != null) {
                HpaResourceMetricDTO r = new HpaResourceMetricDTO();
                r.setName(ms.getResource().getName());
                r.setTarget(fromMetricTarget(ms.getResource().getTarget()));
                d.setResource(r);
            } else if (ms.getContainerResource() != null) {
                HpaContainerResourceMetricDTO cr = new HpaContainerResourceMetricDTO();
                cr.setContainer(ms.getContainerResource().getContainer());
                cr.setName(ms.getContainerResource().getName());
                cr.setTarget(fromMetricTarget(ms.getContainerResource().getTarget()));
                d.setContainerResource(cr);
            } else if (ms.getPods() != null) {
                HpaPodsMetricDTO p = new HpaPodsMetricDTO();
                if (ms.getPods().getMetric() != null) {
                    p.setMetricName(ms.getPods().getMetric().getName());
                }
                p.setTarget(fromMetricTarget(ms.getPods().getTarget()));
                d.setPods(p);
            } else if (ms.getObject() != null) {
                HpaObjectMetricDTO o = new HpaObjectMetricDTO();
                o.setDescribedObject(fromCrossVersionRef(ms.getObject().getDescribedObject()));
                if (ms.getObject().getMetric() != null) {
                    o.setMetricName(ms.getObject().getMetric().getName());
                    o.setSelector(matchLabelsOf(ms.getObject().getMetric().getSelector()));
                }
                o.setTarget(fromMetricTarget(ms.getObject().getTarget()));
                d.setObject(o);
            } else if (ms.getExternal() != null) {
                HpaExternalMetricDTO e = new HpaExternalMetricDTO();
                if (ms.getExternal().getMetric() != null) {
                    e.setMetricName(ms.getExternal().getMetric().getName());
                    e.setMetricSelector(matchLabelsOf(ms.getExternal().getMetric().getSelector()));
                }
                e.setTarget(fromMetricTarget(ms.getExternal().getTarget()));
                d.setExternal(e);
            }
            out.add(d);
        }
        return out;
    }

    private Map<String, String> matchLabelsOf(LabelSelector ls) {
        if (ls == null || ls.getMatchLabels() == null || ls.getMatchLabels().isEmpty()) {
            return null;
        }
        return ls.getMatchLabels();
    }

    private HpaMetricTargetDTO fromMetricTarget(MetricTarget mt) {
        if (mt == null) {
            return null;
        }
        HpaMetricTargetDTO d = new HpaMetricTargetDTO();
        d.setType(mt.getType());
        d.setAverageUtilization(mt.getAverageUtilization());
        if (mt.getValue() != null) {
            d.setValue(QuantityUtil.toBase(mt.getValue()));
        }
        if (mt.getAverageValue() != null) {
            d.setAverageValue(QuantityUtil.toBase(mt.getAverageValue()));
        }
        return d;
    }

    private HpaBehaviorDTO fromBehavior(HorizontalPodAutoscalerBehavior bh) {
        if (bh == null || (bh.getScaleUp() == null && bh.getScaleDown() == null)) {
            return null;
        }
        HpaBehaviorDTO d = new HpaBehaviorDTO();
        d.setScaleUp(fromScalingRules(bh.getScaleUp()));
        d.setScaleDown(fromScalingRules(bh.getScaleDown()));
        return d;
    }

    private HpaScalingRulesDTO fromScalingRules(HPAScalingRules rules) {
        if (rules == null) {
            return null;
        }
        HpaScalingRulesDTO d = new HpaScalingRulesDTO();
        d.setStabilizationWindowSeconds(rules.getStabilizationWindowSeconds());
        d.setSelectPolicy(rules.getSelectPolicy());
        if (rules.getPolicies() != null && !rules.getPolicies().isEmpty()) {
            List<HpaScalingPolicyDTO> ps = new ArrayList<>();
            for (HPAScalingPolicy p : rules.getPolicies()) {
                HpaScalingPolicyDTO pd = new HpaScalingPolicyDTO();
                pd.setType(p.getType());
                pd.setValue(p.getValue());
                pd.setPeriodSeconds(p.getPeriodSeconds());
                ps.add(pd);
            }
            d.setPolicies(ps);
        }
        return d;
    }

}
