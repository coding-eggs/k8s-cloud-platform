package com.coding.platformapi.audit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记一个**写操作的 Controller 端点**，由 {@link AuditAspect} 记录审计。
 *
 * <p>设计见 docs/superpowers/specs/2026-10-13-audit-log-design.md。
 *
 * <p><b>标在 Controller 层</b>：审计的单元是「一次用户请求」，不是「一次内部方法调用」——
 * Controller 端点与用户动作 1:1，而 Service 方法可能被多个入口复用、或被内部编排调用
 * （一次「创建租户」会经 {@code TenantService.create} → {@code TenantMemberService.add}，
 * 标在 Service 上会记两条）。详见 {@link AuditAspect} 的类注释。
 *
 * <p><b>不要标在流式端点上</b>（Pod / Node 日志流、exec）—— 那会把流式响应拖进审计路径。
 *
 * <p><b>覆盖范围靠人工维护</b>：已明确不做「断言写端点覆盖率」的护栏，也不维护写端点清单；
 * 新增写端点时手动加注解，漏标不会被发现（代价已显式接受）。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Auditable {

    /**
     * 动作码，取 {@link AuditAction} 里的常量（如 {@code AuditAction.WORKLOAD_DELETE}）。
     */
    String action();

    /**
     * 目标对象名的 SpEL，如 {@code "#name"} / {@code "#request.namespace"}。
     * 留空则按命名约定探测（方法参数名，或单个 DTO 参数的一级字段名 {@code name}）。
     */
    String target() default "";

    /**
     * 上下文（tenantId / clusterId / namespace）的 SpEL，逗号分隔，按此顺序填充。
     * 留空则按约定探测同名的参数 / 一级字段。
     */
    String scope() default "";
}
