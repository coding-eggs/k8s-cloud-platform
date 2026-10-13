# 审计日志设计（platform-api）

> 任务台账 **S0.1**。需求依据与裁定理据见 `docs/gap-analysis-2026-10-12.md`（§5.1 #5、§7.4.3）。

**目标**：让平台能回答「**谁、在什么时候、哪个集群/命名空间、改了什么、成功还是失败**」，
并覆盖「谁试图越权被拦」。当前状态是**零留痕** —— 库里没有任何审计表，代码里 `audit` 一词零命中。

**范围**：只做 `platform-api` 一层（它是平台唯一入口），不覆盖 `k8s-server`。

---

## 已定决策（brainstorming 结论，2026-10-13）

> ### ⚠️ 修订记录（2026-10-13，用户评审后）
>
> **拦截点从 Service 层上移到 Controller 层** —— 本文档相关段落已同步订正。
>
> **为什么改**（用户评审指出「审计应该在 controller 层」，核对后确认**用户是对的**）：
> - 审计的单元是「**一次用户请求**」，不是「一次内部方法调用」。Controller 端点与用户动作 1:1；
>   Service 方法可能被多个入口复用、或被内部编排调用 —— `TenantService.create` 内部会调
>   `TenantMemberService.add`，标在 Service 上会让一次「创建租户」**记两条**。
> - 与 403 记录（已在 HTTP 边界）**同层同粒度**，整条审计轨统一在「请求边界」。
> - 写端点集合就在权限表里，覆盖面是可枚举的；标在 Service 上这份清单根本不存在。
> - **顺带消掉了本设计最容易写错的一整块**：Controller 不带事务，切面记录时内层事务都已提交/回滚，
>   原先 §5.1 那套「`@Order` 必须排事务外侧」的必要性不复存在。
>
> **同时订正一条错误论据**：原文称「只有 Service 方法抛的 `CloudPlatformException` 才是真正的业务失败」——
> **不成立**。本仓 controller 里一个 `try` 都没有，异常原样穿过 Controller，切在这一层一样看得到业务失败。
>
> **动作覆盖一并补齐**：原先只标了 5 个高危动作，且「覆盖不全是本批有意为之」只写在 §10 里 ——
> 看代码像是漏了。现在把**已引入审计的四个 Controller 的写端点全部标上**，共 **23 处**：
> 工作负载 7 · 命名空间 7 · 节点 4 · 集群 5。**其余族未动**（不做全平台横扫，按用户要求也不维护写端点清单）。

| 决策点 | 结论 | 理由 |
|---|---|---|
| **审计范围** | **写操作 + 被拒请求（403）** | 不含变更前后值、不含读操作、不存请求体全文。被拒请求是安全审计最常查的东西 |
| **可见范围** | **只有平台管理员** | 一个页面、一套权限点；租户看不到。**表结构与查询接口不预留租户维度**（YAGNI，将来要开时再说） |
| **写操作判据** | **注解声明**（`@Auditable`），标在 **Controller 端点**上 | 用户明确否决「权限表加 `is_mutation` 列」。⚠️ 背景：本平台的查询也走 POST（`/pods/list`、`/cluster/list`），所以**按 HTTP method 判断写操作在此平台直接不成立** |
| **拦截机制** | **注解 + AOP** 管写操作（切 Controller）；**复用已有的 Security 钩子**管 403 | AOP 切方法调用，**被拒请求进不了方法**，所以拒绝那一半必须靠钩子 —— 好在 `ResourceServerConfig` 已有 `authenticationEntryPoint` / `accessDeniedHandler` 两个现成扩展点 |
| **写入契约** | **同步写 + fail-open** | 审计的可用性不应低于业务；写操作频率远低于读，多一跳 DB 可接受。DB 挂掉期间无记录，这是显式接受的代价 |
| **401** | **不记** | token 过期是常态，噪声大，且那时没有身份可记 |
| **覆盖护栏 / 写端点清单** | **都不做** | 用户明确不要「断言写端点覆盖率」这类测试，也明确不要枚举/维护一份写端点清单。代价显式接受：**漏标不会被发现** |
| **保留策略** | **不分区、不自动清理** | 量级估算一年约 10 万条，远不需要分区。只提供一个按时间范围删除的管理端点 |
| **事务** | 切面在 Controller 层，**天然不需要 `@Order`**；审计写仍保留 `REQUIRES_NEW` 作保险 | 见 §5.1 |

