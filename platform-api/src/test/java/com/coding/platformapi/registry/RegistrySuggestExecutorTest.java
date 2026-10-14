package com.coding.platformapi.registry;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 补全的**并发出口**。一次输入可能要让 N 条连接各自查一次 ——
 * 串行会把整条时间线拖到「最慢那条 × N」，而补全是每按键一次的高频动作。
 *
 * <p>它守的不变量有两条，且都是**逐连接**的（spec §2.5）：
 * 一条超时/抛异常只影响它自己；任一情况都**不把异常抛给调用方**。
 */
class RegistrySuggestExecutorTest {

    @Test
    void aHungTaskDoesNotDelayTheOthers() {
        // 串行的话一条黑洞连接会把整条时间线拖到「最慢 × N」——
        // RegistryHttpClient 单条最坏是连接 5s + 读 10s = 15s
        RegistrySuggestExecutor ex = new RegistrySuggestExecutor();
        long start = System.currentTimeMillis();

        List<List<String>> out = ex.allOf(List.of(
                () -> List.of("fast"),
                () -> {
                    Thread.sleep(30_000);
                    return List.of("never");
                }));

        assertThat(out.get(0)).containsExactly("fast");
        assertThat(out.get(1)).as("被取消 → 按空算").isEmpty();
        assertThat(System.currentTimeMillis() - start).isLessThan(10_000);
        ex.shutdown();
    }

    @Test
    void aThrowingTaskBecomesAnEmptyResultNotAPropagatedException() {
        RegistrySuggestExecutor ex = new RegistrySuggestExecutor();

        List<List<String>> out = ex.allOf(List.of(
                () -> {
                    throw new IllegalStateException("boom");
                },
                () -> List.of("ok")));

        assertThat(out.get(0)).isEmpty();
        assertThat(out.get(1)).containsExactly("ok");
        ex.shutdown();
    }

    @Test
    void resultsKeepTheSameOrderAsTheTasks() {
        // 调用方按索引把结果配回连接名 —— 顺序乱了就会把候选挂到错的连接上
        RegistrySuggestExecutor ex = new RegistrySuggestExecutor();

        List<List<String>> out = ex.allOf(List.of(
                () -> List.of("a"),
                () -> List.of("b"),
                () -> List.of("c")));

        assertThat(out).containsExactly(List.of("a"), List.of("b"), List.of("c"));
        ex.shutdown();
    }

    @Test
    void noTasksYieldsNoResults() {
        RegistrySuggestExecutor ex = new RegistrySuggestExecutor();
        assertThat(ex.allOf(List.of())).isEmpty();
        assertThat(ex.allOf(null)).isEmpty();
        ex.shutdown();
    }
}
