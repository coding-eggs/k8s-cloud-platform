package com.coding.data.mapper.system;

import com.coding.data.models.system.PlatformApplicationReference;
import org.apache.ibatis.annotations.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/** 应用对平台对象的引用（表 platform_application_reference）。 */
@Repository
public interface PlatformApplicationReferenceMapper {

    int insert(PlatformApplicationReference record);

    int deleteByApp(@Param("appId") String appId);

    /** 去掉某个应用对某个对象的引用（依赖移除时用，不动其它引用） */
    int deleteByAppAndRef(@Param("appId") String appId, @Param("refType") String refType,
                          @Param("refId") String refId);

    List<PlatformApplicationReference> selectByApp(@Param("appId") String appId);

    /** 反查：这个平台对象被哪些应用引用 */
    List<PlatformApplicationReference> selectByRef(@Param("refType") String refType,
                                                   @Param("refId") String refId);
}
