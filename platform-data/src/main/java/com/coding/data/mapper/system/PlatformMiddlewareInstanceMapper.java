package com.coding.data.mapper.system;

import com.coding.data.models.system.PlatformMiddlewareInstance;
import org.apache.ibatis.annotations.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/** 中间件实例台账（表 platform_middleware_instance）。 */
@Repository
public interface PlatformMiddlewareInstanceMapper {

    int insert(PlatformMiddlewareInstance record);

    int updateById(PlatformMiddlewareInstance record);

    PlatformMiddlewareInstance selectByPrimaryKey(@Param("id") String id);

    /** code 为空 = 该集群全部 */
    /** 应用画布建的实例：按「应用 + 节点」找最近一条（重试时旧失败行已软删） */
    PlatformMiddlewareInstance selectLatestByAppNode(@Param("appId") String appId,
                                                     @Param("appNodeId") String appNodeId);

    List<PlatformMiddlewareInstance> selectByCluster(@Param("clusterId") String clusterId,
                                                     @Param("code") String code);

    int softDelete(@Param("id") String id);
}
