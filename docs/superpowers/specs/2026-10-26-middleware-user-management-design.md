# 中间件用户管理（改口令 / 增删用户）· 设计（2026-10-26）

> 上位设计：[2026-10-22-middleware-store-design.md](2026-10-22-middleware-store-design.md)（固定清单 + Operator + 实例台账）。
> 既有实现：Milvus 的「开启鉴权 + root 改口令」见 [2026-10-25-milvus-middleware-design.md](2026-10-25-milvus-middleware-design.md)
> 与 `k8s-server/src/main/resources/charts/middleware/milvus/README.md`（§4 坑 7/11）。
> 本文件把「账号」从 Milvus 单款推广到全部中间件，**只做最小集合：改内置口令、增用户、删用户**。

---

## 0. 一句话

每个中间件实例的详情页加一个「用户」页签：能改**内置账号**的口令、能**新增/删除**普通用户并只给**两三个预设角色**；
口令一律落 Kubernetes Secret、页面不回显明文。Kafka（PLAINTEXT 无认证）与 Nacos（chart 无鉴权）不做，写清原因。

---

## 1. 决策（已与用户确认）

| # | 决策 | 取值 |
|---|---|---|
| 1 | 范围 | 「可以做的都做」，不做细粒度权限编辑器；每家只要 2~3 个预设角色 |
| 2 | 租户作用域 | **走顶栏**（资源上下文 store），与中间件其余端点一致；实例归属校验不变 |
| 3 | 口令来源 | 表单填（8~72 位）；留空 = 平台生成（与创建实例的随机口令同源） |
| 4 | 口令存储 | 统一写 Secret `<实例名>-users`（key = 用户名）；内置账号仍写各家**自己**的 Secret（见 §4） |
| 5 | Kafka / Nacos | **不做**：Kafka 监听器是 PLAINTEXT（没有认证就没有"用户"）、Nacos chart 默认关鉴权；都要先动认证方案，另开 |

---

## 2. 研究基线（2026-10-26 真集群核对）

| 中间件 | 内置账号与 Secret | 改口令机制 | 增删用户机制 | 本批做什么 |
|---|---|---|---|---|
| `mysql`（Percona PS） | `root`；Secret `<名称>-secrets`（key `root`） | SQL `ALTER USER` | SQL `CREATE USER` / `DROP USER` + `GRANT` | 两项都做（Pod exec） |
| `postgresql`（CNPG 1.30） | 应用账号；Secret `<名称>-app`（`username`/`password`） | 改 `<名称>-app` Secret，CNPG 会同步到数据库 | **`databaseroles.postgresql.cnpg.io` CRD**（`login` / `passwordSecret` / `inRoles` / `ensure`） | 两项都做（纯声明式） |
| `redis`（opstree） | Secret `<名称>-credentials`（key `password`） | 改 Secret + 滚动重启 | opstree 不管 ACL 用户 | **只做改口令** |
| `rabbitmq` | `user`；Secret `<名称>-default-user`（`username`/`password`） | Management API `PUT /api/users/<u>` | 同 API `PUT /api/permissions/%2F/<u>` / `DELETE` | 两项都做（HTTP 15672） |
| `elasticsearch`（ECK 3.5） | `elastic`；Secret `<名称>-es-elastic-user`（key `elastic`） | `POST /_security/user/elastic/_password` | `POST/DELETE /_security/user/<u>`（内置角色 viewer/editor/superuser） | 两项都做（HTTPS + CA） |
| `milvus` | `root`；Secret `<名称>-milvus-auth`（`username`/`password`） | **已实现** `update_password` | `users/create` + `users/grant_role`（只有内置 `admin`）/ `users/drop` | 改口令已有；本批补增删，**角色只给 admin** |
| `kafka`（Strimzi） | 无（PLAINTEXT 9092） | — | — | 不做（见 §1-5） |
| `nacos`（chart） | 无（默认无鉴权） | — | — | 不做 |

