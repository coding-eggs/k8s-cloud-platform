package com.coding.k8sserver.components;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.BytesLimitTerminateTimeTailPrettyLoggable;
import io.fabric8.kubernetes.client.dsl.TimeTailPrettyLoggable;

/**
 * 两处 Pod 日志端点（命名空间域 / 集群域）共用的一小块：把「取哪一份日志」拼成 fabric8 的查询链。
 *
 * <p>⚠️ 这里藏着两件只有这一处知道的事，所以宁可单独成类、也不在两边各抄一遍：
 *
 * <ol>
 *   <li><b>fabric8 里 {@code previous} 叫 {@code terminated()}</b>。实测反编译
 *       （kubernetes-client 7.6.1）：{@code terminated()} →
 *       {@code PodOperationContext.withTerminatedStatus(true)} → 拼进 URL 的是
 *       <b>{@code &previous=true}</b>。Java 侧没有 {@code previous()} 这个方法名可猜。</li>
 *   <li><b>{@code terminated()} 会让链"降级"</b>：它返回 {@link TimeTailPrettyLoggable}，
 *       而 {@code limitBytes} 与 {@code terminated} 都挂在
 *       {@link BytesLimitTerminateTimeTailPrettyLoggable} 上 —— 调了任一个就再也够不到另一个。
 *       两处端点本来都不用 {@code limitBytes}（它们靠 {@code transferTo} 直出流），所以无影响；
 *       <b>但别顺手加</b>，加了直接编译不过。</li>
 * </ol>
 */
public final class PodLogQuery {

    private PodLogQuery() {
    }

    /**
     * 开链：恒带 timestamps —— 每行加 RFC3339Nano 前缀，前端据此做增量游标、展示时剥掉还原原始日志。
     */
    public static BytesLimitTerminateTimeTailPrettyLoggable open(
            KubernetesClient client, String namespace, String pod, String container) {
        return client.pods()
                .inNamespace(namespace)
                .withName(pod)
                .inContainer(container)
                .usingTimestamps();
    }

    /**
     * 要不要「上一次终止的容器实例」。false 时**原样返回**，一个方法都不多调。
     */
    public static TimeTailPrettyLoggable withPrevious(BytesLimitTerminateTimeTailPrettyLoggable loggable,
                                                      boolean previous) {
        return previous ? loggable.terminated() : loggable;
    }
}