---

## 1. 拦截拓扑

两条独立路径，**共用一张表**，靠 `result` 区分（写操作 `SUCCESS` / `FAILED`，被拒 `DENIED`）。

```
写操作（切 Controller 端点）
  请求 ─► Security 链（通过）─► [AuditAspect  @Around @Auditable] ─► Controller ─► Service（各自事务）
                                        │                                   ▲
                                        │ 记录时机：内层事务已提交/回滚 ────┘
                                        ▼
                                 AuditService.record()  ← 同步写 + fail-open
                                        │
                                        ▼
                                 platform_audit_log

被拒请求（切 Security 钩子，与 403 是同一条路）
  请求 ─┬─ 401 ─► authenticationEntryPoint                              （不记）
        └─ 403 ─► accessDeniedHandler ─► AuditService.recordDenied() ─► platform_audit_log
```

---

## 2. 组件清单

全部在 `platform-api`，除 DTO/实体/Mapper 在 `platform-data`。

| 组件 | 路径 | 职责 |
|---|---|---|
| `@Auditable` | `platform-api/.../audit/Auditable.java` | 方法级注解，标在 **Controller 的写端点上** |
| `AuditAction` | `platform-api/.../audit/AuditAction.java` | 动作码常量（`<resource>:<verb>`，动词在前）。**只是词汇表，不承担覆盖率校验** |
| `AuditResult` | `platform-api/.../audit/AuditResult.java` | 枚举 `SUCCESS` / `FAILED` / `DENIED` |
| `AuditAspect` | `platform-api/.../audit/AuditAspect.java` | `@Around("@annotation(auditable)")`，**不需要 `@Order`**（见 §5.1） |
| `AuditWriter` | `platform-api/.../audit/AuditWriter.java` | **独立 bean**，只放 `@Transactional(REQUIRES_NEW)` 的写方法。见 §5.1：切 Controller 后 `REQUIRES_NEW` 已不是承重件，留作保险；拆成独立 bean 的另一半原因（fail-open 的 try/catch 必须在事务边界之外）仍然成立 |
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

用法（显式优先、隐式兜底）—— 都是 Controller 上的真实形态：

```java
// 零配置：路径参数就叫 name，clusterId/tenantId/namespace 在 DTO 一级字段里
@Auditable(action = AuditAction.WORKLOAD_DELETE)
public ResponseData<Void> delete(@PathVariable String name,
                                 @RequestParam String tenantId,
                                 @RequestParam String clusterId,
                                 @RequestParam String namespace) { ... }

// 显式 target：被操作对象是「命名空间」，参数里没有 name 可探
@Auditable(action = AuditAction.NAMESPACE_DELETE, target = "#request.namespace")
public ResponseData<Void> delete(@RequestBody NamespaceKeyRequest request) { ... }

// 显式 target + DTO 一级字段供 scope 探测（NodeDrainRequest 上有 clusterId）
@Auditable(action = AuditAction.NODE_DRAIN, target = "#req.name")
public ResponseData<NodeDrainResultDTO> drain(@RequestBody NodeDrainRequest req) { ... }
```
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
| `http_method` | VARCHAR(8) NULL | 请求方法 —— `DENIED` 与写操作都有；非 HTTP 线程为空 |
| `request_path` | VARCHAR(512) NULL | 请求路径 —— 同上；写操作记录下来是 `PUT /workloads/web-a` 这样，自描述 |
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

### 5.1 事务 —— 切 Controller 后已不是坑（原先最容易写错的一处）

本仓 `PlatformApiApplication` 有 `@EnableTransactionManagement`，且 Service 方法级 `@Transactional` 真在用
（`RoleService` / `TenantMemberService` / `PermissionRowWriter`）。

**当初标在 Service 层时**，切面与事务通知会在同一个方法上竞争，需要两道处理：`@Order` 把切面排到事务外侧 +
审计写走 `REQUIRES_NEW`。**那是最容易写错的一块**。

