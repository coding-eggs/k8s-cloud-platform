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

    /** scope 不变量：TENANT 角色只 tenant: 族，PLATFORM 角色只 platform: 族。
     *  豁免内置超管 admin（code=admin）：V2026_09_29_2 起它同时持有资源域 tenant:* code，
     *  全量重存若按不变量拒绝，角色管理页对 admin 的保存会必错。 */
    private void assertScopeMatches(PlatformRole r, String permCode) {
        boolean isTenantPerm = permCode.startsWith("tenant:");
        boolean isPlatformPerm = permCode.startsWith("platform:");
        if (RoleScope.TENANT.name().equals(r.getScope()) && !isTenantPerm)
            throw new CloudPlatformException(EnumResponseType.ROLE_SCOPE_MISMATCH, "租户角色只能勾选 tenant: 权限: " + permCode);
        boolean isAdminSuperuser = "admin".equals(r.getCode());
        if (RoleScope.PLATFORM.name().equals(r.getScope()) && !isAdminSuperuser && !isPlatformPerm)
            throw new CloudPlatformException(EnumResponseType.ROLE_SCOPE_MISMATCH, "平台角色只能勾选 platform: 权限: " + permCode);
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
