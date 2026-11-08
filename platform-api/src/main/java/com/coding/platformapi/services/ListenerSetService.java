package com.coding.platformapi.services;

import com.coding.common.models.k8s.dto.ListenerSetDTO;
import com.coding.data.mapper.system.PlatformApplicationMapper;
import com.coding.data.models.system.PlatformApplication;
import com.coding.data.models.system.PlatformIngressPort;
import com.coding.platformapi.k8s.K8sClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * ListenerSet 业务层（只读）：透传 list / list-all / get / yaml，并给平台维护的 {@code platform-ingress}
 * 补上每条 listener 的归属（端口分配表 → 应用 / 服务）。
 *
 * <p>只补 {@code platform-ingress}：用户自建 ListenerSet 的端口与分配表无关，按端口号去配会张冠李戴。
 * 分配表里没有的 listener（同步失败留下的残留）保留端口、归属为空，页面据此标「无归属」。
 */
@Service
@RequiredArgsConstructor
public class ListenerSetService {

    private final K8sClient k8s;
    private final IngressPortService ports;
    private final PlatformApplicationMapper apps;

    public List<ListenerSetDTO> list(ListenerSetDTO query) {
        return enrich(k8s.list(query), query.getClusterId());
    }

    public List<ListenerSetDTO> listAll(ListenerSetDTO query) {
        return enrich(k8s.listAll(query), query.getClusterId());
    }

    public ListenerSetDTO get(String name, String tenantId, String clusterId, String namespace) {
        ListenerSetDTO d = k8s.get(dto(name, tenantId, clusterId, namespace));
        if (d == null) {
            return null;
        }
        enrich(List.of(d), clusterId);
        return d;
    }

    public String yaml(String name, String tenantId, String clusterId, String namespace) {
        return k8s.yaml(dto(name, tenantId, clusterId, namespace));
    }

    private List<ListenerSetDTO> enrich(List<ListenerSetDTO> items, String clusterId) {
        if (items == null) {
            return List.of();
        }
        Map<String, Optional<PlatformApplication>> appCache = new HashMap<>();
        for (ListenerSetDTO d : items) {
            boolean managed = IngressListenerSync.LISTENER_SET.equals(d.getName());
            d.setPlatformManaged(managed);
            if (!managed) {
                continue;
            }
            String cluster = d.getClusterId() != null ? d.getClusterId() : clusterId;
            Map<Integer, PlatformIngressPort> byPort = new HashMap<>();
            List<PlatformIngressPort> rows = ports.activeInNamespace(cluster, d.getNamespace());
            for (PlatformIngressPort r : rows == null ? List.<PlatformIngressPort>of() : rows) {
                byPort.put(r.getPort(), r);
            }
            List<ListenerSetDTO.ListenerOwner> owners = new ArrayList<>();
            for (ListenerSetDTO.Listener l : d.getListeners() == null ? List.<ListenerSetDTO.Listener>of() : d.getListeners()) {
                ListenerSetDTO.ListenerOwner o = new ListenerSetDTO.ListenerOwner();
                o.setListenerName(l.getName());
                o.setPort(l.getPort());
                PlatformIngressPort r = l.getPort() == null ? null : byPort.get(l.getPort());
                if (r != null) {
                    o.setAppId(r.getAppId());
                    o.setServiceKey(r.getServiceKey());
                    appCache.computeIfAbsent(r.getAppId(), id -> Optional.ofNullable(apps.selectById(id)))
                            .ifPresent(a -> o.setAppName(a.getName()));
                }
                owners.add(o);
            }
            d.setOwners(owners);
        }
        return items;
    }

    private static ListenerSetDTO dto(String name, String tenantId, String clusterId, String namespace) {
        ListenerSetDTO d = new ListenerSetDTO();
        d.setName(name);
        d.setTenantId(tenantId);
        d.setClusterId(clusterId);
        d.setNamespace(namespace);
        return d;
    }
}
