package com.coding.data.models.auth;

import java.io.Serializable;
import java.util.Date;
import lombok.Data;

/**
 * platform_user
 */
@Data
public class PlatformUser implements Serializable {

    private String id;

    private String username;

    private String password;

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

    private Date lastLoginAt;

    private Long createdBy;

    private Long updatedBy;

    private Date createdAt;

    private Date updatedAt;

}