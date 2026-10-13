package com.coding.platformapi.audit;

import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;

/**
 * 审计动作码常量。命名约定：{@code <resource>:<verb>}，**动词在前**（`create` / `update` / `delete` /
 * `toggle-mesh` / `update-image`…），读记录的人一眼能看出发生了什么。
 *
 * <p>动作按**端点语义**取，不镜像 URL：`POST /workloads/{name}/mesh-toggle` 的动作码是
 * {@code workload:toggle-mesh} 而不是 {@code workload:mesh-toggle}。
 *
 * <p>新增审计点时分两种用法：
 * <ul>
 *   <li>动作码是**新的** → 在这里加一条常量；</li>
 *   <li>动作码**已有**（如又一个删除工作负载的入口）→ 直接复用，不新增。</li>
 * </ul>
 *
 * <p><b>本类不承担完整性校验</b> —— 已明确不做「断言写端点覆盖率」的护栏，也不维护写端点清单。
 * 这里只是动作码的词汇表，防拼写；漏标不会被发现（代价已显式接受）。
 */
public final class AuditAction {

    private AuditAction() {
    }

    // ---- 工作负载（7 个写端点全量）----

    public static final String WORKLOAD_CREATE = "workload:create";

    public static final String WORKLOAD_UPDATE = "workload:update";

    public static final String WORKLOAD_DELETE = "workload:delete";

    /** 暂停 / 恢复 Deployment 更新（同一端点，body.paused 决定方向） */
    public static final String WORKLOAD_PAUSE = "workload:pause";

    public static final String WORKLOAD_ROLLBACK = "workload:rollback";

    /** 改单个容器的镜像（直接发版） */
    public static final String WORKLOAD_UPDATE_IMAGE = "workload:update-image";

    /** 切 ambient 开关（pod template 的两个保留 label） */
    public static final String WORKLOAD_TOGGLE_MESH = "workload:toggle-mesh";

    // ---- 命名空间（7 个写端点全量）----

    public static final String NAMESPACE_CREATE = "namespace:create";

    public static final String NAMESPACE_UPDATE = "namespace:update";

    public static final String NAMESPACE_DELETE = "namespace:delete";

    public static final String NAMESPACE_QUOTA_UPSERT = "namespace:quota-upsert";

    public static final String NAMESPACE_QUOTA_DELETE = "namespace:quota-delete";

    public static final String NAMESPACE_LIMITRANGE_UPSERT = "namespace:limitrange-upsert";

    public static final String NAMESPACE_LIMITRANGE_DELETE = "namespace:limitrange-delete";

    // ---- 节点（4 个写端点）----

    public static final String NODE_CORDON = "node:cordon";

    public static final String NODE_UNCORDON = "node:uncordon";

    public static final String NODE_UPDATE_LABELS_TAINTS = "node:update-labels-taints";

    public static final String NODE_DRAIN = "node:drain";

    // ---- 集群（5 个写端点）----

    public static final String CLUSTER_CREATE = "cluster:create";

    public static final String CLUSTER_UPDATE = "cluster:update";

    public static final String CLUSTER_TOGGLE_ENABLED = "cluster:toggle-enabled";

    public static final String CLUSTER_DELETE = "cluster:delete";

    /** K8s 侧重新开通（幂等重试） */
    public static final String CLUSTER_PROVISION = "cluster:provision";

    /**
     * 全部动作码（常量类的反射视图），按字母序。
     *
     * <p><b>为什么用反射而不是再手写一份 List</b>：手写那份会与常量漂移（加了常量忘了加进 List），
     * 而反射永远与常量同步。审计页的动作码筛选候选值就取这里 —— 取的是**可能值全集**，
     * 不是审计表里的观测值（观测值在新装 / 刚上线时几乎是空的，下拉会只剩一两条）。
     */
    public static List<String> all() {
        return Arrays.stream(AuditAction.class.getDeclaredFields())
                .filter(f -> Modifier.isPublic(f.getModifiers())
                        && Modifier.isStatic(f.getModifiers())
                        && f.getType() == String.class)
                .map(f -> {
                    try {
                        return (String) f.get(null);
                    } catch (IllegalAccessException e) {
                        throw new IllegalStateException("读取审计动作码常量失败：" + f.getName(), e);
                    }
                })
                .sorted()
                .toList();
    }
}
