package com.coding.platformapi.services;

import com.coding.common.models.k8s.dto.WorkloadProbeResultDTO;
import com.coding.common.models.k8s.dto.WorkloadProbeTargetDTO;
import com.coding.platformapi.cache.RedisJsonCache;
import com.coding.platformapi.components.ClusterComponentCatalog;
import com.coding.platformapi.components.ComponentDef;
import com.coding.platformapi.components.ComponentProbe;
import com.coding.platformapi.components.ComponentRecipe;
import com.coding.platformapi.components.ComponentRecipeCatalog;
import com.coding.platformapi.components.ReleaseSpec;
import com.coding.platformapi.k8s.K8sComponentClient;
import com.coding.platformapi.models.ComponentFlagDTO;
import com.coding.platformapi.models.ComponentStatusDTO;
import com.coding.platformapi.models.EditableFieldDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    private final ClusterComponentCatalog catalog;
    private final ClusterCapabilityService capabilityService;
    private final K8sComponentClient client;
    private final RedisJsonCache cache;
    /** 可安装版本来自 recipe（按集群画像取哪一套） */
    private final ClusterService clusterService;
    private final ComponentRecipeCatalog recipes;

    /** 告警回调地址的平台默认值：前端安装表单预填（用户可改，clusterId 由后端拼） */
    @Value("${platform.alert.webhook.url:}")
    private String defaultAlertWebhookUrl;

    /** Thanos Receive 地址的平台默认值：安装表单预填（remoteWrite 目标） */
    @Value("${thanos.receive.url:}")
    private String defaultThanosReceiveUrl;
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
        return out;
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
        return catalog.all().stream().map(d -> compose(d, byCode, groups, profile)).toList();
    }

    private String profileOf(String clusterId) {
        try {
            return clusterService.requireProfile(clusterId);
        } catch (Exception e) {
            log.warn("读取集群 {} 画像失败，可安装版本按空处理: {}", clusterId, e.getMessage());
            return null; // recipeOf(code, null) 返回 null → 版本列表为空
        }
    }

    /** 平台可安装的 chart 版本（去重，保持 recipe 顺序）；没有 recipe → 空列表 */
    private static List<String> availableVersions(ComponentRecipe recipe) {
        if (recipe == null) {
            return List.of();
        }
        return recipe.releases().stream()
                .map(ReleaseSpec::chartVersion)
                .distinct()
                .toList();
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
        f.setType(typeOf(defaultValue));
        f.setDefaultValue(defaultValue);
        return f;
    }

    private static String typeOf(Object defaultValue) {
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
        s.setDefaultAlertWebhookUrl(recipe != null && recipe.platformAlerting()
                ? defaultAlertWebhookUrl : null);
        s.setThanosRemoteWrite(recipe != null && recipe.thanosRemoteWrite());
        s.setDefaultThanosReceiveUrl(recipe != null && recipe.thanosRemoteWrite()
                ? defaultThanosReceiveUrl : null);
        s.setThanosInstall(recipe != null && recipe.thanosInstall());

        // capability 列为空 = 未探测（既有口径）—— 不猜"没装"。这是**全局**判据：
        // 它对只有工作负载判据的组件（istio-ambient）同样成立，否则同一集群里会出现
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
        boolean workloadHit = w != null && w.isFound();
        if (!groupHit && !workloadHit) {
            s.setState(NOT_INSTALLED);
            s.setDetail(w != null && w.getDetail() != null ? w.getDetail() : "在所有判据上都没命中");
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
