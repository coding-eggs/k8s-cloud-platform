package com.coding.platformapi.configs;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * 告警投递的异步池。
 *
 * <p>为什么必须异步：Alertmanager 只看 HTTP 状态码，webhook 必须**很快**回 200；
 * 连 SMTP、失败退避 1s/5s/30s 都不能占着请求线程。
 *
 * <p>固定 4 线程 + 有界队列（200）：告警是突发流量，但发邮件是慢操作 ——
 * 无界队列在 SMTP 挂掉时会囤积任务直到 OOM，宁可丢给拒绝策略（日志里有记录）。
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean("alertDispatchExecutor")
    public Executor alertDispatchExecutor() {
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(4);
        ex.setMaxPoolSize(4);
        ex.setQueueCapacity(200);
        ex.setThreadNamePrefix("alert-dispatch-");
        ex.initialize();
        return ex;
    }
}
