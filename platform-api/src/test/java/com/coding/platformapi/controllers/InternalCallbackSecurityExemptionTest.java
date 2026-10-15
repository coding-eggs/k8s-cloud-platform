package com.coding.platformapi.controllers;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 内部回调是**唯一一条不靠用户 JWT 的入站路径**，所以它要同时满足两件事，
 * 而这两件事分在两个地方、谁都不会提醒对方：
 *
 * <ol>
 *   <li>进 {@code ExemptPaths}（免**权限表**）—— 漏了 {@code PermissionCrossCheckRunner} 会拒启，**响亮**；</li>
 *   <li>进 {@code spring.security.ignore-urls}（免**认证**）—— 漏了的表现是 **401**，
 *       而 401 只在 k8s-server 一侧的日志里，平台这边一片安静。</li>
 * </ol>
 *
 * <p>这条测试钉的是第 2 条。它读的是**真的 application.yaml**，不是常量：
 * 这道闸门的失效方式就是「加了内部端点、忘了往 yaml 里加一行」，
 * 而那种漂移只有拿配置当被测对象才看得见。
 */
class InternalCallbackSecurityExemptionTest {

    /**
     * ⚠️ yaml 里的列表被扁平化成 {@code spring.security.ignore-urls[0]}、{@code [1]}…
     * 而不是一个 List 值 —— 直接取不带下标的键会拿到 null（这条测试第一版就是这么挂的）。
     */
    private static List<String> ignoreUrls() throws Exception {
        List<String> out = new java.util.ArrayList<>();
        for (PropertySource<?> ps : new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yaml"))) {
            for (int i = 0; ; i++) {
                Object v = ps.getProperty("spring.security.ignore-urls[" + i + "]");
                if (v == null) {
                    break;
                }
                out.add(String.valueOf(v));
            }
        }
        return out;
    }

    @Test
    void theInternalCallbackPathIsPermittedByTheSecurityChain() throws Exception {
        assertThat(ignoreUrls())
                .as("少了这一条，k8s-server 那条不带 Authorization 的回调会被 entry point 直接 401，"
                        + "控制器的密钥校验（唯一防线）也就从未真正生效过")
                .contains("/internal/pipeline-run/finished");
    }

    @Test
    void thePermittedPathIsActuallyAControllerMapping() {
        // 放行一个**不存在**的路径不会报任何错，只会让下一个人以为已经放行了真的那条
        boolean mapped = Arrays.stream(PipelineInternalController.class.getDeclaredMethods())
                .flatMap(m -> Arrays.stream(m.getAnnotationsByType(
                        org.springframework.web.bind.annotation.PostMapping.class)))
                .flatMap(a -> Arrays.stream(a.value()))
                .anyMatch("/pipeline-run/finished"::equals);

        assertThat(mapped)
                .as("常量漂移：放行清单里的路径与控制器上的 @PostMapping 对不上")
                .isTrue();
    }
}
