package com.coding.platformapi.services;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.k8s.dto.ComponentInstallResultDTO;
import com.coding.common.models.k8s.dto.ReleaseInstallSpecDTO;
import com.coding.data.mapper.system.PlatformComponentOperationMapper;
import com.coding.data.models.system.PlatformComponentOperation;
import com.coding.platformapi.components.ComponentRecipe;
import com.coding.platformapi.components.ComponentRecipeCatalog;
import com.coding.platformapi.components.DeepMerge;
import com.coding.platformapi.components.ReleaseSpec;
import com.coding.platformapi.components.ValueTemplate;
import com.coding.platformapi.k8s.K8sComponentClient;
import com.coding.platformapi.k8s.K8sServerGateway;
import com.coding.platformapi.models.ComponentApplyRequest;
import com.coding.platformapi.models.CalicoInstallRequest;
import com.coding.platformapi.models.ComponentStatusDTO;
import com.coding.platformapi.models.ThanosInstallRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

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

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<Map<String, Map<String, Object>>> SNAPSHOT_TYPE =
            new TypeReference<>() {};

    private final PlatformComponentOperationMapper mapper;
    private final K8sComponentClient client;
    private final ComponentRecipeCatalog recipes;
    private final ClusterService clusterService;
    private final ClusterComponentService components;

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    /**
     * 业务集群 Prometheus 的 remoteWrite 目标（管理集群 Thanos receive）。
     * 空值时凡是引用 {@code ${thanos.receive.url}} 的 recipe 都会**明确报错** ——
     * 下发一个空 URL 装出来的 Prometheus 永远上报不了，且运行时没有报错。
     */
    @Value("${thanos.receive.url:}")
    private String thanosReceiveUrl;

    /** Calico Installation 的 CIDR 与封装方式（spec §5.5）；为空时安装 calico 会明确报错 */
    @Value("${platform.calico.pod-cidr:}")
    private String calicoPodCidr;

    @Value("${platform.calico.encapsulation:}")
    private String calicoEncapsulation;

    /** 集群里的 Alertmanager 回调平台的**完整端点**（含 /alert/webhook）；代码只追加 ?clusterId= */
    @Value("${platform.alert.webhook.url:http://127.0.0.1:8081/alert/webhook}")
    private String alertWebhookBaseUrl;

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

        // 告警回调地址 / Thanos Receive：表单值 > 上次成功操作里记的值 > 平台配置。
        // platformAlerting / thanosRemoteWrite 的组件没有地址就直接拒（别装完才失败）
        Map<String, Object> previousPlatform = platformInputsOf(last);
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

        // spec 决策 10：只升级平台自己装的 release，手工装的一律不接管
        String action = req.getAction() == null ? "INSTALL" : req.getAction();
        if ("UPGRADE".equals(action) && last == null) {
            throw new CloudPlatformException(EnumResponseType.ERROR,
                    "该组件不是平台安装的，平台不接管（只升级平台自己装的 release）");
        }

        // spec §3.3：升级复用上次成功操作里的 values（本次显式传入的字段覆盖它）
        List<ReleaseSpec> merged = mergeValues(recipe.releases(), req.getEditableValues(),
                parseSnapshot(last == null ? null : last.getValuesSnapshot()));
        // 占位符（${cluster.name} / ${thanos.receive.url}）在落库前解析 —— 配置缺失要立刻失败，不留失败记录
        List<ReleaseSpec> effective = resolveValues(merged, req.getClusterId(), thanosUrl, calico);
        String targetVersion = resolveTargetVersion(effective, req.getTargetVersion());

        PlatformComponentOperation op = new PlatformComponentOperation();
        op.setClusterId(req.getClusterId());
        op.setComponentCode(req.getComponentCode());
        op.setAction(action);
        op.setProfile(profile);
        op.setFromVersion(last == null ? null : last.getToVersion());
        op.setToVersion(targetVersion);
        op.setReleaseNames(JSON.writeValueAsString(
                effective.stream().map(ReleaseSpec::releaseName).toList()));
        op.setValuesSnapshot(JSON.writeValueAsString(
                snapshotWithPlatformInputs(effective, recipe, alertWebhookUrl, thanosUrl, thanos, calico)));
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
                        List.copyOf(effective), recipe.platformAlerting(), alertWebhookUrl, thanos)));
        return id;
    }

    /** 前置组件必须已 INSTALLED_HEALTHY（批① 的 metrics-server 无前置，批② 起生效） */
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
            Map<String, Object> merged = DeepMerge.merge(pathNested(r, r.editableValues()),
                    previous.getOrDefault(r.releaseName(), Map.of()));
            merged = DeepMerge.merge(merged, pathNested(r, editable));
            merged = DeepMerge.merge(merged, r.contractValues());
            out.add(new ReleaseSpec(r.releaseName(), r.namespace(), r.chartCode(),
                    r.chartVersion(), merged, r.editableValues()));
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

    /** 把 recipe 里的占位符替换成真实值（集群名、Thanos receive 地址） */
    @SuppressWarnings("unchecked")
    private List<ReleaseSpec> resolveValues(List<ReleaseSpec> releases, String clusterId, String thanosUrl,
                                            CalicoInstallRequest calico) {
        Map<String, String> context = new LinkedHashMap<>();
        context.put("cluster.name", clusterService.requireName(clusterId));
        context.put("thanos.receive.url", thanosUrl == null ? "" : thanosUrl);
        context.put("calico.podCidr", calico != null && calico.getPodCidr() != null
                ? calico.getPodCidr() : (calicoPodCidr == null ? "" : calicoPodCidr));
        context.put("calico.encapsulation", calico != null && calico.getEncapsulation() != null
                ? calico.getEncapsulation() : (calicoEncapsulation == null ? "" : calicoEncapsulation));
        List<ReleaseSpec> out = new ArrayList<>();
        for (ReleaseSpec r : releases) {
            Map<String, Object> resolved =
                    (Map<String, Object>) ValueTemplate.resolve(r.contractValues(), context);
            out.add(new ReleaseSpec(r.releaseName(), r.namespace(), r.chartCode(),
                    r.chartVersion(), resolved, r.editableValues()));
        }
        return out;
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
                                                                  CalicoInstallRequest calico) {
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

    /** Calico 参数：表单 > 上次成功 > 平台配置 */
    @SuppressWarnings("unchecked")
    private CalicoInstallRequest resolveCalicoInstall(CalicoInstallRequest requested, Object previous) {
        Map<String, Object> prev = previous instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
        CalicoInstallRequest out = new CalicoInstallRequest();
        out.setPodCidr(firstNonBlank(
                requested == null ? null : requested.getPodCidr(), asString(prev.get("podCidr")), calicoPodCidr));
        out.setEncapsulation(firstNonBlank(
                requested == null ? null : requested.getEncapsulation(),
                asString(prev.get("encapsulation")), calicoEncapsulation));
        if (isBlank(out.getPodCidr()) && isBlank(out.getEncapsulation())) {
            return null;
        }
        return out;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
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
     * 目标版本只能是**组件本体**（recipe 第一个 release）的版本。
     *
     * <p>多 release 组件（管理集群的 Prometheus = platform-kps + platform-thanos）里，
     * 随附 release 的版本不是组件的版本 —— 放它进来会把操作记录写成"已升级到 0.47.2"，
     * 后续页面按这个版本比较，永远显示可升级。
     */
    private static String resolveTargetVersion(List<ReleaseSpec> releases, String requested) {
        String primary = releases.get(0).chartVersion();
        if (requested == null || requested.isBlank()) {
            return primary;
        }
        if (!primary.equals(requested)) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "未知的目标版本：" + requested);
        }
        return requested;
    }

    private void run(Long id, String clusterId, String componentCode, String action,
                     List<ReleaseSpec> releases, boolean platformAlerting, String alertWebhookUrl,
                     ThanosInstallRequest thanos) {
        PlatformComponentOperation running = new PlatformComponentOperation();
        running.setId(id);
        running.setStatus(RUNNING);
        mapper.updateById(running);
        try {
            // Thanos 的对象存储 Secret 必须在 helm 之前就位（Pod 会挂载它）
            if (thanos != null) {
                client.applyThanosObjstore(clusterId, thanos.getNamespace(), thanos.getSecretName(),
                        thanos.getEndpoint(), thanos.getBucket(), thanos.isInsecureSkipVerify(),
                        thanosReleaseNamespace(releases), THANOS_OBJSTORE_SECRET);
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
            PlatformComponentOperation done = new PlatformComponentOperation();
            done.setId(id);
            done.setStatus(result.isSuccess() ? SUCCESS : FAILED);
            done.setMessage(truncate(result.getMessage(), 1000));
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
        String value = firstNonBlank(requested, previous, alertWebhookBaseUrl);
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
    /**
     * 表单值优先，其次上次成功里的值，最后**画像默认**（管理集群 = 集群内 receive）或平台配置
     * （业务集群的跨集群入口）。画像默认优先于平台配置：同一个 {@code thanos.receive.url}
     * 是业务集群指向管理集群的地址，拿来当管理集群自己的 remoteWrite 会写回它自己。
     */
    private String resolveThanosReceiveUrl(String requested, String previous, String profileDefault) {
        String fallback = profileDefault != null && !profileDefault.isBlank() ? profileDefault : thanosReceiveUrl;
        String value = firstNonBlank(requested, previous, fallback);
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
            dto.setValues(r.contractValues());
            out.add(dto);
        }
        return out;
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

    public List<PlatformComponentOperation> latestByCluster(String clusterId) {
        // 惰性标定：页面每次加载都会经过这里，比"只在启动时扫一次"更能兜住"重启时还没超时"的情况。
        // （platform-api 当前没有 @EnableScheduling，故不用定时任务。）
        markStaleRunning();
        return mapper.selectLatestPerComponent(clusterId);
    }

    /** message 列是 VARCHAR(1024)：helm 原始输出可能很长，落库前截断（完整输出在 log_tail） */
    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
