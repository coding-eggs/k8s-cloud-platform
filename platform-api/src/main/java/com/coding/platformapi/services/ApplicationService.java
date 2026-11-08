package com.coding.platformapi.services;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.app.AppObjectRefDTO;
import com.coding.common.models.app.AppPruneRequestDTO;
import com.coding.data.mapper.auth.PlatformTenantNamespaceMapper;
import com.coding.data.mapper.system.PlatformAppExecutionMapper;
import com.coding.data.mapper.system.PlatformApplicationMapper;
import com.coding.data.mapper.system.PlatformApplicationResourceMapper;
import com.coding.data.models.auth.PlatformTenantNamespace;
import com.coding.data.models.system.PlatformApplication;
import com.coding.data.models.system.PlatformApplicationResource;
import com.coding.platformapi.app.AppObjectNames;
import com.coding.platformapi.k8s.K8sAppClient;
import com.coding.platformapi.models.ApplicationCreateRequest;
import com.coding.platformapi.models.ApplicationDeleteRequest;
import com.coding.platformapi.models.ApplicationOptionsView;
import com.coding.platformapi.models.ApplicationView;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 应用台账：建、查、删与新建表单的可选项。服务、依赖、发布分别在
 * {@link AppServiceConfigService}、{@link AppDependencyService}、{@link AppReleaseService}。集群写入一律经 k8s-server。
 */
@lombok.extern.slf4j.Slf4j
@Service
public class ApplicationService {

    private final PlatformApplicationMapper apps;
    private final PlatformApplicationResourceMapper resources;
    private final PlatformTenantNamespaceMapper allocations;
    private final NamespaceAccessGuard namespaceGuard;
    private final K8sAppClient client;
    private final AppDependencyService appDependencies;
    private final PlatformAppExecutionMapper appExecutions;
    private final IngressPortService ingressPorts;
    private final IngressListenerSync ingressListeners;

    public ApplicationService(PlatformApplicationMapper apps,
                              PlatformApplicationResourceMapper resources,
                              PlatformTenantNamespaceMapper allocations,
                              NamespaceAccessGuard namespaceGuard,
                              K8sAppClient client,
                              AppDependencyService appDependencies,
                              PlatformAppExecutionMapper appExecutions,
                              IngressPortService ingressPorts,
                              IngressListenerSync ingressListeners) {
        this.ingressPorts = ingressPorts;
        this.ingressListeners = ingressListeners;
        this.apps = apps;
        this.resources = resources;
        this.allocations = allocations;
        this.namespaceGuard = namespaceGuard;
        this.client = client;
        this.appDependencies = appDependencies;
        this.appExecutions = appExecutions;
    }

