package com.coding.platformapi.services;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.app.AppObjectRefDTO;
import com.coding.common.models.app.AppObjectStatusDTO;
import com.coding.common.models.app.AppProblemDTO;
import com.coding.common.models.app.AppRenderResultDTO;
import com.coding.common.models.app.AppStatusRequestDTO;
import com.coding.common.models.app.AppStatusResultDTO;
import com.coding.data.mapper.system.PlatformAppDependencyMapper;
import com.coding.data.mapper.system.PlatformAppExecutionMapper;
import com.coding.data.mapper.system.PlatformAppReleaseMapper;
import com.coding.data.mapper.system.PlatformAppServiceMapper;
import com.coding.data.mapper.system.PlatformApplicationMapper;
import com.coding.data.models.system.PlatformAppDependency;
import com.coding.data.models.system.PlatformAppExecution;
import com.coding.data.models.system.PlatformAppRelease;
import com.coding.data.models.system.PlatformAppService;
import com.coding.data.models.system.PlatformApplication;
import com.coding.platformapi.app.AppObjectNames;
import com.coding.platformapi.app.AppPending;
import com.coding.platformapi.app.AppServiceDesired;
import com.coding.platformapi.app.AppSnapshot;
import com.coding.platformapi.k8s.K8sAppClient;
import com.coding.platformapi.k8s.K8sServerGateway;
import com.coding.platformapi.models.AppExecutionView;
import com.coding.platformapi.models.AppOverviewView;
import com.coding.platformapi.models.AppReleasePreviewView;
import com.coding.platformapi.models.AppReleaseView;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * 发布：预览、创建、列表/详情、概览、执行进度与续跑。
 *
 * <p>快照 = 线上服务 + 勾选服务的期望配置 − 勾选的待删除服务；未勾选的改动仍留在 desired 里继续算待发布。
 * 快照在创建那一刻定稿并存成字符串，之后再改 desired 不影响这次发布（执行器只读快照）。
 */
@Slf4j
@Service
public class AppReleaseService {

    private static final ObjectMapper JSON = JsonMapper.builder().build();
    private static final JavaType OBJECT_LIST =
            JSON.getTypeFactory().constructCollectionType(List.class, AppObjectRefDTO.class);
    private static final int NOTE_MAX = 512;

    private final PlatformApplicationMapper apps;
    private final PlatformAppServiceMapper services;
    private final PlatformAppDependencyMapper dependencyRows;
    private final PlatformAppReleaseMapper releases;
    private final PlatformAppExecutionMapper executions;
    private final AppDependencyService dependencies;
    private final AppReleasePipeline pipeline;
    private final AppReleaseExecutor executor;
    private final ApplicationDeployCredentials credentials;
    private final K8sAppClient client;
    private final TransactionTemplate tx;

    public AppReleaseService(PlatformApplicationMapper apps, PlatformAppServiceMapper services,
                             PlatformAppDependencyMapper dependencyRows, PlatformAppReleaseMapper releases,
                             PlatformAppExecutionMapper executions, AppDependencyService dependencies,
                             AppReleasePipeline pipeline, AppReleaseExecutor executor,
                             ApplicationDeployCredentials credentials, K8sAppClient client,
                             PlatformTransactionManager transactionManager) {
        this.apps = apps;
        this.services = services;
        this.dependencyRows = dependencyRows;
        this.releases = releases;
        this.executions = executions;
        this.dependencies = dependencies;
        this.pipeline = pipeline;
        this.executor = executor;
        this.credentials = credentials;
        this.client = client;
        this.tx = new TransactionTemplate(transactionManager);
    }

    // ------------------------------------------------------------------ 预览

