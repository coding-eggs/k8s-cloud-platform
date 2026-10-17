package com.coding.k8sserver.helm;

/** helm 一次调用的结果。{@code ok=false} 时 exitCode 与脱敏后的输出都保留，便于写进操作记录。 */
public record HelmResult(boolean ok, int exitCode, String output) {
}
