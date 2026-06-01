package com.coding.data.mapper.auth;

import com.coding.data.models.auth.PlatformPermission;
import org.springframework.stereotype.Repository;

@Repository
public interface PlatformPermissionMapper {
    int deleteByPrimaryKey(String id);

    int insert(PlatformPermission record);

    int insertSelective(PlatformPermission record);

    PlatformPermission selectByPrimaryKey(String id);

    int updateByPrimaryKeySelective(PlatformPermission record);

    int updateByPrimaryKey(PlatformPermission record);
}