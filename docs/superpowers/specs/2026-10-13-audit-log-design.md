# 审计日志设计（platform-api）

> 任务台账 **S0.1**。需求依据与裁定理据见 `docs/gap-analysis-2026-10-12.md`（§5.1 #5、§7.4.3）。

**目标**：让平台能回答「**谁、在什么时候、哪个集群/命名空间、改了什么、成功还是失败**」，
并覆盖「谁试图越权被拦」。当前状态是**零留痕** —— 库里没有任何审计表，代码里 `audit` 一词零命中。

**范围**：只做 `platform-api` 一层（它是平台唯一入口），不覆盖 `k8s-server`。

---

## 已定决策（brainstorming 结论，2026-10-13）

| 决策点 | 结论 | 理由 |
|---|---|---|
| **审计范围** | **写操作 + 被拒请求（403）** | 不含变更前后值、不含读操作、不存请求体全文。被拒请求是安全审计最常查的东西 |
| **可见范围** | **只有平台管理员** | 一个页面、一套权限点；租户看不到。**表结构与查询接口不预留租户维度**（YAGNI，将来要开时再说） |
| **写操作判据** | **注解声明**（`@Auditable`） | 用户明确否决「权限表加 `is_mutation` 列」。⚠️ 背景：本平台的查询也走 POST（`/pods/list`、`/cluster/list`），所以**按 HTTP method 判断写操作在此平台直接不成立** |
| **拦截机制** | **注解 + AOP** 管写操作；**复用已有的 Security 钩子**管 403 | AOP 切方法调用，**被拒请求进不了方法**，所以拒绝那一半必须靠钩子 —— 好在 `ResourceServerConfig` 已有 `authenticationEntryPoint` / `accessDeniedHandler` 两个现成扩展点 |
| **写入契约** | **同步写 + fail-open** | 审计的可用性不应低于业务；写操作频率远低于读，多一跳 DB 可接受。DB 挂掉期间无记录，这是显式接受的代价 |
| **401** | **不记** | token 过期是常态，噪声大，且那时没有身份可记 |
| **覆盖护栏** | **不做** | 用户明确不要「反射断言注解覆盖」这类测试。代价显式接受：**漏标不会被发现** |
| **保留策略** | **不分区、不自动清理** | 量级估算一年约 10 万条，远不需要分区。只提供一个按时间范围删除的管理端点 |
| **事务** | 审计写走 `REQUIRES_NEW`；Aspect 显式 `@Order` 排在外层 | 见 §5.1，这是本设计最容易写错的一处 |

---

## 1. 拦截拓扑

```
请求 ─► [Spring Security 过滤器链]
          ├─ 401 ─► authenticationEntryPoint        （不记）
          ├─ 403 ─► accessDeniedHandler ───────────┐
          └─ 通过 ─► DispatcherServlet             │
                       └─► Controller               │
                            └─► Service             │
                                 │                  │
                    @Auditable 方法 ┴─► AuditAspect │
                                            │       │
                                            ▼       ▼
                                    AuditService.record()   ← 同步写 + fail-open
                                            │
                                            ▼
                                     platform_audit_log
```

两条路径**共用一张表**，靠 `result` 区分：写操作落 `SUCCESS` / `FAILED`，被拒落 `DENIED`。

---

## 2. 组件清单

全部在 `platform-api`，除 DTO/实体/Mapper 在 `platform-data`。