    public AppReleasePreviewView preview(String appId, List<String> selectedKeys, Map<String, String> images,
                                         String tenantId) {
        PlatformApplication app = AppGuard.require(apps, appId, tenantId);
        Plan plan = plan(app, selectedKeys, images);

        List<String> problems = new ArrayList<>(plan.errors);
        List<String> added = List.of();
        List<String> removed = List.of();
        if (plan.errors.isEmpty() && plan.required.isEmpty() && !plan.selected.isEmpty()) {
            problems.addAll(pipeline.problems(app, plan.next));
            if (problems.isEmpty()) {
                try {
                    int nextNo = nextReleaseNo(app.getId());
                    AppRenderResultDTO rendered = client.render(pipeline.buildRequest(app, nextNo, plan.next));
                    List<AppProblemDTO> renderProblems = rendered.getProblems() == null
                            ? List.of() : rendered.getProblems();
                    renderProblems.stream().filter(p -> "ERROR".equals(p.getSeverity()))
                            .forEach(p -> problems.add(p.getMessage()));
                    Set<String> before = objectIds(liveObjects(app));
                    Set<String> after = objectIds(rendered.getObjects());
                    added = after.stream().filter(x -> !before.contains(x)).toList();
                    removed = before.stream().filter(x -> !after.contains(x)).toList();
                } catch (RuntimeException e) {
                    problems.add("渲染失败：" + e.getMessage());
                }
            }
        }
        List<AppReleasePreviewView.Change> changes = plan.changes.stream()
                .map(c -> toChange(c, plan)).toList();
        List<String> warnings = new ArrayList<>();
        for (String key : plan.selected) {
            Map<String, Object> d = plan.next.services().get(key);
            if (d != null) {
                AppServiceDesired.warnings(d).forEach(w -> warnings.add("服务 " + key + "：" + w));
            }
        }
        return new AppReleasePreviewView(changes,
                plan.changes.stream().map(AppPending.Change::key).toList(),
                plan.selected, plan.required, added, removed, problems, warnings);
    }

    // ------------------------------------------------------------------ 创建

    /**
     * 创建发布 + 执行（同一事务）；返回执行 id。
     *
     * <p>事务内：校验线上发布号没变、写镜像 tag 覆盖、取代 INTERRUPTED、写发布与执行、记凭据、置 DEPLOYING。
     * 凭据在执行行插入之后、提交之前记下：worker 只能看到已提交的行，那时凭据一定在；回滚则一并忘掉。
     */
    public Long create(String appId, List<String> selectedKeys, Map<String, String> images, String note,
                       String operator, String tenantId) {
        PlatformApplication app = AppGuard.require(apps, appId, tenantId);
        String authorization = K8sServerGateway.currentAuthorization();
        if (authorization == null || authorization.isBlank()) {
            throw error("当前请求没有可用的凭据");
        }
        PlatformAppExecution running = executions.selectActiveByApp(app.getId());
        if (running != null && !"INTERRUPTED".equals(running.getStatus())) {
            throw error("该应用已有进行中的发布，请等它结束后再发布");
        }
        int plannedLive = app.getLiveReleaseNo() == null ? 0 : app.getLiveReleaseNo();
        Plan plan = plan(app, selectedKeys, images);
        if (plan.selected.isEmpty()) {
            throw error("请至少勾选一项改动");
        }
        if (!plan.required.isEmpty()) {
            throw error("勾选的服务引用了尚未发布的服务，请一并勾选：" + String.join("、", plan.required));
        }
        List<String> problems = new ArrayList<>(plan.errors);
        if (problems.isEmpty()) {
            problems.addAll(pipeline.problems(app, plan.next));
        }
        if (!problems.isEmpty()) {
            throw error(String.join("；", problems));
        }

        AtomicReference<Long> remembered = new AtomicReference<>();
        try {
            return tx.execute(status -> {
                // 计划基于的线上版本在这期间被别的发布推进了：快照会基于过期的线上服务，必须重来
                PlatformApplication fresh = apps.selectById(app.getId());
                int liveNow = fresh == null || fresh.getLiveReleaseNo() == null ? 0 : fresh.getLiveReleaseNo();
                if (liveNow != plannedLive) {
                    throw error("线上已变化，请刷新后重试");
                }
                for (PlatformAppService row : plan.overridden) {
                    if (services.updateWithVersion(row) == 0) {
                        throw error("配置冲突：服务 " + row.getServiceKey() + " 已被他人修改，请刷新后重试");
                    }
                }
                supersedeInterrupted(app.getId());
                int releaseNo = nextReleaseNo(app.getId());

                PlatformAppRelease release = new PlatformAppRelease();
                release.setAppId(app.getId());
                release.setReleaseNo(releaseNo);
                release.setSnapshotJson(plan.next.toJson());
                release.setType("NORMAL");
                release.setNote(note == null ? null
                        : (note.length() > NOTE_MAX ? note.substring(0, NOTE_MAX) : note));
                release.setStatus("RENDERED");
                release.setCreatedBy(operator);
                release.setCreatedAt(new Date());
                PlatformAppExecution execution = new PlatformAppExecution();
                execution.setAppId(app.getId());
                execution.setReleaseNo(releaseNo);
                execution.setStatus("PENDING");
                execution.setStage("PRECHECK");
                execution.setStartedBy(operator);
                execution.setStartedAt(new Date());
                try {
                    releases.insert(release);
                    executions.insert(execution);
                } catch (DuplicateKeyException e) {
                    throw error("该应用已有进行中的发布，或发布号冲突，请刷新后重试");
                }
                credentials.rememberExecution(execution.getId(), authorization);
                remembered.set(execution.getId());
                PlatformApplication patch = new PlatformApplication();
                patch.setId(app.getId());
                patch.setStatus("DEPLOYING");
                patch.setUpdatedAt(new Date());
                apps.updateById(patch);
                return execution.getId();
            });
        } catch (RuntimeException e) {
            credentials.forgetExecution(remembered.get());   // 回滚了就不留凭据
            throw e;
        }
    }

