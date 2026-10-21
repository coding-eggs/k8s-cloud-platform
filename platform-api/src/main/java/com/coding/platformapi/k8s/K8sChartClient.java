package com.coding.platformapi.k8s;

import com.coding.common.models.k8s.dto.ChartApplyRequestDTO;
import com.coding.common.models.k8s.dto.ChartApplyResultDTO;
import com.coding.common.models.k8s.dto.ChartInspectRequestDTO;
import com.coding.common.models.k8s.dto.ChartInspectResultDTO;
import com.coding.common.models.k8s.dto.ChartPreviewRequestDTO;
import com.coding.common.models.k8s.dto.ChartPreviewResultDTO;
import com.coding.common.models.k8s.dto.ChartUninstallRequestDTO;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;

/** 平台 → k8s-server 的 chart 执行链（安装/卸载走慢速通道——helm 可能要几分钟）。 */
@Component
public class K8sChartClient {

    private final K8sServerGateway gateway;

    public K8sChartClient(K8sServerGateway gateway) {
        this.gateway = gateway;
    }

    public ChartInspectResultDTO inspect(ChartInspectRequestDTO body) {
        return gateway.exchange(HttpMethod.POST, "/chart/inspect", null, body,
                gateway.responseType(ChartInspectResultDTO.class));
    }

    public ChartPreviewResultDTO preview(ChartPreviewRequestDTO body) {
        return gateway.exchange(HttpMethod.POST, "/chart/preview", null, body,
                gateway.responseType(ChartPreviewResultDTO.class));
    }

    public ChartApplyResultDTO apply(ChartApplyRequestDTO body) {
        return gateway.exchangeSlow(HttpMethod.POST, "/chart/apply", null, body,
                gateway.responseType(ChartApplyResultDTO.class));
    }

    public Boolean uninstall(ChartUninstallRequestDTO body) {
        return gateway.exchangeSlow(HttpMethod.POST, "/chart/uninstall", null, body,
                gateway.responseType(Boolean.class));
    }
}
