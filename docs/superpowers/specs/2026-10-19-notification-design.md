# 通知方式（S2.2.4）设计

> 目标：告警已经**全部 webhook 到平台**（Alertmanager 的 `platform` receiver 是唯一出口），
> 因此「发给谁、怎么发」从这一版起由平台决定，不再由 Alertmanager 自己发。
>
> 本轮范围：通知方式管理（含连接配置）、规则绑定「通知方式 + 收件人」、按绑定发送邮件、投递记录。
> **不做**：告警压制（下一轮）、邮件模板自定义、非邮件类型（表结构预留）。

---

## 1. 现场与前提

| 事实 | 影响 |
|---|---|
| Alertmanager 只往平台推（`AlertmanagerConfig/platform-webhook`） | 平台不发送 = 没人收到；本轮必须真发 |
| 告警落 `platform_alert_event`，`labels` 里没有「谁该收」 | 收件人只能来自平台侧配置 |
| `platform_user` 有 `email`；`platform_user_tenant` 记录用户-租户关系 | 收件人 = 平台用户，不维护第二份邮箱 |
| 现有 `platform_credential` 只覆盖 git / registry | 通知方式的连接配置自己存（见决策 3） |
| 内置规则（Helm 装的）不走平台保存路径 | 没有绑定 → 走默认渠道 + 默认收件人 |

---

## 2. 决策记录

**决策 1：通知方式是平台实体，可建多条，带类型与连接配置。**
本轮只有 `EMAIL`；表里预留 `type`，以后加短信/企微机器人就是新增一条记录 + 一段配置解析，
不改规则侧契约。

**决策 2：渠道带租户属性。**
`tenant_id` 为空 = 全局渠道（所有租户的规则都能选）；非空 = 该租户专用。
规则编辑页只列「全局 + 本租户」的启用渠道 —— 防止 A 租户的规则选中含 B 租户收件人的渠道。

**决策 3：连接配置存渠道表内 JSON，敏感字段字段级加密。**
`config` 是 JSON blob（按 `type` 不同结构），密码/密钥/token 用现有 `AESUtils`
（`platform.credential.aes-key`）加密后**以字符串形式**存在同一个 JSON 里。
理由同 `PlatformCredential`：加新类型 = blob 多几个键，零迁移。

**决策 4：`rule → 通知方式 + 收件人` 的绑定存在平台表，不写进 K8s 对象。**
label 一个值只能放一个字符串（≤63 字符），放不下用户列表；而让每个收件人占一个 label
会带来两个硬伤：标签数量随人数增长、**收件人一变就改规则对象的标签集 → 告警 fingerprint 变 →
同一次故障会重新触发**。所以绑定进表。

**决策 5：label 仍要写，但只写渠道 code（冗余展示）。**
`labels.platform_notify = <channel code>` 由后端在保存规则时代打（同 `labels.namespace`），
用途只有一个：告警自带「该用哪个通知方式」，列表上能直接看到。**收件人不进 label。**

**决策 6：未绑定的规则走默认。**
- 默认渠道：先找该告警所属租户的默认渠道，再找全局默认渠道；
- 默认收件人：该命名空间所属租户的 `tenant-admin` 用户。
两者都没有 → 投递记 `FAILED("未配置通知渠道/收件人")`，不影响落库与列表。

**决策 7：投递异步 + 记录 + 失败重试 3 次。**
webhook 必须快速回 200（Alertmanager 只看状态码），发送不能阻塞它。
每次投递落 `platform_alert_delivery`，失败按 1s / 5s / 30s 退避重试，最多 3 次。

---

## 3. 数据模型

### 3.1 `platform_notification_channel`

| 列 | 类型 | 说明 |
|---|---|---|
| `id` | VARCHAR(64) | ULID |
| `code` | VARCHAR(64) | **全局唯一（跨租户）**；写进 label，必须 K8s label 合法（`[A-Za-z0-9]([-A-Za-z0-9_.]*[A-Za-z0-9])?`、≤63）。全局唯一是必须的 —— label 里只有 code，靠它唯一定位渠道 |
| `name` | VARCHAR(128) | 显示名（可中文） |
| `type` | VARCHAR(32) | 本轮只有 `EMAIL` |
| `config` | JSON | 按类型的连接配置；敏感字段加密后的字符串 |
| `tenant_id` | VARCHAR(64) NULL | NULL = 全局；非空 = 该租户专用 |
| `is_default` | TINYINT | 默认渠道（同一 `tenant_id` 维度下唯一，应用层保证） |
| `enabled` | TINYINT | 停用后不可新选；已绑定的规则**照常发送**（见 §6） |
| `created_at` / `updated_at` | DATETIME(3) | |

`EMAIL` 的 `config` 形如：