核对依据（2026-10-26）：
- CRD：`databaseroles.postgresql.cnpg.io` 存在，`spec` 含 `login` / `passwordSecret` / `inRoles` / `ensure`；
  `perconaservermysqls.ps.percona.com` 的 `spec` 只有 `secretsName` / `sslSecretName`，**没有**声明式用户字段 → MySQL 只能走 SQL；
- ES 实例 `spec.http.tls.certificate` 已配置（HTTPS），CA 在 `<名称>-es-http-ca-internal`；
- Milvus 的 `update_password` 路由与用法已在 `MilvusPasswordClient` 里验证过（`/v2/vectordb/users/update_password`）。

---

## 3. 统一模型

### 3.1 页面

实例详情页新增「用户」页签（只对 §2 里"做"的中间件显示）：

```
用户名        角色            类型      操作
root          管理员          内置      改口令
app           读写            内置      改口令
alice         只读            平台创建   改口令 | 删除
                                          [新增用户]
```

- **内置账号**：不可删除，只能改口令；
- **平台建的账号**：可改口令、可删除（删的是数据库/RabbitMQ/ES/Milvus 里的账号 + `<名称>-users` 里那条 key）；
- 列表**不显示明文口令**，只给"在 Secret `<名称>-users` 的 `<用户名>` 键"。

### 3.2 角色（简化，不做细粒度）

| 中间件 | 预设角色 → 实际授权 |
|---|---|
| mysql | `只读` → `SELECT ON *.*`；`读写` → `ALL PRIVILEGES ON *.*` |
| postgresql | `只读` → `inRoles: [pg_read_all_data]`；`读写` → `[pg_read_all_data, pg_write_all_data]` |
| rabbitmq | `只读` → 权限 `configure="" / write="" / read=".*"`；`读写` → 三项全 `.*`；`管理员` → 再加 `administrator` tag |
| elasticsearch | `只读` → 内置角色 `viewer`；`读写` → `editor`；`管理员` → `superuser` |
| milvus | 只有 `管理员` → 内置角色 `admin`（实测该实例 `roles/list` 只有 `admin` / `public`，没有 `db_ro`/`db_rw`；做只读/读写要先自建角色并授一组 privilege，本批不做） |

> 只读/读写都作用于**整个实例**（MySQL 是 `*.*`、PG 是集群级内置角色、Milvus 是 DB 级内置角色），
> 不按库/表细分 —— 这是"不用太复杂"的取舍，写在 UI 帮助文案里。

### 3.3 口令

- 表单可填（8~72 位，Milvus 上限 72，全平台统一按 72 卡）；留空 = 平台生成（18 字节随机 → URL-safe Base64）；
- 平台生成或用户填的，**都写一份**到 Secret `<实例名>-users`（key = 用户名，value = 口令）；
  这样"这个实例有哪些平台建的账号、口令是什么"只在一个地方，应用编排要注入也读它；
- 内置账号口令**额外**写回各家自己的 Secret（§4 表），因为那是它们的"事实来源"；
- 页面与接口都不回显明文（与 Milvus 现有口径一致）；
- **"平台建的账号"怎么认**：Secret `<实例名>-users` 里有 key 的就是（PG 例外：认 `DatabaseRole` CR）。
  没有 key 的账号（别人手工建的）列表里不显示 —— 平台不接管、也不删它。

---

## 4. 执行通道（关键设计）

**统一原则**：能用声明式/HTTP 就不用 exec；**不引入数据库驱动**（不给 k8s-server 加 mysql/pg JDBC 依赖）。

