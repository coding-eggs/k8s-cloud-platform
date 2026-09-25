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

    /**
     * 不变量 3 守卫专用：count + FOR UPDATE，在调用方事务内锁住该 (tenant,role) 的行区间，
     * 使"最后一个 tenant-admin"的 count-then-delete 串行化（并发 revoke/remove 互相阻塞）。
     */
    int countByTenantAndRoleForUpdate(@Param("tenantId") String tenantId, @Param("roleId") String roleId);
}