    /** INTERRUPTED 在等用户点继续，新发布取代它；PENDING / RUNNING 正在推进，拒绝。条件更新，输了就拒绝。 */
    private void supersedeInterrupted(String appId) {
        PlatformAppExecution active = executions.selectActiveByApp(appId);
        if (active == null) {
            return;
        }
        if (!"INTERRUPTED".equals(active.getStatus())) {
            throw error("该应用已有进行中的发布，请等它结束后再发布");
        }
        if (executions.supersedeInterrupted(active.getId(), "被新发布取代", new Date()) == 0) {
            throw error("执行状态已变化，请刷新后重试");
        }
        credentials.forgetExecution(active.getId());
        PlatformAppRelease old = releases.selectByNo(appId, active.getReleaseNo());
        if (old != null) {
            PlatformAppRelease mark = new PlatformAppRelease();
            mark.setId(old.getId());
            mark.setStatus("FAILED");
            releases.updateById(mark);
        }
    }

    // ------------------------------------------------------------------ 读

    public List<AppReleaseView> list(String appId, String tenantId) {
        PlatformApplication app = AppGuard.require(apps, appId, tenantId);
        return releases.selectByApp(app.getId()).stream().map(r -> releaseView(app, r, false)).toList();
    }

    public AppReleaseView get(String appId, Integer releaseNo, String tenantId) {
        PlatformApplication app = AppGuard.require(apps, appId, tenantId);
        PlatformAppRelease r = releaseNo == null ? null : releases.selectByNo(app.getId(), releaseNo);
        if (r == null) {
            throw error("发布不存在：" + releaseNo);
        }
        return releaseView(app, r, true);
    }

    public AppExecutionView activeExecution(String appId, String tenantId) {
        PlatformApplication app = AppGuard.require(apps, appId, tenantId);
        return executionView(executions.selectActiveByApp(app.getId()));
    }

    /** 归属校验在这里，executor.resume 自己不查租户。 */
    public void resume(Long executionId, String operator, String tenantId) {
        PlatformAppExecution execution = executionId == null ? null : executions.selectById(executionId);
        if (execution == null) {
            throw error("执行记录不存在");
        }
        AppGuard.require(apps, execution.getAppId(), tenantId);
        executor.resume(executionId, operator);
    }

