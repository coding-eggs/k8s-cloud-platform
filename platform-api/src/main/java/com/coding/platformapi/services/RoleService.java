package com.coding.platformapi.services;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.utils.ULIDGenerator;
import com.coding.data.mapper.auth.PlatformPermissionMapper;
import com.coding.data.mapper.auth.PlatformRoleMapper;
import com.coding.data.mapper.auth.PlatformRolePermissionMapper;
import com.coding.data.models.auth.PlatformPermission;
import com.coding.data.models.auth.PlatformRole;
import com.coding.data.models.auth.PlatformRolePermission;
import com.coding.data.models.auth.RoleScope;
import com.coding.platformapi.models.RoleCreateRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 角色管理：自定义角色 CRUD + 权限勾选全量重存（spec §3.4.1 scope 不变量）。
 *
 * <p>不变量自 2026-10-08 起是**单向**的：TENANT 角色只能持 tenant: 族；PLATFORM 角色不限
 * （理由与后果见 {@link #assertScopeMatches}）。「该角色可分配哪些权限点」由
 * {@link #assignablePermissions} 统一给出，前端不再自行按前缀过滤。
 *
 * <p>权限点 code 自 Task 4 起不再唯一（ANY-of：同一 code 对应多个 URL 行），
 * 故勾选一个 code 必须把该 code 的<b>全部</b>行都落 platform_role_permission 关联，
 * 否则运行时按行 id 闭包查询会漏掉未关联的 URL。
 */
@Service
@RequiredArgsConstructor
public class RoleService {
    private final PlatformRoleMapper roleMapper;
    private final PlatformPermissionMapper permMapper;
    private final PlatformRolePermissionMapper rpMapper;

    public List<PlatformRole> list() {
        return roleMapper.listAllActive();
    }

    public PlatformRole create(RoleCreateRequest req) {
        if (!StringUtils.hasText(req.getName()) || !StringUtils.hasText(req.getCode()))
            throw new CloudPlatformException(EnumResponseType.BEAN_VALIDATION_EXCEPTION, "角色名称与编码必填");
        if (!RoleScope.isValid(req.getScope()))
            throw new CloudPlatformException(EnumResponseType.BEAN_VALIDATION_EXCEPTION, "角色族须为 PLATFORM 或 TENANT");
        // platform_role.code 仍保持唯一（UNIQUE(role)），ANY-of 放宽只发生在 platform_permission.code
        if (roleMapper.selectByCode(req.getCode()) != null)
            throw new CloudPlatformException(EnumResponseType.BEAN_VALIDATION_EXCEPTION, "角色编码已存在");
        PlatformRole r = new PlatformRole();
        r.setId(ULIDGenerator.generateULID());
        r.setName(req.getName());
        r.setCode(req.getCode());
        r.setDescription(req.getDescription());
        r.setScope(req.getScope());
        r.setBuiltIn((byte) 0);
        r.setStatus((byte) 1);
        Date now = new Date();
        r.setCreatedAt(now);
        r.setUpdatedAt(now);
        roleMapper.insert(r);
        return r;
    }

    @Transactional
    public void delete(String roleId) {
        PlatformRole r = require(roleId);
        if (isBuiltIn(r))
            throw new CloudPlatformException(EnumResponseType.ROLE_BUILTIN_READONLY);
        roleMapper.deleteByPrimaryKey(roleId);
        rpMapper.deleteByRoleId(roleId);
    }

    /**
     * 全量重存角色权限：先校验（存在性 + scope 不变量），后 delete + insert，同事务。
     * 校验置于任何写操作之前：非法勾选直接抛错，不出现"已清空但未写入"的中间态
     * （即便有事务兜底，fail-fast 也让审计日志与错误响应更干净）。
     * 内置角色（admin/tenant-admin）的权限集合允许 role:manage 重存，故此处不拦 built_in。
     */
    @Transactional
    public void savePermissions(String roleId, List<String> permissionCodes) {
        PlatformRole r = require(roleId);
        // 去重保序；同 code 多行（ANY-of）只解析一次
        LinkedHashSet<String> uniqueCodes = new LinkedHashSet<>(
                permissionCodes == null ? List.<String>of() : permissionCodes);
        Map<String, List<PlatformPermission>> resolved = new LinkedHashMap<>();
        for (String code : uniqueCodes) {
            List<PlatformPermission> perms = permMapper.selectAllByCode(code);
            if (perms == null || perms.isEmpty())
                throw new CloudPlatformException(EnumResponseType.PERMISSION_NOT_FOUND, "权限点不存在: " + code);
            assertScopeMatches(r, code); // 按 code 前缀校验，一族 code 只需一次
            resolved.put(code, perms);
        }
        rpMapper.deleteByRoleId(roleId);
        Date now = new Date();
        for (List<PlatformPermission> perms : resolved.values()) {
            for (PlatformPermission p : perms) { // code 不唯一 → 每行都落关联
                PlatformRolePermission rp = new PlatformRolePermission();
                rp.setId(ULIDGenerator.generateULID());
                rp.setRoleId(roleId);
                rp.setPermissionId(p.getId());
                rp.setCreatedAt(now);
                rpMapper.insert(rp);
            }
        }
    }

    /** 配置页回显：角色已勾选的权限 code（XML 已 distinct + 过滤软删行）。 */
    public List<String> permissionCodesOf(String roleId) {
        return rpMapper.selectPermissionCodesByRoleId(roleId);
    }

    /**
     * 该角色**可分配**的权限点行（角色管理页的勾选列表数据源，判据只此一处）。
     *
     * <p>TENANT 角色只给 tenant: 族；PLATFORM 角色给全部。判据放后端而非前端：可分配范围是
     * 授权策略，前端再写一遍必然会与本类的 savePermissions 校验漂移（此前正是如此 ——
     * 前端按 scope 前缀过滤 + 硬编码 admin 豁免，与后端 assertScopeMatches 各写一份）。
     */
    public List<PlatformPermission> assignablePermissions(String roleId) {
        PlatformRole r = require(roleId);
        List<PlatformPermission> all = permMapper.selectAllActive();
        if (!RoleScope.TENANT.name().equals(r.getScope())) return all; // PLATFORM：两族都可
        return all.stream()
                .filter(p -> p.getCode() != null && p.getCode().startsWith("tenant:"))
                .toList();
    }

    /**
     * scope 不变量：**TENANT 角色只能持 tenant: 族**。
     * 原始动机是防"租户管理员"被勾出建租户权限（spec §3.4 不变量 1），方向只有一个：
     * 租户族不能向上够。
     *
     * <p>PLATFORM 侧**不设反向限制**（2026-10-08 起）。平台族在运行时本就是租户族的超集 ——
     * TokenExtrasService.permissions() 把平台族角色的码无条件并入、租户角色的码只在带 tenantInfo
     * 时并入；k8s-server 的平台侧身份判据（PLATFORM_SCOPE）取自 platformRoles 非空，与码族无关。
     * 原先的反向限制只是对称，代价是造不出"平台运维/审计"这类只管一部分的平台角色，且为救内置
     * admin（持有全部资源域 tenant:* 码，见 V2026_09_29_2）必须硬编码 `code=admin` 特例 —— 该特例随本次删除。
     *
     * <p>⚠️ 后果（已接受，见 docs/superpowers/plans/2026-10-08-role-assignable-permissions.md）：
     * 持 platform:role:manage 的角色可给自己勾满两族 ⇒ 事实上的全权；给 PLATFORM 角色勾租户码
     * = 授权它对**任何**租户做那件事（无 tenantInfo 的 token 走 admin 代操作分支，边界只剩分配表）。
     */
    private void assertScopeMatches(PlatformRole r, String permCode) {
        if (RoleScope.TENANT.name().equals(r.getScope()) && !permCode.startsWith("tenant:"))
            throw new CloudPlatformException(EnumResponseType.ROLE_SCOPE_MISMATCH, "租户角色只能勾选 tenant: 权限: " + permCode);
    }

    private boolean isBuiltIn(PlatformRole r) {
        return r.getBuiltIn() != null && r.getBuiltIn() == 1;
    }

    private PlatformRole require(String id) {
        PlatformRole r = roleMapper.selectByPrimaryKey(id);
        if (r == null)
            throw new CloudPlatformException(EnumResponseType.BEAN_VALIDATION_EXCEPTION, "角色不存在");
        return r;
    }
}