**切到 Controller 层后，这一整块复杂度消失**：Controller 不带 `@Transactional`，它调用的 Service 事务
都在方法内部完成 —— **切面记录时所有内层事务都已提交或回滚**，看到的天然是真实结果，
**不需要 `@Order`**。

**仍然保留 `REQUIRES_NEW`**（`AuditWriter`），但要说清它现在的地位：

- **不再是承重件** —— 记录时已无外层事务，普通传播级别同样能正确写入；
- 留作**保险**：若将来有人给某个 controller 加 `@Transactional`，审计记录仍不会随业务回滚被带走；
- `AuditWriter` 拆成独立 bean 的**另一半原因仍然成立**：fail-open 的 `try/catch` 必须在事务边界**之外**。
  若把 `write` 放回 `AuditService` 由同类方法自调用，代理被绕过（`PermissionRowWriter` 的类注释记着同一个坑）。
  当前是 `AuditAspect → AuditService.record()`（try/catch）`→ AuditWriter.write()`（REQUIRES_NEW），跨 bean，代理生效。

> **已知限制（两层都一样）**：审计记的是「这次请求的成败」，**不等于内部所有改动都回滚了** ——
> 复合操作里各 Service 方法有各自独立的事务，第 2 个失败时第 1 个可能已提交。
> 例如 `TenantService.create` 内部调 `TenantMemberService.add`，两者各自 `@Transactional`。

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
| 2 | `API` | `/audit/actions` | `POST` | `platform:audit:read` | 动作码候选值（`AuditAction` 全集 ∪ API 权限码）—— 供筛选 autocomplete，见 §7 |
| 3 | `API` | `/audit/purge` | `POST` | `platform:audit:purge` | 按时间范围删除 |
| 4 | `Page` | `/audit` | `view` | `platform:page:audit` | 前端页面可见性 |

> 第 2 行（`/audit/actions`）后来补入，见 `V2026_10_13_2`。**不造新码**，复用 `platform:audit:read`
> （同族只读、同一数据面）—— 与 `V2026_10_10_1` 复用 `platform:cluster:manage` 同做法。
> 它属于本仓既有的「**候选值端点**」一族：`/servicemonitors/relabel-labels`、`/podmonitors/metric-names`、
> `/calico/form-options` —— 都是纯候选值查询而非业务数据，独立成窄端点。

> **偏离记录（写 plan 时发现）**：本表原列了第 4 行 `/audit/get`，与 §7 的「详情 = 行展开」自相矛盾 ——
> **列表本来就返回全部 18 个字段**，单条详情端点纯属冗余。已去掉：少一个端点、少一条权限行、少一份维护面。

- `resource` 与 api 侧路由一致，**不带 `/resource` 前缀族**（2026-10-08 起已去掉）。
- 角色关联：`builtin_role_admin` **显式绑定**（按 id 列表）—— 照既有做法把「这一行归谁」写死在迁移里，
  日后若权限闭包改成按行绑定，这里不会成为缺口。
- **tenant-admin / tenant-member 有意不给**：审计是平台管理面，无对应租户页面。
- ⚠️ **部署顺序：先重放本迁移，再起新构建。** `PermissionCrossCheckRunner` 是 fail-closed ——
  无权限行的新端点会让 platform-api **拒绝启动**。
