package com.coding.data.mapper.system;

import com.coding.data.models.system.PlatformAppOperation;
import org.apache.ibatis.annotations.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/** 应用操作记录（表 platform_app_operation）。只追加与更新状态，不删除。 */
@Repository
public interface PlatformAppOperationMapper {

    int insert(PlatformAppOperation record);

    int updateById(PlatformAppOperation record);

    PlatformAppOperation selectByPrimaryKey(@Param("id") Long id);

    PlatformAppOperation selectLatestSuccess(@Param("appId") String appId);

    List<PlatformAppOperation> selectByApp(@Param("appId") String appId);
}
