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

    /**
     * 按租户标识（service_account）查询未删除的租户
     */
    PlatformTenant selectByServiceAccount(@Param("serviceAccount") String serviceAccount);

    int updateByPrimaryKeySelective(PlatformTenant record);

    int updateByPrimaryKey(PlatformTenant record);

    /**
     * 校验租户在指定集群下是否有命名空间分配
     */
    boolean hasClusterAccess(@Param("tenantId") String tenantId, @Param("clusterId") String clusterId);

    /**
     * 列出租户有命名空间分配的集群id
     */
    List<String> listClusterIdsByTenant(@Param("tenantId") String tenantId);

    UserTenantInfo selectTenantByUser(@Param("username") String username, @Param("tenantId") String tenantId);

    List<PlatformTenant> listAll();

    /**
     * 校验租户在指定集群下是否有权限访问某命名空间
     */
    boolean hasNamespaceAccess(@Param("tenantId") String tenantId, @Param("clusterId") String clusterId, @Param("namespace") String namespace);
}