- ⚠️ **本仓没有引入 Flyway**（任何 pom 无依赖、无配置）——`db/migration/V*.sql` **不会由应用自动执行**，
  一直是由人**手工重放**的（既有 memory 里「先重放 `V2026_10_12_1` 再起构建」即此意）。
  所以启动日志里**不会**有 "Migrating schema"；别把它当作重放成功的判据，要直接查库确认。
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
  - **集群 / 命名空间是下拉**（原先是手输 id）：集群来自 `/cluster/options`；命名空间来自 `/namespace/list(clusterId)`，
    **未选集群时禁用**，换集群自动清空已选命名空间（否则留着一条永不命中的条件）。
  - **动作码是 autocomplete**，候选值取自 `/audit/actions`，**不在前端写死一份**（会与后端 `AuditAction` 漂移）。
    取一次后本地过滤；**候选拉不到时静默降级为自由文本**（不弹错、不阻塞手输，同 Docker Hub tag 候选的处理原则）。
    - **候选 = 「可能值全集」，不是审计表里的观测值**：`AuditAction.all()`（反射读常量，永远与常量同步）
      ∪ API 域权限码（DENIED 记录的 `action` 存的是权限码；与 `PermissionRegistryFactory` 同一读法）。
      ⚠️ **这条是实施后按用户反馈改的**：最初取的是「审计表里实际出现过的值」，后果是**新装 / 刚上线时
      下拉几乎是空的**（表里只有一两条记录 → 候选只剩一两个），筛选根本用不了。
      「可能值」与数据量无关、开箱即用；代价是下拉会包含**从未被使用过**的码，选中后查不到数据 —— 显式接受。
      顺序：动作码在前、权限码在后，各组内部字母序（空查询时顺序可预期）。
    - **过滤留在前端（不下推）**：`action` 列**没有索引**（只有 occurred_at / actor / cluster_ns / result），
      `where action like '%q%' group by action` 会退化成**每次按键一次全表扫描 + 分组**（表按年级别增长）；
      而候选上界 ≈ 动作码常量（23）+ API 权限**行**数（262）≈ 285，2026-10-13 **实测 137**
      （262 是行数、多行共享同码，去重后为 114 个权限码）——
      几百条短字符串的内存 `includes` 是微秒级。**下推不是优化，是劣化。**
    - **上限 2000 + 截断留痕**：合并后超过上限则 `WARN` 并截断 ——
      不静默返回一份「看起来完整却不完整」的候选（同 `backend-layering.md` 的「绝不静默降级」原则）。
      上界推导与「何时该重新评估」写在 `AuditService.MAX_ACTION_OPTIONS` 的注释里。
- **租户 / 集群列显示名字**：表里存的是 id（§4.2 自包含原则），名字由一次 lookups 拉取
  （`/tenant/list` + `/cluster/options`）建 `id → 名字` 映射 —— **同一份数据同时喂下拉选项与列渲染**。
  映射未命中（对象已删除、或无读权限）**回退显示原始 id**，单元格 `title` 始终挂着 id。
  ⚠️ 取舍：这样显示的是**现在的名字**，不是**记录发生时的名字**。要"当时那个名字"得像 `actor_display_name`
  那样在**写入时冗余存名字**（后端 +2 列 + 写时解析）—— 未做，属可选升级。
- ⚠️ **清空筛选项给的是空串**：`el-input` / `el-select` 的 `clearable` 清空后 `v-model` 是 `''` 而非 `null`，
  而后端条件是 `if (xxx != null)` —— 空串不为 null，会变成 `and cluster_id = ''` **静默返回 0 行**。
  故前端发请求前统一归一化（空串 / null / undefined 一律不发）。
- **详情**：行展开，看 `error_msg` / `request_path` / `user_agent` / `client_ip` / `duration_ms`。
- **删除**：`POST /audit/purge`，入参 `{ from, to }`（时间范围，闭开区间由实现定但要在响应里回显实际删除区间），
  二次确认写明**影响条数**；返回实际删除行数。`platform:audit:purge`。
- 权限门控与菜单显隐一律走 `pageCodes.ts`（**不得写字面量**，见 `frontend-permission-conventions.md`）。

---

## 8. 测试

**`AuditAspectTest`**（切面切 Controller，假 join point 按 controller 的真实签名造 —— 路径参数 + DTO 混合）

- 正常返回 → `SUCCESS`，字段齐全（动作码 / 目标 / 身份 / 耗时）
- 抛 `CloudPlatformException` → `FAILED`，带 `error_code` / `error_msg`；异常**原样抛回**
- **写记录带请求上下文**（`requestPath` / `clientIp` / `userAgent`）—— 切 Controller 层才有的收益
- **写库抛异常 → 业务照常返回**（fail-open 断言）
- SpEL 求值失败 → `target` 为 NULL，其余照记
- 身份与上下文全取不到时仍落一条（非 HTTP 线程）

**`AuditServiceTest`**

