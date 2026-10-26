package com.coding.platformapi.services;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.middleware.MiddlewareUserCreateRequestDTO;
import com.coding.common.models.middleware.MiddlewareUserCredentialDTO;
import com.coding.common.models.middleware.MiddlewareUserDeleteRequestDTO;
import com.coding.common.models.middleware.MiddlewareUserListRequestDTO;
import com.coding.common.models.middleware.MiddlewareUserPasswordRequestDTO;
import com.coding.common.models.middleware.MiddlewareUserRules;
import com.coding.common.models.middleware.MiddlewareUserView;
import com.coding.data.mapper.system.PlatformMiddlewareInstanceMapper;
import com.coding.data.models.system.PlatformMiddlewareInstance;
import com.coding.platformapi.k8s.K8sMiddlewareClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 用户管理的平台侧：归属校验、口令生成、一次性返回。
 *
 * <p>执行下沉到 k8s-server（按 code 分派到各家适配器）；这里只管"能不能操作"和"口令从哪来"。
 * 明文口令只在 {@link #create} / {@link #changePassword} 的返回值里出现一次，
 * 不进日志、不进审计、不落台账。
 */
@Service
public class MiddlewareUserService {

    /** 能做用户管理的中间件（Redis 只支持改口令，另在方法内判断） */
    public static final Set<String> SUPPORTED_CODES = MiddlewareUserRules.SUPPORTED_CODES;

    /** 需要"实例已开启鉴权"才有账号概念的中间件 */
    private static final Set<String> NEEDS_AUTH = Set.of("milvus", "nacos");

    /** 各家的内置账号名：不允许新建、不允许删除 */
    private static final Map<String, Set<String>> RESERVED = Map.of(
            "mysql", Set.of("root"),
            "milvus", Set.of("root"),
            "elasticsearch", Set.of("elastic"),
            "redis", Set.of("default"),
            // bitnami cluster operator 的默认用户；它同时是平台调 management API 的凭据，
            // 被改名/删掉 = 平台再也管不了这个实例
            "rabbitmq", Set.of("user"));

    private static final SecureRandom RANDOM = new SecureRandom();

    private final PlatformMiddlewareInstanceMapper instances;
    private final K8sMiddlewareClient client;
    private final JsonMapper jsonMapper;

    public MiddlewareUserService(PlatformMiddlewareInstanceMapper instances, K8sMiddlewareClient client,
                                 JsonMapper jsonMapper) {
        this.instances = instances;
        this.client = client;
        this.jsonMapper = jsonMapper;
    }

    public static boolean validUsername(String username) {
        return MiddlewareUserRules.validUsername(username);
    }

    public static void requirePasswordLength(String password) {
        MiddlewareUserRules.requirePasswordLength(password);
    }

    public List<MiddlewareUserView> list(MiddlewareUserListRequestDTO req, String tenantId) {
        PlatformMiddlewareInstance instance = require(req.getInstanceId(), tenantId);
        requireSupported(instance);
        requireAuthIfNeeded(instance);
        return client.listUsers(instance.getCode(), listDto(instance));
    }

    public List<String> roles(String instanceId, String tenantId) {
        PlatformMiddlewareInstance instance = require(instanceId, tenantId);
        requireSupported(instance);
        return client.userRoles(instance.getCode(), listDto(instance));
    }

    public MiddlewareUserCredentialDTO create(MiddlewareUserCreateRequestDTO req, String tenantId) {
        PlatformMiddlewareInstance instance = require(req.getInstanceId(), tenantId);
        requireCreatable(instance);
        MiddlewareUserRules.requireUsername(req.getUsername());
        requireNotReserved(instance.getCode(), req.getUsername());

        String password = isBlank(req.getPassword()) ? randomPassword() : req.getPassword().trim();
        MiddlewareUserRules.requirePasswordLength(password);

        MiddlewareUserCreateRequestDTO dto = new MiddlewareUserCreateRequestDTO();
        dto.setInstanceId(instance.getId());
        dto.setClusterId(instance.getClusterId());
        dto.setNamespace(instance.getNamespace());
        dto.setName(instance.getName());
        dto.setUsername(req.getUsername());
        dto.setPassword(password);
        dto.setRole(req.getRole());
        dto.setTenantId(tenantId);
        return client.createUser(instance.getCode(), dto);
    }

    public MiddlewareUserCredentialDTO changePassword(MiddlewareUserPasswordRequestDTO req, String tenantId) {
        PlatformMiddlewareInstance instance = require(req.getInstanceId(), tenantId);
        requireSupported(instance);
        requireAuthIfNeeded(instance);
        MiddlewareUserRules.requireUsername(req.getUsername());

        String password = isBlank(req.getNewPassword()) ? randomPassword() : req.getNewPassword().trim();
        MiddlewareUserRules.requirePasswordLength(password);

        MiddlewareUserPasswordRequestDTO dto = new MiddlewareUserPasswordRequestDTO();
        dto.setInstanceId(instance.getId());
        dto.setClusterId(instance.getClusterId());
        dto.setNamespace(instance.getNamespace());
        dto.setName(instance.getName());
        dto.setUsername(req.getUsername());
        dto.setNewPassword(password);
        dto.setTenantId(tenantId);
        return client.changeUserPassword(instance.getCode(), dto);
    }

    public void drop(MiddlewareUserDeleteRequestDTO req, String tenantId) {
        PlatformMiddlewareInstance instance = require(req.getInstanceId(), tenantId);
        requireDeletable(instance);
        requireNotReserved(instance.getCode(), req.getUsername());

        MiddlewareUserDeleteRequestDTO dto = new MiddlewareUserDeleteRequestDTO();
        dto.setInstanceId(instance.getId());
        dto.setClusterId(instance.getClusterId());
        dto.setNamespace(instance.getNamespace());
        dto.setName(instance.getName());
        dto.setUsername(req.getUsername());
        dto.setTenantId(tenantId);
        client.deleteUser(instance.getCode(), dto);
    }

    // ---------- 内部 ----------

    private PlatformMiddlewareInstance require(String instanceId, String tenantId) {
        if (isBlank(instanceId)) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "缺少实例 id");
        }
        PlatformMiddlewareInstance instance = instances.selectByPrimaryKey(instanceId);
        if (instance == null || instance.getDeletedAt() != null) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "实例不存在");
        }
        if (tenantId != null && !tenantId.equals(instance.getTenantId())) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "无权操作该实例");
        }
        return instance;
    }

    private void requireSupported(PlatformMiddlewareInstance instance) {
        if (!SUPPORTED_CODES.contains(instance.getCode())) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "该中间件暂不支持用户管理");
        }
    }

    private void requireCreatable(PlatformMiddlewareInstance instance) {
        String code = instance.getCode();
        requireSupported(instance);
        if ("redis".equals(code)) {
            throw new CloudPlatformException(EnumResponseType.ERROR,
                    "Redis 不支持新增用户（opstree 不管理 ACL 用户），只支持修改口令");
        }
        requireAuthIfNeeded(instance);
    }

    private void requireDeletable(PlatformMiddlewareInstance instance) {
        String code = instance.getCode();
        requireSupported(instance);
        if ("redis".equals(code)) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "Redis 不支持删除用户，只支持修改口令");
        }
        requireAuthIfNeeded(instance);
    }

    private void requireAuthIfNeeded(PlatformMiddlewareInstance instance) {
        if (!NEEDS_AUTH.contains(instance.getCode())) {
            return;
        }
        if (!Boolean.TRUE.equals(parseSpec(instance.getSpecJson()).get("authEnabled"))) {
            throw new CloudPlatformException(EnumResponseType.ERROR,
                    "实例没有开启鉴权，请重建并在创建时勾选「开启鉴权」");
        }
    }

    private static void requireNotReserved(String code, String username) {
        Set<String> reserved = RESERVED.getOrDefault(code, Set.of());
        if (username != null && reserved.contains(username)) {
            throw new CloudPlatformException(EnumResponseType.ERROR,
                    "内置账号不允许新建或删除：" + username);
        }
    }

    private MiddlewareUserListRequestDTO listDto(PlatformMiddlewareInstance instance) {
        MiddlewareUserListRequestDTO dto = new MiddlewareUserListRequestDTO();
        dto.setInstanceId(instance.getId());
        dto.setClusterId(instance.getClusterId());
        dto.setNamespace(instance.getNamespace());
        dto.setName(instance.getName());
        dto.setTenantId(instance.getTenantId());
        return dto;
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

    private static String randomPassword() {
        byte[] bytes = new byte[18];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
