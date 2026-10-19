package com.coding.platformapi.services;

import com.coding.common.models.k8s.dto.WorkloadProbeResultDTO;
import com.coding.common.models.k8s.dto.WorkloadProbeTargetDTO;
import com.coding.data.mapper.system.PlatformComponentOperationMapper;
import com.coding.data.models.system.PlatformComponentOperation;
import com.coding.platformapi.cache.RedisJsonCache;
import com.coding.platformapi.components.ClusterComponentCatalog;
import com.coding.platformapi.components.ComponentDef;
import com.coding.platformapi.components.ComponentProbe;
import com.coding.platformapi.components.ComponentRecipe;
import com.coding.platformapi.components.ComponentRecipeCatalog;
import com.coding.platformapi.components.OtelRecipe;
import com.coding.platformapi.components.ReleaseSpec;
import com.coding.platformapi.k8s.K8sComponentClient;
import com.coding.platformapi.models.ComponentFlagDTO;
import com.coding.platformapi.models.ComponentStatusDTO;
import com.coding.platformapi.models.EditableFieldDTO;
import com.coding.platformapi.models.EsConfig;
import com.coding.platformapi.models.OtelInstallRequest;
import com.coding.platformapi.models.SecretRef;
import com.coding.platformapi.models.TempoStorageConfig;
import com.coding.platformapi.models.ThanosInstallRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 集群组件状态的<b>唯一派生实现</b>：目录（判什么）+ capability 列（API group 那一半）+
 * 一次活探测（工作负载那一半）→ 四态 → 两个投影。
 *
 * <h2>四态的意义</h2>
 * {@code UNKNOWN}（探测失败 / 未探测）与 {@code NOT_INSTALLED}（探测成功但没命中）必须分开 ——
 * 把"不知道"说成"没装"是本仓修过一次的坑（见 {@code MeshStatusDTO} 的注释）。
 *
 * <h2>为什么必须缓存</h2>
 * 门控收口后，<b>每个租户进 CI 页 / 网格页都会触发一次</b>。不挡的话一个 20 人的团队进进出出
 * 就是几百次集群调用。45s 是"多新算够新"的取舍：组件是分钟级变化的东西。
 * <b>缓存不随租户变</b> —— 探测结果里没有任何按租户切分的内容。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ClusterComponentService {

    public static final String HEALTHY = "INSTALLED_HEALTHY";
    public static final String UNHEALTHY = "INSTALLED_UNHEALTHY";
    public static final String NOT_INSTALLED = "NOT_INSTALLED";
    public static final String UNKNOWN = "UNKNOWN";

    private static final String KEY_PREFIX = "cluster:components:";
    /** 45s —— 组件是分钟级变化的东西；装完想看最新状态走「重新探测」（探完覆写缓存） */
    private static final Duration TTL = Duration.ofSeconds(45);
    private static final TypeReference<List<ComponentStatusDTO>> LIST_TYPE = new TypeReference<>() {};
    private static final TypeReference<Map<String, Object>> SNAPSHOT_TYPE = new TypeReference<>() {};
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final ClusterComponentCatalog catalog;
    private final ClusterCapabilityService capabilityService;
    private final K8sComponentClient client;
    private final RedisJsonCache cache;
    /** 可安装版本来自 recipe（按集群画像取哪一套） */
    private final ClusterService clusterService;
    private final ComponentRecipeCatalog recipes;
    /** 编辑表单要显示"当前生效"的地址：从上次成功操作的快照里读 */
    private final PlatformComponentOperationMapper operationMapper;

    /** full 投影：状态 + 版本 + 命名空间 + detail + 功能映射（管理员） */
    public List<ComponentStatusDTO> status(String clusterId) {
        return cache.get(KEY_PREFIX + clusterId, TTL, LIST_TYPE, () -> probe(clusterId));
    }

    /**
     * flags 投影：功能 code → 能不能用 + 一句话原因，**不含版本/命名空间/detail**。
     * <p>判定口径：该功能依赖的组件<b>全部</b>处于 {@link #HEALTHY} 才算可用。
     */
    public Map<String, ComponentFlagDTO> flags(String clusterId) {
        List<ComponentStatusDTO> all = status(clusterId);
        Map<String, ComponentFlagDTO> out = new LinkedHashMap<>();
        for (String feature : catalog.featureCodes()) {
            List<ComponentDef> deps = catalog.componentsOf(feature);
            List<String> missing = deps.stream()
                    .filter(d -> !HEALTHY.equals(stateOf(all, d.code())))
                    .map(ComponentDef::name).toList();
            out.put(feature, new ComponentFlagDTO(missing.isEmpty(), reasonOf(all, deps, missing), missing));
        }
        out.put("otel-logs", otelLogsFlag(clusterId, all));
        return out;
    }

    /**
     * 工作负载日志开关的可用性：**采集栈健康** 且上次成功安装时开了「应用日志」。
     *
     * <p>只装了 operator、没开应用日志时，gateway 没有 logs 管道 —— 工作负载这边的
     * 应用日志/文件日志上报都会被 gateway 丢弃，ES 里什么都不会有。这个旗标让工作负载页
     * 直接把日志开关禁掉并说明原因，而不是让用户配完才发现没数据。
     *
     * <p>与其它 feature 旗标不同，它读的是**配置**（上次成功操作的快照），不是安装状态。
     */
    private ComponentFlagDTO otelLogsFlag(String clusterId, List<ComponentStatusDTO> all) {
        if (!HEALTHY.equals(stateOf(all, "otel-operator"))) {
            return new ComponentFlagDTO(false, "OTel 采集栈未安装或未就绪", List.of("OpenTelemetry 采集栈"));
        }
        PlatformComponentOperation last = operationMapper.selectLatestSuccess(clusterId, "otel-operator");
        boolean logs = last != null && Boolean.TRUE.equals(logsEnabledOf(last.getValuesSnapshot()));
        return logs
                ? new ComponentFlagDTO(true, null, List.of())
                : new ComponentFlagDTO(false,
                        "OTel 采集栈未开启「应用日志」（gateway 没有 logs 管道），请先在集群组件页打开",
                        List.of());
    }

    /** {@code __platform.otel.logsEnabled}；快照缺失/旧格式返回 null */
    private static Boolean logsEnabledOf(String snapshotJson) {
        if (snapshotJson == null || snapshotJson.isBlank()) {
            return null;
        }
        try {
            Map<String, Object> snapshot = JSON.readValue(snapshotJson, SNAPSHOT_TYPE);
            if (!(snapshot.get("__platform") instanceof Map<?, ?> platform)
                    || !(platform.get("otel") instanceof Map<?, ?> otel)) {
                return null;
            }
            return otel.get("logsEnabled") instanceof Boolean b ? b : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 「重新探测」：探一次并**覆写**缓存。
     *
     * <p>⚠️ 不是"清缓存让下次重探" —— {@link RedisJsonCache} 的公开方法只有 get / getMany / setMany，
     * <b>没有 evict</b>，这是有意的（TTL-only，见 memory「别做手工失效」）。本类恰好受得了这条约束：
     * 失效点只有这一个，写成"探完覆写"即可。
     */
    public void refresh(String clusterId) {
        cache.setMany(Map.of(KEY_PREFIX + clusterId, probe(clusterId)), TTL);
    }

    // ---------- 派生 ----------

    private List<ComponentStatusDTO> probe(String clusterId) {
        Map<String, List<String>> groups = capabilityService.groups(clusterId);
        // 画像决定可用 recipe；读不到就按"没有 recipe"处理（版本列留空），不让探测整体失败
        String profile = profileOf(clusterId);

        // 目录 → 探测候选（顺序即结果顺序；(ns,kind) 在目录里唯一）
        List<String> codes = new ArrayList<>();
        List<WorkloadProbeTargetDTO> targets = new ArrayList<>();
        for (ComponentDef def : catalog.all()) {
            for (ComponentProbe p : def.probes()) {
                codes.add(def.code());
                targets.add(targetOf(p));
            }
        }

        Map<String, WorkloadProbeResultDTO> byCode = new HashMap<>();
        if (!targets.isEmpty()) {
            List<WorkloadProbeResultDTO> results;
            try {
                results = client.probe(clusterId, targets);
            } catch (Exception e) {
                // 探测整体失败（集群不可达 / 网关异常）：**不整体返回 UNKNOWN** ——
                // 只有依赖工作负载判据的组件失去答案；纯 group 判据的组件（gateway-api）仍能从
                // DB 快照得出答案（spec 决策 7：group 存在性的唯一判据就是 capability 列）。
                String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                log.warn("集群 {} 工作负载探测失败（工作负载判据按 UNKNOWN 处理）: {}", clusterId, msg);
                results = new ArrayList<>();
                for (WorkloadProbeTargetDTO t : targets) {
                    results.add(errorResult(t, msg));
                }
            }
            if (results == null) {
                results = List.of();
            }
            // ⚠️ 按入参下标对齐；条数不足**不得**静默当成"没命中" —— 那会把"探测结果不完整"
            //    说成"没装"（与 UNKNOWN/NOT_INSTALLED 同一条原则）。
            for (int i = 0; i < codes.size(); i++) {
                WorkloadProbeResultDTO r = i < results.size()
                        ? results.get(i)
                        : incompleteResult(targets.get(i));
                byCode.merge(codes.get(i), r, ClusterComponentService::prefer);
            }
        }
        List<ComponentStatusDTO> statuses = catalog.all().stream()
                .map(d -> compose(d, byCode, groups, profile)).toList();
        applyEffectivePlatformInputs(statuses, clusterId, profile);
        return statuses;
    }

    /**
     * 把「上次成功操作」里记的平台输入填回状态。
     *
     * <p>安装表单用平台默认值预填；**编辑表单**必须预填**当前生效**的地址 ——
     * 否则用户改完 webhook / Thanos Receive 后再打开编辑，看到的还是平台默认值，
     * 分不清到底哪个在生效。
     */
    private void applyEffectivePlatformInputs(List<ComponentStatusDTO> statuses, String clusterId, String profile) {
        Map<String, PlatformComponentOperation> latest;
        try {
            latest = operationMapper.selectLatestSuccesses(clusterId).stream()
                    .collect(Collectors.toMap(PlatformComponentOperation::getComponentCode,
                            o -> o, (a, b) -> a));
        } catch (Exception e) {
            // 读操作记录失败不影响状态探测本身；地址按未知处理（编辑表单退回平台默认值）
            log.warn("读取集群 {} 的组件操作快照失败: {}", clusterId, e.getMessage());
            return;
        }
        for (ComponentStatusDTO s : statuses) {
            PlatformComponentOperation op = latest.get(s.getCode());
            if (op == null) {
                continue;
            }
            Map<String, Object> snapshot = parseSnapshot(op.getValuesSnapshot());
            Map<String, Object> platform = platformOf(snapshot);
            if (s.isPlatformAlerting() && platform.get("alertWebhookUrl") instanceof String url) {
                s.setAlertWebhookUrl(url);
            }
            if (s.isThanosRemoteWrite() && platform.get("thanosReceiveUrl") instanceof String url) {
                s.setThanosReceiveUrl(url);
            }
            if (s.isThanosInstall()) {
                s.setThanosInstallCurrent(thanosInstallOf(platform));
            }
            if (s.isTektonProfile() && platform.get("tektonProfile") instanceof String p) {
                s.setTektonProfileCurrent(p);
            }
            if (s.isTempoRemoteWrite() && platform.get("otel") instanceof Map<?, ?> otelMap) {
                s.setTempoEndpoint(asString(((Map<?, ?>) otelMap).get("tempoEndpoint")));
            }
            if (s.isOtelInstrumentation()) {
                s.setOtelInstallCurrent(otelInstallOf(platform));
            }
            applyCurrentEditableValues(s, snapshot, profile);
        }
    }

    /**
     * 可编辑字段的"当前生效值"：上次成功的 values 快照里，组件本体 release 下按路径取。
     * 没有记录 / 路径不存在 → 不填（前端退回 recipe 默认值）。
     */
    @SuppressWarnings("unchecked")
    private void applyCurrentEditableValues(ComponentStatusDTO s, Map<String, Object> snapshot, String profile) {
        if (s.getEditableFields() == null || s.getEditableFields().isEmpty()) {
            return;
        }
        ComponentRecipe recipe = recipes.recipeOf(s.getCode(), profile);
        if (recipe == null || recipe.releases().isEmpty()) {
            return;
        }
        Object releaseValues = snapshot.get(recipe.releases().get(0).releaseName());
        if (!(releaseValues instanceof Map<?, ?>)) {
            return;
        }
        Map<String, Object> values = (Map<String, Object>) releaseValues;
        for (EditableFieldDTO f : s.getEditableFields()) {
            Object current = valueAtPath(values, f.getKey());
            if (current != null) {
                f.setCurrentValue(current);
            }
        }
    }

    /** 按点分路径从 values 树里取值（与安装侧 putPath 同一约定）；不存在 → null */
    private static Object valueAtPath(Map<String, Object> root, String path) {
        Object cursor = root;
        for (String part : path.split("\\.")) {
            if (!(cursor instanceof Map<?, ?> map)) {
                return null;
            }
            cursor = map.get(part);
            if (cursor == null) {
                return null;
            }
        }
        return cursor;
    }

    /** {@code values_snapshot} 整棵；解析失败按空处理（不猜） */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseSnapshot(String snapshotJson) {
        if (snapshotJson == null || snapshotJson.isBlank()) {
            return Map.of();
        }
        try {
            return JSON.readValue(snapshotJson, SNAPSHOT_TYPE);
        } catch (Exception e) {
            return Map.of();
        }
    }

    /** {@code values_snapshot.__platform}；没有按空处理 */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> platformOf(Map<String, Object> snapshot) {
        Object platform = snapshot.get("__platform");
        return platform instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    /** {@code __platform.thanosInstall} → 编辑表单的当前值；没有记录返回 null（表单退回空值） */
    private static ThanosInstallRequest thanosInstallOf(Map<String, Object> platform) {
        if (!(platform.get("thanosInstall") instanceof Map<?, ?> m)) {
            return null;
        }
        ThanosInstallRequest cur = new ThanosInstallRequest();
        cur.setNamespace(asString(m.get("namespace")));
        cur.setSecretName(asString(m.get("secretName")));
        cur.setEndpoint(asString(m.get("endpoint")));
        cur.setBucket(asString(m.get("bucket")));
        cur.setInsecureSkipVerify(Boolean.TRUE.equals(m.get("insecureSkipVerify")));
        return cur;
    }

    /** {@code __platform.otel} → 编辑表单的当前值；没有记录返回 null */
    private static OtelInstallRequest otelInstallOf(Map<String, Object> platform) {
        if (!(platform.get("otel") instanceof Map<?, ?> m)) {
            return null;
        }
        OtelInstallRequest cur = new OtelInstallRequest();
        cur.setTempoEndpoint(asString(m.get("tempoEndpoint")));
        cur.setSamplingPercentage(asInt(m.get("samplingPercentage")));
        cur.setGatewayReplicas(asInt(m.get("gatewayReplicas")));
        cur.setLogsEnabled(m.get("logsEnabled") instanceof Boolean b ? b : null);
        cur.setTempoRetention(asString(m.get("tempoRetention")));
        cur.setTempoTlsMode(asString(m.get("tempoTlsMode")));
        cur.setTempoStorage(tempoStorageOf(m.get("tempoStorage")));
        cur.setEs(esOf(m.get("es")));
        return cur;
    }

    private static TempoStorageConfig tempoStorageOf(Object value) {
        if (!(value instanceof Map<?, ?> m)) {
            return null;
        }
        TempoStorageConfig out = new TempoStorageConfig();
        out.setEndpoint(asString(m.get("endpoint")));
        out.setBucket(asString(m.get("bucket")));
        out.setCredentialSecret(secretRefOf(m.get("credentialSecret")));
        out.setInsecureSkipVerify(Boolean.TRUE.equals(m.get("insecureSkipVerify")));
        out.setPlaintext(m.get("plaintext") instanceof Boolean b ? b : null);
        return out;
    }

    private static EsConfig esOf(Object value) {
        if (!(value instanceof Map<?, ?> m)) {
            return null;
        }
        EsConfig out = new EsConfig();
        out.setEndpoint(asString(m.get("endpoint")));
        out.setCredentialSecret(secretRefOf(m.get("credentialSecret")));
        out.setInsecureSkipVerify(Boolean.TRUE.equals(m.get("insecureSkipVerify")));
        return out;
    }

    private static SecretRef secretRefOf(Object value) {
        if (!(value instanceof Map<?, ?> m)) {
            return null;
        }
        return new SecretRef(asString(m.get("namespace")), asString(m.get("name")));
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

    private static String asString(Object value) {
        return value instanceof String s ? s : null;
    }

    private String profileOf(String clusterId) {
        try {
            return clusterService.requireProfile(clusterId);
        } catch (Exception e) {
            log.warn("读取集群 {} 画像失败，可安装版本按空处理: {}", clusterId, e.getMessage());
            return null; // recipeOf(code, null) 返回 null → 版本列表为空
        }
    }

    /**
     * 平台可安装的 chart 版本：**只取 recipe 的第一个 release** —— 它才是组件本体。
     *
     * <p>其余 release 是随附底座（管理集群的 Prometheus recipe = platform-kps 92.10.1
     * + platform-thanos 0.47.2）。把它们的版本混进来，UI 会显示
     * 「已装 92.10.1，可升级到 0.47.2」—— 两个 chart 的版本根本不可比（2026-10-18 实锤）。
     */
    private static List<String> availableVersions(ComponentRecipe recipe) {
        if (recipe == null || recipe.releases().isEmpty()) {
            return List.of();
        }
        return List.of(recipe.releases().get(0).chartVersion());
    }

    /** 可编辑配置项（⚙️）：按各 release 声明的 editableValues 展平、同 key 去重 */
    private static List<EditableFieldDTO> editableFields(ComponentRecipe recipe) {
        if (recipe == null) {
            return List.of();
        }
        Map<String, EditableFieldDTO> byKey = new LinkedHashMap<>();
        for (ReleaseSpec r : recipe.releases()) {
            r.editableValues().forEach((key, defaultValue) ->
                    byKey.putIfAbsent(key, field(key, defaultValue)));
        }
        return List.copyOf(byKey.values());
    }

    private static EditableFieldDTO field(String key, Object defaultValue) {
        EditableFieldDTO f = new EditableFieldDTO();
        f.setKey(key);
        f.setLabel(ComponentRecipeCatalog.labelOf(key));
        f.setType(typeOf(key, defaultValue));
        f.setDefaultValue(defaultValue);
        return f;
    }

    /**
     * 表单控件类型：存储的两个字段按 key 后缀特判（{@code storageClass} = 集群 StorageClass 下拉，
     * {@code size} = 带单位的容量输入），其余按默认值的 Java 类型。
     */
    private static String typeOf(String key, Object defaultValue) {
        if (key.endsWith(".storageClassName")) {
            return "storageClass";
        }
        if (key.endsWith(".requests.storage")) {
            return "size";
        }
        if (defaultValue instanceof Boolean) {
            return "boolean";
        }
        if (defaultValue instanceof Number) {
            return "number";
        }
        if (defaultValue instanceof Map || defaultValue instanceof List) {
            return "json";
        }
        return "string";
    }

    /** 四态合成：**探测失败 = UNKNOWN，不是 NOT_INSTALLED**（集群断开时说"没装"是本仓修过的坑） */
    private ComponentStatusDTO compose(ComponentDef def, Map<String, WorkloadProbeResultDTO> byCode,
                                       Map<String, List<String>> groups, String profile) {
        ComponentStatusDTO s = new ComponentStatusDTO();
        s.setCode(def.code());
        s.setName(def.name());
        s.setFeatures(def.features());
        ComponentRecipe recipe = recipes.recipeOf(def.code(), profile);
        s.setAvailableVersions(availableVersions(recipe));
        s.setEditableFields(editableFields(recipe));
        s.setPlatformAlerting(recipe != null && recipe.platformAlerting());
        boolean thanosRemoteWrite = recipe != null && recipe.thanosRemoteWrite();
        s.setThanosRemoteWrite(thanosRemoteWrite);
        // 表单预填只来自画像默认值（管理集群 = 集群内 receive）；业务集群的跨集群入口由用户填
        s.setDefaultThanosReceiveUrl(!thanosRemoteWrite ? null : recipe.thanosReceiveDefault());
        s.setThanosInstall(recipe != null && recipe.thanosInstall());
        s.setCalicoNetwork(recipe != null && recipe.calicoNetwork());
        s.setTektonProfile(recipe != null && recipe.tektonProfile());
        s.setDefaultTektonProfile(recipe != null && recipe.tektonProfile()
                ? ComponentRecipeCatalog.DEFAULT_TEKTON_PROFILE : null);
        OtelRecipe otel = recipe == null ? null : recipe.otel();
        s.setOtelInstrumentation(otel != null && otel.instrumentation());
        s.setTempoInstall(otel != null && otel.tempoInstall());
        s.setTempoRemoteWrite(otel != null && otel.tempoRemoteWrite());
        s.setDefaultTempoEndpoint(otel == null ? null : otel.tempoEndpointDefault());

        // capability 列为空 = 未探测（既有口径）—— 不猜"没装"。这是**全局**判据：
        // 它对将来可能出现的"只有工作负载判据"的组件同样成立，否则同一集群里会出现
        // 一半"未探测"、一半"没装"的自相矛盾（spec §3.2）。
        if (groups.isEmpty()) {
            s.setState(UNKNOWN);
            s.setDetail("尚未探测集群 API 能力");
            return s;
        }

        boolean groupHit = def.apiGroup() != null && groups.containsKey(def.apiGroup());
        WorkloadProbeResultDTO w = byCode.get(def.code());

        if (w != null && w.getError() != null) {
            s.setState(UNKNOWN);
            s.setDetail("探测失败：" + w.getError());
            return s;
        }
        // ⭐ 有活判据（工作负载 / CRD 探测）时它是**唯一权威**。API group 来自 DB 快照，
        //    而 Helm 卸载**不删 CRD**、capability 快照也不会自己刷新 ——"group 还在"
        //    永远不代表组件还在跑。2026-10-18 实测：`helm uninstall` 旧 Prometheus 后，
        //    monitor 下已无 Deployment，group 却因 10 个残留 CRD 仍在，页面仍显示「已安装且健康」。
        if (w != null && !w.isFound()) {
            s.setState(NOT_INSTALLED);
            s.setDetail(notFoundDetail(w, def, groupHit));
            return s;
        }
        if (w == null && !groupHit) {
            s.setState(NOT_INSTALLED);
            s.setDetail("在所有判据上都没命中");
            return s;
        }
        if (w != null) {
            s.setNamespace(w.getNamespace());
            s.setVersion(w.getVersion());
            s.setReady(w.getReady());
            s.setDesired(w.getDesired());
            s.setDetail(w.getDetail());
        }
        // ⚠️ desired == 0 判不健康：被 scale 到 0 的组件没在跑，不是"健康"。
        //    desired == null = 这条判据没有副本语义（纯 CRD 组件）→ 命中即健康。
        boolean healthy = w == null
                || w.getDesired() == null
                || (w.getDesired() > 0 && w.getReady() != null && w.getReady().equals(w.getDesired()));
        s.setState(healthy ? HEALTHY : UNHEALTHY);
        return s;
    }

    /**
     * 活判据没命中时的详情。若 capability 快照里还留着这个 group，要点明那是 **CRD 残留**
     * （Helm 卸载不删 CRD），否则用户排障时看不出"以前为什么显示已安装"。
     */
    private static String notFoundDetail(WorkloadProbeResultDTO w, ComponentDef def, boolean groupHit) {
        String base = w.getDetail() != null ? w.getDetail() : "在平台已知的位置没有找到";
        if (!groupHit) {
            return base;
        }
        return base + "（API group " + def.apiGroup()
                + " 仍在：Helm 卸载不删除 CRD，不代表组件还在运行）";
    }

    private static WorkloadProbeResultDTO incompleteResult(WorkloadProbeTargetDTO t) {
        return errorResult(t, "探测结果不完整（适配器少回了一条）");
    }

    private static WorkloadProbeResultDTO errorResult(WorkloadProbeTargetDTO t, String error) {
        WorkloadProbeResultDTO r = new WorkloadProbeResultDTO();
        r.setNamespace(t.getNamespace());
        r.setKind(t.getKind());
        r.setFound(false);
        r.setError(error);
        return r;
    }

    private static WorkloadProbeTargetDTO targetOf(ComponentProbe p) {
        WorkloadProbeTargetDTO t = new WorkloadProbeTargetDTO();
        t.setNamespace(p.namespace());
        t.setKind(p.kind());
        t.setImageRepo(p.imageRepo());
        t.setName(p.name());
        // CRD 判据要带上"读哪个标签"；漏了它只能靠 *bundle-version 兜底（能跑，但显式配置失效）
        t.setVersionLabel(p.versionLabel());
        return t;
    }

    /** 一个组件有多条判据时怎么合并：命中优先于未命中，成功结果优先于错误结果 */
    private static WorkloadProbeResultDTO prefer(WorkloadProbeResultDTO a, WorkloadProbeResultDTO b) {
        if (a.isFound() != b.isFound()) {
            return a.isFound() ? a : b;
        }
        return a.getError() == null ? a : b;
    }

    private static String stateOf(List<ComponentStatusDTO> all, String code) {
        return all.stream().filter(s -> s.getCode().equals(code))
                .map(ComponentStatusDTO::getState).findFirst().orElse(UNKNOWN);
    }

    private static String reasonOf(List<ComponentStatusDTO> all, List<ComponentDef> deps, List<String> missing) {
        if (missing.isEmpty()) {
            return null;
        }
        boolean unknown = deps.stream().map(d -> stateOf(all, d.code())).anyMatch(UNKNOWN::equals);
        return (unknown ? "探测不到：" : "缺 ") + String.join("、", missing);
    }
}
