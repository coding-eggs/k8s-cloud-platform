package com.coding.platformapi.services;

import com.coding.common.models.k8s.dto.PodDTO;
import com.coding.platformapi.k8s.K8sClient;
import com.coding.platformapi.k8s.K8sPodClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.OutputStream;
import java.util.List;

/**
 * Pod 业务层：六操作走 {@link K8sClient}，流式日志走 {@link K8sPodClient}。
 * <p>
 * 日志的 query 组装规则（container 空判 + sinceTime/tailLines/sinceSeconds 三选一）在
 * {@code K8sPodClient}（属传输层映射）；本层只做 service→client 的转发与未来的审计/前置校验位。
 * {@code OutputStream} 由 controller 从 {@code HttpServletResponse} 提供，服务层不感知 servlet。
 * 分层约定见 docs/development/backend-layering.md。
 */
@Service
@RequiredArgsConstructor
public class PodService {

    private final K8sClient k8s;
    private final K8sPodClient podClient;

    public List<PodDTO> list(PodDTO query) {
        return k8s.list(query);
    }

    /**跨全部命名空间列举（平台侧）。query 不带 namespace —— 授权码在 controller 上独立于 list。 */
    public List<PodDTO> listAll(PodDTO query) {
        return k8s.listAll(query);
    }

    public PodDTO get(String name, String tenantId, String clusterId, String namespace) {
        return k8s.get(dto(name, tenantId, clusterId, namespace));
    }

    public String yaml(String name, String tenantId, String clusterId, String namespace) {
        return k8s.yaml(dto(name, tenantId, clusterId, namespace));
    }

    public void delete(String name, String tenantId, String clusterId, String namespace) {
        k8s.delete(dto(name, tenantId, clusterId, namespace));
    }

    /**流式日志透传（不整体缓冲）：写入调用方提供的响应输出流 */
    public void streamLogs(String name, String tenantId, String clusterId, String namespace,
                           String container, Integer sinceSeconds, String sinceTime, Integer tailLines,
                           OutputStream out) {
        podClient.streamLogs(name, tenantId, clusterId, namespace, container, sinceSeconds, sinceTime, tailLines, out);
    }

    /**查询 DTO：apiPath 内置于 DTO */
    private PodDTO dto(String name, String tenantId, String clusterId, String namespace) {
        PodDTO d = new PodDTO();
        d.setName(name);
        d.setTenantId(tenantId);
        d.setClusterId(clusterId);
        d.setNamespace(namespace);
        return d;
    }
}
