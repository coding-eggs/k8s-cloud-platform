package com.coding.platformapi.services;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.app.AppObjectRefDTO;
import com.coding.common.models.app.AppObjectStatusDTO;
import com.coding.common.models.app.AppProblemDTO;
import com.coding.common.models.app.AppRenderResultDTO;
import com.coding.common.models.app.AppStatusRequestDTO;
import com.coding.common.models.app.AppStatusResultDTO;
import com.coding.common.models.page.PageResult;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
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
import com.coding.platformapi.app.AppSettings;
import com.coding.platformapi.app.AppSnapshot;
import com.coding.platformapi.k8s.K8sAppClient;
import com.coding.platformapi.k8s.K8sServerGateway;
import com.coding.platformapi.models.AppExecutionView;
import com.coding.platformapi.models.AppOverviewView;
import com.coding.platformapi.models.AppSettingsView;
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
import java.util.Objects;
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
    private final AppExecutionLease leases;
    private final K8sAppClient client;
    private final AppImageUpdates imageUpdates;
    private final TransactionTemplate tx;
    private final AppFeatureGate featureGate;
    private final IngressPortService ingressPorts;

    public AppReleaseService(PlatformApplicationMapper apps, PlatformAppServiceMapper services,
                             PlatformAppDependencyMapper dependencyRows, PlatformAppReleaseMapper releases,
                             PlatformAppExecutionMapper executions, AppDependencyService dependencies,
                             AppReleasePipeline pipeline, AppReleaseExecutor executor,
                             ApplicationDeployCredentials credentials, AppExecutionLease leases, K8sAppClient client,
                             AppImageUpdates imageUpdates, PlatformTransactionManager transactionManager,
                             AppFeatureGate featureGate, IngressPortService ingressPorts) {
        this.ingressPorts = ingressPorts;
        this.apps = apps;
        this.services = services;
        this.dependencyRows = dependencyRows;
        this.releases = releases;
        this.executions = executions;
        this.dependencies = dependencies;
        this.pipeline = pipeline;
        this.executor = executor;
        this.credentials = credentials;
        this.leases = leases;
        this.client = client;
        this.imageUpdates = imageUpdates;
        this.tx = new TransactionTemplate(transactionManager);
        this.featureGate = featureGate;
    }

    // ------------------------------------------------------------------ 预览

    public AppReleasePreviewView preview(String appId, List<String> selectedKeys, Map<String, String> images,
                                         String tenantId) {
        PlatformApplication app = AppGuard.require(apps, appId, tenantId);
        Plan plan = plan(app, selectedKeys, images);

        List<String> problems = new ArrayList<>(plan.errors);
        List<String> added = List.of();
        List<String> removed = List.of();
        AppFeatureGate.Report gate = plan.selected.isEmpty() ? null
                : featureGate.release(app, plan.live, plan.next, plan.selected);
        if (plan.errors.isEmpty() && plan.required.isEmpty() && !plan.selected.isEmpty()) {
            problems.addAll(gate.problems());
            problems.addAll(pipeline.problems(app, plan.next));
            if (problems.isEmpty()) {
                try {
                    int nextNo = nextReleaseNo(app.getId());
                    AppRenderResultDTO rendered = client.render(pipeline.buildRequest(app, nextNo, plan.next, true));
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
        if (!plan.selected.isEmpty()) {
            warnings.addAll(dependencies.degraded(AppReleasePipeline.referencedDependencies(plan.next)));
            warnings.addAll(gate.warnings());
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
        requireNoRunningExecution(app.getId());
        int plannedLive = app.getLiveReleaseNo() == null ? 0 : app.getLiveReleaseNo();
        Plan plan = plan(app, selectedKeys, images);
        if (plan.selected.isEmpty()) {
            throw error("请至少勾选一项改动");
        }
        if (!plan.required.isEmpty()) {
            throw error("以下服务需要一并勾选（引用了尚未发布的服务，或引用的依赖连接信息已修改）："
                    + String.join("、", plan.required));
        }
        List<String> problems = new ArrayList<>(plan.errors);
        if (problems.isEmpty()) {
            // 组件门禁：勾选且新开启的功能组件不可用 → 拦；回滚不走这里（止血不能被门禁挡住）
            problems.addAll(featureGate.release(app, plan.live, plan.next, plan.selected).problems());
        }
        if (problems.isEmpty()) {
            problems.addAll(pipeline.problems(app, plan.next));
        }
        if (!problems.isEmpty()) {
            throw error(String.join("；", problems));
        }

        return launch(app, authorization, plannedLive, plan.next.toJson(), "NORMAL", null, note, operator, () -> {
            for (PlatformAppService row : plan.overridden) {
                if (services.updateWithVersion(row) == 0) {
                    throw error("配置冲突：服务 " + row.getServiceKey() + " 已被他人修改，请刷新后重试");
                }
            }
        });
    }

    /**
     * 写发布与执行的公共事务（创建与回滚共用）。
     *
     * <p>事务内：校验线上发布号没变、执行调用方的 {@code writes}（desired 的写入）、取代 INTERRUPTED、写发布与执行、
     * 记凭据、置 DEPLOYING。凭据在执行行插入之后、提交之前记下：worker 只能看到已提交的行，那时凭据一定在；
     * 回滚则一并忘掉。
     */
    private Long launch(PlatformApplication app, String authorization, int plannedLive, String snapshotJson,
                        String type, Integer sourceNo, String note, String operator, Runnable writes) {
        AtomicReference<Long> remembered = new AtomicReference<>();
        try {
            return tx.execute(status -> {
                // 计划基于的线上版本在这期间被别的发布推进了：快照会基于过期的线上服务，必须重来
                PlatformApplication fresh = apps.selectById(app.getId());
                int liveNow = fresh == null || fresh.getLiveReleaseNo() == null ? 0 : fresh.getLiveReleaseNo();
                if (liveNow != plannedLive) {
                    throw error("线上已变化，请刷新后重试");
                }
                writes.run();
                supersedeInterrupted(app.getId());
                int releaseNo = nextReleaseNo(app.getId());

                PlatformAppRelease release = new PlatformAppRelease();
                release.setAppId(app.getId());
                release.setReleaseNo(releaseNo);
                release.setSnapshotJson(snapshotJson);
                release.setType(type);
                release.setSourceReleaseNo(sourceNo);
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
                // 归属与租约一起写：本实例活着时别人不该来抢这条执行（见 AppExecutionLease）
                execution.setOwnerId(leases.instanceId());
                execution.setLeaseUntil(leases.nextLease(execution.getStartedAt()));
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

    // ------------------------------------------------------------------ 回滚

    /**
     * 回滚 = 以历史快照为内容生成一条新发布（type=ROLLBACK），走与创建相同的执行路径；不倒回状态，也不改旧记录。
     *
     * <p>快照原样取自源发布；引用在 RENDER 时重新解析，所以旧版本引用的实例已删除、或依赖未就绪，都在这里（校验）
     * 或 PRECHECK 拦下，线上不变。
     *
     * <p>期望配置（desired）的处理：以「当前线上快照 vs 当前行」判断有没有未发布编辑——
     * <ul>
     *   <li>没有编辑的服务：desired 同步为旧快照；</li>
     *   <li>有编辑的服务（含已标待删除且仍在线上的）：保留，回滚后继续显示为待发布；</li>
     *   <li>旧快照有、当前没有行的服务（行被硬删）：按旧快照重建行；</li>
     *   <li>旧快照有、当前只剩「待删除且已不在线上」的残留行：复活（清待删除，desired 取旧快照）；</li>
     *   <li>旧快照里没有、当前线上有的服务：行不动，回滚后因线上不再有而显示为待发布的新增。</li>
     * </ul>
     * 要重建或复活的 key 若已被依赖占用，拒绝（同一应用内 key 唯一）。
     */
    public Long rollback(String appId, Integer sourceReleaseNo, String note, String operator, String tenantId) {
        PlatformApplication app = AppGuard.require(apps, appId, tenantId);
        String authorization = K8sServerGateway.currentAuthorization();
        if (authorization == null || authorization.isBlank()) {
            throw error("当前请求没有可用的凭据");
        }
        PlatformAppRelease source = sourceReleaseNo == null ? null : releases.selectByNo(app.getId(), sourceReleaseNo);
        if (source == null) {
            throw error("发布不存在：" + sourceReleaseNo);
        }
        if (!"DEPLOYED".equals(source.getStatus())) {
            throw error("只能回滚到成功上线过的发布（发布 " + sourceReleaseNo + " 的状态是 " + source.getStatus() + "）");
        }
        int plannedLive = app.getLiveReleaseNo() == null ? 0 : app.getLiveReleaseNo();
        Integer maxNo = releases.selectMaxNo(app.getId());
        // 线上就是最新发布时回滚到它没有意义；有更新的（失败 / 中断）发布时，回到线上版本是合法的收尾
        if (plannedLive == sourceReleaseNo && (maxNo == null || maxNo == plannedLive)) {
            throw error("发布 " + sourceReleaseNo + " 已经是线上版本");
        }
        requireNoRunningExecution(app.getId());
        AppSnapshot target = AppSnapshot.parse(source.getSnapshotJson());
        if (target.services().isEmpty()) {
            throw error("发布 " + sourceReleaseNo + " 不含任何服务，无法回滚");
        }
        List<String> problems = new ArrayList<>(changedDependencies(app, target));
        if (problems.isEmpty()) {
            problems.addAll(deletedDependencies(target));
        }
        if (problems.isEmpty()) {
            problems.addAll(pipeline.problems(app, target));
        }
        if (!problems.isEmpty()) {
            throw error("无法回滚到发布 " + sourceReleaseNo + "：" + String.join("；", problems));
        }
        DesiredSync sync = syncDesired(app, target);
        // 应用设置与服务同口径：没有未发布编辑（期望 = 线上）才同步为旧快照的值；有编辑的保留，回滚后仍是待发布
        Map<String, Object> settingsNow = AppSnapshot.parseMap(app.getSettingsJson());
        boolean syncSettings = AppPending.changedFields(liveSnapshot(app).app(), settingsNow).isEmpty()
                && !AppPending.changedFields(settingsNow, target.app()).isEmpty();
        return launch(app, authorization, plannedLive, target.toJson(), "ROLLBACK", sourceReleaseNo, note, operator,
                () -> {
                    if (syncSettings && apps.updateSettings(app.getId(),
                            target.app().isEmpty() ? null : JSON.writeValueAsString(target.app()),
                            app.getSettingsVersion() == null ? 0 : app.getSettingsVersion(), new Date()) == 0) {
                        throw error("配置冲突：应用设置已被他人修改，请刷新后重试");
                    }
                    for (PlatformAppService row : sync.updates) {
                        if (services.updateWithVersion(row) == 0) {
                            throw error("配置冲突：服务 " + row.getServiceKey() + " 已被他人修改，请刷新后重试");
                        }
                    }
                    for (PlatformAppService row : sync.inserts) {
                        try {
                            services.insert(row);
                        } catch (DuplicateKeyException e) {
                            throw error("配置冲突：服务 " + row.getServiceKey() + " 已被他人创建，请刷新后重试");
                        }
                    }
                });
    }

    // ------------------------------------------------------------------ 下线

    /**
     * 下线 = 一条 type=OFFLINE、快照不含任何服务的发布，走与发布相同的执行路径（租约、凭据、可「继续」）。
     *
     * <p>执行器对它跳过渲染 / 应用 / 健康检查，直接清掉本应用台账里的全部对象（PVC 保留，重新上线后挂回同一个卷），
     * 成功后线上发布号指向这条下线记录 —— 线上快照为空，所有服务自然显示为待发布的新增；重新上线就是一次普通发布。
     * 服务配置、依赖与中间件实例都不动。快照保留线上那份依赖，只清空服务。
     */
    public Long offline(String appId, String note, String operator, String tenantId) {
        PlatformApplication app = AppGuard.require(apps, appId, tenantId);
        String authorization = K8sServerGateway.currentAuthorization();
        if (authorization == null || authorization.isBlank()) {
            throw error("当前请求没有可用的凭据");
        }
        requireNoRunningExecution(app.getId());
        int plannedLive = app.getLiveReleaseNo() == null ? 0 : app.getLiveReleaseNo();
        if (plannedLive <= 0) {
            throw error("应用尚未上线，无需下线");
        }
        AppSnapshot live = liveSnapshot(app);
        if (live.services().isEmpty()) {
            throw error("应用已下线");
        }
        // 应用设置照留：重新上线时它不该显示成待发布改动
        String snapshot = new AppSnapshot(new LinkedHashMap<>(), live.dependencies(), live.app()).toJson();
        return launch(app, authorization, plannedLive, snapshot, AppReleaseExecutor.OFFLINE_TYPE, null, note, operator,
                () -> { });
    }

    /** PENDING / RUNNING 正在推进，拒绝；INTERRUPTED 在等用户点继续，新发布会取代它。 */
    private void requireNoRunningExecution(String appId) {
        PlatformAppExecution running = executions.selectActiveByApp(appId);
        if (running != null && !"INTERRUPTED".equals(running.getStatus())) {
            throw error("该应用已有进行中的发布，请等它结束后再发布");
        }
    }

    /**
     * 快照服务引用到的依赖，在当前台账里必须还有同 key、同类型、同实例的行：
     * 被移除、或已改指向别的实例，都说明旧版本的连接信息不再是现在的依赖。
     */
    private List<String> changedDependencies(PlatformApplication app, AppSnapshot target) {
        Map<String, PlatformAppDependency> current = new HashMap<>();
        dependencyRows.selectByApp(app.getId()).forEach(d -> current.put(d.getDepKey(), d));
        List<String> out = new ArrayList<>();
        for (PlatformAppDependency old : AppReleasePipeline.referencedDependencies(target)) {
            PlatformAppDependency now = current.get(old.getDepKey());
            if (now == null) {
                out.add("依赖 " + old.getDepKey() + " 已被移除");
            } else if (!Objects.equals(now.getType(), old.getType())
                    || !Objects.equals(now.getInstanceId(), old.getInstanceId())) {
                out.add("依赖 " + old.getDepKey() + " 现在指向不同的实例或类型");
            }
        }
        return out;
    }

    /** 快照里被引用、实例已删除的中间件依赖，每条点名。未被任何服务引用的不挡。 */
    private List<String> deletedDependencies(AppSnapshot target) {
        List<String> out = new ArrayList<>();
        Map<String, String> status = dependencies.statusOf(AppReleasePipeline.referencedDependencies(target));
        status.forEach((key, st) -> {
            if ("DELETED".equals(st)) {
                out.add("依赖 " + key + " 引用的实例已删除");
            }
        });
        return out;
    }

    private record DesiredSync(List<PlatformAppService> updates, List<PlatformAppService> inserts) {
    }

    /**
     * 未发布编辑 = 与线上快照有差异、且与最新一次发布的快照也有差异。
     * desired 等于最新发布的配置，说明它是某次未成功（失败 / 中断 / 回滚失败）的发布留下的，不是用户另外的改动。
     * 待删除是用户意图，不是配置，只要仍在线上就算编辑。两处差异都用 {@link AppPending} 的同一套判定。
     */
    private Set<String> editedKeys(PlatformApplication app, AppSnapshot live, List<PlatformAppService> rows) {
        Set<String> vsLive = AppPending.compute(live.services(), rows).stream()
                .map(AppPending.Change::key).collect(Collectors.toCollection(LinkedHashSet::new));
        Integer maxNo = releases.selectMaxNo(app.getId());
        int liveNo = app.getLiveReleaseNo() == null ? 0 : app.getLiveReleaseNo();
        PlatformAppRelease latest = maxNo == null || maxNo == liveNo ? null : releases.selectByNo(app.getId(), maxNo);
        if (latest == null) {
            return vsLive;
        }
        Set<String> vsLatest = AppPending.compute(AppSnapshot.parse(latest.getSnapshotJson()).services(), rows)
                .stream().map(AppPending.Change::key).collect(Collectors.toSet());
        Set<String> pendingDelete = rows.stream().filter(r -> Boolean.TRUE.equals(r.getPendingDelete()))
                .map(PlatformAppService::getServiceKey).collect(Collectors.toSet());
        vsLive.removeIf(k -> !pendingDelete.contains(k) && !vsLatest.contains(k));
        return vsLive;
    }

    private DesiredSync syncDesired(PlatformApplication app, AppSnapshot target) {
        AppSnapshot live = liveSnapshot(app);
        List<PlatformAppService> rows = services.selectByApp(app.getId());
        Map<String, PlatformAppService> byKey = new HashMap<>();
        rows.forEach(r -> byKey.put(r.getServiceKey(), r));
        Set<String> edited = editedKeys(app, live, rows);
        Set<String> depKeys = dependencyRows.selectByApp(app.getId()).stream()
                .map(PlatformAppDependency::getDepKey).collect(Collectors.toSet());

        List<PlatformAppService> updates = new ArrayList<>();
        List<PlatformAppService> inserts = new ArrayList<>();
        Date now = new Date();
        for (Map.Entry<String, Map<String, Object>> e : target.services().entrySet()) {
            String key = e.getKey();
            String desiredJson = JSON.writeValueAsString(e.getValue());
            PlatformAppService row = byKey.get(key);
            boolean leftover = row != null && Boolean.TRUE.equals(row.getPendingDelete())
                    && !live.services().containsKey(key);
            if ((row == null || leftover) && depKeys.contains(key)) {
                throw error("无法回滚：服务 " + key + " 的名字现在被依赖占用（同一应用内 key 唯一），请先处理该依赖");
            }
            if (row == null) {
                PlatformAppService fresh = new PlatformAppService();
                fresh.setAppId(app.getId());
                fresh.setServiceKey(key);
                fresh.setDesiredJson(desiredJson);
                fresh.setPendingDelete(false);
                fresh.setCreatedAt(now);
                fresh.setUpdatedAt(now);
                inserts.add(fresh);
                continue;
            }
            if (!leftover && edited.contains(key)) {
                continue;   // 有未发布编辑（含仍在线上的待删除）：保留
            }
            if (!leftover && AppPending.changedFields(AppSnapshot.parseMap(row.getDesiredJson()),
                    e.getValue()).isEmpty()) {
                continue;   // 本就一致
            }
            PlatformAppService patch = new PlatformAppService();
            patch.setId(row.getId());
            patch.setServiceKey(key);
            patch.setVersion(row.getVersion());
            patch.setDesiredJson(desiredJson);
            patch.setPendingDelete(false);
            patch.setUpdatedAt(now);
            updates.add(patch);
        }
        return new DesiredSync(updates, inserts);
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

    /**
     * 发布记录（按发布号倒序）。page/size 都给了才走 PageHelper 分页，否则全量；
     * {@code startPage} 必须紧贴 {@code selectByApp}（前面的归属校验也是查询，会把分页吃掉）。
     */
    public PageResult<AppReleaseView> list(String appId, String tenantId, Integer page, Integer size) {
        PlatformApplication app = AppGuard.require(apps, appId, tenantId);
        boolean paged = page != null && size != null;
        if (paged) {
            PageHelper.startPage(page, size);
        }
        List<PlatformAppRelease> rows = releases.selectByApp(app.getId());
        long total = paged ? new PageInfo<>(rows).getTotal() : rows.size();
        return PageResult.of(rows.stream().map(r -> releaseView(app, r, false)).toList(), total);
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
        List<AppPending.Change> changes = AppPending.compute(live.services(), rows,
                AppPending.changedDependencies(live.dependencies(), dependencyRows.selectByApp(app.getId())));
        Map<String, Object> settings = AppSnapshot.parseMap(app.getSettingsJson());
        List<String> pendingKeys = new ArrayList<>();
        if (!AppPending.changedFields(live.app(), settings).isEmpty()) {
            pendingKeys.add(AppSettings.KEY);
        }
        changes.forEach(c -> pendingKeys.add(c.key()));
        Map<String, AppPending.Change> byKey = changes.stream()
                .collect(Collectors.toMap(AppPending.Change::key, c -> c, (a, b) -> a));
        Map<String, String> states = liveStates(app);
        Map<String, String> newTags = newTags(live, byKey, tenantId);
        Map<String, AppOverviewView.Ingress> ingress = ingressOf(app);

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
                    state, change == null ? null : change.type(), desired, row.getVersion(),
                    newTags.get(row.getServiceKey()), ingress.get(row.getServiceKey())));
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
                    states.getOrDefault(objectName, "UNKNOWN"), "DELETED", liveDesired, null, null,
                    ingress.get(c.key())));
        }
        Map<String, String> depStatus = dependencies.statusOf(dependencyRows.selectByApp(app.getId()));
        List<AppOverviewView.DependencyRow> depRows = dependencyRows.selectByApp(app.getId()).stream()
                .map(d -> new AppOverviewView.DependencyRow(d.getDepKey(), d.getType(), d.getOwnership(),
                        d.getInstanceId(), depStatus.get(d.getDepKey()), d.getInitStatus(),
                        AppDependencyService.tail(d.getInitLog()), AppSnapshot.scrubExternal(d.getExternalJson()),
                        dependencies.codeOf(d)))
                .toList();
        return new AppOverviewView(app.getId(), app.getStatus(), app.getLiveReleaseNo(),
                serviceRows, depRows, pendingKeys,
                executionView(executions.selectActiveByApp(app.getId())),
                executionView(executions.selectLatestByApp(app.getId())),
                new AppSettingsView(settings, live.app(),
                        app.getSettingsVersion() == null ? 0 : app.getSettingsVersion()));
    }

    /**
     * 每个持有网关端口的服务 → 端口 + listener 状态。一个应用只查一次 ListenerSet（命名空间共用）；
     * 查不到（集群断开、CRD 缺失、还没下发）时状态为未知，端口照样显示。不让概览失败。
     */
    private Map<String, AppOverviewView.Ingress> ingressOf(PlatformApplication app) {
        List<com.coding.data.models.system.PlatformIngressPort> held = ingressPorts.activeOfApp(app.getId());
        if (held == null || held.isEmpty()) {
            return Map.of();
        }
        Map<String, com.coding.common.models.k8s.dto.IngressListenerSetStatusDTO.Listener> byName = new HashMap<>();
        try {
            var status = client.ingressListenerSetStatus(app.getClusterId(), app.getNamespace(),
                    IngressListenerSync.LISTENER_SET);
            if (status != null && status.getListeners() != null) {
                status.getListeners().forEach(l -> byName.put(l.getName(), l));
            }
        } catch (RuntimeException e) {
            log.debug("读取应用 {} 的 ListenerSet 状态失败：{}", app.getId(), e.getMessage());
        }
        Map<String, AppOverviewView.Ingress> out = new HashMap<>();
        for (var p : held) {
            var l = byName.get(IngressListenerSync.sectionName(p.getPort()));
            out.put(p.getServiceKey(), l == null
                    ? new AppOverviewView.Ingress(p.getPort(), p.getProtocol(), null, null, null, null)
                    : new AppOverviewView.Ingress(p.getPort(), p.getProtocol(), l.isProgrammed(), l.isConflicted(),
                            l.getReason(), l.getMessage()));
        }
        return out;
    }

    /**
     * 线上已发布服务的「新版本」。只探线上跑的镜像（线上 tag），待删除的不探。
     * 提示是锦上添花：任何失败都只丢提示，绝不让概览失败。
     */
    private Map<String, String> newTags(AppSnapshot live, Map<String, AppPending.Change> byKey, String tenantId) {
        try {
            Map<String, AppImageUpdates.Probe> probes = new LinkedHashMap<>();
            live.services().forEach((key, d) -> {
                AppPending.Change c = byKey.get(key);
                Object repo = d.get("imageRepo");
                Object tag = d.get("imageTag");
                if ((c == null || !"DELETED".equals(c.type())) && repo != null && tag != null) {
                    probes.put(key, new AppImageUpdates.Probe(String.valueOf(repo), String.valueOf(tag)));
                }
            });
            Map<String, String> out = imageUpdates.newTags(probes, tenantId);
            return out == null ? Map.of() : out;
        } catch (RuntimeException e) {
            log.warn("新版本提示查询失败（忽略）：{}", e.getMessage());
            return Map.of();
        }
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

        List<PlatformAppDependency> currentDeps = dependencyRows.selectByApp(app.getId());
        Set<String> changedDeps = AppPending.changedDependencies(live.dependencies(), currentDeps);
        List<AppPending.Change> changes = AppPending.compute(live.services(), rows, changedDeps);
        Map<String, AppPending.Change> byKey = new LinkedHashMap<>();
        changes.forEach(c -> byKey.put(c.key(), c));
        Map<String, PlatformAppService> rowByKey = new HashMap<>();
        rows.forEach(r -> rowByKey.put(r.getServiceKey(), r));
        // 应用设置（链路追踪 / 网格）：期望值 vs 线上快照的 app 字段；勾选「@app」才带上新值，否则沿用线上
        Map<String, Object> settings = AppSnapshot.parseMap(app.getSettingsJson());
        List<String> appFields = AppPending.changedFields(live.app(), settings);
        for (String key : selected) {
            if (AppSettings.KEY.equals(key)) {
                if (appFields.isEmpty()) {
                    errors.add("应用设置没有待发布的改动");
                }
            } else if (!byKey.containsKey(key)) {
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
        // 依赖连接信息改过：发布按当前台账整份渲染，没勾选的引用方也会被改到，所以必须一并勾选
        if (!selected.isEmpty() && !changedDeps.isEmpty()) {
            for (AppPending.Change c : changes) {
                if ("CHANGED".equals(c.type()) && !selected.contains(c.key())
                        && c.fields().stream().anyMatch(f -> changedDeps.stream()
                                .anyMatch(d -> f.equals(AppPending.dependencyField(d))))) {
                    required.add(c.key());
                }
            }
        }
        if (!selected.isEmpty() && next.isEmpty()) {
            errors.add("发布后应用至少要保留一个服务");
        }
        AppSnapshot snapshot = new AppSnapshot(next, currentDeps,
                selected.contains(AppSettings.KEY) ? settings : live.app());
        if (!appFields.isEmpty()) {
            changes = new ArrayList<>(changes);
            changes.add(0, new AppPending.Change(AppSettings.KEY, "CHANGED", appFields));
        }
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

    private AppExecutionView executionView(PlatformAppExecution e) {
        if (e == null) {
            return null;
        }
        PlatformAppRelease r = e.getReleaseNo() == null ? null : releases.selectByNo(e.getAppId(), e.getReleaseNo());
        return new AppExecutionView(e.getId(), e.getAppId(), e.getReleaseNo(), e.getStatus(),
                e.getStage(), e.getMessage(), e.getStartedBy(), e.getStartedAt(), e.getFinishedAt(),
                r == null ? null : r.getType());
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