| 组件 | 路径 | 职责 |
|---|---|---|
| `@Auditable` | `platform-api/.../audit/Auditable.java` | 方法级注解，标在 **Service 的写方法**上 |
| `AuditAction` | `platform-api/.../audit/AuditAction.java` | 动作码常量（`<resource>:<verb>`） |
| `AuditResult` | `platform-api/.../audit/AuditResult.java` | 枚举 `SUCCESS` / `FAILED` / `DENIED` |
| `AuditAspect` | `platform-api/.../audit/AuditAspect.java` | `@Around` + 显式 `@Order`（见 §5.1） |
| `AuditWriter` | `platform-api/.../audit/AuditWriter.java` | **独立 bean**，只放 `@Transactional(REQUIRES_NEW)` 的写方法 —— 与 `PermissionRowWriter` 同构；若把写方法塞进 `AuditService` 并由同类方法自调用，代理会被绕过、`REQUIRES_NEW` 静默失效（见 §5.1） |
| `AuditService` | `platform-api/.../services/AuditService.java` | fail-open 包一层写（try/catch → `AuditWriter`）+ 查询 + `recordDenied`。**记录组装在 Aspect**（它才有 join point） |
| `AuditDenied` | `platform-api/.../audit/AuditDenied.java` | request attribute 名 + 读写帮助方法（deny 时塞权限码） |
| `AuditController` | `platform-api/.../controllers/AuditController.java` | 薄 controller：绑定 + `ResponseData` 包装 |
| `AuditQueryRequest` | `platform-api/.../models/AuditQueryRequest.java` | 查询入参（筛选 + 游标分页） |
| `PlatformAuditLog` | `platform-data/.../models/system/PlatformAuditLog.java` | 实体 |
| `PlatformAuditLogMapper` | `platform-data/.../mapper/system/PlatformAuditLogMapper.java` + `resources/mapper/system/PlatformAuditLogMapper.xml` | insert / 条件查询 / 按时间范围删除 |
| `spring-boot-starter-aop` | `platform-api/pom.xml` | **新增依赖**。⚠️ **版本要先核实**，见 §2.1 |
| Security 两个钩子 | `platform-api/.../configs/ResourceServerConfig.java` | **已存在**，只改回调体（403 记一条；401 不动） |
| deny 时写入权限码 | `platform-api/.../security/PermissionAuthorizationManager.java` | `authorize()` 的 deny 分支把求得的 code 塞进 request attribute（它拿得到 `RequestAuthorizationContext.getRequest()`）；`accessDeniedHandler` 读出来落到 `action` 列（见 §4.1） |

> **新增 `audit/` 包**而不是塞进 `security/` 或 `components/`：注解 / 枚举 / 切面三者是一组，
> 放在一起才好读；`security/` 的语义是「鉴权」，审计是它的消费者而不是同位概念。

### 2.1 ⚠️ AOP 依赖版本要先核实（本仓的一处陈旧配置）

**现状**：
- 项目实际是 **Spring Boot 4.1.1**（根 pom `spring.boot.version`，import 了 `spring-boot-dependencies:4.1.1`）；
- 但根 pom 的 `dependencyManagement` 里另有一条**显式**管理项把 `spring-boot-starter-aop` 钉在
  `${spring.aop.version}` = **3.5.13**（疑似 Boot 3 → 4 升级的遗留）；
- `k8s-server/pom.xml` 声明了这个 starter 却**全仓无一 `@Aspect`**（已核实），
  所以这个版本偏差**至今没有症状** —— **platform-api 将是第一处真正使用 Spring AOP 的地方**。

**因此实施第一步要先验证**：加完依赖后确认解析出的 `spring-aop` / `aspectjweaver` 与 Boot 4.1.1 一致
（期望 `spring-aop` 7.0.x），而不是被那条钉死的 3.5.13 带偏。

- 若解析正确（Boot BOM 先声明、按「首个声明优先」胜出）→ 直接加依赖、不带版本即可；
- 若被带偏 → **清掉根 pom 里那条陈旧管理项**（连同 `spring.aop.version` 属性）；`k8s-server` 那条无用的
  starter 声明可一并清理（它没有任何 aspect）。

> 这属于「顺手修掉挡路的既有问题」，不扩大范围：只动与本功能直接相关的那条依赖声明。

---

## 3. 注解与上下文抽取

### 3.1 注解形态

```java
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Auditable {
    /** 动作码，取 AuditAction 里的常量 */
    String action();

    /** 目标对象名的 SpEL（如 "#name" / "#req.workloadName"）。留空则按命名约定探测 */
    String target() default "";

    /** 上下文覆盖（clusterId/namespace/tenantId）的 SpEL，逗号分隔。留空则按约定探测 */
    String scope() default "";
}
```

用法（显式优先、隐式兜底）：

```java
@Auditable(action = AuditAction.WORKLOAD_DELETE, target = "#name")
public void delete(String name, String tenantId, String clusterId, String namespace) { ... }

@Auditable(action = AuditAction.TENANT_NAMESPACE_ALLOCATE)   // 零配置
public NamespaceAllocation create(AllocationCreateRequest req) { ... }
```

### 3.2 隐式探测的约定

未显式给 `target` / `scope` 时，Aspect 按**参数名或 DTO 字段名**探测
`tenantId` / `clusterId` / `namespace` / `name`。**探测深度只到「方法参数本身 + 单个 DTO 参数的一级字段」，不递归。**

这正是本平台 DTO 的统一形状（`BaseResources` 子类与 `XxxDTO` 都带这几个字段），所以简单场景零配置即可。
嵌套结构（如 `#req.spec.foo`）必须走显式 SpEL —— 保持探测规则简单可预测，比"猜得深"更重要。

