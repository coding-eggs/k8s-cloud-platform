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
import com.coding.data.mapper.system.PlatformApplicationMapper;
import com.coding.data.models.system.PlatformAppDependency;
import com.coding.data.models.system.PlatformAppExecution;
import com.coding.data.models.system.PlatformAppRelease;
import com.coding.data.models.system.PlatformApplication;
import com.coding.platformapi.app.AppEnvResolver;
import com.coding.platformapi.app.AppEnvResolver.ServiceAddr;
import com.coding.platformapi.app.AppObjectNames;
import com.coding.platformapi.app.AppServiceDesired;
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
@Component
public class AppReleasePipeline implements AppReleasePrecheck, AppReleaseRenderer {

    private static final Set<String> NODE_EXCLUDED = Set.of("imageRepo", "imageTag", "env");

    private final PlatformApplicationMapper apps;
    private final PlatformAppReleaseMapper releases;
    private final AppDependencyService dependencies;

    public AppReleasePipeline(PlatformApplicationMapper apps, PlatformAppReleaseMapper releases,
                              AppDependencyService dependencies) {
        this.apps = apps;
        this.releases = releases;
        this.dependencies = dependencies;
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
    }

    @Override
    public AppRenderRequestDTO build(PlatformApplication app, PlatformAppRelease release) {
        return buildRequest(app, release.getReleaseNo(), AppSnapshot.parse(release.getSnapshotJson()));
    }

    public AppRenderRequestDTO buildRequest(PlatformApplication app, int releaseNo, AppSnapshot snapshot) {
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

            Map<String, Object> params = new LinkedHashMap<>();
            desired.forEach((k, v) -> {
                if (!NODE_EXCLUDED.contains(k)) {
                    params.put(k, v);
                }
            });
            params.put("image", AppServiceDesired.image(desired));
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

    /** 快照内服务的环境变量引用到的依赖（就绪检查与连接信息都只针对它们）。 */
    static List<PlatformAppDependency> referencedDependencies(AppSnapshot snapshot) {
        Set<String> refs = new java.util.HashSet<>();
        snapshot.services().values().forEach(d -> refs.addAll(AppServiceDesired.references(d)));
        return snapshot.dependencies().stream().filter(d -> refs.contains(d.getDepKey())).toList();
    }

    private static CloudPlatformException error(String message) {
        return new CloudPlatformException(EnumResponseType.ERROR, message);
    }
}
