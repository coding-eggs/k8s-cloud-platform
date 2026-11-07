package com.coding.data.mapper.system;

import java.util.Collection;
import com.coding.data.models.system.StatusCount;
import com.coding.data.models.system.PlatformApplication;
import org.apache.ibatis.annotations.Param;
import org.springframework.stereotype.Repository;

import java.util.Date;
import java.util.List;

/** 应用台账（表 platform_application）。 */
@Repository
public interface PlatformApplicationMapper {

    int insert(PlatformApplication record);

    int updateById(PlatformApplication record);

    /** 应用设置的乐观锁写：version 不匹配返回 0；settingsJson 为 null = 清空 */
    int updateSettings(@Param("id") String id, @Param("settingsJson") String settingsJson,
                       @Param("version") Integer version, @Param("updatedAt") Date updatedAt);

    PlatformApplication selectById(@Param("id") String id);

    /** clusterId 为空 = 该租户在所有集群的应用 */
    List<PlatformApplication> selectByTenant(@Param("tenantId") String tenantId,
                                             @Param("clusterId") String clusterId);

    int softDelete(@Param("id") String id, @Param("deletedAt") Date deletedAt);

    /** 概览计数；clusterId 为 null = 全部集群，namespaces 为 null = 不限、空集合 = 无结果 */
    List<StatusCount> countByStatus(@Param("clusterId") String clusterId,
                                    @Param("namespaces") Collection<String> namespaces);
}
