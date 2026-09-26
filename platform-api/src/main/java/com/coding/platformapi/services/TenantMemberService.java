package com.coding.platformapi.services;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.utils.ULIDGenerator;
import com.coding.data.mapper.auth.PlatformRoleMapper;
import com.coding.data.mapper.auth.PlatformUserMapper;
import com.coding.data.mapper.auth.PlatformUserTenantMapper;
import com.coding.data.mapper.auth.PlatformUserTenantRoleMapper;
import com.coding.data.models.auth.PlatformRole;
import com.coding.data.models.auth.PlatformUser;
import com.coding.data.models.auth.PlatformUserTenant;
import com.coding.data.models.auth.PlatformUserTenantRole;
import com.coding.data.models.auth.RoleScope;
import com.coding.platformapi.models.TenantMemberView;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.List;

/**
 * 租户成员管理（不变量 2/3）：
 * <ul>
 *   <li>不变量 2：授予租户角色前必须已是该租户成员（user_tenant 行存在），否则 TENANT_MEMBER_NOT_FOUND</li>
 *   <li>不变量 3：任何路径（回收角色 / 移除成员）都不得让租户失去最后一个 tenant-admin，否则 TENANT_ADMIN_REQUIRED</li>
 * </ul>
 * 每个写方法各自 {@code @Transactional}（方法级原子；uk_user_tenant / uk_user_tenant_role 唯一索引兜并发幂等）。
 */
@Service
@RequiredArgsConstructor
public class TenantMemberService {

    /** 内置租户管理员角色 code（seed 保证存在） */
    public static final String TENANT_ADMIN_CODE = "tenant-admin";

    private final PlatformUserTenantMapper utMapper;
    private final PlatformUserTenantRoleMapper utrMapper;
    private final PlatformRoleMapper roleMapper;
    private final PlatformUserMapper userMapper;

    /** 加入成员：已存在则幂等返回（uk_user_tenant 兜并发重复）。 */
    @Transactional
    public void addMember(String tenantId, String userId) {
        if (!utMapper.exists(userId, tenantId)) {
            PlatformUserTenant r = new PlatformUserTenant();
            r.setId(ULIDGenerator.generateULID());
            r.setUserId(userId);
            r.setTenantId(tenantId);
            r.setCreatedAt(new Date());
            try {
                utMapper.insert(r);
            } catch (DuplicateKeyException e) {
                // 并发下另一请求已插入 → 视为已存在
            }
        }
    }

    /** 授予租户角色：不变量 2 前置校验 + 角色域校验（CRITICAL 1：仅可授存在的、启用的 TENANT 族角色，
     *  阻断租户管理员把 builtin_role_admin 等平台角色授给自己提权）；重复授予幂等。 */
    @Transactional
    public void grantRole(String tenantId, String userId, String roleId) {
        if (!utMapper.exists(userId, tenantId)) {
            throw new CloudPlatformException(EnumResponseType.TENANT_MEMBER_NOT_FOUND);
        }
        requireTenantScopeRole(roleId);
        boolean already = utrMapper.selectByTenantAndUser(tenantId, userId).stream()
                .anyMatch(x -> x.getRoleId().equals(roleId));
        if (already) {
            return;
        }
        PlatformUserTenantRole r = new PlatformUserTenantRole();
        r.setId(ULIDGenerator.generateULID());
        r.setUserId(userId);
        r.setTenantId(tenantId);
        r.setRoleId(roleId);
        r.setCreatedAt(new Date());
        try {
            utrMapper.insert(r);
        } catch (DuplicateKeyException e) {
            // 并发重复授予 → 幂等
        }
    }

    /** 校验 roleId 是存在、启用、TENANT 族的角色；与平台侧授予共用 {@link RoleValidations}（口径对称）。 */
    private void requireTenantScopeRole(String roleId) {
        RoleValidations.requireRoleOfScope(roleMapper, roleId, RoleScope.TENANT);
    }

    /** 回收租户角色：回收 tenant-admin 时守不变量 3（FOR UPDATE count，事务内串行化防竞态）。回收本身宽松（按键删）。 */
    @Transactional
    public void revokeRole(String tenantId, String userId, String roleId) {
        String adminRoleId = requireAdminRoleId();
        if (roleId.equals(adminRoleId) && utrMapper.countByTenantAndRoleForUpdate(tenantId, adminRoleId) <= 1) {
            throw new CloudPlatformException(EnumResponseType.TENANT_ADMIN_REQUIRED);
        }
        utrMapper.delete(userId, tenantId, roleId);
    }

    /** 移除成员：级联删其租户角色；被移者持 tenant-admin 时守不变量 3（FOR UPDATE count 防竞态）。 */
    @Transactional
    public void removeMember(String tenantId, String userId) {
        String adminRoleId = requireAdminRoleId();
        boolean isAdmin = utrMapper.selectByTenantAndUser(tenantId, userId).stream()
                .anyMatch(x -> x.getRoleId().equals(adminRoleId));
        if (isAdmin && utrMapper.countByTenantAndRoleForUpdate(tenantId, adminRoleId) <= 1) {
            throw new CloudPlatformException(EnumResponseType.TENANT_ADMIN_REQUIRED);
        }
        utrMapper.deleteByUserAndTenant(userId, tenantId);
        utMapper.deleteByUserAndTenant(userId, tenantId);
    }

    /** 成员列表：per member 角色 id + 用户信息反查；持 tenant-admin → owner=true。 */
    public List<TenantMemberView> listMembers(String tenantId) {
        String adminRoleId = requireAdminRoleId();
        return utMapper.selectByTenantId(tenantId).stream().map(m -> {
            List<PlatformUserTenantRole> roles = utrMapper.selectByTenantAndUser(tenantId, m.getUserId());
            TenantMemberView v = new TenantMemberView();
            v.setUserId(m.getUserId());
            v.setRoleIds(roles.stream().map(PlatformUserTenantRole::getRoleId).toList());
            v.setOwner(v.getRoleIds().contains(adminRoleId));
            PlatformUser u = userMapper.selectByPrimaryKey(m.getUserId());
            if (u != null) {
                v.setUsername(u.getUsername());
                v.setDisplayName(u.getDisplayName());
            }
            return v;
        }).toList();
    }

    /** 内置 tenant-admin 角色 id；seed 缺失属部署错误，快速失败。create-with-owner 等跨服务场景复用。 */
    public String requireAdminRoleId() {
        PlatformRole r = roleMapper.selectByCode(TENANT_ADMIN_CODE);
        if (r == null) {
            throw new IllegalStateException("内置角色 tenant-admin 缺失（seed 未执行）");
        }
        return r.getId();
    }
}