**`tenant_id` 的取值优先级**（见 §5.6）：**参数里的 tenantId 优先于 token 的租户帽**。

### 3.3 SpEL 求值失败不致命

求值异常 ⇒ 对应字段记 NULL + `log.warn`，其余字段照记，**不影响业务**。
（fail-open 同样适用于 SpEL —— 注解写错不该让生产接口挂。）

---

## 4. 数据模型

### 4.1 表 `platform_audit_log`

| 列 | 类型 | 说明 |
|---|---|---|
| `id` | BIGINT AUTO_INCREMENT | 主键。**刻意不用本仓惯用的 ULID** —— 追加型日志表用自增，插入更省、时间序扫描更顺；对外分页用「时间 + id」游标 |
| `occurred_at` | DATETIME(3) | 发生时刻（毫秒） |
| `actor_username` | VARCHAR(64) NOT NULL | 操作者账号。**取不到时记字面量 `unknown`**（不记 NULL）—— 避免与「平台侧无租户操作」那类 NULL 语义混淆，也便于排查身份丢失的来源 |
| `actor_display_name` | VARCHAR(64) NULL | **冗余存**，见 §4.2 |
| `tenant_id` | VARCHAR(26) NULL | 被操作的租户（代操作时是目标租户；平台侧无租户操作为 NULL） |
| `cluster_id` | VARCHAR(26) NULL | |
| `namespace` | VARCHAR(63) NULL | |
| `action` | VARCHAR(64) NULL | **语义随 `result` 变**：写操作存动作码 `<resource>:<verb>`；`DENIED` 存**被拒的权限码**（如 `platform:audit:read`）。两者都是 `xxx:yyy` 形状，靠 `result` 区分语义，**因此不需要另加 `denied_code` 列** |
| `target_name` | VARCHAR(253) NULL | 目标对象名；`DENIED` 记录为空（拒绝发生在方法调用前，无从知道目标） |
| `result` | VARCHAR(16) | `SUCCESS` / `FAILED` / `DENIED` |
| `error_code` | VARCHAR(32) NULL | `FAILED` 的业务码 |
| `error_msg` | VARCHAR(512) NULL | `FAILED` 的业务 msg |
| `http_method` | VARCHAR(8) NULL | 仅 `DENIED` 有值 |
| `request_path` | VARCHAR(512) NULL | 仅 `DENIED` 有值 |
| `client_ip` | VARCHAR(45) NULL | 兼容 IPv6 |
| `user_agent` | VARCHAR(256) NULL | 安全排查用 |
| `duration_ms` | INT NULL | 耗时；`DENIED` 为 NULL |

**索引只留四个**（每个索引都要在写入时付代价）：

```
PRIMARY KEY (id)
KEY idx_occurred    (occurred_at, id)                    -- 主排序 + 游标分页
KEY idx_actor       (actor_username, occurred_at)
KEY idx_cluster_ns  (cluster_id, namespace, occurred_at)
KEY idx_result      (result, occurred_at)                -- 查 DENIED / FAILED
```

**不给 `action` 单建索引**：选择性低，且在时间范围内过滤它就够了。

### 4.2 三个设计决定

1. **审计记录必须自包含。** 一旦落库就不得依赖其他表才读得懂 —— 账号改名或删除后，历史记录仍要能回答「当时是谁做的」。所以 `actor_display_name` 冗余存。
   **但只冗余这一个**：`resource_type` 从 `action` 就能推出来，不再单存一列（避免同一事实两处）。
2. **两类记录共表，不加 `source` 列。** `result = DENIED` 本身就是区分：这类记录 `action` / `target_name` 为空、靠 `request_path` + `http_method` 定位；写操作则相反。401 不记 ⇒ `result` 只有三个值。
3. **动作码用 `<resource>:<verb>` 命名约定**（`workload:delete`、`namespace:quota-upsert`、`node:drain`、`tenant:namespace-allocate`…），常量集中在 `AuditAction` 类里。**用户已决定不做「反射断言覆盖」的护栏**，所以常量类只是防拼写，不承担完整性校验。

---

## 5. 边界情况

### 5.1 ⚠️ 事务 —— 本设计最容易写错的一处

本仓 `PlatformApiApplication` 有 `@EnableTransactionManagement`，且 Service 方法级 `@Transactional` 真在用（`RoleService` / `TenantMemberService` / `PermissionRowWriter`）。由此产生两个问题：

- **业务回滚会把审计记录一起回滚掉** —— 恰好丢掉最想留的那条 `FAILED`；
- **审计写失败若污染业务事务**（标记 rollback-only）→ 破坏 fail-open。

