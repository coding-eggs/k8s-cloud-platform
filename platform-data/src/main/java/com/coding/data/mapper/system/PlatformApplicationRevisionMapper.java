package com.coding.data.mapper.system;

import com.coding.data.models.system.PlatformApplicationRevision;
import org.apache.ibatis.annotations.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/** 应用版本快照（表 platform_application_revision）。 */
@Repository
public interface PlatformApplicationRevisionMapper {

    int insert(PlatformApplicationRevision record);

    int updateById(PlatformApplicationRevision record);

    List<PlatformApplicationRevision> selectByApp(@Param("appId") String appId);

    PlatformApplicationRevision selectByRevision(@Param("appId") String appId,
                                                 @Param("revision") Integer revision);

    /** 没有版本时返回 null（调用方按 0 处理） */
    Integer selectMaxRevision(@Param("appId") String appId);
}