| 中间件 | 通道 | 说明 |
|---|---|---|
| postgresql | Kubernetes API（`DatabaseRole` + Secret） | 全声明式，不连数据库 |
| elasticsearch / rabbitmq / milvus | HTTP(S) | 集群内 Service，`http://<实例名>-…`；ES 用 CA Secret 建信任链 |
| mysql | **Pod exec**（fabric8 `pod.exec("sh","-c",…)`） | 口令用环境变量传（`MYSQL_PWD`），**不上命令行**（避免 ps 可见） |
| redis | Kubernetes API（Secret + 滚动重启） | 不 exec、不走 RESP 客户端 |

- exec 能力已验证存在：`PodExecWebSocketHandler` 用同一套 fabric8 client 做 `pod.exec(...)`；
- **执行身份**：统一用 **admin client**（租户 SA 的 RBAC 模板里没有 `pods/exec`），
  授权仍在平台层 —— 端点先 `requireInstance(instanceId, 顶栏租户)`，再过分配表边界；
- **网络前提**：HTTP/exec 都要求 k8s-server 能到目标集群（主集群满足；跨集群托管不通时以明确错误暴露，
  与 `SeaweedS3Service`、Milvus 改口令同一前提）。

---

## 5. 契约

### 5.1 platform-common（DTO）

```java
// 列表项
record MiddlewareUserView(String username, String role, boolean builtin, String secretName, String secretKey)
// 新增
class MiddlewareUserCreateRequestDTO { String instanceId, clusterId, namespace, name, username, password, role, tenantId; }
// 改口令 / 删除
class MiddlewareUserPasswordRequestDTO { String instanceId, clusterId, namespace, name, username, newPassword, tenantId; }
class MiddlewareUserDeleteRequestDTO   { String instanceId, clusterId, namespace, name, username, tenantId; }
```

