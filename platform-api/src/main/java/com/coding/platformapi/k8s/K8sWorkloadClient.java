package com.coding.platformapi.k8s;

import com.coding.common.models.k8s.dto.WorkloadDTO;
import com.coding.common.models.k8s.dto.WorkloadRevisionDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * k8s-server 工作负载专用端点 client（{@code /workloads/**} 中非六端点形态的三个行内动作）：
 * 改镜像 / 历史版本 / 回滚。一个方法一个端点，纯传输零业务。
 * <p>六端点（list/get/yaml/create/update/delete）仍在 {@link K8sClient}（DTO 驱动）；这里只放"动作"。
 * <p>请求体的形状刻意与 k8s-server 侧同名类逐字段一致（namespace + 动作字段）—— 多带一个字段就会被
 * 对方的 Jackson 当成未知属性拒掉（{@code clusterId}/{@code tenantId} 因此走 query，不进 body）。
 */
@Component
@RequiredArgsConstructor
public class K8sWorkloadClient {

    private final K8sServerGateway gateway;

    /**改一个容器的镜像：POST /workloads/{name}/image?clusterId[&tenantId]（body 只带 namespace/containerName/image） */
    public WorkloadDTO updateImage(String name, String tenantId, String clusterId, String namespace,
                                   String containerName, String image) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("namespace", namespace);
        body.put("containerName", containerName);
        body.put("image", image);
        return gateway.exchange(HttpMethod.POST, "/workloads/" + name + "/image", ctx(tenantId, clusterId), body,
                gateway.responseType(WorkloadDTO.class));
    }

    /**历史版本列表（新→旧）：GET /workloads/{name}/revisions?clusterId&namespace[&tenantId] */
    public List<WorkloadRevisionDTO> revisions(String name, String tenantId, String clusterId, String namespace) {
        Map<String, String> params = ctx(tenantId, clusterId);
        params.put("namespace", namespace);
        return gateway.exchange(HttpMethod.GET, "/workloads/" + name + "/revisions", params, null,
                gateway.listResponseType(WorkloadRevisionDTO.class));
    }

    /**回滚到指定版本：POST /workloads/{name}/rollback?clusterId[&tenantId]（body 只带 namespace/revision） */
    public WorkloadDTO rollback(String name, String tenantId, String clusterId, String namespace, Long revision) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("namespace", namespace);
        body.put("revision", revision);
        return gateway.exchange(HttpMethod.POST, "/workloads/" + name + "/rollback", ctx(tenantId, clusterId), body,
                gateway.responseType(WorkloadDTO.class));
    }

    /**query：clusterId 必带；tenantId 仅在有值时拼（admin 代操作时才有） */
    private Map<String, String> ctx(String tenantId, String clusterId) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("clusterId", clusterId);
        if (tenantId != null) {
            params.put("tenantId", tenantId);
        }
        return params;
    }
}
