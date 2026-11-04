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

    /**
     * 抢租约并钉住归属：只有**上一拍就是本实例**、租约为空、或租约已过期时才成功。
     *
     * <p>归属必须在创建 / 继续时就写好（那时把租约也给出去）：否则别的实例会在「刚建好、
     * 还没人推进」的那几秒抢到一条本进程才持有凭据的执行，然后因为没凭据把它判成中断。
     *
     * <p>返回 0 = 别人正持有（另一台还活着），这一拍什么都不做。
     */
    int claim(@Param("id") Long id, @Param("ownerId") String ownerId,
              @Param("now") Date now, @Param("leaseUntil") Date leaseUntil);

    PlatformAppExecution selectById(@Param("id") Long id);

    /**
     * 续跑：仅 FAILED / INTERRUPTED 可重开；清 finished_at / message。
     * started_at 重置为 startedAt，因为 HEALTH 超时按它计时，不重置的话中断久了一续跑就判超时。
     * 同一事务里把**归属和租约**一并写给续跑方：否则刚交接完就会被另一台抢走（连点「继续」的根因）。
     * 同一条语句里还要求它仍是该应用最新的执行（派生表绕开 MySQL 的 1093），否则返回 0。
     * 返回 0 表示状态已变（并发续跑）或已不是最新。
     */
    int reopen(@Param("id") Long id, @Param("status") String status, @Param("startedAt") Date startedAt,
               @Param("ownerId") String ownerId, @Param("leaseUntil") Date leaseUntil);
}
