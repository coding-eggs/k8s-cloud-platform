package com.coding.k8sserver.chart;

/**
 * 一条检查结果。
 *
 * <p>BLOCK = 阻断安装；WARN = 只提示（管理员路径下的集群级对象 / hook）。
 * 跨命名空间永远 BLOCK —— 它不是"风险"，是"这份 chart 与目标位置不一致"。
 */
public record Violation(String severity, String type, String object, String message) {

    public static final String BLOCK = "BLOCK";
    public static final String WARN = "WARN";

    public boolean blocking() {
        return BLOCK.equals(severity);
    }

    public static Violation block(String type, String object, String message) {
        return new Violation(BLOCK, type, object, message);
    }

    public static Violation warn(String type, String object, String message) {
        return new Violation(WARN, type, object, message);
    }
}
