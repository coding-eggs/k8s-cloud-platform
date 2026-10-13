package com.coding.platformapi.audit;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link AuditTargetResolver}：SpEL 求值、按参数名/一级字段探测、求值失败降级。
 *
 * <p>探测依赖编译期参数名（根 pom 已开 {@code <parameters>true</parameters>}，见 maven-compiler-plugin 配置），
 * 所以这里用真实反射方法拿参数名，构造 mock 的 join point，不起 Spring 上下文
 * （与本仓其它 Service 测试的写法一致：手工构造被测对象 + Mockito 替身）。
 */
class AuditTargetResolverTest {

    private final AuditTargetResolver resolver = new AuditTargetResolver();

    // ---- 被切的方法（只为提供参数名与签名，不会被调用） ----

    @SuppressWarnings("unused")
    private void withConventionParams(String name, String tenantId, String clusterId, String namespace) {
    }

    @SuppressWarnings("unused")
    private void withDto(ScopeDto req) {
    }

    /** 测试用 DTO：一级字段探测的载体 */
    public static class ScopeDto {
        private String clusterId;
        private String namespace;
        private String name;

        public String getClusterId() {
            return clusterId;
        }

        public String getNamespace() {
            return namespace;
        }

        public String getName() {
            return name;
        }
    }

    private ProceedingJoinPoint pjp(String methodName, Class<?>[] types, Object[] args) throws Exception {
        Method m = AuditTargetResolverTest.class.getDeclaredMethod(methodName, types);
        MethodSignature sig = mock(MethodSignature.class);
        when(sig.getMethod()).thenReturn(m);
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        when(pjp.getSignature()).thenReturn(sig);
        when(pjp.getArgs()).thenReturn(args);
        return pjp;
    }

    @Test
    void spelTargetWins() throws Exception {
        ProceedingJoinPoint pjp = pjp("withConventionParams",
                new Class<?>[]{String.class, String.class, String.class, String.class},
                new Object[]{"web-a", "t1", "c1", "ns1"});

        assertThat(resolver.resolveTarget(pjp, "#name")).isEqualTo("web-a");
    }

    @Test
    void probeFindsParamNamedName() throws Exception {
        ProceedingJoinPoint pjp = pjp("withConventionParams",
                new Class<?>[]{String.class, String.class, String.class, String.class},
                new Object[]{"web-a", "t1", "c1", "ns1"});

        assertThat(resolver.resolveTarget(pjp, "")).isEqualTo("web-a");
    }

    @Test
    void probeFindsScopeFromParamNames() throws Exception {
        ProceedingJoinPoint pjp = pjp("withConventionParams",
                new Class<?>[]{String.class, String.class, String.class, String.class},
                new Object[]{"web-a", "t1", "c1", "ns1"});

        AuditTargetResolver.Scope scope = resolver.resolveScope(pjp, "");

        assertThat(scope.clusterId()).isEqualTo("c1");
        assertThat(scope.namespace()).isEqualTo("ns1");
        assertThat(scope.tenantId()).isEqualTo("t1");
    }

    @Test
    void probeReadsOneLevelOfDtoFields() throws Exception {
        ScopeDto dto = new ScopeDto();
        dto.clusterId = "c9";
        dto.namespace = "ns9";
        dto.name = "db-0";
        ProceedingJoinPoint pjp = pjp("withDto", new Class<?>[]{ScopeDto.class}, new Object[]{dto});

        assertThat(resolver.resolveTarget(pjp, "")).isEqualTo("db-0");
        assertThat(resolver.resolveScope(pjp, "").clusterId()).isEqualTo("c9");
        assertThat(resolver.resolveScope(pjp, "").namespace()).isEqualTo("ns9");
    }

    @Test
    void badSpelDegradesToNullInsteadOfThrowing() throws Exception {
        ProceedingJoinPoint pjp = pjp("withConventionParams",
                new Class<?>[]{String.class, String.class, String.class, String.class},
                new Object[]{"web-a", "t1", "c1", "ns1"});

        // 引用了不存在的变量 —— 必须降级为 null，不能让业务方法因此挂掉（fail-open 同样适用于 SpEL）
        assertThat(resolver.resolveTarget(pjp, "#nope.deeper")).isNull();
    }

    @Test
    void missingEverythingReturnsNullsNotException() throws Exception {
        ProceedingJoinPoint pjp = pjp("withConventionParams",
                new Class<?>[]{String.class, String.class, String.class, String.class},
                new Object[]{null, null, null, null});

        assertThat(resolver.resolveTarget(pjp, "")).isNull();
        assertThat(resolver.resolveScope(pjp, "").clusterId()).isNull();
        assertThat(resolver.resolveScope(pjp, "").tenantId()).isNull();
    }

    @Test
    void explicitScopeSpelIsHonoured() throws Exception {
        ProceedingJoinPoint pjp = pjp("withConventionParams",
                new Class<?>[]{String.class, String.class, String.class, String.class},
                new Object[]{"web-a", "t1", "c1", "ns1"});

        // scope 的三个表达式按 tenantId, clusterId, namespace 顺序填充
        AuditTargetResolver.Scope scope = resolver.resolveScope(pjp, "#tenantId, #clusterId, #namespace");

        assertThat(scope.tenantId()).isEqualTo("t1");
        assertThat(scope.clusterId()).isEqualTo("c1");
        assertThat(scope.namespace()).isEqualTo("ns1");
    }
}
