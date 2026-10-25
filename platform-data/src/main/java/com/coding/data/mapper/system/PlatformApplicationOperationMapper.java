package com.coding.data.mapper.system;

import com.coding.data.models.system.PlatformApplicationOperation;
import org.apache.ibatis.annotations.Param;
import org.springframework.stereotype.Repository;

/** 应用部署操作（表 platform_application_operation）。 */
@Repository
public interface PlatformApplicationOperationMapper {

    int insert(PlatformApplicationOperation record);

    int updateById(PlatformApplicationOperation record);

    PlatformApplicationOperation selectById(@Param("id") Long id);

    /** 某应用当前未结束的操作（没有返回 null） */
    PlatformApplicationOperation selectActiveByApp(@Param("appId") String appId);

    /**
     * worker 每次取最老的一条活操作。
     *
     * <p>一次只推进一条：并发推进会在 PRUNE 的差集上互相打架（一条按旧台账删、一条按新台账写）。
     * 单副本部署，不做分布式锁；多副本时这里需要改成 select ... for update skip locked。
     */
    PlatformApplicationOperation selectFirstActive();
}
