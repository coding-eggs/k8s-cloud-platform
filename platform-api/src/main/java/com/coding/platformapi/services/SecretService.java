package com.coding.platformapi.services;

import com.coding.common.models.k8s.dto.SecretDTO;
import com.coding.platformapi.k8s.K8sClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Secret 业务层：目前无业务规则，纯组装 DTO 后经 {@link K8sClient} 透传 k8s-server。
 * 分层约定见 docs/development/backend-layering.md。
 */
@Service
@RequiredArgsConstructor
public class SecretService {

    private final K8sClient k8s;

    public List<SecretDTO> list(SecretDTO query) {
        return k8s.list(query);
    }

    public SecretDTO get(String name, String tenantId, String clusterId, String namespace) {
        return k8s.get(dto(name, tenantId, clusterId, namespace));
    }

    public String yaml(String name, String tenantId, String clusterId, String namespace) {
        return k8s.yaml(dto(name, tenantId, clusterId, namespace));
    }

    public SecretDTO create(SecretDTO body) {
        return k8s.create(body);
    }

    public SecretDTO update(SecretDTO body) {
        return k8s.update(body);
    }

    public void delete(String name, String tenantId, String clusterId, String namespace) {
        k8s.delete(dto(name, tenantId, clusterId, namespace));
    }

    /**查询 DTO：apiPath 内置于 DTO */
    private SecretDTO dto(String name, String tenantId, String clusterId, String namespace) {
        SecretDTO d = new SecretDTO();
        d.setName(name);
        d.setTenantId(tenantId);
        d.setClusterId(clusterId);
        d.setNamespace(namespace);
        return d;
    }
}