```json
{
  "host": "smtp.example.com",
  "port": 465,
  "ssl": true,
  "username": "alerts@example.com",
  "passwordEnc": "<AES 密文>",
  "from": "alerts@example.com"
}
```

> 后续 `SMS` / `WECHAT_WORK` 各自定义 `config` 结构；解析放在 `NotificationSender` 的实现里，
> 表结构不动。

### 3.2 `platform_rule_notify`（绑定表）

| 列 | 类型 | 说明 |
|---|---|---|
| `id` | VARCHAR(64) | ULID |
| `cluster_id` | VARCHAR(64) | |
| `namespace` | VARCHAR(253) | |
| `rule_name` | VARCHAR(253) | PrometheusRule 名（**CR 级**绑定） |
| `channel_code` | VARCHAR(64) | 选中的通知方式 |
| `user_ids` | JSON | 收件人平台用户 id 数组（同租户） |
| `created_at` / `updated_at` | DATETIME(3) | |

`UNIQUE KEY (cluster_id, namespace, rule_name)`。

> 规则改名/删除后绑定成为孤儿：告警里带着旧 code 时按默认渠道处理；
> 规则列表里的孤儿绑定本轮只在日志里提示，不阻断。

### 3.3 `platform_alert_delivery`

| 列 | 类型 | 说明 |
|---|---|---|
| `id` | BIGINT AUTO_INCREMENT | 追加型日志表（同 `platform_audit_log`） |
| `event_id` | BIGINT | `platform_alert_event.id` |
| `channel_code` | VARCHAR(64) | 实际使用的渠道 |
| `alert_status` | VARCHAR(16) | 投递时的告警状态（firing / resolved） |
| `status` | VARCHAR(16) | PENDING / SENT / FAILED |
| `attempts` | INT | 已尝试次数 |
| `last_error` | VARCHAR(1024) | 最后一次失败原因 |
| `created_at` / `sent_at` | DATETIME(3) | |

`UNIQUE KEY (event_id, channel_code, alert_status)` —— **去重键**：
（`event_id` 的来源见 §5 的「upsert 后回查」——MySQL 的 `ON DUPLICATE KEY UPDATE` 不回填自增 id）
Alertmanager 的 `repeat_interval` 重推同一告警时不会重复发邮件；
恢复时 `alert_status` 变成 `resolved`，是另一条投递，正常发。

---

## 4. 规则侧

- `PrometheusRuleDTO` 增加平台侧字段 `notifyChannel`（渠道 code）与 `notifyUserIds`（用户 id 列表），
  **converter 不映射**，只用于保存绑定；与 `ServiceMonitorDTO.serviceRef` 同款（解析后剥离）。
- 编辑页新增两块：
  - **通知方式**：下拉，选项 = 全局 + 本租户的启用渠道（`name`，值为 `code`）；默认选中默认渠道。
  - **通知给谁**：同租户用户多选（显示 `displayName` + email；无邮箱的用户给提示，仍可选）。
- 保存（create / update）时后端：
  1. 校验 `notifyChannel` 属于「全局 + 本租户」且启用；
  2. upsert `platform_rule_notify`（CR 级）；
  3. 给每条 **alert** 规则写 `labels.platform_notify = code`（和 `namespace` 同一处代打）；
  4. 剥离 DTO 上的两个平台侧字段，**不下发**给 k8s-server。
- `record` 规则不打标（它不产生告警）。

---

## 5. 投递流程

```
POST /alert/webhook
  └─ AlertIngestService.ingest(...)         落库（upsert，幂等），返回落库后的事件列表
       └─ AlertDispatchService.dispatch(events)   @Async，不阻塞响应
            ├─ 补齐 event 的 id：upsert 命中已有行时 MySQL 不回填自增 id，
            │   按 (cluster_id, fingerprint, starts_at) 回查一次（唯一键，成本可忽略）
            ├─ 解析渠道：labels.platform_notify → 渠道；缺失/无效 → 默认渠道
            ├─ 解析收件人：绑定表（按 namespace + alertname 反查 CR 名）→ user_ids
            │              缺失 → 该命名空间所属租户的 tenant-admin 用户
            ├─ 按渠道分组，每组一封邮件（firing / resolved 分块）
            ├─ 每个 event 落一条 delivery（唯一键去重，已存在则跳过）
            ├─ NotificationSender（EMAIL）发送；失败重试 3 次
            └─ 更新 delivery 状态
```

**发送器**：`NotificationSender` 接口（`type()` + `send(channel, recipients, alerts)`）；
本轮只实现 `EmailSender`。加新类型 = 新增一个实现 bean，调度处按 `channel.type` 选。

**邮件内容**：

- 主题：`[firing] 3 条告警 · <渠道名>` / `[resolved] 1 条告警`；
- 正文：firing 与 resolved 分块，每条列 `alertname / namespace / severity / 摘要 / 开始时间(或结束时间)`；
- 纯文本 + 简单 HTML 二选一（本轮**纯文本**，避免模板维护成本）；
- 收件人没有邮箱的跳过并记 warn；整批无有效收件人 → `FAILED("无有效收件人")`。

