package com.coding.k8score.pipeline;

import java.util.List;

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

    // ---- 下面四个是**长相**：画布上的卡片长什么样。都有默认值，所以加一个节点仍然只需要实现上面三个 ----

    /** 画布与面板上显示的名字。默认就是 type。 */
    default String label() {
        return type();
    }

    /** 一句话说明「这个节点是干嘛的」，显示在节点面板上。默认空（不写就不显示）。 */
    default String description() {
        return "";
    }

    /** Element Plus 图标名（前端全局注册过）。 */
    default String icon() {
        return "Operation";
    }

    /** 配色**语义名**（不是色值）：success / accent / warning / info / danger。
     *  用语义名而不是 hex，是为了让浅色与暗色两套主题各自取自己的色 —— 写死色值会在另一半主题里翻车。 */
    default String accent() {
        return "info";
    }

    /**
     * 卡片副标题取哪几个字段的值（按顺序用 ` · ` 连起来）。
     *
     * <p>这样「不点开也知道这个节点在干嘛」，而节点作者只需要说**用哪几个字段**，
     * 不需要发明摘要模板（本平台没有模板语言，这条边界在 §2.2 已经立过）。
     */
    default List<String> summaryFields() {
        return List.of();
    }
}
