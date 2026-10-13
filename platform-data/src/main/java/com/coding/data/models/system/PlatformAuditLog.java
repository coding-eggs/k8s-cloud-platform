package com.coding.data.models.system;

import lombok.Data;

import java.util.Date;

/**
 * 平台审计日志（表 platform_audit_log）。
 *
 * <p>设计见 docs/superpowers/specs/2026-10-13-audit-log-design.md。
 *
 * <p><b>审计记录必须自包含</b>：一旦落库就不得依赖其他表才读得懂，所以 actorDisplayName 冗余存
 * （账号改名或删除后，历史记录仍要能回答「当时是谁做的」）。但只冗余这一个 ——
 * resource_type 从 action 就能推出来，不再单存一列（避免同一事实两处）。
 *
 * <p>{@code action} 的语义随 {@code result} 变：写操作存动作码 {@code <resource>:<verb>}，
 * {@code DENIED} 存被拒的权限码。两者都是 {@code xxx:yyy} 形状，故不需要另加 denied_code 列。
 *
 * <p>{@code id} 用自增（非本仓惯用的 ULID）：追加型日志表用自增，插入更省、时间序扫描更顺。
 *
 * <p>{@code result} 是 String 而非枚举：{@code AuditResult} 在 platform-api，
 * 而本实体在 platform-data —— 依赖方向不允许反向引用，故由调用方写 {@code AuditResult.XXX.name()}。
 */
@Data
public class PlatformAuditLog {

    private Long id;

    private Date occurredAt;

    /** 操作者账号；取不到身份时记字面量 unknown（不记 NULL —— 与「平台侧无租户操作」的 NULL 语义区分开） */
    private String actorUsername;

    private String actorDisplayName;

    /** 被操作的租户（代操作时是目标租户；平台侧无租户操作为 NULL） */
    private String tenantId;

    private String clusterId;

    private String namespace;

    /** 写操作 = 动作码；DENIED = 被拒的权限码 */
    private String action;

    /** 目标对象名；DENIED 为空（拒绝发生在方法调用之前） */
    private String targetName;

    /** SUCCESS / FAILED / DENIED（见 com.coding.platformapi.audit.AuditResult） */
    private String result;

    private String errorCode;

    private String errorMsg;

    /** 仅 DENIED 有值 */
    private String httpMethod;

    /** 仅 DENIED 有值 */
    private String requestPath;

    private String clientIp;

    private String userAgent;

    /** 耗时；DENIED 为 NULL */
    private Integer durationMs;
}
