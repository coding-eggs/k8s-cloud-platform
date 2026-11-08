package com.coding.platformapi.services;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.app.AppInjectDTO;
import com.coding.common.models.app.AppNodeDTO;
import com.coding.common.models.app.AppProblemDTO;
import com.coding.common.models.app.AppRenderRequestDTO;
import com.coding.common.models.app.AppSpecDTO;
import com.coding.common.models.middleware.MiddlewareConnectionDTO.ConnInfo;
import com.coding.data.mapper.system.PlatformAppReleaseMapper;
import com.coding.data.mapper.system.PlatformAppServiceMapper;
import com.coding.data.mapper.system.PlatformApplicationMapper;
import com.coding.data.models.system.PlatformAppDependency;
import com.coding.data.models.system.PlatformAppExecution;
import com.coding.data.models.system.PlatformAppRelease;
import com.coding.data.models.system.PlatformAppService;
import com.coding.data.models.system.PlatformApplication;
import com.coding.data.models.system.PlatformIngressPort;
import com.coding.platformapi.app.AppEnvResolver;
import com.coding.platformapi.app.AppEnvResolver.ServiceAddr;
import com.coding.platformapi.app.AppObjectNames;
import com.coding.platformapi.app.AppServiceDesired;
import com.coding.platformapi.app.AppServiceInjection;
import com.coding.platformapi.app.AppSnapshot;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 执行器的两个接缝：发布前校验（PRECHECK）与渲染请求构造（RENDER）。
 *
 * <p>引用在这里（RENDER 时）才解析：快照里存的是 {@code ${依赖.字段}} 原文，
 * 连接信息每次都从中间件现取，不会把地址或凭据位置固化进发布记录。
 */
@lombok.extern.slf4j.Slf4j
@Component
public class AppReleasePipeline implements AppReleasePrecheck, AppReleaseRenderer {

    private static final Set<String> NODE_EXCLUDED = Set.of("imageRepo", "imageTag", "env", "configFiles", "otel", "mesh");

    private final PlatformApplicationMapper apps;
    private final PlatformAppReleaseMapper releases;
    private final AppDependencyService dependencies;
    private final PlatformAppServiceMapper services;
    private final IngressPortService ports;
    private final IngressListenerSync listeners;

    public AppReleasePipeline(PlatformApplicationMapper apps, PlatformAppReleaseMapper releases,
                              AppDependencyService dependencies, PlatformAppServiceMapper services,
                              IngressPortService ports, IngressListenerSync listeners) {
        this.apps = apps;
        this.releases = releases;
        this.dependencies = dependencies;
        this.services = services;
        this.ports = ports;
        this.listeners = listeners;
    }

    @Override
    public void check(PlatformAppExecution execution) {
        PlatformApplication app = apps.selectById(execution.getAppId());
        PlatformAppRelease release = releases.selectByNo(execution.getAppId(), execution.getReleaseNo());
        if (app == null || release == null) {
            throw error("发布或应用不存在");
        }
        AppSnapshot snapshot = AppSnapshot.parse(release.getSnapshotJson());
        // 只看被引用的依赖：没人用的依赖还在创建中，不该挡住别的服务发布
        List<String> notReady = dependencies.notReady(referencedDependencies(snapshot));
        if (!notReady.isEmpty()) {
            throw error(String.join("；", notReady));
        }
        List<String> problems = problems(app, snapshot);
        if (!problems.isEmpty()) {
            throw error(String.join("；", problems));
        }
        // 在下发任何路由之前：端口到位、ListenerSet 同步好（缺 CRD / 没开端口池在这里就拦下）
        reconcileIngress(app, snapshot);
    }