    public AppOverviewView overview(String appId, String tenantId) {
        PlatformApplication app = AppGuard.require(apps, appId, tenantId);
        AppSnapshot live = liveSnapshot(app);
        List<PlatformAppService> rows = services.selectByApp(app.getId());
        List<AppPending.Change> changes = AppPending.compute(live.services(), rows);
        Map<String, AppPending.Change> byKey = changes.stream()
                .collect(Collectors.toMap(AppPending.Change::key, c -> c, (a, b) -> a));
        Map<String, String> states = liveStates(app);

        List<AppOverviewView.ServiceRow> serviceRows = new ArrayList<>();
        for (PlatformAppService row : rows) {
            boolean deployed = live.services().containsKey(row.getServiceKey());
            if (Boolean.TRUE.equals(row.getPendingDelete()) && !deployed) {
                continue;
            }
            Map<String, Object> desired = AppSnapshot.parseMap(row.getDesiredJson());
            Map<String, Object> liveDesired = live.services().get(row.getServiceKey());
            AppPending.Change change = byKey.get(row.getServiceKey());
            String objectName = AppObjectNames.objectName(app.getSlug(), row.getServiceKey());
            String state = deployed ? states.getOrDefault(objectName, "UNKNOWN") : "NOT_RELEASED";
            serviceRows.add(new AppOverviewView.ServiceRow(row.getServiceKey(), AppServiceDesired.kindOf(desired),
                    AppServiceDesired.image(desired),
                    liveDesired == null ? null : AppServiceDesired.image(liveDesired),
                    desired.get("replicas") instanceof Number n ? n.intValue() : null,
                    state, change == null ? null : change.type(), desired, row.getVersion()));
        }
        // 线上有、台账无行的孤儿服务（待删除）：照样列出来，才有地方勾选删除
        Set<String> rowKeys = rows.stream().map(PlatformAppService::getServiceKey).collect(Collectors.toSet());
        for (AppPending.Change c : changes) {
            if (!"DELETED".equals(c.type()) || rowKeys.contains(c.key())) {
                continue;
            }
            Map<String, Object> liveDesired = live.services().get(c.key());
            String objectName = AppObjectNames.objectName(app.getSlug(), c.key());
            serviceRows.add(new AppOverviewView.ServiceRow(c.key(), AppServiceDesired.kindOf(liveDesired),
                    AppServiceDesired.image(liveDesired), AppServiceDesired.image(liveDesired),
                    liveDesired.get("replicas") instanceof Number n ? n.intValue() : null,
                    states.getOrDefault(objectName, "UNKNOWN"), "DELETED", liveDesired, null));
        }
        Map<String, String> depStatus = dependencies.statusOf(dependencyRows.selectByApp(app.getId()));
        List<AppOverviewView.DependencyRow> depRows = dependencyRows.selectByApp(app.getId()).stream()
                .map(d -> new AppOverviewView.DependencyRow(d.getDepKey(), d.getType(), d.getOwnership(),
                        d.getInstanceId(), depStatus.get(d.getDepKey())))
                .toList();
        return new AppOverviewView(app.getId(), app.getStatus(), app.getLiveReleaseNo(), serviceRows, depRows,
                changes.stream().map(AppPending.Change::key).toList(),
                executionView(executions.selectActiveByApp(app.getId())),
                executionView(executions.selectLatestByApp(app.getId())));
    }

    // ------------------------------------------------------------------ 计划

    private record Plan(AppSnapshot next, List<AppPending.Change> changes, List<String> selected,
                        List<String> required, List<String> errors, List<PlatformAppService> overridden,
                        Map<String, Map<String, Object>> desiredByKey, AppSnapshot live) {
    }

