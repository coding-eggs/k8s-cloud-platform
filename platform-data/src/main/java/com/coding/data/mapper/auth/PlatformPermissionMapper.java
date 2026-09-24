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

    PlatformPermission selectByCode(@Param("code") String code);

    List<String> selectPermissionCodesByRoleIds(@Param("roleIds") Collection<String> roleIds);
}