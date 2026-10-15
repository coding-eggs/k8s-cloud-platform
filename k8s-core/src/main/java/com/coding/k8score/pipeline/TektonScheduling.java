package com.coding.k8score.pipeline;

import java.util.Map;

/**
 * Tekton 的**调度相关**配置：{@code feature-flags} ConfigMap 的 {@code coschedule}。
 *
 * <p>⚠️ 为什么平台要知道它：{@code coschedule: workspaces}（Tekton 默认）下，
 * **一个 TaskRun 不允许挂两块 PVC 类 workspace** ——
 * {@code pkg/reconciler/taskrun/taskrun.go} 会直接判 `FailedValidation`。而构建缓存正是
 * 「共享 workspace 之外再挂一块长期卷」，所以那个值决定缓存能不能用。清单见
 * {@code docs/development/cluster-prerequisites.md}。
 *
 * <p>本类只做**解析**（纯函数，可单测）；读取在调用方（k8s-server 侧，且必须是 admin 凭据 ——
 * 租户 SA 读不到 {@code tekton-pipelines}）。
 */
public final class TektonScheduling {

    /** Tekton 的配置住在这个命名空间。 */
    public static final String NAMESPACE = "tekton-pipelines";
    public static final String FEATURE_FLAGS = "feature-flags";
    public static final String COSCHEDULE_KEY = "coschedule";

    /** 唯一允许"一个 TaskRun 挂多块 PVC workspace"的值（1.15.3；老文档里的 {@code pipeline} 已改名）。 */
    public static final String MULTI_WORKSPACE = "pipelineruns";

    /** Tekton 自己的默认值 —— 键缺失时就是它。 */
    public static final String DEFAULT_COSCHEDULE = "workspaces";

    private TektonScheduling() {
    }

    /**
     * 取 {@code coschedule}（入参是 ConfigMap 的 data 段）。
     *
     * <p>键缺失 = Tekton 用它的默认值 {@code workspaces} —— 这不是"读不到"，是**真的没开**。
     */
    public static String coscheduleOf(Map<String, String> data) {
        if (data == null) {
            return DEFAULT_COSCHEDULE;
        }
        String v = data.get(COSCHEDULE_KEY);
        return v == null || v.isBlank() ? DEFAULT_COSCHEDULE : v.trim();
    }

    /**
     * 这个值允不允许一个 TaskRun 挂两块 PVC 类 workspace（= 构建缓存能不能开）。
     *
     * <p>ConfigMap 不在（data 空）→ 默认值 workspaces → false，这是**对的**；
     * 而读它时出错（权限/网络）也会落在这条 false 上 —— 那是有意的 fail-closed：
     * 退化掉缓存只是慢，而在不支持多 workspace 的集群上真去挂两块 = 运行直接判失败。
     */
    public static boolean allowsMultiplePvcWorkspaces(Map<String, String> data) {
        return MULTI_WORKSPACE.equals(coscheduleOf(data));
    }
}
