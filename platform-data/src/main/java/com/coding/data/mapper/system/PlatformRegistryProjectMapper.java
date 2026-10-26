package com.coding.data.mapper.system;

import com.coding.data.models.system.PlatformRegistryProject;
import org.apache.ibatis.annotations.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/** 租户镜像项目台账（表 platform_registry_project）。 */
@Repository
public interface PlatformRegistryProjectMapper {

    int insert(PlatformRegistryProject record);

    /** 同步结果回写：host / project / credential / robot / status / message / last_synced_at */
    int updateResult(PlatformRegistryProject record);

    PlatformRegistryProject selectById(@Param("id") String id);

    List<PlatformRegistryProject> selectByCluster(@Param("clusterId") String clusterId);

    /** tenantId 为 null 时匹配 tenant_id is null */
    PlatformRegistryProject selectByClusterAndTenant(@Param("clusterId") String clusterId,
                                                     @Param("tenantId") String tenantId);

    PlatformRegistryProject selectByClusterAndProject(@Param("clusterId") String clusterId,
                                                      @Param("projectName") String projectName);

    PlatformRegistryProject selectByCredentialId(@Param("credentialId") String credentialId);

    List<PlatformRegistryProject> selectByTenant(@Param("tenantId") String tenantId);

    /** 凭据 → 项目名（流水线连接下拉展示用；量小，一次全取） */
    List<PlatformRegistryProject> selectAll();

    int deleteById(@Param("id") String id);
}