### 5.2 platform-api

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/middleware/instance/users` | 列表（只读，`{instanceId, tenantId}`） |
| POST | `/middleware/instance/users/create` | 新增（`@Auditable(middleware:user:create)`） |
| POST | `/middleware/instance/users/password` | 改口令（`middleware:user:password`） |
| POST | `/middleware/instance/users/delete` | 删除（`middleware:user:delete`） |

- 四个端点都先 `requireInstance(instanceId, effectiveTenantId(顶栏 tenantId))`；
- 新口令留空 → `randomPassword()`；长度 8~72 校验；**口令不进操作记录、不进日志**；
- `instance.code` 不在支持清单 → 明确报错「该中间件暂不支持用户管理」。

### 5.3 k8s-server

```java
/** 一套操作按 code 分派（与 MiddlewareAdapter 同形，不抽公共基类） */
public interface MiddlewareUserAdapter {
    String code();
    List<MiddlewareUserView> list(KubernetesClient client, MiddlewareUserRef ref);
    void create(KubernetesClient client, MiddlewareUserRef ref, String username, String password, String role);
    void drop(KubernetesClient client, MiddlewareUserRef ref, String username);
    void changePassword(KubernetesClient client, MiddlewareUserRef ref, String username, String newPassword);
    List<String> roles();
}
```
`MiddlewareUserRef` = `{instanceName, namespace, instanceId}`（服务名/Secret 名都从实例名推导，口径与适配器一致）。

端点：`POST /middleware/{code}/instances/users`、`…/users/create|password|delete`（`AccessBoundary.TENANT`）。

---

## 6. 各家的落地细节

### 6.1 MySQL

- 列表：`SELECT user, host FROM mysql.user WHERE user NOT IN ('mysql.sys','mysql.session','mysql.infoschema')`；
  平台建的账号记为 `host='%'`；
- 内置 `root` 改口令：`ALTER USER 'root'@'%' IDENTIFIED BY ?` → 成功后写回 `<名称>-secrets`；
- 新增：`CREATE USER '<u>'@'%' IDENTIFIED BY ?` + `GRANT … ON *.* TO '<u>'@'%'`；
- 删除：`DROP USER '<u>'@'%'`；
- 连接：`exec mysql -uroot -e "<sql>"`，口令走 `MYSQL_PWD` 环境变量；Pod 选 `<名称>-mysql-0`。

### 6.2 PostgreSQL（纯声明式）

- 列表：`DatabaseRole` CR（`spec.cluster` = 本实例）+ 内置应用账号（`<名称>-app`）；
- 新增：建 Secret（`kubernetes.io/basic-auth`，`username`/`password`）+ `DatabaseRole{name, login:true,
  passwordSecret, inRoles:[…], ensure: present}`；
- 删除：`DatabaseRole.ensure = absent`（CR 保留，便于回滚），同时删每用户 Secret；
- 改口令：改 Secret 的 `password`（CNPG 会 reconcile 到数据库）；内置应用账号改 `<名称>-app`。

> ⚠️ CNPG 的 `-app` Secret 是 operator 生成/托管的：改它之前先确认 operator 不会把值改回去
> （实施第一步在真集群验证：改完看 `pg_roles` 的 `rolpassword` 是否变、Secret 是否被回写）。

### 6.3 Redis（只改口令）

- 改 `<名称>-credentials` 的 `password` → `rollout restart` StatefulSet；
- 列表只有 `default` 一个内置账号；
- 若实测 operator 会把 Secret 改回旧值 → 该能力降级为"不支持"，在 UI 里不显示。

### 6.4 RabbitMQ

- 先读 `<名称>-default-user` 拿管理凭据（`user`/`password`）；
- 列表 `GET /api/users`（过滤内部账号）；新增 `PUT /api/users/<u>`（`{password, tags}`）+
  `PUT /api/permissions/%2F/<u>`；删除 `DELETE /api/users/<u>`；改口令 = 再 `PUT` 一次；
- vhost 固定 `/`（平台不建别的 vhost）。

### 6.5 Elasticsearch

- 先读 `<名称>-es-elastic-user` 的 `elastic` 作超管凭据；
- 列表 `GET /_security/user`（只列 `platform-` 前缀 + `elastic`）；
- 新增 `POST /_security/user/<u>`（`{password, roles:[内置角色]}`）；删除 `DELETE /_security/user/<u>`；
- 改口令 `POST /_security/user/<u>/_password` → 内置 `elastic` 时同时写回 Secret；
- TLS：从 `<名称>-es-http-ca-internal` 取 CA（`ca.crt`）建 SSLContext；实例 `tls=false` 时走 http。

### 6.6 Milvus（补增删）

- 列表 `POST /v2/vectordb/users/list`；新增先 `users/create` 再 `roles/grant_role`（`roleName`, `userName`）；
- 删除 `users/drop`；
- 只列出 `root` + 平台建的（`<名称>-users` 里有 key 的），避免暴露别人建的账号；
- ⚠️ 角色选项**运行时从实例读**（`POST /v2/vectordb/roles/list`），实测只有 `admin` / `public`：
  `public` 默认无任何权限，所以下拉里只出现 `admin`；将来实例里出现别的可用角色会自动出现，不硬编码。
- 只读/读写对 Milvus 意味着"建平台预设角色 + 授一组 privilege"，属于细粒度授权，**不在本批**（§12）。

---

## 7. 时序（以新增用户为例）

```
前端（详情页 · 用户页签）
  → platform-api POST /middleware/instance/users/create {instanceId, username, password?|null, role}
      1. requireInstance(instanceId, 顶栏租户)
      2. code 支持校验；口令留空则 randomPassword()
      3. client.createUser(code, {clusterId, ns, instanceName, username, password, role})
  → k8s-server admin client
      4. adapter.create(...)：调 SQL/CR/HTTP 建账号
      5. 成功 → 写 Secret <实例名>-users 的 <username> 键（失败不回滚账号，但返回明确错误让人工核对）
  ← 返回 true；前端刷新列表（不回显口令，提示"口令在 Secret <名称>-users 的 <用户名> 键"）
