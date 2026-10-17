package com.coding.platformapi.k8s;

import com.coding.common.models.k8s.dto.ComponentInstallRequestDTO;
import com.coding.common.models.k8s.dto.ComponentInstallResultDTO;
import com.coding.common.models.k8s.dto.ReleaseInstallSpecDTO;
import com.coding.common.models.k8s.dto.WorkloadProbeRequestDTO;
import com.coding.common.models.k8s.dto.WorkloadProbeResultDTO;
import com.coding.common.models.k8s.dto.WorkloadProbeTargetDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * k8s-server 的组件探测专用端点 client（{@code POST /workload-probes}）。
 *
 * <p>该端点在 k8s-server 侧是 {@code UPSTREAM} 边界（租户可达），收窄在本层 —— 各自功能页的权限码。
 */
@Component
@RequiredArgsConstructor
public class K8sComponentClient {

    private final K8sServerGateway gateway;

    /** POST /workload-probes：按候选读工作负载状态（返回顺序与 targets 一致） */
    public List<WorkloadProbeResultDTO> probe(String clusterId, List<WorkloadProbeTargetDTO> targets) {
        WorkloadProbeRequestDTO body = new WorkloadProbeRequestDTO();
        body.setClusterId(clusterId);
        body.setTargets(targets);
        return gateway.exchange(HttpMethod.POST, "/workload-probes", null, body,
                gateway.listResponseType(WorkloadProbeResultDTO.class));
    }

    /**
     * 安装/升级（长任务）：POST /components/apply。
     * 用**慢速通道**（30 分钟读超时）—— helm 装一个栈可能要几分钟，常规 120s 会提前断开。
     */
    public ComponentInstallResultDTO applyComponent(String clusterId, String componentCode, String action,
                                                    List<ReleaseInstallSpecDTO> releases) {
        ComponentInstallRequestDTO body = new ComponentInstallRequestDTO();
        body.setClusterId(clusterId);
        // ⚠️ componentCode/action 缺一不可：k8s-server 用 componentCode 拼 release 标签，
        //    漏了会在 release secret 上留下 component=null（评审发现过）
        body.setComponentCode(componentCode);
        body.setAction(action);
        body.setReleases(releases);
        return gateway.exchangeSlow(HttpMethod.POST, "/components/apply", null, body,
                gateway.responseType(ComponentInstallResultDTO.class));
    }
}
