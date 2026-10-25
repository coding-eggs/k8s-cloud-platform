package com.coding.platformapi.services;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.app.AppObjectRefDTO;
import com.coding.common.models.app.AppPruneRequestDTO;
import com.coding.common.models.app.AppProblemDTO;
import com.coding.common.models.app.AppSpecDTO;
import com.coding.data.mapper.system.PlatformApplicationMapper;
import com.coding.data.mapper.system.PlatformApplicationOperationMapper;
import com.coding.data.mapper.system.PlatformApplicationResourceMapper;
import com.coding.data.mapper.system.PlatformApplicationRevisionMapper;
import com.coding.data.mapper.auth.PlatformTenantNamespaceMapper;
import com.coding.data.models.system.PlatformApplication;
import com.coding.data.models.system.PlatformApplicationResource;
import com.coding.data.models.auth.PlatformTenantNamespace;
import com.coding.platformapi.app.AppObjectNames;
import com.coding.platformapi.app.AppSpecValidator;
import com.coding.platformapi.k8s.K8sAppClient;
import com.coding.platformapi.models.ApplicationCreateRequest;
import com.coding.platformapi.models.ApplicationDeleteRequest;
import com.coding.platformapi.models.ApplicationOptionsView;
import com.coding.platformapi.models.ApplicationQueryRequest;
import com.coding.platformapi.models.ApplicationRevisionView;
import com.coding.platformapi.models.ApplicationUpdateRequest;
import com.coding.platformapi.models.ApplicationView;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/** 应用台账（spec 是真相源；集群写入一律经 k8s-server）。 */
@Service
public class ApplicationService {

    private static final ObjectMapper JSON = JsonMapper.builder().build();

    private final PlatformApplicationMapper apps;
    private final PlatformApplicationRevisionMapper revisions;
    private final PlatformApplicationOperationMapper operations;
    private final PlatformApplicationResourceMapper resources;
    private final PlatformTenantNamespaceMapper allocations;
    private final K8sAppClient client;

    public ApplicationService(PlatformApplicationMapper apps,
                              PlatformApplicationRevisionMapper revisions,
                              PlatformApplicationOperationMapper operations,
                              PlatformApplicationResourceMapper resources,
                              PlatformTenantNamespaceMapper allocations,
                              K8sAppClient client) {
        this.apps = apps;
        this.revisions = revisions;
        this.operations = operations;
        this.resources = resources;
        this.allocations = allocations;
        this.client = client;
    }

    public ApplicationView create(ApplicationCreateRequest req, String operator, String tenantId) {
        assertValid(req.getSpec(), req.getNamespace());
        PlatformApplication row = new PlatformApplication();
        row.setId("app_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        row.setTenantId(tenantId);
        row.setClusterId(req.getClusterId());
        row.setNamespace(req.getSpec().getNamespace());
        row.setName(req.getName());
        row.setSlug(AppObjectNames.slug(req.getName()));
        row.setDisplayName(isBlank(req.getDisplayName()) ? req.getName() : req.getDisplayName());
        row.setDescription(req.getDescription());
        row.setStatus("DRAFT");
        row.setSpecJson(JSON.writeValueAsString(req.getSpec()));
        row.setCurrentRevision(0);
        row.setCreatedBy(operator);
        row.setCreatedAt(new Date());
        row.setUpdatedAt(new Date());
        apps.insert(row);
        return view(row);
    }

    public ApplicationView get(String appId, String tenantId) {
        return view(require(appId, tenantId));
    }

    public List<ApplicationView> list(String clusterId, String tenantId) {
        return apps.selectByTenant(tenantId, clusterId).stream().map(this::view).toList();
    }

    public ApplicationView update(ApplicationUpdateRequest req, String operator) {
        PlatformApplication row = require(req.getAppId(), null);
        if (!"DRAFT".equals(row.getStatus()) && !"READY".equals(row.getStatus())
                && !"FAILED".equals(row.getStatus())) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "部署进行中，不能改图");
        }
        if (req.getSpec() != null) {
            assertValid(req.getSpec(), row.getNamespace());
            row.setSpecJson(JSON.writeValueAsString(req.getSpec()));
        }
        if (req.getName() != null && !req.getName().isBlank()) {
            row.setName(req.getName());
            row.setSlug(AppObjectNames.slug(req.getName()));
        }
        if (req.getDisplayName() != null) {
            row.setDisplayName(req.getDisplayName());
        }
        if (req.getDescription() != null) {
            row.setDescription(req.getDescription());
        }
        row.setUpdatedAt(new Date());
        apps.updateById(row);
        return view(row);
    }

    /**
     * 删除应用：先删本应用台账里的集群对象（PVC 保留，数据不随应用消失），再软删台账。
     *
     * <p>为什么同步删而不是排一个操作：对象数量受 20 节点上限约束，删除是幂等的，
     * 而「台账没了但对象还在」会让这些资源永远失去归属。
     */
    public void delete(ApplicationDeleteRequest req, String operator) {
        PlatformApplication row = apps.selectById(req.getAppId());
        if (row == null || row.getDeletedAt() != null) {
            return;
        }
        if (operations.selectActiveByApp(req.getAppId()) != null) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "应用正在部署，不能删除");
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
    }

    public List<ApplicationRevisionView> revisions(String appId) {
        return revisions.selectByApp(appId).stream().map(r -> {
            ApplicationRevisionView v = new ApplicationRevisionView();
            v.setRevision(r.getRevision());
            v.setStatus(r.getStatus());
            v.setMessage(r.getMessage());
            v.setCreatedAt(r.getCreatedAt());
            return v;
        }).toList();
    }

    /** 画布左栏的可选项：命名空间与中间件商店同源（分配表），避免两处口径不一致。 */
    public ApplicationOptionsView options(String clusterId, String tenantId) {
        ApplicationOptionsView out = new ApplicationOptionsView();
        out.setNodeKinds(List.of(
                nodeKind("SERVICE", "服务", "有端口的无状态/有状态服务"),
                nodeKind("WORKER", "后台进程", "无端口、不建 Service")));
        out.setNamespaces(allocations.listByCluster(clusterId).stream()
                .filter(a -> tenantId == null || tenantId.equals(a.getTenantId()))
                .map(PlatformTenantNamespace::getNamespace)
                .filter(Objects::nonNull)
                .distinct().sorted().toList());
        return out;
    }

    private static Map<String, Object> nodeKind(String kind, String name, String description) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", kind);
        m.put("name", name);
        m.put("description", description);
        return m;
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

    private void assertValid(AppSpecDTO spec, String requestNamespace) {
        if (spec == null) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "应用定义为空");
        }
        if (requestNamespace != null && !requestNamespace.isBlank()
                && !requestNamespace.equals(spec.getNamespace())) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "请求命名空间与应用定义里的命名空间不一致");
        }
        String errors = AppSpecValidator.validate(spec).stream()
                .filter(p -> "ERROR".equals(p.getSeverity()))
                .map(AppProblemDTO::getMessage)
                .collect(Collectors.joining("；"));
        if (!errors.isEmpty()) {
            throw new CloudPlatformException(EnumResponseType.ERROR, errors);
        }
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
        v.setCurrentRevision(row.getCurrentRevision());
        v.setSpec(JSON.readValue(row.getSpecJson(), AppSpecDTO.class));
        v.setCreatedAt(row.getCreatedAt());
        v.setUpdatedAt(row.getUpdatedAt());
        return v;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
