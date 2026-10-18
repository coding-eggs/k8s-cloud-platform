package com.coding.data.mapper.system;

import com.coding.data.models.system.PlatformComponentOperation;
import org.apache.ibatis.annotations.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/** 组件安装/升级操作记录（S3.6）。只追加与更新状态，不删除。 */
@Repository
public interface PlatformComponentOperationMapper {

    int insert(PlatformComponentOperation record);

    /** 只更新可变的几个字段：status/message/log_tail/finished_at */
    int updateById(PlatformComponentOperation record);

    PlatformComponentOperation selectByPrimaryKey(@Param("id") Long id);

    /** 每个组件最近一次操作（含进行中的），组件 tab 首屏一次拿全 */
    List<PlatformComponentOperation> selectLatestPerComponent(@Param("clusterId") String clusterId);

    /**
     * 每个组件最近一次**成功**操作。编辑表单用它的 {@code values_snapshot.__platform}
     * 预填"当前生效"的告警回调地址 / Thanos Receive 地址。
     */
    List<PlatformComponentOperation> selectLatestSuccesses(@Param("clusterId") String clusterId);

    /** 最近一次成功的操作（升级时用它取 fromVersion） */
    PlatformComponentOperation selectLatestSuccess(@Param("clusterId") String clusterId,
                                                   @Param("componentCode") String componentCode);

    int countRunning(@Param("clusterId") String clusterId, @Param("componentCode") String componentCode);

    List<PlatformComponentOperation> selectStaleRunning(@Param("minutes") int minutes);
}
