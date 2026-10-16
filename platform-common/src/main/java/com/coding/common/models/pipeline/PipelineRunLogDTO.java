package com.coding.common.models.pipeline;

/**
 * 一次运行的日志，**给"跟着看"用的形状**（运行详情页）。
 *
 * <p>为什么要带 {@code cursor} 而不是只回一段文本：构建日志是**流式**的 —— 页面点开一个节点后
 * 应该自己往下滚，而不是让人反复点。集群侧的 Pod 日志通道本来就支持 {@code sinceTime} 增量
 * （见 k8s-server {@code PodController.logs}），游标是「上一批最后一行的时间戳」，
 * 但它属于**实现细节**，不该漏到前端去 —— 前端的活儿是"把新来的字接上去"。
 *
 * @param text   **这一批**新增的、已经可以直接显示的文本
 *               （k8s 注入的 {@code <RFC3339Nano>\t} 前缀已在服务端剥掉；边界上重复的那一行也滤掉了）
 * @param cursor 下一批要带回来的游标；{@code null} 表示**没法按游标增量**
 *               （已归档 / 集群那份日志没带时间戳）—— 这时调用方应当整段替换并停止轮询
 * @param live   还在跑、且能按游标增量跟。{@code false} 时 {@code text} 就是全量
 */
public record PipelineRunLogDTO(String text, String cursor, boolean live) {

    /** 全量、不再跟（已归档；或集群侧给不了时间戳）—— 也是"旧行为"的形状。 */
    public static PipelineRunLogDTO whole(String text) {
        return new PipelineRunLogDTO(text, null, false);
    }
}