- fail-open（`record` / `recordDenied` 都不抛，后者尤其：一抛就把 403 变成 500）
- `recordDenied` 的字段来源（权限码 / 身份 / `X-Forwarded-For` 首段）；无权限码仍记录
- limit 钳制（默认 50 / 上限 200）
- **`writerUsesRequiresNewIndependentTransaction`** —— 一条**反射钉子**。`REQUIRES_NEW` 的行为
  用单测隔离不出来（切面在事务外层时业务事务已回滚完，普通 REQUIRED 同样能写进去），故钉住注解本身。
  它**能**挡住「有人删掉注解或改小传播级别」，**挡不住**「有人把 write 挪进自调用位置」。
  切 Controller 后它已不是承重件（见 §5.1），留着是防将来有人给 controller 加 `@Transactional`。

**拒绝路径**（`PermissionAuthorizationManagerTest`）

- deny 时把被判的权限码写进 request attribute；allow 与匿名都不写
- **401 → 无记录**：由「不改 `authenticationEntryPoint`」保证，**没有代码路径**（不是靠测试拦）

**启动期**

- 迁移重放后 platform-api 启动通过（`PermissionCrossCheckRunner` 交叉校验；本批端点总数 +2）

**端到端（临时探针，验完即删，不入库）**

- 切 Controller 后最需要证明的是「**切点仍然命中**」—— Spring MVC 的 handler 映射 + CGLIB 代理之下
  `@Around("@annotation(auditable)")` 是否照常生效。单测直接 `new AuditAspect(...)` 证明不了这一点。
- 已用一次临时 `@SpringBootTest` + `WebApplicationContext` 起的探针跑通：
  带 `@Auditable` 的 controller 端点正常返回 → 表里 1 行 `SUCCESS`（`target` 由 `@RequestParam` 的 SpEL 解出、
  `requestPath` 正确、自增 id 回填）；抛 `CloudPlatformException` → 1 行 `FAILED`，带业务码与 msg。
- 探针两个坑（下次别重踩）：
  1. **嵌套 `@RestController` 会被组件扫描直接注册**，不能再 `@Bean` 注册一次 —— 否则同一路径映射两次，
     报 `Ambiguous mapping`；
  2. 路径要落在 `ExemptPaths` 的豁免前缀下（如 `/actuator/**`），否则 `PermissionCrossCheckRunner` 拒启。
     注意 `spring.security.ignore-urls`（Security 的 permitAll）**不等于** `ExemptPaths`（交叉校验豁免），
     两者是**不同**的列表 —— 放 `/test/**` 只在前者里，交叉校验照样拒。

**按用户要求不做**

- 反射断言写端点覆盖率的护栏测试；
- 枚举 / 维护一份写端点清单 —— 覆盖靠人工加注解，**漏标不会被发现**（代价显式接受）。

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
| 不枚举 / 不维护写端点清单 | 用户明确不要（「不要枚举接口」）。覆盖靠人工在 Controller 上加注解；本批已标的 23 处是**手工过了一遍那四个 controller 的写端点**，不是一份被维护的清单，也不是全平台横扫 |
| 不做分区 / 不自动清理 | 量级估算一年约 10 万条 |
| 不做「高危动作富化说明」列 | 本条从段 2 的表里**删掉了**（原预留 `detail`）—— 注解方案下它没有写入方，属 YAGNI。将来真需要补充说明时，`@Auditable` 加一个 `detail` SpEL 字段 + 一条 ALTER 即可，成本远低于现在留一个空列 |
| 不记 401、不给 `audit` 加租户维度、`resource_type` 不单存列 | 见 §4.2 与上表 |

---

## 10. 部署与回滚

**部署顺序**
1. 重放 `V2026_10_13_1__audit_log.sql`（建表 + 3 条权限行 + admin 角色关联）—— **手工执行，本仓无 Flyway**
2. 起新构建（platform-api；`spring-boot-starter-aop` 随之生效）
3. 等权限缓存 TTL（30s）或重新登录

**回滚**
- 审计是**旁路**：去掉所有 `@Auditable` 注解即回到改动前的行为，业务零影响。
- 表可保留（无外键、无其他表依赖它）。
- 权限行保留无害（没有可达端点时只是无用的行）。

**新增 `@Auditable` 的动作（后续批次）**
每加一个写操作审计点，只需在对应 Service 方法上加注解；**不需要动迁移、不需要动权限表**。
这是注解方案相对「权限表加列」方案的直接收益。
