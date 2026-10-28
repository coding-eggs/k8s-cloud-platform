package com.coding.platformapi.k8s;

import com.coding.common.models.k8s.dto.ComponentInstallRequestDTO;
import com.coding.common.models.k8s.dto.ComponentInstallResultDTO;
import com.coding.common.models.k8s.dto.ExternalGatewayRequestDTO;
import com.coding.common.models.k8s.dto.GatewayListenerDTO;
import com.coding.common.models.k8s.dto.OtelEsSecretRequestDTO;
import com.coding.common.models.k8s.dto.OtelServiceMonitorRequestDTO;
import com.coding.common.models.k8s.dto.OtelTelemetryRequestDTO;
import com.coding.common.models.k8s.dto.OtelTelemetryStateDTO;
import com.coding.common.models.k8s.dto.PlatformAlertingRequestDTO;
import com.coding.common.models.k8s.dto.ReleaseInstallSpecDTO;
import com.coding.common.models.k8s.dto.HarborExternalSecretsRequestDTO;
import com.coding.common.models.k8s.dto.HarborSecretsRequestDTO;
import com.coding.common.models.k8s.dto.SeaweedAdminSecretRequestDTO;
import com.coding.common.models.objectstorage.SeaweedS3ConfigRequestDTO;
import com.coding.common.models.k8s.dto.TempoObjstoreRequestDTO;
import com.coding.common.models.k8s.dto.TlsSecretRequestDTO;
import com.coding.common.models.k8s.dto.ThanosObjstoreRequestDTO;
import com.coding.common.models.k8s.dto.WorkloadProbeRequestDTO;
import com.coding.common.models.k8s.dto.WorkloadProbeResultDTO;
import com.coding.common.models.k8s.dto.WorkloadProbeTargetDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

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
     * 对外入口 Gateway CR：组件负责创建并写死 Service 绑定（{@code spec.addresses}）。
     * Gateway API CRD 缺失时 k8s-server 返回 false（跳过），不当作失败。
     */
    public boolean ensureExternalGateway(String clusterId, String namespace, String name,
                                         String serviceName, List<GatewayListenerDTO> listeners) {
        return ensureExternalGateway(clusterId, namespace, name, serviceName, listeners, false);
    }

    /**
     * @param merge true = 只把 listeners 并进已有 Gateway（按名字覆盖同名项，其余保留）。
     *              Harbor / S3 这类组件各自只带自己的 443 listener 时必须用 true，
     *              否则会把 Istio 的端口表整份覆盖掉。
     */
    public boolean ensureExternalGateway(String clusterId, String namespace, String name,
                                         String serviceName, List<GatewayListenerDTO> listeners,
                                         boolean merge) {
        ExternalGatewayRequestDTO body = new ExternalGatewayRequestDTO();
        body.setClusterId(clusterId);
        body.setNamespace(namespace);
        body.setName(name);
        body.setServiceName(serviceName);
        body.setListeners(listeners);
        body.setMerge(merge);
        Boolean ok = gateway.exchange(HttpMethod.POST, "/components/external-gateway", null, body,
                gateway.responseType(Boolean.class));
        return Boolean.TRUE.equals(ok);
    }

    /** 内置 chart 的镜像清单（k8s-server 遍历它自己的 chart 目录）。 */
    public List<com.coding.common.models.k8s.dto.ImageListItemDTO> listImages(
            String clusterId, String imageRegistryHost, String imageRegistryProject) {
        com.coding.common.models.k8s.dto.ImageRegistryQueryDTO body =
                new com.coding.common.models.k8s.dto.ImageRegistryQueryDTO();
        body.setClusterId(clusterId);
        body.setImageRegistryHost(imageRegistryHost);
        body.setImageRegistryProject(imageRegistryProject);
        // 网关的 responseType 只接受 Class：用数组接再转 List
        com.coding.common.models.k8s.dto.ImageListItemDTO[] items = gateway.exchange(
                HttpMethod.POST, "/components/images", null, body,
                gateway.responseType(com.coding.common.models.k8s.dto.ImageListItemDTO[].class));
        return items == null ? List.of() : List.of(items);
    }

    /** 把一对 PEM 写成 {@code kubernetes.io/tls} Secret（Harbor 的 HTTPS 证书）。 */
    public void applyTlsSecret(String clusterId, String namespace, String secretName,
                               String certPem, String keyPem) {
        TlsSecretRequestDTO body = new TlsSecretRequestDTO();
        body.setClusterId(clusterId);
        body.setNamespace(namespace);
        body.setSecretName(secretName);
        body.setCertPem(certPem);
        body.setKeyPem(keyPem);
        gateway.exchange(HttpMethod.POST, "/components/tls-secret", null, body,
                gateway.responseType(Void.class));
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
     * SeaweedFS 的 S3 identities 配置文件：安装/重放前必须存在（含 platform-admin 身份）。
     * 配置内容（AK/SK）由 k8s-server 在集群内维护，platform-api 不接触。
     */
    public void ensureSeaweedS3Config(String clusterId) {
        SeaweedS3ConfigRequestDTO body = new SeaweedS3ConfigRequestDTO();
        body.setClusterId(clusterId);
        gateway.exchange(HttpMethod.POST, "/object-storage/config/ensure", null, body,
                gateway.responseType(Void.class));
    }

    /**
     * SeaweedFS admin 管理台的凭证 Secret：不存在时由 k8s-server 生成随机口令（已存在不轮换）。
     * 口令不经过本层，也不会进 values 快照。
     */
    public void applySeaweedAdminSecret(String clusterId, String namespace, String secretName) {
        SeaweedAdminSecretRequestDTO body = new SeaweedAdminSecretRequestDTO();
        body.setClusterId(clusterId);
        body.setNamespace(namespace);
        body.setSecretName(secretName);
        gateway.exchange(HttpMethod.POST, "/components/seaweed-admin-secret", null, body,
                gateway.responseType(Void.class));
    }

    /**
     * Harbor 的管理口令与 secretKey：首次安装时由 k8s-server 生成随机值（已存在不轮换）。
     * 口令不经过本层，也不会进 values 快照。
     */
    public void applyHarborSecrets(String clusterId, String namespace, String secretName) {
        HarborSecretsRequestDTO body = new HarborSecretsRequestDTO();
        body.setClusterId(clusterId);
        body.setNamespace(namespace);
        body.setSecretName(secretName);
        gateway.exchange(HttpMethod.POST, "/components/harbor-secrets", null, body,
                gateway.responseType(Void.class));
    }

    /**
     * 读 Harbor 的管理口令（组件自用，只在内存里拼 Basic 头）。
     *
     * <p>不能走通用的 {@code GET /secrets/{name}}：{@code harbor} 是组件命名空间，不在租户
     * 分配表里，命名空间域端点会被边界拒成「管理员代操作需显式指定 tenantId」。
     */
    public String harborAdminPassword(String clusterId, String namespace, String secretName) {
        HarborSecretsRequestDTO body = new HarborSecretsRequestDTO();
        body.setClusterId(clusterId);
        body.setNamespace(namespace);
        body.setSecretName(secretName);
        return gateway.exchange(HttpMethod.POST, "/components/harbor-admin-password", null, body,
                gateway.responseType(String.class));
    }

    /**
     * Harbor 外部 PostgreSQL / Redis 的口令 Secret：口令只走这条通道，不经过 values。
     * 口令传 null 表示沿用已有 Secret（k8s-server 会校验它存在）。
     */
    public void applyHarborExternalSecrets(String clusterId, String namespace,
                                           boolean databaseExternal, String databaseSecretName,
                                           String databasePassword,
                                           boolean redisExternal, String redisSecretName,
                                           String redisUsername, String redisPassword) {
        HarborExternalSecretsRequestDTO body = new HarborExternalSecretsRequestDTO();
        body.setClusterId(clusterId);
        body.setNamespace(namespace);
        body.setDatabaseExternal(databaseExternal);
        body.setDatabaseSecretName(databaseSecretName);
        body.setDatabasePassword(databasePassword);
        body.setRedisExternal(redisExternal);
        body.setRedisSecretName(redisSecretName);
        body.setRedisUsername(redisUsername);
        body.setRedisPassword(redisPassword);
        gateway.exchange(HttpMethod.POST, "/components/harbor-external-secrets", null, body,
                gateway.responseType(Void.class));
    }

    /**
     * Tempo 对象存储凭证：k8s-server 读来源 Secret、归一化成 MINIO_ACCESS_KEY/MINIO_SECRET_KEY。
     * 凭证不经过本层。
     */
    public void applyTempoObjstore(String clusterId, String namespace, String secretName,
                                   String targetNamespace, String targetSecretName) {
        applyTempoObjstore(clusterId, namespace, secretName, targetNamespace, targetSecretName,
                null, null);
    }

    /**
     * Tempo 对象存储：{@code accessKey/secretKey} 非空时直接用这对凭据（平台对象存储模式 ——
     * 凭据是 platform-api 刚发放的），否则按 {@code namespace/secretName} 从集群里读来源 Secret。
     */
    public void applyTempoObjstore(String clusterId, String namespace, String secretName,
                                   String targetNamespace, String targetSecretName,
                                   String accessKey, String secretKey) {
        TempoObjstoreRequestDTO body = new TempoObjstoreRequestDTO();
        body.setClusterId(clusterId);
        body.setNamespace(namespace);
        body.setSecretName(secretName);
        body.setTargetNamespace(targetNamespace);
        body.setTargetSecretName(targetSecretName);
        body.setAccessKey(accessKey);
        body.setSecretKey(secretKey);
        gateway.exchange(HttpMethod.POST, "/components/tempo-objstore", null, body,
                gateway.responseType(Void.class));
    }

    /**
     * ES basic auth：把来源 Secret（固定 key {@code username}/{@code password}）复制成
     * collector 读的 {@code platform-otel-es}。密码不经过本层。
     *
     * <p>{@code collectorName} 是 operator 生成 Pod 的那个 CR：凭证变了由 k8s-server 改它的
     * {@code spec.podAnnotations}，让 operator 自己滚动（collector 只在启动时读 env）。
     */
    public void applyOtelEsSecret(String clusterId, String namespace, String secretName,
                                  String targetNamespace, String targetSecretName, String collectorName) {
        OtelEsSecretRequestDTO body = new OtelEsSecretRequestDTO();
        body.setClusterId(clusterId);
        body.setNamespace(namespace);
        body.setSecretName(secretName);
        body.setTargetNamespace(targetNamespace);
        body.setTargetSecretName(targetSecretName);
        body.setCollectorName(collectorName);
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

    /**
     * Istio Telemetry（ingress/waypoint tracing）：enabled=false 时删除。
     * 返回 false 表示集群没有 Telemetry CRD 或目标 Gateway（跳过）。
     */
    public boolean applyOtelTelemetry(String clusterId, String namespace, String name,
                                      String gatewayName, int samplingPercentage, boolean enabled) {
        OtelTelemetryRequestDTO body = new OtelTelemetryRequestDTO();
        body.setClusterId(clusterId);
        body.setNamespace(namespace);
        body.setName(name);
        body.setGatewayName(gatewayName);
        body.setSamplingPercentage(samplingPercentage);
        body.setEnabled(enabled);
        Boolean ok = gateway.exchange(HttpMethod.POST, "/components/telemetry", null, body,
                gateway.responseType(Boolean.class));
        return Boolean.TRUE.equals(ok);
    }

    /** 查询某条 Telemetry 是否存在及其采样率（waypoint 编辑页回填）。 */
    public OtelTelemetryStateDTO otelTelemetryState(String clusterId, String namespace, String name) {
        Map<String, String> params = Map.of(
                "clusterId", clusterId, "namespace", namespace, "name", name);
        return gateway.exchange(HttpMethod.GET, "/components/telemetry", params, null,
                gateway.responseType(OtelTelemetryStateDTO.class));
    }
}
