package com.coding.data.mapper.system;

import com.coding.data.models.system.PlatformApplicationResource;
import org.apache.ibatis.annotations.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/** 应用资源台账（表 platform_application_resource）。 */
@Repository
public interface PlatformApplicationResourceMapper {

    int batchInsert(@Param("rows") List<PlatformApplicationResource> rows);

    List<PlatformApplicationResource> selectByRevision(@Param("appId") String appId,
                                                       @Param("revision") Integer revision);

    /** 本应用历史上写过的所有对象（用于「对象已存在但不是我们的」冲突判定） */
    List<PlatformApplicationResource> selectByApp(@Param("appId") String appId);

    int deleteByAppRevision(@Param("appId") String appId, @Param("revision") Integer revision);
}
