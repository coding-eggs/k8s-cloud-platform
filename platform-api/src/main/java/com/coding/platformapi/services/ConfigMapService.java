package com.coding.platformapi.services;

import com.coding.common.models.k8s.dto.ConfigMapDTO;
import com.coding.platformapi.k8s.K8sClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * ConfigMap 业务层（资源域参考实现）：目前无业务规则，纯组装 DTO 后经 {@link K8sClient} 透传 k8s-server。
 * <p>
 * 分层约定（见 docs/development/backend-layering.md）：controller 只做 HTTP 绑定与 ResponseData 包装，
 * DTO 组装与后续业务规则一律落在本层 —— 直连 client 会让业务规则散落进 controller，是本批改造要消除的形态。
 */
@Service
@RequiredArgsConstructor
public class ConfigMapService {

    private final K8sClient k8s;

    public List<ConfigMapDTO> list(ConfigMapDTO query) {
        return k8s.list(query);
    }

    /**跨全部命名空间列举（平台侧）。query 不带 namespace —— 授权码在 controller 上独立于 list。 */
    public List<ConfigMapDTO> listAll(ConfigMapDTO query) {
        return k8s.listAll(query);
    }

    public ConfigMapDTO get(String name, String tenantId, String clusterId, String namespace) {
        return k8s.get(dto(name, tenantId, clusterId, namespace));
    }

    public String yaml(String name, String tenantId, String clusterId, String namespace) {
        return k8s.yaml(dto(name, tenantId, clusterId, namespace));
    }

    public ConfigMapDTO create(ConfigMapDTO body) {
        return k8s.create(body);
    }

    public ConfigMapDTO update(ConfigMapDTO body) {
        return k8s.update(body);
    }

    public void delete(String name, String tenantId, String clusterId, String namespace) {
        k8s.delete(dto(name, tenantId, clusterId, namespace));
    }

    /**查询 DTO：apiPath 内置于 DTO */
    private ConfigMapDTO dto(String name, String tenantId, String clusterId, String namespace) {
        ConfigMapDTO d = new ConfigMapDTO();
        d.setName(name);
        d.setTenantId(tenantId);
        d.setClusterId(clusterId);
        d.setNamespace(namespace);
        return d;
    }
}
