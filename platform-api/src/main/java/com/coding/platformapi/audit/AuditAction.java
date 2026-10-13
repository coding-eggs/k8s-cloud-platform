package com.coding.platformapi.audit;

/**
 * 审计动作码常量。命名约定：{@code <resource>:<verb>}。
 *
 * <p>新增审计点时分两种用法：
 * <ul>
 *   <li>动作码是**新的** → 在这里加一条常量；</li>
 *   <li>动作码**已有**（如又一个删除工作负载的入口）→ 直接复用，不新增。</li>
 * </ul>
 *
 * <p><b>本类不承担完整性校验</b> —— 用户已明确不做「反射断言注解覆盖率」的护栏测试，
 * 所以这里只是防拼写；漏标不会被发现（代价已显式接受，见 spec §9）。
 *
 * <p>当前收的是首批高危动作（plan Task 9 标注）。
 */
public final class AuditAction {

    private AuditAction() {
    }

    public static final String WORKLOAD_DELETE = "workload:delete";

    public static final String WORKLOAD_ROLLBACK = "workload:rollback";

    public static final String NAMESPACE_DELETE = "namespace:delete";

    public static final String NODE_DRAIN = "node:drain";

    public static final String CLUSTER_DELETE = "cluster:delete";
}