**两道处理，都要做**：

1. `AuditService.record()` 标 **`@Transactional(propagation = Propagation.REQUIRES_NEW)`** —— 独立事务提交：不随业务回滚，失败也不牵连业务；
2. `AuditAspect` **显式 `@Order`**，值小于事务通知的默认 `Ordered.LOWEST_PRECEDENCE`（例如 `@Order(0)`）—— 保证记录时机在业务提交/回滚**之后**，看到的是真实结果。

> 同族先例：`PermissionRowWriter` 的类注释记着「独立 bean 让 `@Transactional` 走代理」的坑 —— 同一个代理/事务家族的另一个面。
> 本设计天然规避了那个坑：**`AuditAspect` → `AuditService` 是跨 bean 调用**，`REQUIRES_NEW` 的代理生效。
> 若日后有人把记录逻辑挪进 Aspect 内部的自调用，`REQUIRES_NEW` 会静默失效 —— 那正是这个坑的复现形态。

### 5.2 非 HTTP 线程

`RequestContextHolder` 取不到时（将来的定时任务、异步线程），`client_ip` / `user_agent` / `request_path` 记 NULL，**不抛**。

### 5.3 SpEL 求值失败

见 §3.3：降级为 NULL + `log.warn`，不抛。

### 5.4 ⚠️ 403 钩子里那行调用绝不能抛

这是**唯一一处「审计失败会改变用户可见结果」的位置** —— 它一抛，403 就变成 500，把「权限不足」伪装成「服务故障」，还掩盖了真实原因。该调用必须显式包在 fail-open 里。

### 5.5 流式端点天然不受影响

没有 Filter 包装、没有注解 ⇒ Pod / Node 日志流与 exec WebSocket 完全不进审计路径。
**写进文档是为了挡住后来者「顺手」给流式方法加注解** —— 那会把流式响应拖进审计路径。

### 5.6 代操作时 `tenant_id` 取谁

admin 用 base token 代租户操作时，token 的租户帽是 NULL、参数里带着 tenantId。
**优先记参数里的**（被代操作的目标租户），退而取 token 帽。这样审计能回答
「这个租户的命名空间是被平台代删的」，而不是笼统地记成一次平台侧操作。

---

## 6. 权限与迁移

迁移文件：`platform-data/src/main/resources/db/migration/V2026_10_13_1__audit_log.sql`
（接在当前最新的 `V2026_10_12_1` 之后）。形状照 `V2026_10_10_1__cluster_overview_permissions.sql` 的既有惯例：
**显式 id + `ON DUPLICATE KEY UPDATE` 保证幂等**；角色关联按**显式 id 列表**，**不用 LIKE**
（id 里的 `_` 是 LIKE 的单字符通配符，`V2026_10_08_5` 已踩过）。

| # | domain | resource | action | code | 用途 |
|---|---|---|---|---|---|
| 1 | `API` | `/audit/list` | `POST` | `platform:audit:read` | 审计列表（筛选 + 游标分页；**返回全部字段**） |
| 2 | `API` | `/audit/purge` | `POST` | `platform:audit:purge` | 按时间范围删除 |
| 3 | `Page` | `/audit` | `view` | `platform:page:audit` | 前端页面可见性 |

> **偏离记录（写 plan 时发现）**：本表原列了第 4 行 `/audit/get`，与 §7 的「详情 = 行展开」自相矛盾 ——
> **列表本来就返回全部 18 个字段**，单条详情端点纯属冗余。已去掉：少一个端点、少一条权限行、少一份维护面。

- `resource` 与 api 侧路由一致，**不带 `/resource` 前缀族**（2026-10-08 起已去掉）。
- 角色关联：`builtin_role_admin` **显式绑定**（按 id 列表）—— 照既有做法把「这一行归谁」写死在迁移里，
  日后若权限闭包改成按行绑定，这里不会成为缺口。
- **tenant-admin / tenant-member 有意不给**：审计是平台管理面，无对应租户页面。
- ⚠️ **部署顺序：先重放本迁移，再起新构建。** `PermissionCrossCheckRunner` 是 fail-closed ——
  无权限行的新端点会让 platform-api **拒绝启动**。
- ⚠️ 重放后需等权限缓存 TTL（30s）或重新登录（token 里已无权限码，闭包在请求期现算）。

---

## 7. 前端

新页 `/audit`，归「平台管理」组，`meta.requiresPerm: [pageCodes.audit]`。

