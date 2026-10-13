package com.coding.data.mapper.system;

import com.coding.data.models.system.PlatformCredential;
import org.apache.ibatis.annotations.Param;
import org.springframework.stereotype.Repository;

import java.util.Date;
import java.util.List;

/**
 * 凭据读写。设计见 docs/superpowers/specs/2026-10-13-credential-management-design.md。
 *
 * <p><b>{@link #update} 与 {@link #updateVerifyResult} 分开</b>：前者写用户可改的列，
 * 后者只写测试结果两列。合成一个「整体更新」会让「保存」与「测试」互相覆盖 ——
 * 用户在测试结果落库前保存了表单，就会把上次的验证时间抹掉。
 */
@Repository
public interface PlatformCredentialMapper {

    int insert(PlatformCredential c);

    /**
     * 只写用户可改列（name、type、endpoint、auth_type、secret 的两个列、description、enabled），
     * <b>不碰 last_verified_at / last_verify_error</b>。
     */
    int update(PlatformCredential c);

    int deleteById(@Param("id") String id);

    PlatformCredential selectById(@Param("id") String id);

    PlatformCredential selectByName(@Param("name") String name);

    List<PlatformCredential> selectAll();

    /** 测试结果回写：ok=true 时置 last_verified_at 并清空 error，ok=false 时只写 error */
    int updateVerifyResult(@Param("id") String id,
                           @Param("ok") boolean ok,
                           @Param("error") String error,
                           @Param("at") Date at);
}
