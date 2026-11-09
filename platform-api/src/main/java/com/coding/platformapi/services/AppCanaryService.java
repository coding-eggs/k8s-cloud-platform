package com.coding.platformapi.services;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.data.mapper.system.PlatformAppServiceMapper;
import com.coding.data.mapper.system.PlatformApplicationMapper;
import com.coding.data.models.system.PlatformAppService;
import com.coding.data.models.system.PlatformApplication;
import com.coding.platformapi.app.AppCanary;
import com.coding.platformapi.app.AppObjectNames;
import com.coding.platformapi.app.AppPending;
import com.coding.platformapi.app.AppServiceDesired;
import com.coding.platformapi.app.AppSnapshot;
import com.coding.platformapi.k8s.K8sServerGateway;
import com.coding.platformapi.metrics.MetricsService;
import com.coding.platformapi.models.AppCanaryMetricsView;
import com.coding.platformapi.models.AppCanaryRequest;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * 入口灰度的四个操作（设计 {@code 2026-11-09-app-canary-release-design.md} §5 / §6）。
 *
 * <p>每个操作都是一条普通发布：以线上快照为底，只改目标服务的 {@code canary} 块（或把它并回 stable），
 * 走与普通发布相同的执行路径。线上快照里某服务带 canary 块 = 正在灰度；live_release_no 只在执行成功时切换，
 * 所以失败的操作不会改变「是否在灰度」。
 */
@Service
public class AppCanaryService {

    public static final String TYPE_START = "CANARY_START";
    public static final String TYPE_UPDATE = "CANARY_UPDATE";
    public static final String TYPE_PROMOTE = "CANARY_PROMOTE";
    public static final String TYPE_ABORT = "CANARY_ABORT";

