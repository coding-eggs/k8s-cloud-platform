package com.coding.platformapi.services;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.k8s.dto.ComponentInstallResultDTO;
import com.coding.common.models.k8s.dto.ReleaseInstallSpecDTO;
import com.coding.data.mapper.system.PlatformComponentOperationMapper;
import com.coding.data.models.system.PlatformComponentOperation;
import com.coding.platformapi.components.ComponentRecipe;
import com.coding.platformapi.components.ComponentRecipeCatalog;
import com.coding.platformapi.components.ReleaseSpec;
import com.coding.platformapi.k8s.K8sComponentClient;
import com.coding.platformapi.models.ComponentApplyRequest;
import com.coding.platformapi.models.ComponentStatusDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<Map<String, Map<String, Object>>> SNAPSHOT_TYPE =
            new TypeReference<>() {};

    private final PlatformComponentOperationMapper mapper;
    private final K8sComponentClient client;
    private final ComponentRecipeCatalog recipes;
    private final ClusterService clusterService;
    private final ClusterComponentService components;

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

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
        // spec 决策 10：只升级平台自己装的 release，手工装的一律不接管
        String action = req.getAction() == null ? "INSTALL" : req.getAction();
        if ("UPGRADE".equals(action) && last == null) {
            throw new CloudPlatformException(EnumResponseType.ERROR,
                    "该组件不是平台安装的，平台不接管（只升级平台自己装的 release）");
        }

        // spec §3.3：升级复用上次成功操作里的 values（本次显式传入的字段覆盖它）
        List<ReleaseSpec> effective = mergeValues(recipe.releases(), req.getEditableValues(),
                parseSnapshot(last == null ? null : last.getValuesSnapshot()));
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
        op.setValuesSnapshot(JSON.writeValueAsString(snapshotOf(effective)));
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
        executor.submit(() -> run(id, req.getClusterId(), req.getComponentCode(), op.getAction(),
                List.copyOf(effective)));
        return id;
    }

    /** 前置组件必须已 INSTALLED_HEALTHY（批① 的 metrics-server 无前置，批② 起生效） */
    private void requireDependencies(String clusterId, ComponentRecipe recipe) {
        if (recipe.requires() == null || recipe.requires().isEmpty()) {
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
     * 合并 values：契约值打底，用户值只能覆盖 recipe **声明过**的可编辑 key。
     * 注意这是**顶层 key** 的合并；批② 的嵌套 values（如 prometheus.prometheusSpec.*）
     * 要么用扁平 key，要么在这里加路径式合并 —— 届时补用例。
     */
    private static List<ReleaseSpec> mergeValues(List<ReleaseSpec> releases, Map<String, Object> editable,
                                                 Map<String, Map<String, Object>> previous) {
        List<ReleaseSpec> out = new ArrayList<>();
        for (ReleaseSpec r : releases) {
            // 优先级：上次的值 < 本次可编辑值 < 🔒 契约值（契约永远最后压，不能被历史值覆盖）
            Map<String, Object> merged = new LinkedHashMap<>(
                    previous.getOrDefault(r.releaseName(), Map.of()));
            merged.putAll(r.contractValues());
            if (editable != null) {
                for (String key : r.editableValues().keySet()) {
                    if (editable.containsKey(key)) {
                        merged.put(key, editable.get(key));
                    }
                }
            }
            out.add(new ReleaseSpec(r.releaseName(), r.namespace(), r.chartCode(),
                    r.chartVersion(), merged, r.editableValues()));
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
    private static String resolveTargetVersion(List<ReleaseSpec> releases, String requested) {
        if (requested == null || requested.isBlank()) {
            return releases.get(0).chartVersion();
        }
        boolean known = releases.stream().anyMatch(r -> requested.equals(r.chartVersion()));
        if (!known) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "未知的目标版本：" + requested);
        }
        return requested;
    }

    private void run(Long id, String clusterId, String componentCode, String action,
                     List<ReleaseSpec> releases) {
        PlatformComponentOperation running = new PlatformComponentOperation();
        running.setId(id);
        running.setStatus(RUNNING);
        mapper.updateById(running);
        try {
            ComponentInstallResultDTO result =
                    client.applyComponent(clusterId, componentCode, action, toDtos(releases));
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
