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

    /** GIT / HARBOR（本批只落已实现的方言值，见 CredentialService.TYPES） */
    private String type;

    @Schema(description = "host 基址")
    private String endpoint;

    /**
     * endpoint 归一后的 {@code host[:port]}（去 scheme、去路径）。
     *
     * <p>派生字段、不含任何凭据内容 —— 镜像仓库页靠它把连接挂到左栏的仓库行上，
     * 省得前端再实现一遍归一规则（那就成了第二个真相源）。
     */
    private String host;

    /**
     * 这条连接固定的 Harbor 项目（平台建的租户 robot 才有，如 {@code platform} / {@code t-xxx}）。
     *
     * <p>镜像仓库页的浏览要它：根层每一行项目对应一条 robot，点进去得换成**那个项目自己的**
     * 凭据 —— 平台 robot 没有别家项目的权限。只有 {@code /credential/list} 会填它
     * （创建/更新的响应里是 null，页面保存后本来就会重新拉列表）。
     */
    private String project;

    /** BASIC */
    private String authType;

    @Schema(description = "是否已配置凭据内容（不是内容本身）")
    private Boolean hasSecret;

    @Schema(description = "凭据指纹：明文认证字段 JSON 的 SHA-256 前 16 位")
    private String fingerprint;

    private String description;

    private Boolean enabled;

    /**
     * 1 = 允许租户在流水线的 git / 推送节点里引用它，0 = 不允许（默认）。
     * ⚠️ 它**不是凭据内容**，是个开关，所以可以回显（spec §5.3）。
     */
    private Integer usableByPipelines;

    @Schema(description = "上次测试通过的时刻；测失败不改它")
    private Date lastVerifiedAt;

    @Schema(description = "上次测试的失败原因；成功则 null")
    private String lastVerifyError;

    /** 归属租户；null = 平台级 */
    private String tenantId;

    /** 租户名（列表展示用；平台级为 null） */
    private String tenantName;

    /** MANUAL / HARBOR（Harbor 自动维护，页面只读） */
    private String source;

    private Date createdAt;

    private Date updatedAt;
}
