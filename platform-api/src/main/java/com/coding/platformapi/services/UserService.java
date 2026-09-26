package com.coding.platformapi.services;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.utils.ULIDGenerator;
import com.coding.data.mapper.auth.PlatformRoleMapper;
import com.coding.data.mapper.auth.PlatformUserMapper;
import com.coding.data.mapper.auth.PlatformUserRoleMapper;
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
     * uk_user_role 唯一索引兜幂等，重复授予视为已存在（不报错）
     */
    public void grantPlatformRole(String userId, String roleId) {
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
    }

    public void revokePlatformRole(String userId, String roleId) {
        userRoleMapper.deleteByUserAndRole(userId, roleId);
    }
}
