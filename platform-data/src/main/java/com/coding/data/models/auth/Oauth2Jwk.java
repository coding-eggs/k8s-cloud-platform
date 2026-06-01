package com.coding.data.models.auth;

import java.io.Serializable;
import java.util.Date;
import lombok.Data;

/**
 * oauth2_jwk
 */
@Data
public class Oauth2Jwk implements Serializable {
    /**
     * 主键
     */
    private String id;

    /**
     * Key ID（必须与 JWK 内 kid 一致）
     */
    private String kid;

    /**
     * Key Type：RSA / EC / OKP
     */
    private String kty;

    /**
     * sig / enc
     */
    private String use;

    /**
     * 算法，如 RS256 / ES256 / RSA-OAEP-256
     */
    private String alg;

    /**
     * 曲线（EC/OKP）
     */
    private String crv;

    /**
     * 完整 JWK JSON（建议仅存私钥，需加密）
     */
    private String jwkJson;

    /**
     * ENABLED / DISABLED / EXPIRED
     */
    private String status;

    /**
     * 生效时间
     */
    private Date validFrom;

    /**
     * 失效时间
     */
    private Date validUntil;

    /**
     * 优先级（越小越优先）
     */
    private Integer priority;

    private Date createdAt;

    private Date updatedAt;

}