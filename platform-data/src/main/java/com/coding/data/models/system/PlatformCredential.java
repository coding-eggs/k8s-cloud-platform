package com.coding.data.models.system;

import lombok.Data;

import java.util.Date;

/**
 * 平台级凭据（表 platform_credential）：git / registry 连接。
 *
 * <p>设计见 docs/superpowers/specs/2026-10-13-credential-management-design.md。
 *
 * <p><b>认证字段不建模成强类型列</b>：{@link #secretEncrypted} 解密后是一段 JSON
 * （v1 形如 {@code {"username":"…","secret":"…"}}）。这样加 SSH 等新认证方式是「blob 里多两个键」，
 * 零迁移；而 username/password 强类型列的唯一好处是「DB 里能直接读到用户名」——
 * 那本来就是不该可读的敏感数据。
 *
 * <p><b>没有 tenant_id / scope</b>：纯平台级 + 全局可用（spec 已定决策）。
 * <b>没有 deletedAt</b>：硬删除，理由见 spec「删除语义」一行。
 */
@Data
public class PlatformCredential {

    /** ULIDGenerator.generateULID() */
    private String id;

    private String name;

    /** GIT / REGISTRY */
    private String type;

    /** host 基址（spec §1.1） */
    private String endpoint;

    /** BASIC（v1 唯一实现） */
    private String authType;

    /** AES 密文，含 "v1:" 前缀；明文是认证字段 JSON */
    private String secretEncrypted;

    /** SHA-256(认证字段 JSON) 前 16 hex */
    private String secretFingerprint;

    private String description;

    /** 1 启用 / 0 停用 */
    private Integer enabled;

    /**
     * 1 = 允许租户在流水线的 git / 推送节点里引用这条连接；**默认 0（关）**。
     * S0.2 特意留着「租户侧没有凭据码」这条边界，CI 正面撞上了它 —— 所以改成逐条显式授权
     * （spec §5.3）。它是个**开关**，不是凭据内容，所以可以回显。
     */
    private Integer usableByPipelines;

    /** 上次测试通过的时刻 */
    private Date lastVerifiedAt;

    /** 上次测试的失败原因；成功则 NULL */
    private String lastVerifyError;

    private Date createdAt;

    private Date updatedAt;
}
