package com.coding.data.mapper.auth;

import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PlatformUserRoleMapper {
    /**
     * 查询用户被授予的平台域角色 code 列表（仅启用且未删除的角色）
     */
    List<String> selectRoleCodesByUser(String userId);
}
