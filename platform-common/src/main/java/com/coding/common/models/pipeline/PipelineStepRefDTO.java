package com.coding.common.models.pipeline;

import java.util.List;

/**
 * 一条运行里**一个节点**在集群上的落点。归档要按它去取日志（spec §4.4）。
 *
 * <p>⚠️ <b>「run → TaskRun → Pod → 容器」这套解析在 k8s-server 侧做</b>
 * （spec §7 的改定块）：platform-api 不依赖 k8s-core/fabric8，不该知道 Tekton 的结构。
 * 所以这里回的是**已经解析好的落点**，platform-api 拿着 {@code podName} + {@code containers}
 * 去走既有的 Pod 日志通道 —— 那条通道本来就在（spec §4.4：不用新写日志通道）。
 *
 * @param nodeName   画布上的节点 id（= Tekton 的 PipelineTask 名 = 那个 TaskRun 名）
 * @param status     RUNNING / SUCCEEDED / FAILED / CANCELLED / TIMEOUT
 * @param podName    跑这个节点的 Pod；**还没起来时为 null**（那就没有日志可抓）
 * @param containers Pod 里属于这个节点的容器名（每个 step 一个容器）
 * @param message    Tekton 对这个节点失败的解释（TaskRun condition 的 message）——
 *                   没失败 / Tekton 没给就是 null（2026-10-15 加：真跑失败后运行历史里
 *                   只有顶层那句 {@code Failed}，对排障毫无信息量，真因就在这一层）
 */
public record PipelineStepRefDTO(String nodeName, String status, String podName,
                                 List<String> containers, String message) {
}
