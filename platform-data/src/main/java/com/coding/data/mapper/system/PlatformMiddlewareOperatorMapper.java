package com.coding.data.mapper.system;

import com.coding.data.models.system.PlatformMiddlewareOperator;
import org.apache.ibatis.annotations.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/** 中间件 Operator 安装状态（表 platform_middleware_operator）。 */
@Repository
public interface PlatformMiddlewareOperatorMapper {

    int insert(PlatformMiddlewareOperator record);

    int updateById(PlatformMiddlewareOperator record);

    PlatformMiddlewareOperator selectByPrimaryKey(@Param("id") String id);

    PlatformMiddlewareOperator selectByClusterAndCode(@Param("clusterId") String clusterId,
                                                      @Param("code") String code);

    List<PlatformMiddlewareOperator> selectByCluster(@Param("clusterId") String clusterId);
}
