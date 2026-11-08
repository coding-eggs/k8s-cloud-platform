package com.coding.platformapi.services;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.app.AppApplyRequestDTO;
import com.coding.common.models.app.AppObjectRefDTO;
import com.coding.common.models.app.AppObjectStatusDTO;
import com.coding.common.models.app.AppProblemDTO;
import com.coding.common.models.app.AppPruneRequestDTO;
import com.coding.common.models.app.AppRenderRequestDTO;
import com.coding.common.models.app.AppRenderResultDTO;
import com.coding.common.models.app.AppStatusRequestDTO;
import com.coding.common.models.app.AppStatusResultDTO;
import com.coding.data.mapper.system.PlatformAppExecutionMapper;
import com.coding.data.mapper.system.PlatformAppReleaseMapper;
import com.coding.data.mapper.system.PlatformApplicationMapper;
import com.coding.data.mapper.system.PlatformApplicationResourceMapper;
import com.coding.data.models.system.PlatformAppExecution;
import com.coding.data.models.system.PlatformAppRelease;
import com.coding.data.models.system.PlatformApplication;
import com.coding.data.models.system.PlatformApplicationResource;
import com.coding.platformapi.k8s.K8sAppClient;
import com.coding.platformapi.k8s.K8sServerGateway;
import com.coding.platformapi.k8s.K8sServerUnauthorizedException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 发布执行的阶段机：PRECHECK → RENDER → CONFLICT → APPLY → HEALTH → PRUNE → DONE。
 *
 * <p>{@link #advance} 每次只走一个阶段并落库，HEALTH 未就绪时直接返回，等下一拍再看，不在这里等待。
 * 所有阶段都可重入：进程在任何位置中断，从落库的阶段重跑都安全。
 *
 * <p>新版本健康之前不删旧对象，所以 HEALTH 在 PRUNE 之前。失败不自动回滚。
 *
 * <p>凭据是发起人的 token，存内存 + Redis（TTL = token 剩余有效期，不落库）。拿不到凭据、或被 k8s-server 拒绝
 * → INTERRUPTED（等用户「继续」），不是 FAILED：发布本身没有问题，只是缺一个能用的身份。
 *
 * <p><b>拿不到凭据 == 接管了别的实例的执行，且共享的那份也没有</b>：正常情况下执行有归属（owner_id + 租约续期，
 * 见 {@link AppExecutionLease}），别的实例抢不到；能走到这里说明原进程已停机 / 租约过期，
 * 而 Redis 里取不到（不通，或 token 已过期）。
 * 两种原因的提示语分开写（一个要重新登录、一个不用），否则线上只能靠猜。
 */
@Slf4j
@Service
public class AppReleaseExecutor {

    static final String PENDING = "PENDING";
    static final String RUNNING = "RUNNING";
    static final String INTERRUPTED = "INTERRUPTED";
    static final String FAILED = "FAILED";
    static final String SUCCESS = "SUCCESS";

    /** 下线发布：快照不含服务，跳过渲染 / 应用 / 健康检查，直接清掉本应用的全部对象（见 {@link AppReleaseService#offline}） */
    public static final String OFFLINE_TYPE = "OFFLINE";

    private static final int MESSAGE_MAX = 1024;   // platform_app_execution.message 的列宽
    private static final String CREDENTIAL_LOST =
            "发起人凭据已失效（平台重启或登录过期），请点击「继续」";
    private static final String CREDENTIAL_REJECTED =
            "k8s-server 拒绝了发起人的凭据（HTTP 401），请重新登录后点「继续」";

    private static final ObjectMapper JSON = JsonMapper.builder().build();
    private static final JavaType OBJECT_LIST =
            JSON.getTypeFactory().constructCollectionType(List.class, AppObjectRefDTO.class);

    private final PlatformApplicationMapper apps;
    private final PlatformAppReleaseMapper releases;
    private final PlatformAppExecutionMapper executions;
    private final PlatformApplicationResourceMapper resources;
    private final K8sAppClient client;
    private final ApplicationDeployCredentials credentials;
    private final AppExecutionLease leases;
    private final ObjectProvider<AppReleasePrecheck> precheck;
    private final ObjectProvider<AppReleaseRenderer> renderer;
    private final AppExecutionJournal journal;
    private final long timeoutMinutes;

    public AppReleaseExecutor(PlatformApplicationMapper apps,
                              PlatformAppReleaseMapper releases,
                              PlatformAppExecutionMapper executions,
                              PlatformApplicationResourceMapper resources,
                              K8sAppClient client,
                              ApplicationDeployCredentials credentials,
                              AppExecutionLease leases,
                              ObjectProvider<AppReleasePrecheck> precheck,
                              ObjectProvider<AppReleaseRenderer> renderer,
                              AppExecutionJournal journal,
                              @Value("${platform.application.timeout-minutes:30}") long timeoutMinutes) {
        this.apps = apps;
        this.releases = releases;
        this.executions = executions;
        this.resources = resources;
        this.client = client;
        this.credentials = credentials;
        this.leases = leases;
        this.precheck = precheck;
        this.renderer = renderer;
        this.journal = journal;
        this.timeoutMinutes = timeoutMinutes;
    }

    /** 推进一个阶段。调用方负责持有租约。 */
    public void advance(PlatformAppExecution execution) {
        String authorization = credentials.getExecution(execution.getId());
        if (authorization == null) {
            interruptNoCredential(execution);
            return;
        }
        K8sServerGateway.runAs(authorization, () -> step(execution));
    }

    /**
     * 从原阶段继续，发布号不变，改用调用者当前的 token。
     * 只允许该应用最新发布的那条执行：旧发布再跑会把线上版本倒回去。
     *
     * <p>归属校验由调用方（controller）负责。
     */
    public void resume(Long executionId, String operator) {
        PlatformAppExecution execution = executions.selectById(executionId);
        if (execution == null) {
            throw reject("执行记录不存在");
        }
        if (!FAILED.equals(execution.getStatus()) && !INTERRUPTED.equals(execution.getStatus())) {
            throw reject("只有失败或中断的执行可以继续");
        }
        PlatformAppExecution latest = executions.selectLatestByApp(execution.getAppId());
        Integer maxNo = releases.selectMaxNo(execution.getAppId());
        if (latest == null || !executionId.equals(latest.getId())
                || (maxNo != null && !maxNo.equals(execution.getReleaseNo()))) {
            throw reject("已有更新的发布，不能继续旧发布");
        }
        String authorization = K8sServerGateway.currentAuthorization();
        if (authorization == null || authorization.isBlank()) {
            throw reject("当前请求没有可用的凭据");
        }
        // 先记凭据再重开：否则 worker 可能抢在中间，因没有凭据又把它打回 INTERRUPTED
        credentials.rememberExecution(executionId, authorization);
        Date now = new Date();
        int reopened;
        try {
            // 归属和租约一并写给续跑方：否则刚交接完就会被另一台实例抢走（只能连点「继续」的根因）
            reopened = executions.reopen(executionId, PENDING, now, leases.instanceId(), leases.nextLease(now));
        } catch (DuplicateKeyException e) {
            // 与新发布的取代撞车：uk_app_exec_active 保证同一应用只有一条活动执行
            credentials.forgetExecution(executionId);
            throw reject("该应用已有新的发布在进行，不能继续旧发布");
        } catch (RuntimeException e) {
            credentials.forgetExecution(executionId);
            throw e;
        }
        if (reopened == 0) {
            credentials.forgetExecution(executionId);
            throw reject("执行状态已变化，请刷新后重试");
        }
        if (FAILED.equals(execution.getStatus())) {
            markRelease(execution, "RENDERED");
            PlatformApplication patch = new PlatformApplication();
            patch.setId(execution.getAppId());
            patch.setStatus("DEPLOYING");
            patch.setUpdatedAt(new Date());
            apps.updateById(patch);
        }
        log.info("发布执行 {} 由 {} 续跑，阶段 {}，发布号 {}", executionId, operator,
                execution.getStage(), execution.getReleaseNo());
        journal.info(execution, execution.getStage(), (operator == null ? "" : operator + " ")
                + (FAILED.equals(execution.getStatus()) ? "重新执行" : "点了继续") + "，从「"
                + stageName(execution.getStage()) + "」接着跑");
    }

    private void step(PlatformAppExecution execution) {
        try {
            String stage = execution.getStage();
            execution.setStatus(RUNNING);
            String next = switch (stage == null ? "PRECHECK" : stage) {
                case "PRECHECK" -> precheck(execution);
                case "RENDER" -> render(execution);
                case "CONFLICT" -> conflict(execution);
                case "APPLY" -> apply(execution);
                case "HEALTH" -> health(execution);
                case "PRUNE" -> prune(execution);
                case "DONE" -> finish(execution);
                default -> throw new CloudPlatformException(EnumResponseType.ERROR, "未知阶段：" + stage);
            };
            if (next != null) {
                execution.setStage(next);
            }
            if (!SUCCESS.equals(execution.getStatus())) {
                save(execution);
            }
        } catch (RuntimeException e) {
            if (isUnauthorized(e)) {
                interruptRejected(execution, e);
            } else {
                fail(execution, e);
            }
        }
    }

    private String precheck(PlatformAppExecution execution) {
        PlatformApplication app = requireApp(execution);
        if (app.getNamespace() == null || app.getNamespace().isBlank()) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "应用没有命名空间");
        }
        if (isOffline(requireRelease(execution))) {
            journal.info(execution, "PRECHECK", "下线：跳过渲染、冲突检查与健康检查，直接删除本应用的集群对象（数据卷保留）");
            return "PRUNE";   // 下线不渲染、不校验依赖：没有要上线的东西
        }
        AppReleasePrecheck check = precheck.getIfAvailable();
        if (check == null) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "发布校验器未注册");
        }
        check.check(execution);
        journal.info(execution, "PRECHECK", "预检通过：引用的依赖已就绪，变量与配置文件引用均可解析");
        return "RENDER";
    }

    private String render(PlatformAppExecution execution) {
        PlatformAppRelease release = requireRelease(execution);
        if (isOffline(release)) {
            return "PRUNE";
        }
        if (release.getManifest() != null && !release.getManifest().isBlank()) {
            journal.info(execution, "RENDER", "已渲染过（续跑），沿用上次的清单");
            return "CONFLICT";   // 续跑：已渲染过
        }
        AppReleaseRenderer builder = renderer.getIfAvailable();
        if (builder == null) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "发布渲染器未注册");
        }
        AppRenderRequestDTO request = builder.build(requireApp(execution), release);
        AppRenderResultDTO rendered = client.render(request);
        String error = rendered.getProblems() == null ? null : rendered.getProblems().stream()
                .filter(p -> "ERROR".equals(p.getSeverity()))
                .map(AppProblemDTO::getMessage)
                .findFirst().orElse(null);
        if (error != null) {
            throw new CloudPlatformException(EnumResponseType.ERROR, error);
        }
        if (releases.updateRendered(release.getId(), rendered.getManifest(),
                JSON.writeValueAsString(rendered.getObjects())) == 0) {
            // 0 行：要么别的副本刚写过（正常），要么条件没命中而行里仍没有清单（必须失败，不能带着空清单往下走）
            PlatformAppRelease fresh = releases.selectByNo(execution.getAppId(), execution.getReleaseNo());
            if (fresh == null || fresh.getManifest() == null || fresh.getManifest().isBlank()) {
                throw new CloudPlatformException(EnumResponseType.ERROR, "渲染结果未能写入发布记录");
            }
        }
        List<AppObjectRefDTO> objs = rendered.getObjects() == null ? List.of() : rendered.getObjects();
        journal.info(execution, "RENDER", "渲染完成，共 " + objs.size() + " 个对象：" + names(objs));
        return "CONFLICT";
    }

    /** 要写的对象已存在、又不在本应用台账里 → 拒绝。否则 SSA 会静默接管别人的对象，之后还可能被 prune 删掉。 */
    private String conflict(PlatformAppExecution execution) {
        if (isOffline(requireRelease(execution))) {
            return "PRUNE";
        }
        PlatformApplication app = requireApp(execution);
        AppStatusResultDTO status = statusOf(app, objectsOf(requireRelease(execution)));
        Set<String> ours = resources.selectByApp(execution.getAppId()).stream()
                .map(r -> r.getKind() + "/" + r.getName())
                .collect(Collectors.toSet());
        if (status != null && status.getObjects() != null) {
            for (AppObjectStatusDTO s : status.getObjects()) {
                String id = s.getKind() + "/" + s.getName();
                if (!"MISSING".equals(s.getState()) && !"UNKNOWN".equals(s.getState()) && !ours.contains(id)) {
                    throw new CloudPlatformException(EnumResponseType.ERROR,
                            "对象已存在且不属于本应用：" + id + "（请改名或先手工处理）");
                }
            }
        }
        journal.info(execution, "CONFLICT", "冲突检查通过：没有不属于本应用的同名对象");
        return "APPLY";
    }

    /**
     * 台账先于 apply 写：apply 中途失败时集群里已有部分对象，
     * 台账若没记，用户改发新版本时会被 CONFLICT 判成「不属于本应用」。
     */
    private String apply(PlatformAppExecution execution) {
        PlatformApplication app = requireApp(execution);
        PlatformAppRelease release = requireRelease(execution);
        if (isOffline(release)) {
            return "PRUNE";
        }
        requireObjects(release);
        resources.deleteByAppRevision(execution.getAppId(), execution.getReleaseNo());
        String prefix = app.getSlug() + "-";
        List<PlatformApplicationResource> rows = objectsOf(release).stream()
                .map(ref -> toResource(execution, ref, nodeKeyOf(ref.getName(), prefix)))
                .toList();
        if (!rows.isEmpty()) {
            resources.batchInsert(rows);
        }
        AppApplyRequestDTO request = new AppApplyRequestDTO();
        request.setClusterId(app.getClusterId());
        request.setManifest(release.getManifest());
        client.apply(request);
        journal.info(execution, "APPLY", "已下发 " + rows.size() + " 个对象到命名空间 " + app.getNamespace()
                + "，开始等待就绪");
        return "HEALTH";
    }

    /** 必须清单里每个对象都明确 READY；状态缺失不能当就绪，否则会误删旧版本。返回 null = 留在本阶段。 */
    private String health(PlatformAppExecution execution) {
        if (isOffline(requireRelease(execution))) {
            return "PRUNE";
        }
        PlatformApplication app = requireApp(execution);
        List<AppObjectRefDTO> objects = requireObjects(requireRelease(execution));
        AppStatusResultDTO status = null;
        try {
            status = statusOf(app, objects);
        } catch (RuntimeException e) {
            if (isUnauthorized(e)) {
                throw e;
            }
            // 瞬时故障算「未就绪」，由下面的超时兜底
            log.warn("发布执行 {} 查询对象状态失败，按未就绪处理：{}", execution.getId(), e.getMessage());
        }
        Map<String, String> states = new HashMap<>();
        if (status != null && status.getObjects() != null) {
            status.getObjects().forEach(s -> states.put(s.getKind() + "/" + s.getName(), s.getState()));
        }
        Map<String, String> notes = new HashMap<>();
        Map<String, List<String>> podIssues = new HashMap<>();
        if (status != null && status.getObjects() != null) {
            status.getObjects().forEach(s -> {
                notes.put(s.getKind() + "/" + s.getName(), s.getMessage());
                if (s.getPodIssues() != null && !s.getPodIssues().isEmpty()) {
                    podIssues.put(s.getKind() + "/" + s.getName(), s.getPodIssues());
                }
            });
        }
        List<String> notReady = new ArrayList<>();
        List<String> detail = new ArrayList<>();
        for (AppObjectRefDTO o : objects) {
            String key = o.getKind() + "/" + o.getName();
            String state = states.get(key);
            if (!"READY".equals(state)) {
                String note = notes.get(key);
                List<String> pods = podIssues.getOrDefault(key, List.of());
                notReady.add(key + "（" + (state == null ? "无状态" : state)
                        + (note == null || note.isBlank() ? "" : "：" + (note.length() > 200 ? note.substring(0, 200) + "…" : note))
                        + (pods.isEmpty() ? "" : "；" + pods.get(0)) + "）");
                detail.add(key + " " + stateText(state) + (note == null || note.isBlank() ? "" : "（" + note + "）"));
                pods.forEach(p -> detail.add("  · " + p));
            }
        }
        if (notReady.isEmpty()) {
            execution.setMessage("");
            journal.info(execution, "HEALTH", "全部 " + objects.size() + " 个对象已就绪");
            return "PRUNE";
        }
        if (status == null) {
            detail.add(0, "（本拍查询对象状态失败，按未就绪处理）");
        }
        journal.infoIfChanged(execution, "HEALTH", "等待就绪（" + (objects.size() - notReady.size()) + "/"
                + objects.size() + " 已就绪）：\n" + String.join("\n", detail));
        if (execution.getStartedAt() != null
                && Duration.between(execution.getStartedAt().toInstant(), Instant.now())
                .compareTo(Duration.ofMinutes(timeoutMinutes)) > 0) {
            throw new CloudPlatformException(EnumResponseType.ERROR,
                    clip("发布超时（" + timeoutMinutes + " 分钟）：仍有对象未就绪：" + String.join("；", notReady)));
        }
        execution.setMessage(clip("等待就绪：" + String.join("；", notReady)));
        return null;
    }

    /** message 列宽 1024：超出则截断并以省略号收尾。 */
    private static String clip(String s) {
        return s.length() <= MESSAGE_MAX ? s : s.substring(0, MESSAGE_MAX - 1) + "…";
    }

    /**
     * 基线 = 台账中 [live, 当前发布号) 的所有行（live 为 0 时是此前全部行），减去当前发布的对象。
     * 不能只看 live：中间失败的发布可能已 apply 了一部分对象，必须等后续发布健康后一并清掉。PVC 承载数据，不删。
     */
    private String prune(PlatformAppExecution execution) {
        PlatformApplication app = requireApp(execution);
        PlatformAppRelease release = requireRelease(execution);
        if (isOffline(release)) {
            return pruneAll(app, execution);
        }
        int live = app.getLiveReleaseNo() == null ? 0 : app.getLiveReleaseNo();
        // 清单为空时 current 为空集，会把线上对象全部判成多余：必须先拦住
        Set<String> current = requireObjects(release).stream()
                .map(r -> r.getKind() + "/" + r.getName())
                .collect(Collectors.toSet());
        List<AppObjectRefDTO> stale = resources.selectByRevisionRange(execution.getAppId(), live, execution.getReleaseNo()).stream()
                .filter(r -> !current.contains(r.getKind() + "/" + r.getName()))
                .map(AppReleaseExecutor::toRef)
                .toList();
        if (!stale.isEmpty()) {
            AppPruneRequestDTO request = new AppPruneRequestDTO();
            request.setClusterId(app.getClusterId());
            request.setObjects(stale);
            request.setDeletePvc(false);
            client.prune(request);
            journal.info(execution, "PRUNE", "清理旧版本留下的对象 " + stale.size() + " 个（数据卷保留）：" + names(stale));
        } else {
            journal.info(execution, "PRUNE", "没有需要清理的旧对象");
        }
        return "DONE";
    }

    /**
     * 下线：台账里本应用的全部对象（不分发布号，连同中途失败留下的）都删，PVC 跳过。
     * 删除幂等（不存在的对象直接跳过），中途失败点「继续」从 PRUNE 重来即可。
     */
    private String pruneAll(PlatformApplication app, PlatformAppExecution execution) {
        Map<String, AppObjectRefDTO> all = new LinkedHashMap<>();
        for (PlatformApplicationResource r : resources.selectByApp(execution.getAppId())) {
            AppObjectRefDTO ref = toRef(r);
            all.putIfAbsent(ref.getKind() + "/" + ref.getNamespace() + "/" + ref.getName(), ref);
        }
        if (!all.isEmpty()) {
            AppPruneRequestDTO request = new AppPruneRequestDTO();
            request.setClusterId(app.getClusterId());
            request.setObjects(new ArrayList<>(all.values()));
            request.setDeletePvc(false);
            client.prune(request);
            journal.info(execution, "PRUNE", "已删除 " + all.size() + " 个对象（数据卷保留）："
                    + names(new ArrayList<>(all.values())));
        } else {
            journal.info(execution, "PRUNE", "台账里没有本应用的对象，无需删除");
        }
        return "DONE";
    }

    /** 「Deployment/x、Service/x …」；太长截断（日志列宽 2000） */
    private static String names(List<AppObjectRefDTO> refs) {
        String joined = refs.stream().map(r -> r.getKind() + "/" + r.getName()).collect(Collectors.joining("、"));
        return joined.length() <= 1500 ? joined : joined.substring(0, 1499) + "…";
    }

    private static String stateText(String state) {
        if (state == null) {
            return "状态未知";
        }
        return switch (state) {
            case "PENDING" -> "未就绪";
            case "MISSING" -> "不存在";
            case "FAILED" -> "失败";
            case "UNKNOWN" -> "状态未知";
            default -> state;
        };
    }

    static String stageName(String stage) {
        if (stage == null) {
            return "预检";
        }
        return switch (stage) {
            case "PRECHECK" -> "预检";
            case "RENDER" -> "渲染";
            case "CONFLICT" -> "冲突检查";
            case "APPLY" -> "下发";
            case "HEALTH" -> "健康检查";
            case "PRUNE" -> "清理";
            case "DONE" -> "完成";
            default -> stage;
        };
    }

    static boolean isOffline(PlatformAppRelease release) {
        return OFFLINE_TYPE.equals(release.getType());
    }

    /** 执行记录最后写：中途崩溃时它仍在 DONE，下一拍重跑，前面的写入都幂等。 */
    private String finish(PlatformAppExecution execution) {
        PlatformApplication patch = new PlatformApplication();
        patch.setId(execution.getAppId());
        patch.setLiveReleaseNo(execution.getReleaseNo());
        patch.setStatus(isOffline(requireRelease(execution)) ? "OFFLINE" : "READY");
        patch.setUpdatedAt(new Date());
        apps.updateById(patch);
        markRelease(execution, "DEPLOYED");

        execution.setStatus(SUCCESS);
        execution.setFinishedAt(new Date());
        save(execution);
        if (isOffline(requireRelease(execution))) {
            // 对象已清掉：释放网关端口、同步 ListenerSet（实现自己吞异常，不改变下线结论）
            AppReleasePrecheck hook = precheck.getIfAvailable();
            if (hook != null) {
                hook.afterOffline(execution);
            }
        }
        credentials.forgetExecution(execution.getId());
        journal.info(execution, "DONE", isOffline(requireRelease(execution))
                ? "下线完成" : "发布完成，线上版本 #" + execution.getReleaseNo());
        return null;
    }

    /**
     * 本进程没有这个执行的凭据。正常只发生在「接管了已死实例留下的执行」：租约没过期时别的实例抢不到，
     * 能抢到就说明原进程已经不在了。不写 finished_at：INTERRUPTED 仍是活动执行。
     */
    private void interruptNoCredential(PlatformAppExecution execution) {
        log.info("发布执行 {} 在阶段 {} 中断：本实例（{}）内存与 Redis 里都没有它的凭据，原推进实例已不在",
                execution.getId(), execution.getStage(), leases.instanceId());
        interrupt(execution, CREDENTIAL_LOST);
    }

    /** k8s-server 明确拒绝（HTTP 401）：token 过期或验签不过，用户重新登录后点「继续」即可。与上面分开写，不再靠猜。 */
    private void interruptRejected(PlatformAppExecution execution, RuntimeException cause) {
        log.info("发布执行 {} 在阶段 {} 被 k8s-server 拒绝凭据（HTTP 401）：{}",
                execution.getId(), execution.getStage(), cause.getMessage());
        interrupt(execution, CREDENTIAL_REJECTED);
    }

    /** 不写 finished_at：INTERRUPTED 仍是活动执行。 */
    private void interrupt(PlatformAppExecution execution, String message) {
        execution.setStatus(INTERRUPTED);
        execution.setMessage(message);
        save(execution);
        credentials.forgetExecution(execution.getId());
        journal.warn(execution, execution.getStage(), "在「" + stageName(execution.getStage()) + "」中断：" + message);
    }

    private void fail(PlatformAppExecution execution, RuntimeException e) {
        log.warn("发布执行 {} 在阶段 {} 失败", execution.getId(), execution.getStage(), e);
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        execution.setStatus(FAILED);
        execution.setMessage(clip(message));
        execution.setFinishedAt(new Date());
        save(execution);
        credentials.forgetExecution(execution.getId());
        journal.error(execution, execution.getStage(), "在「" + stageName(execution.getStage()) + "」失败：" + message);
        markRelease(execution, "FAILED");

        PlatformApplication patch = new PlatformApplication();
        patch.setId(execution.getAppId());
        patch.setStatus("FAILED");
        patch.setUpdatedAt(new Date());
        apps.updateById(patch);
    }

    /** 只写进度字段，不带 lease_until：入参里的旧租约不能写回去，租约归 worker 管。 */
    private void save(PlatformAppExecution execution) {
        PlatformAppExecution patch = new PlatformAppExecution();
        patch.setId(execution.getId());
        patch.setStatus(execution.getStatus());
        patch.setStage(execution.getStage());
        patch.setMessage(execution.getMessage());
        patch.setFinishedAt(execution.getFinishedAt());
        executions.updateById(patch);
    }

    private void markRelease(PlatformAppExecution execution, String status) {
        PlatformAppRelease release = releases.selectByNo(execution.getAppId(), execution.getReleaseNo());
        if (release != null) {
            PlatformAppRelease patch = new PlatformAppRelease();
            patch.setId(release.getId());
            patch.setStatus(status);
            releases.updateById(patch);
        }
    }

    /** 沿异常链找：precheck 等环节可能把网关异常包一层。 */
    private static boolean isUnauthorized(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof K8sServerUnauthorizedException) {
                return true;
            }
        }
        return false;
    }

    private static CloudPlatformException reject(String message) {
        return new CloudPlatformException(EnumResponseType.ERROR, message);
    }

    private AppStatusResultDTO statusOf(PlatformApplication app, List<AppObjectRefDTO> objects) {
        AppStatusRequestDTO request = new AppStatusRequestDTO();
        request.setClusterId(app.getClusterId());
        request.setObjects(objects);
        return client.status(request);
    }

    private PlatformApplication requireApp(PlatformAppExecution execution) {
        PlatformApplication app = apps.selectById(execution.getAppId());
        if (app == null || app.getDeletedAt() != null) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "应用不存在或已删除");
        }
        return app;
    }

    private PlatformAppRelease requireRelease(PlatformAppExecution execution) {
        PlatformAppRelease release = releases.selectByNo(execution.getAppId(), execution.getReleaseNo());
        if (release == null) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "发布不存在：" + execution.getReleaseNo());
        }
        return release;
    }

    /** 有清单却解析不出对象 = 数据异常；继续会让健康检查空转通过、prune 删光线上对象。 */
    private static List<AppObjectRefDTO> requireObjects(PlatformAppRelease release) {
        List<AppObjectRefDTO> objects = objectsOf(release);
        if (objects.isEmpty() && release.getManifest() != null && !release.getManifest().isBlank()) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "发布有清单但对象列表为空，拒绝继续以免误删线上对象");
        }
        return objects;
    }

    private static List<AppObjectRefDTO> objectsOf(PlatformAppRelease release) {
        String json = release.getObjectsJson();
        if (json == null || json.isBlank()) {
            return List.of();
        }
        return JSON.readValue(json, OBJECT_LIST);
    }

    /** 对象名 = &lt;slug&gt;-&lt;key&gt;；headless Service / env、files ConfigMap 归到同一个 key。 */
    private static String nodeKeyOf(String objectName, String slugPrefix) {
        String key = objectName != null && objectName.startsWith(slugPrefix)
                ? objectName.substring(slugPrefix.length()) : objectName;
        if (key != null && key.endsWith("-headless")) {
            key = key.substring(0, key.length() - "-headless".length());
        }
        if (key != null && (key.endsWith("-env") || key.endsWith("-secret") || key.endsWith("-files"))) {
            key = key.substring(0, key.lastIndexOf('-'));
        }
        return key;
    }

    private static PlatformApplicationResource toResource(PlatformAppExecution execution,
                                                          AppObjectRefDTO ref, String nodeKey) {
        PlatformApplicationResource r = new PlatformApplicationResource();
        r.setAppId(execution.getAppId());
        r.setRevision(execution.getReleaseNo());
        r.setApiVersion(ref.getApiVersion());
        r.setKind(ref.getKind());
        r.setNamespace(ref.getNamespace());
        r.setName(ref.getName());
        r.setNodeKey(nodeKey);
        r.setCreatedAt(new Date());
        return r;
    }

    private static AppObjectRefDTO toRef(PlatformApplicationResource r) {
        AppObjectRefDTO ref = new AppObjectRefDTO();
        ref.setApiVersion(r.getApiVersion());
        ref.setKind(r.getKind());
        ref.setNamespace(r.getNamespace());
        ref.setName(r.getName());
        return ref;
    }
}
