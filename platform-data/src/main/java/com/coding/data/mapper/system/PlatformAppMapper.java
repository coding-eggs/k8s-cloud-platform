package com.coding.data.mapper.system;

import com.coding.data.models.system.PlatformApp;
import org.apache.ibatis.annotations.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/** 应用实例（表 platform_app）。 */
@Repository
public interface PlatformAppMapper {

    int insert(PlatformApp record);

    int updateById(PlatformApp record);

    PlatformApp selectByPrimaryKey(@Param("id") String id);

    /** namespace 为空 = 该集群全部 */
    List<PlatformApp> selectByCluster(@Param("clusterId") String clusterId,
                                      @Param("namespace") String namespace);
}
