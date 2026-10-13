package com.coding.platformapi.audit;

import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.expression.Expression;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.lang.reflect.Method;

/**
 * 从被切的 Service 方法上抽出「目标对象名」与「上下文」。
 *
 * <p><b>显式优先、隐式兜底</b>：注解给了 SpEL 就用 SpEL；没给就按命名约定探测 ——
 * 方法参数名，或**单个 DTO 参数的一级字段名**（{@code name} / {@code tenantId} /
 * {@code clusterId} / {@code namespace}）。这正是本平台 DTO 的统一形状，所以简单场景零配置。
 *
 * <p><b>探测深度只到一级，不递归</b>：嵌套结构（如 {@code #req.spec.foo}）必须走显式 SpEL ——
 * 规则简单可预测比「猜得深」更重要（spec §3.2）。
 *
 * <p><b>求值失败不致命</b>：任何异常都降级为 null + 一条 warn，绝不让业务方法因注解写错而挂（spec §3.3）。
 * fail-open 同样适用于 SpEL。
 */
@Slf4j
@Component
public class AuditTargetResolver {

    private static final ParameterNameDiscoverer NAME_DISCOVERER = new DefaultParameterNameDiscoverer();

    private static final String FIELD_NAME = "name";

    private static final String FIELD_TENANT_ID = "tenantId";

    private static final String FIELD_CLUSTER_ID = "clusterId";

    private static final String FIELD_NAMESPACE = "namespace";

    private final SpelExpressionParser parser = new SpelExpressionParser();

    /** 审计上下文三元组；任一取不到即为 null。 */
    public record Scope(String tenantId, String clusterId, String namespace) {
    }

    /**
     * 目标对象名：SpEL 优先，否则探测名为 {@code name} 的参数 / DTO 一级字段。
     */
    public String resolveTarget(ProceedingJoinPoint pjp, String spel) {
        if (StringUtils.hasText(spel)) {
            return asString(evaluate(pjp, spel));
        }
        return probe(pjp, FIELD_NAME);
    }

    /**
     * 上下文三元组：SpEL 优先（按 tenantId, clusterId, namespace 顺序取逗号分隔的表达式），
     * 否则各自按名探测。
     */
    public Scope resolveScope(ProceedingJoinPoint pjp, String spel) {
        if (StringUtils.hasText(spel)) {
            String[] parts = spel.split(",");
            return new Scope(
                    parts.length > 0 ? asString(evaluate(pjp, parts[0].trim())) : null,
                    parts.length > 1 ? asString(evaluate(pjp, parts[1].trim())) : null,
                    parts.length > 2 ? asString(evaluate(pjp, parts[2].trim())) : null);
        }
        return new Scope(probe(pjp, FIELD_TENANT_ID), probe(pjp, FIELD_CLUSTER_ID), probe(pjp, FIELD_NAMESPACE));
    }

    // ------------------------------------------------------------------ 内部

    private Object evaluate(ProceedingJoinPoint pjp, String spel) {
        try {
            Expression expression = parser.parseExpression(spel);
            StandardEvaluationContext ctx = new StandardEvaluationContext();
            String[] paramNames = paramNames(pjp);
            Object[] args = pjp.getArgs();
            for (int i = 0; i < args.length; i++) {
                String pname = paramNames != null && i < paramNames.length ? paramNames[i] : "arg" + i;
                ctx.setVariable(pname, args[i]);
            }
            return expression.getValue(ctx);
        } catch (Exception e) {
            // 注解写错不该让生产接口挂掉
            log.warn("审计注解 SpEL 求值失败，字段降级为 null：spel={} err={}", spel, e.getMessage());
            return null;
        }
    }

    /**
     * 按名探测：先看与方法参数同名的字符串参数，再看单个 DTO 参数的一级字段。
     */
    private String probe(ProceedingJoinPoint pjp, String field) {
        try {
            String[] paramNames = paramNames(pjp);
            Object[] args = pjp.getArgs();
            for (int i = 0; i < args.length; i++) {
                Object arg = args[i];
                if (arg == null) {
                    continue;
                }
                if (arg instanceof String s) {
                    String pname = paramNames != null && i < paramNames.length ? paramNames[i] : null;
                    if (field.equals(pname)) {
                        return s;
                    }
                } else if (isPlainPojo(arg)) {
                    String v = readGetter(arg, field);
                    if (v != null) {
                        return v;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("审计上下文探测失败，字段降级为 null：field={} err={}", field, e.getMessage());
        }
        return null;
    }

    private String[] paramNames(ProceedingJoinPoint pjp) {
        MethodSignature sig = (MethodSignature) pjp.getSignature();
        Method method = sig.getMethod();
        return NAME_DISCOVERER.getParameterNames(method);
    }

    /** 只对平台自己的类做反射读字段，跳过 JDK / Spring 类型（避免在无关对象上乱找 getter）。 */
    private boolean isPlainPojo(Object o) {
        return o.getClass().getName().startsWith("com.coding.");
    }

    private String readGetter(Object target, String field) {
        String getter = "get" + Character.toUpperCase(field.charAt(0)) + field.substring(1);
        try {
            Object v = target.getClass().getMethod(getter).invoke(target);
            return v == null ? null : String.valueOf(v);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    private String asString(Object v) {
        return v == null ? null : String.valueOf(v);
    }
}
