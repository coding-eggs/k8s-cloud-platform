package com.coding.data.mapper.auth;

import com.coding.data.models.auth.PlatformUserTenantRole;
import org.apache.ibatis.annotations.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PlatformUserTenantRoleMapper {

    int insert(PlatformUserTenantRole record);

    int delete(@Param("userId") String userId, @Param("tenantId") String tenantId, @Param("roleId") String roleId);

    int deleteByUserAndTenant(@Param("userId") String userId, @Param("tenantId") String tenantId);

    List<PlatformUserTenantRole> selectByTenantAndUser(@Param("tenantId") String tenantId, @Param("userId") String userId);

    /**
     * 查询用户在某租户内被授予的角色 id 列表
     */
    List<String> selectRoleIdsByUserAndTenant(@Param("userId") String userId, @Param("tenantId") String tenantId);

    int countByTenantAndRole(@Param("tenantId") String tenantId, @Param("roleId") String roleId);
}
