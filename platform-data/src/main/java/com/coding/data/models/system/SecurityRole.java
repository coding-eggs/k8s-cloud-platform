package com.coding.data.models.system;

import lombok.Data;
import org.jspecify.annotations.Nullable;
import org.springframework.security.core.GrantedAuthority;

import java.io.Serializable;

/**
 * 租户域角色。
 *
 * <p>必须实现 {@link Serializable}：登录态（SecurityContext，其 authorities 含本类实例）会被
 * Spring Session 以 JDK 序列化写入 Redis 会话；图内任一节点不可序列化都会导致保存失败
 * （{@code NotSerializableException}）。字段均为 String，直接可序列化。
 */
@Data
public class SecurityRole implements GrantedAuthority, Serializable {

    private static final long serialVersionUID = 1L;

    private String tenantName;

    private String roleCode;

    private String roleName;

    private String serviceAccount;


    @Override
    public @Nullable String getAuthority() {
        return tenantName + ":" + roleCode;
    }
}
