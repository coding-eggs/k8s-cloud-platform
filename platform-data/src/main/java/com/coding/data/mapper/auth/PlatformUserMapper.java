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

    /** 管理员改资料：显示名 / 邮箱 / 状态（null 即写 null，用于清空；用户名不可改） */
    int updateProfile(@Param("id") String id, @Param("displayName") String displayName,
                      @Param("email") String email, @Param("status") Byte status);

    /** 软删除：置 deleted_at 并禁用；用户名唯一索引仍占着（不可复用） */
    int softDelete(@Param("id") String id);

    /**
     * 全部未删除用户
     */
    List<PlatformUser> listAllActive();

    int updateLastLoginTime(@Param("id") String id,@Param("dateTime") LocalDateTime dateTime);

    /**
     * 按 id 批量取（只取未删除的用户；调用方负责保证 ids 非空 —— `in ()` 是语法错误）
     */
    List<PlatformUser> selectByIds(@Param("ids") List<String> ids);
}