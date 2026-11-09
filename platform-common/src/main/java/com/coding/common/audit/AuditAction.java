package com.coding.common.audit;

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

    // ---- 存储类（1 个写端点）----

    /** 设为 / 取消集群默认存储类（设时清掉其它已默认的 SC，单默认） */
    public static final String STORAGECLASS_SET_DEFAULT = "storageclass:set-default";

    // ---- PVC（4 个写端点全量）----

    /** 含从快照恢复：恢复走同一个端点，审计不存请求体，所以这条记录分不出是不是恢复 */
    public static final String PVC_CREATE = "pvc:create";

    /** 只同步标签；容量变更走 {@link #PVC_RESIZE} */
    public static final String PVC_UPDATE = "pvc:update";

    /** 在线扩容：动存储后端，不可逆 */
    public static final String PVC_RESIZE = "pvc:resize";

    public static final String PVC_DELETE = "pvc:delete";

    // ---- 卷快照（S4.3，2 个写端点全量；恢复走 PVC 创建）----

    public static final String VOLUME_SNAPSHOT_CREATE = "volumesnapshot:create";

    /** 删快照：快照类的 deletionPolicy=Delete 时会连带销毁存储后端的数据 */
    public static final String VOLUME_SNAPSHOT_DELETE = "volumesnapshot:delete";

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
    public static final String MIDDLEWARE_EXPOSE = "middleware:expose";

    /** 改中间件实例的鉴权口令（Milvus；新口令由平台生成或用户填写） */
    public static final String MIDDLEWARE_PASSWORD = "middleware:password";

    /** 中间件实例的账号管理：新增用户 / 改口令 / 删除 */
    public static final String MIDDLEWARE_USER_CREATE = "middleware:user:create";
    public static final String MIDDLEWARE_USER_PASSWORD = "middleware:user:password";
    public static final String MIDDLEWARE_USER_DELETE = "middleware:user:delete";

    /** 删除 Elasticsearch 实例里的一个索引（数据随之删除，不可恢复） */
    public static final String MIDDLEWARE_INDEX_DELETE = "middleware:index:delete";

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

    /** 开/关桶的匿名读（等于把该桶的读取公开） */
    public static final String OBJECT_BUCKET_ANONYMOUS_SET = "object-bucket:anonymous";

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

    // ---- 应用台账 ----

    public static final String APPLICATION_CREATE = "application:create";

    public static final String APPLICATION_DELETE = "application:delete";

    // 旧画布的 application:update / application:deploy 接口已移除；历史审计记录里仍可能出现这两个动作值

    // ---- 应用发布（服务配置 / 发布 / 续跑）----

    public static final String APPLICATION_SERVICE_CREATE = "application:service-create";

    public static final String APPLICATION_SERVICE_UPDATE = "application:service-update";

    public static final String APPLICATION_SERVICE_REMOVE = "application:service-remove";

    /** 修改应用设置（链路追踪 / 服务网格，只写期望值，发布时生效） */
    public static final String APPLICATION_SETTINGS_UPDATE = "application:settings-update";

    /** 创建发布（勾选的服务 key 记在 target 里） */
    public static final String APPLICATION_RELEASE_CREATE = "application:release-create";

    /** 回滚到历史发布（源发布号记在 target 里） */
    public static final String APPLICATION_RELEASE_ROLLBACK = "application:release-rollback";

    /** 下线：删掉应用的全部集群对象（PVC 保留），记一条 OFFLINE 发布 */
    public static final String APPLICATION_RELEASE_OFFLINE = "application:release-offline";

    /** 入口灰度：开始 / 调整权重与配置 / 全量 / 回退（服务 key 记在 target 里） */
    public static final String APPLICATION_CANARY_START = "application:canary-start";

    public static final String APPLICATION_CANARY_UPDATE = "application:canary-update";

    public static final String APPLICATION_CANARY_PROMOTE = "application:canary-promote";

    public static final String APPLICATION_CANARY_ABORT = "application:canary-abort";

    public static final String APPLICATION_DEPENDENCY_ADD = "application:dependency-add";

    /** 修改外部连接（地址 / 端口 / Secret 登记） */
    public static final String APPLICATION_DEPENDENCY_UPDATE = "application:dependency-update";

    public static final String APPLICATION_DEPENDENCY_REMOVE = "application:dependency-remove";

    public static final String APPLICATION_DEPENDENCY_INIT = "application:dependency-init";

    public static final String APPLICATION_EXECUTION_RESUME = "application:execution-resume";

    // ---- 镜像仓库（Harbor 租户项目）----

    /** 同步全部租户的 Harbor 项目与机器人账号 */
    public static final String REGISTRY_SYNC_PROJECTS = "registry:sync-projects";

    /** 重建单个租户（或平台共享）的 Harbor 机器人账号 */
    public static final String REGISTRY_REBUILD_PROJECT = "registry:rebuild-project";

    /** 重下发某集群全部命名空间的默认拉取凭据（租户镜像能不能拉） */
    public static final String REGISTRY_SYNC_PULL_SECRET = "registry:sync-pull-secret";

    // ---- OAuth 客户端 ----

    public static final String OAUTH_CLIENT_CREATE = "oauth-client:create";

    public static final String OAUTH_CLIENT_UPDATE = "oauth-client:update";

    public static final String OAUTH_CLIENT_DELETE = "oauth-client:delete";

    /** 启用与停用同一个动作码（请求体里的 enabled 区分；审计不记请求体，看路径即可） */
    public static final String OAUTH_CLIENT_ENABLE = "oauth-client:enable";

    public static final String OAUTH_CLIENT_RESET_SECRET = "oauth-client:reset-secret";

    // ---- 签名 / 加密密钥 ----

    /** 生成 JWK 并按优先级入库（高优先级签名密钥 = 决定平台用哪把钥匙签 token） */
    public static final String JWK_GENERATE = "jwk:generate";

    // ---- 告警 / 审计本身（2026-11-05 补齐） ----
    public static final String ALERT_DELETE = "alert:delete";

    /** 告警链路自检：往库里写测试告警行 */
    public static final String ALERT_SELF_TEST = "alert:self-test";

    /** 按时间范围删除审计记录 —— 删审计本身必须留痕 */
    public static final String AUDIT_PURGE = "audit:purge";


    // ---- 权限点 / 角色 / 用户 / 账号（2026-11-05 补齐） ----
    public static final String PERMISSION_CREATE = "permission:create";

    public static final String PERMISSION_UPDATE = "permission:update";

    public static final String PERMISSION_DELETE = "permission:delete";

    /** SQL 直接改库后重载运行时规则 */
    public static final String PERMISSION_RELOAD = "permission:reload";

    public static final String ROLE_CREATE = "role:create";

    public static final String ROLE_DELETE = "role:delete";

    /** 改角色名称 / 描述 */
    public static final String ROLE_UPDATE = "role:update";

    /** 整体覆盖角色绑定的权限码 */
    public static final String ROLE_PERMISSION_SAVE = "role:permission-save";

    public static final String USER_CREATE = "user:create";

    /** 管理员改用户资料 / 启停 */
    public static final String USER_UPDATE = "user:update";

    /** 管理员删除用户（软删，连带收回全部角色与租户成员关系） */
    public static final String USER_DELETE = "user:delete";

    public static final String USER_PLATFORM_ROLE_GRANT = "user:platform-role-grant";

    public static final String USER_PLATFORM_ROLE_REVOKE = "user:platform-role-revoke";

    /** 自助改密码（auth，走登录会话） */
    public static final String ACCOUNT_PASSWORD = "account:password";

    /** 自助改昵称 / 邮箱（auth，走登录会话） */
    public static final String ACCOUNT_PROFILE = "account:profile";


    // ---- 租户（2026-11-05 补齐） ----
    public static final String TENANT_CREATE = "tenant:create";

    public static final String TENANT_UPDATE = "tenant:update";

    public static final String TENANT_DELETE = "tenant:delete";

    public static final String TENANT_PROVISION = "tenant:provision";

    public static final String TENANT_NAMESPACE_ALLOCATE = "tenant:namespace-allocate";

    public static final String TENANT_NAMESPACE_DEALLOCATE = "tenant:namespace-deallocate";

    public static final String TENANT_MEMBER_ADD = "tenant:member-add";

    public static final String TENANT_MEMBER_REMOVE = "tenant:member-remove";

    public static final String TENANT_MEMBER_ROLE_GRANT = "tenant:member-role-grant";

    public static final String TENANT_MEMBER_ROLE_REVOKE = "tenant:member-role-revoke";


    // ---- 平台配置（2026-11-05 补齐） ----
    public static final String RBAC_TEMPLATE_CREATE = "rbac-template:create";

    public static final String RBAC_TEMPLATE_UPDATE = "rbac-template:update";

    public static final String RBAC_TEMPLATE_DELETE = "rbac-template:delete";

    /** 重下发全部模板到各租户 */
    public static final String RBAC_TEMPLATE_SYNC_ALL = "rbac-template:sync-all";

    public static final String NOTIFICATION_CHANNEL_CREATE = "notification-channel:create";

    public static final String NOTIFICATION_CHANNEL_UPDATE = "notification-channel:update";

    public static final String NOTIFICATION_CHANNEL_DELETE = "notification-channel:delete";

    /** 重新探测集群能力（CRD / API 组）并刷新缓存 */
    public static final String CLUSTER_REFRESH_CAPABILITY = "cluster:refresh-capability";


    // ---- Pod ----
    /** 删 Pod（控制器会重建 = 重启） */
    public static final String POD_DELETE = "pod:delete";


    // ---- ConfigMap ----
    public static final String CONFIGMAP_CREATE = "configmap:create";

    public static final String CONFIGMAP_UPDATE = "configmap:update";

    public static final String CONFIGMAP_DELETE = "configmap:delete";


    // ---- Secret ----
    public static final String SECRET_CREATE = "secret:create";

    public static final String SECRET_UPDATE = "secret:update";

    public static final String SECRET_DELETE = "secret:delete";


    // ---- Service ----
    public static final String SERVICE_CREATE = "service:create";

    public static final String SERVICE_UPDATE = "service:update";

    public static final String SERVICE_DELETE = "service:delete";


    // ---- HPA ----
    public static final String HPA_CREATE = "hpa:create";

    public static final String HPA_UPDATE = "hpa:update";

    public static final String HPA_DELETE = "hpa:delete";


    // ---- Gateway ----
    public static final String GATEWAY_CREATE = "gateway:create";

    public static final String GATEWAY_UPDATE = "gateway:update";

    public static final String GATEWAY_DELETE = "gateway:delete";


    // ---- HTTPRoute ----
    public static final String HTTPROUTE_CREATE = "httproute:create";

    public static final String HTTPROUTE_UPDATE = "httproute:update";

    public static final String HTTPROUTE_DELETE = "httproute:delete";


    // ---- GRPCRoute ----
    public static final String GRPCROUTE_CREATE = "grpcroute:create";

    public static final String GRPCROUTE_UPDATE = "grpcroute:update";

    public static final String GRPCROUTE_DELETE = "grpcroute:delete";


    // ---- TCPRoute ----
    public static final String TCPROUTE_CREATE = "tcproute:create";

    public static final String TCPROUTE_UPDATE = "tcproute:update";

    public static final String TCPROUTE_DELETE = "tcproute:delete";


    // ---- TLSRoute ----
    public static final String TLSROUTE_CREATE = "tlsroute:create";

    public static final String TLSROUTE_UPDATE = "tlsroute:update";

    public static final String TLSROUTE_DELETE = "tlsroute:delete";


    // ---- UDPRoute ----
    public static final String UDPROUTE_CREATE = "udproute:create";

    public static final String UDPROUTE_UPDATE = "udproute:update";

    public static final String UDPROUTE_DELETE = "udproute:delete";


    // ---- GatewayClass ----
    public static final String GATEWAYCLASS_CREATE = "gatewayclass:create";

    public static final String GATEWAYCLASS_UPDATE = "gatewayclass:update";

    public static final String GATEWAYCLASS_DELETE = "gatewayclass:delete";


    // ---- ServiceMonitor ----
    public static final String SERVICEMONITOR_CREATE = "servicemonitor:create";

    public static final String SERVICEMONITOR_UPDATE = "servicemonitor:update";

    public static final String SERVICEMONITOR_DELETE = "servicemonitor:delete";


    // ---- PodMonitor ----
    public static final String PODMONITOR_CREATE = "podmonitor:create";

    public static final String PODMONITOR_UPDATE = "podmonitor:update";

    public static final String PODMONITOR_DELETE = "podmonitor:delete";


    // ---- PrometheusRule ----
    public static final String PROMETHEUSRULE_CREATE = "prometheusrule:create";

    public static final String PROMETHEUSRULE_UPDATE = "prometheusrule:update";

    public static final String PROMETHEUSRULE_DELETE = "prometheusrule:delete";


    // ---- Calico IPPool ----
    public static final String IPPOOL_CREATE = "ippool:create";

    public static final String IPPOOL_UPDATE = "ippool:update";

    public static final String IPPOOL_DELETE = "ippool:delete";


    // ---- Calico IPReservation ----
    public static final String IPRESERVATION_CREATE = "ipreservation:create";

    public static final String IPRESERVATION_UPDATE = "ipreservation:update";

    public static final String IPRESERVATION_DELETE = "ipreservation:delete";


    // ---- Calico BGPConfiguration ----
    public static final String BGPCONFIGURATION_CREATE = "bgpconfiguration:create";

    public static final String BGPCONFIGURATION_UPDATE = "bgpconfiguration:update";

    public static final String BGPCONFIGURATION_DELETE = "bgpconfiguration:delete";


    // ---- Calico BGPPeer ----
    public static final String BGPPEER_CREATE = "bgppeer:create";

    public static final String BGPPEER_UPDATE = "bgppeer:update";

    public static final String BGPPEER_DELETE = "bgppeer:delete";


    // ---- Calico BGPFilter ----
    public static final String BGPFILTER_CREATE = "bgpfilter:create";

    public static final String BGPFILTER_UPDATE = "bgpfilter:update";

    public static final String BGPFILTER_DELETE = "bgpfilter:delete";


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
