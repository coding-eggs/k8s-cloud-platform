package com.coding.k8sserver.middleware;

/**
 * 实例状态判定（纯函数，便于单测）。
 *
 * <p>真集群验证抓到的两个谎报：
 * <ul>
 *   <li>Kafka 3 个 broker 只起来 2 个时，"所有现存 Pod 都就绪"会判成 READY；</li>
 *   <li>MySQL bootstrap 期间 Pod 还没被 selector 命中时，被当成 FAILED（且消息为空）。</li>
 * </ul>
 * 所以判定要同时看：Pod 数 vs 期望数、CR 的 Initializing 信号、Operator 报的硬失败。
 */
final class MiddlewareStatusEvaluator {

    record Result(String status, String message) {
    }

    private MiddlewareStatusEvaluator() {
    }

    /**
     * @param pods            selector 命中的 Pod 数
     * @param ready           其中 Ready 的数量
     * @param desired         期望 Pod 数；null = 无法从 CR 推出（只能按现存 Pod 判定）
     * @param initializing    CR 上是否有 Initializing=True 信号
     * @param failureMessage  Operator 报的失败/告警消息；null/空白表示没有
     */
    static Result evaluate(int pods, long ready, Integer desired, boolean initializing, String failureMessage) {
        String failure = failureMessage == null || failureMessage.isBlank() ? null : failureMessage;

        if (pods == 0) {
            if (initializing) {
                return new Result("CREATING", "初始化中");
            }
            if (failure != null) {
                return new Result("FAILED", failure);
            }
            return new Result("CREATING", "等待 Pod 创建");
        }

        int target = desired == null ? pods : Math.max(pods, desired);
        if (pods < target) {
            return new Result("DEGRADED", ready + "/" + target + " 就绪（期望 " + target + " 个）");
        }
        if (ready < pods) {
            return new Result("DEGRADED", ready + "/" + pods + " 就绪");
        }
        if (failure != null) {
            return new Result("DEGRADED", failure);
        }
        return new Result("READY", "运行中");
    }
}
