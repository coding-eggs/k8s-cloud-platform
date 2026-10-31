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

    /**
     * 用户所属的未删除租户列表（前端租户切换器数据源，/user/my-tenants）
     */
    List<PlatformTenant> listTenantsByUser(@Param("userId") String userId);

    List<PlatformTenant> listAll();

    /**
     * 校验租户在指定集群下是否有权限访问某命名空间
     */
    boolean hasNamespaceAccess(@Param("tenantId") String tenantId, @Param("clusterId") String clusterId, @Param("namespace") String namespace);

    /**
     * 反查：这个命名空间归属哪个租户。
     *
     * <p>用途是<b>归属标注</b>（审计 / 列表展示），不是授权 —— 平台侧能不能碰它由身份决定，
     * 不看这张表（见 {@code ResourceAccessResolver}）。未分配给任何租户 → {@code null}，是合法状态。
     *
     * <p>唯一键改成 {@code (cluster_id, namespace)} 之后至多一行；在那之前按
     * {@code create_time, id} 取最早的一条，保证结果确定（与迁移脚本「保留最早」同一口径）。
     */
    String selectTenantIdByClusterAndNs(@Param("clusterId") String clusterId,
                                        @Param("namespace") String namespace);
}