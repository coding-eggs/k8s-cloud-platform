# 中间件用户管理 · 真集群验收清单（2026-10-26）

> 计划：[2026-10-26-middleware-user-management.md](2026-10-26-middleware-user-management.md)
> spec：[2026-10-26-middleware-user-management-design.md](../specs/2026-10-26-middleware-user-management-design.md)
>
> **部署顺序**：重放 `V2026_10_26_2__kafka_user_rbac.sql` → 重建 k8s-server / platform-api / platform-web →
> 「租户 RBAC 模板」页点一次**重下发全部模板** → 存量 Kafka / Nacos 实例各重放一次。

本清单按"每家一张表"写：**建 → 连 → 改 → 连 → 删 → 连不上**。
把 `<实例名>` 换成实际名字（`mw-<code>-<8 hex>`），`<ns>` 换命名空间。

---

## 0. 前置（每家都要过一遍）

| 检查 | 命令 | 期望 |
|---|---|---|
| 用户页签出现 | 打开实例详情 → 用户 tab | 八家都出现；Kafka/Nacos 仅开了鉴权才出现 |
| 列表不泄漏口令 | 浏览器 devtools 看 `instance/users` 响应 | `password` 字段不存在 |
| 建用户返回一次明文 | 新增用户（口令留空） | 弹「口令只显示这一次」，含明文 + Secret 位置 |

---

## 1. MySQL

| 步骤 | 命令 | 期望 |
|---|---|---|
| 建用户 | 页面新增 `alice` / 读写 | 返回一次性口令 |
| 连 | `kubectl -n <ns> exec <实例名>-mysql-0 -- sh -c 'MYSQL_PWD=<alice口令> mysql -h127.0.0.1 -ualice -e "select 1"'` | 输出 1 |
| 改口令 | 页面改 `alice` 的口令 | 返回新明文 |
| 连（新口令） | 同上换新口令 | 输出 1 |
| 删除 | 页面删 `alice` | 列表消失 |
| 连（应失败） | 同上 | `Access denied` |
| 内置改口令 | 改 `root` 口令 | 返回新明文；`<实例名>-secrets` 的 `root` 键同步变了 |

## 2. PostgreSQL

| 步骤 | 命令 | 期望 |
|---|---|---|
| 建用户 | 页面新增 `alice` / 只读 | 返回一次性口令 |
| 看 decl | `kubectl -n <ns> get databaserole alice -o yaml` | `login: true`、`inRoles: [pg_read_all_data]`、`passwordSecret` 指向 `-user-alice` |
| 连 | `kubectl -n <ns> exec <实例名>-1 -- psql -U alice -h 127.0.0.1 -d app -c 'select 1'`（`PGPASSWORD` 走 env） | 输出 1 |
| 改口令 | 页面改口令 | 返回新明文；`-user-alice` Secret 的 password 变了 |
| 改内置 `-app` | 页面改应用账号口令 | **⚠️ 先验**：改完 `pg_roles.rolpassword` 是否变、Secret 是否被 operator 回写；回写则降级 |
| 删除 | 页面删 `alice` | `databaserole` 变 `ensure: absent`、`-user-alice` Secret 消失 |

## 3. Redis（只改口令）

| 步骤 | 命令 | 期望 |
|---|---|---|
| 列表 | 打开用户 tab | 只有 `default`，无「新增」可用项（增删按钮被挡） |
| 改口令 | 页面改 `default` 口令 | 返回新明文 |
| **⚠️ 先验**：改完看 `<实例名>-credentials` | `kubectl -n <ns> get secret <实例名>-credentials -o jsonpath='{.data.password}' \| base64 -d` | operator **没有**把它改回旧值 |
| 连 | `kubectl -n <ns> exec <实例名>-0 -- sh -c 'REDISCLI_AUTH=<新口令> redis-cli ping'` | `PONG` |

> 若 operator 回写旧值 → 把 `redis` 从 `MiddlewareUserRules.SUPPORTED_CODES` 去掉（UI 自动不显示用户页签）。

## 4. RabbitMQ

| 步骤 | 命令 | 期望 |
|---|---|---|
| 建用户 | 页面新增 `alice` / 只读 | 返回一次性口令 |
| 连 | `curl -u alice:<口令> http://<实例名>.<ns>.svc:15672/api/overview`（集群内） | 200 |
| 权限 | `curl -u <default用户>:<口令> http://…/api/permissions/%2F/alice` | `configure:""` `write:""` `read:".*"` |
| 改口令 / 删除 | 页面操作 | 新口令能连；删除后 401 |