```

改口令同理：**先改事实来源（SQL/API/数据库），成功后再写 Secret**；顺序反了会出现"平台上写着新口令、数据库还是旧的"。

---

## 8. 权限与审计

- 权限点复用现有中间件写权限（`tenant:middleware:*`），**不新增权限码**（与"改显示名/扩容"同级）；
- 审计：`middleware:user:create` / `middleware:user:password` / `middleware:user:delete`，
  `target` = `instanceId`；**请求体里的口令不写进审计**（`@Auditable` 只记 target/摘要）；
- 口令不进日志、不进操作记录的 values 快照。

---

## 9. 前端

- 实例详情页新增 `用户` tab（`el-tabs` 现有结构里加一项），仅当 `code` 在支持清单时显示；
- 表格列：用户名 / 角色 / 类型（内置 · 平台创建）/ 操作（改口令、删除——内置账号无删除）；
- 「新增用户」弹窗：用户名、角色（下拉，来自后端 `roles`）、口令（可留空，占位提示"留空由平台生成"）；
- 改口令弹窗：新口令（可留空）；
- 提示文案统一：「口令不会回显，存在 Secret `<实例名>-users` 的 `<用户名>` 键」。

---

## 10. 测试

**单元（每个适配器一组）**
- SQL / REST 载荷拼装是纯函数：用户名角色→SQL 语句、角色→RabbitMQ permissions、角色→ES roles、角色→Milvus 内置角色；
- HTTP 适配器用本地 `HttpServer` 断言路径与 body（照 `MilvusPasswordClientTest` 的做法）；
- 拒绝非法用户名（`[a-z0-9_]{3,32}`）、非法角色、口令长度越界；
- 口令从不出现在异常消息里。

**服务层**
- 只允许支持的 code；未支持的 code 报错；
- 留空口令 → 生成且长度合规；给定口令原样透传（trim）；
- `requireInstance` 归属校验（租户 A 不能操作租户 B 的实例）。

**真集群（清单写进 plan）**
- 每家各建一个实例 → 建用户 → 用客户端连一次 → 改口令 → 用新口令连 → 删用户 → 连不上；
- Redis 的"改 Secret 是否被 operator 回写"、CNPG 的"改 `-app` 是否 reconcile"这两条是**必须先验**的前提，
  验不过就把该家的能力降级（UI 不显示）。

---

## 11. 风险与取舍

| 风险 | 处理 |
|---|---|
| 跨集群网络不通（HTTP/exec） | 与对象存储同一前提；错误信息带目标地址，不静默 |
| ES 自签证书 | 用 ECK 的 CA Secret 建信任链，不全局 trust-all |
| 改库成功但写 Secret 失败 | 不回滚账号，返回明确错误（"口令已改，Secret 未更新：…"），人工核对 |
| 租户 SA 没有 `pods/exec` | 统一用 admin client；授权在平台层（归属 + 分配表） |
| 口令泄露面 | 不进日志/审计/台账；只存在 Secret；Redis/MySQL 走环境变量不上命令行 |
| 内置账号被改坏（比如改掉 RabbitMQ 默认用户） | 只允许改**已知用户名**（各家白名单），不允许平台建同名账号 |

---

## 12. 不在本批

- 细粒度权限编辑（按库/表/collection 授权）、角色自定义；
- Kafka 用户管理（需先把监听器换成 SASL_SSL + 引入 `KafkaUser`，是独立一批）；
- Nacos 鉴权（chart 默认关闭，开了才有用户）；
- 用户操作的历史台账（只记审计日志，不建表）。

---

## 13. 部署步骤（本批）

1. 后端：`k8s-server`（新适配器 + exec 工具）、`platform-api`（服务/控制器/审计）、`platform-common`（DTO）；
2. 前端：`platform-web`（详情页用户页签 + 弹窗）；
3. **无 SQL 迁移**（用户口令存 Secret，不落库）；
4. 真集群按 §10 的清单逐家验；不通过的降级为"不支持"。
