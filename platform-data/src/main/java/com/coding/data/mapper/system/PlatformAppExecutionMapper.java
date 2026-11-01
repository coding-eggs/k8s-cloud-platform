package com.coding.data.mapper.system;

import com.coding.data.models.system.PlatformAppExecution;
import org.apache.ibatis.annotations.Param;
import org.springframework.stereotype.Repository;

import java.util.Date;
import java.util.List;

/** 应用发布执行（表 platform_app_execution）。 */
@Repository
public interface PlatformAppExecutionMapper {

    /** 全部活动执行（PENDING / RUNNING / INTERRUPTED），按 id 升序 */
    List<PlatformAppExecution> selectActive();

    /** 某应用的活动执行（没有返回 null） */
    PlatformAppExecution selectActiveByApp(@Param("appId") String appId);

    /** 某应用最近一条执行（含已结束） */
    PlatformAppExecution selectLatestByApp(@Param("appId") String appId);

    int insert(PlatformAppExecution record);

    int updateById(PlatformAppExecution record);

    /** 抢租约：仅当 lease_until 为空或早于 now 时成功；返回 0 表示被别人持有。 */
    int claim(@Param("id") Long id, @Param("now") Date now, @Param("leaseUntil") Date leaseUntil);

    PlatformAppExecution selectById(@Param("id") Long id);

    /** 清租约（updateById 写不了 null）。每步结束调用，不必等过期。 */
    int releaseLease(@Param("id") Long id);

    /**
     * 续跑：仅 FAILED / INTERRUPTED 可重开；清 finished_at / message / lease_until。
     * started_at 重置为 startedAt，因为 HEALTH 超时按它计时，不重置的话中断久了一续跑就判超时。
     * 返回 0 表示状态已变（并发续跑）。
     */
    int reopen(@Param("id") Long id, @Param("status") String status, @Param("startedAt") Date startedAt);
}
