package com.coding.data.mapper.auth;

import com.coding.data.models.auth.PlatformRolePermission;
import org.apache.ibatis.annotations.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PlatformRolePermissionMapper {

    int insert(PlatformRolePermission record);

    int deleteByRoleId(@Param("roleId") String roleId);

    /**
     * 查询角色关联的权限点 id 列表
     */
    List<String> selectPermissionIdsByRoleId(@Param("roleId") String roleId);

    /**
     * 查询角色关联的权限点 code 列表
     */
    List<String> selectPermissionCodesByRoleId(@Param("roleId") String roleId);

    /**
     * 查询引用某权限点的角色 id 列表
     */
    List<String> selectRoleIdsByPermissionId(@Param("permissionId") String permissionId);
}
