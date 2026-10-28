package com.coding.platformapi.services;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.k8s.dto.ComponentInstallResultDTO;
import com.coding.common.models.k8s.dto.GatewayListenerDTO;
import com.coding.common.models.k8s.dto.PersistentVolumeClaimDTO;
import com.coding.common.models.k8s.dto.ReleaseInstallSpecDTO;
import com.coding.data.mapper.system.PlatformComponentOperationMapper;
import com.coding.data.models.system.PlatformComponentOperation;
import com.coding.platformapi.components.ComponentRecipe;
import com.coding.platformapi.components.ComponentRecipeCatalog;
import com.coding.platformapi.components.DeepMerge;
import com.coding.platformapi.components.OtelObjects;
import com.coding.platformapi.components.OtelRecipe;
import com.coding.platformapi.components.ReleaseSpec;
import com.coding.platformapi.components.ThanosRetention;
import com.coding.platformapi.components.ValueTemplate;
import com.coding.platformapi.k8s.K8sClient;
import com.coding.platformapi.k8s.K8sComponentClient;
import com.coding.platformapi.k8s.K8sServerGateway;
import com.coding.platformapi.models.ComponentApplyRequest;
import com.coding.platformapi.models.CalicoInstallRequest;
import com.coding.platformapi.models.ComponentStatusDTO;
import com.coding.platformapi.models.EsConfig;
import com.coding.platformapi.models.HarborInstallRequest;
import com.coding.platformapi.models.OtelInstallRequest;
import com.coding.platformapi.models.SecretRef;
import com.coding.platformapi.models.SeaweedInstallRequest;
import com.coding.common.models.objectstorage.ObjectCredentialDTO;
import com.coding.data.models.auth.PlatformTenant;
import com.coding.platformapi.models.ObjectStorageBucketRef;
import com.coding.platformapi.models.ObjectStorageBucketView;
import com.coding.platformapi.models.ObjectStorageCreateBucket;
import com.coding.platformapi.models.TempoStorageConfig;
import com.coding.platformapi.models.ThanosInstallRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * 组件安装/升级的编排（S3.6）。
 *
 * <p><b>为什么异步</b>：helm 装一个栈可能几分钟，同步 HTTP 撑不住（网关常规读超时 120s）。
 * 操作记录先落 PENDING，虚拟线程执行，前端轮询。
 *
 * <p><b>事务边界</b>：本方法**刻意不加 @Transactional** —— insert 是单条语句，自身即提交；
 * 若把整个方法包进事务，`executor.submit` 会在提交前就启动，worker 的 RUNNING 更新
 * 可能读不到那一行（更新 0 行），记录会停在 PENDING 而任务已经在跑。
 *
 * <p><b>成功判定</b>：helm 成功后会调用 {@link ClusterComponentService#refresh} 重探；
 * 页面上的"已装"以探测为准，不以 helm exit code 为准。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ComponentInstallService {

    public static final String PENDING = "PENDING";
    public static final String RUNNING = "RUNNING";
    public static final String SUCCESS = "SUCCESS";
    public static final String FAILED = "FAILED";
    public static final String UNKNOWN = "UNKNOWN";

    /** 超过这个分钟数仍 RUNNING → 判定为中断 */
    private static final int STALE_MINUTES = 15;

    /** Thanos chart 的 global.objstore.secretName（recipe 里固定为它） */
    private static final String THANOS_OBJSTORE_SECRET = "platform-thanos-objstore";

    /** SeaweedFS admin 管理台的口令 Secret：口令由 k8s-server 生成，platform-api 只传名字 */
    private static final String SEAWEED_ADMIN_SECRET = "platform-seaweedfs-admin";


    /** Calico 支持的封装方式（Installation CR 的 calicoNetwork.ipPools[].encapsulation） */
    private static final List<String> CALICO_ENCAPSULATIONS =
            List.of("IPIP", "VXLAN", "IPIPCrossSubnet", "VXLANCrossSubnet", "None");

    // ==================== OTel（批次一） ====================

    private static final int DEFAULT_OTEL_SAMPLING = 10;
    private static final int DEFAULT_OTEL_REPLICAS = 1;
    private static final String DEFAULT_ES_ENDPOINT = "https://es-es-http.default.svc:9200";
    /** Tempo 用独立桶，不与 Thanos 共用（Thanos 的桶是 thanos） */
    private static final String DEFAULT_MINIO_BUCKET = "tempo";
    private static final String DEFAULT_TEMPO_RETENTION = "168h";
    private static final String DEFAULT_TEMPO_TLS_MODE = "plaintext";
    private static final List<String> TEMPO_TLS_MODES =
            List.of("plaintext", "tls", "tls-skip-verify");
    /** Tempo/collector 的命名空间：凭证 Secret 必须落在这里，Pod 才能挂到 */
    private static final String OTEL_NAMESPACE = "monitor";
    /** Tempo chart 的 extraEnvFrom 指向它（平台两种存储模式都写这一个名字） */
    private static final String TEMPO_OBJSTORE_SECRET = "platform-tempo-objstore";

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<Map<String, Map<String, Object>>> SNAPSHOT_TYPE =
            new TypeReference<>() {};

    private final PlatformComponentOperationMapper mapper;
    private final K8sComponentClient client;
    private final ComponentRecipeCatalog recipes;
    private final ClusterService clusterService;
    private final ClusterComponentService components;
    private final HarborRegistryService harborRegistry;
    /** Tempo 的「平台对象存储」模式：解析集群 S3 地址、确保桶存在、发放凭据 */
    private final ObjectStorageService objectStorage;
    private final TenantService tenantService;
    private final K8sClient k8s;

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    /** 与 /alert/webhook 的共享密钥；下发到集群 Secret 前会剥掉 Bearer 前缀 */
    @Value("${platform.alert.webhook.token:}")
    private String alertWebhookToken;

    public Long apply(ComponentApplyRequest req, String operator) {
        String profile = clusterService.requireProfile(req.getClusterId());
        ComponentRecipe recipe = recipes.recipeOf(req.getComponentCode(), profile);
        if (recipe == null) {
            throw new CloudPlatformException(EnumResponseType.ERROR,
                    "该组件尚未支持安装（recipe 未配置）：" + req.getComponentCode());
        }
        if (mapper.countRunning(req.getClusterId(), req.getComponentCode()) > 0) {
            throw new CloudPlatformException(EnumResponseType.ERROR,
                    "该组件正在执行安装/升级，请等待当前操作结束");
        }
        requireDependencies(req.getClusterId(), recipe);

        PlatformComponentOperation last =
                mapper.selectLatestSuccess(req.getClusterId(), req.getComponentCode());
        // 表单预填用的是「最近一次提交」（成功或失败，见 ClusterComponentService），这里的兜底必须同源：
        // 前端对「没改动」的字段（Harbor 整组、webhook 等）是**不下发**的，没有成功记录时后端若还
        // 只看成功记录，就会拿不到值 —— 原封不动点确定被拒成「请填写 Harbor 访问地址」（2026-10-27 撞到）。
        PlatformComponentOperation baseline = last != null ? last
                : mapper.selectLatestAttempt(req.getClusterId(), req.getComponentCode());

        // 告警回调地址 / Thanos Receive：表单值 > 上次提交里记的值 > 平台配置。
        // platformAlerting / thanosRemoteWrite 的组件没有地址就直接拒（别装完才失败）
        Map<String, Object> previousPlatform = platformInputsOf(baseline);
        String alertWebhookUrl = resolveAlertWebhookUrl(
                req.getAlertWebhookUrl(), asString(previousPlatform.get("alertWebhookUrl")));
        if (recipe.platformAlerting() && (alertWebhookUrl == null || alertWebhookUrl.isBlank())) {
            throw new CloudPlatformException(EnumResponseType.ERROR,
                    "请填写平台告警回调地址（集群里的 Alertmanager 需要能访问到平台）");
        }
        String thanosUrl = resolveThanosReceiveUrl(
                req.getThanosReceiveUrl(), asString(previousPlatform.get("thanosReceiveUrl")),
                recipe.thanosReceiveDefault());
        if (recipe.thanosRemoteWrite() && (thanosUrl == null || thanosUrl.isBlank())) {
            throw new CloudPlatformException(EnumResponseType.ERROR,
                    "请填写 Thanos Receive 地址（Prometheus 的 remoteWrite 目标）");
        }
        // Thanos 安装参数（管理集群）：表单 > 上次成功；必填项缺失直接拒
        ThanosInstallRequest thanos = recipe.thanosInstall()
                ? resolveThanosInstall(req.getThanosInstall(), previousPlatform.get("thanosInstall"))
                : null;
        if (recipe.thanosInstall() && (thanos == null
                || isBlank(thanos.getNamespace()) || isBlank(thanos.getSecretName())
                || isBlank(thanos.getEndpoint()) || isBlank(thanos.getBucket()))) {
            throw new CloudPlatformException(EnumResponseType.ERROR,
                    "请选择对象存储凭证 Secret（monitor 命名空间），并填写 MinIO 地址和桶名");
        }
        // 凭证只在 Thanos 自己的命名空间里找（monitor）：跨命名空间读 Secret 既没有产品必要，
        // 也让"谁能读到凭证"这件事不可预期
        if (recipe.thanosInstall() && thanos != null) {
            String thanosNamespace = thanosReleaseNamespace(recipe.releases());
            if (!thanosNamespace.equals(thanos.getNamespace())) {
                throw new CloudPlatformException(EnumResponseType.ERROR,
                        "对象存储凭证 Secret 必须放在 Thanos 的命名空间（" + thanosNamespace + "）");
            }
        }
        // Calico 网络参数：表单 > 上次成功 > 平台配置；填错直接断网，缺一个就拒
        CalicoInstallRequest calico = recipe.calicoNetwork()
                ? resolveCalicoInstall(req.getCalicoInstall(), previousPlatform.get("calicoInstall"))
                : null;
        if (recipe.calicoNetwork() && (calico == null
                || isBlank(calico.getPodCidr()) || isBlank(calico.getEncapsulation()))) {
            throw new CloudPlatformException(EnumResponseType.ERROR,
                    "请填写 Calico 的 Pod CIDR 与封装方式（写错会立即中断集群网络）");
        }
        // Tekton profile：表单 > 上次成功 > 默认 all；只认官方三个值（lite / basic / all）
        String tektonProfile = recipe.tektonProfile()
                ? resolveTektonProfile(req.getTektonProfile(), asString(previousPlatform.get("tektonProfile")))
                : null;
        // OTel 采集栈：表单 > 上次成功 > 默认；Tempo 地址为空直接拒（装完才失败的话，traces 永远发不出去）
        ResolvedOtel resolvedOtel = recipe.otel() == null ? null
                : resolveOtel(req.getClusterId(), operator, req.getOtel(),
                        previousPlatform.get("otel"), recipe.otel());
        OtelInstallRequest otel = resolvedOtel == null ? null : resolvedOtel.request();
        // 对象存储（SeaweedFS）：StorageClass 必填 —— chart 把 storageClass 无条件写进
        // storageClassName，空串在 K8s 里是「不用动态供给」，会装出一组永远 Pending 的 PVC
        SeaweedInstallRequest seaweed = recipe.seaweedStorage()
                ? resolveSeaweed(req.getSeaweed(), previousPlatform.get("seaweed"))
                : null;
        if (recipe.seaweedStorage()) {
            requireSeaweed(seaweed);
        }
        // Harbor：暴露方式 + 访问地址；route 模式额外要求 istio（HTTPRoute 挂在 external-gateway 上）
        HarborInstallRequest harbor = recipe.harborRegistry()
                ? resolveHarbor(req.getHarbor(), previousPlatform.get("harbor"))
                : null;
        if (recipe.harborRegistry()) {
            requireHarbor(harbor);
            requireHarborSource(harbor, previousPlatform,
                    req.getAction() == null ? "INSTALL" : req.getAction());
            if ("route".equals(harbor.getExposeMode())) {
                requireHarborRouteDependency(req.getClusterId());
            }
        }

        // spec 决策 10：只升级平台自己装的 release，手工装的一律不接管。
        // ⚠️「平台装过但没成功」（FAILED / UNKNOWN）仍算平台装的：release 是平台建的，
        //    一次失败不能把组件永久锁成"外部安装"（否则用户连改配置重试都做不到）。
        String action = req.getAction() == null ? "INSTALL" : req.getAction();
        if ("UPGRADE".equals(action) && last == null
                && mapper.countAttempted(req.getClusterId(), req.getComponentCode()) == 0) {
            throw new CloudPlatformException(EnumResponseType.ERROR,
                    "该组件不是平台安装的，平台不接管（只升级平台自己装的 release）");
        }

        // spec §3.3：升级复用上次成功操作里的 values（本次显式传入的字段覆盖它）
        List<ReleaseSpec> merged = mergeValues(recipe.releases(), req.getEditableValues(),
                parseSnapshot(baseline == null ? null : baseline.getValuesSnapshot()));
        // 对象存储的 PVC / 副本数落在嵌套结构里（volume.dataDirs 是 list），由表单参数拼进来
        if (seaweed != null) {
            merged = applySeaweedValues(merged, seaweed);
        }
        // Harbor 的暴露方式 / Service 端口 / externalURL 落在嵌套结构里，由表单参数拼进来
        if (harbor != null) {
            merged = applyHarborValues(merged, harbor);
            // 盘是「重装也不删」的（chart 的 resourcePolicy=keep）：先把存储类对齐到集群上那份，
            // 否则表单留空会被 SSA 抹成 null → PVC spec 不可变，直接 Forbidden（2026-10-27 实锤）
            alignHarborStorageClasses(req.getClusterId(), harbor, merged);
        }
        // 占位符（${cluster.name} / ${thanos.receive.url} / ${tempo.endpoint}）在落库前解析
        List<ReleaseSpec> effective = resolveValues(merged, req.getClusterId(), thanosUrl, calico,
                tektonProfile, otel);
        // Thanos compactor 的保留期下限（raw ≥ 40h、5m ≥ 10d）：违反时 helm 照样成功，
        // 但 compactor 启动即 CrashLoopBackOff —— 必须在下发之前拒（2026-10-20 实锤）
        validateThanosRetention(effective);
        String targetVersion = resolveTargetVersion(effective, req.getTargetVersion());

        PlatformComponentOperation op = new PlatformComponentOperation();
        op.setClusterId(req.getClusterId());
        op.setComponentCode(req.getComponentCode());
        op.setAction(action);
        op.setProfile(profile);
        op.setFromVersion(last == null ? null : last.getToVersion());
        op.setToVersion(targetVersion);
        op.setReleaseNames(JSON.writeValueAsString(
                effective.stream().map(ReleaseSpec::releaseName).distinct().toList()));
        op.setValuesSnapshot(JSON.writeValueAsString(
                snapshotWithPlatformInputs(effective, recipe, alertWebhookUrl, thanosUrl, thanos, calico,
                        tektonProfile, otel, seaweed, harbor)));
        op.setStatus(PENDING);
        op.setOperator(operator);
        op.setStartedAt(new Date());
        try {
            mapper.insert(op);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // 生成列 running_lock 的唯一键兜住了并发：两个请求都过了 countRunning 检查
            throw new CloudPlatformException(EnumResponseType.ERROR,
                    "该组件正在执行安装/升级，请等待当前操作结束");
        }

        Long id = op.getId();
        // worker 线程没有请求上下文：把发起人的 Authorization 显式带进去（否则 k8s-server 401）
        String authorization = K8sServerGateway.currentAuthorization();
        executor.submit(() -> K8sServerGateway.runAs(authorization,
                () -> run(id, req.getClusterId(), req.getComponentCode(), op.getAction(),
                        List.copyOf(effective), recipe.platformAlerting(), alertWebhookUrl, thanos, otel,
                        seaweed, harbor,
                        resolvedOtel == null ? null : resolvedOtel.tempoPlatform())));
        return id;
    }

    /** 前置组件必须已 INSTALLED_HEALTHY（批① 的 metrics-server 无前置，批② 起生效） */
    /**
     * Thanos compactor 保留期的下限（raw ≥ 40h、5m ≥ 10d，见 {@link ThanosRetention}）。
     *
     * <p>违反时 Helm 安装是成功的，只有 compactor 容器启动即退出 —— 平台会记一条 SUCCESS，
     * 集群里却是个永远起不来的 StatefulSet，所以必须在落库/下发之前拒绝。
     */
    private static void validateThanosRetention(List<ReleaseSpec> releases) {
        for (ReleaseSpec r : releases) {
            // mergeValues 之后有效 values 放在 contractValues 槽位里
            String problem = ThanosRetention.violation(r.contractValues());
            if (problem != null) {
                throw new CloudPlatformException(EnumResponseType.ERROR, problem);
            }
        }
    }

    private void requireDependencies(String clusterId, ComponentRecipe recipe) {        if (recipe.requires() == null || recipe.requires().isEmpty()) {
            return;
        }
        Map<String, String> states = components.status(clusterId).stream()
                .collect(Collectors.toMap(ComponentStatusDTO::getCode, ComponentStatusDTO::getState));
        for (String dep : recipe.requires()) {
            if (!ClusterComponentService.HEALTHY.equals(states.get(dep))) {
                throw new CloudPlatformException(EnumResponseType.ERROR,
                        "前置组件未就绪：" + dep + "（需先安装并健康）");
            }
        }
    }

    /**
     * 合并 values：recipe 可编辑默认值 &lt; 上次快照 &lt; 本次用户值 &lt; 契约值（**深合并**）。
     * 可编辑字段的 key 是路径形式（如 {@code prometheus.prometheusSpec.retention}），
     * 先转成嵌套 Map 再合并；契约值永远最后压，不能被历史值或用户值覆盖。
     */
    private static List<ReleaseSpec> mergeValues(List<ReleaseSpec> releases, Map<String, Object> editable,
                                                 Map<String, Map<String, Object>> previous) {
        List<ReleaseSpec> out = new ArrayList<>();
        for (ReleaseSpec r : releases) {
            // extraObjects 是“本次请求重建”的对象（TektonConfig / OTel CR），不能从历史快照继承：
            // 两遍同名 release 的快照会互相覆盖，继承下来会把 CR 塞进只装 CRD 的第一遍
            Map<String, Object> prev = new LinkedHashMap<>(
                    previous.getOrDefault(r.releaseName(), Map.of()));
            prev.remove("extraObjects");
            Map<String, Object> merged = DeepMerge.merge(pathNested(r, r.editableValues()), prev);
            merged = DeepMerge.merge(merged, pathNested(r, editable));
            merged = DeepMerge.merge(merged, r.contractValues());
            // 存量快照的端口行没有 listener（该功能上线前装的）：按 recipe 默认行补齐，
            // 否则重放时 Gateway CR 会因为没有 listener 而生成失败
            if ("external-gateway".equals(r.releaseName())) {
                merged = withGatewayListenerDefaults(merged, pathNested(r, r.editableValues()));
            }
            out.add(new ReleaseSpec(r.releaseName(), r.namespace(), r.chartCode(),
                    r.chartVersion(), merged, r.editableValues(), r.preserveUnknownPlaceholders(),
                    // ⚠️ 必须带上 primary / appVersion：resolveTargetVersion 只看主 release，
                    // 丢了标记就会退回"第一个 release"（Istio 会记成 Gateway API 的 1.6.2）
                    // applyMode 同理：丢了就会退回 Helm，超大 chart 在 release Secret 上限处失败
                    r.primary(), r.appVersion(), r.applyMode()));
        }
        return out;
    }

    /**
     * 可编辑字段按路径声明，这里转成嵌套 Map —— 只接受 recipe 声明过的 key。
     *
     * <p><b>空值 / 空串跳过</b>：StorageClass 的空串表示"跟随集群默认存储类"，而
     * {@code storageClassName: ""} 在 K8s 里是「不用动态供给」，会装出一个永远 Pending 的 PVC。
     */
    private static Map<String, Object> pathNested(ReleaseSpec r, Map<String, Object> source) {
        if (source == null) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (String key : r.editableValues().keySet()) {
            Object value = source.get(key);
            if (value == null || (value instanceof String s && s.isBlank())) {
                continue;
            }
            putPath(out, key, value);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static void putPath(Map<String, Object> root, String path, Object value) {
        String[] parts = path.split("\\.");
        Map<String, Object> cursor = root;
        for (int i = 0; i < parts.length - 1; i++) {
            Object next = cursor.get(parts[i]);
            if (!(next instanceof Map<?, ?>)) {
                next = new LinkedHashMap<String, Object>();
                cursor.put(parts[i], next);
            }
            cursor = (Map<String, Object>) next;
        }
        cursor.put(parts[parts.length - 1], value);
    }

    /** 把 recipe 里的占位符替换成真实值（集群名、Thanos receive 地址、Tempo/ES 表单值） */
    @SuppressWarnings("unchecked")
    private List<ReleaseSpec> resolveValues(List<ReleaseSpec> releases, String clusterId, String thanosUrl,
                                            CalicoInstallRequest calico, String tektonProfile,
                                            OtelInstallRequest otel) {
        Map<String, String> context = new LinkedHashMap<>();
        context.put("cluster.name", clusterService.requireName(clusterId));
        context.put("thanos.receive.url", thanosUrl == null ? "" : thanosUrl);
        context.put("calico.podCidr", calico != null && calico.getPodCidr() != null
                ? calico.getPodCidr() : "");
        context.put("calico.encapsulation", calico != null && calico.getEncapsulation() != null
                ? calico.getEncapsulation() : "");
        // Tekton profile：没传（非 tekton 组件）就用默认值；值已在 apply() 里校验过
        context.put("tekton.profile", tektonProfile != null && !tektonProfile.isBlank()
                ? tektonProfile : ComponentRecipeCatalog.DEFAULT_TEKTON_PROFILE);
        if (otel != null) {
            context.put("tempo.endpoint", otel.getTempoEndpoint());
            context.put("otel.sampling", samplingRatio(otel.getSamplingPercentage()));
            if (otel.getTempoStorage() != null) {
                context.put("tempo.minio.endpoint", tempoMinioHost(otel.getTempoStorage().getEndpoint()));
                context.put("tempo.minio.bucket", otel.getTempoStorage().getBucket());
            }
            if (otel.getEs() != null) {
                context.put("otel.es.endpoint", otel.getEs().getEndpoint());
            }
        }

        // Instrumentation/collector 只下发给**最后一个** otel-operator release（第二遍）；
        // 第一遍带上 CR 会在 Helm Build 阶段找不到 CRD
        int lastOtelOperator = -1;
        for (int i = 0; i < releases.size(); i++) {
            if ("otel-operator".equals(releases.get(i).chartCode())) {
                lastOtelOperator = i;
            }
        }

        List<ReleaseSpec> out = new ArrayList<>();
        for (int i = 0; i < releases.size(); i++) {
            ReleaseSpec r = releases.get(i);
            Map<String, Object> values = r.contractValues();
            if (i == lastOtelOperator && otel != null) {
                Map<String, Object> copy = new LinkedHashMap<>(values);
                copy.put("extraObjects", OtelObjects.build(
                        Boolean.TRUE.equals(otel.getLogsEnabled()), otel.getGatewayReplicas(),
                        otel.getTempoTlsMode()));
                values = copy;
            }
            // Tempo 保留期按表单注入（chart 默认只有 24h）
            if ("tempo".equals(r.chartCode()) && otel != null) {
                Map<String, Object> copy = new LinkedHashMap<>(values);
                @SuppressWarnings("unchecked")
                Map<String, Object> tempo = new LinkedHashMap<>(
                        (Map<String, Object>) copy.getOrDefault("tempo", Map.of()));
                tempo.put("retention", otel.getTempoRetention());
                copy.put("tempo", tempo);
                values = copy;
            }
            Map<String, Object> resolved = (Map<String, Object>) ValueTemplate.resolve(
                    values, context, r.preserveUnknownPlaceholders());
            if ("tempo".equals(r.chartCode()) && otel != null && otel.getTempoStorage() != null) {
                applyTempoS3Security(resolved, otel.getTempoStorage());
            }
            out.add(new ReleaseSpec(r.releaseName(), r.namespace(), r.chartCode(), r.chartVersion(),
                    resolved, r.editableValues(), r.preserveUnknownPlaceholders(),
                    // 同 mergeValues：占位符解析后也不能丢主 release 标记
                    r.primary(), r.appVersion(), r.applyMode()));
        }
        return out;
    }

    /** Tempo 的 s3.endpoint 只接受 host:port；带 {@code http://} 前缀表示明文，其余按 HTTPS */
    private static String tempoMinioHost(String endpoint) {
        String value = endpoint == null ? "" : endpoint.trim();
        if (value.startsWith("http://")) {
            return value.substring("http://".length());
        }
        if (value.startsWith("https://")) {
            return value.substring("https://".length());
        }
        return value;
    }

    /** 旧调用兜底：endpoint 带 {@code http://} 前缀视为明文（新表单用 plaintext 字段显式选择） */
    private static boolean tempoMinioPlaintext(String endpoint) {
        return endpoint != null && endpoint.trim().startsWith("http://");
    }

    /**
     * Tempo 的 S3 传输方式必须在占位符解析后写**布尔值**（ValueTemplate 只会产出字符串，
     * 而 Go 的 YAML 解析不认字符串布尔）。
     *
     * <p>{@code insecure}：明文 HTTP —— 由表单的「传输方式」选择（{@code plaintext}）决定；
     * 否则 HTTPS。{@code tls_insecure_skip_verify}：表单的「跳过 TLS 校验」开关，用于自签证书。
     */
    @SuppressWarnings("unchecked")
    private static void applyTempoS3Security(Map<String, Object> values, TempoStorageConfig storage) {
        if (!(values.get("tempo") instanceof Map<?, ?> tempo)
                || !(tempo.get("storage") instanceof Map<?, ?> store)
                || !(store.get("trace") instanceof Map<?, ?> trace)
                || !(trace.get("s3") instanceof Map<?, ?> s3)) {
            return;
        }
        Map<String, Object> mutable = (Map<String, Object>) s3;
        mutable.put("insecure", Boolean.TRUE.equals(storage.getPlaintext()));
        mutable.put("tls_insecure_skip_verify", storage.isInsecureSkipVerify());
    }

    /** 10 -> "0.1"；5 -> "0.05"；100 -> "1"（Instrumentation 的 sampler.argument 是字符串） */
    private static String samplingRatio(Integer percentage) {
        return BigDecimal.valueOf(percentage)
                .divide(BigDecimal.valueOf(100))
                .stripTrailingZeros()
                .toPlainString();
    }

    /** 快照按 release 存"最终 values"，下次升级时作为基线 */
    private static Map<String, Object> snapshotOf(List<ReleaseSpec> releases) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        for (ReleaseSpec r : releases) {
            snapshot.put(r.releaseName(), r.contractValues());
        }
        return snapshot;
    }

    /**
     * 快照 + 平台输入（表单里的两个地址）。
     *
     * <p>{@code __platform} 不是 release 名，{@code mergeValues} 按 release 名取值时天然忽略它，
     * 不会混进 helm values；升级时用它把用户上次填的地址带回来。
     */
    private static Map<String, Object> snapshotWithPlatformInputs(List<ReleaseSpec> releases, ComponentRecipe recipe,
                                                                  String alertWebhookUrl, String thanosUrl,
                                                                  ThanosInstallRequest thanos,
                                                                  CalicoInstallRequest calico,
                                                                  String tektonProfile,
                                                                  OtelInstallRequest otel,
                                                                  SeaweedInstallRequest seaweed,
                                                                  HarborInstallRequest harbor) {
        Map<String, Object> snapshot = snapshotOf(releases);
        Map<String, Object> platform = new LinkedHashMap<>();
        if (recipe.platformAlerting() && alertWebhookUrl != null) {
            platform.put("alertWebhookUrl", alertWebhookUrl);
        }
        if (recipe.thanosRemoteWrite() && thanosUrl != null) {
            platform.put("thanosReceiveUrl", thanosUrl);
        }
        if (recipe.thanosInstall() && thanos != null) {
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("namespace", thanos.getNamespace());
            t.put("secretName", thanos.getSecretName());
            t.put("endpoint", thanos.getEndpoint());
            t.put("bucket", thanos.getBucket());
            t.put("insecureSkipVerify", thanos.isInsecureSkipVerify());
            platform.put("thanosInstall", t);
        }
        if (recipe.calicoNetwork() && calico != null) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("podCidr", calico.getPodCidr());
            c.put("encapsulation", calico.getEncapsulation());
            platform.put("calicoInstall", c);
        }
        if (recipe.tektonProfile() && tektonProfile != null) {
            platform.put("tektonProfile", tektonProfile);
        }
        if (otel != null) {
            platform.put("otel", otelSnapshot(otel));
        }
        if (recipe.seaweedStorage() && seaweed != null) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("storageClass", seaweed.getStorageClass());
            s.put("masterSize", seaweed.getMasterSize());
            s.put("volumeSize", seaweed.getVolumeSize());
            s.put("filerSize", seaweed.getFilerSize());
            s.put("masterReplicas", seaweed.getMasterReplicas());
            s.put("volumeReplicas", seaweed.getVolumeReplicas());
            s.put("s3Replicas", seaweed.getS3Replicas());
            s.put("adminEnabled", seaweed.getAdminEnabled());
            s.put("s3Endpoint", seaweed.getS3Endpoint());
            platform.put("seaweed", s);
        }
        if (recipe.harborRegistry() && harbor != null) {
            Map<String, Object> h = new LinkedHashMap<>();
            h.put("exposeMode", harbor.getExposeMode());
            h.put("host", harbor.getHost() == null ? "" : harbor.getHost());
            h.put("nodePort", harbor.getNodePort());
            h.put("port", harbor.getPort());
            // 平台调 Harbor API / 凭据 endpoint 用的完整 authority（nodePort/LB 要带端口）
            h.put("externalHost", harborAuthority(harbor));
            h.put("databaseMode", harbor.getDatabaseMode());
            h.put("dbHost", harbor.getDbHost());
            h.put("dbPort", harbor.getDbPort());
            h.put("dbUsername", harbor.getDbUsername());
            h.put("dbName", harbor.getDbName());
            h.put("dbSslMode", harbor.getDbSslMode());
            h.put("redisMode", harbor.getRedisMode());
            h.put("redisAddr", harbor.getRedisAddr());
            h.put("redisUsername", harbor.getRedisUsername());
            platform.put("harbor", h);
        }
        if (!platform.isEmpty()) {
            snapshot.put("__platform", platform);
        }
        return snapshot;
    }

    /** 从上次成功的快照里取平台输入（{@code __platform} 子对象）；没有/解析失败 → 空 */
    private static Map<String, Object> platformInputsOf(PlatformComponentOperation last) {
        if (last == null) {
            return Map.of();
        }
        return parseSnapshot(last.getValuesSnapshot()).getOrDefault("__platform", Map.of());
    }

    private static String asString(Object value) {
        return value instanceof String s ? s : null;
    }

    // ==================== OTel 参数解析 / 快照（批次一） ====================

    /** 平台对象存储给 Tempo 的凭据：只在本次安装内传递，**绝不进 values / 快照** */
    private record TempoPlatformStorage(String endpoint, String bucket,
                                        String accessKey, String secretKey) {}

    /** resolveOtel 的产物：可落库的配置 + 只在内存里的平台凭据 */
    private record ResolvedOtel(OtelInstallRequest request, TempoPlatformStorage tempoPlatform) {}

    private record ResolvedTempoStorage(TempoStorageConfig config, TempoPlatformStorage platform) {}

    private ResolvedOtel resolveOtel(String clusterId, String operator, OtelInstallRequest requested,
                                     Object previous, OtelRecipe recipe) {
        OtelInstallRequest req = requested == null ? new OtelInstallRequest() : requested;
        OtelInstallRequest prev = otelOf(previous);

        OtelInstallRequest out = new OtelInstallRequest();
        out.setTempoEndpoint(firstNonBlank(req.getTempoEndpoint(),
                prev == null ? null : prev.getTempoEndpoint(), recipe.tempoEndpointDefault()));
        if (isBlank(out.getTempoEndpoint())) {
            throw new CloudPlatformException(EnumResponseType.ERROR,
                    "请填写 Tempo 地址（collector 的 traces 导出目标）");
        }
        out.setSamplingPercentage(firstNonNull(req.getSamplingPercentage(),
                prev == null ? null : prev.getSamplingPercentage(), DEFAULT_OTEL_SAMPLING));
        if (out.getSamplingPercentage() < 1 || out.getSamplingPercentage() > 100) {
            throw new CloudPlatformException(EnumResponseType.ERROR,
                    "采样率必须在 1..100 之间（收到 " + out.getSamplingPercentage() + "）");
        }
        out.setGatewayReplicas(firstNonNull(req.getGatewayReplicas(),
                prev == null ? null : prev.getGatewayReplicas(), DEFAULT_OTEL_REPLICAS));
        if (out.getGatewayReplicas() < 1 || out.getGatewayReplicas() > 10) {
            throw new CloudPlatformException(EnumResponseType.ERROR,
                    "Gateway 副本数必须在 1..10 之间（收到 " + out.getGatewayReplicas() + "）");
        }
        Boolean logs = req.getLogsEnabled() != null ? req.getLogsEnabled()
                : prev != null && prev.getLogsEnabled() != null ? prev.getLogsEnabled() : Boolean.FALSE;
        out.setLogsEnabled(logs);
        out.setTempoRetention(firstNonBlank(req.getTempoRetention(),
                prev == null ? null : prev.getTempoRetention(), DEFAULT_TEMPO_RETENTION));
        if (!out.getTempoRetention().matches("\\d+[hms]")) {
            throw new CloudPlatformException(EnumResponseType.ERROR,
                    "Tempo 保留期必须是 Go duration（如 168h）：" + out.getTempoRetention());
        }
        out.setTempoTlsMode(firstNonBlank(req.getTempoTlsMode(),
                prev == null ? null : prev.getTempoTlsMode(), DEFAULT_TEMPO_TLS_MODE));
        if (!TEMPO_TLS_MODES.contains(out.getTempoTlsMode())) {
            throw new CloudPlatformException(EnumResponseType.ERROR,
                    "Tempo TLS 模式只支持 " + String.join(" / ", TEMPO_TLS_MODES));
        }
        ResolvedTempoStorage storage = recipe.tempoInstall()
                ? resolveTempoStorage(clusterId, operator,
                        req.getTempoStorage(), prev == null ? null : prev.getTempoStorage())
                : null;
        out.setTempoStorage(storage == null ? null : storage.config());
        out.setEs(resolveEs(req.getEs(), prev == null ? null : prev.getEs(), logs));
        return new ResolvedOtel(out, storage == null ? null : storage.platform());
    }

    /**
     * Tempo 的存储：两种来源。
     *
     * <ul>
     *   <li>{@link TempoStorageConfig#SOURCE_PLATFORM}（默认）—— 用平台对象存储：地址取集群 S3 连接地址、
     *       桶按 {@code t-<租户短码>-<名字>} 解析（不存在就现建）、凭据由平台发放并写进
     *       {@code monitor/platform-tempo-objstore}。表单只需要给桶名。</li>
     *   <li>{@link TempoStorageConfig#SOURCE_EXTERNAL} —— 外部地址：endpoint / 桶 / 凭证 Secret 全手填，
     *       与以前完全一致。</li>
     * </ul>
     */
    private ResolvedTempoStorage resolveTempoStorage(String clusterId, String operator,
                                                     TempoStorageConfig requested, TempoStorageConfig prev) {
        TempoStorageConfig out = new TempoStorageConfig();
        String source = firstNonBlank(
                requested == null ? null : requested.getSource(),
                prev == null ? null : prev.getSource(),
                TempoStorageConfig.SOURCE_PLATFORM);
        if (!TempoStorageConfig.SOURCE_PLATFORM.equals(source)
                && !TempoStorageConfig.SOURCE_EXTERNAL.equals(source)) {
            throw new CloudPlatformException(EnumResponseType.ERROR,
                    "Tempo 存储来源只支持 " + TempoStorageConfig.SOURCE_PLATFORM + " / "
                            + TempoStorageConfig.SOURCE_EXTERNAL + "（收到 " + source + "）");
        }
        out.setSource(source);

        if (TempoStorageConfig.SOURCE_PLATFORM.equals(source)) {
            String tenantId = requested != null && !isBlank(requested.getTenantId())
                    ? requested.getTenantId()
                    : prev == null ? null : prev.getTenantId();
            // PLATFORM 下 bucket 是逻辑名（默认 tempo）—— 真实桶名由平台拼
            String name = firstNonBlank(
                    requested == null ? null : requested.getBucket(),
                    prev == null ? null : prev.getBucket(), DEFAULT_MINIO_BUCKET);
            TempoPlatformStorage platform = provisionTempoStorage(clusterId, operator, tenantId,
                    name.trim().toLowerCase());
            out.setEndpoint(tempoMinioHost(platform.endpoint()));
            out.setBucket(platform.bucket());
            out.setPlaintext(!platform.endpoint().startsWith("https://"));
            out.setInsecureSkipVerify(true);
            out.setCredentialSecret(new SecretRef(OTEL_NAMESPACE, TEMPO_OBJSTORE_SECRET));
            return new ResolvedTempoStorage(out, platform);
        }

        String endpoint = firstNonBlank(
                requested == null ? null : requested.getEndpoint(),
                prev == null ? null : prev.getEndpoint());
        // Tempo 的 s3.endpoint 只接受 host:port：scheme 一律剥掉，传输方式由下面的 plaintext 决定
        out.setEndpoint(tempoMinioHost(endpoint));
        out.setBucket(firstNonBlank(
                requested == null ? null : requested.getBucket(),
                prev == null ? null : prev.getBucket(), DEFAULT_MINIO_BUCKET));
        // 传输方式：表单显式选择优先；没传（旧调用/旧快照）才按 endpoint 的 scheme 推断
        Boolean plaintext = requested != null ? requested.getPlaintext() : null;
        if (plaintext == null && prev != null) {
            plaintext = prev.getPlaintext();
        }
        if (plaintext == null) {
            plaintext = tempoMinioPlaintext(endpoint);
        }
        out.setPlaintext(plaintext);
        SecretRef secret = requested != null && requested.getCredentialSecret() != null
                ? requested.getCredentialSecret()
                : prev == null ? null : prev.getCredentialSecret();
        out.setCredentialSecret(secret);
        out.setInsecureSkipVerify(requested != null
                ? requested.isInsecureSkipVerify()
                : prev == null || prev.isInsecureSkipVerify());
        if (isBlank(out.getEndpoint()) || isBlank(out.getBucket())
                || secret == null || isBlank(secret.getNamespace()) || isBlank(secret.getName())) {
            throw new CloudPlatformException(EnumResponseType.ERROR,
                    "请填写 MinIO 地址/桶，并选择 Tempo 的凭证 Secret");
        }
        if (!OTEL_NAMESPACE.equals(secret.getNamespace())) {
            throw new CloudPlatformException(EnumResponseType.ERROR,
                    "Tempo 的凭证 Secret 必须放在 " + OTEL_NAMESPACE + " 命名空间");
        }
        return new ResolvedTempoStorage(out, null);
    }

    /**
     * 平台对象存储：解析（必要时创建）桶并发放凭据。
     *
     * <p>桶名规则与对象存储页一致（{@code t-<租户短码>-<名字>}）：先按名字在集群里找现成的桶，
     * 找到就复用（同一次部署的多次升级不会重复建）；没有才建，归属租户 = 指定的 &gt; default 租户。
     * 每次安装都会发放一组新凭据 —— 旧凭据留着不动，Secret 被覆盖成新的，Tempo 随之滚动。
     */
    private TempoPlatformStorage provisionTempoStorage(String clusterId, String operator,
                                                       String tenantId, String name) {
        String raw = objectStorage.s3Endpoint(clusterId);   // 对象存储组件没装时这里就报错
        if (isBlank(raw)) {
            throw new CloudPlatformException(EnumResponseType.ERROR,
                    "集群还没有对象存储连接地址：请先在「集群组件」里安装对象存储（或改用外部地址）");
        }
        String wanted = isBlank(tenantId) ? null : ObjectStorageService.bucketName(tenantId, name);
        // 必须精确匹配 t-<8 位租户短码>-<名字>：只按后缀匹配会把别的租户的 `xxx-tempo` 认成自己的桶，
        // Tempo 就会往别人的桶里写数据
        String pattern = "^t-[0-9a-f]{8}-" + java.util.regex.Pattern.quote(name) + "$";
        ObjectStorageBucketView bucket = objectStorage.listBuckets(clusterId, tenantId).stream()
                .filter(b -> wanted != null
                        ? wanted.equals(b.getBucket())
                        : b.getBucket().matches(pattern))
                .findFirst().orElse(null);
        if (bucket == null) {
            ObjectStorageCreateBucket body = new ObjectStorageCreateBucket();
            body.setClusterId(clusterId);
            body.setTenantId(isBlank(tenantId) ? defaultTenantId() : tenantId);
            body.setName(name);
            body.setDisplayName("Tempo " + name);
            bucket = objectStorage.createBucket(body, operator);
        }
        ObjectStorageBucketRef ref = new ObjectStorageBucketRef();
        ref.setBucketId(bucket.getId());
        ObjectCredentialDTO credential = objectStorage.issueCredential(ref, operator);
        log.info("Tempo 使用平台对象存储：集群 {} 桶 {}（操作者 {}）",
                clusterId, bucket.getBucket(), operator);
        return new TempoPlatformStorage(raw, bucket.getBucket(),
                credential.getAccessKey(), credential.getSecretKey());
    }

    /** 平台桶必须挂在租户名下：没指定就落到 default 租户，没有 default 再取第一个。 */
    private String defaultTenantId() {
        List<PlatformTenant> tenants = tenantService.list();
        return tenants.stream().filter(t -> "default".equals(t.getName())).findFirst()
                .or(() -> tenants.stream().findFirst())
                .map(PlatformTenant::getId)
                .orElseThrow(() -> new CloudPlatformException(EnumResponseType.ERROR,
                        "还没有任何租户：平台对象存储的桶必须归属某个租户，请先创建租户"));
    }

    private static EsConfig resolveEs(EsConfig requested, EsConfig prev, Boolean logs) {
        EsConfig out = new EsConfig();
        out.setEndpoint(firstNonBlank(
                requested == null ? null : requested.getEndpoint(),
                prev == null ? null : prev.getEndpoint(), DEFAULT_ES_ENDPOINT));
        SecretRef secret = requested != null && requested.getCredentialSecret() != null
                ? requested.getCredentialSecret()
                : prev == null ? null : prev.getCredentialSecret();
        out.setCredentialSecret(secret);
        out.setInsecureSkipVerify(requested != null
                ? requested.isInsecureSkipVerify()
                : prev == null || prev.isInsecureSkipVerify());
        if (Boolean.TRUE.equals(logs)) {
            if (!out.getEndpoint().startsWith("http://") && !out.getEndpoint().startsWith("https://")) {
                throw new CloudPlatformException(EnumResponseType.ERROR,
                        "ES 地址必须以 http:// 或 https:// 开头：" + out.getEndpoint());
            }
            if (secret == null || isBlank(secret.getNamespace()) || isBlank(secret.getName())) {
                throw new CloudPlatformException(EnumResponseType.ERROR, "请选择 ES 凭证 Secret");
            }
            if (!OTEL_NAMESPACE.equals(secret.getNamespace())) {
                throw new CloudPlatformException(EnumResponseType.ERROR,
                        "ES 的凭证 Secret 必须放在 " + OTEL_NAMESPACE + " 命名空间");
            }
        }
        return out;
    }

    /** 只放地址、用户名、索引与 Secret 引用；密码不在其中 */
    private static Map<String, Object> otelSnapshot(OtelInstallRequest otel) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tempoEndpoint", otel.getTempoEndpoint());
        out.put("samplingPercentage", otel.getSamplingPercentage());
        out.put("gatewayReplicas", otel.getGatewayReplicas());
        out.put("logsEnabled", Boolean.TRUE.equals(otel.getLogsEnabled()));
        out.put("tempoRetention", otel.getTempoRetention());
        out.put("tempoTlsMode", otel.getTempoTlsMode());
        if (otel.getTempoStorage() != null) {
            TempoStorageConfig s = otel.getTempoStorage();
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("endpoint", s.getEndpoint());
            t.put("bucket", s.getBucket());
            t.put("credentialSecret", secretRefSnapshot(s.getCredentialSecret()));
            t.put("insecureSkipVerify", s.isInsecureSkipVerify());
            t.put("plaintext", s.getPlaintext());
            out.put("tempoStorage", t);
        }
        if (otel.getEs() != null) {
            EsConfig e = otel.getEs();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("endpoint", e.getEndpoint());
            m.put("credentialSecret", secretRefSnapshot(e.getCredentialSecret()));
            m.put("insecureSkipVerify", e.isInsecureSkipVerify());
            out.put("es", m);
        }
        return out;
    }

    private static Map<String, Object> secretRefSnapshot(SecretRef ref) {
        if (ref == null) {
            return Map.of();
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("namespace", ref.getNamespace());
        m.put("name", ref.getName());
        return m;
    }

    private static OtelInstallRequest otelOf(Object previous) {
        if (!(previous instanceof Map<?, ?> map)) {
            return null;
        }
        OtelInstallRequest out = new OtelInstallRequest();
        out.setTempoEndpoint(asString(map.get("tempoEndpoint")));
        out.setSamplingPercentage(asInt(map.get("samplingPercentage")));
        out.setGatewayReplicas(asInt(map.get("gatewayReplicas")));
        out.setLogsEnabled(map.get("logsEnabled") instanceof Boolean b ? b : null);
        out.setTempoStorage(tempoStorageOf(map.get("tempoStorage")));
        out.setEs(esOf(map.get("es")));
        return out;
    }

    private static TempoStorageConfig tempoStorageOf(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return null;
        }
        TempoStorageConfig out = new TempoStorageConfig();
        out.setEndpoint(asString(map.get("endpoint")));
        out.setBucket(asString(map.get("bucket")));
        out.setCredentialSecret(secretRefOf(map.get("credentialSecret")));
        out.setInsecureSkipVerify(Boolean.TRUE.equals(map.get("insecureSkipVerify")));
        out.setPlaintext(map.get("plaintext") instanceof Boolean b ? b : null);
        return out;
    }

    private static EsConfig esOf(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return null;
        }
        EsConfig out = new EsConfig();
        out.setEndpoint(asString(map.get("endpoint")));
        out.setCredentialSecret(secretRefOf(map.get("credentialSecret")));
        out.setInsecureSkipVerify(Boolean.TRUE.equals(map.get("insecureSkipVerify")));
        return out;
    }

    private static SecretRef secretRefOf(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return null;
        }
        return new SecretRef(asString(map.get("namespace")), asString(map.get("name")));
    }

    private static Integer asInt(Object value) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value instanceof String s && !s.isBlank()) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    @SafeVarargs
    private static <T> T firstNonNull(T... values) {
        for (T value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    /** 表单没带的字段从上次成功的 {@code __platform.thanosInstall} 补齐 */
    @SuppressWarnings("unchecked")
    private static ThanosInstallRequest resolveThanosInstall(ThanosInstallRequest requested, Object previous) {
        Map<String, Object> prev = previous instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
        if (requested == null && prev.isEmpty()) {
            return null;
        }
        ThanosInstallRequest out = new ThanosInstallRequest();
        out.setNamespace(firstNonBlank(requested == null ? null : requested.getNamespace(),
                asString(prev.get("namespace"))));
        out.setSecretName(firstNonBlank(requested == null ? null : requested.getSecretName(),
                asString(prev.get("secretName"))));
        out.setEndpoint(firstNonBlank(requested == null ? null : requested.getEndpoint(),
                asString(prev.get("endpoint"))));
        out.setBucket(firstNonBlank(requested == null ? null : requested.getBucket(),
                asString(prev.get("bucket"))));
        out.setInsecureSkipVerify(requested != null
                ? requested.isInsecureSkipVerify()
                : Boolean.TRUE.equals(prev.get("insecureSkipVerify")));
        return out;
    }

    /** Tekton profile：表单 > 上次成功 > 默认 all；只接受官方三个值 */
    private static String resolveTektonProfile(String requested, String previous) {
        String value = firstNonBlank(requested, previous, ComponentRecipeCatalog.DEFAULT_TEKTON_PROFILE);
        if (!ComponentRecipeCatalog.TEKTON_PROFILES.contains(value)) {
            throw new CloudPlatformException(EnumResponseType.ERROR,
                    "不支持的 Tekton profile：" + value + "（可选 lite / basic / all）");
        }
        return value;
    }

    /** Calico 参数：表单 > 上次成功 > 平台配置 */
    @SuppressWarnings("unchecked")
    private CalicoInstallRequest resolveCalicoInstall(CalicoInstallRequest requested, Object previous) {
        Map<String, Object> prev = previous instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
        CalicoInstallRequest out = new CalicoInstallRequest();
        out.setPodCidr(firstNonBlank(
                requested == null ? null : requested.getPodCidr(), asString(prev.get("podCidr"))));
        out.setEncapsulation(firstNonBlank(
                requested == null ? null : requested.getEncapsulation(),
                asString(prev.get("encapsulation"))));
        if (isBlank(out.getPodCidr()) && isBlank(out.getEncapsulation())) {
            return null;
        }
        // 表单是下拉，API 调用方不受它约束：Calico 收到未知封装方式不会报错退出，
        // 而是装完跨节点 Pod 不通（排查成本极高），所以这里必须拒。
        if (!isBlank(out.getEncapsulation()) && !CALICO_ENCAPSULATIONS.contains(out.getEncapsulation())) {
            throw new CloudPlatformException(EnumResponseType.ERROR,
                    "不支持的 Calico 封装方式：" + out.getEncapsulation()
                            + "（可选 IPIP / VXLAN / IPIPCrossSubnet / VXLANCrossSubnet / None）");
        }
        return out;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    // ==================== Harbor（镜像仓库） ====================

    private static final java.util.Set<String> HARBOR_MODES =
            java.util.Set.of("route", "loadBalancer", "nodePort");
    private static final int DEFAULT_HARBOR_NODE_PORT = 30002;
    private static final int DEFAULT_HARBOR_PORT = 80;
    private static final int DEFAULT_HARBOR_DB_PORT = 5432;
    private static final String DEFAULT_HARBOR_DB_NAME = "registry";
    private static final String DEFAULT_HARBOR_DB_SSLMODE = "disable";
    /** 外部数据库 / Redis 的口令 Secret（键：password / REDIS_PASSWORD[+REDIS_USERNAME]） */
    static final String HARBOR_EXTERNAL_DB_SECRET = "platform-harbor-external-db";
    static final String HARBOR_EXTERNAL_REDIS_SECRET = "platform-harbor-external-redis";
    private static final java.util.Set<String> HARBOR_DB_SSL_MODES =
            java.util.Set.of("disable", "require", "verify-ca", "verify-full");
    /** 外部 Redis 单实例地址 host:port（sentinel 列表暂不支持） */
    private static final java.util.regex.Pattern HARBOR_REDIS_ADDR =
            java.util.regex.Pattern.compile("^[a-z0-9]([a-z0-9.-]*[a-z0-9])?:[0-9]{1,5}$");

    /** 访问地址：不带 scheme/path/端口（端口在各自的独立字段里），全小写 */
    private static final java.util.regex.Pattern HARBOR_HOST =
            java.util.regex.Pattern.compile("^[a-z0-9]([a-z0-9.-]*[a-z0-9])?$");

    private static final java.util.regex.Pattern IPV4 =
            java.util.regex.Pattern.compile("^\\d{1,3}(\\.\\d{1,3}){3}$");

    /** 表单 > 上次成功 > 默认（nodePort / 30002 / 80） */
    @SuppressWarnings("unchecked")
    private static HarborInstallRequest resolveHarbor(HarborInstallRequest requested, Object previous) {
        Map<String, Object> prev = previous instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
        String mode = firstNonBlank(requested == null ? null : requested.getExposeMode(),
                asString(prev.get("exposeMode")), "nodePort");
        String host = firstNonBlank(requested == null ? null : requested.getHost(),
                asString(prev.get("host")));
        Integer nodePort = firstNonNullInt(requested == null ? null : requested.getNodePort(),
                prev.get("nodePort"), DEFAULT_HARBOR_NODE_PORT);
        Integer port = firstNonNullInt(requested == null ? null : requested.getPort(),
                prev.get("port"), DEFAULT_HARBOR_PORT);

        HarborInstallRequest out = new HarborInstallRequest(mode,
                host == null ? null : host.trim(), nodePort, port);
        // 来源只能来自表单/上次快照；口令**只**来自本次请求（快照里不存）
        out.setDatabaseMode(firstNonBlank(requested == null ? null : requested.getDatabaseMode(),
                asString(prev.get("databaseMode")), "internal"));
        out.setDbHost(firstNonBlank(requested == null ? null : requested.getDbHost(),
                asString(prev.get("dbHost"))));
        out.setDbPort(firstNonNullInt(requested == null ? null : requested.getDbPort(),
                prev.get("dbPort"), DEFAULT_HARBOR_DB_PORT));
        out.setDbUsername(firstNonBlank(requested == null ? null : requested.getDbUsername(),
                asString(prev.get("dbUsername"))));
        out.setDbName(firstNonBlank(requested == null ? null : requested.getDbName(),
                asString(prev.get("dbName")), DEFAULT_HARBOR_DB_NAME));
        out.setDbSslMode(firstNonBlank(requested == null ? null : requested.getDbSslMode(),
                asString(prev.get("dbSslMode")), DEFAULT_HARBOR_DB_SSLMODE));
        out.setDbPassword(requested == null ? null : requested.getDbPassword());
        out.setRedisMode(firstNonBlank(requested == null ? null : requested.getRedisMode(),
                asString(prev.get("redisMode")), "internal"));
        out.setRedisAddr(firstNonBlank(requested == null ? null : requested.getRedisAddr(),
                asString(prev.get("redisAddr"))));
        out.setRedisUsername(firstNonBlank(requested == null ? null : requested.getRedisUsername(),
                asString(prev.get("redisUsername"))));
        out.setRedisPassword(requested == null ? null : requested.getRedisPassword());
        return out;
    }

    private static Integer firstNonNullInt(Integer requested, Object previous, int fallback) {
        if (requested != null) {
            return requested;
        }
        if (previous instanceof Number n) {
            return n.intValue();
        }
        if (previous instanceof String text && !text.isBlank()) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    /**
     * Harbor 暴露方式与访问地址必填，且按模式校验。
     *
     * <p>route 模式的 host 会写进 HTTPRoute 的 hostnames —— Gateway API 明确**不允许 IP**，
     * 也不接受端口；IP 直连必须选 loadBalancer / nodePort。
     */
    private static void requireHarbor(HarborInstallRequest harbor) {
        String mode = harbor == null ? null : harbor.getExposeMode();
        if (mode == null || !HARBOR_MODES.contains(mode)) {
            throw new CloudPlatformException(EnumResponseType.ERROR,
                    "不支持的 Harbor 暴露方式：" + mode + "（可选 route / loadBalancer / nodePort）");
        }
        String host = harbor.getHost();
        if ("route".equals(mode) && host != null && host.contains(":")) {
            throw new CloudPlatformException(EnumResponseType.ERROR,
                    "route 模式的域名不能带端口（端口由 external-gateway 的 listener 决定）");
        }
        if ("route".equals(mode) && host != null && IPV4.matcher(host.trim()).matches()) {
            throw new CloudPlatformException(EnumResponseType.ERROR,
                    "route 模式必须用域名（Gateway API 不允许 IP；IP 直连请选 loadBalancer / nodePort）");
        }
        if (host == null || host.isBlank() || !HARBOR_HOST.matcher(host.trim()).matches()) {
            throw new CloudPlatformException(EnumResponseType.ERROR,
                    "请填写 Harbor 访问地址（IP 或域名，不带 http:// 与路径）");
        }
        if ("nodePort".equals(mode)) {
            Integer nodePort = harbor.getNodePort();
            if (nodePort == null || nodePort < 1 || nodePort > 65535) {
                throw new CloudPlatformException(EnumResponseType.ERROR,
                        "NodePort 必须在 1~65535 之间（默认 30002）");
            }
        }
        if ("loadBalancer".equals(mode)) {
            Integer port = harbor.getPort();
            if (port == null || port < 1 || port > 65535) {
                throw new CloudPlatformException(EnumResponseType.ERROR,
                        "Service 端口必须在 1~65535 之间（默认 80）");
            }
        }
    }

    /**
     * 数据库 / Redis 来源与外部连接信息。
     *
     * <p>来源（内置 ↔ 外部）**只能在首次安装时选**：从内置切外部不是一次 helm upgrade
     * 能完成的（内库数据要迁移），放开会让操作"成功"但项目/镜像元数据留在旧库。
     *
     * <p>口令：首次安装必填；编辑时留空 = 沿用已有 Secret（由 k8s-server 校验 Secret 是否存在）。
     */
    @SuppressWarnings("unchecked")
    private static void requireHarborSource(HarborInstallRequest h, Map<String, Object> previousPlatform,
                                            String action) {
        String dbMode = h.getDatabaseMode();
        String redisMode = h.getRedisMode();
        if (!"internal".equals(dbMode) && !"external".equals(dbMode)) {
            throw new CloudPlatformException(EnumResponseType.ERROR,
                    "不支持的 Harbor 数据库来源：" + dbMode + "（internal / external）");
        }
        if (!"internal".equals(redisMode) && !"external".equals(redisMode)) {
            throw new CloudPlatformException(EnumResponseType.ERROR,
                    "不支持的 Harbor Redis 来源：" + redisMode + "（internal / external）");
        }
        if (!"INSTALL".equals(action) && previousPlatform.get("harbor") instanceof Map<?, ?> prev) {
            String prevDb = asString(prev.get("databaseMode"));
            if (prevDb != null && !prevDb.equals(dbMode)) {
                throw new CloudPlatformException(EnumResponseType.ERROR,
                        "数据库来源只能在首次安装时选择（内置 ↔ 外部需要迁移数据）");
            }
            String prevRedis = asString(prev.get("redisMode"));
            if (prevRedis != null && !prevRedis.equals(redisMode)) {
                throw new CloudPlatformException(EnumResponseType.ERROR,
                        "Redis 来源只能在首次安装时选择（内置 ↔ 外部需要迁移数据）");
            }
        }
        boolean install = "INSTALL".equals(action);
        if ("external".equals(dbMode)) {
            if (h.getDbHost() == null || h.getDbHost().isBlank()
                    || !HARBOR_HOST.matcher(h.getDbHost()).matches()) {
                throw new CloudPlatformException(EnumResponseType.ERROR,
                        "请填写外部数据库地址（IP 或域名，不带 http:// 与端口；端口单独填）");
            }
            if (h.getDbPort() == null || h.getDbPort() < 1 || h.getDbPort() > 65535) {
                throw new CloudPlatformException(EnumResponseType.ERROR,
                        "外部数据库端口必须在 1~65535 之间（默认 5432）");
            }
            if (h.getDbUsername() == null || h.getDbUsername().isBlank()) {
                throw new CloudPlatformException(EnumResponseType.ERROR,
                        "请填写外部数据库用户名");
            }
            if (h.getDbName() == null || h.getDbName().isBlank()) {
                h.setDbName(DEFAULT_HARBOR_DB_NAME);
            }
            if (!HARBOR_DB_SSL_MODES.contains(h.getDbSslMode())) {
                throw new CloudPlatformException(EnumResponseType.ERROR,
                        "外部数据库 sslmode 只能是 disable / require / verify-ca / verify-full");
            }
            if (install && (h.getDbPassword() == null || h.getDbPassword().isBlank())) {
                throw new CloudPlatformException(EnumResponseType.ERROR,
                        "请填写外部数据库口令（首次安装必须填；编辑时留空 = 不改）");
            }
        }
        if ("external".equals(redisMode)) {
            if (h.getRedisAddr() == null || h.getRedisAddr().isBlank()
                    || !HARBOR_REDIS_ADDR.matcher(h.getRedisAddr().trim()).matches()) {
                throw new CloudPlatformException(EnumResponseType.ERROR,
                        "外部 Redis 地址应形如 redis.internal:6379（sentinel 列表暂不支持）");
            }
            if (install && (h.getRedisPassword() == null || h.getRedisPassword().isBlank())) {
                throw new CloudPlatformException(EnumResponseType.ERROR,
                        "请填写外部 Redis 口令（首次安装必须填；编辑时留空 = 不改）");
            }
        }
    }

    /** route 模式必须已有健康的 istio（external-gateway）；另两种模式不要求 */
    private void requireHarborRouteDependency(String clusterId) {
        Map<String, String> states = components.status(clusterId).stream()
                .collect(Collectors.toMap(ComponentStatusDTO::getCode, ComponentStatusDTO::getState,
                        (a, b) -> a));
        if (!ClusterComponentService.HEALTHY.equals(states.get("istio"))) {
            throw new CloudPlatformException(EnumResponseType.ERROR,
                    "route 模式需要先安装 istio（external-gateway）；或改用 loadBalancer / nodePort 暴露");
        }
    }

    /**
     * 平台访问 Harbor 的完整 authority（host[:port]）：
     * route 用域名；loadBalancer 端口非 80 时带端口；nodePort 恒带 NodePort。
     */
    static String harborAuthority(HarborInstallRequest harbor) {
        String host = harbor.getHost();
        if ("nodePort".equals(harbor.getExposeMode())) {
            return host + ":" + harbor.getNodePort();
        }
        if ("loadBalancer".equals(harbor.getExposeMode())) {
            Integer port = harbor.getPort();
            return port == null || port == 80 ? host : host + ":" + port;
        }
        return host;
    }

    /**
     * 按暴露方式拼 chart 的 {@code expose.*} 与 {@code externalURL}。
     *
     * <p>三种模式都只走 HTTP（{@code tls.enabled=false}）；HTTPS 不在本期。
     * route 的 {@code parentRefs} 固定指 {@code istio-system/external-gateway}。
     */
    private static List<ReleaseSpec> applyHarborValues(List<ReleaseSpec> releases, HarborInstallRequest h) {
        Map<String, Object> expose = new LinkedHashMap<>();
        expose.put("type", h.getExposeMode());
        expose.put("tls", Map.of("enabled", false));
        expose.put("route", Map.of(
                "parentRefs", List.of(Map.of("name", "external-gateway", "namespace", "istio-system")),
                "hosts", List.of(h.getHost())));
        expose.put("nodePort", Map.of("name", "harbor", "ports", Map.of("http",
                Map.of("port", 80, "nodePort", h.getNodePort()))));
        expose.put("loadBalancer", Map.of("name", "harbor", "ports",
                Map.of("httpPort", h.getPort())));

        Map<String, Object> values = new LinkedHashMap<>();
        values.put("expose", expose);
        values.put("externalURL", "http://" + harborAuthority(h));

        // 外部数据库：口令走 Secret（键 password），values 里只引用名字
        if ("external".equals(h.getDatabaseMode())) {
            Map<String, Object> ext = new LinkedHashMap<>();
            ext.put("host", h.getDbHost());
            ext.put("port", String.valueOf(h.getDbPort()));
            ext.put("username", h.getDbUsername());
            ext.put("coreDatabase", h.getDbName());
            ext.put("sslmode", h.getDbSslMode());
            ext.put("existingSecret", HARBOR_EXTERNAL_DB_SECRET);
            values.put("database", Map.of("type", "external", "external", ext));
        }
        // 外部 Redis：chart 的 helper 用 lookup 读 Secret（REDIS_PASSWORD / REDIS_USERNAME）
        if ("external".equals(h.getRedisMode())) {
            Map<String, Object> ext = new LinkedHashMap<>();
            ext.put("addr", h.getRedisAddr());
            if (h.getRedisUsername() != null && !h.getRedisUsername().isBlank()) {
                ext.put("username", h.getRedisUsername());
            }
            ext.put("existingSecret", HARBOR_EXTERNAL_REDIS_SECRET);
            values.put("redis", Map.of("type", "external", "external", ext));
        }

        List<ReleaseSpec> out = new ArrayList<>(releases.size());
        for (ReleaseSpec r : releases) {
            out.add(new ReleaseSpec(r.releaseName(), r.namespace(), r.chartCode(), r.chartVersion(),
                    DeepMerge.merge(r.contractValues(), values), r.editableValues(),
                    r.preserveUnknownPlaceholders(), r.primary(), r.appVersion(), r.applyMode()));
        }
        return out;
    }

    /**
     * 把 Harbor 的存储类对齐到集群上**已经存在的盘**。
     *
     * <p>为什么必须对齐：chart 给 PVC 打了 {@code helm.sh/resource-policy: keep}，上次安装失败
     * （helm 回滚 = uninstall）后盘会留下。PVC 的 {@code storageClassName} 创建后不可变，而表单
     * 留空 = 跟随集群默认 = manifest 里没有这个字段 —— helm 4 的 SSA 会把它抹成 null，直接
     * {@code Forbidden: spec is immutable after creation}（2026-10-27 真集群上就是这么失败的）。
     *
     * <p>规则：表单留空 → 填回盘上的存储类（复用旧盘，重试就能装成）；表单显式选了别的 →
     * 直接拒并说清怎么办（改不了，只能删盘或改回）。读不到 PVC（没权限 / 集群不可达）就跳过 ——
     * 让 helm 自己报错，别因为对齐失败挡住安装。
     */
    private void alignHarborStorageClasses(String clusterId, HarborInstallRequest harbor,
                                           List<ReleaseSpec> releases) {
        ReleaseSpec target = releases.stream()
                .filter(r -> "platform-harbor".equals(r.releaseName())).findFirst().orElse(null);
        if (target == null) {
            return;
        }
        Map<String, String> bound = existingStorageClasses(clusterId, target.namespace());
        if (bound.isEmpty()) {
            return;
        }
        Map<String, Object> values = target.contractValues();
        for (Map.Entry<String, String> e : harborPvcKeys(harbor, values).entrySet()) {
            String existing = bound.get(e.getValue());
            if (existing == null || existing.isBlank()) {
                continue;
            }
            Object requested = readPath(values, e.getKey());
            String wanted = requested instanceof String s ? s.trim() : "";
            if (wanted.isEmpty()) {
                putPath(values, e.getKey(), existing);
                log.info("Harbor 复用遗留 PVC {}：存储类按盘上的 {} 填回", e.getValue(), existing);
            } else if (!wanted.equals(existing)) {
                throw new CloudPlatformException(EnumResponseType.ERROR,
                        "命名空间 " + target.namespace() + " 已存在 PVC " + e.getValue()
                                + "（存储类 " + existing + "）—— 上次安装失败后保留下来的。"
                                + "PVC 的存储类创建后不可改：把存储类填成 " + existing
                                + " 复用这块盘，或先在集群侧删掉这个 PVC 再装");
            }
        }
    }

    /**
     * 字段路径 → chart 渲染出来的 PVC 名（{@code fullnameOverride=harbor} 是契约）。
     * 只列这次真的会创建的盘：外部库 / 关掉 Trivy 时对应的 PVC 不渲染。
     */
    private static Map<String, String> harborPvcKeys(HarborInstallRequest harbor,
                                                     Map<String, Object> values) {
        Map<String, String> keys = new LinkedHashMap<>();
        keys.put("persistence.persistentVolumeClaim.registry.storageClass", "harbor-registry");
        keys.put("persistence.persistentVolumeClaim.jobservice.jobLog.storageClass", "harbor-jobservice");
        if (!"external".equals(harbor.getDatabaseMode())) {
            keys.put("persistence.persistentVolumeClaim.database.storageClass",
                    "database-data-harbor-database-0");
        }
        if (!"external".equals(harbor.getRedisMode())) {
            keys.put("persistence.persistentVolumeClaim.redis.storageClass", "data-harbor-redis-0");
        }
        if (Boolean.TRUE.equals(readPath(values, "trivy.enabled"))) {
            keys.put("persistence.persistentVolumeClaim.trivy.storageClass", "data-harbor-trivy-0");
        }
        return keys;
    }

    /** 命名空间里已存在的 PVC → 存储类。读失败当作「没有」并留日志：不能因为对齐失败挡住安装 */
    private Map<String, String> existingStorageClasses(String clusterId, String namespace) {
        PersistentVolumeClaimDTO query = new PersistentVolumeClaimDTO();
        query.setClusterId(clusterId);
        query.setNamespace(namespace);
        try {
            List<PersistentVolumeClaimDTO> list = k8s.list(query);
            Map<String, String> out = new LinkedHashMap<>();
            for (PersistentVolumeClaimDTO pvc : list == null ? List.<PersistentVolumeClaimDTO>of() : list) {
                if (pvc.getName() != null && pvc.getStorageClassName() != null) {
                    out.put(pvc.getName(), pvc.getStorageClassName());
                }
            }
            return out;
        } catch (Exception ex) {
            log.warn("读取命名空间 {} 的 PVC 失败，跳过 Harbor 存储类对齐：{}", namespace, ex.getMessage());
            return Map.of();
        }
    }

    /** 按点路径读嵌套 Map（读不到返回 null） */
    private static Object readPath(Map<String, Object> root, String path) {
        Object cursor = root;
        for (String part : path.split("\\.")) {
            if (!(cursor instanceof Map<?, ?> map)) {
                return null;
            }
            cursor = map.get(part);
        }
        return cursor;
    }

    // ==================== 对象存储（SeaweedFS） ====================

    /** 容量格式：10Gi / 200Mi / 1Ti（不接受裸数字，避免 30 被当成 30 字节） */
    private static final java.util.regex.Pattern SEAWEED_SIZE =
            java.util.regex.Pattern.compile("^[1-9]\\d*(Mi|Gi|Ti)$");

    /** 表单 > 上次成功 > 默认；StorageClass 没有默认（没选就由 {@link #requireSeaweed} 拒） */
    @SuppressWarnings("unchecked")
    private SeaweedInstallRequest resolveSeaweed(SeaweedInstallRequest requested, Object previous) {
        Map<String, Object> prev = previous instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
        SeaweedInstallRequest out = new SeaweedInstallRequest();
        out.setStorageClass(firstNonBlank(requested == null ? null : requested.getStorageClass(),
                asString(prev.get("storageClass"))));
        out.setMasterSize(firstNonBlank(requested == null ? null : requested.getMasterSize(),
                asString(prev.get("masterSize")), "1Gi"));
        out.setVolumeSize(firstNonBlank(requested == null ? null : requested.getVolumeSize(),
                asString(prev.get("volumeSize")), "30Gi"));
        out.setFilerSize(firstNonBlank(requested == null ? null : requested.getFilerSize(),
                asString(prev.get("filerSize")), "10Gi"));
        out.setMasterReplicas(firstPositive(requested == null ? null : requested.getMasterReplicas(),
                positiveOrNull(prev.get("masterReplicas")), 1));
        out.setVolumeReplicas(firstPositive(requested == null ? null : requested.getVolumeReplicas(),
                positiveOrNull(prev.get("volumeReplicas")), 1));
        out.setS3Replicas(firstPositive(requested == null ? null : requested.getS3Replicas(),
                positiveOrNull(prev.get("s3Replicas")), 1));
        out.setAdminEnabled(firstBoolean(requested == null ? null : requested.getAdminEnabled(),
                prev.get("adminEnabled") instanceof Boolean b ? b : null, false));
        out.setS3Endpoint(firstNonBlank(requested == null ? null : requested.getS3Endpoint(),
                asString(prev.get("s3Endpoint")),
                // 表单默认值 = 集群内地址；**不能**拿平台的拨号配置当默认值（开发环境那条 port-forward
                // 一旦被写进快照，就变成「别的集群来连这个 S3」的声明值了）
                ComponentRecipeCatalog.seaweedS3InClusterEndpoint()));
        return out;
    }

    /**
     * 对象存储的必填/取值边界。
     *
     * <p>⭐ StorageClass 必须非空：chart 的模板无条件渲染 {@code storageClassName: {{ .Values.*.storageClass }}}，
     * 空串在 K8s 里是「不用动态供给」，装完是一组永远 Pending 的 PVC —— 而 helm 会显示成功。
     * 这与 editableValues 里「空串跳过」的语义不同（那边是标量路径，可以真的省略这个键）。
     */
    private void requireSeaweed(SeaweedInstallRequest s) {
        if (s == null || isBlank(s.getStorageClass())) {
            throw new CloudPlatformException(EnumResponseType.ERROR,
                    "请选择对象存储的 StorageClass（PVC 需要动态供给，留空会装出一组永远 Pending 的卷）");
        }
        requireSeaweedSize("master（raft 日志/快照）", s.getMasterSize());
        requireSeaweedSize("volume（数据盘）", s.getVolumeSize());
        requireSeaweedSize("filer（元数据）", s.getFilerSize());
        // Raft 要奇数成员，平台只放开 1/3；volume/s3 是普通副本
        requireSeaweedReplicas("master", s.getMasterReplicas(), 1, 3);
        requireSeaweedReplicas("volume", s.getVolumeReplicas(), 1, 20);
        requireSeaweedReplicas("s3 网关", s.getS3Replicas(), 1, 10);
    }

    private void requireSeaweedSize(String label, String size) {
        if (isBlank(size) || !SEAWEED_SIZE.matcher(size).matches()) {
            throw new CloudPlatformException(EnumResponseType.ERROR,
                    "对象存储的 " + label + " 容量格式不对（应形如 10Gi / 200Mi）：" + size);
        }
    }

    private void requireSeaweedReplicas(String label, Integer replicas, int min, int max) {
        if (replicas == null || replicas < min || replicas > max) {
            throw new CloudPlatformException(EnumResponseType.ERROR,
                    "对象存储的 " + label + " 副本数必须在 " + min + "~" + max + " 之间");
        }
    }

    /**
     * 把表单参数拼成 chart 的嵌套 values。
     *
     * <p>本方法在 {@code mergeValues} 之后执行，所以覆盖的是「契约值合并完」的结果；
     * SeaweedFS 的 recipe 有意不在契约里写这几块（否则契约会压掉用户的选择）。
     */
    private static List<ReleaseSpec> applySeaweedValues(List<ReleaseSpec> releases, SeaweedInstallRequest s) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("master", Map.of("replicas", s.getMasterReplicas(),
                "data", pvcValues(s.getMasterSize(), s.getStorageClass())));
        values.put("volume", Map.of("replicas", s.getVolumeReplicas(),
                "dataDirs", List.of(Map.of(
                        "name", "data",
                        "type", "persistentVolumeClaim",
                        "size", s.getVolumeSize(),
                        "storageClass", s.getStorageClass(),
                        // 0 = 按磁盘自动算每个盘能放多少个卷（chart 文档的推荐做法）
                        "maxVolumes", 0))));
        values.put("filer", Map.of("data", pvcValues(s.getFilerSize(), s.getStorageClass())));
        values.put("s3", Map.of("replicas", s.getS3Replicas()));

        // admin 管理台：默认不装；打开时**必须**引用平台生成的凭证 Secret ——
        // chart 从 4.46 起，admin 绑定非 loopback 地址（0.0.0.0）而没有口令时会直接退出
        Map<String, Object> admin = new LinkedHashMap<>();
        admin.put("enabled", Boolean.TRUE.equals(s.getAdminEnabled()));
        if (Boolean.TRUE.equals(s.getAdminEnabled())) {
            admin.put("secret", Map.of(
                    "existingSecret", SEAWEED_ADMIN_SECRET,
                    "userKey", "username",
                    "pwKey", "password"));
        }
        values.put("admin", admin);

        List<ReleaseSpec> out = new ArrayList<>(releases.size());
        for (ReleaseSpec r : releases) {
            out.add(new ReleaseSpec(r.releaseName(), r.namespace(), r.chartCode(), r.chartVersion(),
                    DeepMerge.merge(r.contractValues(), values), r.editableValues(),
                    r.preserveUnknownPlaceholders(), r.primary(), r.appVersion(), r.applyMode()));
        }
        return out;
    }

    private static Map<String, Object> pvcValues(String size, String storageClass) {
        return Map.of("type", "persistentVolumeClaim", "size", size, "storageClass", storageClass);
    }

    private static Integer positiveOrNull(Object value) {
        if (value instanceof Number n) {
            return n.intValue() > 0 ? n.intValue() : null;
        }
        return null;
    }

    private static Integer firstPositive(Integer requested, Integer previous, Integer fallback) {
        if (requested != null && requested > 0) {
            return requested;
        }
        return previous != null && previous > 0 ? previous : fallback;
    }

    private static Boolean firstBoolean(Boolean requested, Boolean previous, boolean fallback) {
        if (requested != null) {
            return requested;
        }
        return previous != null ? previous : fallback;
    }

    /** 组件本体（主 release）所在的命名空间；没有主标记时退回第一个 */
    private static String releaseNamespace(List<ReleaseSpec> releases) {
        return releases.stream().filter(ReleaseSpec::primary).findFirst()
                .orElse(releases.get(0)).namespace();
    }

    private static Map<String, Map<String, Object>> parseSnapshot(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return JSON.readValue(json, SNAPSHOT_TYPE);
        } catch (Exception e) {
            // 旧格式（整段 ReleaseSpec 数组）解析不了：当没有历史值，别让升级因此失败
            return Map.of();
        }
    }

    /** 目标版本必须存在于 recipe 里（批① 只有一个版本；批② 起一个组件可有多个） */
    /**
     * 目标版本只能是**组件本体**（recipe 里 {@code asPrimary()} 标记的 release）的版本。
     *
     * <p>多 release 组件（管理集群的 Prometheus = platform-kps + platform-thanos；
     * Istio = Gateway API + 5 个 Istio chart）里，随附 release 的版本不是组件的版本 ——
     * 放它进来会把操作记录写成"已升级到 0.47.2 / 1.6.2"，后续页面按这个版本比较，永远显示可升级。
     */
    private static String resolveTargetVersion(List<ReleaseSpec> releases, String requested) {
        String primary = ReleaseSpec.primaryChartVersion(releases);
        if (requested == null || requested.isBlank()) {
            return primary;
        }
        if (!primary.equals(requested)) {
            // 把主版本带进消息：存量记录可能记着历史错误版本（Istio 曾把 Gateway API 的 1.6.2 记成组件版本）
            throw new CloudPlatformException(EnumResponseType.ERROR,
                    "未知的目标版本：" + requested + "（当前组件版本 " + primary + "）");
        }
        return requested;
    }

    private void run(Long id, String clusterId, String componentCode, String action,
                     List<ReleaseSpec> releases, boolean platformAlerting, String alertWebhookUrl,
                     ThanosInstallRequest thanos, OtelInstallRequest otel,
                     SeaweedInstallRequest seaweed, HarborInstallRequest harbor,
                     TempoPlatformStorage tempoPlatform) {
        PlatformComponentOperation running = new PlatformComponentOperation();
        running.setId(id);
        running.setStatus(RUNNING);
        mapper.updateById(running);
        try {
            // Tempo 的凭证 Secret / collector 的 ES Secret 必须在 helm 之前就位（Pod 会挂载/引用它）
            if (otel != null && otel.getTempoStorage() != null) {
                TempoStorageConfig s = otel.getTempoStorage();
                if (tempoPlatform != null) {
                    // 平台对象存储：凭据是本次解析时刚发放的，直接写进同一个 Secret
                    client.applyTempoObjstore(clusterId, null, null, OTEL_NAMESPACE,
                            TEMPO_OBJSTORE_SECRET,
                            tempoPlatform.accessKey(), tempoPlatform.secretKey());
                } else {
                    client.applyTempoObjstore(clusterId,
                            s.getCredentialSecret().getNamespace(), s.getCredentialSecret().getName(),
                            OTEL_NAMESPACE, TEMPO_OBJSTORE_SECRET);
                }
            }
            if (otel != null && Boolean.TRUE.equals(otel.getLogsEnabled())) {
                EsConfig e = otel.getEs();
                client.applyOtelEsSecret(clusterId,
                        e.getCredentialSecret().getNamespace(), e.getCredentialSecret().getName(),
                        OTEL_NAMESPACE, "platform-otel-es", OtelObjects.GATEWAY);
            }
            // Thanos 的对象存储 Secret 必须在 helm 之前就位（Pod 会挂载它）
            if (thanos != null) {
                client.applyThanosObjstore(clusterId, thanos.getNamespace(), thanos.getSecretName(),
                        thanos.getEndpoint(), thanos.getBucket(), thanos.isInsecureSkipVerify(),
                        thanosReleaseNamespace(releases), THANOS_OBJSTORE_SECRET);
            }
            // SeaweedFS 的 admin 管理台必须带鉴权（chart 绑定 0.0.0.0 时无密码会直接退出）：
            // 平台先确保一个随机口令的 Secret 存在（已存在则原样保留，升级不轮换），再用 values 引用它。
            if (seaweed != null && Boolean.TRUE.equals(seaweed.getAdminEnabled())) {
                client.applySeaweedAdminSecret(clusterId, releaseNamespace(releases), SEAWEED_ADMIN_SECRET);
            }
            // S3 的 identities 配置由平台接管（recipe 的 s3.existingConfigSecret）：
            // helm 之前必须已有 platform-admin 身份，否则 S3 网关起不来 / 平台读不到配置。
            if (seaweed != null) {
                client.ensureSeaweedS3Config(clusterId);
            }
            // Harbor 的管理口令与 secretKey 必须在 helm 之前就位；values 里只引用 Secret 名
            if (harbor != null) {
                client.applyHarborSecrets(clusterId, "harbor", "platform-harbor-secrets");
                boolean dbExternal = "external".equals(harbor.getDatabaseMode());
                boolean redisExternal = "external".equals(harbor.getRedisMode());
                if (dbExternal || redisExternal) {
                    // 外部库/Redis 的口令由 k8s-server 写成 Secret（键 password / REDIS_PASSWORD）；
                    // chart 的 redis 用 lookup 读 Secret，所以必须赶在 helm 之前
                    client.applyHarborExternalSecrets(clusterId, "harbor",
                            dbExternal, HARBOR_EXTERNAL_DB_SECRET,
                            dbExternal ? harbor.getDbPassword() : null,
                            redisExternal, HARBOR_EXTERNAL_REDIS_SECRET,
                            redisExternal ? harbor.getRedisUsername() : null,
                            redisExternal ? harbor.getRedisPassword() : null);
                }
            }
            ComponentInstallResultDTO result =
                    client.applyComponent(clusterId, componentCode, action, toDtos(releases));
            // 告警联动在写 SUCCESS 之前做：失败则整条操作记 FAILED（消息里说明组件本身已装好）
            if (result.isSuccess() && platformAlerting) {
                try {
                    applyPlatformAlerting(clusterId, releases.get(0).namespace(), alertWebhookUrl);
                } catch (Exception e) {
                    throw new IllegalStateException("组件已安装，但平台告警联动配置失败：" + e.getMessage(), e);
                }
            }
            // ServiceMonitor 依赖 prometheus-operator 的 CRD：缺失/失败都不该把安装结果改成 FAILED
            if (result.isSuccess() && "otel-operator".equals(componentCode)) {
                try {
                    if (!client.applyOtelServiceMonitor(clusterId, OTEL_NAMESPACE)) {
                        log.info("集群 {} 没有 ServiceMonitor CRD，跳过 collector 指标抓取配置", clusterId);
                    }
                } catch (Exception e) {
                    log.warn("组件 {} 安装成功但 ServiceMonitor 下发失败（不影响操作结果）: {}",
                            id, e.getMessage());
                }
            }
            // 对外入口 Gateway：组件负责建 CR 并把 addresses 绑到承载它的 Service。
            // 必须排在 Telemetry 之前 —— Telemetry 的 targetRefs 指向这个 Gateway。
            if (result.isSuccess() && "istio".equals(componentCode)) {
                try {
                    List<GatewayListenerDTO> listeners = externalGatewayListeners(releases);
                    if (listeners.isEmpty()
                            || !client.ensureExternalGateway(clusterId, "istio-system",
                                    "external-gateway", "external-gateway", listeners)) {
                        log.info("集群 {} 的 Gateway API CRD 未就绪或端口表没有可监听的端口，跳过 external-gateway CR",
                                clusterId);
                    }
                } catch (Exception e) {
                    log.warn("组件 {} 安装成功但 external-gateway CR 下发失败（不影响操作结果）: {}",
                            id, e.getMessage());
                }
            }
            // 网格 tracing：ingress 的 Telemetry。谁后装谁补建；Gateway/CRD 缺失时服务端返回 false
            if (result.isSuccess() && ("otel-operator".equals(componentCode) || "istio".equals(componentCode))) {
                try {
                    int sampling = otel != null && otel.getSamplingPercentage() != null
                            ? otel.getSamplingPercentage() : otelSamplingPercentage(clusterId);
                    if (!client.applyOtelTelemetry(clusterId, "istio-system",
                            "platform-tracing-ingress", "external-gateway", sampling, true)) {
                        log.info("集群 {} 的 ingress gateway / Telemetry CRD 未就绪，跳过 ingress tracing",
                                clusterId);
                    }
                } catch (Exception e) {
                    log.warn("组件 {} 安装成功但 ingress Telemetry 下发失败（不影响操作结果）: {}",
                            id, e.getMessage());
                }
            }
            // Harbor 装好后立刻把租户接进去（建 project / robot / 凭据）。
            // ⚠️ 同步失败**不改变安装结论**：helm 已经成功、组件确实装好了。把它记成 FAILED 会让
            //    「上次成功」为空 —— 编辑表单读不到当前值、版本列显示「—」（2026-10-27 用户撞到）。
            //    失败写进操作消息，镜像项目面板里每个租户还有一行 ERROR，可以单独重试。
            String postInstallNote = null;
            if (result.isSuccess() && "harbor".equals(componentCode)) {
                try {
                    // host 直接来自本次安装参数：钩子在写 SUCCESS 之前跑，harborHost() 还读不到记录
                    // INSTALL 强制重建：手工卸载后同地址重装时，旧凭据还在但新 Harbor 里没有那个 robot
                    HarborRegistryService.SyncResult sync = harborRegistry.ensureAll(
                            clusterId, harborAuthority(harbor), "INSTALL".equals(action));
                    if (sync.failed() > 0) {
                        postInstallNote = sync.failed()
                                + " 个租户的镜像项目/凭据同步失败（见「镜像项目」面板，可重试）";
                    }
                } catch (Exception e) {
                    postInstallNote = "镜像项目/凭据同步失败：" + e.getMessage()
                            + "（见「镜像项目」面板，可重试）";
                    log.warn("组件 {} 安装成功，但 Harbor 租户同步失败: {}", id, e.getMessage());
                }
            }
            PlatformComponentOperation done = new PlatformComponentOperation();
            done.setId(id);
            done.setStatus(result.isSuccess() ? SUCCESS : FAILED);
            done.setMessage(truncate(messageWith(result, postInstallNote), 1000));
            done.setLogTail(tailOf(result));
            done.setFinishedAt(new Date());
            mapper.updateById(done);
            if (result.isSuccess()) {
                // 以探测为准：装完刷新一次，页面状态跟着变。
                // ⚠️ 刷新失败**不能**把已经成功的安装覆盖成 FAILED（评审发现）——单独 catch。
                try {
                    components.refresh(clusterId);
                } catch (Exception e) {
                    log.warn("组件 {} 安装成功但刷新探测失败（不影响操作结果）: {}", id, e.getMessage());
                }
                // 能力快照也要刷新：Gateway API / monitoring.coreos.com 等 group 的存在性影响
                // 网格横幅与功能门控（hasGatewayApi 直接读 capability，不再有独立组件行）。
                // 失败同样不能把已成功的安装覆盖成 FAILED。
                try {
                    clusterService.refreshCapabilityQuietly(clusterId);
                } catch (Exception e) {
                    log.warn("组件 {} 安装成功但刷新集群能力失败（不影响操作结果）: {}", id, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("组件操作 {} 执行失败: {}", id, e.getMessage());
            PlatformComponentOperation failed = new PlatformComponentOperation();
            failed.setId(id);
            failed.setStatus(FAILED);
            failed.setMessage(truncate(e.getMessage(), 1000));
            failed.setFinishedAt(new Date());
            mapper.updateById(failed);
        }
    }

    /**
     * 下发平台告警联动（Secret + AlertmanagerConfig）。
     *
     * <p>⚠️ 地址是**表单里填的**（集群里的 Alertmanager 要能访问到）——
     * 平台与集群不在同一台机器时，127.0.0.1 不可达，必须配成集群网络可达的地址。
     * clusterId 由这里统一拼，前端不传。
     */
    private void applyPlatformAlerting(String clusterId, String namespace, String base) {
        String token = stripBearer(alertWebhookToken);
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("platform.alert.webhook.token 未配置");
        }
        if (base == null || base.isBlank()) {
            throw new IllegalStateException("平台告警回调地址为空");
        }
        if (!base.startsWith("http://") && !base.startsWith("https://")) {
            throw new IllegalStateException("平台告警回调地址必须以 http:// 或 https:// 开头：" + base);
        }
        String separator = base.contains("?") ? "&" : "?";
        client.applyPlatformAlerting(clusterId, namespace,
                base + separator + "clusterId=" + clusterId, token);
    }

    /** 表单值优先，其次上次成功里的值，最后平台配置；只做 trim */
    private String resolveAlertWebhookUrl(String requested, String previous) {
        String value = firstNonBlank(requested, previous);
        return value == null ? null : value.trim();
    }

    /** Thanos 的部署命名空间（Pod 只能挂本 ns 的 Secret）：取 recipe 里那个 release 的 ns */
    private static String thanosReleaseNamespace(List<ReleaseSpec> releases) {
        return releases.stream()
                .filter(r -> r.releaseName().equals("platform-thanos"))
                .map(ReleaseSpec::namespace)
                .findFirst()
                .orElse("monitor");
    }

    /** 表单值优先，其次上次成功里的值，最后平台配置；只做 trim */
    /** 表单值优先，其次上次成功里的值，最后**画像默认**（只有管理集群自带集群内 receive） */
    private String resolveThanosReceiveUrl(String requested, String previous, String profileDefault) {
        String value = firstNonBlank(requested, previous, profileDefault);
        return value == null ? null : value.trim();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    /**
     * AlertmanagerConfig 的 {@code authorization.type=Bearer} 会自己拼前缀；
     * 配置里常见的 "Bearer xxx" 必须剥掉，否则线上头变成 "Bearer Bearer xxx"，平台侧校验不过。
     */
    private static String stripBearer(String value) {
        return value != null && value.startsWith("Bearer ") ? value.substring(7) : value;
    }

    private static List<ReleaseInstallSpecDTO> toDtos(List<ReleaseSpec> releases) {
        List<ReleaseInstallSpecDTO> out = new ArrayList<>();
        for (ReleaseSpec r : releases) {
            ReleaseInstallSpecDTO dto = new ReleaseInstallSpecDTO();
            dto.setReleaseName(r.releaseName());
            dto.setNamespace(r.namespace());
            dto.setChartCode(r.chartCode());
            dto.setChartVersion(r.chartVersion());
            // null = 普通 Helm release；SERVER_SIDE_APPLY = 渲染后直接落盘（超大 chart，见 ReleaseSpec）
            dto.setApplyMode(r.applyMode());
            // 端口表里的 listener/tlsSecret 是平台生成 Gateway CR 用的，不能进 Helm 的 Service values
            dto.setValues("istio-gateway".equals(r.chartCode())
                    ? withoutGatewayListenerFields(r.contractValues())
                    : r.contractValues());
            out.add(dto);
        }
        return out;
    }

    /**
     * 从端口表派生 Gateway listener：
     * <ul>
     *   <li>端口 = {@code targetPort}（Envoy 实际监听端口；Service 的 port 只是对外映射）；</li>
     *   <li>{@code listener} 为空 = 仅 Service（如 15021 status-port），不生成 listener；</li>
     *   <li>协议为表单里选的 HTTP / HTTPS / TLS / TCP / UDP；HTTPS 必须带证书 Secret。</li>
     * </ul>
     */
    @SuppressWarnings("unchecked")
    static List<GatewayListenerDTO> externalGatewayListeners(List<ReleaseSpec> releases) {
        ReleaseSpec gateway = releases.stream()
                .filter(r -> "external-gateway".equals(r.releaseName()))
                .findFirst().orElse(null);
        if (gateway == null) {
            return List.of();
        }
        Object service = gateway.contractValues().get("service");
        if (!(service instanceof Map<?, ?> svc) || !(svc.get("ports") instanceof List<?> rows)) {
            return List.of();
        }
        List<GatewayListenerDTO> out = new ArrayList<>();
        for (Object o : rows) {
            if (!(o instanceof Map<?, ?> row)) {
                continue;
            }
            Object listener = row.get("listener");
            String protocol = listener == null ? "" : listener.toString().trim().toUpperCase(java.util.Locale.ROOT);
            if (protocol.isEmpty()) {
                continue;
            }
            Object port = row.get("targetPort") != null ? row.get("targetPort") : row.get("port");
            if (!(port instanceof Number n) || n.intValue() < 1 || n.intValue() > 65535) {
                continue;
            }
            GatewayListenerDTO dto = new GatewayListenerDTO();
            Object name = row.get("name");
            dto.setName(name == null || name.toString().isBlank() ? "port-" + n.intValue() : name.toString());
            dto.setPort(n.intValue());
            dto.setProtocol(protocol);
            boolean tlsProtocol = "HTTPS".equals(protocol) || "TLS".equals(protocol);
            Object mode = row.get("tlsMode");
            String tlsMode = mode == null || mode.toString().isBlank()
                    ? ("HTTPS".equals(protocol) ? "Terminate" : "Passthrough")
                    : mode.toString();
            dto.setTlsMode(tlsProtocol ? tlsMode : null);
            Object secret = row.get("tlsSecret");
            dto.setTlsSecret(tlsProtocol && !"Passthrough".equals(tlsMode) && secret != null
                    ? secret.toString() : null);
            out.add(dto);
        }
        return out;
    }

    /** 把端口行裁成 Helm 的 Service 端口字段（listener/tlsSecret 只给平台用） */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> withoutGatewayListenerFields(Map<String, Object> values) {        if (!(values.get("service") instanceof Map<?, ?> service)
                || !(service.get("ports") instanceof List<?> ports)) {
            return values;
        }
        List<Map<String, Object>> cleaned = new ArrayList<>();
        for (Object o : ports) {
            if (!(o instanceof Map<?, ?> row)) {
                continue;
            }
            Map<String, Object> copy = new LinkedHashMap<>();
            for (String key : List.of("name", "port", "targetPort", "protocol", "nodePort")) {
                if (row.get(key) != null) {
                    copy.put(key, row.get(key));
                }
            }
            cleaned.add(copy);
        }
        Map<String, Object> svc = new LinkedHashMap<>((Map<String, Object>) service);
        svc.put("ports", cleaned);
        Map<String, Object> out = new LinkedHashMap<>(values);
        out.put("service", svc);
        return out;
    }

    /**
     * 存量快照的端口行没有 {@code listener}（功能上线前装的）。重放时按 recipe 默认行（同名/同端口）补齐；
     * 默认行也没有时按端口兜底：80→HTTP、443→TLS，其余保持「仅 Service」。
     *
     * <p>不补的话重放会得到「没有 listener」→ 不生成 Gateway CR，用户会以为功能没生效。
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> withGatewayListenerDefaults(Map<String, Object> values,
                                                           Map<String, Object> defaults) {
        if (!(values.get("service") instanceof Map<?, ?> service)
                || !(service.get("ports") instanceof List<?> rows)) {
            return values;
        }
        List<Map<String, Object>> defaultRows = List.of();
        if (defaults.get("service") instanceof Map<?, ?> defaultService
                && defaultService.get("ports") instanceof List<?> list) {
            defaultRows = (List<Map<String, Object>>) list;
        }
        List<Map<String, Object>> enriched = new ArrayList<>();
        for (Object o : rows) {
            if (!(o instanceof Map<?, ?> raw)) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>((Map<String, Object>) raw);
            if (row.get("listener") == null) {
                Map<String, Object> fallback = defaultRowFor(defaultRows, row);
                if (fallback != null && fallback.get("listener") != null) {
                    row.put("listener", fallback.get("listener"));
                    if (fallback.get("tlsMode") != null) {
                        row.put("tlsMode", fallback.get("tlsMode"));
                    }
                    if (fallback.get("tlsSecret") != null) {
                        row.put("tlsSecret", fallback.get("tlsSecret"));
                    }
                } else {
                    Object port = row.get("port");
                    int value = port instanceof Number n ? n.intValue() : -1;
                    String listener = value == 80 ? "HTTP" : value == 443 ? "TLS" : "";
                    row.put("listener", listener);
                    if ("TLS".equals(listener)) {
                        row.put("tlsMode", "Passthrough");
                    }
                }
            }
            enriched.add(row);
        }
        Map<String, Object> svc = new LinkedHashMap<>((Map<String, Object>) service);
        svc.put("ports", enriched);
        Map<String, Object> out = new LinkedHashMap<>(values);
        out.put("service", svc);
        return out;
    }

    private static Map<String, Object> defaultRowFor(List<Map<String, Object>> defaults,
                                                     Map<String, Object> row) {
        Object name = row.get("name");
        for (Map<String, Object> d : defaults) {
            if (name != null && name.equals(d.get("name"))) {
                return d;
            }
        }
        Object port = row.get("port");
        for (Map<String, Object> d : defaults) {
            if (port != null && port.equals(d.get("port"))) {
                return d;
            }
        }
        return null;
    }

    private static String tailOf(ComponentInstallResultDTO result) {
        if (result.getReleases() == null) {
            return null;
        }
        String joined = result.getReleases().stream()
                .map(r -> r.getReleaseName() + ": " + r.getStatus()
                        + (r.getMessage() == null ? "" : " — " + r.getMessage()))
                .collect(Collectors.joining("\n"));
        return joined.length() <= 8192 ? joined : joined.substring(joined.length() - 8192);
    }

    /** 启动时调用：超过 15 分钟仍 RUNNING 的标 UNKNOWN（进程重启/超时中断） */
    public int markStaleRunning() {
        List<PlatformComponentOperation> stale = mapper.selectStaleRunning(STALE_MINUTES);
        for (PlatformComponentOperation op : stale) {
            PlatformComponentOperation upd = new PlatformComponentOperation();
            upd.setId(op.getId());
            upd.setStatus(UNKNOWN);
            upd.setMessage("操作中断（进程重启或超时）：请查看 release 状态后重试");
            upd.setFinishedAt(new Date());
            mapper.updateById(upd);
        }
        return stale.size();
    }

    public PlatformComponentOperation get(Long id) {
        return mapper.selectByPrimaryKey(id);
    }

    /**
     * 最近一次**成功**的操作。
     * ⚠️ 它查不到"正在跑的操作"—— 轮询走 {@link #get(Long)}，页面首屏用
     * {@link #latestByCluster(String)}（取每个组件 id 最大的那条，含进行中的）。
     */
    public PlatformComponentOperation latest(String clusterId, String componentCode) {
        return mapper.selectLatestSuccess(clusterId, componentCode);
    }

    /**
     * OTel 组件的采样率（上次成功快照的 {@code __platform.otel.samplingPercentage}）；
     * 没有记录 → 默认 10。waypoint/ingress 的 Telemetry 用它，保证应用与网格采样一致。
     */
    public int otelSamplingPercentage(String clusterId) {
        Map<String, Object> platform = platformInputsOf(mapper.selectLatestSuccess(clusterId, "otel-operator"));
        if (platform.get("otel") instanceof Map<?, ?> m && m.get("samplingPercentage") instanceof Number n) {
            return n.intValue();
        }
        return DEFAULT_OTEL_SAMPLING;
    }

    /**
     * 管理员中止一条进行中的操作：只把平台侧记录置为终态 {@code CANCELED}，**不 kill helm 进程**。
     *
     * <p>存在的理由：worker 随进程重启消失后，RUNNING 记录会一直占着并发锁（要等 15 分钟标定）；
     * 或者 helm 早已失败/退出、但调用链没有回来。释放锁之后用户可以改配置重试；
     * 如果 helm 其实还在跑，下一次提交会被 helm 的 release 锁拒绝（报错可见），不会静默并发。
     */
    public void abandon(Long id, String operator) {
        PlatformComponentOperation op = mapper.selectByPrimaryKey(id);
        if (op == null) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "操作不存在：" + id);
        }
        if (!"PENDING".equals(op.getStatus()) && !"RUNNING".equals(op.getStatus())) {
            throw new CloudPlatformException(EnumResponseType.ERROR,
                    "该操作已结束（当前状态 " + op.getStatus() + "），无需中止");
        }
        PlatformComponentOperation upd = new PlatformComponentOperation();
        upd.setId(id);
        upd.setStatus("CANCELED");
        upd.setMessage("管理员中止" + (isBlank(operator) ? "" : "（" + operator + "）")
                + "；仅释放平台侧并发锁，若 helm 仍在运行请稍后确认 release 状态");
        upd.setFinishedAt(new Date());
        mapper.updateById(upd);
    }

    public List<PlatformComponentOperation> latestByCluster(String clusterId) {
        // 惰性标定：页面每次加载都会经过这里，比"只在启动时扫一次"更能兜住"重启时还没超时"的情况。
        // （platform-api 当前没有 @EnableScheduling，故不用定时任务。）
        markStaleRunning();
        return mapper.selectLatestPerComponent(clusterId);
    }

    /** message 列是 VARCHAR(1024)：helm 原始输出可能很长，落库前截断（完整输出在 log_tail） */
    /** 操作消息 = helm 结果 + 后置集成警告（组件装好了但同步失败时，两件事都要留下） */
    private static String messageWith(ComponentInstallResultDTO result, String note) {
        if (note == null) {
            return result.getMessage();
        }
        String base = result.getMessage();
        return base == null || base.isBlank() ? note : base + "；" + note;
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
