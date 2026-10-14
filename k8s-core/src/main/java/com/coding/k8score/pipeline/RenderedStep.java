package com.coding.k8score.pipeline;

import io.fabric8.tekton.v1.Step;

import java.util.List;

/** 一个节点渲染出来的东西。目前只有 steps —— 将来要 results/volumes 时在这里加。 */
public record RenderedStep(List<Step> steps) {
}
