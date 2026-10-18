package com.coding.platformapi.trace;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 回归：{@link TempoTraceClient} 有两个构造器（公开的 {@code @Value} 版 + 包级私有、给测试注入
 * RestClient 的版本）。Spring 只在「有且仅有一个构造器」时才隐式选它，多构造器必须显式
 * {@code @Autowired}，否则退回无参构造并在启动期报 {@code No default constructor found}。
 *
 * <p>这里用最小上下文复现启动期的实例化路径（不带 @Value 注入的值，只验证 bean 能建出来）。
 */
class TempoTraceClientSpringWiringTest {

    @Test
    void springInstantiatesTheBeanDespiteTheTestOnlyConstructor() {
        try (var ctx = new AnnotationConfigApplicationContext()) {
            ctx.getEnvironment().getPropertySources().addFirst(
                    new MapPropertySource("test", Map.of("platform.tempo.query-url", "http://tempo:3200")));
            ctx.register(TempoTraceClient.class);
            ctx.refresh();

            assertThat(ctx.getBean(TempoTraceClient.class)).isNotNull();
        }
    }
}
