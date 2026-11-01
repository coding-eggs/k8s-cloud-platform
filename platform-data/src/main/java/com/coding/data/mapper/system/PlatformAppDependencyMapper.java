package com.coding.data.mapper.system;

import com.coding.data.models.system.PlatformAppDependency;
import org.apache.ibatis.annotations.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/** 应用的依赖（表 platform_app_dependency）。 */
@Repository
public interface PlatformAppDependencyMapper {

    List<PlatformAppDependency> selectByApp(@Param("appId") String appId);

    PlatformAppDependency selectByKey(@Param("appId") String appId, @Param("depKey") String depKey);

    int insert(PlatformAppDependency record);

    int updateById(PlatformAppDependency record);

    int deleteById(@Param("id") Long id);
}