    /**
     * 网关端口与 ListenerSet 对账（spec §5.3 / §6）：
     * <ul>
     *   <li>快照里网关方式的服务：确保有端口（下线后重新上线、回滚到旧版本时端口已释放 → 重新分配），协议以快照为准；</li>
     *   <li>快照里有网关方式的服务时，整份同步命名空间的 ListenerSet；</li>
     *   <li>改离网关方式的端口<b>不在这里放</b>，见 {@link #afterRelease}。</li>
     * </ul>
     */
    private void reconcileIngress(PlatformApplication app, AppSnapshot snapshot) {
        Map<String, String> wanted = new LinkedHashMap<>();
        snapshot.services().forEach((key, desired) -> {
            String protocol = AppServiceDesired.gatewayProtocol(desired);
            if (protocol != null) {
                wanted.put(key, protocol);
            }
        });
        if (!wanted.isEmpty()) {
            IngressPortService.PortRange pool = ports.currentPool(app.getClusterId());
            if (pool == null) {
                throw error("集群未启用应用端口池（Istio 组件编辑里配置），不能用网关方式暴露："
                        + String.join("、", wanted.keySet()));
            }
            wanted.forEach((key, protocol) ->
                    ports.allocate(app.getClusterId(), app.getNamespace(), app.getId(), key, protocol, pool));
        }
        if (!wanted.isEmpty()) {
            listeners.sync(app.getClusterId(), app.getNamespace());
        }
    }

    /**
     * 发布成功后：本应用持有、但快照<b>和</b>期望配置都不再是网关方式的端口，摘掉 listener 并释放（M6）。
     * 放在发布前会让「发布失败、线上仍是旧版本」时旧路由先失去 listener。失败只记日志、端口保留，下次成功发布再放。
     */
    @Override
    public void afterRelease(PlatformAppExecution execution) {
        PlatformApplication app = apps.selectById(execution.getAppId());
        PlatformAppRelease release = releases.selectByNo(execution.getAppId(), execution.getReleaseNo());
        if (app == null || release == null) {
            return;
        }
        AppSnapshot snapshot = AppSnapshot.parse(release.getSnapshotJson());
        Set<String> keep = new java.util.HashSet<>();
        snapshot.services().forEach((key, desired) -> {
            if (AppServiceDesired.gatewayProtocol(desired) != null) {
                keep.add(key);
            }
        });
        for (PlatformAppService row : services.selectByApp(app.getId())) {
            if (!Boolean.TRUE.equals(row.getPendingDelete())
                    && AppServiceDesired.gatewayProtocol(AppSnapshot.parseMap(row.getDesiredJson())) != null) {
                keep.add(row.getServiceKey());
            }
        }
        Set<String> leaving = new java.util.LinkedHashSet<>();
        List<PlatformIngressPort> held = ports.activeOfApp(app.getId());
        for (PlatformIngressPort p : held == null ? List.<PlatformIngressPort>of() : held) {
            if (!keep.contains(p.getServiceKey())) {
                leaving.add(p.getServiceKey());
            }
        }
        if (leaving.isEmpty()) {
            return;
        }
        try {
            listeners.syncReleasing(app.getClusterId(), app.getNamespace(),
                    p -> app.getId().equals(p.getAppId()) && leaving.contains(p.getServiceKey()),
                    () -> leaving.forEach(key -> ports.release(app.getId(), key)));
        } catch (RuntimeException e) {
            log.warn("应用 {} 发布后释放网关端口失败（端口保留，下次成功发布再放）：{}", app.getId(), e.getMessage());
        }
    }

    /** 下线完成后：摘掉本应用的 listener 并释放全部网关端口；同步失败只记日志、端口保留 */
    @Override
    public void afterOffline(PlatformAppExecution execution) {
        PlatformApplication app = apps.selectById(execution.getAppId());
        if (app == null) {
            return;
        }
        try {
            listeners.syncReleasing(app.getClusterId(), app.getNamespace(),
                    p -> app.getId().equals(p.getAppId()), () -> ports.releaseApp(app.getId()));
        } catch (RuntimeException e) {
            // 端口保留（listener 还在集群上）：再上线直接复用，删应用时释放
            log.warn("应用 {} 下线后同步 ListenerSet 失败，网关端口保留：{}", app.getId(), e.getMessage());
        }
    }

    @Override
    public AppRenderRequestDTO build(PlatformApplication app, PlatformAppRelease release) {
        return buildRequest(app, release.getReleaseNo(), AppSnapshot.parse(release.getSnapshotJson()));
    }

