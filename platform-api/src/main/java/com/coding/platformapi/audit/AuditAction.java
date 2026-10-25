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

    /** 开/关 OTel 可观测接入（注入注解 + OTEL_SERVICE_NAME） */
    public static final String WORKLOAD_TOGGLE_OBSERVABILITY = "workload:toggle-observability";

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

    /** 安装/升级集群组件（helm 执行，cluster-admin 级写入） */
    public static final String COMPONENT_APPLY = "component:apply";

    /** 中止进行中的组件操作（只释放平台侧并发锁，不 kill helm 进程） */
    public static final String COMPONENT_ABANDON = "component:abandon";

    /** 重新探测集群组件（清缓存语义上的重探：探完覆写缓存） */
    public static final String CLUSTER_REFRESH_COMPONENTS = "cluster:refresh-components";

    // ---- 应用商店 ----

    /** 安装应用（helm 执行，租户路径会做三道闸的静态检查） */
    public static final String APP_INSTALL = "app:install";

    /** 升级应用到目标版本 */
    public static final String APP_UPGRADE = "app:upgrade";

    /** 卸载应用（CRD 与 PVC 不删） */
    public static final String APP_UNINSTALL = "app:uninstall";

    // ---- 中间件商店 ----

    /** 创建中间件实例 */
    public static final String MIDDLEWARE_CREATE = "middleware:create";

    /** 扩容中间件实例（节点数/资源） */
    public static final String MIDDLEWARE_SCALE = "middleware:scale";

    /** 删除中间件实例（keepPvc 决定数据卷去留） */
    public static final String MIDDLEWARE_DELETE = "middleware:delete";

    /** 修改中间件实例显示名（只改台账） */
    public static final String MIDDLEWARE_RENAME = "middleware:rename";

    /** 安装/升级中间件 Operator（管理员） */
    public static final String MIDDLEWARE_OPERATOR_INSTALL = "middleware:operator:install";

    // ===== 对象存储（SeaweedFS 组件）=====

    /** 建桶（管理员代建） */
    public static final String OBJECT_BUCKET_CREATE = "object-bucket:create";

    /** 删桶 */
    public static final String OBJECT_BUCKET_DELETE = "object-bucket:delete";

    /** 设置租户的桶数上限 */
    public static final String OBJECT_QUOTA_SET = "object-quota:set";

    /** 修改某个桶的容量上限（SeaweedFS 每桶 quota） */
    public static final String OBJECT_BUCKET_QUOTA_SET = "object-bucket:quota";

    /** 发放访问凭据（每桶一组 AK/SK） */
    public static final String OBJECT_CREDENTIAL_ISSUE = "object-credential:issue";

    /** 查看凭据（读取 secretKey 是敏感动作，留痕） */
    public static final String OBJECT_CREDENTIAL_VIEW = "object-credential:view";

    /** 吊销凭据 */
    public static final String OBJECT_CREDENTIAL_REVOKE = "object-credential:revoke";

    /** 上传对象（只记 key，不记内容） */
    public static final String OBJECT_UPLOAD = "object:upload";

    /** 删除单个对象 */
    public static final String OBJECT_DELETE = "object:delete";

    // ---- 凭据（S0.2，4 个写端点全量）----

    public static final String CREDENTIAL_CREATE = "credential:create";

    public static final String CREDENTIAL_UPDATE = "credential:update";

    /** 硬删除。审计里不记 endpoint 与凭据内容 —— 见 spec §7.1 */
    public static final String CREDENTIAL_DELETE = "credential:delete";

    /**
     * 测试连接。这是 S0.2 里**唯一的「使用」路径** —— 它真的解密凭据并出站打对端。
     *
     * <p>CI 触发、镜像拉取等其它使用动作的审计归各自的批次（S3.5 / S3.1），
     * 因为那些动作发生在它们自己的端点里。
     */
    public static final String CREDENTIAL_TEST = "credential:test";

    // ---- 流水线（S3.5 第 ① 片，5 个写端点全量）----

    public static final String PIPELINE_CREATE = "pipeline:create";

    public static final String PIPELINE_UPDATE = "pipeline:update";

    public static final String PIPELINE_DELETE = "pipeline:delete";

    /**
     * 触发一次运行。台账 S0.1.3 点名了「CI 触发」要进审计 ——
     * 它是本平台「让集群跑任意代码」的那条路径（spec §10.1）。
     */
    public static final String PIPELINE_RUN = "pipeline:run";

    public static final String PIPELINE_CANCEL = "pipeline:cancel";

    /**
     * 清掉命名空间的构建缓存（删那块长期卷）。
     *
     * <p>它值得进审计：这一步会让**下一次构建变慢**（依赖要重下一遍），而用户看到的现象是
     * "别人动过之后我的构建慢了" —— 没有审计就只能猜。
     */
    public static final String PIPELINE_CACHE_CLEAR = "pipeline:cache-clear";

    /**
     * 删掉一条运行记录（运行历史里的行）。
     *
     * <p>它值得进审计：删掉之后**那次运行就再也查不到了**（日志与 YAML 都只在那一行上，
     * 集群里的对象早在归档时就删了）—— 事后想追「那次到底怎么跑的」只能靠审计里这条。
     */
    public static final String PIPELINE_RUN_DELETE = "pipeline:run-delete";

    /**
     * 清掉待重试工作区。
     *
     * <p>它值得进审计：清掉之后**那些运行就再也不能从断点继续了**，而用户看到的现象是
     * 「重试按钮突然报工作区已过期」—— 不记下来就只能猜是谁清的。
     */
    public static final String PIPELINE_RETRY_WORKSPACE_CLEAR = "pipeline:retry-workspace-clear";

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
