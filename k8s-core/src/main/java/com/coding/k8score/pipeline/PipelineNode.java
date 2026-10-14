package com.coding.k8score.pipeline;

/**
 * 一个**内置**流水线节点。设计见 docs/superpowers/specs/2026-10-15-ci-tekton-design.md §2。
 *
 * <p><b>加一个节点 = 加一个类</b>，实现本接口并在构造器上标 {@code @Component}。
 * 表单由 {@link #formSchema()} 驱动（加字段不用写 Vue）；「字段 → taskSpec」用 Java 写
 * （不发明模板 DSL —— 那是选「一个节点一个类」而不是「纯声明式」的理由）。
 */
public interface PipelineNode {

    /** 节点的 type 标识，如 {@code "git"} / {@code "shell"} / {@code "image"}。全局唯一。 */
    String type();

    /** 表单 schema。前端据此渲染属性面板，渲染器据此做必填校验。 */
    NodeSchema formSchema();

    /** 把节点上填的值合成 Tekton 的 step。**纯函数**：无 IO、可单测。 */
    RenderedStep render(NodeContext ctx);
}
