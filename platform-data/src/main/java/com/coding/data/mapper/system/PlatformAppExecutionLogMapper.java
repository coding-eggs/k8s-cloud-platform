package com.coding.data.mapper.system;

import com.coding.data.models.system.PlatformAppExecutionLog;
import org.apache.ibatis.annotations.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/** 发布执行的过程日志（表 platform_app_execution_log）。只追加，不改。 */
@Repository
public interface PlatformAppExecutionLogMapper {

    int insert(PlatformAppExecutionLog record);

    /** 某条发布的全部日志，按写入顺序 */
    List<PlatformAppExecutionLog> selectByRelease(@Param("appId") String appId, @Param("releaseNo") Integer releaseNo);

    /** 某次执行最近一条（健康检查去重用：内容没变就不重复写） */
    PlatformAppExecutionLog selectLastByExecution(@Param("executionId") Long executionId);

    /** 删应用时一并清掉 */
    int deleteByApp(@Param("appId") String appId);
}
