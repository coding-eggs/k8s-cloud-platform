package com.coding.platformapi.audit;

/**
 * 审计结果。写进 {@code platform_audit_log.result}（存 {@link #name()}）。
 *
 * <p>只有三个值：401 不记，所以没有「未认证」这一类（spec §9）。
 */
public enum AuditResult {

    /** 业务方法正常返回 */
    SUCCESS,

    /** 业务方法抛异常（典型是 CloudPlatformException） */
    FAILED,

    /** 被 Spring Security 拒绝（403），未进入业务方法 */
    DENIED
}
