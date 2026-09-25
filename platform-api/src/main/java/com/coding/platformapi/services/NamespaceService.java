package com.coding.platformapi.services;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.k8s.dto.LimitRangeDTO;
import com.coding.common.models.k8s.dto.LimitRangeItemDTO;
import com.coding.common.models.k8s.dto.NamespaceDTO;
import com.coding.common.models.k8s.dto.ResourcePairDTO;
import com.coding.common.models.k8s.dto.ResourceQuotaDTO;
import com.coding.common.utils.K8sNaming;
import com.coding.data.mapper.auth.PlatformTenantMapper;
import com.coding.data.mapper.auth.PlatformTenantNamespaceMapper;
import com.coding.data.mapper.k8s.K8sClusterMapper;
import com.coding.data.models.auth.PlatformTenant;
import com.coding.data.models.auth.PlatformTenantNamespace;
import com.coding.data.models.k8s.K8sCluster;
import com.coding.platformapi.k8s.K8sResourceClient;
import com.coding.platformapi.models.NamespaceLimitRangeUpsertRequest;
import com.coding.platformapi.models.NamespaceQuotaUpsertRequest;
import com.coding.platformapi.models.NamespaceUpsertRequest;
import com.coding.platformapi.models.NamespaceView;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 命名空间管理：K8s 命名空间（一等公民资源 {@code /admin/namespaces}）× 分配表合并视图 + 增删改查。
 * 数据处理 / 业务规则全在本层；k8s-server 只执行 K8s 动作（列 / 取 / 建 / 改 / 删 / yaml）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NamespaceService {

    /** 平台管理标签（managed-by 由 k8s-server 侧 converter 盖章，本层只读判定） */
    private static final String MANAGED_BY_LABEL = "app.kubernetes.io/managed-by";
    private static final String MANAGED_BY_VALUE = "k8s-cloud-platform";

    /** 平台单份约定：命名空间约束资源（配额 / 限制范围）对象名固定 default（见 QUOTA_NAME_NOT_DEFAULT） */
    private static final String SINGLE_NAME = "default";

    /** 与 k8s-core {@code CoreV1LimitRangeConverter.MODELED_TYPES} 同值（platform-api 不依赖 k8s-core，故本地重复声明，改一处需同步） */
    private static final List<String> MODELED_LIMIT_TYPES = List.of("Container", "Pod", "PersistentVolumeClaim");

    private final K8sResourceClient k8s;

    private final K8sClusterMapper clusterMapper;

    private final PlatformTenantNamespaceMapper allocationMapper;

    private final PlatformTenantMapper tenantMapper;

    /**
     * 集群命名空间视图：K8s 列表 + 分配信息叠加（哪个租户占用了该 ns），按名字升序。
     */
    public List<NamespaceView> list(String clusterId) {
        requireCluster(clusterId);
        Map<String, String> allocatedTenant = allocatedTenant(clusterId);
        return k8s.list(query(clusterId)).stream()
                .sorted(Comparator.comparing(NamespaceDTO::getName))
                .map(ns -> toView(ns, allocatedTenant))
                .toList();
    }

    /**
     * 单个命名空间视图：K8s 取 + 分配信息叠加（与 list 同加工）。
     */
    public NamespaceView get(String clusterId, String namespace) {
        requireCluster(clusterId);
        NamespaceDTO ns = k8s.get(dto(clusterId, namespace));
        if (ns == null) {
            throw new CloudPlatformException(EnumResponseType.RESOURCE_NOT_EXIST, "命名空间不存在: " + namespace);
        }
        return toView(ns, allocatedTenant(clusterId));
    }

    /**
     * 命名空间 YAML（只读展示）。
     */
    public String yaml(String clusterId, String namespace) {
        requireCluster(clusterId);
        return k8s.yaml(dto(clusterId, namespace));
    }

    /**
     * 创建命名空间：名字合法 + 不存在 → k8s create（managed-by 由 converter 盖章，本层不打）。
     */
    public NamespaceDTO create(NamespaceUpsertRequest req) {
        requireCluster(req.getClusterId());
        K8sNaming.validateRawName(req.getName(), "", "命名空间");
        if (k8s.get(dto(req.getClusterId(), req.getName())) != null) {
            throw new CloudPlatformException(EnumResponseType.RESOURCE_EXIST,
                    "命名空间「" + req.getName() + "」已存在");
        }
        NamespaceDTO dto = toDto(req);
        NamespaceDTO created = k8s.create(dto);
        log.info("集群 {} 创建命名空间 {}", req.getClusterId(), req.getName());
        return created;
    }

    /**
     * 更新命名空间（描述/标签）：仅平台管理（managed-by 标签）可编辑。
     */
    public NamespaceDTO update(NamespaceUpsertRequest req) {
        requireCluster(req.getClusterId());
        NamespaceDTO live = k8s.get(dto(req.getClusterId(), req.getName()));
        if (live == null) {
            throw new CloudPlatformException(EnumResponseType.RESOURCE_NOT_EXIST,
                    "命名空间不存在: " + req.getName());
        }
        if (!isManaged(live.getLabels())) {
            throw new CloudPlatformException(EnumResponseType.BEAN_VALIDATION_EXCEPTION,
                    "仅平台创建的命名空间（带 managed-by 标签）可编辑");
        }
        NamespaceDTO dto = toDto(req);
        NamespaceDTO updated = k8s.update(dto);
        log.info("集群 {} 更新命名空间 {}", req.getClusterId(), req.getName());
        return updated;
    }

    /**
     * 删除命名空间：仅当平台管理（managed-by 标签）且未分配给任何租户。
     */
    public void delete(String clusterId, String namespace) {
        requireCluster(clusterId);
        NamespaceDTO ns = k8s.get(dto(clusterId, namespace));
        if (ns == null) {
            throw new CloudPlatformException(EnumResponseType.RESOURCE_NOT_EXIST, "命名空间不存在: " + namespace);
        }
        if (!isManaged(ns.getLabels())) {
            throw new CloudPlatformException(EnumResponseType.BEAN_VALIDATION_EXCEPTION, "仅平台创建的命名空间（带 managed-by 标签）可删除");
        }
        boolean allocated = allocationMapper.listByCluster(clusterId).stream()
                .anyMatch(a -> a.getNamespace().equals(namespace));
        if (allocated) {
            throw new CloudPlatformException(EnumResponseType.BEAN_VALIDATION_EXCEPTION, "该命名空间仍分配给租户，请先取消分配再删除");
        }
        k8s.delete(dto(clusterId, namespace));
        log.info("集群 {} 删除命名空间 {}", clusterId, namespace);
    }

    /**
     * 查询命名空间配额：未配置返回 null（前端「未配置」）；存在则叠加多份告警旗标（D4，平台只管理 default 那份）。
     */
    public ResourceQuotaDTO quotaGet(String clusterId, String namespace) {
        requireManagedNamespace(clusterId, namespace);
        ResourceQuotaDTO found = k8s.get(quotaDto(clusterId, namespace));
        if (found == null) {
            return null;
        }
        ResourceQuotaDTO listQuery = quotaDto(clusterId, namespace);
        List<ResourceQuotaDTO> all = k8s.list(listQuery);
        if (all != null && all.size() > 1) {
            found.setMultiple(true);
        }
        return found;
    }

    /**
     * upsert 配额（对象名固定 default）：入参非 default 且非空 → 拒；空白 → 静默改写为 default。
     * get 命中则 update，否则 create（重试幂等 —— 前端错误恢复流程依赖重复提交安全）。
     */
    public ResourceQuotaDTO quotaUpsert(NamespaceQuotaUpsertRequest req) {
        requireManagedNamespace(req.getClusterId(), req.getNamespace());
        ResourceQuotaDTO quota = req.getQuota();
        forceSingleName(quota == null ? null : quota.getName());
        if (quota == null) {
            quota = new ResourceQuotaDTO();
        }
        quota.setClusterId(req.getClusterId());
        quota.setNamespace(req.getNamespace());
        quota.setName(SINGLE_NAME);
        ResourceQuotaDTO live = k8s.get(quotaDto(req.getClusterId(), req.getNamespace()));
        ResourceQuotaDTO saved = live == null ? k8s.create(quota) : k8s.update(quota);
        log.info("集群 {} 命名空间 {} {}配额", req.getClusterId(), req.getNamespace(),
                live == null ? "创建" : "更新");
        return saved;
    }

    /** 删除配额：不存在为 no-op（幂等）。 */
    public void quotaDelete(String clusterId, String namespace) {
        requireManagedNamespace(clusterId, namespace);
        ResourceQuotaDTO live = k8s.get(quotaDto(clusterId, namespace));
        if (live == null) {
            return;
        }
        k8s.delete(quotaDto(clusterId, namespace));
        log.info("集群 {} 命名空间 {} 删除配额", clusterId, namespace);
    }

    /**
     * 查询命名空间限制范围：未配置返回 null；存在则叠加多份告警旗标（同 {@link #quotaGet}）。
     */
    public LimitRangeDTO limitRangeGet(String clusterId, String namespace) {
        requireManagedNamespace(clusterId, namespace);
        LimitRangeDTO found = k8s.get(limitRangeDto(clusterId, namespace));
        if (found == null) {
            return null;
        }
        List<LimitRangeDTO> all = k8s.list(limitRangeDto(clusterId, namespace));
        if (all != null && all.size() > 1) {
            found.setMultiple(true);
        }
        return found;
    }

    /**
     * upsert 限制范围（对象名固定 default，同 {@link #quotaUpsert}）：先跑业务校验 {@link #validateLimitRange}
     * （类型集 + 同类型内数值序），业务规则归 platform-api，k8s-server 零逻辑。
     */
    public LimitRangeDTO limitRangeUpsert(NamespaceLimitRangeUpsertRequest req) {
        requireManagedNamespace(req.getClusterId(), req.getNamespace());
        LimitRangeDTO lr = req.getLimitRange();
        forceSingleName(lr == null ? null : lr.getName());
        if (lr == null) {
            lr = new LimitRangeDTO();
        }
        validateLimitRange(lr);
        lr.setClusterId(req.getClusterId());
        lr.setNamespace(req.getNamespace());
        lr.setName(SINGLE_NAME);
        LimitRangeDTO live = k8s.get(limitRangeDto(req.getClusterId(), req.getNamespace()));
        LimitRangeDTO saved = live == null ? k8s.create(lr) : k8s.update(lr);
        log.info("集群 {} 命名空间 {} {}限制范围", req.getClusterId(), req.getNamespace(),
                live == null ? "创建" : "更新");
        return saved;
    }

    /** 删除限制范围：不存在为 no-op（幂等）。 */
    public void limitRangeDelete(String clusterId, String namespace) {
        requireManagedNamespace(clusterId, namespace);
        LimitRangeDTO live = k8s.get(limitRangeDto(clusterId, namespace));
        if (live == null) {
            return;
        }
        k8s.delete(limitRangeDto(clusterId, namespace));
        log.info("集群 {} 命名空间 {} 删除限制范围", clusterId, namespace);
    }

    /** 平台级约束操作前置：集群存在 + 命名空间存在 + 平台拥有（D3 同源策略） */
    private void requireManagedNamespace(String clusterId, String namespace) {
        requireCluster(clusterId);
        NamespaceDTO ns = k8s.get(dto(clusterId, namespace));
        if (ns == null) {
            throw new CloudPlatformException(EnumResponseType.RESOURCE_NOT_EXIST, "命名空间不存在: " + namespace);
        }
        if (!isManaged(ns.getLabels())) {
            throw new CloudPlatformException(EnumResponseType.BEAN_VALIDATION_EXCEPTION,
                    "仅平台创建的命名空间可设置配额/限制范围");
        }
    }

    /** 入参名字非 default 且非空白 → 拒（10023）；空白 / null → 交由调用方静默改写为 default */
    private void forceSingleName(String incomingName) {
        if (StringUtils.hasText(incomingName) && !SINGLE_NAME.equals(incomingName)) {
            throw new CloudPlatformException(EnumResponseType.QUOTA_NAME_NOT_DEFAULT,
                    EnumResponseType.QUOTA_NAME_NOT_DEFAULT.getMsg() + "，收到: " + incomingName);
        }
    }

    /**
     * 业务校验在 platform-api（k8s-server 零逻辑）：类型集 + 同类型内数值序。
     * 任一数值项为 null = 未填 = 不约束该比较，跳过。
     */
    private void validateLimitRange(LimitRangeDTO lr) {
        List<LimitRangeItemDTO> items = lr.getLimits() == null ? List.of() : lr.getLimits();
        for (LimitRangeItemDTO it : items) {
            if (!MODELED_LIMIT_TYPES.contains(it.getType())) {
                throw new CloudPlatformException(EnumResponseType.LIMIT_RANGE_TYPE_UNSUPPORTED,
                        EnumResponseType.LIMIT_RANGE_TYPE_UNSUPPORTED.getMsg() + "，收到: " + it.getType());
            }
            requireOrder(it.getMax(), it.getMin(), it.getType(), "cpu", ResourcePairDTO::getCpu, "max 不得小于 min");
            requireOrder(it.getMax(), it.getMin(), it.getType(), "memory", ResourcePairDTO::getMemory, "max 不得小于 min");
            requireOrder(it.getDefaultValue(), it.getDefaultRequest(), it.getType(), "cpu",
                    ResourcePairDTO::getCpu, "defaultRequest 不得大于 default");
            requireOrder(it.getDefaultValue(), it.getDefaultRequest(), it.getType(), "memory",
                    ResourcePairDTO::getMemory, "defaultRequest 不得大于 default");
            requireRatioAtLeastOne(it.getMaxLimitRequestRatio(), it.getType(), "cpu", ResourcePairDTO::getCpu);
            requireRatioAtLeastOne(it.getMaxLimitRequestRatio(), it.getType(), "memory", ResourcePairDTO::getMemory);
        }
    }

    /** 同项同资源 bigger ≥ smaller；任一侧为 null → 跳过（未填 = 不约束） */
    private void requireOrder(ResourcePairDTO pairBigger, ResourcePairDTO pairSmaller, String type,
                              String resource, Function<ResourcePairDTO, BigDecimal> field, String reason) {
        if (pairBigger == null || pairSmaller == null) {
            return;
        }
        BigDecimal bigger = field.apply(pairBigger);
        BigDecimal smaller = field.apply(pairSmaller);
        if (bigger == null || smaller == null || bigger.compareTo(smaller) >= 0) {
            return;
        }
        throw new CloudPlatformException(EnumResponseType.LIMIT_RANGE_VALUE_INVALID,
                EnumResponseType.LIMIT_RANGE_VALUE_INVALID.getMsg() + "（" + type + " " + resource + " " + reason + "）");
    }

    /** maxLimitRequestRatio 该资源项不得 < 1 */
    private void requireRatioAtLeastOne(ResourcePairDTO ratio, String type, String resource,
                                        Function<ResourcePairDTO, BigDecimal> field) {
        if (ratio == null) {
            return;
        }
        BigDecimal value = field.apply(ratio);
        if (value != null && value.compareTo(BigDecimal.ONE) < 0) {
            throw new CloudPlatformException(EnumResponseType.LIMIT_RANGE_VALUE_INVALID,
                    EnumResponseType.LIMIT_RANGE_VALUE_INVALID.getMsg() + "（" + type + " " + resource
                            + " maxLimitRequestRatio 不得小于 1）");
        }
    }

    private ResourceQuotaDTO quotaDto(String clusterId, String namespace) {
        ResourceQuotaDTO d = new ResourceQuotaDTO();
        d.setClusterId(clusterId);
        d.setNamespace(namespace);
        d.setName(SINGLE_NAME);
        return d;
    }

    private LimitRangeDTO limitRangeDto(String clusterId, String namespace) {
        LimitRangeDTO d = new LimitRangeDTO();
        d.setClusterId(clusterId);
        d.setNamespace(namespace);
        d.setName(SINGLE_NAME);
        return d;
    }

    /** clusterId → (namespace → 租户名) 分配表叠加 */
    private Map<String, String> allocatedTenant(String clusterId) {
        Map<String, String> allocatedTenant = new HashMap<>();
        for (PlatformTenantNamespace tenantNamespace : allocationMapper.listByCluster(clusterId)) {
            PlatformTenant t = tenantMapper.selectByPrimaryKey(tenantNamespace.getTenantId());
            allocatedTenant.putIfAbsent(tenantNamespace.getNamespace(), t != null ? t.getName() : tenantNamespace.getTenantId());
        }
        return allocatedTenant;
    }

    private NamespaceView toView(NamespaceDTO ns, Map<String, String> allocatedTenant) {
        NamespaceView v = new NamespaceView();
        v.setName(ns.getName());
        v.setPhase(ns.getPhase());
        v.setCreationTimestamp(ns.getCreationTimestamp());
        v.setManagedBy(isManaged(ns.getLabels()));
        v.setAllocatedTenantName(allocatedTenant.get(ns.getName()));
        v.setDescription(ns.getDescription());
        v.setLabels(ns.getLabels());
        return v;
    }

    private NamespaceDTO query(String clusterId) {
        NamespaceDTO q = new NamespaceDTO();
        q.setClusterId(clusterId);
        return q;
    }

    private NamespaceDTO dto(String clusterId, String name) {
        NamespaceDTO d = new NamespaceDTO();
        d.setClusterId(clusterId);
        d.setName(name);
        return d;
    }

    private NamespaceDTO toDto(NamespaceUpsertRequest req) {
        NamespaceDTO dto = new NamespaceDTO();
        dto.setClusterId(req.getClusterId());
        dto.setName(req.getName());
        dto.setDescription(req.getDescription());
        dto.setLabels(req.getLabels());
        return dto;
    }

    private boolean isManaged(Map<String, String> labels) {
        return labels != null && MANAGED_BY_VALUE.equals(labels.get(MANAGED_BY_LABEL));
    }

    private void requireCluster(String clusterId) {
        K8sCluster cluster = clusterMapper.selectByPrimaryKey(clusterId);
        if (cluster == null || cluster.getDeletedAt() != null) {
            throw new CloudPlatformException(EnumResponseType.CLUSTER_NOT_EXIST);
        }
    }
}
