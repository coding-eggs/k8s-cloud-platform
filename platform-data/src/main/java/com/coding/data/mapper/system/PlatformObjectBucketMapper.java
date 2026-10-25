package com.coding.data.mapper.system;

import com.coding.data.models.system.PlatformObjectBucket;
import org.apache.ibatis.annotations.Param;
import org.springframework.stereotype.Repository;

import java.util.Date;
import java.util.List;

/** 对象存储桶台账（表 platform_object_bucket）。 */
@Repository
public interface PlatformObjectBucketMapper {

    int insert(PlatformObjectBucket record);

    PlatformObjectBucket selectById(@Param("id") String id);

    /** 本集群全部未删除的桶（管理员视图） */
    List<PlatformObjectBucket> selectByCluster(@Param("clusterId") String clusterId);

    /** 某租户在本集群未删除的桶（租户视图） */
    List<PlatformObjectBucket> selectByTenant(@Param("clusterId") String clusterId,
                                              @Param("tenantId") String tenantId);

    PlatformObjectBucket selectActiveByBucket(@Param("clusterId") String clusterId,
                                              @Param("bucket") String bucket);

    int countActiveByTenant(@Param("clusterId") String clusterId,
                            @Param("tenantId") String tenantId);

    int softDelete(@Param("id") String id, @Param("deletedAt") Date deletedAt);

    /** 改桶容量上限（null = 不限）；只动台账，S3 侧由调用方先行设置 */
    int updateQuota(@Param("id") String id, @Param("quotaBytes") Long quotaBytes,
                    @Param("updatedAt") Date updatedAt);
}
