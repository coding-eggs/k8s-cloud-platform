package com.coding.data.mapper.auth;

import com.coding.data.models.auth.NamespaceRequest;
import org.apache.ibatis.annotations.*;
import java.util.List;

public interface NamespaceRequestMapper {
    @Select("""
        <script>SELECT * FROM platform_tenant_namespace WHERE lifecycle='ACTIVE'
        AND expires_at BETWEEN UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AND UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000+604800000
        <if test="tenant != null">AND tenant_id=#{tenant}</if> ORDER BY expires_at</script>
        """)
    List<com.coding.data.models.auth.PlatformTenantNamespace> soon(@Param("tenant") String tenant);

    @Insert("INSERT IGNORE INTO platform_namespace_reminder(allocation_id,expires_at,days_left,recipient) VALUES(#{allocation},#{until},#{days},#{user})")
    int deliver(@Param("allocation") String allocation, @Param("until") long until, @Param("days") int days, @Param("user") String user);
    @Insert("""
        INSERT INTO platform_namespace_request(id, tenant_id, applicant, kind, allocation_id, namespace, cluster_id,
          purpose, expires_at, quota_key, baseline, target_value, quota_items_json)
        VALUES(#{id}, #{tenantId}, #{applicant}, #{kind}, #{allocationId}, #{namespace}, #{clusterId},
          #{purpose}, #{expiresAt}, #{quotaKey}, #{baseline}, #{targetValue}, #{quotaItemsJson})
        """)
    int insert(NamespaceRequest row);

    @Select("SELECT * FROM platform_namespace_request WHERE tenant_id=#{tenantId} AND applicant=#{actor} ORDER BY created_at DESC,id DESC")
    List<NamespaceRequest> mine(@Param("tenantId") String tenantId, @Param("actor") String actor);

    @Select("SELECT * FROM platform_namespace_request WHERE tenant_id=#{tenantId} ORDER BY created_at DESC,id DESC")
    List<NamespaceRequest> list(String tenantId);

    @Select("SELECT * FROM platform_namespace_request WHERE id=#{id} FOR UPDATE")
    NamespaceRequest lock(String id);

    @Update("""
        UPDATE platform_namespace_request SET status=#{status},reason=#{reason},reviewer=#{reviewer},
          cluster_id=#{clusterId},allocation_id=#{allocationId},approved_value=#{approvedValue},quota_items_json=#{quotaItemsJson} WHERE id=#{id}
        """)
    int reviewed(NamespaceRequest row);
}
