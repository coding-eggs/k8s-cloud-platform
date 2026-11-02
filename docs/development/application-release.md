# 应用发布

> 设计：`docs/superpowers/specs/2026-11-01-application-release-redesign-design.md`
> 本文取代旧的「应用编排（画布）」文档：画布、整份 spec、预检/部署/操作记录/修订历史已于 Task 13 删除。

## 1. 模型

**应用 = 一组服务 + 一组依赖 + 一串不可变的发布。** 没有画布，没有整份 spec。

| 概念 | 存在哪 | 说明 |
|---|---|---|
| 应用 | `platform_application` | 只登记集群、命名空间、名称（slug）；`spec_json` 新应用恒为 null |
| 服务 | `platform_app_service` | 每个服务一行 `desired`（镜像、端口、副本、环境变量、配置文件……），带乐观锁 `version` |
| 依赖 | `platform_app_dependency` | 中间件（应用创建 / 绑定已有）或外部连接；**中间件不进发布**，增删即生效 |
| 发布 | `platform_app_release` | 一次发布的不可变快照：勾选服务的 desired + 解析结果；回滚 = 用旧快照再发一次 |
| 执行 | `platform_app_execution` | 发布/回滚的阶段机，`PENDING → … → DONE`；platform-api 重启后进入「已中断」，可继续 |
| 对象台账 | `platform_application_resource` | 平台建过的 K8s 对象；prune 差集、冲突判定、删除应用时清理 |

**待发布改动代替草稿**：服务页保存只改 `desired`；应用页列出「与线上不同」的服务，勾选后发布。
未勾选的服务不会带着自己的待发布改动上线，但仍会从线上快照重新渲染；
若它引用的服务端口或依赖的连接信息变了，其 configHash 随之变化，也会滚动重启。

### 1.1 变量引用代替连线

环境变量和配置文件里写 `${名称.字段}`，名称是服务 key 或依赖 key，在 platform-api 渲染前解析
（`AppEnvResolver`）。

- 环境变量可引用全部字段；凭据字段（`username` / `password`）渲染成 `secretKeyRef`，明文不进快照。
- **配置文件只能引用 `host` / `port` / `address` / `url`**：文件内容会原样进 ConfigMap，凭据请走环境变量。
  保存时校验，渲染时再拦一次。
- 引用了不存在的 key 会在发布预览里报出来，不能发布。

### 1.2 拓扑

应用页的「拓扑」页签是只读图：节点 = 服务 + 依赖，边由服务的环境变量与配置文件引用生成
（使用方 → 被使用的一方）。不存储、不可拖拽、不可连线。点服务进服务页，点依赖打开依赖抽屉。

## 2. 配置文件

服务页「配置文件」页签：每条一个绝对路径 + 内容。

| 规则 | 值 |
|---|---|
| 路径 | 绝对路径，不以 `/` 结尾，每段只含 `[A-Za-z0-9._@+=,-]`，不能有 `.` / `..` / 空段，≤255 字符 |
| 冲突 | 路径不能重复、不能互相嵌套；不能落在持久化数据卷（默认 `/data`）之内 |
| 数量 / 大小 | 每服务至多 20 个，内容合计至多 512KiB |
| 引用 | 只允许 `host` / `port` / `address` / `url` |

渲染：一个服务一个 ConfigMap `<slug>-<key>-files`（键 `file-0..N`），每个文件 `subPath` **只读**挂到
它的路径，不覆盖目录里的其他文件。`subPath` 挂载**不会跟随 ConfigMap 更新**，所以文件内容计入
Pod 模板注解 `platform.coding/config-hash`：改文件 → 哈希变 → 发布时滚动。没有配置文件的服务哈希不变
（老服务升级后不会因此重启）。`-files` 与 `-env` / `-secret` / `-headless` 一样是保留后缀，服务和依赖
key 不能以它结尾。

## 3. 上线

按顺序执行，**迁移与代码必须同批**。

