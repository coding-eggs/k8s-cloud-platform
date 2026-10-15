package com.coding.k8score.pipeline;

import io.fabric8.tekton.v1.Step;

import java.util.List;

/**
 * 一个节点渲染出来的东西。
 *
 * @param steps 这个节点的容器（本批每个节点一个）
 * @param extraWorkspaces 这个节点**额外要挂的 workspace 名**（除了隐式共享的那个）。
 *        典型是 {@code creds} —— 构建/克隆步骤要读挂进来的凭据文件。
 *        ⚠️ 凭据**只以文件形式挂进来**，绝不写进 {@code steps} 的 script：
 *        那会让凭据进 PipelineRun 的 spec，而 {@code /pipeline/preview} 会把渲染结果原样给用户看。
 */
public record RenderedStep(List<Step> steps, List<String> extraWorkspaces) {

    /** 只要 steps 的常见情形。 */
    public RenderedStep(List<Step> steps) {
        this(steps, List.of());
    }
}
