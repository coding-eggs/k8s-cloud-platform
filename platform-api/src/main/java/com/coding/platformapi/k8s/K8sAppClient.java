package com.coding.platformapi.k8s;

import com.coding.common.models.app.AppApplyRequestDTO;
import com.coding.common.models.app.AppPruneRequestDTO;
import com.coding.common.models.app.AppRenderRequestDTO;
import com.coding.common.models.app.AppRenderResultDTO;
import com.coding.common.models.app.AppStatusRequestDTO;
import com.coding.common.models.app.AppStatusResultDTO;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;

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

    public AppStatusResultDTO status(AppStatusRequestDTO body) {
        return gateway.exchange(HttpMethod.POST, "/application/status", Map.of(), body,
                gateway.responseType(AppStatusResultDTO.class));
    }
}
