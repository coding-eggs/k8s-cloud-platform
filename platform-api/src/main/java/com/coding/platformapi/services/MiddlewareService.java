package com.coding.platformapi.services;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.k8s.dto.StorageClassDTO;
import com.coding.common.models.middleware.MiddlewareCatalogItemDTO;
import com.coding.common.models.middleware.MiddlewareConnectionDTO;
import com.coding.common.models.middleware.MiddlewareDetailRequestDTO;
import com.coding.common.models.middleware.MiddlewareInstanceLiveDTO;
import com.coding.common.models.middleware.MiddlewareFormOptionsDTO;
import com.coding.common.models.middleware.MiddlewareCreateRequestDTO;
import com.coding.common.models.middleware.MiddlewareDeleteRequestDTO;
import com.coding.common.models.middleware.MiddlewareModeDTO;
import com.coding.common.models.middleware.MiddlewareOperatorInstallRequestDTO;
import com.coding.common.models.middleware.MiddlewareOperatorStatusDTO;
import com.coding.common.models.middleware.MiddlewareOperatorStatusRequestDTO;
import com.coding.common.models.middleware.MiddlewareScaleRequestDTO;
import com.coding.common.models.middleware.MiddlewareStatusRequestDTO;
import com.coding.common.models.objectstorage.ObjectCredentialDTO;
import com.coding.platformapi.models.ObjectStorageBucketRef;
import com.coding.platformapi.models.ObjectStorageBucketView;
import com.coding.platformapi.models.ObjectStorageCreateBucket;
import com.coding.data.mapper.auth.PlatformTenantNamespaceMapper;
import com.coding.data.mapper.system.PlatformMiddlewareInstanceMapper;
import com.coding.data.mapper.system.PlatformMiddlewareOperationMapper;
import com.coding.data.mapper.system.PlatformMiddlewareOperatorMapper;
import com.coding.data.models.auth.PlatformTenantNamespace;
import com.coding.data.models.system.PlatformMiddlewareInstance;
import com.coding.data.models.system.PlatformMiddlewareOperation;
import com.coding.data.models.system.PlatformMiddlewareOperator;
import com.coding.platformapi.cache.RedisJsonCache;
import com.coding.platformapi.k8s.K8sClient;
import com.coding.platformapi.k8s.K8sMiddlewareClient;
import com.coding.platformapi.middleware.MiddlewareCatalog;
import com.coding.platformapi.models.MiddlewareCreateRequest;
import com.coding.platformapi.models.MiddlewareInstanceDetail;
import com.coding.platformapi.models.MiddlewareDeleteRequest;
import com.coding.platformapi.models.MiddlewareMetricsRequest;
import com.coding.platformapi.models.MiddlewareScaleRequest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.time.Duration;
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
@lombok.extern.slf4j.Slf4j
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
    private final RedisJsonCache cache;
    private final com.coding.platformapi.metrics.MetricsService metrics;
    private final ObjectStorageService objectStorage;
    private final ClusterComponentService components;

    /** 引用守卫：被应用引用的实例不能删 */
    private final ApplicationReferenceGuard referenceGuard;

    public MiddlewareService(PlatformMiddlewareInstanceMapper instances,
                             PlatformMiddlewareOperationMapper operations,
                             PlatformMiddlewareOperatorMapper operators,
                             K8sMiddlewareClient client,
                             MiddlewareCatalog catalog,
                             JsonMapper jsonMapper,
                             K8sClient k8s,
                             PlatformTenantNamespaceMapper allocations,
                             RedisJsonCache cache,
                             com.coding.platformapi.metrics.MetricsService metrics,
                             ObjectStorageService objectStorage,
                             ClusterComponentService components,
                             ApplicationReferenceGuard referenceGuard) {
        this.instances = instances;
        this.operations = operations;
        this.operators = operators;
        this.client = client;
        this.catalog = catalog;
        this.jsonMapper = jsonMapper;
        this.k8s = k8s;
        this.allocations = allocations;
        this.cache = cache;
        this.metrics = metrics;
        this.objectStorage = objectStorage;
        this.components = components;
        this.referenceGuard = referenceGuard;
    }

    // ==================== 目录 ====================

    public List<MiddlewareCatalogItemDTO> catalog(String clusterId, String tenantId) {
        requireCluster(clusterId);
        // Operator 状态要逐个 Operator 查 helm release + CRD + 工作负载，列表/商店卡片都会打到它：
        // 用 Redis 缓存一个短 TTL（安装完成后最多旧 30s，比每次进页面查 6 遍值）
        List<MiddlewareOperatorStatusDTO> stats = cache.get(
                "middleware:operator-status:" + clusterId, Duration.ofSeconds(30),
                new tools.jackson.core.type.TypeReference<List<MiddlewareOperatorStatusDTO>>() {
                },
                () -> client.operatorStatus(statusRequest(clusterId)));
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
                dto.setInstalledChartVersion(s.getInstalledChartVersion());
                dto.setUpgradeAvailable(s.isUpgradeAvailable());
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
        try {
            out.setObjectStorageAvailable(components.seaweedInstalled(clusterId));
        } catch (RuntimeException e) {
            out.setObjectStorageAvailable(false);
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

    public List<PlatformMiddlewareInstance> list(String clusterId, String code, String tenantId, String namespace) {
        requireCluster(clusterId);
        List<PlatformMiddlewareInstance> rows = instances.selectByCluster(clusterId, code).stream()
                .filter(i -> tenantId == null || tenantId.equals(i.getTenantId()))
                .filter(i -> isBlank(namespace) || namespace.equals(i.getNamespace()))
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
        // 输入校验要在建桶之前：越界值先被拒，别先建出一个空桶再被 k8s-server 打回
        if ("milvus".equals(req.getCode()) && req.getEtcdReplicas() != null
                && (req.getEtcdReplicas() < 1 || req.getEtcdReplicas() > 3)) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "etcd 节点数只能是 1~3");
        }

        String name = "mw-" + req.getCode() + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        // Milvus：对象存储只能是外部。PLATFORM 要先把桶和凭据准备好，k8s-server 才拿得到 AK/SK
        StorageGrant grant = "milvus".equals(req.getCode())
                ? prepareObjectStorage(req, operator, tenantId, name) : null;
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
        instance.setStatus("CREATING");
        instance.setTenantId(tenantId);
        instance.setAppId(req.getAppId());
        instance.setAppNodeId(req.getAppNodeId());
        instance.setCreatedBy(operator);
        instance.setCreatedAt(new Date());
        instance.setUpdatedAt(new Date());

        PlatformMiddlewareOperation op = null;
        boolean inserted = false;
        boolean k8sCalled = false;
        try {
            instance.setSpecJson(toSpecJson(req, grant));
            instances.insert(instance);
            inserted = true;
            op = newOperation(instance, "CREATE", operator);
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
            dto.setTls(!Boolean.FALSE.equals(req.getTls()));   // 缺省开
            dto.setIk(Boolean.TRUE.equals(req.getIk()));
            dto.setMetricsEnabled(Boolean.TRUE.equals(req.getMetricsEnabled()));
            dto.setEtcdReplicas(req.getEtcdReplicas());
            dto.setLocalStorageSize(req.getLocalStorageSize());
            if (grant != null) {
                dto.setObjectStorageType(grant.type());
                dto.setObjectStorageEndpoint(grant.endpoint());
                dto.setObjectStorageBucket(grant.bucket());
                dto.setObjectStorageAccessKey(grant.accessKey());
                dto.setObjectStorageSecretKey(grant.secretKey());
                dto.setObjectStorageUseSsl(grant.useSsl());
            }
            dto.setPassword(randomPassword());
            dto.setTenantId(tenantId);
            k8sCalled = true;
            client.create(req.getCode(), dto);
            finish(op, "SUCCESS", null);
        } catch (RuntimeException e) {
            if (inserted) {
                instance.setStatus("FAILED");
                instance.setMessage(e.getMessage());
                instance.setUpdatedAt(new Date());
                // 创建失败 = 实例从未存在：软删台账，否则失败后重试会在列表里留下一条幽灵记录
                //（真集群实测：配额不足 -> 调小规格重试 -> 列表出现两条 MySQL）
                instance.setDeletedAt(new Date());
                instances.updateById(instance);
            }
            if (grant != null && grant.bucketId() != null) {
                if (k8sCalled) {
                    // 不能在这里删桶：网关把传输层异常也包成 CloudPlatformException，CR 可能已经建成、
                    // Pod 正在跑 —— 删桶就是删掉实例唯一的数据。留桶给运维核对（失败的操作记录里有实例与错误）。
                    log.warn("Milvus 实例创建失败，保留对象存储桶 {}（集群侧可能已经建成），请人工核对",
                            grant.bucket());
                } else {
                    // 还没调 k8s = 确定没建成，可以安全回收（deleteBucket 会连带吊销刚发的凭据）
                    deleteBucketQuietly(grant.bucketId(), operator);
                }
            }
            if (op != null) {
                finish(op, "FAILED", e.getMessage());
            }
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
                String scheme = tlsEnabled(instance) ? "https" : "http";
                endpoints.add(line(scheme.toUpperCase() + " 接口", scheme + "://" + svc(ns, name + "-es-http", 9200)));
                endpoints.add(line("Kibana（创建时勾选才有）", scheme + "://" + svc(ns, name + "-kb-http", 5601)));
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
            case "milvus" -> {
                endpoints.add(line("gRPC", svc(ns, name + "-milvus", 19530)));
                endpoints.add(line("指标 / 健康检查", svc(ns, name + "-milvus", 9091)));
                Map<String, Object> spec = parseSpec(instance.getSpecJson());
                String bucket = text(spec.get("objectStorageBucket"));
                String storage = "CUSTOM".equals(text(spec.get("objectStorageType")))
                        ? "自带 S3 " + text(spec.get("objectStorageEndpoint")) + " / 桶 " + bucket
                        : (bucket == null ? "平台对象存储" : "平台桶 " + bucket);
                credentials.add(line("对象存储", storage
                        + "；凭据在 Secret " + name + "-milvus-storage（accesskey / secretkey）"));
                note = "Milvus 默认未开启鉴权；对外暴露前请用命名空间或网格策略控制访问。";
            }
            default -> note = "该中间件暂未提供连接方式说明。";
        }

        MiddlewareConnectionDTO out = new MiddlewareConnectionDTO();
        out.setInstanceName(name);
        out.setNamespace(ns);
        out.setEndpoints(endpoints);
        out.setCredentials(credentials);
        out.setNote(note);
        out.setInfo(connInfoOf(instance));
        return out;
    }

    /**
     * 机器可用的连接信息。**只在用这里**：应用编排的注入会直接消费它，
     * 在别处再拼一遍 Service 名/端口/Secret 键，两处一漂移就注入到不存在的地址。
     */
    private MiddlewareConnectionDTO.ConnInfo connInfoOf(PlatformMiddlewareInstance instance) {
        String ns = instance.getNamespace();
        String name = instance.getName();
        return switch (instance.getCode()) {
            case "redis" -> switch (instance.getMode() == null ? "" : instance.getMode()) {
                case "SENTINEL" -> MiddlewareConnectionDTO.ConnInfo
                        .of("redis", host(ns, name + "-master"), 6379)
                        .secret(name + "-credentials", null, "password").build();
                case "CLUSTER" -> MiddlewareConnectionDTO.ConnInfo
                        .of("redis", host(ns, name + "-leader"), 6379)
                        .secret(name + "-credentials", null, "password").build();
                default -> MiddlewareConnectionDTO.ConnInfo.of("redis", host(ns, name), 6379)
                        .secret(name + "-credentials", null, "password").build();
            };
            case "mysql" -> MiddlewareConnectionDTO.ConnInfo.of("mysql", host(ns, name + "-haproxy"), 3306)
                    .secret(name + "-secrets", "root", "root").build();
            // Kafka 是集群内 PLAINTEXT，无认证
            case "kafka" -> MiddlewareConnectionDTO.ConnInfo
                    .of("kafka", host(ns, name + "-kafka-bootstrap"), 9092).build();
            case "elasticsearch" -> MiddlewareConnectionDTO.ConnInfo
                    .of("elasticsearch", host(ns, name + "-es-http"), 9200)
                    .secret(name + "-es-elastic-user", null, "elastic").build();
            case "postgresql" -> MiddlewareConnectionDTO.ConnInfo
                    .of("postgresql", host(ns, name + "-rw"), 5432)
                    .secret(name + "-app", "username", "password").build();
            case "rabbitmq" -> MiddlewareConnectionDTO.ConnInfo.of("rabbitmq", host(ns, name), 5672)
                    .secret(name + "-default-user", "username", "password").build();
            case "nacos" -> MiddlewareConnectionDTO.ConnInfo.of("nacos", host(ns, "nacos-cs"), 8848).build();
            case "milvus" -> MiddlewareConnectionDTO.ConnInfo.of("milvus", host(ns, name + "-milvus"), 19530)
                    .secret(name + "-milvus-storage", "accesskey", "secretkey").build();
            default -> null;
        };
    }

    private static String host(String namespace, String service) {
        return service + "." + namespace + ".svc.cluster.local";
    }

    /** 实例规格里的 TLS 开关（缺省视为开启；老实例没有这个键） */
    private boolean tlsEnabled(PlatformMiddlewareInstance instance) {
        String raw = instance.getSpecJson();
        if (raw == null || raw.isBlank()) {
            return true;
        }
        try {
            Map<?, ?> spec = jsonMapper.readValue(raw, Map.class);
            return !Boolean.FALSE.equals(spec.get("tls"));
        } catch (RuntimeException e) {
            return true;
        }
    }

    /** 装 IK 成功后回写规格（单向：只置 true，不回退） */
    private void markIkInstalled(PlatformMiddlewareInstance instance) {
        try {
            Map<String, Object> spec = isBlank(instance.getSpecJson())
                    ? new LinkedHashMap<>()
                    : new LinkedHashMap<>(jsonMapper.readValue(instance.getSpecJson(), Map.class));
            spec.put("ik", true);
            instance.setSpecJson(jsonMapper.writeValueAsString(spec));
            instance.setUpdatedAt(new Date());
            instances.updateById(instance);
        } catch (RuntimeException e) {
            // 回写失败不影响已经装上的插件；规格没标记而已
        }
    }

    private static MiddlewareConnectionDTO.Line line(String label, String value) {
        return new MiddlewareConnectionDTO.Line(label, value);
    }

    private static String svc(String namespace, String service, int port) {
        return service + "." + namespace + ".svc.cluster.local:" + port;
    }

    /**
     * 详情页的聚合：台账 + 规格 + 连接方式 + 集群里的实时对象。
     * 集群读失败不整体失败 —— 台账和连接方式仍然有值，实时部分留空并带原因。
     */
    public MiddlewareInstanceDetail detail(String instanceId, String tenantId) {
        PlatformMiddlewareInstance instance = requireInstance(instanceId, tenantId);
        // 台账的 status 只在创建时写过 CREATING，之后没人回写（列表是每次现读的）。
        // 详情页也必须现读一次，否则实例已经跑起来了还显示「创建中」。
        refreshGroup(java.util.List.of(instance));
        MiddlewareInstanceDetail out = new MiddlewareInstanceDetail();
        out.setInstance(instance);
        out.setSpec(parseSpec(instance.getSpecJson()));
        out.setConnection(connection(instanceId, tenantId));
        try {
            MiddlewareDetailRequestDTO req = new MiddlewareDetailRequestDTO();
            req.setClusterId(instance.getClusterId());
            req.setCode(instance.getCode());
            req.setNamespace(instance.getNamespace());
            req.setName(instance.getName());
            req.setInstanceId(instance.getId());
            req.setTenantId(instance.getTenantId());
            out.setLive(client.detail(instance.getCode(), req));
        } catch (RuntimeException e) {
            out.setLiveError(e.getMessage());
        }
        return out;
    }

    public List<PlatformMiddlewareOperation> operations(String instanceId, String tenantId) {
        requireInstance(instanceId, tenantId);
        return operations.selectByInstance(instanceId);
    }

    /**
     * 实例的监控曲线（CPU / 内存 / 网络 / 磁盘），一次返回四张图。
     *
     * <p>Pod 集合与状态判定同口径 —— 由 k8s-server 按适配器 selector 读出来，再拼成 pod 名正则交给 Prometheus。
     * 不按工作负载名猜：一个实例可能有多个工作负载（ES+Kibana、Redis 哨兵两个 CR、Kafka 的 StrimziPodSet）。
     */
    public com.coding.platformapi.metrics.dto.MiddlewareMetricsResponse metrics(MiddlewareMetricsRequest req) {
        PlatformMiddlewareInstance instance = requireInstance(req.getInstanceId(), req.getTenantId());
        long end = req.getEnd() == null ? System.currentTimeMillis() / 1000 : req.getEnd();
        long start = req.getStart() == null || req.getStart() >= end ? end - 1800 : req.getStart();
        List<String> pods = podNamesOf(instance);
        if (pods.isEmpty()) {
            return com.coding.platformapi.metrics.dto.MiddlewareMetricsResponse.empty();
        }
        String regex = "^(?:" + pods.stream().map(MiddlewareService::escapeRegex)
                .collect(java.util.stream.Collectors.joining("|")) + ")$";
        var base = metrics.middlewareInstance(instance.getClusterId(), instance.getNamespace(), regex, start, end);
        // 业务指标默认不采：只有创建时打开了开关（spec_json.metricsEnabled）才去查
        if (!Boolean.TRUE.equals(parseSpec(instance.getSpecJson()).get("metricsEnabled"))) {
            return base.withBusiness(List.of());
        }
        // 业务指标：清单在 MiddlewareCatalog 里按 code 声明；没有声明就只回四张资源图
        List<com.coding.platformapi.metrics.dto.MiddlewareMetricsResponse.BusinessMetric> business =
                catalog.require(instance.getCode()).metrics().stream()
                        .map(def -> new com.coding.platformapi.metrics.dto.MiddlewareMetricsResponse.BusinessMetric(
                                def.key(), def.label(),
                                metrics.businessMetric(instance.getClusterId(), instance.getNamespace(), regex,
                                        def.query(), def.unit(), start, end),
                                def.kind().name(), def.goodWhenNonZero(), def.goodLabel(), def.badLabel()))
                        .toList();
        return base.withBusiness(business);
    }

    /** 该实例当前在集群里的 Pod 名（读不到就当没有，图表显示「无数据」）。 */
    private List<String> podNamesOf(PlatformMiddlewareInstance instance) {
        try {
            MiddlewareDetailRequestDTO req = new MiddlewareDetailRequestDTO();
            req.setClusterId(instance.getClusterId());
            req.setCode(instance.getCode());
            req.setNamespace(instance.getNamespace());
            req.setName(instance.getName());
            req.setInstanceId(instance.getId());
            req.setTenantId(instance.getTenantId());
            MiddlewareInstanceLiveDTO live = client.detail(instance.getCode(), req);
            if (live == null || live.getPods() == null) {
                return List.of();
            }
            return live.getPods().stream().map(MiddlewareInstanceLiveDTO.PodView::getName)
                    .filter(Objects::nonNull).toList();
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    /** Prometheus 是 RE2：没有 {@code \Q..\E}，这里手工转义元字符（Pod 名本是 DNS label）。 */
    private static String escapeRegex(String s) {
        return s.replaceAll("([\\\\.^$*+?()\\[\\]{}|])", "\\\\$1");
    }

    /** 只改显示名（台账字段），不动集群对象；重名不拦（显示名不唯一）。 */
    public PlatformMiddlewareInstance rename(String instanceId, String displayName, String tenantId) {
        PlatformMiddlewareInstance instance = requireInstance(instanceId, tenantId);
        if (isBlank(displayName)) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "显示名不能为空");
        }
        instance.setDisplayName(displayName.trim());
        instance.setUpdatedAt(new Date());
        instances.updateById(instance);
        return instance;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseSpec(String specJson) {
        if (specJson == null || specJson.isBlank()) {
            return Map.of();
        }
        try {
            return jsonMapper.readValue(specJson, Map.class);
        } catch (RuntimeException e) {
            return Map.of();
        }
    }

    public Long scale(MiddlewareScaleRequest req, String operator, String tenantId) {
        PlatformMiddlewareInstance instance = requireInstance(req.getInstanceId(), tenantId);
        // 业务指标是单向开关：已经开着的实例再提交也不重复改 CR（避免白白滚动重启一次）
        boolean enableMetrics = Boolean.TRUE.equals(req.getMetricsEnabled())
                && !Boolean.TRUE.equals(parseSpec(instance.getSpecJson()).get("metricsEnabled"));
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
            dto.setIk(Boolean.TRUE.equals(req.getIk()));
            dto.setInstanceId(instance.getId());
            dto.setMetricsEnabled(enableMetrics);
            dto.setTenantId(tenantId);
            if (enableMetrics && isChartBacked(instance.getCode())) {
                // chart 实例（Nacos）重新 upgrade 需要完整规格：从台账补齐（请求里可能没带）。
                // ⚠️ 只对 chart 实例做：给 Operator 实例补上 storageSize 会被 k8s-server 当成"扩容存储"直接拒掉
                Map<String, Object> spec = parseSpec(instance.getSpecJson());
                if (dto.getReplicas() == null && spec.get("replicas") instanceof Number n) {
                    dto.setReplicas(n.intValue());
                }
                if (isBlank(dto.getCpuRequest())) dto.setCpuRequest(text(spec.get("cpuRequest")));
                if (isBlank(dto.getCpuLimit())) dto.setCpuLimit(text(spec.get("cpuLimit")));
                if (isBlank(dto.getMemoryRequest())) dto.setMemoryRequest(text(spec.get("memoryRequest")));
                if (isBlank(dto.getMemoryLimit())) dto.setMemoryLimit(text(spec.get("memoryLimit")));
                if (isBlank(dto.getStorageSize())) dto.setStorageSize(text(spec.get("storageSize")));
                dto.setStorageClass(text(spec.get("storageClass")));
            }
            client.scale(instance.getCode(), dto);
            if (Boolean.TRUE.equals(req.getIk())) {
                markIkInstalled(instance);
            }
            if (enableMetrics) {
                markMetricsEnabled(instance);
            }
            finish(op, "SUCCESS", null);
        } catch (RuntimeException e) {
            finish(op, "FAILED", e.getMessage());
            throw e;
        }
        return op.getId();
    }

    /** 台账里记下「业务指标已开启」——详情页据此展示业务指标、metrics 接口据此去查。 */
    @SuppressWarnings("unchecked")
    private void markMetricsEnabled(PlatformMiddlewareInstance instance) {
        try {
            Map<String, Object> spec = isBlank(instance.getSpecJson())
                    ? new LinkedHashMap<>()
                    : new LinkedHashMap<>(jsonMapper.readValue(instance.getSpecJson(), Map.class));
            spec.put("metricsEnabled", true);
            instance.setSpecJson(jsonMapper.writeValueAsString(spec));
            instance.setUpdatedAt(new Date());
            instances.updateById(instance);
        } catch (RuntimeException e) {
            // 回写失败不影响已经开起来的采集；下次编辑会再尝试
        }
    }

    private static String text(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    public Long delete(MiddlewareDeleteRequest req, String operator, String tenantId) {
        PlatformMiddlewareInstance instance = requireInstance(req.getInstanceId(), tenantId);
        // 被应用引用时拒绝：删掉它会让正在跑的应用连不上库
        referenceGuard.assertNotReferenced(instance.getId());
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

            // 实例已经删掉，再清外部对象存储（仅平台桶；用户自带的桶不碰）；失败只记日志
            Map<String, Object> spec = parseSpec(instance.getSpecJson());
            if (!req.isKeepPvc() && spec.get("objectStorageBucketId") instanceof String bucketId
                    && !bucketId.isBlank()) {
                deleteBucketQuietly(bucketId, operator);
            }

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

    /** 该中间件的默认形态（画布建实例时没选形态就用它）。 */
    public String defaultMode(String code) {
        var item = catalog.require(code);
        return item.modes().isEmpty() ? null : item.modes().get(0).mode();
    }

    /** 现读一个实例的状态（应用编排等它 Ready 用）；实例已被删返回 DELETED。 */
    public String statusOf(String instanceId) {
        PlatformMiddlewareInstance instance = instances.selectByPrimaryKey(instanceId);
        if (instance == null || instance.getDeletedAt() != null) {
            return "DELETED";
        }
        refreshGroup(java.util.List.of(instance));
        return instance.getStatus();
    }

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
        return toSpecJson(req, null);
    }

    private String toSpecJson(MiddlewareCreateRequest req, StorageGrant grant) {
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
        // 连接方式要按它决定 http/https（只有 ES/Kibana 用得到）
        spec.put("tls", !Boolean.FALSE.equals(req.getTls()));
        // 业务指标开关（默认关）：详情页据此决定是否展示业务指标、接口是否去查
        spec.put("metricsEnabled", Boolean.TRUE.equals(req.getMetricsEnabled()));
        // IK 分词器（只有 ES 用得到）：编辑弹窗靠它回显「已安装」
        spec.put("ik", Boolean.TRUE.equals(req.getIk()));
        if (grant != null) {
            spec.put("etcdReplicas", req.getEtcdReplicas());
            spec.put("localStorageSize", req.getLocalStorageSize());
            spec.put("objectStorageType", grant.type());
            spec.put("objectStorageBucket", grant.bucket());
            spec.put("objectStorageBucketId", grant.bucketId());
            spec.put("objectStorageEndpoint", grant.endpoint());
            // AK/SK 不落库
        }
        return jsonMapper.writeValueAsString(spec);
    }

    /** Milvus 外部对象存储的下发参数（AK/SK 只在内存里过一遍，不落库）。 */
    private record StorageGrant(String type, String endpoint, String bucket, String bucketId,
                                String accessKey, String secretKey, boolean useSsl) {
    }

    /** Milvus 的对象存储：PLATFORM 建桶 + 发凭据，CUSTOM 直接用请求里的参数。 */
    private StorageGrant prepareObjectStorage(MiddlewareCreateRequest req, String operator,
                                              String tenantId, String name) {
        String type = req.getObjectStorageType();
        if (isBlank(type)) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "请选择对象存储来源");
        }
        if ("CUSTOM".equals(type)) {
            if (isBlank(req.getObjectStorageEndpoint()) || isBlank(req.getObjectStorageBucket())
                    || isBlank(req.getObjectStorageAccessKey()) || isBlank(req.getObjectStorageSecretKey())) {
                throw new CloudPlatformException(EnumResponseType.ERROR,
                        "自带 S3 需要填 endpoint / 桶名 / AccessKey / SecretKey");
            }
            return new StorageGrant(type, hostPort(req.getObjectStorageEndpoint()),
                    req.getObjectStorageBucket(), null,
                    req.getObjectStorageAccessKey(), req.getObjectStorageSecretKey(),
                    Boolean.TRUE.equals(req.getObjectStorageUseSsl()));
        }
        if (!"PLATFORM".equals(type)) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "不支持的对象存储来源：" + type);
        }
        // 平台桶必须挂在租户名下：租户作用域统一取顶栏（资源上下文 store）的租户，前端每个请求都带 tenantId；
        // 平台视图下顶栏会 ensureDefaults 到第一个可用租户，确实没有租户时 UI 直接禁用「平台对象存储」。
        if (isBlank(tenantId)) {
            throw new CloudPlatformException(EnumResponseType.ERROR,
                    "平台对象存储的桶必须归属某个租户，请先在顶栏选择租户（或改用「自带 S3」）");
        }
        if (!components.seaweedInstalled(req.getClusterId())) {
            throw new CloudPlatformException(EnumResponseType.ERROR,
                    "对象存储组件尚未安装：请先在「集群组件」里安装对象存储");
        }
        ObjectStorageCreateBucket body = new ObjectStorageCreateBucket();
        body.setClusterId(req.getClusterId());
        body.setTenantId(tenantId);
        body.setName("milvus-" + name);
        body.setDisplayName("Milvus " + name);
        long quota = quantityBytes(req.getStorageSize());
        // 0 = 没填/识别不了；createBucket 对 <=0 会直接拒，所以转成 null（= 桶不限容量）
        body.setQuotaBytes(quota <= 0 ? null : quota);
        ObjectStorageBucketView bucket = objectStorage.createBucket(body, operator);
        ObjectStorageBucketRef ref = new ObjectStorageBucketRef();
        ref.setBucketId(bucket.getId());
        ObjectCredentialDTO credential = objectStorage.issueCredential(ref, operator);
        String raw = objectStorage.s3Endpoint(req.getClusterId());
        // CR 里的 endpoint 只能是 host:port（operator 自己按 useSSL 补默认端口）；scheme 只用来定 TLS
        return new StorageGrant(type, hostPort(raw), bucket.getBucket(), bucket.getId(),
                credential.getAccessKey(), credential.getSecretKey(), raw.startsWith("https://"));
    }

    /** "http://host:8333/x" / "host:8333" → "host:8333"（与 k8s-server 适配器的规整规则一致）。 */
    static String hostPort(String endpoint) {
        String s = endpoint == null ? "" : endpoint.trim();
        int scheme = s.indexOf("://");
        if (scheme >= 0) {
            s = s.substring(scheme + 3);
        }
        int slash = s.indexOf('/');
        if (slash >= 0) {
            s = s.substring(0, slash);
        }
        return s;
    }

    /** "20Gi" / "512Mi" → 字节（桶配额要 long）；识别不了返回 0（= 不限）。 */
    static long quantityBytes(String quantity) {
        if (isBlank(quantity)) {
            return 0;
        }
        String s = quantity.trim();
        long mul = 1;
        if (s.endsWith("Gi")) {
            mul = 1024L * 1024 * 1024;
            s = s.substring(0, s.length() - 2);
        } else if (s.endsWith("Mi")) {
            mul = 1024L * 1024;
            s = s.substring(0, s.length() - 2);
        } else if (s.endsWith("Ki")) {
            mul = 1024L;
            s = s.substring(0, s.length() - 2);
        }
        try {
            return (long) (Double.parseDouble(s.trim()) * mul);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void deleteBucketQuietly(String bucketId, String operator) {
        try {
            ObjectStorageBucketRef ref = new ObjectStorageBucketRef();
            ref.setBucketId(bucketId);
            objectStorage.deleteBucket(ref, operator);
        } catch (RuntimeException e) {
            log.warn("删除 Milvus 对象存储桶 {} 失败（需在对象存储页手工清理）：{}", bucketId, e.getMessage());
        }
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
