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

    /** 回填渲染结果，只写一次（manifest 为 null 或空白才写，已有值时不动），保持发布内容不可变。 */
    int updateRendered(@Param("id") Long id, @Param("manifest") String manifest,
                       @Param("objectsJson") String objectsJson);

    /** 只更新 status / note，其余内容不可变。 */
    int updateById(PlatformAppRelease record);
}
