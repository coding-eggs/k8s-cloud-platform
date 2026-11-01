package com.coding.data.mapper.system;

import com.coding.data.models.system.PlatformAppRelease;
import org.apache.ibatis.annotations.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/** 应用发布（表 platform_app_release）。 */
@Repository
public interface PlatformAppReleaseMapper {

    PlatformAppRelease selectByNo(@Param("appId") String appId, @Param("releaseNo") Integer releaseNo);

    /** 按发布号倒序 */
    List<PlatformAppRelease> selectByApp(@Param("appId") String appId);

    /** 没有发布时返回 null */
    Integer selectMaxNo(@Param("appId") String appId);

    int insert(PlatformAppRelease record);

    /** 只更新 status / note，其余内容不可变。 */
    int updateById(PlatformAppRelease record);
}
