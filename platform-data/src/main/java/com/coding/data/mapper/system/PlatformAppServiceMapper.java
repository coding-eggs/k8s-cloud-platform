package com.coding.data.mapper.system;

import com.coding.data.models.system.PlatformAppService;
import org.apache.ibatis.annotations.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/** 应用的服务（表 platform_app_service）。 */
@Repository
public interface PlatformAppServiceMapper {

    List<PlatformAppService> selectByApp(@Param("appId") String appId);

    PlatformAppService selectByKey(@Param("appId") String appId, @Param("serviceKey") String serviceKey);

    /** 同集群（未删除应用）里期望配置写了 nodePort 的服务行：给 NodePort 分配避让还没发布的端口。 */
    List<PlatformAppService> selectNodePortServicesInCluster(@Param("clusterId") String clusterId);

    int insert(PlatformAppService record);

    /** 按 id + version 乐观更新 desired_json / pending_delete，version 自增；返回 0 表示并发冲突。 */
    int updateWithVersion(PlatformAppService record);

    int deleteById(@Param("id") Long id);
}
