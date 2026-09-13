package com.coding.k8sserver.configs;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * MVC 异步支持配置。
 *
 * <p>本服务唯一走异步（{@code StreamingResponseBody}）的端点是 Pod 日志流式透传，
 * 其余资源 CRUD 均为同步返回。Spring MVC 默认异步超时仅 30s：follow=true 的日志长连接
 * （多容器 Pod 里长时间运行的容器尤甚）一旦超过 30s 未完成，就会被
 * {@code AsyncRequestTimeoutException} 掐断，表现为「查不出日志」。
 *
 * <p>这里把异步超时放宽到 30 分钟作为安全上限：正常结束由客户端断开触发（controller 内
 * 已捕获 {@code IOException} 并关闭 watch），此值只用于回收异常挂起的连接。若后续需要支持
 * 更长的持续跟随，可进一步调大或设为 0（Servlet 规范：≤0 表示不超时）。
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    /**流式日志异步超时（毫秒）= 30 分钟 */
    private static final long STREAMING_ASYNC_TIMEOUT_MS = 30 * 60 * 1000L;

    @Override
    public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
        configurer.setDefaultTimeout(STREAMING_ASYNC_TIMEOUT_MS);
    }

}
