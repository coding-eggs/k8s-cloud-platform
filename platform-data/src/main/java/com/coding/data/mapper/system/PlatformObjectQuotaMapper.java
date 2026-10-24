package com.coding.data.mapper.system;

import com.coding.data.models.system.PlatformObjectQuota;
import org.apache.ibatis.annotations.Param;
import org.springframework.stereotype.Repository;

/** 对象存储按租户的桶配额（表 platform_object_quota）。没有记录 = 平台默认值。 */
@Repository
public interface PlatformObjectQuotaMapper {

    PlatformObjectQuota selectByClusterTenant(@Param("clusterId") String clusterId,
                                              @Param("tenantId") String tenantId);

    int upsert(PlatformObjectQuota record);
}
