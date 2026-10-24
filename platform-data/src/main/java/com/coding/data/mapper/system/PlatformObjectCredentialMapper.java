package com.coding.data.mapper.system;

import com.coding.data.models.system.PlatformObjectCredential;
import org.apache.ibatis.annotations.Param;
import org.springframework.stereotype.Repository;

import java.util.Date;
import java.util.List;

/** 对象存储凭据台账（表 platform_object_credential）。密钥本体在 K8s Secret，不在这张表。 */
@Repository
public interface PlatformObjectCredentialMapper {

    int insert(PlatformObjectCredential record);

    PlatformObjectCredential selectById(@Param("id") String id);

    /** 某桶下仍然生效的凭据 */
    List<PlatformObjectCredential> selectActiveByBucket(@Param("bucketId") String bucketId);

    /** 某租户在本集群仍然生效的凭据（桶详情之外的聚合视图用） */
    List<PlatformObjectCredential> selectActiveByTenant(@Param("clusterId") String clusterId,
                                                        @Param("tenantId") String tenantId);

    int revoke(@Param("id") String id, @Param("revokedAt") Date revokedAt);
}
