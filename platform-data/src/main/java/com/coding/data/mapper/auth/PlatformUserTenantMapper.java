package com.coding.data.mapper.auth;

import com.coding.data.models.auth.PlatformUserTenant;
import org.apache.ibatis.annotations.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PlatformUserTenantMapper {

    int insert(PlatformUserTenant record);

    int deleteByUserAndTenant(@Param("userId") String userId, @Param("tenantId") String tenantId);

    boolean exists(@Param("userId") String userId, @Param("tenantId") String tenantId);

    /**
     * 查询用户加入的未删除租户 id 列表
     */
    List<String> selectTenantIdsByUser(@Param("userId") String userId);

    List<PlatformUserTenant> selectByTenantId(@Param("tenantId") String tenantId);
}
