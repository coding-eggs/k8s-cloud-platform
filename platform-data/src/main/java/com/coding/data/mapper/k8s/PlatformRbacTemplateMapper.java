package com.coding.data.mapper.k8s;

import com.coding.data.models.k8s.PlatformRbacTemplate;
import org.apache.ibatis.annotations.Param;
import org.springframework.stereotype.Repository;

import java.util.Date;
import java.util.List;

@Repository
public interface PlatformRbacTemplateMapper {

    int insert(PlatformRbacTemplate record);

    PlatformRbacTemplate selectByPrimaryKey(@Param("id") String id);

    PlatformRbacTemplate selectByName(@Param("name") String name);

    List<PlatformRbacTemplate> listAll();

    int updateByPrimaryKeySelective(PlatformRbacTemplate record);

    /**
     * 软删除（置 deleted_at）
     */
    int softDelete(@Param("id") String id, @Param("deletedAt") Date deletedAt);
}
