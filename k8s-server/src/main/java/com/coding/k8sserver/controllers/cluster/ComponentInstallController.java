package com.coding.k8sserver.controllers.cluster;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.k8s.dto.ComponentInstallRequestDTO;
import com.coding.common.models.k8s.dto.ComponentInstallResultDTO;
import com.coding.common.models.k8s.dto.ExternalGatewayRequestDTO;
import com.coding.common.models.k8s.dto.GatewayListenerDTO;
import com.coding.common.models.k8s.dto.HarborSecretsRequestDTO;
import com.coding.common.models.k8s.dto.OtelEsSecretRequestDTO;
import com.coding.common.models.k8s.dto.OtelServiceMonitorRequestDTO;
import com.coding.common.models.k8s.dto.OtelTelemetryRequestDTO;
import com.coding.common.models.k8s.dto.OtelTelemetryStateDTO;
import com.coding.common.models.k8s.dto.PlatformAlertingRequestDTO;
import com.coding.common.models.k8s.dto.ReleaseInstallSpecDTO;
import com.coding.common.models.k8s.dto.SeaweedAdminSecretRequestDTO;
import com.coding.common.models.k8s.dto.TempoObjstoreRequestDTO;
import com.coding.common.models.k8s.dto.ThanosObjstoreRequestDTO;
import com.coding.common.models.system.ResponseData;
import com.coding.k8score.factory.KubernetesClientFactory;
import com.coding.k8score.operations.ServerSideApply;
import com.coding.k8sserver.components.AccessBoundary;
import com.coding.k8sserver.components.AccessBoundaryAware;
import com.coding.k8sserver.components.ExternalGatewayObjects;
import com.coding.k8sserver.components.HarborSecretObjects;
import com.coding.k8sserver.components.PlatformAlertingObjects;
import com.coding.k8sserver.components.ResourceAccessResolver;
import com.coding.k8sserver.components.SecretConsumers;
import com.coding.k8sserver.components.SeaweedAdminSecretObjects;
import com.coding.k8sserver.components.OtelEsSecretObjects;
import com.coding.k8sserver.components.OtelServiceMonitorObjects;
import com.coding.k8sserver.components.OtelTelemetryObjects;
import com.coding.k8sserver.components.TempoObjstoreObjects;
import com.coding.k8sserver.components.ThanosObjstoreObjects;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import com.coding.k8sserver.helm.ChartBundle;
import com.coding.k8sserver.helm.HelmResult;
import com.coding.k8sserver.helm.HelmRunner;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 集群域 - 组件安装/升级（S3.6，**PLATFORM 边界**）。
 *
 * <p>这是平台唯一一处"自己往集群写非资源对象"的入口（helm）。授权在上游 platform-api
 * （{@code platform:cluster:manage}），本层只声明边界并执行。
 *
 * <p>为什么单开一个类：{@code BoundaryAuthorizationManager} 读 handler 所属类；挂到
 * 集群管理的 PLATFORM 类上虽然边界相同，但安装动作的审计与语义不同，独立成类更清楚。
 *
 * <p><b>失败即停</b>：多 release 的 recipe 里后一个通常依赖前一个（Prometheus → Thanos），
 * 前一个失败还继续装第二个只会留下半个栈。
 */
@Tag(name = "集群域-组件安装", description = "安装/升级集群组件（helm，平台管理员）")
@Slf4j
@RestController
@RequestMapping("/components")
public class ComponentInstallController implements AccessBoundaryAware {

    /** values 以 JSON 写入 .yaml 文件 —— JSON 是合法的 YAML 子集，helm 能正确解析。 */
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final tools.jackson.dataformat.yaml.YAMLMapper YAML =
            tools.jackson.dataformat.yaml.YAMLMapper.builder().build();

    /** 渲染结果直接落盘（超大 chart，不建 Helm release）；取值与 platform-api 共用一份 */
    private static final String APPLY_SERVER_SIDE = ReleaseInstallSpecDTO.APPLY_SERVER_SIDE;

    private final HelmRunner helm;
    private final ChartBundle charts;
    private final KubernetesClientFactory clientFactory;
    private final ResourceAccessResolver accessResolver;

    public ComponentInstallController(HelmRunner helm, ChartBundle charts,
                                      KubernetesClientFactory clientFactory,
                                      ResourceAccessResolver accessResolver) {
        this.helm = helm;
        this.charts = charts;
        this.clientFactory = clientFactory;
        this.accessResolver = accessResolver;
    }

    @Override
    public AccessBoundary accessBoundary() {
        return AccessBoundary.PLATFORM;
    }

