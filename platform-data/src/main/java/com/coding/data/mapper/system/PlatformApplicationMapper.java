package com.coding.data.mapper.system;

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

    PlatformApplication selectById(@Param("id") String id);

    /** clusterId 为空 = 该租户在所有集群的应用 */
    List<PlatformApplication> selectByTenant(@Param("tenantId") String tenantId,
                                             @Param("clusterId") String clusterId);

    int softDelete(@Param("id") String id, @Param("deletedAt") Date deletedAt);
}
