package com.coding.platformapi.k8s;

import com.coding.common.models.k8s.dto.ComponentInstallRequestDTO;
import com.coding.common.models.k8s.dto.ComponentInstallResultDTO;
import com.coding.common.models.k8s.dto.OtelEsSecretRequestDTO;
import com.coding.common.models.k8s.dto.OtelServiceMonitorRequestDTO;
import com.coding.common.models.k8s.dto.PlatformAlertingRequestDTO;
import com.coding.common.models.k8s.dto.ReleaseInstallSpecDTO;
import com.coding.common.models.k8s.dto.TempoObjstoreRequestDTO;
import com.coding.common.models.k8s.dto.ThanosObjstoreRequestDTO;
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

    /**
     * 平台告警联动：下发 Secret（共享密钥）+ AlertmanagerConfig（route/receivers）。
     * 幂等 SSA；token 只在这一跳出现，**不进 helm values**。
     */
    public void applyPlatformAlerting(String clusterId, String namespace, String webhookUrl, String token) {
        PlatformAlertingRequestDTO body = new PlatformAlertingRequestDTO();
        body.setClusterId(clusterId);
        body.setNamespace(namespace);
        body.setWebhookUrl(webhookUrl);
        body.setToken(token);
        gateway.exchange(HttpMethod.POST, "/components/platform-alerting", null, body,
                gateway.responseType(Void.class));
    }

    /**
     * Thanos 对象存储：k8s-server 在集群内读凭证 Secret、拼 {@code objstore.yml} 并写成目标 Secret。
     * 凭证不经过本层。
     */
    public void applyThanosObjstore(String clusterId, String namespace, String secretName,
                                    String endpoint, String bucket, boolean insecureSkipVerify,
                                    String targetNamespace, String targetSecretName) {
        ThanosObjstoreRequestDTO body = new ThanosObjstoreRequestDTO();
        body.setClusterId(clusterId);
        body.setNamespace(namespace);
        body.setSecretName(secretName);
        body.setEndpoint(endpoint);
        body.setBucket(bucket);
        body.setInsecureSkipVerify(insecureSkipVerify);
        body.setTargetNamespace(targetNamespace);
        body.setTargetSecretName(targetSecretName);
        gateway.exchange(HttpMethod.POST, "/components/thanos-objstore", null, body,
                gateway.responseType(Void.class));
    }

    /**
     * Tempo 对象存储凭证：k8s-server 读来源 Secret、归一化成 MINIO_ACCESS_KEY/MINIO_SECRET_KEY。
     * 凭证不经过本层。
     */
    public void applyTempoObjstore(String clusterId, String namespace, String secretName,
                                   String targetNamespace, String targetSecretName) {
        TempoObjstoreRequestDTO body = new TempoObjstoreRequestDTO();
        body.setClusterId(clusterId);
        body.setNamespace(namespace);
        body.setSecretName(secretName);
        body.setTargetNamespace(targetNamespace);
        body.setTargetSecretName(targetSecretName);
        gateway.exchange(HttpMethod.POST, "/components/tempo-objstore", null, body,
                gateway.responseType(Void.class));
    }

    /**
     * ES basic auth：把来源 Secret 的密码归一化成 collector 读的 platform-otel-es。
     * 密码不经过本层。
     */
    public void applyOtelEsSecret(String clusterId, String namespace, String secretName,
                                  String passwordKey, String username,
                                  String targetNamespace, String targetSecretName) {
        OtelEsSecretRequestDTO body = new OtelEsSecretRequestDTO();
        body.setClusterId(clusterId);
        body.setNamespace(namespace);
        body.setSecretName(secretName);
        body.setPasswordKey(passwordKey);
        body.setUsername(username);
        body.setTargetNamespace(targetNamespace);
        body.setTargetSecretName(targetSecretName);
        gateway.exchange(HttpMethod.POST, "/components/otel-es-secret", null, body,
                gateway.responseType(Void.class));
    }

    /**
     * collector 的 ServiceMonitor；返回 false 表示集群没有 {@code monitoring.coreos.com} CRD（跳过）。
     * 调用方应把异常/失败当作提示而不是安装失败。
     */
    public boolean applyOtelServiceMonitor(String clusterId, String namespace) {
        OtelServiceMonitorRequestDTO body = new OtelServiceMonitorRequestDTO();
        body.setClusterId(clusterId);
        body.setNamespace(namespace);
        Boolean created = gateway.exchange(HttpMethod.POST, "/components/otel-servicemonitor", null, body,
                gateway.responseType(Boolean.class));
        return Boolean.TRUE.equals(created);
    }
}