    private Plan plan(PlatformApplication app, List<String> selectedKeys, Map<String, String> images) {
        AppSnapshot live = liveSnapshot(app);
        List<PlatformAppService> stored = services.selectByApp(app.getId());
        Map<String, PlatformAppService> storedByKey = new HashMap<>();
        stored.forEach(r -> storedByKey.put(r.getServiceKey(), r));

        List<String> selected = selectedKeys == null ? List.of()
                : selectedKeys.stream().filter(k -> k != null && !k.isBlank()).distinct().toList();
        Map<String, String> tags = images == null ? Map.of() : images;
        List<String> errors = new ArrayList<>();

        // 镜像 tag 覆盖先于「有没有改动」的判断：desired 与线上相同、只想换 tag 发布也成立
        List<PlatformAppService> rows = new ArrayList<>();
        List<PlatformAppService> overridden = new ArrayList<>();
        for (PlatformAppService row : stored) {
            String tag = tags.get(row.getServiceKey());
            if (tag == null || tag.isBlank() || !selected.contains(row.getServiceKey())
                    || Boolean.TRUE.equals(row.getPendingDelete())) {
                rows.add(row);
                continue;
            }
            Map<String, Object> current = AppSnapshot.parseMap(row.getDesiredJson());
            Map<String, Object> desired = new LinkedHashMap<>(current);
            desired.put("imageTag", tag.trim());
            desired = AppServiceDesired.normalize(desired);
            PlatformAppService effective = new PlatformAppService();
            effective.setId(row.getId());
            effective.setAppId(row.getAppId());
            effective.setServiceKey(row.getServiceKey());
            effective.setPendingDelete(row.getPendingDelete());
            effective.setVersion(row.getVersion());
            effective.setDesiredJson(JSON.writeValueAsString(desired));
            rows.add(effective);
            if (!AppPending.changedFields(current, desired).isEmpty()) {
                PlatformAppService patch = new PlatformAppService();
                patch.setId(row.getId());
                patch.setServiceKey(row.getServiceKey());
                patch.setVersion(row.getVersion());
                patch.setDesiredJson(effective.getDesiredJson());
                patch.setUpdatedAt(new Date());
                overridden.add(patch);
            }
        }
        for (String key : tags.keySet()) {
            PlatformAppService row = storedByKey.get(key);
            if (!selected.contains(key) || row == null || Boolean.TRUE.equals(row.getPendingDelete())) {
                errors.add("镜像 tag 只能用于本次勾选的、非删除的服务：" + key);
            }
        }

        List<AppPending.Change> changes = AppPending.compute(live.services(), rows);
        Map<String, AppPending.Change> byKey = new LinkedHashMap<>();
        changes.forEach(c -> byKey.put(c.key(), c));
        Map<String, PlatformAppService> rowByKey = new HashMap<>();
        rows.forEach(r -> rowByKey.put(r.getServiceKey(), r));
        for (String key : selected) {
            if (!byKey.containsKey(key)) {
                errors.add("服务 " + key + " 没有待发布的改动");
            }
        }

        Map<String, Map<String, Object>> next = new TreeMap<>(live.services());
        // 所有待发布（非删除）服务的期望配置：预览要展示未勾选项的目标镜像
        Map<String, Map<String, Object>> desiredByKey = new HashMap<>();
        for (AppPending.Change c : changes) {
            if (!"DELETED".equals(c.type())) {
                desiredByKey.put(c.key(), AppSnapshot.parseMap(rowByKey.get(c.key()).getDesiredJson()));
            }
        }
        for (String key : selected) {
            AppPending.Change c = byKey.get(key);
            if (c == null) {
                continue;
            }
            if ("DELETED".equals(c.type())) {
                next.remove(key);
            } else {
                next.put(key, desiredByKey.get(key));
            }
        }

        Set<String> unreleased = byKey.values().stream()
                .filter(c -> "ADDED".equals(c.type()) && !selected.contains(c.key()))
                .map(AppPending.Change::key).collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> required = new LinkedHashSet<>();
        for (String key : selected) {
            Map<String, Object> d = desiredByKey.get(key);
            if (d != null) {
                for (String ref : AppServiceDesired.references(d)) {
                    if (unreleased.contains(ref)) {
                        required.add(ref);
                    }
                }
            }
        }
        if (!selected.isEmpty() && next.isEmpty()) {
            errors.add("发布后应用至少要保留一个服务");
        }
        AppSnapshot snapshot = new AppSnapshot(next, dependencyRows.selectByApp(app.getId()));
        return new Plan(snapshot, changes, selected, new ArrayList<>(required), errors, overridden,
                desiredByKey, live);
    }

