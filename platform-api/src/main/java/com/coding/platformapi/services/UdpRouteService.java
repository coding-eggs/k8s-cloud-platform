package com.coding.platformapi.services;

import com.coding.common.models.k8s.dto.UdpRouteDTO;
import com.coding.platformapi.k8s.K8sClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * UDPRoute 业务层：当前为纯透传（六操作全部落到 {@link K8sClient}），按分层约定仍与本资源一一对应
 * （docs/development/backend-layering.md §1.2）。
 * <p>spec 级未建模键（如 v1.6 的 {@code useDefaultGateways}）的保全在 k8s-core 的
 * {@code UdpRouteConverter.convertForUpdate}（fetch-overlay），本层不参与 —— 它没有额外规则要加。
 */
@Service
@RequiredArgsConstructor
public class UdpRouteService {

    private final K8sClient k8s;

    public List<UdpRouteDTO> list(UdpRouteDTO query) {
        return k8s.list(query);
    }

    public UdpRouteDTO get(String name, String tenantId, String clusterId, String namespace) {
        return k8s.get(dto(name, tenantId, clusterId, namespace));
    }

    public String yaml(String name, String tenantId, String clusterId, String namespace) {
        return k8s.yaml(dto(name, tenantId, clusterId, namespace));
    }

    public UdpRouteDTO create(UdpRouteDTO body) {
        return k8s.create(body);
    }

    public UdpRouteDTO update(UdpRouteDTO body) {
        return k8s.update(body);
    }

    public void delete(String name, String tenantId, String clusterId, String namespace) {
        k8s.delete(dto(name, tenantId, clusterId, namespace));
    }

    /** 查询 DTO：apiPath 内置于 DTO */
    private UdpRouteDTO dto(String name, String tenantId, String clusterId, String namespace) {
        UdpRouteDTO d = new UdpRouteDTO();
        d.setName(name);
        d.setTenantId(tenantId);
        d.setClusterId(clusterId);
        d.setNamespace(namespace);
        return d;
    }

}
