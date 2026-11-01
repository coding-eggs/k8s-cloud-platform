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
        return new AppReleasePreviewView(changes,
                plan.changes.stream().map(AppPending.Change::key).toList(),
                plan.selected, plan.required, added, removed, problems);
    }

    // ------------------------------------------------------------------ 创建

    /** 创建发布 + 执行（同一事务）；返回执行 id。 */
    public Long create(String appId, List<String> selectedKeys, Map<String, String> images, String note,
                       String operator, String tenantId) {
        PlatformApplication app = AppGuard.require(apps, appId, tenantId);
        String authorization = K8sServerGateway.currentAuthorization();
        if (authorization == null || authorization.isBlank()) {
            throw error("当前请求没有可用的凭据");
        }
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

        Long executionId = tx.execute(status -> {
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
            release.setNote(note == null ? null : (note.length() > NOTE_MAX ? note.substring(0, NOTE_MAX) : note));
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
            return execution.getId();
        });

        // 提交之后再记凭据、改状态：事务回滚时不留下孤儿凭据
        credentials.rememberExecution(executionId, authorization);
        PlatformApplication patch = new PlatformApplication();
        patch.setId(app.getId());
        patch.setStatus("DEPLOYING");
        patch.setUpdatedAt(new Date());
        apps.updateById(patch);
        return executionId;
    }

    /** INTERRUPTED 在等用户点继续，新发布取代它；PENDING / RUNNING 正在推进，拒绝。 */
    private void supersedeInterrupted(String appId) {
        PlatformAppExecution active = executions.selectActiveByApp(appId);
        if (active == null) {
            return;
        }
        if (!"INTERRUPTED".equals(active.getStatus())) {
            throw error("该应用已有进行中的发布，请等它结束后再发布");
        }
        PlatformAppExecution patch = new PlatformAppExecution();
        patch.setId(active.getId());
        patch.setStatus("FAILED");
        patch.setMessage("被新发布取代");
        patch.setFinishedAt(new Date());
        executions.updateById(patch);
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
                    state, change == null ? null : change.type()));
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
        List<PlatformAppService> rows = services.selectByApp(app.getId());
        List<AppPending.Change> changes = AppPending.compute(live.services(), rows);
        Map<String, AppPending.Change> byKey = new LinkedHashMap<>();
        changes.forEach(c -> byKey.put(c.key(), c));
        Map<String, PlatformAppService> rowByKey = new HashMap<>();
        rows.forEach(r -> rowByKey.put(r.getServiceKey(), r));

        List<String> selected = selectedKeys == null ? List.of()
                : selectedKeys.stream().filter(k -> k != null && !k.isBlank()).distinct().toList();
        Map<String, String> tags = images == null ? Map.of() : images;
        List<String> errors = new ArrayList<>();
        for (String key : selected) {
            if (!byKey.containsKey(key)) {
                errors.add("服务 " + key + " 没有待发布的改动");
            }
        }
        for (String key : tags.keySet()) {
            AppPending.Change c = byKey.get(key);
            if (!selected.contains(key) || c == null || "DELETED".equals(c.type())) {
                errors.add("镜像 tag 只能用于本次勾选的、非删除的服务：" + key);
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
        List<PlatformAppService> overridden = new ArrayList<>();
        for (String key : selected) {
            AppPending.Change c = byKey.get(key);
            if (c == null) {
                continue;
            }
            if ("DELETED".equals(c.type())) {
                next.remove(key);
                continue;
            }
            PlatformAppService row = rowByKey.get(key);
            Map<String, Object> desired = desiredByKey.get(key);
            String tag = tags.get(key);
            if (tag != null && !tag.isBlank()) {
                desired.put("imageTag", tag.trim());
                desired = AppServiceDesired.normalize(desired);
                PlatformAppService patch = new PlatformAppService();
                patch.setId(row.getId());
                patch.setServiceKey(key);
                patch.setVersion(row.getVersion());
                patch.setDesiredJson(JSON.writeValueAsString(desired));
                patch.setUpdatedAt(new Date());
                overridden.add(patch);
            }
            desiredByKey.put(key, desired);
            next.put(key, desired);
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
