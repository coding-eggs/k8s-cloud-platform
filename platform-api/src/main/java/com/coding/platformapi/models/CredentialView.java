package com.coding.platformapi.models;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.Date;

/**
 * 凭据的**对外视图**：列表与创建/更新的响应都是它。
 *
 * <p>⚠️ <b>本类永不携带凭据内容</b> —— 没有 username、没有密码、没有密文（spec §5 已定决策
 * 「永不回显」）。认证字段只以 {@link #hasSecret}（有没有配）与 {@link #fingerprint}
 * （哪一份，用于区分「改了凭据」与「只改了描述」）两种派生形态出现。
 *
 * <p>{@code CredentialServiceTest} 里有一条**反射断言**钉住这一点：名字含
 * {@code secret} / {@code password} / {@code username} 的字段都会让测试失败 ——
 * 唯一的例外是 {@code Boolean} 类型的 {@link #hasSecret}（它是「是否已配置」的标志，不是内容）。
 * 加字段前先读那段注释。
 */
@Data
public class CredentialView {

    private String id;

    private String name;

    /** GIT / REGISTRY */
    private String type;

    @Schema(description = "host 基址")
    private String endpoint;

    /** BASIC */
    private String authType;

    @Schema(description = "是否已配置凭据内容（不是内容本身）")
    private Boolean hasSecret;

    @Schema(description = "凭据指纹：明文认证字段 JSON 的 SHA-256 前 16 位")
    private String fingerprint;

    private String description;

    private Boolean enabled;

    @Schema(description = "上次测试通过的时刻；测失败不改它")
    private Date lastVerifiedAt;

    @Schema(description = "上次测试的失败原因；成功则 null")
    private String lastVerifyError;

    private Date createdAt;

    private Date updatedAt;
}
