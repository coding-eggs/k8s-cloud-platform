package com.coding.k8score.converter.impl.core;

import com.coding.common.models.k8s.dto.ServiceDTO;
import com.coding.common.models.k8s.dto.ServicePortDTO;
import com.coding.k8score.converter.CommonConverter;
import io.fabric8.kubernetes.api.model.IntOrString;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServiceBuilder;
import io.fabric8.kubernetes.api.model.ServicePort;
import io.fabric8.kubernetes.api.model.ServicePortBuilder;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * Service ⇄ ServiceDTO（基础表单字段：name/namespace/labels/type/ports）
 */
public class CoreV1ServiceConverter implements CommonConverter<Service, ServiceDTO> {

    @Override
    public Service convert(ServiceDTO in) {
        return new ServiceBuilder()
                .withNewMetadata()
                    .withName(in.getName())
                    .withNamespace(in.getNamespace())
                    .withLabels(in.getLabels())
                .endMetadata()
                .withNewSpec()
                    .withType(in.getType())
                    .withPorts(toServicePorts(in.getPorts()))
                .endSpec()
                .build();
    }

    @Override
    public ServiceDTO revert(Service service) {
        ServiceDTO dto = new ServiceDTO();
        if (service.getMetadata() != null) {
            dto.setName(service.getMetadata().getName());
            dto.setNamespace(service.getMetadata().getNamespace());
            dto.setLabels(service.getMetadata().getLabels());
            if (service.getMetadata().getCreationTimestamp() != null) {
                dto.setCreationTime(service.getMetadata().getCreationTimestamp().toString());
            }
        }
        if (service.getSpec() != null) {
            dto.setType(service.getSpec().getType());
            String clusterIp = service.getSpec().getClusterIP();
            if (StringUtils.hasText(clusterIp) && !"None".equals(clusterIp)) {
                dto.setClusterIp(clusterIp);
            }
            dto.setPorts(toServicePortDTOs(service.getSpec().getPorts()));
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
                        .withTargetPort(new IntOrString(p.getTargetPort()))
                        .withNodePort(p.getNodePort())
                        .withProtocol(p.getProtocol())
                        .build())
                .toList();
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
            return dto;
        }).toList();
    }

}
