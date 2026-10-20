package com.coding.common.models.pipeline;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Map;

/**
 * 一次运行的重试计划 —— **前端据此决定哪些节点有「重试」按钮、对话框里列什么**。
 *
 * <p>⚠️ 断点规则只在服务端实现（{@code PipelineCanvasMask.plan}）；前端不要自己重算。
 *
 * @param retryable      这条运行整体能不能重试（RUNNING / SUCCEEDED / 没有工作区时为 false）
 * @param reason         整体不能重试的原因（可重试时为 null）
 * @param retryableNodes 可以作为重试起点的节点（普通断点 + 没跑成的收尾节点）
 * @param runNodeIds     点普通断点时**会执行**的节点集合（所有断点的子树之并）
 * @param reusedNodeIds  沿用（上次成功、本次不跑）的节点
 * @param blocked        节点 id → 不能点的原因
 */
@Schema(description = "一次运行的重试计划")
public record PipelineRetryPlanDTO(
        boolean retryable,
        String reason,
        List<String> retryableNodes,
        List<String> runNodeIds,
        List<String> reusedNodeIds,
        Map<String, String> blocked) {
}
