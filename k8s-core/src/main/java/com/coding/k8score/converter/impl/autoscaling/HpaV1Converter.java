package com.coding.k8score.converter.impl.autoscaling;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.k8s.dto.HpaCrossVersionObjectReferenceDTO;
import com.coding.common.models.k8s.dto.HpaDTO;
import com.coding.common.models.k8s.dto.HpaMetricSpecDTO;
import com.coding.common.models.k8s.dto.HpaMetricTargetDTO;
import com.coding.common.models.k8s.dto.HpaResourceMetricDTO;
import com.coding.k8score.converter.CommonConverter;
import io.fabric8.kubernetes.api.model.autoscaling.v1.CrossVersionObjectReference;
import io.fabric8.kubernetes.api.model.autoscaling.v1.HorizontalPodAutoscaler;
import io.fabric8.kubernetes.api.model.autoscaling.v1.HorizontalPodAutoscalerBuilder;
import io.fabric8.kubernetes.api.model.autoscaling.v1.HorizontalPodAutoscalerSpec;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * HPA ⇄ HpaDTO（autoscaling/v1）。v1 仅支持 CPU 利用率：{@code targetCPUUtilizationPercentage}。
 * <p>因此 {@link #convert} 只接受「单条 Resource/cpu/Utilization」指标，出现内存/其他指标或 behavior 时抛
 * {@link EnumResponseType#HPA_V1_ONLY_CPU}（显式报错，不静默丢弃用户意图）；{@link #revert} 把 CPU 目标还原为
 * 一条 Resource/cpu/Utilization metric，behavior 恒为 null。
 */
public class HpaV1Converter implements CommonConverter<HorizontalPodAutoscaler, HpaDTO> {

    @Override
    public HorizontalPodAutoscaler convert(HpaDTO in) {
        Integer cpu = extractCpuTarget(in); // 校验：v1 仅支持 CPU 利用率
        HorizontalPodAutoscalerSpec spec = new HorizontalPodAutoscalerSpec();
        spec.setMinReplicas(in.getMinReplicas());
        spec.setMaxReplicas(in.getMaxReplicas());
        spec.setTargetCPUUtilizationPercentage(cpu);
        if (in.getScaleTargetRef() != null) {
            CrossVersionObjectReference ref = new CrossVersionObjectReference();
            ref.setApiVersion(in.getScaleTargetRef().getApiVersion());
            ref.setKind(in.getScaleTargetRef().getKind());
            ref.setName(in.getScaleTargetRef().getName());
            spec.setScaleTargetRef(ref);
        }
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
            if (spec.getScaleTargetRef() != null) {
                HpaCrossVersionObjectReferenceDTO ref = new HpaCrossVersionObjectReferenceDTO();
                ref.setApiVersion(spec.getScaleTargetRef().getApiVersion());
                ref.setKind(spec.getScaleTargetRef().getKind());
                ref.setName(spec.getScaleTargetRef().getName());
                dto.setScaleTargetRef(ref);
            }
            Integer cpu = spec.getTargetCPUUtilizationPercentage();
            if (cpu != null) {
                HpaMetricSpecDTO m = new HpaMetricSpecDTO();
                m.setType("Resource");
                HpaResourceMetricDTO r = new HpaResourceMetricDTO();
                r.setName("cpu");
                HpaMetricTargetDTO t = new HpaMetricTargetDTO();
                t.setType("Utilization");
                t.setAverageUtilization(cpu);
                r.setTarget(t);
                m.setResource(r);
                dto.setMetrics(List.of(m));
            }
        }
        if (hpa.getStatus() != null) {
            dto.setCurrentReplicas(hpa.getStatus().getCurrentReplicas());
        }
        return dto;
    }

    /**
     * 从 DTO 提取 v1 可表示的 CPU 利用率目标；任何 v1 无法表示的内容（内存/其他指标/多指标/behavior）都显式抛错。
     */
    private Integer extractCpuTarget(HpaDTO in) {
        if (in.getBehavior() != null) {
            throw new CloudPlatformException(EnumResponseType.HPA_V1_ONLY_CPU);
        }
        List<HpaMetricSpecDTO> metrics = in.getMetrics();
        if (metrics == null || metrics.isEmpty()) {
            return null;
        }
        if (metrics.size() > 1) {
            throw new CloudPlatformException(EnumResponseType.HPA_V1_ONLY_CPU);
        }
        HpaResourceMetricDTO r = metrics.getFirst().getResource();
        if (r == null || !StringUtils.hasText(r.getName()) || !"cpu".equalsIgnoreCase(r.getName())) {
            throw new CloudPlatformException(EnumResponseType.HPA_V1_ONLY_CPU);
        }
        HpaMetricTargetDTO t = r.getTarget();
        if (t == null || !"Utilization".equals(t.getType()) || t.getAverageUtilization() == null) {
            throw new CloudPlatformException(EnumResponseType.HPA_V1_ONLY_CPU);
        }
        return t.getAverageUtilization();
    }

}
