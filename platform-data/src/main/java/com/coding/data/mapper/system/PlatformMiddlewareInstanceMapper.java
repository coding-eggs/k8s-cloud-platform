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

    /** 清掉应用来源（app_id / app_node_id）：实例转交中间件模块管理。updateById 不能置空列，所以单独一条。 */
    int clearApp(@Param("id") String id);

    /** 删除应用时一条语句转交它名下全部实例（含没有依赖行的旧实例） */
    int clearByApp(@Param("appId") String appId);

    int softDelete(@Param("id") String id);
}
