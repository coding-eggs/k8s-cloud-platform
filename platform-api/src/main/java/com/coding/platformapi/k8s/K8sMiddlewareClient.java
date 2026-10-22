package com.coding.platformapi.k8s;

import com.coding.common.models.middleware.MiddlewareCreateRequestDTO;
import com.coding.common.models.middleware.MiddlewareDeleteRequestDTO;
import com.coding.common.models.middleware.MiddlewareOperatorInstallRequestDTO;
import com.coding.common.models.middleware.MiddlewareOperatorStatusDTO;
import com.coding.common.models.middleware.MiddlewareOperatorStatusRequestDTO;
import com.coding.common.models.middleware.MiddlewareScaleRequestDTO;
import com.coding.common.models.middleware.MiddlewareStatusRequestDTO;
import com.coding.common.models.middleware.MiddlewareStatusResultDTO;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;

import java.util.List;

/** 平台 → k8s-server 的中间件执行链。创建/删除 CR 走慢速通道（Operator 可能要拉镜像/建 PVC）。 */
@Component
public class K8sMiddlewareClient {

    private final K8sServerGateway gateway;

    public K8sMiddlewareClient(K8sServerGateway gateway) {
        this.gateway = gateway;
    }

    public List<MiddlewareOperatorStatusDTO> operatorStatus(MiddlewareOperatorStatusRequestDTO body) {
        return gateway.exchange(HttpMethod.POST, "/middleware/operators/status", null, body,
                gateway.listResponseType(MiddlewareOperatorStatusDTO.class));
    }

    public MiddlewareOperatorStatusDTO operatorInstall(MiddlewareOperatorInstallRequestDTO body) {
        return gateway.exchangeSlow(HttpMethod.POST, "/middleware/operators/install", null, body,
                gateway.responseType(MiddlewareOperatorStatusDTO.class));
    }

    public Boolean create(String code, MiddlewareCreateRequestDTO body) {
        return gateway.exchangeSlow(HttpMethod.POST, "/middleware/" + code + "/instances", null, body,
                gateway.responseType(Boolean.class));
    }

    public Boolean scale(String code, MiddlewareScaleRequestDTO body) {
        return gateway.exchangeSlow(HttpMethod.POST, "/middleware/" + code + "/instances/scale", null, body,
                gateway.responseType(Boolean.class));
    }

    public Boolean delete(String code, MiddlewareDeleteRequestDTO body) {
        return gateway.exchangeSlow(HttpMethod.POST, "/middleware/" + code + "/instances/delete", null, body,
                gateway.responseType(Boolean.class));
    }

    public List<MiddlewareStatusResultDTO> status(String code, MiddlewareStatusRequestDTO body) {
        return gateway.exchange(HttpMethod.POST, "/middleware/" + code + "/status", null, body,
                gateway.listResponseType(MiddlewareStatusResultDTO.class));
    }
}
