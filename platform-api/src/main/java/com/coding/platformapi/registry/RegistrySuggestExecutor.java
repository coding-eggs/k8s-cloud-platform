package com.coding.platformapi.registry;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 补全的并发出口。一次输入可能要让 N 条连接各自查一次 ——
 * 串行会把整条时间线拖到「最慢那条 × N」，而补全是每按键一次的高频动作。
 *
 * <p>设计见 docs/superpowers/specs/2026-10-14-registry-dockerhub-design.md §2.5。
 */
@Slf4j
@Component
public class RegistrySuggestExecutor {

    /**
     * 总等待上限。
     *
     * <p>⚠️ <b>刻意小于单条连接的最坏耗时</b>（{@code RegistryHttpClient} 是连接 5s + 读 10s，
     * 最坏 15s）。一条黑洞连接不该让用户的补全等 15 秒 —— 它被取消、按空算，
     * 而其余连接的结果照常返回。S3.1 时只有一条连接参与，不存在这个问题。
     */
    static final Duration BUDGET = Duration.ofSeconds(3);

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    /**
     * 并发跑完所有任务。**顺序与入参一一对应** —— 调用方按索引把结果配回连接名，
     * 顺序错了就会把候选挂到错的连接上。
     *
     * <p>任何一条抛异常或超时都**只影响它自己**：该位置返回空列表，别的照常。
     * 这仍是 S3.1 spec §5.3 那条「补全静默降级」的姿态，只是**逐连接**适用。
     */
    public List<List<String>> allOf(List<Callable<List<String>>> tasks) {
        if (tasks == null || tasks.isEmpty()) {
            return List.of();
        }
        List<Future<List<String>>> futures;
        try {
            futures = executor.invokeAll(tasks, BUDGET.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("镜像候选并发查询被中断（静默降级为空）", e);
            return tasks.stream().map(t -> List.<String>of()).toList();
        }
        List<List<String>> out = new ArrayList<>(futures.size());
        for (Future<List<String>> f : futures) {
            try {
                List<String> v = f.get();
                out.add(v == null ? List.of() : v);
            } catch (Exception e) {
                // CancellationException（超时被取消）与 ExecutionException（对方抛）都落在这里
                log.warn("镜像候选查询失败或超时（静默降级为空）", e);
                out.add(List.of());
            }
        }
        return out;
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
