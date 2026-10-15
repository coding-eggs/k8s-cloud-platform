package com.coding.common.models.pipeline;

/**
 * 这个集群的 Tekton **调度配置**（{@code feature-flags} 的 {@code coschedule}）。
 *
 * <p>窄投影：只回这一个事实 —— 它决定「构建缓存能不能开」（一个 TaskRun 能否挂两块 PVC workspace）。
 * 读取必须用 admin 凭据（配置在 {@code tekton-pipelines} 命名空间，租户 SA 读不到），
 * 所以它按 {@code UPSTREAM} 边界走，收窄在上游 platform-api。
 *
 * @param coschedule                 实际值（键缺失时是 Tekton 的默认 {@code workspaces}）
 * @param multiPvcWorkspacesAllowed  这个值允不允许一个 TaskRun 挂两块 PVC 类 workspace
 */
public record TektonSchedulingDTO(String coschedule, boolean multiPvcWorkspacesAllowed) {
}