    private AppReleasePreviewView.Change toChange(AppPending.Change c, Plan plan) {
        Map<String, Object> before = plan.live.services().get(c.key());
        String from = before == null ? null : AppServiceDesired.image(before);
        Map<String, Object> after = plan.desiredByKey.get(c.key());
        return new AppReleasePreviewView.Change(c.key(), c.type(), c.fields(), from,
                after == null ? null : AppServiceDesired.image(after));
    }

    // ------------------------------------------------------------------ 内部

    private AppSnapshot liveSnapshot(PlatformApplication app) {
        int no = app.getLiveReleaseNo() == null ? 0 : app.getLiveReleaseNo();
        if (no <= 0) {
            return AppSnapshot.empty();
        }
        PlatformAppRelease release = releases.selectByNo(app.getId(), no);
        return release == null ? AppSnapshot.empty() : AppSnapshot.parse(release.getSnapshotJson());
    }

    private List<AppObjectRefDTO> liveObjects(PlatformApplication app) {
        int no = app.getLiveReleaseNo() == null ? 0 : app.getLiveReleaseNo();
        PlatformAppRelease release = no <= 0 ? null : releases.selectByNo(app.getId(), no);
        return release == null ? List.of() : parseObjects(release.getObjectsJson());
    }

    /** 现读线上对象状态：对象名 → state。查询失败不让概览整页失败，状态记 UNKNOWN。 */
    private Map<String, String> liveStates(PlatformApplication app) {
        List<AppObjectRefDTO> objects = liveObjects(app);
        Map<String, String> out = new HashMap<>();
        if (objects.isEmpty()) {
            return out;
        }
        try {
            AppStatusRequestDTO req = new AppStatusRequestDTO();
            req.setClusterId(app.getClusterId());
            req.setObjects(objects);
            AppStatusResultDTO result = client.status(req);
            if (result != null && result.getObjects() != null) {
                for (AppObjectStatusDTO s : result.getObjects()) {
                    if ("Deployment".equals(s.getKind()) || "StatefulSet".equals(s.getKind())) {
                        out.put(s.getName(), s.getState());
                    }
                }
            }
        } catch (RuntimeException e) {
            log.warn("应用 {} 概览读取对象状态失败：{}", app.getId(), e.getMessage());
        }
        return out;
    }

    private int nextReleaseNo(String appId) {
        Integer max = releases.selectMaxNo(appId);
        return (max == null ? 0 : max) + 1;
    }

    private AppReleaseView releaseView(PlatformApplication app, PlatformAppRelease r, boolean detail) {
        boolean live = app.getLiveReleaseNo() != null && app.getLiveReleaseNo().equals(r.getReleaseNo());
        return new AppReleaseView(r.getReleaseNo(), r.getType(), r.getSourceReleaseNo(), r.getNote(), r.getStatus(),
                r.getCreatedBy(), r.getCreatedAt(), live,
                detail ? AppSnapshot.parseMap(r.getSnapshotJson()) : null,
                detail ? parseObjects(r.getObjectsJson()) : null);
    }

    private static AppExecutionView executionView(PlatformAppExecution e) {
        return e == null ? null : new AppExecutionView(e.getId(), e.getAppId(), e.getReleaseNo(), e.getStatus(),
                e.getStage(), e.getMessage(), e.getStartedBy(), e.getStartedAt(), e.getFinishedAt());
    }

    private static List<AppObjectRefDTO> parseObjects(String json) {
        return json == null || json.isBlank() ? List.of() : JSON.readValue(json, OBJECT_LIST);
    }

    private static Set<String> objectIds(List<AppObjectRefDTO> objects) {
        Set<String> out = new LinkedHashSet<>();
        if (objects != null) {
            objects.forEach(o -> out.add(o.getKind() + "/" + o.getName()));
        }
        return out;
    }

    private static CloudPlatformException error(String message) {
        return new CloudPlatformException(EnumResponseType.ERROR, message);
    }
}
