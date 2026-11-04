package com.coding.common.audit;

import com.coding.common.security.AuthContext;
import com.coding.data.mapper.system.PlatformAuditLogMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import tools.jackson.databind.json.JsonMapper;

/**
 * servlet 业务服务（platform-api、platform-auth）的审计与身份工具：写操作切面、独立事务写入、
 * fail-open 门面、SpEL 目标解析、token 身份视图。
 *
 * <p><b>故意不是 {@code @Configuration}</b>：api / auth / k8s-server 都扫描 {@code com.coding.common}，
 * 打了注解 k8s-server 也会被装上审计切面与写表 bean —— 它零业务逻辑，既没有审计语义也不该写这张表。
 * 只靠 {@code @ConditionalOnWebApplication(SERVLET)} 挡不住它（k8s-server 也是 servlet）。
 * 所以由需要的服务显式 {@code @Import(ServletAuditConfiguration.class)}（@Bean 以 lite 模式注册）。
 */
public class ServletAuditConfiguration {

    @Bean
    public AuthContext authContext(JsonMapper jsonMapper, @Value("${jwt.data-key:data}") String dataKey) {
        return new AuthContext(jsonMapper, dataKey);
    }

    @Bean
    public AuditWriter auditWriter(PlatformAuditLogMapper mapper) {
        return new AuditWriter(mapper);
    }

    @Bean
    public AuditRecorder auditRecorder(AuditWriter writer, AuthContext authContext) {
        return new AuditRecorder(writer, authContext);
    }

    @Bean
    public AuditTargetResolver auditTargetResolver() {
        return new AuditTargetResolver();
    }

    @Bean
    public AuditAspect auditAspect(AuditRecorder recorder, AuthContext authContext, AuditTargetResolver targetResolver) {
        return new AuditAspect(recorder, authContext, targetResolver);
    }
}
