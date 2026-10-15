package com.coding.k8score.pipeline;

import java.util.List;

/**
 * 一个表单字段。**纯数据** —— 前端据此渲染，渲染器据此校验。
 *
 * @param type TEXT / TEXTAREA / SELECT / IMAGE。IMAGE 在前端会挂 S3.1 的候选补全
 */
public record NodeField(String key, String label, FieldType type,
                        boolean required, String placeholder, String help,
                        String defaultValue, List<Option> options) {

    public enum FieldType { TEXT, TEXTAREA, SELECT, IMAGE }

    public record Option(String value, String label) { }

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

    public static NodeField image(String key, String label) {
        return new NodeField(key, label, FieldType.IMAGE, true, "如 maven:3.9-eclipse-temurin-21",
                "基础镜像。**带候选补全** —— 从已配的镜像仓库里挑", null, List.of());
    }

    public static NodeField select(String key, String label, List<Option> options, String def) {
        return new NodeField(key, label, FieldType.SELECT, true, null, null, def, options);
    }
}
