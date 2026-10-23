package com.coding.platformapi.services;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.k8s.dto.StorageClassDTO;
import com.coding.common.models.middleware.MiddlewareCatalogItemDTO;
import com.coding.common.models.middleware.MiddlewareConnectionDTO;
import com.coding.common.models.middleware.MiddlewareFormOptionsDTO;
import com.coding.common.models.middleware.MiddlewareCreateRequestDTO;
import com.coding.common.models.middleware.MiddlewareDeleteRequestDTO;
import com.coding.common.models.middleware.MiddlewareModeDTO;
import com.coding.common.models.middleware.MiddlewareOperatorInstallRequestDTO;
import com.coding.common.models.middleware.MiddlewareOperatorStatusDTO;
import com.coding.common.models.middleware.MiddlewareOperatorStatusRequestDTO;
import com.coding.common.models.middleware.MiddlewareScaleRequestDTO;
import com.coding.common.models.middleware.MiddlewareStatusRequestDTO;
import com.coding.data.mapper.auth.PlatformTenantNamespaceMapper;
import com.coding.data.mapper.system.PlatformMiddlewareInstanceMapper;
import com.coding.data.mapper.system.PlatformMiddlewareOperationMapper;
import com.coding.data.mapper.system.PlatformMiddlewareOperatorMapper;
import com.coding.data.models.auth.PlatformTenantNamespace;
import com.coding.data.models.system.PlatformMiddlewareInstance;
import com.coding.data.models.system.PlatformMiddlewareOperation;
import com.coding.data.models.system.PlatformMiddlewareOperator;
import com.coding.platformapi.k8s.K8sClient;
import com.coding.platformapi.k8s.K8sMiddlewareClient;
import com.coding.platformapi.middleware.MiddlewareCatalog;
import com.coding.platformapi.models.MiddlewareCreateRequest;
import com.coding.platformapi.models.MiddlewareDeleteRequest;
import com.coding.platformapi.models.MiddlewareScaleRequest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 中间件商店的业务层：目录 + 实例台账 + 操作记录。
 *
 * <p>判定都在这边（Operator 是否就绪、形态是否支持、租户归属）；k8s-server 只负责执行与事实读取。
 *
 * <p><b>凭据</b>：密码在这里生成、随 DTO 下发、只由 k8s-server 写进 Secret；
 * 台账的 {@code spec_json} 与审计都不含密码。
 */