    public AppRenderRequestDTO buildRequest(PlatformApplication app, int releaseNo, AppSnapshot snapshot) {
        return buildRequest(app, releaseNo, snapshot, false);
    }

    /**
     * @param preview true = 发布预览：网关端口可能还没分配（下线后重新上线，PRECHECK 才重新分配），
     *                路由用占位 sectionName 渲染，只用于对象差异展示，不会下发
     */
    public AppRenderRequestDTO buildRequest(PlatformApplication app, int releaseNo, AppSnapshot snapshot,
                                            boolean preview) {
        if (snapshot.services().isEmpty()) {
            throw error("发布不含任何服务");
        }
        Context ctx = context(app, snapshot);
        List<AppNodeDTO> nodes = new ArrayList<>();
        Map<String, List<AppInjectDTO>> inject = new LinkedHashMap<>();
        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> e : snapshot.services().entrySet()) {
            Map<String, Object> desired = e.getValue();
            AppEnvResolver.Result r = AppEnvResolver.resolve(AppServiceDesired.envRows(desired),
                    ctx.connections, ctx.addrs);
            r.problems().forEach(p -> problems.add(e.getKey() + "：" + p.getMessage()));
            inject.put(e.getKey(), r.env());
            List<Map<String, Object>> files = resolveFiles(e.getKey(), desired, ctx, problems);

            Map<String, Object> params = new LinkedHashMap<>();
            desired.forEach((k, v) -> {
                if (!NODE_EXCLUDED.contains(k)) {
                    params.put(k, v);
                }
            });
            params.put("image", AppServiceDesired.image(desired));
            // otel / mesh 翻译成通用注入参数（k8s-server 只透传）；没有这两块时什么都不加
            // 链路追踪 / 网格是应用级设置：先合并进该服务的生效配置
            params.putAll(AppServiceInjection.params(snapshot.effective(e.getKey()),
                    AppObjectNames.objectName(app.getSlug(), e.getKey())));
            if (!files.isEmpty()) {
                params.put("configFiles", files);   // 引用已替换成值；没有配置文件就不带这个键（参数不变 = 不滚动）
            }
            String gateway = AppServiceDesired.gatewayProtocol(desired);
            if (gateway != null) {
                // 网关方式：Service 保持 ClusterIP，路由挂到命名空间 ListenerSet 上分到的那个 listener
                params.put("expose", "none");
                params.remove("nodePort");
                params.put("route", Map.of(
                        "kind", gateway.toLowerCase(java.util.Locale.ROOT),
                        "parentName", IngressListenerSync.LISTENER_SET,
                        "sectionName", sectionName(app, e.getKey(), preview)));
            }
            // 拉取权限不在这里补：imagePullSecrets 只取自快照（用户意图，回滚才能逐字还原）。
            // 平台 Harbor 的拉取凭据靠命名空间 default SA 上挂着的 platform-registry-pull —— 它在分配命名空间时
            // 下发，也可由「重下发全部模板」补；chart 不设 serviceAccountName，Pod 走 default SA，由 SA 准入带上。
            AppNodeDTO node = new AppNodeDTO();
            node.setKey(e.getKey());
            node.setKind(AppServiceDesired.kindOf(desired));   // 原样发给 k8s-server
            node.setParams(params);
            nodes.add(node);
        }
        if (!problems.isEmpty()) {
            throw error(String.join("；", problems));
        }
        AppSpecDTO spec = new AppSpecDTO();
        spec.setNamespace(app.getNamespace());
        spec.setSettings(Map.of());
        spec.setNodes(nodes);
        spec.setEdges(List.of());

