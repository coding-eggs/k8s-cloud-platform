package com.coding.data.mapper.auth;

import com.coding.data.models.auth.PlatformTenant;
import com.coding.data.models.system.UserTenantInfo;
import org.apache.ibatis.annotations.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PlatformTenantMapper {
    int deleteByPrimaryKey(String id);

    int insert(PlatformTenant record);

    int insertSelective(PlatformTenant record);

    PlatformTenant selectByPrimaryKey(String id);

    int updateByPrimaryKeySelective(PlatformTenant record);

    int updateByPrimaryKey(PlatformTenant record);

    PlatformTenant selectByTenantId(@Param("clusterId") String clusterId, @Param("tenantId") String tenantId);

    UserTenantInfo selectTenantByUser(@Param("username") String username, @Param("tenantId") String tenantId);

    List<PlatformTenant> listAll();

    /**
     * 校验租户在指定集群下是否有权限访问某命名空间
     */
    boolean hasNamespaceAccess(@Param("tenantId") String tenantId, @Param("clusterId") String clusterId, @Param("namespace") String namespace);
}