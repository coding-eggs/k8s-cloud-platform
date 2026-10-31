package com.coding.platformapi.services;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.utils.ULIDGenerator;
import com.coding.data.mapper.auth.PlatformRoleMapper;
import com.coding.data.mapper.auth.PlatformUserMapper;
import com.coding.data.mapper.auth.PlatformUserRoleMapper;
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
 * 租户成员管理：
 * <ul>
 *   <li>不变量 2：授予租户角色前必须已是该租户成员（user_tenant 行存在），否则 TENANT_MEMBER_NOT_FOUND</li>
 *   <li>不变量 3（<b>2026-10-31 撤销</b>）：原「任何路径都不得让租户失去最后一个 tenant-admin」。
 *       撤销理由：建租户时 owner 是可选的，平台侧又能全权代管 —— 零管理员本来就是合法状态；
 *       只在移除侧拦会自相矛盾，且只能"先加一个再删"。提醒改由前端弹确认。</li>
 *   <li>默认角色：加入成员时顺带授予内置「租户成员」（{@code tenant-member}，租户内只读边界），
 *       省掉"加进来 → 再手动授一次角色"的两步操作。额外权限仍由操作者显式授予。</li>
 * </ul>
 * 每个写方法各自 {@code @Transactional}（方法级原子；uk_user_tenant / uk_user_tenant_role 唯一索引兜并发幂等）。
 */
@Service
@RequiredArgsConstructor
public class TenantMemberService {

    /** 内置租户管理员角色 code（seed 保证存在） */
    public static final String TENANT_ADMIN_CODE = "tenant-admin";

    /** 内置租户成员角色 code（seed 保证存在）—— 新加成员的默认角色 */
    public static final String TENANT_MEMBER_CODE = "tenant-member";

    private final PlatformUserTenantMapper utMapper;
    private final PlatformUserTenantRoleMapper utrMapper;
    private final PlatformRoleMapper roleMapper;
    private final PlatformUserMapper userMapper;
    private final PlatformUserRoleMapper userRoleMapper;

    /**
     * 平台侧身份不得**新增**为租户成员、也不得**新授予**租户角色（spec 2026-10-31 §3.2）。
     *
     * <p>为什么：平台用户的权限不来自租户角色 —— admin 的平台角色本身就持有全部 {@code tenant:*} 码。
     * 把他加进成员表只会让"这个租户有哪些人"变脏，而且租户管理员还能反过来给平台用户授/撤租户角色，
     * 语义完全倒挂。
     *
     * <p>⚠️ 只挡**新增**（{@code alreadyLinked} 为真 = 历史数据，幂等路径不报错）：
     * 已有的历史行一律保留 —— 撤销平台角色后要能回到原状态，删掉就等于把降级做成不可逆。
     */
    private void rejectPlatformSideWhenNew(String userId, boolean alreadyLinked, String action) {
        if (alreadyLinked) {
            return;
        }
        List<String> roles = userRoleMapper.selectRoleCodesByUser(userId);
        if (roles != null && !roles.isEmpty()) {
            throw new CloudPlatformException(EnumResponseType.BEAN_VALIDATION_EXCEPTION,
                    "该用户持有平台角色，不能" + action + "：平台身份不通过租户授权获得（切换器里已能看到全部租户）");
        }
    }

    /**
     * 加入成员：已存在则幂等返回（uk_user_tenant 兜并发重复）。
     *
     * <p>新成员顺带授予内置「租户成员」角色 —— 加入即具备租户内只读边界，不必再多点一次「授予」。
     * 幂等路径（已是成员）**不补授**：否则"添加"就变成了"重置角色"，把操作者后来回收过的角色悄悄加回来。
     */
    @Transactional
    public void addMember(String tenantId, String userId) {
        boolean exists = utMapper.exists(userId, tenantId);
        rejectPlatformSideWhenNew(userId, exists, "加入该租户");
        if (exists) {
            return;
        }
        PlatformUserTenant r = new PlatformUserTenant();
        r.setId(ULIDGenerator.generateULID());
        r.setUserId(userId);
        r.setTenantId(tenantId);
        r.setCreatedAt(new Date());
        try {
            utMapper.insert(r);
        } catch (DuplicateKeyException e) {
            // 并发下另一请求已插入 → 视为已存在（下面按默认角色补授，两边幂等）
        }
        grantRole(tenantId, userId, requireMemberRoleId());
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
        // 平台身份的权限不来自租户角色 —— 新授予一律拒（历史行保留，所以放在 already 之后）
        rejectPlatformSideWhenNew(userId, false, "授予租户角色");
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

    /**
     * 回收租户角色：按键删除。
     *
     * <p>⚠️ 2026-10-31 起**不再拦"最后一个 tenant-admin"**。理由：建租户时 {@code ownerUserId} 本来就是
     * 可选的（不填就建出零成员的租户），平台侧又能全权代管 —— "零管理员"是系统本来就接受的合法状态，
     * 只在移除这一侧拦就自相矛盾，而且没有别的绕法（只能先加一个管理员再删，纯属折腾）。
     * 提醒放在前端：移除/降级最后一名管理员时弹更重的确认，说清"之后只能由平台代管"。
     */
    @Transactional
    public void revokeRole(String tenantId, String userId, String roleId) {
        utrMapper.delete(userId, tenantId, roleId);
    }

    /** 移除成员：级联删其租户角色。同上，不再拦"最后一个管理员"。 */
    @Transactional
    public void removeMember(String tenantId, String userId) {
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

    /** 内置 tenant-member 角色 id（加入成员时的默认角色）；seed 缺失属部署错误，快速失败。 */
    public String requireMemberRoleId() {
        PlatformRole r = roleMapper.selectByCode(TENANT_MEMBER_CODE);
        if (r == null) {
            throw new IllegalStateException("内置角色 tenant-member 缺失（seed 未执行）");
        }
        return r.getId();
    }
}