    private static final ObjectMapper JSON = JsonMapper.builder().build();
    /** HTTP header 名：token 字符的保守子集 */
    private static final Pattern HEADER_NAME = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9-]{0,63}$");
    private static final int HEADER_VALUE_MAX = 256;

    private final PlatformApplicationMapper apps;
    private final PlatformAppServiceMapper services;
    private final AppReleaseService releases;
    private final AppReleasePipeline pipeline;
    private final MetricsService metrics;

    public AppCanaryService(PlatformApplicationMapper apps, PlatformAppServiceMapper services,
                            AppReleaseService releases, AppReleasePipeline pipeline, MetricsService metrics) {
        this.apps = apps;
        this.services = services;
        this.releases = releases;
        this.pipeline = pipeline;
        this.metrics = metrics;
    }

    /** 当前线上状态 + 凭据：四个操作的公共前置 */
    private record Ctx(PlatformApplication app, String authorization, int plannedLive, AppSnapshot live,
                       String key, Map<String, Object> liveService) {
    }

    private Ctx ctx(String appId, String serviceKey, String tenantId) {
        PlatformApplication app = AppGuard.require(apps, appId, tenantId);
        String authorization = K8sServerGateway.currentAuthorization();
        if (authorization == null || authorization.isBlank()) {
            throw error("当前请求没有可用的凭据");
        }
        releases.requireNoRunningExecution(app.getId());
        int plannedLive = app.getLiveReleaseNo() == null ? 0 : app.getLiveReleaseNo();
        AppSnapshot live = releases.liveSnapshot(app);
        Map<String, Object> liveService = serviceKey == null ? null : live.services().get(serviceKey);
        if (liveService == null) {
            throw error("服务 " + serviceKey + " 未上线，不能灰度");
        }
        return new Ctx(app, authorization, plannedLive, live, serviceKey, liveService);
    }

    // ------------------------------------------------------------------ 开始

    public Long start(AppCanaryRequest req, String operator, String tenantId) {
        Ctx c = ctx(req.getAppId(), req.getServiceKey(), tenantId);
        if (AppCanary.of(c.liveService) != null) {
            throw error("服务 " + c.key + " 已在灰度中");
        }
        Map<String, Object> stable = c.liveService;
        requireEligible(c.key, stable);
        Map<String, Object> desired = req.getDesired() == null ? new LinkedHashMap<>(stable)
                : AppServiceDesired.normalize(req.getDesired());
        boolean http = "HTTP".equals(AppServiceDesired.gatewayProtocol(stable));
        AppCanary.Header header = null;
        boolean headerOn = req.getHeaderEnabled() == null ? http : req.getHeaderEnabled();
        if (headerOn) {
            header = new AppCanary.Header(
                    blankOr(req.getHeaderName(), AppCanary.DEFAULT_HEADER_NAME),
                    req.getHeaderValue() == null ? AppCanary.DEFAULT_HEADER_VALUE : req.getHeaderValue());
        }
        AppCanary.Spec spec = new AppCanary.Spec(desired,
                req.getReplicas() == null ? AppCanary.DEFAULT_REPLICAS : req.getReplicas(),
                req.getWeight() == null ? AppCanary.DEFAULT_WEIGHT : req.getWeight(), header);
        validate(c.key, stable, spec);
        return launch(c, AppCanary.with(stable, spec), TYPE_START,
                noteOr(req.getNote(), "灰度 " + c.key + "：权重 " + spec.weight() + "%"), operator, () -> { });
    }

    // ------------------------------------------------------------------ 调整

    public Long update(AppCanaryRequest req, String operator, String tenantId) {
        Ctx c = ctx(req.getAppId(), req.getServiceKey(), tenantId);
        AppCanary.Spec cur = requireInCanary(c);
        Map<String, Object> stable = AppCanary.without(c.liveService);
        Map<String, Object> desired = req.getDesired() == null ? cur.desired()
                : AppServiceDesired.normalize(req.getDesired());
        AppCanary.Header header = cur.header();
        if (Boolean.FALSE.equals(req.getHeaderEnabled())) {
            header = null;
        } else if (Boolean.TRUE.equals(req.getHeaderEnabled()) || req.getHeaderName() != null
                || req.getHeaderValue() != null) {
            String name = req.getHeaderName() != null ? req.getHeaderName()
                    : header != null ? header.name() : AppCanary.DEFAULT_HEADER_NAME;
            String value = req.getHeaderValue() != null ? req.getHeaderValue()
                    : header != null ? header.value() : AppCanary.DEFAULT_HEADER_VALUE;
            header = new AppCanary.Header(blankOr(name, AppCanary.DEFAULT_HEADER_NAME), value);
        }
        AppCanary.Spec spec = new AppCanary.Spec(desired,
                req.getReplicas() == null ? cur.replicas() : req.getReplicas(),
                req.getWeight() == null ? cur.weight() : req.getWeight(), header);
        validate(c.key, stable, spec);
        return launch(c, AppCanary.with(stable, spec), TYPE_UPDATE,
                noteOr(req.getNote(), "调整灰度 " + c.key + "：权重 " + spec.weight() + "%"), operator, () -> { });
    }

    // ------------------------------------------------------------------ 全量

    /** 灰度版本成为新的 stable：快照里服务 = canary desired（去掉灰度块），台账写成 canary desired */
    public Long promote(String appId, String serviceKey, String note, String operator, String tenantId) {
        Ctx c = ctx(appId, serviceKey, tenantId);
        AppCanary.Spec cur = requireInCanary(c);
        Map<String, Object> next = AppCanary.without(cur.desired());
        PlatformAppService row = requireRow(c);
        return launch(c, next, TYPE_PROMOTE, noteOr(note, "全量 " + c.key), operator,
                () -> writeRow(row, next));
    }

    // ------------------------------------------------------------------ 回退

    /**
     * 回到灰度开始前：快照里服务 = 线上 stable（去掉灰度块）；台账若已被改成别的（例如全量失败后台账已是 canary 配置）
     * 就写回 stable，回退后不残留待发布改动。
     */
    public Long abort(String appId, String serviceKey, String note, String operator, String tenantId) {
        Ctx c = ctx(appId, serviceKey, tenantId);
        requireInCanary(c);
        Map<String, Object> stable = AppCanary.without(c.liveService);
        PlatformAppService row = services.selectByKey(c.app.getId(), c.key);
        boolean rewrite = row != null && !AppPending.changedFields(parse(row.getDesiredJson()), stable).isEmpty();
        return launch(c, stable, TYPE_ABORT, noteOr(note, "回退灰度 " + c.key), operator, () -> {
            if (rewrite) {
                writeRow(row, stable);
            }
        });
    }

    // ------------------------------------------------------------------ 指标

    private static final List<String> WINDOWS = List.of("5m", "15m", "1h");
    private static final String DEFAULT_WINDOW = "15m";

    /** 两个版本的网关侧指标；只读，不需要凭据与执行锁。时间窗只收 5m / 15m / 1h，其他按 15m。 */
    public AppCanaryMetricsView metrics(String appId, String serviceKey, String window, String tenantId) {
        PlatformApplication app = AppGuard.require(apps, appId, tenantId);
        Map<String, Object> liveService = releases.liveSnapshot(app).services().get(serviceKey);
        if (AppCanary.of(liveService) == null) {
            throw error("服务 " + serviceKey + " 不在灰度中");
        }
        String w = WINDOWS.contains(window) ? window : DEFAULT_WINDOW;
        String protocol = AppServiceDesired.gatewayProtocol(liveService);
        Map<String, Map<String, Double>> m = metrics.canaryCompare(app.getClusterId(), app.getNamespace(),
                AppObjectNames.objectName(app.getSlug(), serviceKey),
                AppObjectNames.objectName(app.getSlug(), AppCanary.nodeKey(serviceKey)), w, "TCP".equals(protocol));
        return new AppCanaryMetricsView(w, protocol, m.getOrDefault("stable", Map.of()),
                m.getOrDefault("canary", Map.of()));
    }

    // ------------------------------------------------------------------ 内部

    private Long launch(Ctx c, Map<String, Object> service, String type, String note, String operator,
                        Runnable writes) {
        Map<String, Map<String, Object>> services = new TreeMap<>(c.live.services());
        services.put(c.key, service);
        AppSnapshot next = new AppSnapshot(services, c.live.dependencies(), c.live.app());
        List<String> problems = pipeline.problems(c.app, next);
        if (!problems.isEmpty()) {
            throw error(String.join("；", problems));
        }
        return releases.launch(c.app, c.authorization, c.plannedLive, next.toJson(), type, null, note, operator,
                writes);
    }

    /** 能灰度的服务：网关暴露（HTTP / TCP）+ 无状态（与 chart 选 StatefulSet 的判据一致：persistenceEnabled） */
    private static void requireEligible(String key, Map<String, Object> stable) {
        if (AppServiceDesired.gatewayProtocol(stable) == null) {
            throw error("服务 " + key + " 不是网关暴露（网关 HTTP / 网关 TCP），不能灰度");
        }
        if (stateful(stable)) {
            throw error("服务 " + key + " 是有状态服务（开启了持久化），不能灰度");
        }
    }

    private static void validate(String key, Map<String, Object> stable, AppCanary.Spec spec) {
        Map<String, Object> d = spec.desired();
        if (!Objects.equals(AppServiceDesired.port(d), AppServiceDesired.port(stable))) {
            throw error("灰度版本的端口必须与线上一致");
        }
        if (!Objects.equals(d.get("expose"), stable.get("expose"))) {
            throw error("灰度版本的暴露方式必须与线上一致");
        }
        if (!AppServiceDesired.kindOf(d).equals(AppServiceDesired.kindOf(stable)) || stateful(d)) {
            throw error("灰度版本的服务类型必须与线上一致（无状态服务）");
        }
        if (spec.weight() < 0 || spec.weight() > 100) {
            throw error("灰度权重须为 0-100 的整数");
        }
        if (spec.replicas() < 1 || spec.replicas() > 100) {
            throw error("灰度副本数须为 1-100");
        }
        if (spec.header() != null) {
            if (!"HTTP".equals(AppServiceDesired.gatewayProtocol(stable))) {
                throw error("服务 " + key + " 是网关 TCP 暴露，TCP 不支持 header 直达");
            }
            if (!HEADER_NAME.matcher(spec.header().name()).matches()) {
                throw error("header 名只能含字母、数字、连字符（至多 64 位）");
            }
            if (spec.header().value().length() > HEADER_VALUE_MAX) {
                throw error("header 值过长（至多 " + HEADER_VALUE_MAX + " 字符）");
            }
        }
    }

    private static boolean stateful(Map<String, Object> desired) {
        return Boolean.TRUE.equals(desired.get("persistenceEnabled"));
    }

    private static AppCanary.Spec requireInCanary(Ctx c) {
        AppCanary.Spec spec = AppCanary.of(c.liveService);
        if (spec == null) {
            throw error("服务 " + c.key + " 不在灰度中");
        }
        return spec;
    }

    private PlatformAppService requireRow(Ctx c) {
        PlatformAppService row = services.selectByKey(c.app.getId(), c.key);
        if (row == null) {
            throw error("服务 " + c.key + " 的配置不存在");
        }
        return row;
    }

    private void writeRow(PlatformAppService row, Map<String, Object> desired) {
        row.setDesiredJson(JSON.writeValueAsString(desired));
        if (services.updateWithVersion(row) == 0) {
            throw error("配置冲突：服务 " + row.getServiceKey() + " 已被他人修改，请刷新后重试");
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parse(String json) {
        return json == null || json.isBlank() ? Map.of() : JSON.readValue(json, Map.class);
    }

    private static String blankOr(String s, String dflt) {
        return s == null || s.isBlank() ? dflt : s.trim();
    }

    private static String noteOr(String note, String dflt) {
        return note == null || note.isBlank() ? dflt : note;
    }

    private static CloudPlatformException error(String message) {
        return new CloudPlatformException(EnumResponseType.ERROR, message);
    }
}
