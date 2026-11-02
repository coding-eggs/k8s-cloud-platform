package com.coding.platformapi.services;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.app.AppInitRequestDTO;
import com.coding.common.models.app.AppInitResultDTO;
import com.coding.common.models.middleware.MiddlewareConnectionDTO;
import com.coding.common.models.middleware.MiddlewareConnectionDTO.ConnInfo;
import com.coding.data.mapper.system.PlatformAppDependencyMapper;
import com.coding.data.mapper.system.PlatformAppExecutionMapper;
import com.coding.data.mapper.system.PlatformAppReleaseMapper;
import com.coding.data.mapper.system.PlatformAppServiceMapper;
import com.coding.data.mapper.system.PlatformApplicationMapper;
import com.coding.data.mapper.system.PlatformApplicationReferenceMapper;
import com.coding.data.mapper.system.PlatformMiddlewareInstanceMapper;
import com.coding.data.models.system.PlatformAppDependency;
import com.coding.data.models.system.PlatformAppExecution;
import com.coding.data.models.system.PlatformAppRelease;
import com.coding.data.models.system.PlatformApplication;
import com.coding.data.models.system.PlatformApplicationReference;
import com.coding.data.models.system.PlatformMiddlewareInstance;
import com.coding.platformapi.app.AppInitSupport;
import com.coding.platformapi.app.AppServiceDesired;
import com.coding.platformapi.app.AppSnapshot;
import com.coding.platformapi.k8s.K8sAppClient;
import com.coding.platformapi.k8s.K8sServerGateway;
import com.coding.platformapi.models.AppDependencyRequest;
import com.coding.platformapi.models.AppOverviewView;
import com.coding.platformapi.models.MiddlewareCreateRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Executor;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * 应用依赖：只读视图（连接信息、就绪状态）+ 生命周期（添加 / 移除 / 初始化）。
 *
 * <p>连接信息只从 {@code MiddlewareService.connection().getInfo()} 取，不在别处拼。
 * 发布流程从不创建实例、不跑初始化：这两件事只在这里、由用户显式触发。
 */
@Slf4j
@Service
public class AppDependencyService {

    static final String MIDDLEWARE = "MIDDLEWARE";
    static final String EXTERNAL = "EXTERNAL";

    private static final Pattern KEY = Pattern.compile("^[a-z]([a-z0-9-]{0,18}[a-z0-9])?$");
    private static final Pattern HOST = Pattern.compile(
            "^(?=.{1,253}$)[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?(\\.[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?)*$");
    private static final Pattern INDEX_PATH = Pattern.compile("^/[A-Za-z0-9._~:,*+@=%/-]*$");
    private static final List<String> RESERVED_SUFFIXES = List.of("-env", "-secret", "-headless");
    private static final Set<String> PROTOCOLS = Set.of(
            "mysql", "postgresql", "redis", "kafka", "rabbitmq", "elasticsearch", "nacos", "milvus");
    private static final Set<String> EXTERNAL_FIELDS = Set.of(
            "protocol", "host", "port", "secretName", "usernameKey", "passwordKey");
    /** 占位行创建后多久内算「创建请求可能还在飞」 */
    private static final long CREATE_IN_FLIGHT_MILLIS = 5 * 60 * 1000L;
    private static final int LOG_MAX = 20_000;
    private static final int LOG_VIEW = 4_000;
    /** 初始化最长等 10 分钟：超过这个时长还是 RUNNING 的一定是丢了 worker。 */
    private static final long STALE_MILLIS = 15 * 60 * 1000L;

    private final PlatformApplicationMapper apps;
    private final PlatformAppServiceMapper services;
    private final PlatformAppDependencyMapper dependencies;
    private final PlatformAppReleaseMapper releases;
    private final PlatformAppExecutionMapper executions;
    private final PlatformMiddlewareInstanceMapper instances;
    private final PlatformApplicationReferenceMapper references;
    private final MiddlewareService middleware;
    private final K8sAppClient client;
    private final Executor initExecutor;
    /** 自己建的线程池（测试注入的 Executor 不归我们关） */
    private final ExecutorService ownedPool;

