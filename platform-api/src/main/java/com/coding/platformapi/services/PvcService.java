package com.coding.platformapi.services;

import com.coding.common.models.k8s.dto.PersistentVolumeClaimDTO;
import com.coding.platformapi.k8s.K8sClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * PVC 业务层：目前无业务规则（spec 创建后不可变，update 仅同步标签由 k8s-server 侧落地），
 * 纯组装 DTO 后经 {@link K8sClient} 透传。分层约定见 docs/development/backend-layering.md。
 */
@Service
@RequiredArgsConstructor
public class PvcService {

    private final K8sClient k8s;

    public List<PersistentVolumeClaimDTO> list(PersistentVolumeClaimDTO query) {
        return k8s.list(query);
    }

    public PersistentVolumeClaimDTO get(String name, String tenantId, String clusterId, String namespace) {
        return k8s.get(dto(name, tenantId, clusterId, namespace));
    }

    public String yaml(String name, String tenantId, String clusterId, String namespace) {
        return k8s.yaml(dto(name, tenantId, clusterId, namespace));
    }

    public PersistentVolumeClaimDTO create(PersistentVolumeClaimDTO body) {
        return k8s.create(body);
    }

    public PersistentVolumeClaimDTO update(PersistentVolumeClaimDTO body) {
        return k8s.update(body);
    }

    public void delete(String name, String tenantId, String clusterId, String namespace) {
        k8s.delete(dto(name, tenantId, clusterId, namespace));
    }

    /**查询 DTO：apiPath 内置于 DTO */
    private PersistentVolumeClaimDTO dto(String name, String tenantId, String clusterId, String namespace) {
        PersistentVolumeClaimDTO d = new PersistentVolumeClaimDTO();
        d.setName(name);
        d.setTenantId(tenantId);
        d.setClusterId(clusterId);
        d.setNamespace(namespace);
        return d;
    }
}
