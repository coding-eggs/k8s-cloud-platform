package com.coding.platformapi.services;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.utils.ULIDGenerator;
import com.coding.data.mapper.auth.PlatformRoleMapper;
import com.coding.data.mapper.auth.PlatformTenantMapper;
import com.coding.data.mapper.auth.PlatformUserMapper;
import com.coding.data.mapper.auth.PlatformUserRoleMapper;
import com.coding.data.models.auth.PlatformRole;
import com.coding.data.models.auth.PlatformTenant;
import com.coding.data.models.auth.PlatformUser;
import com.coding.data.models.auth.PlatformUserRole;
import com.coding.data.models.auth.RoleScope;
import com.coding.platformapi.models.UserCreateRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Date;
import java.util.List;

/**
 * 平台用户管理：本地建用户（BCrypt 存密）+ 平台域角色授予/回收
 */
@Service
@RequiredArgsConstructor
public class UserService {
    private final PlatformUserMapper userMapper;
    private final PlatformUserRoleMapper userRoleMapper;
    private final PlatformRoleMapper roleMapper;
    private final PlatformTenantMapper tenantMapper;
    private final PasswordEncoder passwordEncoder;

    public PlatformUser create(UserCreateRequest req) {
        if (!StringUtils.hasText(req.getUsername()) || !StringUtils.hasText(req.getPassword()))
            throw new CloudPlatformException(EnumResponseType.BEAN_VALIDATION_EXCEPTION, "用户名与密码必填");
        if (userMapper.selectByUsername(req.getUsername()) != null)
            throw new CloudPlatformException(EnumResponseType.BEAN_VALIDATION_EXCEPTION, "用户名已存在");
        PlatformUser u = new PlatformUser();
        u.setId(ULIDGenerator.generateULID());
        u.setUsername(req.getUsername());
        u.setPassword(passwordEncoder.encode(req.getPassword()));
        u.setDisplayName(req.getDisplayName());
        u.setEmail(req.getEmail());
        u.setStatus(req.getStatus() != null ? req.getStatus().byteValue() : (byte) 1);
        u.setType("TENANT_USER");
        u.setSource("LOCAL");
        Date now = new Date();
        u.setCreatedAt(now);
        u.setUpdatedAt(now);
        userMapper.insert(u);
        return u;
    }

    public List<PlatformUser> list() {
        List<PlatformUser> users = userMapper.listAllActive();
        users.forEach(u -> u.setPassword(null)); // 响应不外泄密码 hash
        return users;
    }

    public PlatformUser get(String id) {
        PlatformUser u = userMapper.selectByPrimaryKey(id);
        if (u != null) {
            u.setPassword(null);
        }
        return u;
    }

    /**
     * 授予平台角色：先校验角色域（与租户侧对称：仅存在、启用、PLATFORM 族，阻断误授/旁路授 TENANT 角色），
     * uk_user_role 唯一索引兜幂等，重复授予视为已存在（不报错）。
     *
     * @return 提示语（可能为空）—— 该用户若仍是某些租户的成员，说清那些关系现在只影响切换器的可见范围，
     *         免得运维以为"升级了但切换器里还是那几个租户"是 bug
     */
    public List<String> grantPlatformRole(String userId, String roleId) {
        if (userMapper.selectByPrimaryKey(userId) == null)
            throw new CloudPlatformException(EnumResponseType.BEAN_VALIDATION_EXCEPTION, "用户不存在");
        RoleValidations.requireRoleOfScope(roleMapper, roleId, RoleScope.PLATFORM);
        PlatformUserRole r = new PlatformUserRole();
        r.setId(ULIDGenerator.generateULID());
        r.setUserId(userId);
        r.setRoleId(roleId);
        r.setCreatedAt(new Date());
        try {
            userRoleMapper.insert(r);
        } catch (DuplicateKeyException e) {
            // 已授予 → 幂等返回
        }
        return tenantMembershipNotice(userId);
    }

    /**
     * "他还是这些租户的成员"的提示。
     *
     * <p>为什么值得说：授了平台角色之后，这些成员关系**对权限不再有任何增量**（平台角色已持有全部
     * {@code tenant:*} 码，切租户也不再需要成员资格），但它们在成员列表里仍然看得见 ——
     * 不说一句的话，运维会以为"升级没生效"。
     *
     * <p>**只提示，不删除**：撤销平台角色后他应当回到原状态，删掉等于把降级做成不可逆。
     */
    private List<String> tenantMembershipNotice(String userId) {
        List<PlatformTenant> tenants = tenantMapper.listTenantsByUser(userId);
        if (tenants == null || tenants.isEmpty()) {
            return List.of();
        }
        String names = tenants.stream().map(PlatformTenant::getName)
                .filter(StringUtils::hasText)
                .collect(java.util.stream.Collectors.joining("、"));
        if (!StringUtils.hasText(names)) {
            return List.of();
        }
        return List.of("该用户同时是「" + names + "」的成员。平台身份已覆盖全部权限，"
                + "这些成员关系只影响他能切进哪些租户的视图（不再需要它们）。");
    }

    public void revokePlatformRole(String userId, String roleId) {
        userRoleMapper.deleteByUserAndRole(userId, roleId);
    }

    /**
     * 用户已持平台角色回显（/user/platformRole/list）：复用 selectRoleIdsByUser 的口径
     * （仅启用 + 未删除 + PLATFORM 族），空关联短路不查角色表。
     */
    public List<PlatformRole> platformRolesOf(String userId) {
        if (userMapper.selectByPrimaryKey(userId) == null)
            throw new CloudPlatformException(EnumResponseType.BEAN_VALIDATION_EXCEPTION, "用户不存在");
        List<String> roleIds = userRoleMapper.selectRoleIdsByUser(userId);
        if (roleIds.isEmpty())
            return List.of();
        return roleMapper.selectByIds(roleIds);
    }
}
