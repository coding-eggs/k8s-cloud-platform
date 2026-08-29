package com.coding.k8score.converter.impl.monitoring;

import com.coding.common.models.k8s.dto.ServiceMonitorDTO;
import com.coding.common.models.k8s.dto.ServiceMonitorEndpointDTO;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ServiceMonitorDTO ⇄ GenericKubernetesResource（monitoring.coreos.com/v1 CRD）。
 * 走 fabric8 通用 CRD API，不引代码生成依赖；spec 以 Map 结构读写。
 */
public class ServiceMonitorConverter {

    public static final String API_VERSION = "monitoring.coreos.com/v1";

    public GenericKubernetesResource convert(ServiceMonitorDTO dto) {
        GenericKubernetesResource res = new GenericKubernetesResource();
        res.setApiVersion(API_VERSION);
        res.setKind("ServiceMonitor");
        res.setMetadata(new ObjectMetaBuilder()
                .withName(dto.getName())
                .withNamespace(dto.getNamespace())
                .withLabels(dto.getLabels())
                .build());

        Map<String, Object> spec = new LinkedHashMap<>();
        if (dto.getMatchLabels() != null && !dto.getMatchLabels().isEmpty()) {
            spec.put("selector", Map.of("matchLabels", dto.getMatchLabels()));
        }
        List<Map<String, Object>> endpoints = toEndpointMaps(dto.getEndpoints());
        if (!endpoints.isEmpty()) {
            spec.put("endpoints", endpoints);
        }
        res.setAdditionalProperty("spec", spec);
        return res;
    }

    @SuppressWarnings("unchecked")
    public ServiceMonitorDTO revert(GenericKubernetesResource res) {
        ServiceMonitorDTO dto = new ServiceMonitorDTO();
        if (res.getMetadata() != null) {
            dto.setName(res.getMetadata().getName());
            dto.setNamespace(res.getMetadata().getNamespace());
            dto.setLabels(res.getMetadata().getLabels());
            if (res.getMetadata().getCreationTimestamp() != null) {
                dto.setCreationTime(res.getMetadata().getCreationTimestamp().toString());
            }
        }
        Map<String, Object> spec = res.getAdditionalProperties() != null
                ? (Map<String, Object>) res.getAdditionalProperties().get("spec") : null;
        if (spec == null) {
            return dto;
        }
        Map<String, Object> selector = (Map<String, Object>) spec.get("selector");
        if (selector != null) {
            dto.setMatchLabels((Map<String, String>) selector.get("matchLabels"));
        }
        List<Map<String, Object>> endpoints = (List<Map<String, Object>>) spec.get("endpoints");
        if (endpoints != null) {
            dto.setEndpoints(endpoints.stream().map(e -> {
                ServiceMonitorEndpointDTO ep = new ServiceMonitorEndpointDTO();
                if (e.get("port") != null) {
                    ep.setPort(String.valueOf(e.get("port")));
                }
                ep.setPath((String) e.get("path"));
                ep.setInterval((String) e.get("interval"));
                return ep;
            }).toList());
        }
        return dto;
    }

    private List<Map<String, Object>> toEndpointMaps(List<ServiceMonitorEndpointDTO> endpoints) {
        if (endpoints == null) {
            return List.of();
        }
        return endpoints.stream().map(e -> {
            Map<String, Object> m = new LinkedHashMap<>();
            if (e.getPort() != null && !e.getPort().isBlank()) {
                m.put("port", e.getPort());
            }
            if (e.getPath() != null && !e.getPath().isBlank()) {
                m.put("path", e.getPath());
            }
            if (e.getInterval() != null && !e.getInterval().isBlank()) {
                m.put("interval", e.getInterval());
            }
            return m;
        }).toList();
    }

}
