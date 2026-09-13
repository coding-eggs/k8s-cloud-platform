package com.coding.k8score.converter.impl.core;

import com.coding.common.models.k8s.dto.ServiceDTO;
import com.coding.common.models.k8s.dto.ServicePortDTO;
import com.coding.k8score.converter.CommonConverter;
import io.fabric8.kubernetes.api.model.IntOrString;
import io.fabric8.kubernetes.api.model.ClientIPConfig;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServiceBuilder;
import io.fabric8.kubernetes.api.model.ServicePort;
import io.fabric8.kubernetes.api.model.ServicePortBuilder;
import io.fabric8.kubernetes.api.model.ServiceSpec;
import io.fabric8.kubernetes.api.model.SessionAffinityConfig;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * Service ⇄ ServiceDTO（name/namespace/labels/type/ports + 网络 IP / 流量策略 / LB / ExternalName）。
 * update 为整对象 PUT 替换，故 convert 按 type 条件写、revert 全量读回，避免编辑保存时字段被清空。
 */
public class CoreV1ServiceConverter implements CommonConverter<Service, ServiceDTO> {

    @Override
    public Service convert(ServiceDTO in) {
        boolean externalName = "ExternalName".equals(in.getType());
        ServiceSpec spec = new ServiceSpec();
        spec.setType(in.getType());
        spec.setPorts(toServicePorts(in.getPorts()));

        if (externalName) {
            // ExternalName：仅外部别名（CNAME），其余网络字段不适用（apiserver 会清空）
            spec.setExternalName(in.getExternalName());
        } else {
            applyIpFields(spec, in);
            spec.setSelector(in.getSelector());
            spec.setExternalIPs(in.getExternalIps());
            spec.setInternalTrafficPolicy(in.getInternalTrafficPolicy());
            spec.setExternalTrafficPolicy(in.getExternalTrafficPolicy());
            spec.setPublishNotReadyAddresses(in.getPublishNotReadyAddresses());
            spec.setSessionAffinity(in.getSessionAffinity());
            applySessionAffinityConfig(spec, in);
            if ("LoadBalancer".equals(in.getType())) {
                spec.setAllocateLoadBalancerNodePorts(in.getAllocateLoadBalancerNodePorts());
                spec.setHealthCheckNodePort(in.getHealthCheckNodePort());
                spec.setLoadBalancerClass(in.getLoadBalancerClass());
                spec.setLoadBalancerSourceRanges(in.getLoadBalancerSourceRanges());
            }
        }

        return new ServiceBuilder()
                .withNewMetadata()
                    .withName(in.getName())
                    .withNamespace(in.getNamespace())
                    .withLabels(in.getLabels())
                .endMetadata()
                .withSpec(spec)
                .build();
    }

    /**
     * 单栈：只发 clusterIP（空=自动 / "None"=headless / 具体 IP），ipFamilies/clusterIPs 交给 apiserver 分配；
     * 双栈：发 ipFamilies + clusterIPs，且保证 clusterIP == clusterIPs[0]（apiserver 强制一致）。
     */
    private void applyIpFields(ServiceSpec spec, ServiceDTO in) {
        List<String> clusterIps = in.getClusterIps();
        if (clusterIps != null && !clusterIps.isEmpty()) {
            spec.setClusterIPs(clusterIps);
            spec.setClusterIP(clusterIps.get(0));
        } else {
            spec.setClusterIP(in.getClusterIp());
        }
        if (in.getIpFamilies() != null && !in.getIpFamilies().isEmpty()) {
            spec.setIpFamilies(in.getIpFamilies());
        }
        if (StringUtils.hasText(in.getIpFamilyPolicy())) {
            spec.setIpFamilyPolicy(in.getIpFamilyPolicy());
        }
    }

    /** 仅 sessionAffinity=ClientIP 且超时非空时构建 spec.sessionAffinityConfig.clientIP.timeoutSeconds */
    private void applySessionAffinityConfig(ServiceSpec spec, ServiceDTO in) {
        if ("ClientIP".equals(in.getSessionAffinity()) && in.getSessionAffinityTimeoutSeconds() != null) {
            ClientIPConfig clientIP = new ClientIPConfig();
            clientIP.setTimeoutSeconds(in.getSessionAffinityTimeoutSeconds());
            SessionAffinityConfig config = new SessionAffinityConfig();
            config.setClientIP(clientIP);
            spec.setSessionAffinityConfig(config);
        }
    }

