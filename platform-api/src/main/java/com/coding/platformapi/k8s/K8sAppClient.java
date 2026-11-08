package com.coding.platformapi.k8s;

import com.coding.common.models.app.AppApplyRequestDTO;
import com.coding.common.models.app.AppInitRequestDTO;
import com.coding.common.models.app.AppInitResultDTO;
import com.coding.common.models.app.AppPruneRequestDTO;
import com.coding.common.models.app.AppRenderRequestDTO;
import com.coding.common.models.app.AppRenderResultDTO;
import com.coding.common.models.app.AppStatusRequestDTO;
import com.coding.common.models.app.AppStatusResultDTO;
import com.coding.common.models.k8s.dto.IngressListenerSetRequestDTO;
import com.coding.common.models.k8s.dto.IngressListenerSetStatusDTO;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** 应用编排的集群侧调用（渲染 / 落盘 / 清理 / 状态）。 */
@Component
public class K8sAppClient {

    private final K8sServerGateway gateway;

    public K8sAppClient(K8sServerGateway gateway) {
        this.gateway = gateway;
    }

    public AppRenderResultDTO render(AppRenderRequestDTO body) {
        return gateway.exchange(HttpMethod.POST, "/application/render", Map.of(), body,
                gateway.responseType(AppRenderResultDTO.class));
    }

    public void apply(AppApplyRequestDTO body) {
        gateway.exchange(HttpMethod.POST, "/application/apply", Map.of(), body,
                gateway.responseType(Void.class));
    }

    public Integer prune(AppPruneRequestDTO body) {
        return gateway.exchange(HttpMethod.POST, "/application/prune", Map.of(), body,
                gateway.responseType(Integer.class));
    }

    public AppInitResultDTO init(AppInitRequestDTO body) {
        // 初始化最长等 10 分钟，常规通道的 120s 读超时会提前掐断
        return gateway.exchangeSlow(HttpMethod.POST, "/application/init", Map.of(), body,
                gateway.responseType(AppInitResultDTO.class));
    }

    public AppStatusResultDTO status(AppStatusRequestDTO body) {
        return gateway.exchange(HttpMethod.POST, "/application/status", Map.of(), body,
                gateway.responseType(AppStatusResultDTO.class));
    }

    /** 某个 Service 声明的端口（应用端口池只分配网关 Service 上已声明的端口）；Service 不存在返回空列表。 */
    public List<Integer> servicePorts(String clusterId, String namespace, String name) {
        Map<String, String> params = Map.of("clusterId", clusterId, "namespace", namespace, "name", name);
        List<Integer> ports = gateway.exchange(HttpMethod.GET, "/application/service-ports", params, null,
                gateway.listResponseType(Integer.class));
        return ports == null ? List.of() : ports;
    }

    /** 集群里所有 Service 已占用的 nodePort。 */
    public List<com.coding.common.models.app.NodePortUseDTO> usedNodePorts(String clusterId) {
        List<com.coding.common.models.app.NodePortUseDTO> out = gateway.exchange(HttpMethod.GET,
                "/application/node-ports", Map.of("clusterId", clusterId), null,
                gateway.listResponseType(com.coding.common.models.app.NodePortUseDTO.class));
        return out == null ? List.of() : out;
    }

    /** 下发/删除命名空间的应用端口池 ListenerSet；ListenerSet CRD 缺失返回 false。 */
    public boolean applyIngressListenerSet(IngressListenerSetRequestDTO body) {
        Boolean ok = gateway.exchange(HttpMethod.POST, "/application/ingress-listenerset", Map.of(), body,
                gateway.responseType(Boolean.class));
        return Boolean.TRUE.equals(ok);
    }

    /** 命名空间应用端口池 ListenerSet 的状态（每个 listener 是否生效 / 冲突）。 */
    public IngressListenerSetStatusDTO ingressListenerSetStatus(String clusterId, String namespace, String name) {
        Map<String, String> params = Map.of("clusterId", clusterId, "namespace", namespace, "name", name);
        return gateway.exchange(HttpMethod.GET, "/application/ingress-listenerset", params, null,
                gateway.responseType(IngressListenerSetStatusDTO.class));
    }
}
