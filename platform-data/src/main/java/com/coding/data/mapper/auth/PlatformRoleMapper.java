package com.coding.data.mapper.auth;

import com.coding.data.models.auth.PlatformRole;
import com.coding.data.models.system.SecurityRole;
import org.apache.ibatis.annotations.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PlatformRoleMapper {
    int deleteByPrimaryKey(String id);

    int insert(PlatformRole record);

    int insertSelective(PlatformRole record);

    PlatformRole selectByPrimaryKey(String id);

    int updateByPrimaryKeySelective(PlatformRole record);

    int updateByPrimaryKey(PlatformRole record);

    List<SecurityRole> selectTenantRole(String userId);

    PlatformRole selectByCode(@Param("code") String code);

    /**
     * 全部未删除角色（配置页角色列表）
     */
    List<PlatformRole> listAllActive();
}