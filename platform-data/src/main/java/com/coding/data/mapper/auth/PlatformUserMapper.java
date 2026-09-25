package com.coding.data.mapper.auth;

import com.coding.data.models.auth.PlatformUser;
import org.apache.ibatis.annotations.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface PlatformUserMapper {
    int deleteByPrimaryKey(String id);

    int insert(PlatformUser record);

    int insertSelective(PlatformUser record);

    PlatformUser selectByPrimaryKey(String id);

    int updateByPrimaryKeySelective(PlatformUser record);

    int updateByPrimaryKey(PlatformUser record);

    PlatformUser selectByUsername(String username);

    /**
     * 全部未删除用户
     */
    List<PlatformUser> listAllActive();

    int updateLastLoginTime(@Param("id") String id,@Param("dateTime") LocalDateTime dateTime);
}