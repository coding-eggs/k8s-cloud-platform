package com.coding.k8score.pipeline;

import com.coding.common.models.pipeline.PipelineConnectionRef;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 渲染一个节点时的上下文。
 *
 * <p>字段值**只从画布上的 config 读** —— 预设（{@link BuildPreset}）是前端预填进 config 的默认值，
 * 到渲染这一步时 config 里已经是用户最终确认的值。所以这里没有 schema 回落：
 * 「字段没填 → 用什么默认值」是表单的事，不是渲染器的事。
 *
 * @param connections 本次运行可用的连接（**已授权、已解密**），按 id 索引。
 *                    ⚠️ 预览与保存校验时是空的 —— 那种路径不该拿到凭据。
 * @param renderedAt  **这次渲染发生的时刻**（渲染器在整张画布上只取一次）。
 *                    给「时间戳当 tag」这类必须知道"什么时候"的节点用。
 *                    ⚠️ 不走 {@code LocalDateTime.now()} 当场取，是为了两件事：
 *                    一张画布上的多个节点拿到**同一个**时刻（同一次运行的产物配套），
 *                    以及单测能把时刻钉死（否则断言只能靠运气）。
 *                    时区就是本进程的默认时区 —— 与 platform-api 生成 run 名用的是同一个口径。
 */
public record NodeContext(String nodeId, String nodeName, String workspaceName,
                          PipelineCanvas.CanvasNode node,
                          Map<String, PipelineConnectionRef> connections,
                          LocalDateTime renderedAt) {

    /** 不带连接的常用形式（预览 / 单测）。 */
    public NodeContext(String nodeId, String nodeName, String workspaceName,
                       PipelineCanvas.CanvasNode node) {
        this(nodeId, nodeName, workspaceName, node, Map.of(), LocalDateTime.now());
    }

    /** 带连接、但不指定渲染时刻（没有节点需要它时用这个）。 */
    public NodeContext(String nodeId, String nodeName, String workspaceName,
                       PipelineCanvas.CanvasNode node,
                       Map<String, PipelineConnectionRef> connections) {
        this(nodeId, nodeName, workspaceName, node, connections, LocalDateTime.now());
    }

    /** 取字段值；没填或只有空白则返回空串。 */
    public String value(String key) {
        String v = node.config() == null ? null : node.config().get(key);
        if (v != null && !v.isBlank()) {
            return v.trim();
        }
        return "";
    }

    /** 按 id 取一条连接；没有/没授权时返回 null（调用方决定是拒还是降级）。 */
    public PipelineConnectionRef connection(String id) {
        return id == null || id.isBlank() ? null : connections.get(id);
    }
}
