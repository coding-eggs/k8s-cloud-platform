package com.coding.data.mapper.auth;

import com.coding.data.models.auth.PlatformPermission;
import org.apache.ibatis.annotations.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface PlatformPermissionMapper {
    int deleteByPrimaryKey(String id);

    int insert(PlatformPermission record);

    int insertSelective(PlatformPermission record);

    PlatformPermission selectByPrimaryKey(String id);

    int updateByPrimaryKeySelective(PlatformPermission record);

    int updateByPrimaryKey(PlatformPermission record);

    List<PlatformPermission> selectAllActive();

    /**
     * 按 code 查询全部有效权限点行。
     * 注意：code 不再唯一（Task 4 起 ANY-of 语义，同一 code 可对应多个 URL 行），
     * 故返回列表而非单条；调用方须对该 code 的全部行一并处理。
     */
    List<PlatformPermission> selectAllByCode(@Param("code") String code);

    List<String> selectPermissionCodesByRoleIds(@Param("roleIds") Collection<String> roleIds);
}