@Service
public class MiddlewareService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final PlatformMiddlewareInstanceMapper instances;
    private final PlatformMiddlewareOperationMapper operations;
    private final PlatformMiddlewareOperatorMapper operators;
    private final K8sMiddlewareClient client;
    private final MiddlewareCatalog catalog;
    private final JsonMapper jsonMapper;
    private final K8sClient k8s;
    private final PlatformTenantNamespaceMapper allocations;

    public MiddlewareService(PlatformMiddlewareInstanceMapper instances,
                             PlatformMiddlewareOperationMapper operations,
                             PlatformMiddlewareOperatorMapper operators,
                             K8sMiddlewareClient client,
                             MiddlewareCatalog catalog,
                             JsonMapper jsonMapper,
                             K8sClient k8s,
                             PlatformTenantNamespaceMapper allocations) {
        this.instances = instances;
        this.operations = operations;
        this.operators = operators;
        this.client = client;
        this.catalog = catalog;
        this.jsonMapper = jsonMapper;
        this.k8s = k8s;
        this.allocations = allocations;
    }

    // ==================== 目录 ====================

    public List<MiddlewareCatalogItemDTO> catalog(String clusterId, String tenantId) {
        requireCluster(clusterId);
        List<MiddlewareOperatorStatusDTO> stats = client.operatorStatus(statusRequest(clusterId));
        List<PlatformMiddlewareInstance> all = instances.selectByCluster(clusterId, null);

        return catalog.items().stream().map(item -> {
            MiddlewareCatalogItemDTO dto = new MiddlewareCatalogItemDTO();
            dto.setCode(item.code());
            dto.setName(item.name());
            dto.setDescription(item.description());
            dto.setChartBacked(catalog.chartBacked(item.code()));
            dto.setModes(item.modes().stream()
                    .map(m -> new MiddlewareModeDTO(m.mode(), m.label(), m.minReplicas(),
                            m.fixedReplicas(), m.replicasLabel(), m.help()))
                    .toList());
            dto.setVersions(item.versions());
            stats.stream().filter(s -> item.code().equals(s.getCode())).findFirst().ifPresent(s -> {
                dto.setOperatorInstalled(s.isInstalled());
                dto.setOperatorReady(s.isReady());
                dto.setChartVersion(s.getChartVersion());
                dto.setOperatorMessage(s.getMessage());
            });
            dto.setInstanceCount((int) all.stream()
                    .filter(i -> item.code().equals(i.getCode()))
                    .filter(i -> tenantId == null || tenantId.equals(i.getTenantId()))
                    .count());
            return dto;
        }).toList();
    }

    /**
     * 创建表单的可选项：命名空间按租户分配表收窄；StorageClass 走管理端读（租户 SA 无此权限）。
     * 读失败降级为空列表，不让一个存储类查询把整个表单打挂。
     */
    public MiddlewareFormOptionsDTO formOptions(String clusterId, String tenantId) {
        requireCluster(clusterId);
        MiddlewareFormOptionsDTO out = new MiddlewareFormOptionsDTO();
        out.setNamespaces(allocations.listByCluster(clusterId).stream()
                .filter(a -> tenantId == null || tenantId.equals(a.getTenantId()))
                .map(PlatformTenantNamespace::getNamespace)
                .filter(Objects::nonNull)
                .distinct().sorted().toList());
        try {
            StorageClassDTO query = new StorageClassDTO();
            query.setClusterId(clusterId);
            out.setStorageClasses(k8s.list(query).stream()
                    .map(StorageClassDTO::getName)
                    .filter(Objects::nonNull)
                    .sorted().toList());
        } catch (RuntimeException e) {
            out.setStorageClasses(List.of());
        }
        return out;
    }

    public MiddlewareOperatorStatusDTO installOperator(String clusterId, String code) {
        catalog.require(code);
        MiddlewareOperatorInstallRequestDTO req = new MiddlewareOperatorInstallRequestDTO();
        req.setClusterId(clusterId);
        req.setCode(code);
        MiddlewareOperatorStatusDTO status = client.operatorInstall(req);
        persistOperator(clusterId, status);
        return status;
    }

    public List<PlatformMiddlewareOperator> operators(String clusterId) {
        return operators.selectByCluster(clusterId);
    }

    // ==================== 实例 ====================

    public List<PlatformMiddlewareInstance> list(String clusterId, String code, String tenantId) {
        requireCluster(clusterId);
        List<PlatformMiddlewareInstance> rows = instances.selectByCluster(clusterId, code).stream()
                .filter(i -> tenantId == null || tenantId.equals(i.getTenantId()))
                .toList();
        // 台账里的状态是"提交时"的快照；列表按 k8s 实时状态覆盖（不写库，读失败则保留台账值）。
        // 按 (code, namespace) 分组批量查，避免每行一次往返。
        rows.stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        r -> r.getCode() + "|" + r.getNamespace(),
                        java.util.LinkedHashMap::new,
                        java.util.stream.Collectors.toList()))
                .values()
                .forEach(this::refreshGroup);
        return rows;
    }

    /** 一次批量读同一 (code, namespace) 下所有实例的状态，失败静默保留台账值。 */
    private void refreshGroup(List<PlatformMiddlewareInstance> group) {
        PlatformMiddlewareInstance first = group.get(0);
        try {
            MiddlewareStatusRequestDTO req = new MiddlewareStatusRequestDTO();
            req.setClusterId(first.getClusterId());
            req.setCode(first.getCode());
            req.setNamespace(first.getNamespace());
            req.setNames(group.stream().map(PlatformMiddlewareInstance::getName).toList());
            // 名字→id：k8s-server 侧用实例标签找 Pod（Redis 哨兵的数据/哨兵 Pod 标签不同）
            req.setInstanceIds(group.stream().collect(java.util.stream.Collectors.toMap(
                    PlatformMiddlewareInstance::getName, PlatformMiddlewareInstance::getId, (a, b) -> a)));
            req.setTenantId(first.getTenantId());
            var result = client.status(first.getCode(), req);
            if (result == null || result.isEmpty()) {
                return;
            }
            var byName = result.stream().collect(java.util.stream.Collectors.toMap(
                    com.coding.common.models.middleware.MiddlewareStatusResultDTO::getName, r -> r,
                    (a, b) -> a));
            for (PlatformMiddlewareInstance row : group) {
                var s = byName.get(row.getName());
                if (s != null) {
                    row.setStatus(mapK8sStatus(row.getStatus(), s.getStatus()));
                    row.setMessage(s.getMessage());
                }
            }
        } catch (RuntimeException ignored) {
            // 读状态失败不影响列表
        }
    }

    private static String mapK8sStatus(String current, String k8s) {
        if ("DELETING".equals(current)) {
            return current;
        }
        return switch (k8s == null ? "" : k8s) {
            case "READY" -> "READY";
            case "DEGRADED" -> "DEGRADED";
            case "FAILED" -> "FAILED";
            default -> "CREATING";
        };
    }

    public PlatformMiddlewareInstance get(String instanceId, String tenantId) {
        PlatformMiddlewareInstance app = instances.selectByPrimaryKey(instanceId);
        if (app == null || app.getDeletedAt() != null) {
            throw new CloudPlatformException(EnumResponseType.RESOURCE_NOT_EXIST);
        }
        assertOwned(app, tenantId);
        return app;
    }

    public Long create(MiddlewareCreateRequest req, String operator, String tenantId) {
        var item = catalog.require(req.getCode());
        var mode = catalog.requireMode(item, req.getMode());
        if (req.getReplicas() != null && req.getReplicas() < mode.minReplicas()) {
            throw new CloudPlatformException(EnumResponseType.ERROR,
                    item.name() + " 的「" + mode.label() + "」至少需要 " + mode.minReplicas() + " 个节点");
        }
        if (isBlank(req.getClusterId()) || isBlank(req.getNamespace())) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "请选择集群与命名空间");
        }
        // 版本只能从平台声明的清单里选（表单是下拉，接口也要挡，否则绕过前端就能写任意 tag）
        if (!item.versions().isEmpty()) {
            String version = isBlank(req.getVersion()) ? item.versions().get(0) : req.getVersion().trim();
            if (!catalog.supportsVersion(item, version)) {
                throw new CloudPlatformException(EnumResponseType.ERROR,
                        item.name() + " 不支持版本 " + version + "，可选：" + String.join(" / ", item.versions()));
            }
            req.setVersion(version);
        }
        // Nacos 的 chart 把 Service/ConfigMap 名字写死成 nacos-cs：同一命名空间只能有一个实例
        if (isChartBacked(req.getCode()) && !instances.selectByCluster(req.getClusterId(), req.getCode())
                .stream().filter(i -> req.getNamespace().equals(i.getNamespace())).toList().isEmpty()) {
            throw new CloudPlatformException(EnumResponseType.ERROR,
                    "同一命名空间只能有一个 Nacos 实例（chart 的 Service 名固定为 nacos-cs）");
        }
        // Operator 未就绪的集群不能创建实例（Nacos 走 chart，不在本批）
        if (!isChartBacked(req.getCode())) {
            requireOperatorReady(req.getClusterId(), req.getCode(), item.name());
        }

        String name = "mw-" + req.getCode() + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        PlatformMiddlewareInstance instance = new PlatformMiddlewareInstance();
        instance.setId("mwi_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        instance.setClusterId(req.getClusterId());
        instance.setNamespace(req.getNamespace());
        instance.setCode(req.getCode());
        instance.setMode(req.getMode());
        instance.setName(name);
        instance.setDisplayName(isBlank(req.getDisplayName()) ? item.name() + "-" + name.substring(name.length() - 4)
                : req.getDisplayName());
        instance.setVersion(req.getVersion() == null ? "" : req.getVersion());
        instance.setSpecJson(toSpecJson(req));
        instance.setStatus("CREATING");
        instance.setTenantId(tenantId);
        instance.setCreatedBy(operator);
        instance.setCreatedAt(new Date());
        instance.setUpdatedAt(new Date());
        instances.insert(instance);

        PlatformMiddlewareOperation op = newOperation(instance, "CREATE", operator);
        try {
            MiddlewareCreateRequestDTO dto = new MiddlewareCreateRequestDTO();
            dto.setInstanceId(instance.getId());
            dto.setClusterId(instance.getClusterId());
            dto.setCode(instance.getCode());
            dto.setMode(instance.getMode());
            dto.setNamespace(instance.getNamespace());
            dto.setName(instance.getName());
            dto.setDisplayName(instance.getDisplayName());
            dto.setVersion(instance.getVersion());
            dto.setReplicas(req.getReplicas());
            dto.setReplicasPerShard(req.getReplicasPerShard());
            dto.setCpuRequest(req.getCpuRequest());
            dto.setCpuLimit(req.getCpuLimit());
            dto.setMemoryRequest(req.getMemoryRequest());
            dto.setMemoryLimit(req.getMemoryLimit());
            dto.setStorageSize(req.getStorageSize());
            dto.setStorageClass(req.getStorageClass());
            dto.setKibana(Boolean.TRUE.equals(req.getKibana()));
            dto.setPassword(randomPassword());
            dto.setTenantId(tenantId);
            client.create(req.getCode(), dto);
            finish(op, "SUCCESS", null);
        } catch (RuntimeException e) {
            instance.setStatus("FAILED");
            instance.setMessage(e.getMessage());
            instance.setUpdatedAt(new Date());
            instances.updateById(instance);
            finish(op, "FAILED", e.getMessage());
            throw e;
        }
        return op.getId();
    }

    /**
     * 实例的连接方式（创建成功后弹给用户，实例列表里也能随时打开）。
     *
     * <p>只给**集群内**地址与凭据位置：集群外暴露（Ingress/NodePort）是租户自己的网络决定，
     * 平台不替他建；口令不回传，只告诉去哪个 Secret 的哪个键取。
     */
    public MiddlewareConnectionDTO connection(String instanceId, String tenantId) {
        PlatformMiddlewareInstance instance = requireInstance(instanceId, tenantId);
        String name = instance.getName();
        String ns = instance.getNamespace();
        List<MiddlewareConnectionDTO.Line> endpoints = new ArrayList<>();
        List<MiddlewareConnectionDTO.Line> credentials = new ArrayList<>();
        String note = "以上是集群内地址（<服务>.<命名空间>.svc.cluster.local）；集群外访问需自行建 Ingress/NodePort。";

        switch (instance.getCode()) {
            case "redis" -> {
                switch (instance.getMode() == null ? "" : instance.getMode()) {
                    case "SENTINEL" -> {
                        endpoints.add(line("主库（读写）", svc(ns, name + "-master", 6379)));
                        endpoints.add(line("从库（只读）", svc(ns, name + "-replica", 6379)));
                        endpoints.add(line("哨兵", svc(ns, name + "-sentinel", 26379)));
                    }
                    case "CLUSTER" -> {
                        endpoints.add(line("读写（任一主分片）", svc(ns, name + "-leader", 6379)));
                        endpoints.add(line("只读（从节点）", svc(ns, name + "-follower", 6379)));
                    }
                    default -> endpoints.add(line("节点", svc(ns, name, 6379)));
                }
                credentials.add(line("密码", "Secret " + name + "-credentials，键 password"));
            }
            case "mysql" -> {
                endpoints.add(line("读写（HAProxy）", svc(ns, name + "-haproxy", 3306)));
                endpoints.add(line("只读（HAProxy）", svc(ns, name + "-haproxy", 3307)));
                credentials.add(line("账号", "Secret " + name + "-secrets（Operator 生成，root 等用户）"));
            }
            case "kafka" -> {
                endpoints.add(line("Bootstrap（PLAINTEXT）", svc(ns, name + "-kafka-bootstrap", 9092)));
                note = "监听器是集群内 PLAINTEXT，无认证；客户端 bootstrap.servers 填上面的地址。";
            }
            case "elasticsearch" -> {
                endpoints.add(line("HTTP(S)", "https://" + svc(ns, name + "-es-http", 9200)));
                endpoints.add(line("Kibana（创建时勾选才有）", "http://" + svc(ns, name + "-kb-http", 5601)));
                credentials.add(line("用户名", "elastic"));
                credentials.add(line("密码", "Secret " + name + "-es-elastic-user，键 elastic"));
            }
            case "postgresql" -> {
                endpoints.add(line("读写（主）", svc(ns, name + "-rw", 5432)));
                endpoints.add(line("只读（备）", svc(ns, name + "-ro", 5432)));
                credentials.add(line("用户名/密码", "Secret " + name + "-app，键 username / password"));
            }
            case "rabbitmq" -> {
                endpoints.add(line("AMQP", svc(ns, name, 5672)));
                endpoints.add(line("管理台", svc(ns, name, 15672)));
                credentials.add(line("用户名/密码", "Secret " + name + "-default-user，键 username / password"));
            }
            case "nacos" -> {
                endpoints.add(line("HTTP 控制台 / 接口", svc(ns, "nacos-cs", 8848)));
                endpoints.add(line("gRPC（2.x 客户端）", svc(ns, "nacos-cs", 9848)));
                note = "Nacos chart 默认未开启鉴权；对外暴露前请自行配置。";
            }
            default -> note = "该中间件暂未提供连接方式说明。";
        }

        MiddlewareConnectionDTO out = new MiddlewareConnectionDTO();
        out.setInstanceName(name);
        out.setNamespace(ns);
        out.setEndpoints(endpoints);
        out.setCredentials(credentials);
        out.setNote(note);
        return out;
    }

    private static MiddlewareConnectionDTO.Line line(String label, String value) {
        return new MiddlewareConnectionDTO.Line(label, value);
    }

    private static String svc(String namespace, String service, int port) {
        return service + "." + namespace + ".svc.cluster.local:" + port;
    }

    public Long scale(MiddlewareScaleRequest req, String operator, String tenantId) {
        PlatformMiddlewareInstance instance = requireInstance(req.getInstanceId(), tenantId);
        PlatformMiddlewareOperation op = newOperation(instance, "SCALE", operator);
        try {
            MiddlewareScaleRequestDTO dto = new MiddlewareScaleRequestDTO();
            dto.setClusterId(instance.getClusterId());
            dto.setCode(instance.getCode());
            dto.setNamespace(instance.getNamespace());
            dto.setName(instance.getName());
            dto.setReplicas(req.getReplicas());
            dto.setMode(instance.getMode());
            dto.setCpuRequest(req.getCpuRequest());
            dto.setCpuLimit(req.getCpuLimit());
            dto.setMemoryRequest(req.getMemoryRequest());
            dto.setMemoryLimit(req.getMemoryLimit());
            dto.setStorageSize(req.getStorageSize());
            dto.setTenantId(tenantId);
            client.scale(instance.getCode(), dto);
            finish(op, "SUCCESS", null);
        } catch (RuntimeException e) {
            finish(op, "FAILED", e.getMessage());
            throw e;
        }
        return op.getId();
    }

    public Long delete(MiddlewareDeleteRequest req, String operator, String tenantId) {
        PlatformMiddlewareInstance instance = requireInstance(req.getInstanceId(), tenantId);
        PlatformMiddlewareOperation op = newOperation(instance, "DELETE", operator);
        try {
            MiddlewareDeleteRequestDTO dto = new MiddlewareDeleteRequestDTO();
            dto.setClusterId(instance.getClusterId());
            dto.setCode(instance.getCode());
            dto.setNamespace(instance.getNamespace());
            dto.setName(instance.getName());
            dto.setKeepPvc(req.isKeepPvc());
            dto.setInstanceId(instance.getId());
            dto.setTenantId(tenantId);
            client.delete(instance.getCode(), dto);

            instance.setStatus("DELETING");
            instance.setUpdatedAt(new Date());
            instance.setDeletedAt(new Date());
            instances.updateById(instance);
            finish(op, "SUCCESS", null);
        } catch (RuntimeException e) {
            finish(op, "FAILED", e.getMessage());
            throw e;
        }
        return op.getId();
    }

    /** 操作记录按实例归属收窄：租户只能看自己实例的操作（operation id 是自增的，不能裸按主键取）。 */
    public PlatformMiddlewareOperation operation(Long id, String tenantId) {
        PlatformMiddlewareOperation op = operations.selectByPrimaryKey(id);
        if (op == null) {
            throw new CloudPlatformException(EnumResponseType.RESOURCE_NOT_EXIST);
        }
        if (tenantId != null) {
            PlatformMiddlewareInstance instance = instances.selectByPrimaryKey(op.getInstanceId());
            if (instance == null || !tenantId.equals(instance.getTenantId())) {
                throw new CloudPlatformException(EnumResponseType.ERROR, "无权查看该操作");
            }
        }
        return op;
    }

    /** 进程重启留下的 PENDING/RUNNING 操作会永久占锁：启动时回收。 */
    @org.springframework.context.event.EventListener(
            org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void reapOnStartup() {
        cleanupStale(30);
    }

    /**
     * 运行期也要回收：create 请求被中断（客户端断开/线程被杀）时 {@code finish} 不会执行，
     * 那条 PENDING 的 {@code running_lock} 会让该实例永远"已有进行中的操作"、连删除都做不了。
     * 真集群验证踩过：只能靠重启 platform-api 才能解锁。
     */
    @org.springframework.scheduling.annotation.Scheduled(
            fixedDelayString = "${platform.middleware.operation.cleanup-interval:5m}")
    public void reapStaleOperations() {
        cleanupStale(30);
    }

    public void cleanupStale(int minutes) {
        for (PlatformMiddlewareOperation op : operations.selectStaleRunning(minutes)) {
            op.setStatus("UNKNOWN");
            op.setMessage("操作超时（进程重启或 worker 丢失）");
            op.setFinishedAt(new Date());
            operations.updateById(op);
            PlatformMiddlewareInstance instance = instances.selectByPrimaryKey(op.getInstanceId());
            if (instance != null && !"READY".equals(instance.getStatus())) {
                instance.setStatus("FAILED");
                instance.setMessage("操作超时，请重新创建或删除");
                instance.setUpdatedAt(new Date());
                instances.updateById(instance);
            }
        }
    }

    // ==================== 内部 ====================

    private PlatformMiddlewareInstance requireInstance(String instanceId, String tenantId) {
        return get(instanceId, tenantId);
    }

    private void assertOwned(PlatformMiddlewareInstance instance, String tenantId) {
        if (tenantId != null && !tenantId.equals(instance.getTenantId())) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "无权操作该实例");
        }
    }

    /** 缺 clusterId 时给明确错误，而不是让 k8s 调用抛出去变成「服务端异常」。 */
    private static void requireCluster(String clusterId) {
        if (isBlank(clusterId)) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "请选择集群");
        }
    }

    private void requireOperatorReady(String clusterId, String code, String displayName) {
        MiddlewareOperatorStatusRequestDTO req = new MiddlewareOperatorStatusRequestDTO();
        req.setClusterId(clusterId);
        req.setCodes(List.of(code));
        boolean ready = client.operatorStatus(req).stream()
                .anyMatch(s -> code.equals(s.getCode()) && s.isReady());
        if (!ready) {
            throw new CloudPlatformException(EnumResponseType.ERROR,
                    "请先安装 " + displayName + " Operator（管理员操作）");
        }
    }

    private PlatformMiddlewareOperation newOperation(PlatformMiddlewareInstance instance, String action,
                                                     String operator) {
        PlatformMiddlewareOperation op = new PlatformMiddlewareOperation();
        op.setClusterId(instance.getClusterId());
        op.setInstanceId(instance.getId());
        op.setAction(action);
        op.setStatus("PENDING");
        op.setOperator(operator);
        op.setStartedAt(new Date());
        try {
            operations.insert(op);
        } catch (DuplicateKeyException e) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "该实例已有进行中的操作");
        }
        return op;
    }

    private void finish(PlatformMiddlewareOperation op, String status, String message) {
        op.setStatus(status);
        op.setMessage(message);
        op.setFinishedAt(new Date());
        operations.updateById(op);
    }

    private void persistOperator(String clusterId, MiddlewareOperatorStatusDTO status) {
        PlatformMiddlewareOperator row = operators.selectByClusterAndCode(clusterId, status.getCode());
        Date now = new Date();
        if (row == null) {
            row = new PlatformMiddlewareOperator();
            row.setId("mwo_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
            row.setClusterId(clusterId);
            row.setCode(status.getCode());
            row.setChartVersion(status.getChartVersion());
            row.setStatus(status.isReady() ? "READY" : "FAILED");
            row.setMessage(status.getMessage());
            row.setInstalledAt(now);
            row.setUpdatedAt(now);
            operators.insert(row);
        } else {
            row.setChartVersion(status.getChartVersion());
            row.setStatus(status.isReady() ? "READY" : "FAILED");
            row.setMessage(status.getMessage());
            row.setUpdatedAt(now);
            operators.updateById(row);
        }
    }

    private String toSpecJson(MiddlewareCreateRequest req) {
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("mode", req.getMode());
        spec.put("replicas", req.getReplicas());
        spec.put("cpuRequest", req.getCpuRequest());
        spec.put("cpuLimit", req.getCpuLimit());
        spec.put("memoryRequest", req.getMemoryRequest());
        spec.put("memoryLimit", req.getMemoryLimit());
        spec.put("storageSize", req.getStorageSize());
        spec.put("storageClass", req.getStorageClass());
        spec.put("version", req.getVersion());
        return jsonMapper.writeValueAsString(spec);
    }

    private static String randomPassword() {
        byte[] bytes = new byte[18];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private boolean isChartBacked(String code) {
        return catalog.chartBacked(code);
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static MiddlewareOperatorStatusRequestDTO statusRequest(String clusterId) {
        MiddlewareOperatorStatusRequestDTO req = new MiddlewareOperatorStatusRequestDTO();
        req.setClusterId(clusterId);
        return req;
    }
}
