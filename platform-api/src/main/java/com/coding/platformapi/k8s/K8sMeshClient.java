package com.coding.platformapi.k8s;

import com.coding.common.models.k8s.dto.GatewayClassDTO;
import com.coding.common.models.k8s.dto.GatewayDTO;
import com.coding.common.models.k8s.dto.admin.AdminMeshProbeResult;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * k8s-server 服务网格 client（{@code /gatewayclasses/**} + {@code /mesh/**}）：一方法一端点，纯传输零业务。
 * <p>降级与编排在 {@code MeshService}。
 * <p><b>为什么 GatewayClass 独立成 client 而不并入 {@link K8sClient} 的六操作</b>：GatewayClass 是集群级
 * 资源，{@link K8sClient} 的 query 拼装按"命名空间域"设计（create/update 的 namespace 在 body），
 * 而集群级端点只有 clusterId。Calico 一族（{@code K8sCalicoClient}）同理。
 * <p>命名空间级的 Gateway / HTTPRoute <b>不在这里</b> —— 它们是标准六操作形态，走 {@link K8sClient}。
 */
@Component
@RequiredArgsConstructor
public class K8sMeshClient {

    private final K8sServerGateway gateway;

    // ==================== GatewayClass（/gatewayclasses，集群级 CRUD） ====================

    /**列出 GatewayClass：POST /gatewayclasses/list（clusterId/labelSelector 在 body） */
    public List<GatewayClassDTO> listGatewayClasses(GatewayClassDTO query) {
        return gateway.exchange(HttpMethod.POST, "/gatewayclasses/list", null, query,
                gateway.listResponseType(GatewayClassDTO.class));
    }

    /**查询单个 GatewayClass：GET /gatewayclasses/{name}?clusterId */
    public GatewayClassDTO getGatewayClass(String clusterId, String name) {
        return gateway.exchange(HttpMethod.GET, "/gatewayclasses/" + name, queryOf(clusterId), null,
                gateway.responseType(GatewayClassDTO.class));
    }

    /**查询 GatewayClass YAML（只读）：GET /gatewayclasses/{name}/yaml?clusterId */
    public String gatewayClassYaml(String clusterId, String name) {
        return gateway.exchange(HttpMethod.GET, "/gatewayclasses/" + name + "/yaml", queryOf(clusterId), null,
                gateway.responseType(String.class));
    }

    /**创建 GatewayClass：POST /gatewayclasses?clusterId */
    public GatewayClassDTO createGatewayClass(GatewayClassDTO dto) {
        return gateway.exchange(HttpMethod.POST, "/gatewayclasses", queryOf(dto.getClusterId()), dto,
                gateway.responseType(GatewayClassDTO.class));
    }

    /**更新 GatewayClass：PUT /gatewayclasses/{name}?clusterId */
    public GatewayClassDTO updateGatewayClass(GatewayClassDTO dto) {
        return gateway.exchange(HttpMethod.PUT, "/gatewayclasses/" + dto.getName(), queryOf(dto.getClusterId()), dto,
                gateway.responseType(GatewayClassDTO.class));
    }

    /**删除 GatewayClass：DELETE /gatewayclasses/{name}?clusterId */
    public void deleteGatewayClass(String clusterId, String name) {
        gateway.exchange(HttpMethod.DELETE, "/gatewayclasses/" + name, queryOf(clusterId), null,
                gateway.responseType(Void.class));
    }

    // ==================== 服务网格活探测（/mesh/status，只读） ====================

    /**
     * 网格**活探测**（ambient = 集群上有没有 ztunnel DaemonSet）：POST /mesh/status?clusterId。
     * <p>本端点自 2026-10-10 起只回"资源 probe"那一半 —— discovery（hasGatewayApi / hasIstio /
     * gatewayApiVersions）是 {@code k8s_cluster.capability} 列的派生，留在 platform-api
     * （{@code ClusterCapabilityService}），不再经这一跳。组装成完整 {@code MeshStatusDTO} 是
     * {@code MeshService} 的事。
     */
    public boolean meshAmbient(String clusterId) {
        AdminMeshProbeResult probe = gateway.exchange(HttpMethod.POST, "/mesh/status", queryOf(clusterId), null,
                gateway.responseType(AdminMeshProbeResult.class));
        return probe != null && probe.isIstioAmbient();
    }

    /**
     * 命名空间内 Gateway 列表（平台侧读）：POST /mesh/gateways?clusterId&namespace。
     * <p>供命名空间编辑器的 waypoint 候选 —— 命名空间是平台侧资源，而 Gateway 是租户域资源，
     * 平台管理员没有租户上下文，走不了 {@code /gateways}。
     */
    public List<GatewayDTO> namespaceGateways(String clusterId, String namespace) {
        Map<String, String> params = new LinkedHashMap<>(queryOf(clusterId));
        params.put("namespace", namespace);
        return gateway.exchange(HttpMethod.POST, "/mesh/gateways", params, null,
                gateway.listResponseType(GatewayDTO.class));
    }

    private Map<String, String> queryOf(String clusterId) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("clusterId", clusterId);
        return params;
    }

}