    @PostMapping("/apply")
    @Operation(summary = "安装/升级组件（helm upgrade --install）",
            description = "先 helm template 硬预检，再执行；每个 release 独立回报成功/失败")
    public ResponseData<ComponentInstallResultDTO> apply(@RequestBody ComponentInstallRequestDTO request) {
        accessResolver.assertClusterAccess(request.getClusterId());
        if (request.getReleases() == null || request.getReleases().isEmpty()) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "没有要安装的 release");
        }
        String kubeconfig = clientFactory.adminKubeconfig(request.getClusterId());

        List<ComponentInstallResultDTO.ReleaseStatusDTO> statuses = new ArrayList<>();
        boolean allOk = true;
        String firstError = null;

        for (ReleaseInstallSpecDTO spec : request.getReleases()) {
            ComponentInstallResultDTO.ReleaseStatusDTO st = new ComponentInstallResultDTO.ReleaseStatusDTO();
            st.setReleaseName(spec.getReleaseName());
            st.setChartVersion(spec.getChartVersion());
            Path valuesFile = null;
            try {
                Path chartDir = charts.chartDir(spec.getChartCode(), spec.getChartVersion());
                valuesFile = writeValues(spec.getValues());

                // 超大 chart 的清单要**完整**：它会被原样写进集群，截断（64KB）会静默少装对象。
                // 同时必须按**集群真实**的 kube/API 版本渲染：helm template 默认用 helm 自带的
                // capabilities（4.2.0 ≈ k8s 1.35），会把 MutatingAdmissionPolicy 渲染成 v1beta1，
                // 而 1.36+ 只认 v1（2026-10-22 实测 apply 报 no matches for kind）。
                boolean directApply = APPLY_SERVER_SIDE.equals(spec.getApplyMode());
                HelmResult rendered;
                if (directApply) {
                    KubernetesClient client = clientFactory.getAdminClient(request.getClusterId());
                    rendered = helm.renderFull(kubeconfig, chartDir, valuesFile, spec.getNamespace(),
                            kubeVersion(client), admissionPolicyApiVersions(client));
                } else {
                    rendered = helm.render(kubeconfig, chartDir, valuesFile, spec.getNamespace());
                }
                if (!rendered.ok()) {
                    st.setStatus("FAILED");
                    st.setMessage("预检失败：" + rendered.output());
                    statuses.add(st);
                    allOk = false;
                    if (firstError == null) {
                        firstError = "预检失败：" + rendered.output();
                    }
                    break; // 失败即停
                }
                // 超大 chart（Calico CRD）：不建 Helm release（Secret 1MiB 上限存不下整个 chart），
                // 直接 server-side apply；并等 CRD Established 再装下一条
                // （否则下一条里的 CR 会报 no matches for kind）。2026-10-22 实测。
                if (directApply) {
                    HelmResult applied = helm.applyServerSide(kubeconfig, rendered.output());
                    if (!applied.ok()) {
                        st.setStatus("FAILED");
                        st.setMessage(applied.output());
                        statuses.add(st);
                        allOk = false;
                        if (firstError == null) {
                            firstError = applied.output();
                        }
                        break; // 失败即停
                    }
                    List<String> crds = crdNames(rendered.output());
                    HelmResult waited = crds.isEmpty() ? null : helm.waitCrdsEstablished(kubeconfig, crds);
                    boolean waitOk = waited == null || waited.ok();
                    st.setStatus(waitOk ? "SUCCESS" : "FAILED");
                    st.setMessage(waitOk
                            ? "已用 server-side apply 落盘（不建 Helm release）"
                            : "CRD 已写入，但未在超时内 Established：" + waited.output());
                    statuses.add(st);
                    if (!waitOk) {
                        allOk = false;
                        if (firstError == null) {
                            firstError = st.getMessage();
                        }
                        break;
                    }
                    continue;
                }
                // 服务端 dry-run：只把输出记为警告，不阻断（CRD 假阴性见 spec §3.1）
                HelmResult dry = helm.dryRunServer(kubeconfig, chartDir, valuesFile,
                        spec.getReleaseName(), spec.getNamespace());
                if (!dry.ok()) {
                    st.setMessage("服务端预检警告（不阻断）：" + dry.output());
                }
                HelmResult applied = helm.apply(kubeconfig, chartDir, valuesFile,
                        spec.getReleaseName(), spec.getNamespace(), labels(request));
                st.setStatus(applied.ok() ? "SUCCESS" : "FAILED");
                st.setMessage(applied.ok() ? st.getMessage() : applied.output());
                statuses.add(st);
                if (!applied.ok()) {
                    allOk = false;
                    if (firstError == null) {
                        firstError = applied.output();
                    }
                    break; // 失败即停
                }
            } catch (Exception e) {
                st.setStatus("FAILED");
                st.setMessage(e.getMessage());
                statuses.add(st);
                allOk = false;
                if (firstError == null) {
                    firstError = e.getMessage();
                }
                break;
            } finally {
                if (valuesFile != null) {
                    try {
                        Files.deleteIfExists(valuesFile);
                    } catch (Exception ignored) {
                        // 与 kubeconfig 同样的原则：清理失败不改变结果
                    }
                }
            }
        }

        ComponentInstallResultDTO out = new ComponentInstallResultDTO();
        out.setSuccess(allOk);
        out.setMessage(firstError);
        out.setReleases(statuses);
        return new ResponseData<>(out);
    }

    /**
     * 平台告警联动（S3.6 批②）：把 Alertmanager 接回平台。
     *
     * <p>两个固定名字的对象，幂等 SSA：Secret（共享密钥）+ AlertmanagerConfig（route/receivers）。
     * token 只在这一跳出现，**不进 helm values** —— 否则它会落进操作记录的 values_snapshot（DB 明文）。
     */
    @PostMapping("/platform-alerting")
    @Operation(summary = "下发平台告警联动对象（Secret + AlertmanagerConfig）",
            description = "固定对象名、幂等 SSA；由 platform-api 在 Prometheus 安装成功后调用")
    public ResponseData<Void> platformAlerting(@RequestBody PlatformAlertingRequestDTO request) {
        accessResolver.assertClusterAccess(request.getClusterId());
        if (request.getNamespace() == null || request.getNamespace().isBlank()
                || request.getWebhookUrl() == null || request.getWebhookUrl().isBlank()
                || request.getToken() == null || request.getToken().isBlank()) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "平台告警联动参数不完整");
        }
        var client = clientFactory.getAdminClient(request.getClusterId());
        client.secrets().inNamespace(request.getNamespace())
                .resource(PlatformAlertingObjects.secret(request.getNamespace(), request.getToken()))
                .fieldManager(ServerSideApply.FIELD_MANAGER).forceConflicts().serverSideApply();
        client.genericKubernetesResources(PlatformAlertingObjects.ALERTMANAGER_CONFIG_CRD)
                .inNamespace(request.getNamespace())
                .resource(PlatformAlertingObjects.alertmanagerConfig(
                        request.getNamespace(), request.getWebhookUrl()))
                .fieldManager(ServerSideApply.FIELD_MANAGER).forceConflicts().serverSideApply();
        return new ResponseData<>();
    }

    /**
     * Thanos 对象存储配置（S3.6）：读已有凭证 Secret → 拼 {@code objstore.yml} → 写成 chart 挂载的 Secret。
     *
     * <p>凭证只在集群内流动：platform-api 只传 Secret 名与 MinIO 地址/桶，读值和拼装都在这里，
     * 既不经过 platform-api，也不会进操作记录的 values 快照。
     */
    @PostMapping("/thanos-objstore")
    @Operation(summary = "下发 Thanos 对象存储配置（凭证 Secret → objstore.yml）",
            description = "幂等 SSA；由 platform-api 在管理集群安装 Prometheus/Thanos 前调用")
    public ResponseData<Void> thanosObjstore(@RequestBody ThanosObjstoreRequestDTO request) {
        accessResolver.assertClusterAccess(request.getClusterId());
        if (isBlank(request.getNamespace()) || isBlank(request.getSecretName())
                || isBlank(request.getEndpoint()) || isBlank(request.getBucket())
                || isBlank(request.getTargetNamespace()) || isBlank(request.getTargetSecretName())) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "Thanos 对象存储参数不完整");
        }
        var client = clientFactory.getAdminClient(request.getClusterId());
        Secret source = client.secrets().inNamespace(request.getNamespace())
                .withName(request.getSecretName()).get();
        if (source == null) {
            throw new CloudPlatformException(EnumResponseType.ERROR,
                    "凭证 Secret 不存在：" + request.getNamespace() + "/" + request.getSecretName());
        }
        String[] credentials = ThanosObjstoreObjects.findCredentials(source.getData());
        if (credentials == null) {
            throw new CloudPlatformException(EnumResponseType.ERROR,
                    "凭证 Secret 缺少可识别的 access key / secret key（支持："
                            + ThanosObjstoreObjects.supportedKeyPairs() + "）");
        }
        String objstore = ThanosObjstoreObjects.objstoreYaml(request.getEndpoint(), request.getBucket(),
                credentials[0], credentials[1], request.isInsecureSkipVerify());

        Secret target = new SecretBuilder()
                .withNewMetadata()
                .withName(request.getTargetSecretName())
                .withNamespace(request.getTargetNamespace())
                .addToLabels("platform.coding/managed", "true")
                .addToLabels("platform.coding/component", "prometheus-operator")
                .endMetadata()
                .withType("Opaque")
                .addToStringData(ThanosObjstoreObjects.OBJSTORE_KEY, objstore)
                .build();
        client.secrets().inNamespace(request.getTargetNamespace())
                .resource(target)
                .fieldManager(ServerSideApply.FIELD_MANAGER).forceConflicts().serverSideApply();

        // 改了 S3 地址 / 桶 / 凭证 → 消费方（compactor / receive / storegateway）必须重启：
        // 它们只在启动时读 --objstore.config-file，而 chart 的 checksum 注解算的是
        // global.objstore.config（平台没设，恒为默认值），helm upgrade 因此不会滚动 Pod。
        // 2026-10-26 用户实报：改完地址 helm 成功、Pod 没动，compactor/receive 继续往旧桶写。
        rollSecretConsumers(client, request.getTargetNamespace(), request.getTargetSecretName(),
                SecretConsumers.hashOf(objstore));
        return new ResponseData<>();
    }

    /**
     * 配置 Secret 写完后让读它的工作负载重新读一次（幂等：模板上的 config-hash 已等于本次内容时不动；
     * 没有这个注解的旧 Pod 会被纠一次）。
     *
     * <p>失败**不吞**：Secret 已经是新配置而 Pod 还是旧的，正是"改了不生效"那种最难查的状态。
     */
    private static void rollSecretConsumers(KubernetesClient client, String namespace, String secretName,
                                            String contentHash) {
        List<String> rolled;
        try {
            rolled = SecretConsumers.alignConsumers(client, namespace, secretName, contentHash);
        } catch (Exception e) {
            throw new CloudPlatformException(EnumResponseType.ERROR.getCode(),
                    "配置 Secret 已更新，但滚动消费方失败（Pod 仍在跑旧配置，需人工处理）：" + e.getMessage(), e);
        }
        if (!rolled.isEmpty()) {
            log.info("配置 Secret 变更，已滚动重启消费方（{}/{}）：{}", namespace, secretName, rolled);
        }
    }

    /**
     * SeaweedFS admin 管理台的登录凭证 Secret（S3.6）：不存在则生成随机口令，已存在则原样保留。
     *
     * <p>口令只在集群内生成与保存，不经过 platform-api；values 里只用
     * {@code admin.secret.existingSecret} 引用它，因此不会进操作记录的 values 快照。
     */
    @PostMapping("/seaweed-admin-secret")
    @Operation(summary = "确保 SeaweedFS admin 凭证 Secret 存在（随机口令，已存在不轮换）",
            description = "幂等；由 platform-api 在安装/升级开启 admin 管理台的 SeaweedFS 前调用")
    public ResponseData<Void> seaweedAdminSecret(@RequestBody SeaweedAdminSecretRequestDTO request) {
        accessResolver.assertClusterAccess(request.getClusterId());
        if (isBlank(request.getNamespace()) || isBlank(request.getSecretName())) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "SeaweedFS admin Secret 参数不完整");
        }
        var client = clientFactory.getAdminClient(request.getClusterId());
        Secret existing = client.secrets().inNamespace(request.getNamespace())
                .withName(request.getSecretName()).get();
        if (existing != null && SeaweedAdminSecretObjects.hasCredentials(existing.getData())) {
            return new ResponseData<>();   // 已存在：升级/重装沿用原口令，不轮换
        }
        client.secrets().inNamespace(request.getNamespace())
                .resource(SeaweedAdminSecretObjects.build(request.getNamespace(), request.getSecretName()))
                .fieldManager(ServerSideApply.FIELD_MANAGER).forceConflicts().serverSideApply();
        return new ResponseData<>();
    }

    /**
     * Harbor 的管理口令与 secretKey（S3.6）：首次安装时生成随机值，已存在则原样保留。
     *
     * <p>⚠️ 已初始化（{@code harbor-core} 存在）但 Secret 缺失时**拒绝**：
     * 重新生成的口令与 Harbor 库里的哈希不一致（secretKey 还牵连库里已加密字段），
     * 同步必然 401。这时只能人工按 Harbor 官方方式重置后再安装。
     */
    @PostMapping("/harbor-secrets")
    @Operation(summary = "确保 Harbor 管理口令 Secret 存在（首次生成，已初始化后丢失则拒绝）",
            description = "幂等；由 platform-api 在安装/升级 Harbor 前调用")
    public ResponseData<Void> harborSecrets(@RequestBody HarborSecretsRequestDTO request) {
        accessResolver.assertClusterAccess(request.getClusterId());
        if (isBlank(request.getNamespace()) || isBlank(request.getSecretName())) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "Harbor Secret 参数不完整");
        }
        var client = clientFactory.getAdminClient(request.getClusterId());
        Secret existing = client.secrets().inNamespace(request.getNamespace())
                .withName(request.getSecretName()).get();
        if (existing != null && HarborSecretObjects.hasAllKeys(existing.getData())) {
            return new ResponseData<>();   // 已存在：升级/重装沿用原口令，不轮换
        }
        boolean initialized = client.apps().deployments()
                .inNamespace(request.getNamespace()).withName("harbor-core").get() != null;
        if (initialized) {
            throw new CloudPlatformException(EnumResponseType.ERROR,
                    "管理口令 Secret 丢失，Harbor 已初始化：重新生成的口令与库里的哈希不一致。"
                            + "请人工按 Harbor 官方方式重置管理员口令 / secretKey 后再安装");
        }
        client.secrets().inNamespace(request.getNamespace())
                .resource(HarborSecretObjects.build(request.getNamespace(), request.getSecretName()))
                .fieldManager(ServerSideApply.FIELD_MANAGER).forceConflicts().serverSideApply();
        return new ResponseData<>();
    }

    /**
     * Tempo 的对象存储凭证（OTel 批次一）：读来源 Secret → 归一化成
     * {@code MINIO_ACCESS_KEY} / {@code MINIO_SECRET_KEY} → 写成 Tempo 挂载的 Secret。
     *
     * <p>与 Thanos 的 objstore 同一原则：凭证只在集群内转换。
     */
    @PostMapping("/tempo-objstore")
    @Operation(summary = "下发 Tempo 对象存储凭证 Secret",
            description = "幂等 SSA；由 platform-api 在管理集群安装 Tempo 前调用")
    public ResponseData<Void> tempoObjstore(@RequestBody TempoObjstoreRequestDTO request) {
        accessResolver.assertClusterAccess(request.getClusterId());
        if (isBlank(request.getNamespace()) || isBlank(request.getSecretName())
                || isBlank(request.getTargetNamespace()) || isBlank(request.getTargetSecretName())) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "Tempo 对象存储参数不完整");
        }
        var client = clientFactory.getAdminClient(request.getClusterId());
        Secret source = client.secrets().inNamespace(request.getNamespace())
                .withName(request.getSecretName()).get();
        if (source == null) {
            throw new CloudPlatformException(EnumResponseType.ERROR,
                    "凭证 Secret 不存在：" + request.getNamespace() + "/" + request.getSecretName());
        }
        String[] credentials = ThanosObjstoreObjects.findCredentials(source.getData());
        if (credentials == null) {
            throw new CloudPlatformException(EnumResponseType.ERROR,
                    "凭证 Secret 缺少可识别的 access key / secret key（支持："
                            + ThanosObjstoreObjects.supportedKeyPairs() + "）");
        }
        Secret target = TempoObjstoreObjects.build(credentials[0], credentials[1],
                request.getTargetNamespace(), request.getTargetSecretName());
        client.secrets().inNamespace(request.getTargetNamespace())
                .resource(target)
                .fieldManager(ServerSideApply.FIELD_MANAGER).forceConflicts().serverSideApply();
        // Tempo 的凭证走 extraEnvFrom（`-config.expand-env=true` 在启动时展开），换凭证同样要滚动；
        // 改 endpoint 走的是 ConfigMap（chart 有 checksum/config），那条路 helm 自己会滚。
        rollSecretConsumers(client, request.getTargetNamespace(), request.getTargetSecretName(),
                SecretConsumers.hashOf(Map.of(
                        TempoObjstoreObjects.ACCESS_KEY, credentials[0],
                        TempoObjstoreObjects.SECRET_KEY, credentials[1])));
        return new ResponseData<>();
    }

    /**
     * ES 凭证（OTel 批次一）：把 ECK 的 {@code elastic} key 归一化成 collector 读的
     * {@code username} / {@code password}，写到 collector 所在的命名空间。
     */
    @PostMapping("/otel-es-secret")
    @Operation(summary = "下发 collector 的 ES basic auth Secret",
            description = "幂等 SSA；由 platform-api 在打开日志采集时调用")
    public ResponseData<Void> otelEsSecret(@RequestBody OtelEsSecretRequestDTO request) {
        accessResolver.assertClusterAccess(request.getClusterId());
        if (isBlank(request.getNamespace()) || isBlank(request.getSecretName())
                || isBlank(request.getTargetNamespace()) || isBlank(request.getTargetSecretName())) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "ES 凭证参数不完整");
        }
        var client = clientFactory.getAdminClient(request.getClusterId());
        Secret source = client.secrets().inNamespace(request.getNamespace())
                .withName(request.getSecretName()).get();
        if (source == null) {
            throw new CloudPlatformException(EnumResponseType.ERROR,
                    "凭证 Secret 不存在：" + request.getNamespace() + "/" + request.getSecretName());
        }
        // 来源 Secret 的 key 固定为 username / password（平台约定，表单只让选 Secret 名）
        String username = OtelEsSecretObjects.value(source, OtelEsSecretObjects.USERNAME_KEY);
        String password = OtelEsSecretObjects.value(source, OtelEsSecretObjects.PASSWORD_KEY);
        if (isBlank(username) || isBlank(password)) {
            throw new CloudPlatformException(EnumResponseType.ERROR,
                    "凭证 Secret 必须包含 username / password 两个 key：" + request.getSecretName());
        }
        Secret target = OtelEsSecretObjects.build(username, password,
                request.getTargetNamespace(), request.getTargetSecretName());
        client.secrets().inNamespace(request.getTargetNamespace())
                .resource(target)
                .fieldManager(ServerSideApply.FIELD_MANAGER).forceConflicts().serverSideApply();
        // collector 的 Pod 由 otel-operator 从 CR 生成：给那个 Deployment 打注解会被 reconcile 改回去，
        // 正确的手柄是 CR 的 spec.podAnnotations（collector 同样只在启动时读环境变量）。
        try {
            if (SecretConsumers.alignCollector(client, request.getTargetNamespace(),
                    request.getCollectorName(), request.getTargetSecretName(),
                    SecretConsumers.hashOf(Map.of(
                            OtelEsSecretObjects.USERNAME_KEY, username,
                            OtelEsSecretObjects.PASSWORD_KEY, password)))) {
                log.info("ES 凭证变更，已让 otel-operator 滚动 collector（{}/{}）",
                        request.getTargetNamespace(), request.getCollectorName());
            }
        } catch (Exception e) {
            throw new CloudPlatformException(EnumResponseType.ERROR.getCode(),
                    "ES 凭证 Secret 已更新，但滚动 collector 失败（Pod 仍在跑旧凭证，需人工处理）："
                            + e.getMessage(), e);
        }
        return new ResponseData<>();
    }

    /**
     * collector gateway 的 ServiceMonitor（OTel 批次一）。CRD 不存在时返回 {@code false}（跳过），
     * 不算失败：traces 不依赖 Prometheus；没装 prometheus-operator 时不该因此让整次安装 FAILED。
     */
    @PostMapping("/otel-servicemonitor")
    @Operation(summary = "下发 collector 的 ServiceMonitor（CRD 缺失时跳过）",
            description = "幂等 SSA；由 platform-api 在 otel-operator 安装成功后调用")
    public ResponseData<Boolean> otelServiceMonitor(@RequestBody OtelServiceMonitorRequestDTO request) {
        accessResolver.assertClusterAccess(request.getClusterId());
        if (isBlank(request.getNamespace())) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "缺少命名空间");
        }
        var client = clientFactory.getAdminClient(request.getClusterId());
        if (client.apiextensions().v1().customResourceDefinitions()
                .withName("servicemonitors.monitoring.coreos.com").get() == null) {
            return new ResponseData<>(false);
        }
        client.genericKubernetesResources(OtelServiceMonitorObjects.CRD)
                .inNamespace(request.getNamespace())
                .resource(OtelServiceMonitorObjects.build(request.getNamespace()))
                .fieldManager(ServerSideApply.FIELD_MANAGER).forceConflicts().serverSideApply();
        return new ResponseData<>(true);
    }

    /**
     * 对外入口 Gateway CR（平台安装 Istio 时下发）。
     *
     * <p>为什么由组件建：{@code istio-gateway} chart 只建 Deployment + Service；自建网关没有
     * {@code <网关名>-istio} 这个默认 Service，istiod 的 status 控制器找不到承载它的 Service，
     * 就是 {@code Programmed=False / AddressNotUsable}。组件负责建 CR 并写死
     * {@code spec.addresses = <service>.<ns>.svc.cluster.local}，把这层绑定变成组件的责任。
     *
     * <p>listeners 来自安装表单的端口表（端口 = targetPort）；Gateway API CRD 缺失返回 false 跳过。
     */
    @PostMapping("/external-gateway")
    @Operation(summary = "下发对外入口 Gateway CR（listeners + Service 绑定）",
            description = "幂等 SSA；由 platform-api 在 Istio 安装/重放成功后调用；Gateway API CRD 缺失时返回 false")
    public ResponseData<Boolean> externalGateway(@RequestBody ExternalGatewayRequestDTO request) {
        accessResolver.assertClusterAccess(request.getClusterId());
        if (isBlank(request.getNamespace()) || isBlank(request.getName())
                || isBlank(request.getServiceName())
                || request.getListeners() == null || request.getListeners().isEmpty()) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "external-gateway 参数不完整");
        }
        java.util.Set<String> names = new java.util.HashSet<>();
        for (GatewayListenerDTO l : request.getListeners()) {
            if (isBlank(l.getName()) || !names.add(l.getName())
                    || l.getPort() == null || l.getPort() < 1 || l.getPort() > 65535) {
                throw new CloudPlatformException(EnumResponseType.ERROR,
                        "Gateway listener 名称/端口不合法：" + l.getName());
            }
            String protocol = l.getProtocol() == null ? ""
                    : l.getProtocol().toUpperCase(java.util.Locale.ROOT);
            if (!ExternalGatewayObjects.PROTOCOLS.contains(protocol)) {
                throw new CloudPlatformException(EnumResponseType.ERROR,
                        "不支持的监听协议：" + l.getProtocol());
            }
            l.setProtocol(protocol);
            boolean tlsProtocol = "HTTPS".equals(protocol) || "TLS".equals(protocol);
            if (!tlsProtocol) {
                l.setTlsMode(null);
                l.setTlsSecret(null);
                continue;
            }
            String mode = l.getTlsMode() == null || l.getTlsMode().isBlank()
                    ? ("HTTPS".equals(protocol) ? "Terminate" : "Passthrough")
                    : l.getTlsMode();
            if (!"Terminate".equals(mode) && !"Passthrough".equals(mode)) {
                throw new CloudPlatformException(EnumResponseType.ERROR,
                        "不支持的 TLS 模式：" + l.getTlsMode());
            }
            if ("HTTPS".equals(protocol) && "Passthrough".equals(mode)) {
                throw new CloudPlatformException(EnumResponseType.ERROR,
                        "HTTPS listener 只能是 Terminate：" + l.getName());
            }
            if ("Terminate".equals(mode) && isBlank(l.getTlsSecret())) {
                throw new CloudPlatformException(EnumResponseType.ERROR,
                        "TLS Terminate 必须指定证书 Secret：" + l.getName());
            }
            l.setTlsMode(mode);
        }
        var client = clientFactory.getAdminClient(request.getClusterId());
        if (client.apiextensions().v1().customResourceDefinitions()
                .withName(ExternalGatewayObjects.CRD_NAME).get() == null) {
            return new ResponseData<>(false);
        }
        client.genericKubernetesResources(ExternalGatewayObjects.CRD)
                .inNamespace(request.getNamespace())
                .resource(ExternalGatewayObjects.build(request.getNamespace(), request.getName(),
                        request.getServiceName(), request.getListeners()))
                .fieldManager(ServerSideApply.FIELD_MANAGER).forceConflicts().serverSideApply();
        return new ResponseData<>(true);
    }

    /**
     * Istio Telemetry（批次二）：ingress gateway / waypoint 的链路追踪开关。
     *
     * <p>{@code enabled=false} 删除；CRD 或目标 Gateway 不存在返回 false（跳过），
     * 不把"网格还没装好"当成失败。
     */
    @PostMapping("/telemetry")
    @Operation(summary = "下发/删除 Istio Telemetry（ingress/waypoint tracing）",
            description = "幂等 SSA；enabled=false 时删除；CRD 或 Gateway 不存在时返回 false")
    public ResponseData<Boolean> otelTelemetry(@RequestBody OtelTelemetryRequestDTO request) {
        accessResolver.assertClusterAccess(request.getClusterId());
        if (isBlank(request.getNamespace()) || isBlank(request.getName())
                || isBlank(request.getGatewayName())) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "Telemetry 参数不完整");
        }
        var client = clientFactory.getAdminClient(request.getClusterId());
        if (client.apiextensions().v1().customResourceDefinitions()
                .withName("telemetries.telemetry.istio.io").get() == null) {
            return new ResponseData<>(false);
        }
        if (!Boolean.TRUE.equals(request.getEnabled())) {
            client.genericKubernetesResources(OtelTelemetryObjects.CRD)
                    .inNamespace(request.getNamespace())
                    .withName(request.getName())
                    .delete();
            return new ResponseData<>(true);
        }
        Integer sampling = request.getSamplingPercentage();
        if (sampling == null || sampling < 1 || sampling > 100) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "采样率必须在 1..100 之间");
        }
        if (client.genericKubernetesResources(OtelTelemetryObjects.GATEWAY_CRD)
                .inNamespace(request.getNamespace())
                .withName(request.getGatewayName()).get() == null) {
            return new ResponseData<>(false);
        }
        client.genericKubernetesResources(OtelTelemetryObjects.CRD)
                .inNamespace(request.getNamespace())
                .resource(OtelTelemetryObjects.build(request.getNamespace(), request.getName(),
                        request.getGatewayName(), sampling))
                .fieldManager(ServerSideApply.FIELD_MANAGER).forceConflicts().serverSideApply();
        return new ResponseData<>(true);
    }

    /** 查询某条 Telemetry 是否存在及其采样率（waypoint 编辑页回填）。 */
    @GetMapping("/telemetry")
    @Operation(summary = "查询 Istio Telemetry 状态")
    public ResponseData<OtelTelemetryStateDTO> otelTelemetryState(@RequestParam String clusterId,
                                                                  @RequestParam String namespace,
                                                                  @RequestParam String name) {
        accessResolver.assertClusterAccess(clusterId);
        var client = clientFactory.getAdminClient(clusterId);
        if (client.apiextensions().v1().customResourceDefinitions()
                .withName("telemetries.telemetry.istio.io").get() == null) {
            return new ResponseData<>(new OtelTelemetryStateDTO(false, null));
        }
        var resource = client.genericKubernetesResources(OtelTelemetryObjects.CRD)
                .inNamespace(namespace).withName(name).get();
        if (resource == null) {
            return new ResponseData<>(new OtelTelemetryStateDTO(false, null));
        }
        Integer sampling = null;
        Object spec = resource.getAdditionalProperties().get("spec");
        if (spec instanceof Map<?, ?> m && m.get("tracing") instanceof List<?> list && !list.isEmpty()
                && list.get(0) instanceof Map<?, ?> tracing
                && tracing.get("randomSamplingPercentage") instanceof Number n) {
            sampling = n.intValue();
        }
        return new ResponseData<>(new OtelTelemetryStateDTO(true, sampling));
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** 集群的 {@code major.minor}（minor 可能带 "+"）：渲染要按它算 capabilities */
    static String kubeVersion(KubernetesClient client) {
        var version = client.getKubernetesVersion();
        String minor = version.getMinor() == null ? "" : version.getMinor().replaceAll("\\D", "");
        return version.getMajor() + "." + minor;
    }

    /**
     * 集群实际提供的 {@code MutatingAdmissionPolicy} API 版本（探测到哪个报哪个）。
     *
     * <p>渲染必须按它来：{@code helm template} 默认用 helm 自带的 capabilities（4.2.0 ≈ k8s 1.35），
     * 在 1.36+ 会把对象渲染成已不提供的 {@code v1beta1}，apply 直接
     * {@code no matches for kind "MutatingAdmissionPolicy"}（2026-10-22 实测）。
     */
    static List<String> admissionPolicyApiVersions(KubernetesClient client) {
        List<String> out = new ArrayList<>();
        for (String version : List.of("v1", "v1beta1", "v1alpha1")) {
            try {
                var resources = client.getApiResources("admissionregistration.k8s.io/" + version);
                boolean served = resources != null && resources.getResources() != null
                        && resources.getResources().stream()
                        .anyMatch(r -> "mutatingadmissionpolicies".equals(r.getName()));
                if (served) {
                    out.add("admissionregistration.k8s.io/" + version + "/MutatingAdmissionPolicy");
                }
            } catch (RuntimeException ignored) {
                // 该 groupVersion 不存在 → 试下一个
            }
        }
        return out;
    }

    /**
     * 渲染清单里所有 CRD 的 {@code metadata.name}（去重、保持出现顺序）。
     *
     * <p>用于 apply 之后 {@code kubectl wait --for=condition=Established}：CRD 写进去了不代表
     * 可以被发现，下一个 release 里的 CR 可能在几毫秒内仍然 {@code no matches for kind}。
     */
    static List<String> crdNames(String manifest) {
        List<String> names = new ArrayList<>();
        if (manifest == null || manifest.isBlank()) {
            return names;
        }
        for (String doc : manifest.split("(?m)^---[ \t]*$")) {
            if (doc.isBlank()) {
                continue;
            }
            try {
                tools.jackson.databind.JsonNode root = YAML.readTree(doc);
                if (root == null || !"CustomResourceDefinition".equals(root.path("kind").asString(""))) {
                    continue;
                }
                String name = root.path("metadata").path("name").asString("");
                if (!name.isBlank() && !names.contains(name)) {
                    names.add(name);
                }
            } catch (RuntimeException ignored) {
                // 解析不了的单条文档不影响其余：真正的失败会在 apply / wait 阶段暴露
            }
        }
        return names;
    }

    private static Map<String, String> labels(ComponentInstallRequestDTO request) {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("platform.coding/managed", "true");
        labels.put("platform.coding/component", request.getComponentCode());
        labels.put("platform.coding/cluster", request.getClusterId());
        return labels;
    }

    private static Path writeValues(Map<String, Object> values) throws Exception {
        Path file = Files.createTempFile("values-", ".yaml");
        Files.writeString(file, JSON.writeValueAsString(values == null ? Map.of() : values));
        return file;
    }
}
