package com.coding.data.mapper.system;

import com.coding.data.models.system.PlatformMiddlewareOperation;
import org.apache.ibatis.annotations.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/** 中间件实例操作记录（表 platform_middleware_operation）。 */
@Repository
public interface PlatformMiddlewareOperationMapper {

    int insert(PlatformMiddlewareOperation record);

    int updateById(PlatformMiddlewareOperation record);

    PlatformMiddlewareOperation selectByPrimaryKey(@Param("id") Long id);

    /** 某个实例的操作记录（新的在前） */
    List<PlatformMiddlewareOperation> selectByInstance(@Param("instanceId") String instanceId);

    /** 超过 minutes 分钟仍在 PENDING/RUNNING 的操作（进程重启留下的孤儿） */
    List<PlatformMiddlewareOperation> selectStaleRunning(@Param("minutes") int minutes);
}
