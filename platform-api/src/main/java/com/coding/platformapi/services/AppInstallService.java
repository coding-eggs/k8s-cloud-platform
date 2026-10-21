package com.coding.platformapi.services;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.k8s.dto.ChartApplyRequestDTO;
import com.coding.common.models.k8s.dto.ChartApplyResultDTO;
import com.coding.common.models.k8s.dto.ChartPreviewRequestDTO;
import com.coding.common.models.k8s.dto.ChartPreviewResultDTO;
import com.coding.common.models.k8s.dto.ChartRefDTO;
import com.coding.common.models.k8s.dto.ChartUninstallRequestDTO;
import com.coding.data.mapper.system.PlatformAppMapper;
import com.coding.data.mapper.system.PlatformAppOperationMapper;
import com.coding.data.models.system.PlatformApp;
import com.coding.data.models.system.PlatformAppOperation;
import com.coding.platformapi.k8s.K8sChartClient;
import com.coding.platformapi.k8s.K8sServerGateway;
import com.coding.platformapi.models.AppInstallRequest;
import com.coding.platformapi.models.AppPreviewRequest;
import com.coding.platformapi.models.AppUninstallRequest;
import com.coding.platformapi.models.AppUpgradeRequest;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * 应用安装/升级/卸载（spec §4.5）。异步执行 + 操作记录，形态与 S3.6 的组件安装一致。
 *
 * <p>本计划只有管理员路径（strict=false）。租户闸门（目录白名单、分配表）在计划②，
 * 但 <b>strict 由服务端决定，不接受前端传入</b>。
 *
 * <p><b>worker 线程必须套 {@link K8sServerGateway#runAs}</b>：虚拟线程里没有 RequestContextHolder，
 * 不委托就会不带 Authorization 调 k8s-server（401）。这是 S3.6 已经踩过的坑。
 */
@Service
public class AppInstallService {

    /** 超过这个分钟数仍在 PENDING/RUNNING 的操作视为孤儿（进程重启/worker 丢失） */
    private static final int STALE_MINUTES = 30;

    private final PlatformAppMapper apps;
    private final PlatformAppOperationMapper ops;
    private final K8sChartClient client;

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public AppInstallService(PlatformAppMapper apps, PlatformAppOperationMapper ops, K8sChartClient client) {
        this.apps = apps;
        this.ops = ops;
        this.client = client;
    }

    public Long install(AppInstallRequest req, String operator) {
        requireBasic(req.getClusterId(), req.getNamespace());
        if (req.getChartRef() == null || isBlank(req.getChartRef().getVersion())) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "请选择 chart 与版本");
        }
        PlatformApp app = new PlatformApp();
        app.setId("app_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        app.setClusterId(req.getClusterId());
        app.setNamespace(req.getNamespace());
        app.setReleaseName("app-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8));
        app.setSource(req.getChartRef().getSource());
        app.setChartRef(req.getChartRef().getRef());
        app.setChartName(req.getChartRef().getChartName());
        app.setVersion(req.getChartRef().getVersion());
        app.setDigest(req.getChartRef().getDigest() == null ? "" : req.getChartRef().getDigest());
        app.setValuesJson(req.getValues());
        app.setAppKind("APP");
        app.setDisplayName(isBlank(req.getDisplayName())
                ? req.getChartRef().getChartName() : req.getDisplayName());
        app.setStatus("INSTALLING");
        app.setNeedsReview(false);
        app.setCreatedBy(operator);
        app.setCreatedAt(new Date());
        app.setUpdatedAt(new Date());
        apps.insert(app);

        PlatformAppOperation op = newOperation(app, "INSTALL", null, app.getVersion(), operator, req.getValues());
        run(op, callerAuthorization(),
                () -> client.apply(applyRequest(app, req.getValues(), false, false)), app);
        return op.getId();
    }

    public Long upgrade(AppUpgradeRequest req, String operator) {
        PlatformApp app = requireOwned(req.getAppId());
        if (isBlank(req.getVersion())) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "请选择目标版本");
        }
        String from = app.getVersion();
        String targetVersion = req.getVersion();
        String targetValues = req.getValues() == null ? app.getValuesJson() : req.getValues();

        // ⚠️ 先拿锁再改应用行：并发的失败方不能把 version/values 留在库里（复审 I1）
        PlatformAppOperation op = newOperation(app, "UPGRADE", from, targetVersion, operator, targetValues);

        app.setVersion(targetVersion);
        if (req.getDigest() != null) {
            app.setDigest(req.getDigest());
        } else if (!targetVersion.equals(from)) {
            // 旧版本的 digest 不能拿去校验新版本（复审 I3）：换了版本又没给 digest → 退回"不校验"
            app.setDigest("");
        }
        if (req.getValues() != null) {
            app.setValuesJson(req.getValues());
        }
        app.setStatus("INSTALLING");
        app.setUpdatedAt(new Date());
        apps.updateById(app);

        run(op, callerAuthorization(),
                () -> client.apply(applyRequest(app, app.getValuesJson(), false, true)), app);
        return op.getId();
    }

    public Long uninstall(AppUninstallRequest req, String operator) {
        PlatformApp app = requireOwned(req.getAppId());
        PlatformAppOperation op = newOperation(app, "UNINSTALL", app.getVersion(), null, operator, null);
        run(op, callerAuthorization(), () -> {
            Boolean ok = client.uninstall(uninstallRequest(app));
            ChartApplyResultDTO dto = new ChartApplyResultDTO();
            dto.setSuccess(Boolean.TRUE.equals(ok));
            dto.setMessage(Boolean.TRUE.equals(ok) ? "uninstalled" : "helm uninstall failed");
            return dto;
        }, app);
        return op.getId();
    }

    public PlatformAppOperation get(Long operationId) {
        return ops.selectByPrimaryKey(operationId);
    }

    public List<PlatformApp> list(String clusterId, String namespace) {
        return apps.selectByCluster(clusterId, namespace);
    }

    public PlatformApp getApp(String appId) {
        return apps.selectByPrimaryKey(appId);
    }

    /** 渲染预览（不落盘）：安装向导用它显示将要创建的对象与阻断项。 */
    public ChartPreviewResultDTO preview(AppPreviewRequest req) {
        requireBasic(req.getClusterId(), req.getNamespace());
        ChartPreviewRequestDTO body = new ChartPreviewRequestDTO();
        body.setClusterId(req.getClusterId());
        body.setNamespace(req.getNamespace());
        body.setStrict(false); // 租户闸门在计划②
        body.setChart(req.getChartRef());
        body.setValues(req.getValues());
        return client.preview(body);
    }

    /**
     * 回收孤儿操作：进程重启/worker 丢失后，PENDING/RUNNING 会永久占住 running_lock。
     * 把超期操作置 UNKNOWN（释放锁），对应应用置 FAILED。
     */
    public void cleanupStale(int minutes) {
        for (PlatformAppOperation op : ops.selectStaleRunning(minutes)) {
            op.setStatus("UNKNOWN");
            op.setMessage("平台重启或 worker 丢失，操作状态未知（并发锁已释放）");
            op.setFinishedAt(new Date());
            ops.updateById(op);
            PlatformApp app = apps.selectByPrimaryKey(op.getAppId());
            if (app != null) {
                app.setStatus("FAILED");
                app.setUpdatedAt(new Date());
                apps.updateById(app);
            }
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void reapOnStartup() {
        cleanupStale(STALE_MINUTES);
    }

    // ---------- 内部 ----------

    /** 请求线程取 header；不在请求线程时退回委托值（后台调用场景） */
    private static String callerAuthorization() {
        String auth = K8sServerGateway.currentAuthorization();
        return auth != null ? auth : K8sServerGateway.delegatedAuthorization();
    }

    private PlatformApp requireOwned(String appId) {
        PlatformApp app = appId == null ? null : apps.selectByPrimaryKey(appId);
        if (app == null) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "该应用不是平台安装的，平台不接管");
        }
        return app;
    }

    private static void requireBasic(String clusterId, String namespace) {
        if (isBlank(clusterId)) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "请选择集群");
        }
        if (isBlank(namespace)) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "请选择命名空间");
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private PlatformAppOperation newOperation(PlatformApp app, String action, String from, String to,
                                              String operator, String values) {
        PlatformAppOperation op = new PlatformAppOperation();
        op.setClusterId(app.getClusterId());
        op.setAppId(app.getId());
        op.setAction(action);
        op.setFromVersion(from);
        op.setToVersion(to);
        op.setValuesSnapshot(values);
        op.setStatus("PENDING");
        op.setOperator(operator);
        op.setStartedAt(new Date());
        try {
            ops.insert(op);
        } catch (DuplicateKeyException e) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "该应用正在安装/升级，请等待当前操作结束");
        }
        return op;
    }

    private void run(PlatformAppOperation op, String authorization,
                     Supplier<ChartApplyResultDTO> call, PlatformApp app) {
        executor.submit(() -> K8sServerGateway.runAs(authorization, () -> {
            op.setStatus("RUNNING");
            ops.updateById(op);
            try {
                ChartApplyResultDTO result = call.get();
                boolean ok = result != null && result.isSuccess();
                op.setStatus(ok ? "SUCCESS" : "FAILED");
                op.setMessage(truncate(result == null ? "无返回" : result.getMessage(), 1000));
                op.setLogTail(result == null ? null : truncate(result.getLogTail(), 60000));
                app.setStatus("UNINSTALL".equals(op.getAction())
                        ? (ok ? "UNINSTALLED" : "FAILED")
                        : (ok ? "RUNNING" : "FAILED"));
                app.setNeedsReview(ok && result.getViolations() != null && !result.getViolations().isEmpty());
                app.setUpdatedAt(new Date());
                apps.updateById(app);
            } catch (Exception e) {
                op.setStatus("FAILED");
                op.setMessage(truncate(e.getMessage(), 1000));
                app.setStatus("FAILED");
                app.setNeedsReview(false);
                app.setUpdatedAt(new Date());
                apps.updateById(app);
            } finally {
                op.setFinishedAt(new Date());
                ops.updateById(op);
            }
        }));
    }

    private static ChartApplyRequestDTO applyRequest(PlatformApp app, String values,
                                                     boolean strict, boolean upgrade) {
        ChartApplyRequestDTO body = new ChartApplyRequestDTO();
        body.setClusterId(app.getClusterId());
        body.setAppId(app.getId());
        body.setReleaseName(app.getReleaseName());
        body.setNamespace(app.getNamespace());
        body.setStrict(strict);
        body.setUpgrade(upgrade);
        ChartRefDTO chart = new ChartRefDTO();
        chart.setSource(app.getSource());
        chart.setRef(app.getChartRef());
        chart.setChartName(app.getChartName());
        chart.setVersion(app.getVersion());
        chart.setDigest(app.getDigest());
        body.setChart(chart);
        body.setValues(values);
        body.setLabels(Map.of(
                "platform.coding/managed", "true",
                "platform.coding/app", app.getId()));
        return body;
    }

    private static ChartUninstallRequestDTO uninstallRequest(PlatformApp app) {
        ChartUninstallRequestDTO body = new ChartUninstallRequestDTO();
        body.setClusterId(app.getClusterId());
        body.setAppId(app.getId());
        body.setReleaseName(app.getReleaseName());
        body.setNamespace(app.getNamespace());
        return body;
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(s.length() - max);
    }
}
