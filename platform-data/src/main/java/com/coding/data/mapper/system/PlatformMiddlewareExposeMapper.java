package com.coding.data.mapper.system;

import com.coding.data.models.system.PlatformMiddlewareExpose;
import org.apache.ibatis.annotations.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/** 中间件端点对外暴露（表 platform_middleware_expose）。 */
@Repository
public interface PlatformMiddlewareExposeMapper {

    List<PlatformMiddlewareExpose> selectByInstance(@Param("instanceId") String instanceId);

    /** 同集群（未删除实例）里 NodePort 方式的行：给 NodePort 分配避让 */
    List<PlatformMiddlewareExpose> selectNodePortsInCluster(@Param("clusterId") String clusterId);

    /** 按 (instance_id, endpoint_key) 插入或覆盖 mode / node_port */
    int upsert(PlatformMiddlewareExpose record);

    int deleteByKey(@Param("instanceId") String instanceId, @Param("endpointKey") String endpointKey);

    int deleteByInstance(@Param("instanceId") String instanceId);
}