1. **上线前在平台库查旧应用，并用线上现有构建删掉部署过的**（设计 §11：不迁移旧应用）：
   ```sql
   SELECT id, name, namespace, status, current_revision,
          EXISTS (SELECT 1 FROM platform_application_resource r WHERE r.app_id = a.id) AS has_objects
     FROM platform_application a
    WHERE deleted_at IS NULL AND spec_json IS NOT NULL AND spec_json <> '';
   ```
   `current_revision > 0` 或 `has_objects = 1` 的是**部署过的旧画布应用**，必须在升级前用线上现有构建的页面删除
   （会 prune 它建过的对象，PVC 与中间件实例保留）。新构建已没有「旧模型只读」守卫，这类应用留到升级后会被当成
   新应用放行发布，它在资源台账里的旧对象从此无人认领。其余（从未部署过的）由清理迁移转成新模型的空应用，无需处理。
   提前和使用方确认要删的应用。
2. **手工重放迁移**（本仓没有 Flyway，启动日志不会有 Migrating schema），按文件名顺序：
   `V2026_11_02_10__application_release` → `V2026_11_02_11__application_release_permissions` →
   `V2026_11_02_12__application_release_rollback_permission` → `V2026_11_02_13__application_dependency_permissions` →
   `V2026_11_02_14__drop_application_canvas_legacy`。最后这条是画布遗留清理（§3.1），**第 0 步报
   「存在已部署的旧画布应用…」表不存在就说明第 1 步没做干净**：回到第 1 步删掉后再重放本条（它会跳过所有结构改动，
   可重复执行）。
   main 上的 `V2026_11_01_1__object_storage_platform_page`、`V2026_11_01_2` 与本批无关，照各自说明重放。
3. **上线前在真 MySQL 上各跑一次两条条件更新**（单测只覆盖了 mock）：执行表 mapper 的 `reopen`
   （继续已中断的执行）和 `supersedeInterrupted`（新发布取代已中断的执行）。用一条手工造的
   `INTERRUPTED` 执行行跑一遍，确认语法与影响行数后删掉这行。**不要留到上线后**：这两条 SQL 平时不走，
   写错了不会在启动或普通发布时暴露，只会在第一次「继续已中断的执行」或「新发布取代已中断的执行」时
   才报错 —— 那正是用户最需要它们的时候。
4. **重建** k8s-server、platform-api、platform-web（platform-data / platform-common 作为依赖一起构建）。
   **k8s-server 必须先于 platform-api 部署，且升级窗口内不要发布**：新 platform-api 对旧 k8s-server 会写出
   未展开的 `$(_REF_n)` 值、丢掉 configFiles，并沿用旧的就绪判定。另外先让仍在进行的旧画布部署操作跑完再升级，
   它们的 worker 随升级一并删除，不会再被续跑。
5. **platform-api 只能单副本**：执行器的进度、依赖初始化的恢复、「重启后进入已中断」都假设只有一个实例；
   多副本会出现两个实例同时推进同一个执行。
6. **「重下发全部模板」**：私有镜像拉取依赖命名空间 default SA 已挂 `platform-registry-pull`
   （命名空间分配时下发；渲染时不再注入）。没挂的命名空间发布会卡在拉镜像。
7. **重新登录**：权限行有新增，旧会话拿不到新码。

### 3.1 画布遗留清理（V2026_11_02_14）

画布模型的遗留在代码和库里都已删除：

- 库：`platform_application.spec_json` / `current_revision` 两列，`platform_application_revision` /
  `platform_application_operation` 两张表，旧接口（`/application/update`、`/application/preview`、
  `/application/deploy`、`/application/operation/get`、`/application/revision/list`）的 5 条权限行及其角色绑定。
- 代码：对应实体与 mapper、`AppGuard` 的「旧模型应用只读」守卫、`ApplicationView` / 概览的 `legacy` 字段、
  前端的「旧模型」标签与提示条。
- **保留**：`platform_application_resource`（新发布也记账）、`platform_application_reference` 与
  `platform_middleware_instance.app_id` / `app_node_id`（新依赖也用）。

迁移对从未部署过的旧应用：状态改回 `DRAFT`、画布建的中间件实例解除归属（回到中间件商店）、画布引用行删除。
部署过的旧应用**不能**交给迁移处理，见 §3 第 1 步。

## 4. 旧模型应用

