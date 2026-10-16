package com.coding.k8score.pipeline;

import java.util.List;

/**
 * 一个表单字段。**纯数据** —— 前端据此渲染，渲染器据此校验。
 *
 * @param type TEXT / TEXTAREA / SELECT / IMAGE / BOOLEAN / NUMBER。IMAGE 在前端会挂 S3.1 的候选补全
 * @param advanced {@code true} = 收在属性面板的「高级」里。
 *                 常态不用填、但排障/特殊环境必须能填的字段走这里（例如代理）——
 *                 摊在面板上会把真正要看的几个字段挤下去。
 * @param unit 只对 {@link FieldType#NUMBER} 有意义：**固定**跟在输入框后面的单位（如 {@code Mi}）。
 *             ⚠️ 单位**不交给用户填** —— 让用户填自由文本，最后必然同时出现 `2G` / `2GB` / `2048` 三种写法，
 *             而它们进了容器就是三种不同的意思（甚至非法值）。数字归数字、单位归字段。
 */
public record NodeField(String key, String label, FieldType type,
                        boolean required, String placeholder, String help,
                        String defaultValue, List<Option> options, boolean advanced,
                        String unit) {

    public enum FieldType { TEXT, TEXTAREA, SELECT, IMAGE, BOOLEAN, NUMBER }

    public record Option(String value, String label) { }

    /** 八参的便利构造：{@code advanced=false}、{@code unit=null}（绝大多数字段）。 */
    public NodeField(String key, String label, FieldType type, boolean required,
                     String placeholder, String help, String defaultValue, List<Option> options) {
        this(key, label, type, required, placeholder, help, defaultValue, options, false, null);
    }

    /** 九参的便利构造：给得出 {@code advanced}、但没有单位（数字字段之外都走这条）。 */
    public NodeField(String key, String label, FieldType type, boolean required,
                     String placeholder, String help, String defaultValue, List<Option> options,
                     boolean advanced) {
        this(key, label, type, required, placeholder, help, defaultValue, options, advanced, null);
    }

    public static NodeField text(String key, String label, boolean required) {
        return new NodeField(key, label, FieldType.TEXT, required, null, null, null, List.of());
    }

    /**
     * 带一句说明的文本字段 —— 说明渲染在输入框下方（前端的 {@code props-help}）。
     *
     * <p>⚠️ 说明是**纯文本**，别写 Markdown（面板里不长 Markdown，`**x**` 会原样显示成星号）。
     * 用在「填了会怎样、留空是什么意思」这类光看 label 猜不出来的字段上。
     */
    public static NodeField text(String key, String label, boolean required, String help) {
        return new NodeField(key, label, FieldType.TEXT, required, null, help, null, List.of());
    }

    /** 收在「高级」里的文本字段（见 {@link #advanced}）。 */
    public static NodeField advancedText(String key, String label, String help) {
        return new NodeField(key, label, FieldType.TEXT, false, null, help, null, List.of(), true);
    }

    /**
     * 收在「高级」里的**数字**字段：只填数字，单位**固定**（渲染成输入框 + 后面跟着 {@code unit}）。
     *
     * <p>与 {@link #advancedText} 的区别就是这一点：单位不由用户写，取值只有一种解释。
     */
    public static NodeField advancedNumber(String key, String label, String unit, String help) {
        return new NodeField(key, label, FieldType.NUMBER, false, null, help, null, List.of(),
                true, unit);
    }

    /**
     * 开关（收在「高级」里）。值就是 {@code "true"} / {@code "false"} 字符串 ——
     * 与其它字段一样"全是字符串"（{@code config} 的形状不为此破例）。
     */
    public static NodeField advancedCheckbox(String key, String label, String help) {
        return new NodeField(key, label, FieldType.BOOLEAN, false, null, help, "false",
                List.of(), true);
    }

    public static NodeField image(String key, String label) {
        return new NodeField(key, label, FieldType.IMAGE, true, "如 maven:3.9-eclipse-temurin-21",
                "基础镜像。带候选补全 —— 从已配的镜像仓库里挑", null, List.of());
    }

    /**
     * **可留空**的镜像字段：留空 = 用节点自带的默认镜像（默认值写在 placeholder 上，一眼看得到）。
     *
     * <p>与 {@link #image} 的区别就在这里：预设构建节点必须**开箱能用**，而内网/离线集群
     * 又要能换成自己 Harbor 里的镜像 —— 候选补全正是为后者准备的。
     */
    public static NodeField imageOptional(String key, String label, String defaultImage) {
        return new NodeField(key, label, FieldType.IMAGE, false, "留空 = " + defaultImage,
                "留空 = 用节点自带的默认镜像 " + defaultImage + "。\n"
                        + "要换成自己仓库里的（内网/离线集群）在这里填，带候选补全", null, List.of());
    }

    /** 多行文本（构建命令这类）。与 {@link #text} 只差 FieldType。 */
    public static NodeField textarea(String key, String label, String help) {
        return new NodeField(key, label, FieldType.TEXTAREA, false, null, help, null, List.of());
    }

    /** 必填的多行文本（如「命令」节点的命令 —— 没有命令这个节点就没有意义）。 */
    public static NodeField textareaRequired(String key, String label, String help) {
        return new NodeField(key, label, FieldType.TEXTAREA, true, null, help, null, List.of());
    }

    public static NodeField select(String key, String label, List<Option> options, String def) {
        return new NodeField(key, label, FieldType.SELECT, true, null, null, def, options);
    }
}
