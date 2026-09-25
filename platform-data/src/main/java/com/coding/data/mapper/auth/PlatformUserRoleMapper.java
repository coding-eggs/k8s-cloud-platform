package com.coding.data.mapper.auth;

import com.coding.data.models.auth.PlatformUserRole;
import org.apache.ibatis.annotations.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PlatformUserRoleMapper {

    /**
     * 授予：插入用户↔平台角色行（唯一索引 uk_user_role 兜底重复授予）
     */
    int insert(PlatformUserRole record);

    /**
     * 回收：按用户+角色删除授予行
     */
    int deleteByUserAndRole(@Param("userId") String userId, @Param("roleId") String roleId);
    /**
     * 查询用户被授予的平台域角色 code 列表（仅启用且未删除的角色）
     */
    List<String> selectRoleCodesByUser(String userId);

    /**
     * 查询用户被授予的角色 id 列表（仅启用且未删除的角色）
     */
    List<String> selectRoleIdsByUser(@Param("userId") String userId);
}
