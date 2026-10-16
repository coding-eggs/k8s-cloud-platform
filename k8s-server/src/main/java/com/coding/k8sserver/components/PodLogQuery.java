package com.coding.k8sserver.components;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import io.fabric8.kubernetes.api.model.Status;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.dsl.BytesLimitTerminateTimeTailPrettyLoggable;
import io.fabric8.kubernetes.client.dsl.TimeTailPrettyLoggable;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.OutputStream;

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

    /**
     * 把这份日志**流式**写出去。
     *
     * <p>四个分支的优先级：{@code sinceTime}（增量）&gt; {@code tailLines}（首屏回看）&gt;
     * {@code sinceSeconds}（旧的时间窗，保留兼容）&gt; 全量。都不给就是全量。
     *
     * <p>⚠️ 这里为什么必须 catch（2026-10-16 整体评审发现，真栽过）：
     * 从 lambda 里抛出的**非** {@link CloudPlatformException} 会被
     * {@code GlobalExceptionHandler.handleOtherError} 接住 —— 它返回
     * {@code new ResponseData<>(5000, null)}，于是 msg 走 {@code getMsgByCode(5000)}
     * 变成通用文案（「服务端异常」），<b>而且 HTTP 200</b>。
     * 后果很具体：用户点「上一次」想看"这个容器为什么死的"，看到的是"服务端异常"；
     * 聚合视图里那行「暂时取不到日志（原因）」的原因也变成同一句通用文案。
     * 翻成 {@link CloudPlatformException} 之后走 {@code handleMovieException}，
     * <b>msg 原样回给前端</b> —— 于是 `previous terminated container "app" not found` 这种原话到得了用户眼前。
     */
    public static void stream(TimeTailPrettyLoggable loggable, String sinceTime, Integer tailLines,
                              Integer sinceSeconds, OutputStream out) throws IOException {
        try {
            if (StringUtils.hasText(sinceTime)) {
                loggable.sinceTime(sinceTime).withPrettyOutput().getLogInputStream().transferTo(out);
            } else if (tailLines != null) {
                loggable.tailingLines(tailLines).withPrettyOutput().getLogInputStream().transferTo(out);
            } else if (sinceSeconds != null) {
                loggable.sinceSeconds(sinceSeconds).withPrettyOutput().getLogInputStream().transferTo(out);
            } else {
                loggable.withPrettyOutput().getLogInputStream().transferTo(out);
            }
        } catch (KubernetesClientException e) {
            throw new CloudPlatformException(EnumResponseType.ERROR, k8sMessage(e));
        }
    }

    /**
     * k8s 的**原话**优先（如 {@code previous terminated container "app" not found}）；
     * 拿不到 Status 才退回异常自己的 message —— 别返回 null，那会让前端显示空字符串。
     */
    private static String k8sMessage(KubernetesClientException e) {
        Status status = e.getStatus();
        String m = status == null ? null : status.getMessage();
        return m != null && !m.isBlank() ? m : e.getMessage();
    }
}
