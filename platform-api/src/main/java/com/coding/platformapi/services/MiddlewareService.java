package com.coding.platformapi.services;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.middleware.MiddlewareCatalogItemDTO;
import com.coding.common.models.middleware.MiddlewareCreateRequestDTO;
import com.coding.common.models.middleware.MiddlewareDeleteRequestDTO;
import com.coding.common.models.middleware.MiddlewareModeDTO;
import com.coding.common.models.middleware.MiddlewareOperatorInstallRequestDTO;
import com.coding.common.models.middleware.MiddlewareOperatorStatusDTO;
import com.coding.common.models.middleware.MiddlewareOperatorStatusRequestDTO;
import com.coding.common.models.middleware.MiddlewareScaleRequestDTO;
import com.coding.data.mapper.system.PlatformMiddlewareInstanceMapper;
import com.coding.data.mapper.system.PlatformMiddlewareOperationMapper;
import com.coding.data.mapper.system.PlatformMiddlewareOperatorMapper;
import com.coding.data.models.system.PlatformMiddlewareInstance;
import com.coding.data.models.system.PlatformMiddlewareOperation;
import com.coding.data.models.system.PlatformMiddlewareOperator;
import com.coding.platformapi.k8s.K8sMiddlewareClient;
import com.coding.platformapi.middleware.MiddlewareCatalog;
import com.coding.platformapi.models.MiddlewareCreateRequest;
import com.coding.platformapi.models.MiddlewareDeleteRequest;
import com.coding.platformapi.models.MiddlewareScaleRequest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    public MiddlewareService(PlatformMiddlewareInstanceMapper instances,
                             PlatformMiddlewareOperationMapper operations,
                             PlatformMiddlewareOperatorMapper operators,
                             K8sMiddlewareClient client,
                             MiddlewareCatalog catalog,
                             JsonMapper jsonMapper) {
        this.instances = instances;
        this.operations = operations;
        this.operators = operators;
        this.client = client;
        this.catalog = catalog;
        this.jsonMapper = jsonMapper;
    }

    // ==================== 目录 ====================

    public List<MiddlewareCatalogItemDTO> catalog(String clusterId) {
        List<MiddlewareOperatorStatusDTO> stats = client.operatorStatus(statusRequest(clusterId));
        List<PlatformMiddlewareInstance> all = instances.selectByCluster(clusterId, null);

        return catalog.items().stream().map(item -> {
            MiddlewareCatalogItemDTO dto = new MiddlewareCatalogItemDTO();
            dto.setCode(item.code());
            dto.setName(item.name());
            dto.setDescription(item.description());
            dto.setModes(item.modes().stream()
                    .map(m -> new MiddlewareModeDTO(m.mode(), m.label(), m.minReplicas(), m.help()))
                    .toList());
            stats.stream().filter(s -> item.code().equals(s.getCode())).findFirst().ifPresent(s -> {
                dto.setOperatorInstalled(s.isInstalled());
                dto.setOperatorReady(s.isReady());
                dto.setChartVersion(s.getChartVersion());
                dto.setOperatorMessage(s.getMessage());
            });
            dto.setInstanceCount((int) all.stream()
                    .filter(i -> item.code().equals(i.getCode())).count());
            return dto;
        }).toList();
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
        return instances.selectByCluster(clusterId, code).stream()
                .filter(i -> tenantId == null || tenantId.equals(i.getTenantId()))
                .toList();
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
        catalog.requireMode(item, req.getMode());
        if (isBlank(req.getClusterId()) || isBlank(req.getNamespace())) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "请选择集群与命名空间");
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
            dto.setCpuRequest(req.getCpuRequest());
            dto.setCpuLimit(req.getCpuLimit());
            dto.setMemoryRequest(req.getMemoryRequest());
            dto.setMemoryLimit(req.getMemoryLimit());
            dto.setStorageSize(req.getStorageSize());
            dto.setStorageClass(req.getStorageClass());
            dto.setPassword(randomPassword());
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
            dto.setCpuRequest(req.getCpuRequest());
            dto.setCpuLimit(req.getCpuLimit());
            dto.setMemoryRequest(req.getMemoryRequest());
            dto.setMemoryLimit(req.getMemoryLimit());
            dto.setStorageSize(req.getStorageSize());
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

    public PlatformMiddlewareOperation operation(Long id) {
        return operations.selectByPrimaryKey(id);
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

    private static boolean isChartBacked(String code) {
        return "nacos".equals(code);
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
