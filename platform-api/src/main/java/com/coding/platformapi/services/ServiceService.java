package com.coding.platformapi.services;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.k8s.BaseResources;
import com.coding.common.models.k8s.dto.PodDTO;
import com.coding.common.models.k8s.dto.ServiceDTO;
import com.coding.common.models.k8s.dto.WorkloadDTO;
import com.coding.platformapi.k8s.K8sClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Service 业务层：create/update 先解析 selector 绑定（selectorRef → label map），通过再透传 k8s-server；
 * list/get/yaml/delete 无业务规则，纯组装 DTO 后透传。分层约定见 docs/development/backend-layering.md。
 */
@Service
@RequiredArgsConstructor
public class ServiceService {

    private final K8sClient k8s;

    public List<ServiceDTO> list(ServiceDTO query) {
        return k8s.list(query);
    }

    public ServiceDTO get(String name, String tenantId, String clusterId, String namespace) {
        return k8s.get(dto(name, tenantId, clusterId, namespace));
    }

    public String yaml(String name, String tenantId, String clusterId, String namespace) {
        return k8s.yaml(dto(name, tenantId, clusterId, namespace));
    }

    public void delete(String name, String tenantId, String clusterId, String namespace) {
        k8s.delete(dto(name, tenantId, clusterId, namespace));
    }

    public ServiceDTO create(ServiceDTO body) {
        resolveSelector(body);
        return k8s.create(body);
    }

    public ServiceDTO update(ServiceDTO body) {
        resolveSelector(body);
        return k8s.update(body);
    }

    /**查询 DTO：apiPath 内置于 DTO */
    private ServiceDTO dto(String name, String tenantId, String clusterId, String namespace) {
        ServiceDTO d = new ServiceDTO();
        d.setName(name);
        d.setTenantId(tenantId);
        d.setClusterId(clusterId);
        d.setNamespace(namespace);
        return d;
    }

    /**
     * 解析 selector 绑定：有 selectorRef（workload/pod + name）→ 反查其 label map 覆盖 body.selector；
     * 无 selectorRef → selector 原样保留（编辑回填 / 「不设置」）。selectorRef 为平台侧字段，解析后剥离、不下发。
     */
    private void resolveSelector(ServiceDTO body) {
        ServiceDTO.SelectorRef ref = body.getSelectorRef();
        body.setSelectorRef(null); // 无论是否解析都剥离，绝不下发 k8s-server / 不落库
        if (ref == null || !StringUtils.hasText(ref.getName())) {
            return;
        }
        Map<String, String> resolved = "pod".equalsIgnoreCase(ref.getType())
                ? resolveFromPod(body, ref.getName())
                : resolveFromWorkload(body, ref.getName());
        body.setSelector(resolved);
    }

    /** 绑定工作负载：取 spec.selector.matchLabels，再反查其 Pod 用实际 labels（更精确）；0 Pod 时退回 matchLabels */
    private Map<String, String> resolveFromWorkload(ServiceDTO ctx, String name) {
        WorkloadDTO wq = new WorkloadDTO();
        fill(wq, ctx);
        wq.setName(name);
        WorkloadDTO w = k8s.get(wq);
        if (w == null) {
            throw new CloudPlatformException(EnumResponseType.RESOURCE_NOT_EXIST, "工作负载「" + name + "」不存在");
        }
        Map<String, String> matchLabels = w.getSelector();
        if (matchLabels == null || matchLabels.isEmpty()) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "工作负载「" + name + "」无 selector.matchLabels，无法绑定");
        }

        return matchLabels;
    }

    /** 绑定 Pod：直接取该 Pod 的 metadata.labels */
    private Map<String, String> resolveFromPod(ServiceDTO ctx, String name) {
        PodDTO pq = new PodDTO();
        fill(pq, ctx);
        pq.setName(name);
        PodDTO p = k8s.get(pq);
        if (p == null) {
            throw new CloudPlatformException(EnumResponseType.RESOURCE_NOT_EXIST, "Pod「" + name + "」不存在");
        }
        Map<String, String> labels = p.getLabels();
        if (labels == null || labels.isEmpty()) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "Pod「" + name + "」无 labels，无法绑定");
        }
        return new LinkedHashMap<>(labels);
    }

    /** 从 ServiceDTO 复制身份字段（tenant/cluster/namespace）到查询 DTO */
    private void fill(BaseResources q, ServiceDTO ctx) {
        q.setTenantId(ctx.getTenantId());
        q.setClusterId(ctx.getClusterId());
        q.setNamespace(ctx.getNamespace());
    }

    /** matchLabels → K8s 标签选择器字符串（k=v,k2=v2） */
    private String toSelectorString(Map<String, String> m) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : m.entrySet()) {
            if (!sb.isEmpty()) {
                sb.append(',');
            }
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

}
