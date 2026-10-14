package com.coding.k8score.pipeline;

/**
 * 渲染一个节点时的上下文。
 *
 * <p>字段值**只从画布上的 config 读** —— 预设（{@link BuildPreset}）是前端预填进 config 的默认值，
 * 到渲染这一步时 config 里已经是用户最终确认的值。所以这里没有 schema 回落：
 * 「字段没填 → 用什么默认值」是表单的事，不是渲染器的事。
 */
public record NodeContext(String nodeId, String nodeName, String workspaceName,
                          PipelineCanvas.CanvasNode node) {

    /** 取字段值；没填或只有空白则返回空串。 */
    public String value(String key) {
        String v = node.config() == null ? null : node.config().get(key);
        if (v != null && !v.isBlank()) {
            return v.trim();
        }
        return "";
    }
}
