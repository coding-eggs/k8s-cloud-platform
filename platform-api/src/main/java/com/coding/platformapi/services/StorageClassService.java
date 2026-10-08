package com.coding.platformapi.services;

import com.coding.common.models.k8s.dto.StorageClassDTO;
import com.coding.platformapi.k8s.K8sClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * StorageClass 业务层：只读透传。分层约定见 docs/development/backend-layering.md。
 * <p>
 * <b>为什么本层没有租户收窄</b>：StorageClass 是集群级对象且<b>没有命名空间维度</b>，无法像 PV 那样按
 * {@code claimRef.namespace} 过滤到本租户——存储类的 name / provisioner / parameters 是整集群共享的。
 * 故访问面整体收在权限表：{@code V2026_10_07_3} 把三个端点的 code 从 {@code tenant:storageclass:*} 改为
 * {@code platform:cluster:manage}，仅平台管理员可见。这里保持纯透传，不做服务层过滤（服务层过滤无法消除
 * "看得到 name" 这件事，只是把整表读变成部分读，属伪安全）。
 */
@Service
@RequiredArgsConstructor
public class StorageClassService {

    private final K8sClient k8s;

    public List<StorageClassDTO> list(StorageClassDTO query) {
        return k8s.list(query);
    }

    public StorageClassDTO get(String name, String tenantId, String clusterId) {
        return k8s.get(dto(name, tenantId, clusterId));
    }

    public String yaml(String name, String tenantId, String clusterId) {
        return k8s.yaml(dto(name, tenantId, clusterId));
    }

    /**查询 DTO：apiPath 内置于 DTO（集群级，无 namespace） */
    private StorageClassDTO dto(String name, String tenantId, String clusterId) {
        StorageClassDTO d = new StorageClassDTO();
        d.setName(name);
        d.setTenantId(tenantId);
        d.setClusterId(clusterId);
        return d;
    }
}
