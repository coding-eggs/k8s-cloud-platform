package com.coding.data.mapper.system;

import com.coding.data.models.system.PlatformAppExecution;
import org.apache.ibatis.annotations.Param;
import org.springframework.stereotype.Repository;

import java.util.Date;
import java.util.List;

/** 应用发布执行（表 platform_app_execution）。 */
@Repository
public interface PlatformAppExecutionMapper {

    /**
     * 被新发布取代：仅当仍是 INTERRUPTED 时置 FAILED。返回 0 表示状态已变（用户刚好点了继续）。
     */
    int supersedeInterrupted(@Param("id") Long id, @Param("message") String message,
                             @Param("finishedAt") Date finishedAt);


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
     * 同一条语句里还要求它仍是该应用最新的执行（派生表绕开 MySQL 的 1093），否则返回 0。
     * 返回 0 表示状态已变（并发续跑）或已不是最新。
     */
    int reopen(@Param("id") Long id, @Param("status") String status, @Param("startedAt") Date startedAt);
}
