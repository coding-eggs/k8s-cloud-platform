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

    /**
     * 该租户**启用的 registry 凭据**（{@code HARBOR} / {@code DOCKERHUB}）。
     *
     * <p>用途：拼命名空间的默认拉取凭据（spec 2026-10-31 §6.3）。
     * {@code GIT} 与停用的行不算 —— 它们不是拉镜像用的。
     */
    List<PlatformCredential> listEnabledRegistryByTenant(@Param("tenantId") String tenantId);

    /** 测试结果回写：ok=true 时置 last_verified_at 并清空 error，ok=false 时只写 error */
    int updateVerifyResult(@Param("id") String id,
                           @Param("ok") boolean ok,
                           @Param("error") String error,
                           @Param("at") Date at);

    /** 租户停用/启用时跟随：只动该租户的 Harbor 自动凭据 */
    int updateEnabledByTenantAndSource(@Param("tenantId") String tenantId,
                                       @Param("source") String source,
                                       @Param("enabled") int enabled);

    /** 删租户时回收它名下的 Harbor 自动凭据 */
    int deleteByTenantAndSource(@Param("tenantId") String tenantId,
                                @Param("source") String source);
}