- **列表**：按 `occurred_at DESC, id DESC` 排序，**游标分页**（不用 offset，避免翻页时漏/重）。
  游标 = 上一页最后一条的 `(occurred_at, id)`，下一页条件为
  `occurred_at < :cursorTime OR (occurred_at = :cursorTime AND id < :cursorId)`。
  列：时间 · 操作者（账号 + 显示名）· 租户 · 集群 · 命名空间 · 动作码 · 目标对象 · 结果徽章。
  `DENIED` 行的「动作码/目标」列显示 `action`（权限码）+ `request_path` + `http_method`。
- **筛选**：时间范围 · 操作者 · 集群 · 命名空间 · 结果 · 动作码。均在单次查询条件里下推（配合 §4.1 的四个索引）。
- **详情**：行展开，看 `error_msg` / `request_path` / `user_agent` / `client_ip` / `duration_ms`。
- **删除**：`POST /audit/purge`，入参 `{ from, to }`（时间范围，闭开区间由实现定但要在响应里回显实际删除区间），
  二次确认写明**影响条数**；返回实际删除行数。`platform:audit:purge`。
- 权限门控与菜单显隐一律走 `pageCodes.ts`（**不得写字面量**，见 `frontend-permission-conventions.md`）。

---

## 8. 测试

**`AuditAspectTest`**
- 正常返回 → `SUCCESS`，字段齐全（动作码 / 目标 / 身份 / 耗时）
- 抛 `CloudPlatformException` → `FAILED`，带 `error_code` / `error_msg`
- **业务事务回滚时仍留一条 `FAILED`**（验证 `REQUIRES_NEW`）—— 最容易写错的一条
- **写库抛异常 → 业务照常返回**（fail-open 断言）
- SpEL 求值失败 → `target` 为 NULL，其余照记

**拒绝路径**
- 403 → 记 `DENIED` 且**带权限码**（验证 §2 的 deny 分支改动）
- **401 → 无记录**（断言）

**其余**
- 迁移重放后 platform-api 启动通过（`PermissionCrossCheckRunner` 交叉校验）
- 前端：列表筛选 + 游标分页 + 结果徽章渲染

**按用户要求不做**：反射断言注解覆盖率的护栏测试。

---

## 9. 有意不做 / 已知缺口（显式记录，勿当新问题上报）

| 项 | 原因 |
|---|---|
| 401 不记 | 噪声大（token 过期是常态），且无身份可记 |
| **不覆盖 `k8s-server` 层** | 用户裁定。**显式接受这个洞**：持已认证 token 直连 k8s-server 的操作无审计 —— 已知两处残留（`/persistentvolumes/**`、`/nodes/addresses`）。治本方案是「只接受 platform-api 调用」的内部凭证，属独立批次 |
| 不记变更前后值 | 需要额外抓对象快照，有并发竞态，且成本远高于收益 |
| 不记读操作 | 量级是写操作的几十倍，敏感面最广（目前无此需求） |
| 不存请求体全文 | 用户明确否决（改存「提交内容摘要」的选项也未采纳） |
| 不做租户可见视图 | 可见范围 = 只有平台管理员 |
| 不做覆盖护栏 | 用户明确不要。**代价显式接受：漏标不会被发现** |
| 不做分区 / 不自动清理 | 量级估算一年约 10 万条 |
| 不做「高危动作富化说明」列 | 本条从段 2 的表里**删掉了**（原预留 `detail`）—— 注解方案下它没有写入方，属 YAGNI。将来真需要补充说明时，`@Auditable` 加一个 `detail` SpEL 字段 + 一条 ALTER 即可，成本远低于现在留一个空列 |
| 不记 401、不给 `audit` 加租户维度、`resource_type` 不单存列 | 见 §4.2 与上表 |

---

## 10. 部署与回滚

**部署顺序**
1. 重放 `V2026_10_13_1__audit_log.sql`（建表 + 4 条权限行 + admin 角色关联）
2. 起新构建（platform-api；`spring-boot-starter-aop` 随之生效）
3. 等权限缓存 TTL（30s）或重新登录

**回滚**
- 审计是**旁路**：去掉所有 `@Auditable` 注解即回到改动前的行为，业务零影响。
- 表可保留（无外键、无其他表依赖它）。
- 权限行保留无害（没有可达端点时只是无用的行）。

**新增 `@Auditable` 的动作（后续批次）**
每加一个写操作审计点，只需在对应 Service 方法上加注解；**不需要动迁移、不需要动权限表**。
这是注解方案相对「权限表加列」方案的直接收益。