        AppRenderRequestDTO req = new AppRenderRequestDTO();
        req.setClusterId(app.getClusterId());
        req.setNamespace(app.getNamespace());
        req.setAppId(app.getId());
        req.setAppSlug(app.getSlug());
        req.setRevision(releaseNo);
        req.setSpec(spec);
        req.setInjectByNode(inject);
        return req;
    }

    private String sectionName(PlatformApplication app, String key, boolean preview) {
        Integer port = ports.portOf(app.getId(), key);
        if (port != null) {
            return IngressListenerSync.sectionName(port);
        }
        if (preview) {
            return "p-unallocated";
        }
        throw error("服务 " + key + " 的网关端口尚未分配（发布前校验会分配，请重试发布）");
    }

    /** 快照里所有引用能否解析；每条问题带服务名。连接信息取不到时只报这一条。 */
    public List<String> problems(PlatformApplication app, AppSnapshot snapshot) {
        Context ctx;
        try {
            ctx = context(app, snapshot);
        } catch (CloudPlatformException e) {
            return List.of(e.getMessage());
        }
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> e : snapshot.services().entrySet()) {
            AppEnvResolver.Result r = AppEnvResolver.resolve(AppServiceDesired.envRows(e.getValue()),
                    ctx.connections, ctx.addrs);
            for (AppProblemDTO p : r.problems()) {
                if ("ERROR".equals(p.getSeverity())) {
                    out.add(e.getKey() + "：" + p.getMessage());
                }
            }
            resolveFiles(e.getKey(), e.getValue(), ctx, out);
        }
        out.addAll(nodePortClashes(snapshot));
        return out;
    }

    /** 同一应用里两个服务填了同一个固定 NodePort：集群只会给第一个，第二个 apply 时才报错，在这里提前拦下 */
    static List<String> nodePortClashes(AppSnapshot snapshot) {
        Map<Integer, String> owner = new LinkedHashMap<>();
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> e : snapshot.services().entrySet()) {
            Integer np = AppServiceDesired.nodePort(e.getValue());
            if (np == null) {
                continue;
            }
            String first = owner.putIfAbsent(np, e.getKey());
            if (first != null) {
                out.add(e.getKey() + "：NodePort 端口 " + np + " 已被服务 " + first + " 使用");
            }
        }
        return out;
    }

    /**
     * 配置文件内容里的引用替换成值（只认 host/port/address/url；保存时已拦过凭据，这里再拦一次，
     * 防止手工改过的快照把凭据位置展开进 ConfigMap）。问题追加进 problems，带服务名。
     */
    private static List<Map<String, Object>> resolveFiles(String key, Map<String, Object> desired, Context ctx,
                                                          List<String> problems) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, String> f : AppServiceDesired.configFiles(desired)) {
            AppEnvResolver.TextResult t = AppEnvResolver.resolveText(f.get("path"), f.get("content"),
                    ctx.connections, ctx.addrs);
            t.problems().forEach(p -> problems.add(key + "：" + p.getMessage()));
            if (t.value() != null) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("path", f.get("path"));
                m.put("content", t.value());
                out.add(m);
            }
        }
        return out;
    }

    private record Context(Map<String, ConnInfo> connections, Map<String, ServiceAddr> addrs) {
    }

    private Context context(PlatformApplication app, AppSnapshot snapshot) {
        Map<String, ConnInfo> conns = dependencies.connectionsOf(referencedDependencies(snapshot),
                app.getTenantId());
        Map<String, ServiceAddr> addrs = snapshot.services().entrySet().stream()
                .filter(e -> AppServiceDesired.port(e.getValue()) != null)
                .collect(Collectors.toMap(Map.Entry::getKey,
                        e -> new ServiceAddr(AppObjectNames.objectName(app.getSlug(), e.getKey()),
                                AppServiceDesired.port(e.getValue()))));
        return new Context(conns, addrs);
    }

    /** 快照内服务的环境变量与配置文件引用到的依赖（就绪检查与连接信息都只针对它们）。 */
    static List<PlatformAppDependency> referencedDependencies(AppSnapshot snapshot) {
        Set<String> refs = new java.util.HashSet<>();
        snapshot.services().values().forEach(d -> refs.addAll(AppServiceDesired.references(d)));
        return snapshot.dependencies().stream().filter(d -> refs.contains(d.getDepKey())).toList();
    }

    private static CloudPlatformException error(String message) {
        return new CloudPlatformException(EnumResponseType.ERROR, message);
    }
}
