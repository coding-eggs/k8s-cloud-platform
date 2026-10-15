package com.coding.k8score.pipeline;

import io.fabric8.tekton.v1.Step;

import java.util.List;

/**
 * 一个节点渲染出来的东西。
 *
 * @param steps 这个节点的容器（本批每个节点一个）
 * @param workspaces 这个节点**额外要挂的 workspace**（除了渲染器给每个 task 都加的那个隐式共享的）。
 *        典型是 {@code creds}（凭据文件）与 {@code cache}（依赖缓存）。
 *        ⚠️ 凭据**只以文件形式挂进来**，绝不写进 {@code steps} 的 script：
 *        那会让凭据进 PipelineRun 的 spec，而 {@code /pipeline/preview} 会把渲染结果原样给用户看。
 */
public record RenderedStep(List<Step> steps, List<WorkspaceRef> workspaces) {

    /**
     * 一个额外要挂的 workspace。
     *
     * @param mountPath 挂到容器里的哪个路径 —— **必须显式给**：Tekton 对没写 mountPath 的
     *                  workspace 默认挂到 {@code /workspace/<名字>}，而 {@code /workspace} 上
     *                  站着的是隐式共享 workspace 与 Tekton 自己的空目录（2026-10-15 踩过）。
     */
    public record WorkspaceRef(String name, String mountPath) { }

    /** 只要 steps 的常见情形。 */
    public RenderedStep(List<Step> steps) {
        this(steps, List.of());
    }
}
