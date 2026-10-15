package com.coding.k8score.pipeline;

import com.coding.common.models.pipeline.PipelineConnectionRef;

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
 */
public record NodeContext(String nodeId, String nodeName, String workspaceName,
                          PipelineCanvas.CanvasNode node,
                          Map<String, PipelineConnectionRef> connections) {

    /** 不带连接的常用形式（预览 / 单测）。 */
    public NodeContext(String nodeId, String nodeName, String workspaceName,
                       PipelineCanvas.CanvasNode node) {
        this(nodeId, nodeName, workspaceName, node, Map.of());
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
