package com.coding.platformapi.audit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记一个**写操作**的 Service 方法，由 {@link AuditAspect} 记录审计。
 *
 * <p>设计见 docs/superpowers/specs/2026-10-13-audit-log-design.md。
 *
 * <p><b>标在 Service 层，不标 Controller</b>：业务全在 Service（见 docs/development/backend-layering.md），
 * 而且只有 Service 方法抛的 {@code CloudPlatformException} 才是真正的业务失败。
 *
 * <p><b>不要标在流式方法上</b>（Pod / Node 日志流、exec）—— 那会把流式响应拖进审计路径。
 *
 * <p>范围以外的写操作有意不标（spec §9：不做覆盖率护栏测试，漏标不会被发现，代价已显式接受）。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Auditable {

    /**
     * 动作码，取 {@link AuditAction} 里的常量（如 {@code AuditAction.WORKLOAD_DELETE}）。
     */
    String action();

    /**
     * 目标对象名的 SpEL，如 {@code "#name"} / {@code "#req.name"}。
     * 留空则按命名约定探测（方法参数名，或单个 DTO 参数的一级字段名 {@code name}）。
     */
    String target() default "";

    /**
     * 上下文（tenantId / clusterId / namespace）的 SpEL，逗号分隔，按此顺序填充。
     * 留空则按约定探测同名的参数/一级字段。
     */
    String scope() default "";
}