上线后不再有「旧模型应用」：部署过的已在升级前删除，没部署过的成了新模型的空应用，照常添加服务、发布。

## 5. 验收清单（未验证，部署后由使用者在真集群执行）

设计 §12 的验收在分支上**没有跑过**（未部署、实现期不连集群），上线后逐条执行：

- [ ] 重复发布不新建中间件实例，也不重跑初始化脚本。
- [ ] 只改环境变量会滚动 Pod，未勾选的服务不重启。
- [ ] 只改配置文件会滚动 Pod；容器内文件内容与路径正确、只读；同目录其他文件还在。
- [ ] 镜像拉取失败时，发布停在 HEALTH，旧 Pod 继续服务。
- [ ] 重启 platform-api 后执行进入「已中断」，点继续能完成（顺带验证 `reopen` 的 SQL）。
- [ ] 已中断时直接发新版本，旧执行被取代（顺带验证 `supersedeInterrupted` 的 SQL）。
- [ ] 一个应用卡住时，其他应用仍能发布。
- [ ] 回滚后线上配置与旧快照一致。
- [ ] 口令不出现在快照、清单和接口响应中。
- [ ] 依赖初始化：MySQL / PostgreSQL / Redis / ES / Kafka 各成功一次、各故意失败一次，失败时状态为失败且日志可读。
- [ ] Harbor 选 tag、新版本提示、资源页跳转在真实 Harbor 上可用。
- [ ] 清理迁移：有部署过的旧应用时第 0 步报错且表结构不变；删掉后重放，两列两表与 5 条权限行消失，未部署的旧应用变成可发布的空应用。

## 6. 已知限制

- 对外域名不在本次范围，第一版只提供 NodePort。
- 依赖新建不支持 Milvus，以及 ES Kibana/TLS/IK、Redis 分片副本等高级参数：先去中间件商店建，再绑定。
- ES 初始化的 `initPath` 可调该实例的任意 ES API（只作用于自己的实例，暂不收紧）；集群内 TLS 用 `curl -k`。
- 初始化排队时间计入超时；`MYSQL_PWD` 已被 MySQL 标记弃用，但客户端仍认。
- Harbor 镜像下，用户自填的 `imagePullSecrets` 列表里删不掉平台 Secret（保存时会再并入）；
  字符串形式的 `imagePullSecrets` 不合并；之前已自填列表的服务需要再保存一次才会并入平台 Secret。
- 发布列表的运行状态取台账而非实时探测。
- 只读成员除了在界面上，也能通过 API（`overview.desired`、`release/get`）看到环境变量的字面值；
  敏感值请用 Secret 引用而不是字面值。
- 一个 EXTERNAL 依赖被删除后又以新地址重新添加：回滚会部署旧快照里的旧地址，而概览显示的是新地址。
- `${依赖.url}` 对非 HTTP 类型依赖拼出的是 `http://host:port`，只对说 HTTP 的依赖有意义
  （ES 按实例 TLS 开关取 http/https）。
- HEALTH 等待应用内**所有**服务：未勾选的服务若不健康，会挡住其他服务的发布，直到它修好；
  执行记录的消息（进行中与超时失败）会点名未就绪的对象。
- 回滚没有单独的预览接口：前端用 `release/get` 读两份快照对比。
- **快照 / 清单列的容量**：`platform_app_release` 的 `snapshot_json`、`manifest` 是 MEDIUMTEXT（16MiB）。
  配置文件每服务至多 512KiB，内容同时进快照和清单（ConfigMap），所以一次发布里配置文件大的服务
  一多（十几个服务都接近上限）就可能写不进去，发布在建记录时失败。真遇到再改 LONGTEXT 或给每次发布设总量上限。
- **`-env` / `-files` ConfigMap 在 APPLY 阶段就被覆写**，早于 HEALTH：发布失败停在 HEALTH 时旧 Pod 仍在跑
  旧配置（环境变量启动时读一次、subPath 不跟随更新），但这期间任何旧 Pod 重启（崩溃、驱逐、节点故障）
  都会读到**新**的环境变量和配置文件，配旧镜像。ConfigMap 名不带版本号，是有意的取舍（不留历史 ConfigMap）；
  发布失败后尽快修复再发，或回滚。
