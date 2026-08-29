package com.coding.data.mapper.auth;

import com.coding.data.models.auth.PlatformTenantNamespace;
import org.apache.ibatis.annotations.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PlatformTenantNamespaceMapper {

    int insert(PlatformTenantNamespace record);

    PlatformTenantNamespace selectByPrimaryKey(@Param("id") String id);

    PlatformTenantNamespace selectByTenantClusterNs(@Param("tenantId") String tenantId,
                                                    @Param("clusterId") String clusterId,
                                                    @Param("namespace") String namespace);

    List<PlatformTenantNamespace> listByTenant(@Param("tenantId") String tenantId);

    List<PlatformTenantNamespace> listByCluster(@Param("clusterId") String clusterId);

    List<PlatformTenantNamespace> listAll();

    /**
     * 关联表物理删除（平台约定：关联表无软删）
     */
    int deleteByPrimaryKey(@Param("id") String id);

    /**
     * 统计引用某模板的分配数（模板删除保护用）
     */
    int countByRoleTemplate(@Param("roleTemplateId") String roleTemplateId);
}