    @Autowired
    public AppDependencyService(PlatformApplicationMapper apps, PlatformAppServiceMapper services,
                                PlatformAppDependencyMapper dependencies, PlatformAppReleaseMapper releases,
                                PlatformAppExecutionMapper executions, PlatformMiddlewareInstanceMapper instances,
                                PlatformApplicationReferenceMapper references, MiddlewareService middleware,
                                K8sAppClient client) {
        this(apps, services, dependencies, releases, executions, instances, references, middleware, client,
                newInitPool());
    }

    /** 全局并发上限 4、排队 16，再多直接拒绝：每个初始化最长占一个线程 10 分钟。 */
    private static ThreadPoolExecutor newInitPool() {
        java.util.concurrent.atomic.AtomicInteger n = new java.util.concurrent.atomic.AtomicInteger();
        return new ThreadPoolExecutor(4, 4, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(16), r -> {
            Thread t = new Thread(r, "app-dependency-init-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
    }

    @jakarta.annotation.PreDestroy
    void shutdown() {
        if (ownedPool != null) {
            // 正在跑的初始化会被打断；遗留的 RUNNING 由下次启动的 recoverInterrupted 置失败
            ownedPool.shutdownNow();
        }
    }

    AppDependencyService(PlatformApplicationMapper apps, PlatformAppServiceMapper services,
                         PlatformAppDependencyMapper dependencies, PlatformAppReleaseMapper releases,
                         PlatformAppExecutionMapper executions, PlatformMiddlewareInstanceMapper instances,
                         PlatformApplicationReferenceMapper references, MiddlewareService middleware,
                         K8sAppClient client, Executor initExecutor) {
        this.apps = apps;
        this.services = services;
        this.dependencies = dependencies;
        this.releases = releases;
        this.executions = executions;
        this.instances = instances;
        this.references = references;
        this.middleware = middleware;
        this.client = client;
        this.initExecutor = initExecutor;
        this.ownedPool = initExecutor instanceof ExecutorService es ? es : null;
    }

    public List<PlatformAppDependency> list(String appId) {
        return dependencies.selectByApp(appId);
    }

    public Map<String, ConnInfo> connections(String appId, String tenantId) {
        return connectionsOf(list(appId), tenantId);
    }

    /** 任何一个依赖取不到连接信息都抛错并点名：静默跳过会让引用变成「不存在」这种误导的报错。 */
    public Map<String, ConnInfo> connectionsOf(List<PlatformAppDependency> deps, String tenantId) {
        Map<String, ConnInfo> out = new LinkedHashMap<>();
        for (PlatformAppDependency dep : deps) {
            ConnInfo info;
            try {
                info = EXTERNAL.equals(dep.getType()) ? externalInfo(dep) : middlewareInfo(dep, tenantId);
            } catch (CloudPlatformException e) {
                throw e;
            } catch (RuntimeException e) {
                throw new CloudPlatformException(EnumResponseType.ERROR,
                        "依赖 " + dep.getDepKey() + " 的连接信息不可用：" + e.getMessage());
            }
            if (info != null) {
                out.put(dep.getDepKey(), info);
            }
        }
        return out;
    }

    /** 依赖 key → 状态（READY / 中间件状态 / EXTERNAL / UNKNOWN）。 */
    public Map<String, String> statusOf(List<PlatformAppDependency> deps) {
        Map<String, String> out = new LinkedHashMap<>();
        for (PlatformAppDependency dep : deps) {
            out.put(dep.getDepKey(), statusOf(dep));
        }
        return out;
    }

    /** 未就绪的依赖，每条一句话且含依赖名；空列表 = 全部就绪。EXTERNAL 不探测，视为就绪。 */
    public List<String> notReady(List<PlatformAppDependency> deps) {
        List<String> out = new ArrayList<>();
        for (PlatformAppDependency dep : deps) {
            if (EXTERNAL.equals(dep.getType())) {
                continue;
            }
            String status = statusOf(dep);
            if (!"READY".equals(status)) {
                out.add("依赖 " + dep.getDepKey() + " 未就绪（当前状态 " + status + "）");
            }
        }
        return out;
    }

    private String statusOf(PlatformAppDependency dep) {
        if (EXTERNAL.equals(dep.getType())) {
            return "EXTERNAL";
        }
        if (dep.getInstanceId() == null || dep.getInstanceId().isBlank()) {
            return "NO_INSTANCE";
        }
        try {
            String s = middleware.statusOf(dep.getInstanceId());
            return s == null ? "UNKNOWN" : s;
        } catch (RuntimeException e) {
            return "UNKNOWN";
        }
    }

    private ConnInfo middlewareInfo(PlatformAppDependency dep, String tenantId) {
        if (dep.getInstanceId() == null || dep.getInstanceId().isBlank()) {
            throw new CloudPlatformException(EnumResponseType.ERROR,
                    "依赖 " + dep.getDepKey() + " 还没有关联的中间件实例");
        }
        MiddlewareConnectionDTO dto = middleware.connection(dep.getInstanceId(), tenantId);
        return dto == null ? null : dto.getInfo();
    }

    private ConnInfo externalInfo(PlatformAppDependency dep) {
        if (dep.getExternalJson() == null || dep.getExternalJson().isBlank()) {
            return null;
        }
        JsonNode n = AppSnapshot.JSON.readTree(dep.getExternalJson());
        ConnInfo info = new ConnInfo();
        info.setProtocol(text(n, "protocol"));
        info.setHost(text(n, "host"));
        info.setPort(n.hasNonNull("port") ? n.get("port").asInt() : null);
        info.setSecretName(text(n, "secretName"));
        info.setUsernameKey(text(n, "usernameKey"));
        info.setPasswordKey(text(n, "passwordKey"));
        return info;
    }

    private static String text(JsonNode n, String field) {
        return n.hasNonNull(field) && !n.get(field).asString().isBlank() ? n.get(field).asString() : null;
    }

    // ================================================================== 添加

    public AppOverviewView.DependencyRow add(AppDependencyRequest req, String operator, String tenantId) {
        PlatformApplication app = AppGuard.require(apps, req.getAppId(), tenantId);
        String key = req.getKey();
        assertValidKey(key);
        assertKeyFree(app, key);
        if (EXTERNAL.equals(req.getType())) {
            return addExternal(app, key, req);
        }
        if (!MIDDLEWARE.equals(req.getType())) {
            throw error("依赖类型只能是 MIDDLEWARE 或 EXTERNAL");
        }
        if ("BIND".equals(req.getSource())) {
            return addBound(app, key, req);
        }
        if ("CREATE".equals(req.getSource())) {
            return addCreated(app, key, req, operator);
        }
        throw error("中间件依赖的来源只能是 CREATE 或 BIND");
    }

    private AppOverviewView.DependencyRow addExternal(PlatformApplication app, String key, AppDependencyRequest req) {
        Map<String, Object> external = validateExternal(req.getExternal());
        PlatformAppDependency row = newRow(app, key, EXTERNAL, null);
        row.setExternalJson(AppSnapshot.JSON.writeValueAsString(external));
        insertOrConflict(row, key);
        return view(row);
    }

    private AppOverviewView.DependencyRow addBound(PlatformApplication app, String key, AppDependencyRequest req) {
        String instanceId = req.getInstanceId();
        PlatformMiddlewareInstance inst = instanceId == null || instanceId.isBlank()
                ? null : instances.selectByPrimaryKey(instanceId);
        if (inst == null || inst.getDeletedAt() != null) {
            throw error("要绑定的中间件实例不存在");
        }
        if (!app.getTenantId().equals(inst.getTenantId())) {
            throw error("不能绑定别的租户的实例");
        }
        if (!app.getClusterId().equals(inst.getClusterId()) || !app.getNamespace().equals(inst.getNamespace())) {
            throw error("只能绑定同一集群同一命名空间里的实例：" + inst.getNamespace());
        }
        for (PlatformAppDependency other : dependencies.selectByApp(app.getId())) {
            if (instanceId.equals(other.getInstanceId())) {
                throw error("该实例已经是依赖 " + other.getDepKey());
            }
        }
        PlatformAppDependency row = newRow(app, key, MIDDLEWARE, "BOUND");
        row.setInstanceId(instanceId);
        insertOrConflict(row, key);
        try {
            addReference(app.getId(), key, instanceId);
        } catch (RuntimeException e) {
            dependencies.deleteById(row.getId());
            throw e;
        }
        return view(row);
    }

    /**
     * 先写占位行占住 key（唯一键挡并发重复），再建实例：重复添加只会有一个走到 create。
     * create 失败就撤掉占位行，用户可以直接重试。
     */
    private AppOverviewView.DependencyRow addCreated(PlatformApplication app, String key, AppDependencyRequest req,
                                                     String operator) {
        if (req.getCode() == null || req.getCode().isBlank()) {
            throw error("请选择中间件类型");
        }
        PlatformAppDependency row = newRow(app, key, MIDDLEWARE, "CREATED");
        insertOrConflict(row, key);
        try {
            MiddlewareCreateRequest create = new MiddlewareCreateRequest();
            create.setClusterId(app.getClusterId());
            create.setNamespace(app.getNamespace());
            create.setCode(req.getCode());
            create.setMode(req.getShape() == null || req.getShape().isBlank()
                    ? middleware.defaultMode(req.getCode()) : req.getShape());
            create.setVersion(req.getVersion());
            create.setReplicas(req.getReplicas());
            create.setDisplayName("应用 " + app.getSlug() + " · " + key);
            create.setCpuRequest(req.getCpuRequest());
            create.setCpuLimit(req.getCpuLimit());
            create.setMemoryRequest(req.getMemoryRequest());
            create.setMemoryLimit(req.getMemoryLimit());
            create.setStorageSize(req.getStorageSize());
            create.setStorageClass(req.getStorageClass());
            create.setAppId(app.getId());
            create.setAppNodeId(key);
            middleware.create(create, operator, app.getTenantId());
        } catch (RuntimeException e) {
            removePlaceholder(row);
            throw e;
        }
        PlatformMiddlewareInstance created = createdInstanceOf(app.getId(), key);
        if (created == null) {
            // 不能让用户据此重新添加：再建一个就是第二个实例。移除依赖时会按「应用+依赖名」找回这个实例。
            throw error("实例创建请求已提交，但暂时没有查到台账记录。依赖 " + key
                    + " 保持占位，请稍后刷新；不要重新添加同名依赖，需要放弃时直接移除它（会一并处理已创建的实例）");
        }
        row.setInstanceId(created.getId());
        row.setUpdatedAt(new Date());
        if (dependencies.updateById(row) == 0) {
            // 占位行在创建期间被移除：实例已经建出来了，转交中间件模块，别留一个带应用来源却删不掉的实例
            instances.clearApp(created.getId());
            throw error("依赖 " + key + " 在创建期间被移除；已创建的实例 " + created.getId()
                    + " 已转交中间件模块管理，可在实例页使用或删除");
        }
        addReference(app.getId(), key, created.getId());
        return view(row);
    }

    /** 按「应用 + 依赖名」找 CREATED 依赖建出的实例（add 收尾与 remove 兜底共用）。 */
    private PlatformMiddlewareInstance createdInstanceOf(String appId, String key) {
        return instances.selectLatestByAppNode(appId, key);
    }

    private void removePlaceholder(PlatformAppDependency row) {
        try {
            dependencies.deleteById(row.getId());
        } catch (RuntimeException cleanup) {
            log.warn("依赖占位行清理失败 app={} key={}", row.getAppId(), row.getDepKey(), cleanup);
        }
    }

    private void insertOrConflict(PlatformAppDependency row, String key) {
        try {
            dependencies.insert(row);
        } catch (DuplicateKeyException e) {
            throw error("依赖 " + key + " 已存在");
        }
    }

    private PlatformAppDependency newRow(PlatformApplication app, String key, String type, String ownership) {
        PlatformAppDependency row = new PlatformAppDependency();
        row.setAppId(app.getId());
        row.setDepKey(key);
        row.setType(type);
        row.setOwnership(ownership);
        row.setCreatedAt(new Date());
        row.setUpdatedAt(new Date());
        return row;
    }

    private void addReference(String appId, String key, String instanceId) {
        PlatformApplicationReference ref = new PlatformApplicationReference();
        ref.setAppId(appId);
        ref.setNodeId(key);
        ref.setRefType(ApplicationReferenceGuard.MIDDLEWARE_INSTANCE);
        ref.setRefId(instanceId);
        ref.setCreatedAt(new Date());
        references.insert(ref);
    }

    /** key 与服务共用一个命名空间：待删除的服务行、线上或进行中快照里的服务都算占用。 */
    private void assertKeyFree(PlatformApplication app, String key) {
        if (dependencies.selectByKey(app.getId(), key) != null) {
            throw error("依赖 " + key + " 已存在");
        }
        if (services.selectByKey(app.getId(), key) != null) {
            throw error("名称 " + key + " 已被服务占用");
        }
        for (AppSnapshot snap : snapshotsOf(app)) {
            if (snap.services().containsKey(key)) {
                throw error("名称 " + key + " 已被服务占用（线上或进行中的发布里还有同名服务）");
            }
        }
    }

    private static void assertValidKey(String key) {
        if (key == null || !KEY.matcher(key).matches()) {
            throw error("依赖名需为小写字母开头的 DNS 标签（小写字母、数字、连字符，至多 20 位）");
        }
        for (String suffix : RESERVED_SUFFIXES) {
            if (key.endsWith(suffix)) {
                throw error("依赖名不能以 " + suffix + " 结尾");
            }
        }
    }

    /** 白名单校验：多出来的字段一律拒绝（疑似口令的字段单独提示），报错里不回显字段值。 */
    static Map<String, Object> validateExternal(Map<String, Object> raw) {
        if (raw == null || raw.isEmpty()) {
            throw error("请填写外部连接信息");
        }
        for (String field : raw.keySet()) {
            if (EXTERNAL_FIELDS.contains(field)) {
                continue;
            }
            String lower = field.toLowerCase();
            if (lower.contains("pass") || lower.contains("pwd") || lower.contains("secret")
                    || lower.contains("token") || lower.contains("credential") || lower.contains("key")) {
                throw error("不接收明文口令或密钥（字段 " + field + "）；请把口令放进 Secret，这里只填 secretName 与键名");
            }
            throw error("不支持的字段：" + field);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        if (!(raw.get("protocol") instanceof String) || !(raw.get("host") instanceof String)) {
            throw error("protocol 与 host 需为字符串");
        }
        String protocol = str(raw.get("protocol"));
        if (!PROTOCOLS.contains(protocol)) {
            throw error("protocol 只能是 " + String.join(" / ", new TreeSet<>(PROTOCOLS)));
        }
        out.put("protocol", protocol);
        String host = str(raw.get("host")).trim();
        if (host.isBlank()) {
            throw error("请填写外部地址 host");
        }
        if (!HOST.matcher(host).matches()) {
            throw error("host 只能是主机名或 IPv4 地址，不要带协议、端口、路径或 user:pass@");
        }
        out.put("host", host);
        if (raw.get("port") != null) {
            if (!(raw.get("port") instanceof Number n) || n.intValue() < 1 || n.intValue() > 65535
                    || n.doubleValue() != n.intValue()) {
                throw error("port 需为 1-65535 的整数");
            }
            out.put("port", n.intValue());
        }
        for (String f : List.of("secretName", "usernameKey", "passwordKey")) {
            Object v = raw.get(f);
            if (v == null || str(v).isBlank()) {
                continue;
            }
            if (!(v instanceof String)) {
                throw error(f + " 需为字符串");
            }
            out.put(f, str(v));
        }
        if ((out.containsKey("passwordKey") || out.containsKey("usernameKey")) && !out.containsKey("secretName")) {
            throw error("填写了键名就必须同时填写 secretName");
        }
        return out;
    }

    // ================================================================== 移除

    public void remove(AppDependencyRequest req, String operator, String tenantId) {
        PlatformApplication app = AppGuard.require(apps, req.getAppId(), tenantId);
        PlatformAppDependency row = req.getKey() == null ? null : dependencies.selectByKey(app.getId(), req.getKey());
        if (row == null) {
            throw error("依赖不存在：" + req.getKey());
        }
        String key = row.getDepKey();
        if ("RUNNING".equals(row.getInitStatus())) {
            throw error("依赖 " + key + " 正在初始化，请等它结束再移除");
        }
        String instanceId = row.getInstanceId();
        if (MIDDLEWARE.equals(row.getType()) && "CREATED".equals(row.getOwnership()) && instanceId == null) {
            // 没写 instance_id 有两种可能：创建请求还在飞，或者创建成功但回填失败。
            // 以占位行的创建时间判断：窗口内当作在飞（中间件创建是同步调用，几十秒内结束），拒绝；
            // 窗口外按「应用+依赖名」找回实例，当作已关联处理。
            if (row.getCreatedAt() != null
                    && System.currentTimeMillis() - row.getCreatedAt().getTime() < CREATE_IN_FLIGHT_MILLIS) {
                throw error("依赖 " + key + " 正在创建中（创建请求发出不足 5 分钟、尚未关联实例），请稍后再移除");
            }
            PlatformMiddlewareInstance found = createdInstanceOf(app.getId(), key);
            if (found != null) {
                instanceId = found.getId();
            }
        }
        TreeSet<String> users = new TreeSet<>();
        for (var svc : services.selectByApp(app.getId())) {
            if (!Boolean.TRUE.equals(svc.getPendingDelete())
                    && AppServiceDesired.references(AppSnapshot.parseMap(svc.getDesiredJson())).contains(key)) {
                users.add(svc.getServiceKey());
            }
        }
        for (AppSnapshot snap : snapshotsOf(app)) {
            snap.services().forEach((name, desired) -> {
                if (AppServiceDesired.references(desired).contains(key)) {
                    users.add(name);
                }
            });
        }
        if (!users.isEmpty()) {
            throw error("依赖 " + key + " 仍被服务 " + String.join("、", users)
                    + " 引用（期望配置、线上或进行中的发布），请先去掉引用并发布");
        }
        if (MIDDLEWARE.equals(row.getType()) && instanceId != null) {
            if ("CREATED".equals(row.getOwnership())) {
                PlatformMiddlewareInstance inst = instances.selectByPrimaryKey(instanceId);
                boolean alreadyGone = inst == null || inst.getDeletedAt() != null;
                if (alreadyGone) {
                    // 实例已经被删（中间件页或上一次移除只做了一半）：目标状态已达成，继续清依赖行
                    log.info("依赖 {} 的实例 {} 已不存在，跳过删除", key, instanceId);
                } else if (Boolean.TRUE.equals(req.getDeleteInstance())) {
                    middleware.deleteForApp(instanceId, app.getId(), operator, app.getTenantId());
                } else {
                    // 先转交再删行：反过来的话转交失败会留下一个谁也删不掉的实例
                    instances.clearApp(instanceId);
                }
            }
            references.deleteByAppAndRef(app.getId(), ApplicationReferenceGuard.MIDDLEWARE_INSTANCE, instanceId);
        }
        dependencies.deleteById(row.getId());
    }

    /** 删除应用时：CREATED 实例保留并转交中间件模块（不静默删数据），清引用行与依赖行。 */
    public void detachForAppDelete(String appId) {
        // 按 app_id 一条语句清：旧画布建的、没有依赖行的实例，和占位后没回填 instance_id 的实例也一并转交
        instances.clearByApp(appId);
        for (PlatformAppDependency dep : dependencies.selectByApp(appId)) {
            dependencies.deleteById(dep.getId());
        }
        references.deleteByApp(appId);
    }

    private List<AppSnapshot> snapshotsOf(PlatformApplication app) {
        List<AppSnapshot> out = new ArrayList<>();
        addSnapshot(out, app.getId(), app.getLiveReleaseNo());
        PlatformAppExecution active = executions.selectActiveByApp(app.getId());
        if (active != null) {
            addSnapshot(out, app.getId(), active.getReleaseNo());
        }
        return out;
    }

    private void addSnapshot(List<AppSnapshot> out, String appId, Integer releaseNo) {
        if (releaseNo == null || releaseNo <= 0) {
            return;
        }
        PlatformAppRelease release = releases.selectByNo(appId, releaseNo);
        if (release != null) {
            out.add(AppSnapshot.parse(release.getSnapshotJson()));
        }
    }

    // ================================================================== 初始化

    /**
     * 手动触发初始化：同步校验并置 RUNNING，真正执行交给独立线程（最长等 10 分钟）。
     * 发起人的 Authorization 只放在局部变量里，由线程闭包持有，不落库。
     */
    public void init(AppDependencyRequest req, String operator, String tenantId) {
        PlatformApplication app = AppGuard.require(apps, req.getAppId(), tenantId);
        PlatformAppDependency row = req.getKey() == null ? null : dependencies.selectByKey(app.getId(), req.getKey());
        if (row == null) {
            throw error("依赖不存在：" + req.getKey());
        }
        if (!MIDDLEWARE.equals(row.getType()) || row.getInstanceId() == null) {
            throw error("只有关联了中间件实例的依赖可以初始化");
        }
        PlatformMiddlewareInstance inst = middleware.get(row.getInstanceId(), app.getTenantId());
        if (!AppInitSupport.supports(inst.getCode())) {
            throw error("该中间件不支持初始化脚本：" + inst.getCode());
        }
        String payload = req.getInitPayload();
        if (payload == null || payload.isBlank()) {
            throw error("请填写初始化内容");
        }
        String status = middleware.statusOf(row.getInstanceId());
        if (!"READY".equals(status)) {
            throw error("实例未就绪（当前状态 " + status + "），就绪后才能初始化");
        }
        if ((row.getInitHash() != null || "SUCCESS".equals(row.getInitStatus()))
                && !Boolean.TRUE.equals(req.getConfirm())) {
            throw error("依赖 " + row.getDepKey() + " 已成功初始化过，再次执行需要确认（confirm=true）");
        }
        String authorization = K8sServerGateway.currentAuthorization();
        if (authorization == null || authorization.isBlank()) {
            throw error("缺少登录凭据，无法发起初始化");
        }
        ConnInfo info = middleware.connection(row.getInstanceId(), app.getTenantId()).getInfo();
        String indexPath = req.getInitPath() == null ? "" : req.getInitPath().trim();
        if ("elasticsearch".equals(inst.getCode()) && !INDEX_PATH.matcher(indexPath).matches()) {
            throw error("Elasticsearch 初始化需要填写目标路径（以 / 开头，例如 /my-index 或 /_index_template/x）");
        }
        AppInitRequestDTO dto = buildInitRequest(app, row, inst.getCode(), payload, info, indexPath);
        String hash = sha256(payload);
        if (dependencies.startInit(row.getId(), new Date()) == 0) {
            throw error("依赖 " + row.getDepKey() + " 正在初始化，请等它结束");
        }
        try {
            initExecutor.execute(() -> K8sServerGateway.runAs(authorization,
                    () -> runInit(row.getId(), dto, hash)));
        } catch (RejectedExecutionException e) {
            dependencies.finishInit(row.getId(), "FAILED", null, "初始化任务过多，未能提交", new Date());
            throw error("当前初始化任务过多，请稍后再试");
        } catch (RuntimeException e) {
            dependencies.finishInit(row.getId(), "FAILED", null, "初始化任务提交失败：" + e.getMessage(), new Date());
            throw e;
        }
    }

    private void runInit(Long id, AppInitRequestDTO dto, String hash) {
        try {
            AppInitResultDTO result = client.init(dto);
            boolean ok = result != null && result.isOk();
            dependencies.finishInit(id, ok ? "SUCCESS" : "FAILED", ok ? hash : null,
                    cap(initLog(result)), new Date());
        } catch (RuntimeException e) {
            log.warn("依赖初始化失败 dep={}", id, e);
            dependencies.finishInit(id, "FAILED", null, cap("初始化失败：" + e.getMessage()), new Date());
        }
    }

    private static String initLog(AppInitResultDTO result) {
        if (result == null) {
            return "k8s-server 没有返回结果";
        }
        String message = result.getMessage() == null ? "" : result.getMessage();
        String tail = result.getLogTail() == null ? "" : result.getLogTail();
        return tail.isBlank() ? message : message + "\n" + tail;
    }

    private AppInitRequestDTO buildInitRequest(PlatformApplication app, PlatformAppDependency row, String code,
                                               String payload, ConnInfo info, String indexPath) {
        AppInitRequestDTO dto = new AppInitRequestDTO();
        dto.setClusterId(app.getClusterId());
        dto.setNamespace(app.getNamespace());
        dto.setJobName(jobName(app.getSlug(), row.getDepKey()));
        dto.setCode(code);
        dto.setPayload(payload);
        if (info != null) {
            dto.setSecretName(info.getSecretName());
            dto.setPasswordKey(info.getPasswordKey());
            Map<String, String> env = new LinkedHashMap<>();
            env.put("HOST", info.getHost());
            if (info.getPort() != null) {
                env.put("PORT", String.valueOf(info.getPort()));
                if ("kafka".equals(code)) {
                    env.put("BOOTSTRAP", info.getHost() + ":" + info.getPort());
                }
                if ("elasticsearch".equals(code)) {
                    // 协议取自连接信息（实例的 TLS 开关）；缺省按 ES 实例默认开 TLS
                    String scheme = info.getExtra() == null ? null : info.getExtra().get("scheme");
                    env.put("URL", ("http".equals(scheme) ? "http" : "https") + "://" + info.getHost() + ":"
                            + info.getPort());
                    env.put("INDEX_PATH", indexPath);
                }
            }
            dto.setEnv(env);
            if ("mysql".equals(code)) {
                dto.setUsername("root");
            } else if ("postgresql".equals(code)) {
                dto.setUsernameKey(info.getUsernameKey());
            }
        }
        return dto;
    }

    /** Job 名带时间戳避免重试撞名；整体压进 63 位。 */
    static String jobName(String slug, String key) {
        String suffix = "-" + key + "-init-" + Long.toString(System.currentTimeMillis(), 36);
        String base = slug == null ? "app" : slug;
        int room = 63 - suffix.length();
        if (base.length() > room) {
            base = base.substring(0, Math.max(room, 1));
        }
        return (base + suffix).replaceAll("-+$", "");
    }

    /** 重启后线程已死：把遗留的 RUNNING 置失败，用户可重新执行。 */
    @org.springframework.context.event.EventListener(
            org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void recoverInterrupted() {
        dependencies.failRunningInit("平台重启，初始化被中断，请重新执行", null, new Date());
    }

    /** 运行期兜底：超过 15 分钟仍是 RUNNING 的（worker 丢失）置失败。 */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelayString = "${platform.app.init.sweep-interval:5m}")
    public void sweepStaleInit() {
        dependencies.failRunningInit("初始化超时未结束，已置为失败，请重新执行",
                new Date(System.currentTimeMillis() - STALE_MILLIS), new Date());
    }

    // ================================================================== 小工具

    private AppOverviewView.DependencyRow view(PlatformAppDependency d) {
        return new AppOverviewView.DependencyRow(d.getDepKey(), d.getType(), d.getOwnership(), d.getInstanceId(),
                statusOf(d), d.getInitStatus(), tail(d.getInitLog()),
                AppSnapshot.scrubExternal(d.getExternalJson()), codeOf(d));
    }

    /** 中间件依赖的实例类型；EXTERNAL、无实例、实例已删或读不到都是 null（概览不能因此失败）。 */
    public String codeOf(PlatformAppDependency dep) {
        if (!MIDDLEWARE.equals(dep.getType()) || dep.getInstanceId() == null || dep.getInstanceId().isBlank()) {
            return null;
        }
        try {
            PlatformMiddlewareInstance inst = instances.selectByPrimaryKey(dep.getInstanceId());
            return inst == null || inst.getDeletedAt() != null ? null : inst.getCode();
        } catch (RuntimeException e) {
            return null;
        }
    }

    static String tail(String log) {
        if (log == null) {
            return null;
        }
        return log.length() <= LOG_VIEW ? log : log.substring(log.length() - LOG_VIEW);
    }

    private static String cap(String log) {
        return log.length() <= LOG_MAX ? log : log.substring(log.length() - LOG_MAX);
    }

    private static String sha256(String text) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static CloudPlatformException error(String message) {
        return new CloudPlatformException(EnumResponseType.ERROR, message);
    }
}
