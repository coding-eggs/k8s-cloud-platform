package com.coding.common.models.pipeline;

import java.util.List;

/**
 * 一次终结了的运行的**完整快照**：状态 + 每个节点的状态 + 每个节点的日志。
 *
 * <h2>⚠️ 为什么快照由 k8s-server 自己抓、自己落库</h2>
 * watch 是 k8s-server 开的，所以收到终结事件的那一刻它**手上就有**集群对象、TaskRun 与 Pod ——
 * 快照本来就在它手里。于是归档链是：
 *
 * <pre>
 * k8s-server: 收到终结事件 → 自己抓快照 → **自己落库**（PipelineArchiveService，它就有 platform-data）
 *              → 落库成功才删对象（级联带走 TaskRun 与 Pod）→ 删凭据 Secret → 关 watch
 * </pre>
 *
 * <p>⚠️ 快照**不出进程**：落库那一步就在 k8s-server 里。这条改定让「免认证入站端点 + 共享密钥 +
 * ack / 待补 / 重投」整条消失，入站攻击面归零 —— 细节与代价见 {@code PipelineArchiveService} 的 javadoc。
 * 若让 platform-api 反过来去 k8s-server 取快照，就得为「没有用户 token 的后台调用」再造一套身份，
 * 而那是个安全边界问题，能绕开就绕开。
 *
 * @param status 终结状态（SUCCEEDED / FAILED / CANCELLED / TIMEOUT）—— 由 k8s-server 判定
 * @param runYaml 集群里那个对象的 YAML 原文。**必须在归档这一刻抓** ——
 *                同一时刻对象就被删了，之后「当年跑的到底是什么」再也拿不到
 *                （拿画布重渲染会得到一份当年没跑过的东西）。运行详情的 YAML 面板读它
 */
public record PipelineRunSnapshotDTO(String clusterId,
                                     String namespace,
                                     String runName,
                                     String status,
                                     String reason,
                                     String startTime,
                                     String completionTime,
                                     List<StepLog> steps,
                                     String runYaml) {

    /**
     * 一个节点的状态与日志。
     *
     * @param log 已被 k8s-server 按上限截断
     */
    public record StepLog(String nodeName, String status, String log,
                          boolean truncated, int sizeBytes) {
    }
}