## 5. Elasticsearch

| 步骤 | 命令 | 期望 |
|---|---|---|
| 建用户 | 页面新增 `alice` / 只读 | 返回一次性口令 |
| 看前缀 | 页面列表 | 显示 `alice`（ES 里实际是 `platform_alice`） |
| 连 | `curl -k -u platform_alice:<口令> https://<实例名>-es-http.<ns>.svc:9200/_cluster/health`（集群内） | 200 |
| 改 / 删 | 页面操作 | 改后新口令可用；删后 401 |

## 6. Milvus

| 步骤 | 命令 | 期望 |
|---|---|---|
| 前提 | 实例创建时勾了「开启鉴权」 | 用户 tab 可见 |
| 建用户 | 页面新增 `alice` / 管理员 | 返回一次性口令 |
| 连 | `curl -X POST http://<实例名>-milvus.<ns>.svc:19530/v2/vectordb/users/list -d '{}' -H 'Content-Type: application/json'` | 含 `alice`（鉴权开着时需带 token） |
| 改 root | 详情页「改密码」或用户 tab 改 `root` | 两处都写 `<实例名>-milvus-auth` |
| 删除 | 页面删 `alice` | `users/list` 不再含 `alice` |

## 7. Kafka（需先重放一次才有 9093）

| 步骤 | 命令 | 期望 |
|---|---|---|
| listener | `kubectl -n <ns> get kafka <实例名> -o jsonpath='{.spec.kafka.listeners}'` | `plain:9092` + `scram:9093`，且**没有** `authorization` |
| 建用户 | 页面新增 `alice` | 返回一次性口令（**operator 生成的**，不是页面传的） |
| 看 CR | `kubectl -n <ns> get kafkauser alice -o yaml` | `authentication.type: scram-sha-512`、`strimzi.io/cluster: <实例名>` |
| 口令来源 | `kubectl -n <ns> get secret alice -o jsonpath='{.data.password}' \| base64 -d` | 与页面弹出的一次性口令一致 |
| plain 不受影响 | `kubectl -n <ns> exec <实例名>-brokers-0 -- bin/kafka-topics.sh --bootstrap-server localhost:9092 --list` | 正常返回 |
| 改口令 | 页面改口令 | CR 被删除重建，返回新的 operator 口令 |
| 删除 | 页面删除 | KafkaUser 消失 |

## 8. Nacos（需先重放一次才有 auth.enabled）

| 步骤 | 命令 | 期望 |
|---|---|---|
| 前提 | 创建时勾「开启鉴权」 | `kubectl -n <ns> get secret <实例名>-nacos-admin` 存在 |
| 管理员口令 | `kubectl -n <ns> get secret <实例名>-nacos-admin -o jsonpath='{.data.password}' \| base64 -d` | 能登录 |
| 登录 | `curl -X POST 'http://nacos-cs.<ns>.svc:8848/nacos/v3/auth/user/login' -d 'username=nacos&password=<口令>'` | 返回 `accessToken` |
| 建用户 | 页面新增 `alice` | 返回一次性口令 |
| **⚠️ 先验**：3.x 管理员口令初始化路径 | 全新实例首次登录 | 能直接用平台生成的口令登录；若要求先初始化密码 → 把 `nacos` 从支持清单去掉 |

---

## 9. 验收结论怎么记

把每家两列填进一张表贴在 PR/记录里：

| 中间件 | 结果 | 备注（验不过的那条） |
|---|---|---|
| mysql | ✅ / ❌ | |
| postgresql | ✅ / ❌ | CNPG 是否 reconcile `-app` |
| redis | ✅ / ❌ | operator 是否回写 Secret |
| rabbitmq | ✅ / ❌ | |
| elasticsearch | ✅ / ❌ | CA 信任链 |
| milvus | ✅ / ❌ | |
| kafka | ✅ / ❌ | plain 客户端是否受影响 |
| nacos | ✅ / ❌ | 管理员口令初始化路径 |

验不过的那家：把 code 从 `MiddlewareUserRules.SUPPORTED_CODES` 去掉（platform-api 与前端各一份清单由后端下发，
后端去掉即可让 UI 不显示用户页签）。
