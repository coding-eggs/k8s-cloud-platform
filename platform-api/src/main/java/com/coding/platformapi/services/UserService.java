package com.coding.platformapi.services;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.security.AuthContext;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.utils.ULIDGenerator;
import com.coding.data.mapper.auth.PlatformRoleMapper;
import com.coding.data.mapper.auth.PlatformTenantMapper;
import com.coding.data.mapper.auth.PlatformUserMapper;
import com.coding.data.mapper.auth.PlatformUserRoleMapper;
import com.coding.data.mapper.auth.PlatformUserTenantMapper;
import com.coding.data.mapper.auth.PlatformUserTenantRoleMapper;
import com.coding.data.models.auth.PlatformRole;
import com.coding.data.models.auth.PlatformTenant;
import com.coding.data.models.auth.PlatformUser;
import com.coding.data.models.auth.PlatformUserRole;
import com.coding.data.models.auth.RoleScope;
import com.coding.platformapi.models.UserCreateRequest;
import com.coding.platformapi.models.UserUpdateRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Date;
import java.util.List;

/**
 * 平台用户管理：本地建用户（BCrypt 存密）/ 改资料与启停 / 软删除 + 平台域角色授予/回收
 */
@Service
@RequiredArgsConstructor
public class UserService {
    private final PlatformUserMapper userMapper;
    private final PlatformUserRoleMapper userRoleMapper;
    private final PlatformRoleMapper roleMapper;
    private final PlatformTenantMapper tenantMapper;
    private final PasswordEncoder passwordEncoder;
    private final PlatformUserTenantMapper userTenantMapper;
    private final PlatformUserTenantRoleMapper userTenantRoleMapper;
    private final AuthContext auth;

    /** 内置平台管理员角色 code：「最后一个可用管理员」护栏按它数人 */
    private static final String ADMIN_ROLE_CODE = "admin";

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
        try {
            userMapper.insert(u);
        } catch (DuplicateKeyException e) {
            // 上面只查了未删除的用户；撞唯一索引 = 名字被已删除的用户占着（软删不释放用户名）
            throw new CloudPlatformException(EnumResponseType.BEAN_VALIDATION_EXCEPTION,
                    "用户名已被一个已删除的用户占用，请换一个");
        }
        return u;
    }

    /**
     * 改资料 / 启停。用户名不改（登录名；会话、审计都按它记）。
     * 禁用的护栏：不能禁用自己（当场把自己锁在外面），不能禁用最后一个可用的平台管理员。
     * 禁用对已登录会话同样生效：权限闭包对禁用用户返回空（{@code PermissionClosureService}）。
     */
    public void update(UserUpdateRequest req) {
        PlatformUser u = requireUser(req.getId());
        Byte status = req.getStatus() == null ? u.getStatus() : req.getStatus().byteValue();
        boolean disabling = status != null && status != 1 && Byte.valueOf((byte) 1).equals(u.getStatus());
        if (disabling) {
            assertNotSelf(u, "禁用");
            assertNotLastAdmin(u, "禁用");
        }
        userMapper.updateProfile(u.getId(), trimToNull(req.getDisplayName()), trimToNull(req.getEmail()), status);
    }

    /**
     * 软删除：置 deleted_at（登录、权限闭包都按"不存在"处理），同事务收回全部平台角色、租户成员关系与租户内角色。
     * 用户行本身保留 —— 审计、流水线、告警收件人等处按 id / 用户名引用他，硬删会让这些记录对不上人。
     */
    @Transactional
    public void delete(String userId) {
        PlatformUser u = requireUser(userId);
        assertNotSelf(u, "删除");
        assertNotLastAdmin(u, "删除");
        userMapper.softDelete(u.getId());
        userRoleMapper.deleteByUser(u.getId());
        userTenantMapper.deleteByUser(u.getId());
        userTenantRoleMapper.deleteByUser(u.getId());
    }

    private PlatformUser requireUser(String id) {
        PlatformUser u = id == null ? null : userMapper.selectByPrimaryKey(id);
        if (u == null)
            throw new CloudPlatformException(EnumResponseType.BEAN_VALIDATION_EXCEPTION, "用户不存在");
        return u;
    }

    private void assertNotSelf(PlatformUser target, String verb) {
        var me = auth.current();
        if (me != null && target.getUsername() != null && target.getUsername().equals(me.getUsername()))
            throw new CloudPlatformException(EnumResponseType.BEAN_VALIDATION_EXCEPTION, "不能" + verb + "自己");
    }

    /** 目标持 admin 角色，且除他之外再没有启用中的 admin → 拒绝（否则平台没人能再管用户与角色） */
    private void assertNotLastAdmin(PlatformUser target, String verb) {
        List<String> adminIds = userRoleMapper.selectUserIdsByRoleCode(ADMIN_ROLE_CODE);
        if (adminIds == null || !adminIds.contains(target.getId()))
            return;
        boolean anotherEnabled = userMapper.selectByIds(adminIds).stream()
                .anyMatch(a -> !target.getId().equals(a.getId()) && a.getStatus() != null && a.getStatus() == 1);
        if (!anotherEnabled)
            throw new CloudPlatformException(EnumResponseType.BEAN_VALIDATION_EXCEPTION,
                    "这是最后一个可用的平台管理员，不能" + verb + "；请先给其他用户授予管理员角色");
    }

    private static String trimToNull(String s) {
        return StringUtils.hasText(s) ? s.trim() : null;
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