**依赖**：`spring-boot-starter-mail`（只用 `JavaMailSenderImpl`，**不读全局 `spring.mail.*`** ——
每个渠道自带 SMTP 配置，由 `EmailSender` 按渠道构建实例并缓存）。

**异步执行**：固定大小线程池（4 线程）+ 有界队列；重试在该线程内 sleep 退避（1s/5s/30s），
不占用请求线程。应用重启时把 `PENDING` 的记录重新入队（启动扫描一次）。

---

## 6. 错误与降级

| 情况 | 行为 |
|---|---|
| 渠道 code 在库中不存在 / 被删除 | 用默认渠道；都无 → `FAILED` |
| 渠道被停用 | **已绑定的规则照常发送**（停用只影响「新选择」），避免停用一次静默断掉线上告警 |
| 渠道租户不匹配（规则租户 ≠ 渠道租户且渠道非全局） | 用默认渠道；记 warn |
| 用户无 email | 跳过该用户；所有用户都无 → `FAILED` |
| SMTP 配置缺失 / 连接失败 | `FAILED` + 重试；错误原文进 `last_error` |
| 绑定表查不到（历史规则） | 默认渠道 + 默认收件人 |
| 告警 namespace 不属于任何租户 | 无默认收件人 → `FAILED("无收件人")` |

**投递失败绝不影响 webhook 的 200。** 邮件是尽力而为，落库与列表是主功能。

---

## 7. 接口与页面

| 方法 | 路径 | 权限码 | 说明 |
|---|---|---|---|
| POST | `/notification-channels/list` | `platform:notifychannel:list` | 管理员列表 |
| POST | `/notification-channels` | `platform:notifychannel:create` | 新建 |
| PUT | `/notification-channels/{code}` | `platform:notifychannel:update` | 修改（含启停 / 默认） |
| DELETE | `/notification-channels/{code}` | `platform:notifychannel:delete` | 有绑定引用时拒绝 |
| POST | `/notification-channels/options` | `tenant:notifychannel:list` | 规则编辑页候选（全局 + 本租户的启用渠道） |
| POST | `/notification-users/list` | `tenant:notificationuser:list` | 本租户用户（id / displayName / email） |

**页面**：
- 「通知方式」页（菜单：监控告警 → 通知方式，**仅管理员**）：列表 + 新建/编辑抽屉（名称、code、类型、连接配置、租户范围、默认、启停）。
- 规则编辑页：通知方式下拉 + 收件人多选。
- 告警列表：新增「通知方式」（label → 渠道名）、「投递状态」（最新 delivery；未投递显示「—」）。

---

## 8. 权限与迁移

- Page：`platform:page:notifychannel`（管理员）。租户看不到该页。
- API：`platform:notifychannel:*` 绑 `builtin_role_admin`。
- 候选接口用 `tenant:` 码并**同时绑 admin**（§「平台+租户共用能力必须用 tenant: 码并同时绑 admin」）：
  - `tenant:notifychannel:list` → admin + tenant_admin；
  - `tenant:notificationuser:list` → admin + tenant_admin。
- 迁移文件：`V2026_10_19_8__notification_channels.sql`（10_19_1~7 已被占用）。**先重放再起构建。**
- 每条新权限行显式绑角色。

---

## 9. 测试策略

**单元（platform-api）**
- 渠道 CRUD：code 合法性（K8s label）、默认唯一、删除时有绑定则拒绝、敏感字段加密/解密；
- 绑定保存：规则 create/update 写表 + 写 label；`record` 规则不打标；平台侧字段被剥离；
- 派发决策：label 有/无、渠道不存在、渠道停用、租户不匹配、默认渠道回落；
- 收件人：绑定表命中 / 回落到租户管理员 / 无收件人；
- 去重：同一 `(event, channel, status)` 只发一次；resolved 另发一条；
- 重试：失败 3 次后 `FAILED`，`attempts` 正确；
- 发送器：`EmailSender` 用 mock `JavaMailSender` 验证主题/收件人/正文。

**真跑**
- 用真实 SMTP（或用户提供的邮箱）手动触发一次：`/alert/self-test` → 收到邮件；
- 告警列表能看到「通知方式」「投递状态 = SENT」。

---

## 10. 未覆盖 / 后续

- **告警压制**（下一轮）：在 `AlertDispatchService` 的发送前留钩子 `beforeSend(events)`；
  具体规则（同 namespace+alertname 下 critical 压制 warning 等）届时单独设计。
- 邮件模板自定义、每用户通知偏好、非邮件类型。
- 孤儿绑定（规则已删）的自动清理。
- 投递失败的 UI 重发入口。