    @Override
    public ServiceDTO revert(Service service) {
        ServiceDTO dto = new ServiceDTO();
        if (service.getMetadata() != null) {
            dto.setName(service.getMetadata().getName());
            dto.setNamespace(service.getMetadata().getNamespace());
            dto.setLabels(service.getMetadata().getLabels());
            if (service.getMetadata().getCreationTimestamp() != null) {
                dto.setCreationTime(service.getMetadata().getCreationTimestamp());
            }
        }
        ServiceSpec spec = service.getSpec();
        if (spec != null) {
            dto.setType(spec.getType());
            // clusterIP 原样回显（含 "None"=headless），空串归一为 null 便于表单判断
            String clusterIp = spec.getClusterIP();
            dto.setClusterIp(clusterIp == null || clusterIp.isEmpty() ? null : clusterIp);
            if (spec.getClusterIPs() != null && !spec.getClusterIPs().isEmpty()) {
                dto.setClusterIps(spec.getClusterIPs());
            }
            dto.setSelector(spec.getSelector());
            dto.setExternalIps(spec.getExternalIPs());
            dto.setIpFamilies(spec.getIpFamilies());
            dto.setIpFamilyPolicy(spec.getIpFamilyPolicy());
            dto.setInternalTrafficPolicy(spec.getInternalTrafficPolicy());
            dto.setExternalTrafficPolicy(spec.getExternalTrafficPolicy());
            dto.setPublishNotReadyAddresses(spec.getPublishNotReadyAddresses());
            dto.setSessionAffinity(spec.getSessionAffinity());
            if (spec.getSessionAffinityConfig() != null && spec.getSessionAffinityConfig().getClientIP() != null) {
                dto.setSessionAffinityTimeoutSeconds(spec.getSessionAffinityConfig().getClientIP().getTimeoutSeconds());
            }
            dto.setAllocateLoadBalancerNodePorts(spec.getAllocateLoadBalancerNodePorts());
            dto.setHealthCheckNodePort(spec.getHealthCheckNodePort());
            dto.setLoadBalancerClass(spec.getLoadBalancerClass());
            dto.setLoadBalancerSourceRanges(spec.getLoadBalancerSourceRanges());
            dto.setExternalName(spec.getExternalName());
            dto.setPorts(toServicePortDTOs(spec.getPorts()));
        }
        return dto;
    }

    private List<ServicePort> toServicePorts(List<ServicePortDTO> ports) {
        if (ports == null) {
            return null;
        }
        return ports.stream()
                .map(p -> new ServicePortBuilder()
                        .withName(p.getName())
                        .withPort(p.getPort())
                        .withTargetPort(toIntOrString(p.getTargetPort()))
                        .withNodePort(p.getNodePort())
                        .withProtocol(p.getProtocol())
                        .withAppProtocol(p.getAppProtocol())
                        .build())
                .toList();
    }

    /** targetPort 数字须以 JSON number 下发（"80"）；用 String 构造器会变成字符串，被 API server 当命名端口拒绝 */
    private static IntOrString toIntOrString(String value) {
        if (value == null) return null;
        String s = value.trim();
        if (s.isEmpty()) return null;
        if (s.matches("-?\\d+")) {
            try {
                return new IntOrString(Integer.parseInt(s));
            } catch (NumberFormatException ignored) { /* 超出 int 范围，退回 string */ }
        }
        return new IntOrString(s);
    }

    private List<ServicePortDTO> toServicePortDTOs(List<ServicePort> ports) {
        if (ports == null) {
            return null;
        }
        return ports.stream().map(p -> {
            ServicePortDTO dto = new ServicePortDTO();
            dto.setName(p.getName());
            dto.setPort(p.getPort());
            IntOrString target = p.getTargetPort();
            if (target != null) {
                dto.setTargetPort(target.getIntVal() != null ? String.valueOf(target.getIntVal()) : target.getStrVal());
            }
            dto.setNodePort(p.getNodePort());
            dto.setProtocol(p.getProtocol());
            dto.setAppProtocol(p.getAppProtocol());
            return dto;
        }).toList();
    }

}