    public ApplicationView create(ApplicationCreateRequest req, String operator, String tenantId) {
        if (tenantId == null) {
            throw new CloudPlatformException(EnumResponseType.ERROR,
                    "请先切换到某个租户（或显式指定 tenantId）再创建应用");
        }
        if (isBlank(req.getClusterId()) || isBlank(req.getNamespace())) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "请选择集群和命名空间");
        }
        if (isBlank(req.getName())) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "请填写应用名");
        }
        // 分配表是安全边界：前端下拉只列本租户命名空间，接口层同样要挡
        namespaceGuard.assertAccessible(req.getClusterId(), req.getNamespace());

        PlatformApplication row = new PlatformApplication();
        row.setId("app_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        row.setTenantId(tenantId);
        row.setClusterId(req.getClusterId());
        row.setNamespace(req.getNamespace());
        row.setName(req.getName().trim());
        row.setSlug(AppObjectNames.slug(row.getName()));
        row.setDisplayName(isBlank(req.getDisplayName()) ? row.getName() : req.getDisplayName());
        row.setDescription(req.getDescription());
        row.setStatus("DRAFT");
        row.setCreatedBy(operator);
        row.setCreatedAt(new Date());
        row.setUpdatedAt(new Date());
        try {
            apps.insert(row);
        } catch (DuplicateKeyException e) {
            // 生成列 active_key 的唯一键：同集群同命名空间下不许重名
            throw new CloudPlatformException(EnumResponseType.ERROR,
                    "该命名空间下已有同名应用：" + row.getName());
        }
        return view(row);
    }

    public ApplicationView get(String appId, String tenantId) {
        return view(require(appId, tenantId));
    }

    public List<ApplicationView> list(String clusterId, String tenantId) {
        // 管理员（tenantId 为 null）= 看全部；mapper 里按需拼 tenant 条件
        return apps.selectByTenant(tenantId, clusterId).stream().map(this::view).toList();
    }

    /**
     * 删除应用：先删本应用台账里的集群对象（PVC 保留，数据不随应用消失），再软删台账。
     *
     * <p>为什么同步删而不是排一个操作：对象数量有限，删除是幂等的，
     * 而「台账没了但对象还在」会让这些资源永远失去归属。资源台账含历次发布（以及旧画布版本）写下的对象，
     * 所以旧画布建的应用也能在这里删干净。
     */
    public void delete(ApplicationDeleteRequest req, String operator, String tenantId) {
        PlatformApplication row = apps.selectById(req.getAppId());
        if (row == null || row.getDeletedAt() != null) {
            return;
        }
        if (tenantId != null && !tenantId.equals(row.getTenantId())) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "应用不存在或已删除");
        }
        if (appExecutions.selectActiveByApp(req.getAppId()) != null) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "应用有进行中的发布，不能删除");
        }
        List<AppObjectRefDTO> stale = wholeOwnedObjects(req.getAppId());
        if (!stale.isEmpty()) {
            AppPruneRequestDTO prune = new AppPruneRequestDTO();
            prune.setClusterId(row.getClusterId());
            prune.setObjects(stale);
            prune.setDeletePvc(false);
            client.prune(prune);
        }
        apps.softDelete(req.getAppId(), new Date());
        // 网关端口：对象已清掉，端口释放并同步命名空间 ListenerSet（失败只记日志：下次该命名空间发布会补齐）
        ingressPorts.releaseApp(req.getAppId());
        try {
            ingressListeners.sync(row.getClusterId(), row.getNamespace());
        } catch (RuntimeException e) {
            log.warn("删除应用 {} 后同步 ListenerSet 失败：{}", req.getAppId(), e.getMessage());
        }
        // 依赖：CREATED 实例保留并转交中间件模块（删应用不静默删数据），引用行与依赖行清掉，
        // 否则实例会被一个已删除的应用永远占着、实例页也删不掉
        appDependencies.detachForAppDelete(req.getAppId());
    }

    /** 新建应用表单的可选项：命名空间与中间件商店同源（分配表），避免两处口径不一致。 */
    public ApplicationOptionsView options(String clusterId, String tenantId) {
        ApplicationOptionsView out = new ApplicationOptionsView();
        out.setNamespaces(allocations.listByCluster(clusterId).stream()
                .filter(a -> tenantId == null || tenantId.equals(a.getTenantId()))
                .map(PlatformTenantNamespace::getNamespace)
                .filter(Objects::nonNull)
                .distinct().sorted().toList());
        return out;
    }

    private List<AppObjectRefDTO> wholeOwnedObjects(String appId) {
        Map<String, AppObjectRefDTO> unique = new LinkedHashMap<>();
        for (PlatformApplicationResource r : resources.selectByApp(appId)) {
            unique.putIfAbsent(r.getKind() + "/" + r.getName(), toRef(r));
        }
        return new ArrayList<>(unique.values());
    }

    private static AppObjectRefDTO toRef(PlatformApplicationResource r) {
        AppObjectRefDTO ref = new AppObjectRefDTO();
        ref.setApiVersion(r.getApiVersion());
        ref.setKind(r.getKind());
        ref.setNamespace(r.getNamespace());
        ref.setName(r.getName());
        return ref;
    }

    /** tenantId 非空 = 租户轨，必须等于台账归属；null = 管理员轨（权限码已在上层判过）。 */
    PlatformApplication require(String appId, String tenantId) {
        PlatformApplication row = apps.selectById(appId);
        if (row == null || row.getDeletedAt() != null) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "应用不存在或已删除");
        }
        if (tenantId != null && !tenantId.equals(row.getTenantId())) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "应用不存在或已删除");
        }
        return row;
    }

    private ApplicationView view(PlatformApplication row) {
        ApplicationView v = new ApplicationView();
        v.setId(row.getId());
        v.setClusterId(row.getClusterId());
        v.setNamespace(row.getNamespace());
        v.setName(row.getName());
        v.setSlug(row.getSlug());
        v.setDisplayName(row.getDisplayName());
        v.setDescription(row.getDescription());
        v.setStatus(row.getStatus());
        v.setLiveReleaseNo(row.getLiveReleaseNo());
        v.setCreatedAt(row.getCreatedAt());
        v.setUpdatedAt(row.getUpdatedAt());
        return v;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
