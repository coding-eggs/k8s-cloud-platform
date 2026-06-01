package com.coding.data.models.system;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;

import org.jspecify.annotations.Nullable;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.User;

import java.util.Collection;
import java.util.Date;

@EqualsAndHashCode(callSuper = true)
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class SecurityUser extends User {

    private String id;

    private String displayName;

    private String email;

    /**
     * 1正常 0禁用
     */
    private Byte status;

    /**
     * 用户类型
     */
    private String type;

    /**
     * 来源：LOCAL/LDAP/OIDC
     */
    private String source;

    // Jackson 需要的注解构造函数
    @JsonCreator
    public SecurityUser(
            @JsonProperty("username") String username,
            @JsonProperty("password") String password,
            @JsonProperty("enabled") boolean enabled,
            @JsonProperty("accountNonExpired") boolean accountNonExpired,
            @JsonProperty("credentialsNonExpired") boolean credentialsNonExpired,
            @JsonProperty("accountNonLocked") boolean accountNonLocked,
            @JsonProperty("authorities") Collection<? extends GrantedAuthority> authorities,
            @JsonProperty("id") String id,
            @JsonProperty("displayName") String displayName,
            @JsonProperty("email") String email,
            @JsonProperty("status") Byte status,
            @JsonProperty("type") String type,
            @JsonProperty("source") String source) {
        super(username, password, enabled, accountNonExpired,
                credentialsNonExpired, accountNonLocked, authorities);

        this.id = id;
        this.displayName = displayName;
        this.email = email;
        this.status = status;
        this.type = type;
        this.source = source;

    }


}
