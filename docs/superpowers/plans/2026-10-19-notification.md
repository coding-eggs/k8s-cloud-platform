# 通知方式（S2.2.4）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让平台接管告警通知：管理员维护通知方式（含连接配置），规则绑定「通知方式 + 收件人」，告警到达后按绑定异步发邮件并记录投递结果。

**Architecture:** 三块。① 通知方式是平台实体（类型 + JSON 连接配置 + 租户属性）；② 规则 ↔ 通知方式/收件人 的绑定存平台表，保存规则时顺带给 alert 规则代打 `labels.platform_notify=<code>`（只为展示与告警自带）；③ 告警落库后异步派发：解析渠道与收件人 → 按渠道合并 → 落投递记录（唯一键去重）→ 发邮件 → 失败重试 3 次。

**Tech Stack:** Spring Boot 4.1.1 / Java 21 / MyBatis / MySQL / fabric8 7.9.0 / Vue 3 + Element Plus / `spring-boot-starter-mail`

**Spec:** `docs/superpowers/specs/2026-10-19-notification-design.md`

## Global Constraints

- **本仓没有 Flyway**：`db/migration/*.sql` 手工重放。**先重放迁移，再起新构建**。
- **权限行路径无 `/resource` 前缀**；Page 域用 `/resources/...`（资源页）或页面自身路径。
- **每条新权限行必须显式绑角色**，漏挂 = 部署后 403。
- **权限行变了必须重新登录**。
- **Maven 一律带 `-am`**：`mvn -pl platform-api -am test`。
- **新增业务错误码从 `10046` 起**（现存最大 10045）。
- **`git add` 一律显式列文件**，绝不 `git add -A`（有并行会话共享 checkout）。
- **提交信息末尾**：`Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>`
- **前端类型检查**：`cd platform-web && npm run type-check`；批次结束跑 `npm run build`。
- **集群只做只读查询；不运行容器。**
- 渠道 `code` 必须满足 K8s label 值语法：`[A-Za-z0-9]([-A-Za-z0-9_.]*[A-Za-z0-9])?`，长度 ≤ 63，**全局唯一**。
- 敏感字段用 `AESUtils`（`platform.credential.aes-key`）加密后存 JSON；**不得明文落库**。

## Review Focus

以下是 spec 蕴含、但没有哪个任务的测试直接覆盖的失效场景，最容易咬到用它的人。每条都在归属任务里有对应测试。

1. **收件人全都没有邮箱**（用户资料没填 email）—— 用户期望「没发出去」能被看见，而不是一封空邮件静默成功。→ Task 11
2. **Alertmanager 重推同一告警**（`repeat_interval` 12h）—— 用户期望**不会重复收到邮件**。→ Task 11
3. **渠道被停用后，已绑定的规则** —— 用户期望线上告警照常发，而不是停用一次就静默断掉。→ Task 3 / Task 11
4. **规则改名或删除后的历史告警** —— 绑定查不到，用户期望回落到默认收件人，而不是抛异常或丢弃。→ Task 11
5. **SMTP 配错（密码/端口）** —— 用户期望 webhook 仍回 200、告警仍落库，失败原因能在投递记录里看到。→ Task 9 / Task 12

---

# 块 1 · 通知方式管理

## Task 1: 迁移 —— 三张表 + 权限行

**Files:**
- Create: `platform-data/src/main/resources/db/migration/V2026_10_19_8__notification_channels.sql`
- Test: 手工重放 + 启动校验（本仓无 Flyway，无自动化测试）

**Interfaces:**
- Produces: 表 `platform_notification_channel` / `platform_rule_notify` / `platform_alert_delivery`；
  权限码 `platform:page:notifychannel`、`platform:notifychannel:{list,create,update,delete}`、
  `tenant:notifychannel:list`、`tenant:notificationuser:list`

- [ ] **Step 1: 写迁移文件**

```sql
-- ============================================================================================
-- S2.2.4 通知方式：渠道 / 规则绑定 / 投递记录 + 权限行
-- ============================================================================================
-- 设计见 docs/superpowers/specs/2026-10-19-notification-design.md。
-- ⚠️ 本仓没有 Flyway，本文件手工重放；先重放再起新构建。

CREATE TABLE IF NOT EXISTS `platform_notification_channel` (
  `id`          VARCHAR(64)  NOT NULL,
  `code`        VARCHAR(64)  NOT NULL COMMENT '写进 label 的值；K8s label 合法；全局唯一',
  `name`        VARCHAR(128) NOT NULL,
  `type`        VARCHAR(32)  NOT NULL COMMENT 'EMAIL（本轮唯一）',
  `config`      JSON                  DEFAULT NULL COMMENT '按类型的连接配置；敏感字段加密后存字符串',
  `tenant_id`   VARCHAR(64)           DEFAULT NULL COMMENT 'NULL=全局；非空=该租户专用',
  `is_default`  TINYINT      NOT NULL DEFAULT 0,
  `enabled`     TINYINT      NOT NULL DEFAULT 1,
  `created_at`  DATETIME(3)  NOT NULL,
  `updated_at`  DATETIME(3)  NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_notify_channel_code` (`code`),
  KEY `idx_notify_channel_tenant` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='通知方式（渠道）';

CREATE TABLE IF NOT EXISTS `platform_rule_notify` (
  `id`           VARCHAR(64)  NOT NULL,
  `cluster_id`   VARCHAR(64)  NOT NULL,
  `namespace`    VARCHAR(253) NOT NULL,
  `rule_name`    VARCHAR(253) NOT NULL,
  `channel_code` VARCHAR(64)  NOT NULL,
  `user_ids`     JSON                  DEFAULT NULL COMMENT '收件人平台用户 id 数组',
  `created_at`   DATETIME(3)  NOT NULL,
  `updated_at`   DATETIME(3)  NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_rule_notify` (`cluster_id`, `namespace`, `rule_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='规则 → 通知方式/收件人 绑定（CR 级）';

CREATE TABLE IF NOT EXISTS `platform_alert_delivery` (
  `id`           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '追加型日志表，同 platform_audit_log',
  `event_id`     BIGINT       NOT NULL,
  `channel_code` VARCHAR(64)  NOT NULL,
  `alert_status` VARCHAR(16)  NOT NULL COMMENT '投递时的告警状态 firing/resolved',
  `status`       VARCHAR(16)  NOT NULL COMMENT 'PENDING/SENT/FAILED',
  `attempts`     INT          NOT NULL DEFAULT 0,
  `last_error`   VARCHAR(1024)         DEFAULT NULL,
  `created_at`   DATETIME(3)  NOT NULL,
  `sent_at`      DATETIME(3)           DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_delivery_once` (`event_id`, `channel_code`, `alert_status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='告警投递记录';

-- ===== 权限行 =====
INSERT INTO platform_permission (id,domain,resource,action,code,description) VALUES
 ('perm_page_notifychannel','Page','/notification-channels','VIEW','platform:page:notifychannel','页面·通知方式（仅管理员）'),
 ('perm_notifychannel_list','API','/notification-channels/list','POST','platform:notifychannel:list','通知方式列表'),
 ('perm_notifychannel_create','API','/notification-channels','POST','platform:notifychannel:create','新建通知方式'),
 ('perm_notifychannel_update','API','/notification-channels/{code}','PUT','platform:notifychannel:update','修改通知方式'),
 ('perm_notifychannel_delete','API','/notification-channels/{code}','DELETE','platform:notifychannel:delete','删除通知方式'),
 ('perm_notifychannel_options','API','/notification-channels/options','POST','tenant:notifychannel:list','通知方式候选（规则编辑页）'),
 ('perm_notificationuser_list','API','/notification-users/list','POST','tenant:notificationuser:list','本租户用户候选（规则编辑页）')
ON DUPLICATE KEY UPDATE description=VALUES(description), resource=VALUES(resource), action=VALUES(action);

-- admin：全部行
INSERT INTO platform_role_permission (id,role_id,permission_id)
SELECT CONCAT('rp_admin_notify_', p.id), 'builtin_role_admin', p.id
FROM platform_permission p
WHERE p.id IN ('perm_page_notifychannel','perm_notifychannel_list','perm_notifychannel_create',
               'perm_notifychannel_update','perm_notifychannel_delete','perm_notifychannel_options',
               'perm_notificationuser_list')
ON DUPLICATE KEY UPDATE permission_id=VALUES(permission_id);

-- tenant-admin：候选两个（规则编辑页要用）+ 页面（详情见下）
INSERT INTO platform_role_permission (id,role_id,permission_id)
SELECT CONCAT('rp_tadmin_notify_', p.id), 'builtin_role_tenant_admin', p.id
FROM platform_permission p
WHERE p.id IN ('perm_notifychannel_options','perm_notificationuser_list')
ON DUPLICATE KEY UPDATE permission_id=VALUES(permission_id);

-- ⚠️ 「通知方式」页面本身是管理员专属（platform 域 page 码），租户不绑。
```

- [ ] **Step 2: 手工重放**

用 jshell + JDBC（配方见 memory `cluster-access-for-debugging`：
`jdbc:mysql://192.168.31.80:1234/k8s_cloud_platform`，root/coding，驱动在
`~/.m2/repository/com/mysql/mysql-connector-j/9.7.0/`）。逐条 `execute`。
MySQL 的 `--` 注释**必须跟一个空白**。

- [ ] **Step 3: 核对**

```sql
show create table platform_notification_channel;
show create table platform_rule_notify;
show create table platform_alert_delivery;
select id, code from platform_permission where id like 'perm_%notify%' or id like 'perm_page_notifychannel' or id='perm_notificationuser_list';
select role_id, count(*) from platform_role_permission
 where permission_id in ('perm_page_notifychannel','perm_notifychannel_list','perm_notifychannel_create',
   'perm_notifychannel_update','perm_notifychannel_delete','perm_notifychannel_options','perm_notificationuser_list')
 group by role_id;
```

Expected：三张表存在；7 条权限行；`builtin_role_admin` 7 条，`builtin_role_tenant_admin` 2 条。

- [ ] **Step 4: 启动校验**

Run: `mvn -pl platform-api -am -DskipTests package`，然后起 platform-api（需 `ALERT_WEBHOOK_TOKEN`，或使用配置里已有的默认值）。
Expected：启动成功；**幽灵行**里出现本次新增的 5 条 API 行（端点 Task 4 才建，只 WARN 不拦启动），
`[RBAC] 交叉校验通过` 正常打印。

- [ ] **Step 5: 提交**

```bash
git add platform-data/src/main/resources/db/migration/V2026_10_19_8__notification_channels.sql
git commit -m "feat(notify): 迁移 —— 渠道/绑定/投递三张表 + 权限行"
```

---

## Task 2: 数据层 —— 三个实体 + 三个 mapper

**Files:**
- Create: `platform-data/src/main/java/com/coding/data/models/alert/NotificationChannel.java`
- Create: `platform-data/src/main/java/com/coding/data/models/alert/RuleNotify.java`
- Create: `platform-data/src/main/java/com/coding/data/models/alert/AlertDelivery.java`
- Create: `platform-data/src/main/java/com/coding/data/mapper/alert/NotificationChannelMapper.java`
- Create: `platform-data/src/main/java/com/coding/data/mapper/alert/RuleNotifyMapper.java`
- Create: `platform-data/src/main/java/com/coding/data/mapper/alert/AlertDeliveryMapper.java`
- Create: `platform-data/src/main/resources/mapper/alert/NotificationChannelMapper.xml`
- Create: `platform-data/src/main/resources/mapper/alert/RuleNotifyMapper.xml`
- Create: `platform-data/src/main/resources/mapper/alert/AlertDeliveryMapper.xml`
- Modify: `platform-data/src/main/java/com/coding/data/mapper/auth/PlatformUserMapper.java` + XML（按 id 批量取、按租户取）
- Test: 无自动化测试（`platform-data` 无 test 树）——用真库 harness 验证（本任务 Step 4）

**Interfaces:**
- Produces:
  - `NotificationChannelMapper#selectAll() / selectByCode(String) / selectAvailable(String tenantId) / selectTenantDefault(String tenantId) / selectGlobalDefault() / insert(...) / updateByCode(...) / deleteByCode(String) / clearDefault(String tenantId)`
  - `RuleNotifyMapper#selectByRule(String clusterId, String namespace, String ruleName) / upsert(RuleNotify) / deleteByRule(...) / countByChannel(String channelCode)`
  - `AlertDeliveryMapper#insertIfAbsent(AlertDelivery) → int（1=新插入，0=已存在） / markSent(long id) / markFailed(long id, String error) / selectLatestByEventIds(List<Long>)`

- [ ] **Step 1: 实体**

```java
package com.coding.data.models.alert;

import lombok.Data;
import java.util.Date;

/** 通知方式（表 platform_notification_channel）。config 是 JSON 串，敏感字段已加密。 */
@Data
public class NotificationChannel {
    private String id;
    /** 写进 label，K8s label 合法，全局唯一 */
    private String code;
    private String name;
    /** EMAIL（本轮唯一） */
    private String type;
    /** JSON 串 */
    private String config;
    /** null = 全局；非空 = 该租户专用 */
    private String tenantId;
    private Boolean isDefault;
    private Boolean enabled;
    private Date createdAt;
    private Date updatedAt;
}
```

```java
package com.coding.data.models.alert;

import lombok.Data;
import java.util.Date;

/** 规则 → 通知方式/收件人（表 platform_rule_notify，CR 级）。 */
@Data
public class RuleNotify {
    private String id;
    private String clusterId;
    private String namespace;
    private String ruleName;
    private String channelCode;
    /** 用户 id 数组的 JSON 串 */
    private String userIds;
    private Date createdAt;
    private Date updatedAt;
}
```

```java
package com.coding.data.models.alert;

import lombok.Data;
import java.util.Date;

/** 一条告警 × 一个渠道 × 一个状态 = 一次投递（表 platform_alert_delivery）。 */
@Data
public class AlertDelivery {
    private Long id;
    private Long eventId;
    private String channelCode;
    /** 投递时的告警状态 firing/resolved */
    private String alertStatus;
    /** PENDING/SENT/FAILED */
    private String status;
    private Integer attempts;
    private String lastError;
    private Date createdAt;
    private Date sentAt;
}
```

- [ ] **Step 2: mapper 接口**

```java
package com.coding.data.mapper.alert;

import com.coding.data.models.alert.NotificationChannel;
import org.apache.ibatis.annotations.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface NotificationChannelMapper {
    List<NotificationChannel> selectAll();
    NotificationChannel selectByCode(@Param("code") String code);
    /** 规则编辑页候选：全局 + 本租户，且 enabled=1 */
    List<NotificationChannel> selectAvailable(@Param("tenantId") String tenantId);
    /** 该租户的默认渠道（tenant_id 精确匹配且 is_default=1） */
    NotificationChannel selectTenantDefault(@Param("tenantId") String tenantId);
    /** 全局默认渠道（tenant_id is null 且 is_default=1） */
    NotificationChannel selectGlobalDefault();
    int insert(NotificationChannel c);
    int updateByCode(NotificationChannel c);
    int deleteByCode(@Param("code") String code);
    /** 把同一租户维度（tenantId 为 null 时清全局）的默认标记全部清掉 */
    int clearDefault(@Param("tenantId") String tenantId);
}
```

```java
package com.coding.data.mapper.alert;

import com.coding.data.models.alert.RuleNotify;
import org.apache.ibatis.annotations.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface RuleNotifyMapper {
    RuleNotify selectByRule(@Param("clusterId") String clusterId,
                            @Param("namespace") String namespace,
                            @Param("ruleName") String ruleName);
    int upsert(RuleNotify r);
    int deleteByRule(@Param("clusterId") String clusterId,
                     @Param("namespace") String namespace,
                     @Param("ruleName") String ruleName);
    /** 删除渠道前的引用检查 */
    int countByChannel(@Param("channelCode") String channelCode);
}
```

```java
package com.coding.data.mapper.alert;

import com.coding.data.models.alert.AlertDelivery;
import org.apache.ibatis.annotations.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AlertDeliveryMapper {
    /** 幂等领取：唯一键已存在时不插入、返回 0 */
    int insertIfAbsent(AlertDelivery d);
    int markSent(@Param("id") Long id);
    int markFailed(@Param("id") Long id, @Param("error") String error,
                   @Param("attempts") Integer attempts);
    List<AlertDelivery> selectLatestByEventIds(@Param("eventIds") List<Long> eventIds);
    /** 启动扫描：中断的 PENDING 记录 */
    List<AlertDelivery> selectPending();
}
```

- [ ] **Step 3: XML**

`NotificationChannelMapper.xml`（resultMap 全列 + 下面几条关键语句）：

```xml
<select id="selectAvailable" resultMap="BaseResultMap">
  select <include refid="Base_Cols"/> from platform_notification_channel
  where enabled = 1 and (tenant_id is null or tenant_id = #{tenantId,jdbcType=VARCHAR})
  order by tenant_id is not null desc, code
</select>

<select id="selectTenantDefault" resultMap="BaseResultMap">
  select <include refid="Base_Cols"/> from platform_notification_channel
  where tenant_id = #{tenantId,jdbcType=VARCHAR} and is_default = 1 and enabled = 1 limit 1
</select>

<select id="selectGlobalDefault" resultMap="BaseResultMap">
  select <include refid="Base_Cols"/> from platform_notification_channel
  where tenant_id is null and is_default = 1 and enabled = 1 limit 1
</select>

<update id="clearDefault">
  update platform_notification_channel set is_default = 0
  <choose>
    <when test="tenantId != null">where tenant_id = #{tenantId,jdbcType=VARCHAR}</when>
    <otherwise>where tenant_id is null</otherwise>
  </choose>
</update>

<delete id="deleteByCode">
  delete from platform_notification_channel where code = #{code,jdbcType=VARCHAR}
</delete>
```

`RuleNotifyMapper.xml`：

```xml
<insert id="upsert" parameterType="com.coding.data.models.alert.RuleNotify">
  insert into platform_rule_notify (id, cluster_id, `namespace`, rule_name, channel_code, user_ids, created_at, updated_at)
  values (#{id}, #{clusterId}, #{namespace}, #{ruleName}, #{channelCode}, #{userIds}, #{createdAt}, #{updatedAt})
  on duplicate key update channel_code = values(channel_code), user_ids = values(user_ids), updated_at = values(updated_at)
</insert>

<select id="countByChannel" resultType="int">
  select count(1) from platform_rule_notify where channel_code = #{channelCode,jdbcType=VARCHAR}
</select>
```

`AlertDeliveryMapper.xml`（**幂等领取**是关键）：

```xml
<insert id="insertIfAbsent" parameterType="com.coding.data.models.alert.AlertDelivery"
        useGeneratedKeys="true" keyProperty="id">
  insert ignore into platform_alert_delivery
    (event_id, channel_code, alert_status, `status`, attempts, created_at)
  values (#{eventId}, #{channelCode}, #{alertStatus}, #{status}, 0, #{createdAt})
</insert>

<update id="markSent">
  update platform_alert_delivery set `status`='SENT', sent_at = now(3), last_error = null
  where id = #{id}
</update>

<update id="markFailed">
  update platform_alert_delivery set `status`='FAILED', last_error = #{error}, attempts = #{attempts}
  where id = #{id}
</update>

<select id="selectLatestByEventIds" resultMap="BaseResultMap">
  select <include refid="Base_Cols"/> from platform_alert_delivery
  where event_id in
  <foreach collection="eventIds" item="eid" open="(" separator="," close=")">#{eid}</foreach>
  order by id desc
</select>
```

> ⚠️ `insert ignore` 命中唯一键时返回 0，调用方据此判断「这次是不是第一次投递」。

- [ ] **Step 4: 真库 harness 验证（本模块无单测）**

写一个临时 JDBC 程序（用完删）依次验证：
1. 插入两条渠道（`a-mail` 全局默认、`b-mail` 租户 t1 默认）→ `selectAvailable("t1")` 返回 2 条，`selectAvailable("t2")` 返回 1 条；
2. `clearDefault(null)` 后 `selectGlobalDefault()` 为空；
3. 同一 `(event_id, channel_code, alert_status)` 连续 `insert ignore` 两次 → 第一次 1、第二次 0；
4. `RuleNotify.upsert` 两次 → 只有一行，`channel_code` 被更新；
5. 清理测试数据。

- [ ] **Step 5: 提交**

```bash
git add platform-data/src/main/java/com/coding/data/models/alert \
        platform-data/src/main/java/com/coding/data/mapper/alert \
        platform-data/src/main/resources/mapper/alert \
        platform-data/src/main/java/com/coding/data/mapper/auth/PlatformUserMapper.java \
        platform-data/src/main/resources/mapper/auth/PlatformUserMapper.xml
git commit -m "feat(notify): 数据层 —— 渠道/绑定/投递三个 mapper + 按租户取用户"
```

---

## Task 3: `NotificationChannelService` —— 加密、校验、默认唯一、删除保护

**Files:**
- Create: `platform-api/src/main/java/com/coding/platformapi/services/NotificationChannelService.java`
- Create: `platform-api/src/main/java/com/coding/platformapi/models/NotificationChannelRequest.java`
- Modify: `platform-common/src/main/java/com/coding/common/exception/EnumResponseType.java`（10046 / 10047）
- Test: `platform-api/src/test/java/com/coding/platformapi/services/NotificationChannelServiceTest.java`

**Interfaces:**
- Consumes: `NotificationChannelMapper`、`RuleNotifyMapper`、`AESUtils`
- Produces:
  - `NotificationChannelService.list() → List<NotificationChannel>`（`config` 中的密码已脱敏）
  - `create(req) / update(code, req) / delete(code)`
  - `options(tenantId) → List<NotificationChannel>`
  - `resolve(code) → NotificationChannel | null`（发送时用，不解密）
  - `defaultFor(tenantId) → NotificationChannel | null`
  - `decryptedConfig(channel) → Map<String,String>`（发送时用）
  - `EnumResponseType.NOTIFY_CHANNEL_INVALID(10046)`、`NOTIFY_CHANNEL_IN_USE(10047)`

- [ ] **Step 1: 写失败的测试**

```java
package com.coding.platformapi.services;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.utils.AESUtils;
import com.coding.data.mapper.alert.NotificationChannelMapper;
import com.coding.data.mapper.alert.RuleNotifyMapper;
import com.coding.data.models.alert.NotificationChannel;
import com.coding.platformapi.models.NotificationChannelRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationChannelServiceTest {

    private static final String KEY = "test-master-key";

    private NotificationChannelMapper mapper;
    private RuleNotifyMapper bindings;
    private NotificationChannelService svc;

    @BeforeEach
    void setUp() {
        mapper = mock(NotificationChannelMapper.class);
        bindings = mock(RuleNotifyMapper.class);
        svc = new NotificationChannelService(mapper, bindings, KEY);
    }

    private static NotificationChannelRequest req(String code, String name) {
        NotificationChannelRequest r = new NotificationChannelRequest();
        r.setCode(code);
        r.setName(name);
        r.setType("EMAIL");
        r.setTenantId(null);
        r.setEnabled(true);
        r.setConfig(new java.util.LinkedHashMap<>(java.util.Map.of(
                "host", "smtp.example.com", "port", "465", "from", "a@example.com",
                "username", "a@example.com", "password", "secret")));
        return r;
    }

    /** code 必须是 K8s label 合法值（它会被写进 label）。 */
    @Test
    void an_invalid_code_is_rejected() {
        assertThatThrownBy(() -> svc.create(req("运维邮件", "运维")))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("code");
    }

    /** 密码必须加密后再落库，且列表返回时脱敏。 */
    @Test
    void the_password_is_encrypted_at_rest_and_masked_on_read() {
        svc.create(req("ops-mail", "运维邮件"));

        ArgumentCaptor<NotificationChannel> cap = ArgumentCaptor.forClass(NotificationChannel.class);
        verify(mapper).insert(cap.capture());
        String config = cap.getValue().getConfig();
        assertThat(config).doesNotContain("secret");
        assertThat(config).contains("passwordEnc");

        NotificationChannel stored = cap.getValue();
        when(mapper.selectAll()).thenReturn(List.of(stored));
        assertThat(svc.list().get(0).getConfig()).doesNotContain("passwordEnc");
    }

    /** 设成默认时，先把同租户维度（null=全局）的旧默认清掉。 */
    @Test
    void setting_default_clears_the_previous_default_in_the_same_scope() {
        NotificationChannelRequest r = req("ops-mail", "运维邮件");
        r.setDefault(true);

        svc.create(r);

        verify(mapper).clearDefault(null);
    }

    /** 有规则绑定时禁止删除。 */
    @Test
    void a_channel_that_is_still_bound_cannot_be_deleted() {
        when(bindings.countByChannel("ops-mail")).thenReturn(2);

        assertThatThrownBy(() -> svc.delete("ops-mail"))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("仍被");

        verify(mapper, never()).deleteByCode(any());
    }

    /** 默认渠道回落：租户默认优先，其次全局默认，都没有返回 null。 */
    @Test
    void default_falls_back_from_tenant_to_global() {
        NotificationChannel tenantDefault = new NotificationChannel();
        tenantDefault.setCode("t1-mail");
        when(mapper.selectTenantDefault("t1")).thenReturn(tenantDefault);

        assertThat(svc.defaultFor("t1").getCode()).isEqualTo("t1-mail");

        when(mapper.selectTenantDefault("t2")).thenReturn(null);
        NotificationChannel global = new NotificationChannel();
        global.setCode("global-mail");
        when(mapper.selectGlobalDefault()).thenReturn(global);

        assertThat(svc.defaultFor("t2").getCode()).isEqualTo("global-mail");

        when(mapper.selectGlobalDefault()).thenReturn(null);
        assertThat(svc.defaultFor("t2")).isNull();
    }

    /** 解出来的配置里，password 是明文（发送时用），且不含 passwordEnc。 */
    @Test
    void decrypted_config_exposes_the_plain_password_for_sending() {
        NotificationChannel ch = new NotificationChannel();
        ch.setCode("ops-mail");
        ch.setConfig("{\"host\":\"smtp.example.com\",\"passwordEnc\":\""
                + AESUtils.encrypt("secret", KEY) + "\"}");

        assertThat(svc.decryptedConfig(ch))
                .containsEntry("host", "smtp.example.com")
                .containsEntry("password", "secret")
                .doesNotContainKey("passwordEnc");
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -pl platform-api -am test -Dtest=NotificationChannelServiceTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 编译失败（`NotificationChannelService` / `NotificationChannelRequest` / 错误码不存在）

- [ ] **Step 3: 加错误码**

`platform-common/.../EnumResponseType.java`：在 `PROMETHEUS_RULE_INVALID(10045)` 之后加

```java
    /** 通知方式参数非法（code 不合法 / 类型不支持 / 配置缺字段） */
    NOTIFY_CHANNEL_INVALID(10046, "通知方式参数非法"),

    /** 通知方式仍被规则绑定，不能删除 */
    NOTIFY_CHANNEL_IN_USE(10047, "该通知方式仍被规则使用，不能删除"),
```

- [ ] **Step 4: 写请求模型与 service**

`NotificationChannelRequest`：

```java
package com.coding.platformapi.models;

import lombok.Data;
import java.util.Map;

/** 通知方式的新建/修改请求。config 里密码是**明文**（service 负责加密）。 */
@Data
public class NotificationChannelRequest {
    private String code;
    private String name;
    /** EMAIL（本轮唯一） */
    private String type;
    private Map<String, String> config;
    /** null = 全局 */
    private String tenantId;
    private Boolean isDefault;
    private Boolean enabled;
}
```

`NotificationChannelService`（要点：code 校验、加密、脱敏、默认唯一、删除保护、解密）：

```java
package com.coding.platformapi.services;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.utils.AESUtils;
import com.coding.data.mapper.alert.NotificationChannelMapper;
import com.coding.data.mapper.alert.RuleNotifyMapper;
import com.coding.data.models.alert.NotificationChannel;
import com.coding.platformapi.models.NotificationChannelRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

@Slf4j
@Service
public class NotificationChannelService {

    /** K8s label 值语法；code 会被写进 label */
    private static final Pattern CODE = Pattern.compile("[A-Za-z0-9]([-A-Za-z0-9_.]*[A-Za-z0-9])?");
    private static final List<String> SUPPORTED_TYPES = List.of("EMAIL");

    private final NotificationChannelMapper mapper;
    private final RuleNotifyMapper bindings;
    private final String aesKey;
    private final JsonMapper json = JsonMapper.builder().build();

    public NotificationChannelService(NotificationChannelMapper mapper,
                                      RuleNotifyMapper bindings,
                                      @Value("${platform.credential.aes-key}") String aesKey) {
        this.mapper = mapper;
        this.bindings = bindings;
        this.aesKey = aesKey;
    }

    public List<NotificationChannel> list() {
        return mapper.selectAll().stream().map(this::masked).toList();
    }

    public List<NotificationChannel> options(String tenantId) {
        return mapper.selectAvailable(tenantId).stream().map(this::masked).toList();
    }

    public NotificationChannel create(NotificationChannelRequest req) {
        validate(req);
        if (mapper.selectByCode(req.getCode()) != null) {
            throw new CloudPlatformException(EnumResponseType.NOTIFY_CHANNEL_INVALID, "code 已存在");
        }
        if (Boolean.TRUE.equals(req.getIsDefault())) {
            mapper.clearDefault(req.getTenantId());
        }
        NotificationChannel c = toEntity(req, UUID.randomUUID().toString().replace("-", ""));
        Date now = new Date();
        c.setCreatedAt(now);
        c.setUpdatedAt(now);
        mapper.insert(c);
        return masked(c);
    }

    public NotificationChannel update(String code, NotificationChannelRequest req) {
        NotificationChannel existing = mapper.selectByCode(code);
        if (existing == null) {
            throw new CloudPlatformException(EnumResponseType.NOTIFY_CHANNEL_INVALID, "通知方式不存在: " + code);
        }
        req.setCode(code);                       // code 不可改
        validate(req);
        if (Boolean.TRUE.equals(req.getIsDefault())) {
            mapper.clearDefault(req.getTenantId());
        }
        NotificationChannel c = toEntity(req, existing.getId());
        c.setUpdatedAt(new Date());
        mapper.updateByCode(c);
        return masked(c);
    }

    public void delete(String code) {
        int used = bindings.countByChannel(code);
        if (used > 0) {
            throw new CloudPlatformException(EnumResponseType.NOTIFY_CHANNEL_IN_USE,
                    "该通知方式仍被 " + used + " 条规则使用，不能删除");
        }
        mapper.deleteByCode(code);
    }

    /** 发送时按 code 取渠道（不解密）。 */
    public NotificationChannel resolve(String code) {
        return StringUtils.hasText(code) ? mapper.selectByCode(code) : null;
    }

    /** 未绑定时的默认渠道：租户默认 → 全局默认。 */
    public NotificationChannel defaultFor(String tenantId) {
        if (StringUtils.hasText(tenantId)) {
            NotificationChannel c = mapper.selectTenantDefault(tenantId);
            if (c != null) return c;
        }
        return mapper.selectGlobalDefault();
    }

    /** 发送用：把 passwordEnc 解成 password，去掉 passwordEnc。 */
    public Map<String, String> decryptedConfig(NotificationChannel c) {
        Map<String, String> cfg = parse(c.getConfig());
        String enc = cfg.remove("passwordEnc");
        if (StringUtils.hasText(enc)) {
            cfg.put("password", AESUtils.decrypt(enc, aesKey));
        }
        return cfg;
    }

    // ---------- 内部 ----------

    private void validate(NotificationChannelRequest req) {
        if (!StringUtils.hasText(req.getCode()) || !CODE.matcher(req.getCode()).matches()
                || req.getCode().length() > 63) {
            throw new CloudPlatformException(EnumResponseType.NOTIFY_CHANNEL_INVALID,
                    "code 必须是 K8s label 合法值（字母数字 - _ .，≤63），如 ops-mail");
        }
        if (!StringUtils.hasText(req.getName())) {
            throw new CloudPlatformException(EnumResponseType.NOTIFY_CHANNEL_INVALID, "名称必填");
        }
        if (!SUPPORTED_TYPES.contains(req.getType())) {
            throw new CloudPlatformException(EnumResponseType.NOTIFY_CHANNEL_INVALID,
                    "暂不支持的通知类型: " + req.getType());
        }
        Map<String, String> cfg = req.getConfig() == null ? Map.of() : req.getConfig();
        for (String key : List.of("host", "port", "from")) {
            if (!StringUtils.hasText(cfg.get(key))) {
                throw new CloudPlatformException(EnumResponseType.NOTIFY_CHANNEL_INVALID,
                        "EMAIL 配置缺少 " + key);
            }
        }
    }

    private NotificationChannel toEntity(NotificationChannelRequest req, String id) {
        Map<String, String> cfg = new LinkedHashMap<>(req.getConfig() == null ? Map.of() : req.getConfig());
        String password = cfg.remove("password");
        if (StringUtils.hasText(password)) {
            cfg.put("passwordEnc", AESUtils.encrypt(password, aesKey));
        }
        NotificationChannel c = new NotificationChannel();
        c.setId(id);
        c.setCode(req.getCode());
        c.setName(req.getName());
        c.setType(req.getType());
        c.setConfig(json.writeValueAsString(cfg));
        c.setTenantId(req.getTenantId());
        c.setIsDefault(Boolean.TRUE.equals(req.getIsDefault()));
        c.setEnabled(req.getEnabled() == null || req.getEnabled());
        return c;
    }

    /** 列表/详情不回密码。 */
    private NotificationChannel masked(NotificationChannel c) {
        Map<String, String> cfg = parse(c.getConfig());
        if (cfg.containsKey("passwordEnc")) {
            cfg.put("passwordEnc", "******");
            c.setConfig(json.writeValueAsString(cfg));
        }
        return c;
    }

    private Map<String, String> parse(String s) {
        if (!StringUtils.hasText(s)) return new LinkedHashMap<>();
        return json.readValue(s, new TypeReference<LinkedHashMap<String, String>>() {});
    }
}
```

- [ ] **Step 5: 跑测试确认通过**

Run: `mvn -pl platform-api -am test -Dtest=NotificationChannelServiceTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS（6/6）

- [ ] **Step 6: 错误码唯一性守卫**

Run: `mvn -pl platform-common -am test`
Expected: PASS

- [ ] **Step 7: 提交**

```bash
git add platform-api/src/main/java/com/coding/platformapi/services/NotificationChannelService.java \
        platform-api/src/main/java/com/coding/platformapi/models/NotificationChannelRequest.java \
        platform-api/src/test/java/com/coding/platformapi/services/NotificationChannelServiceTest.java \
        platform-common/src/main/java/com/coding/common/exception/EnumResponseType.java
git commit -m "feat(notify): 通知方式 service —— code 校验、配置加密、默认唯一、删除保护"
```

---

## Task 4: platform-api 端点 —— 渠道 CRUD + 候选 + 本租户用户

**Files:**
- Create: `platform-api/src/main/java/com/coding/platformapi/controllers/NotificationChannelController.java`
- Create: `platform-api/src/main/java/com/coding/platformapi/controllers/NotificationUserController.java`
- Create: `platform-api/src/main/java/com/coding/platformapi/models/NotificationUserView.java`
- Create: `platform-api/src/main/java/com/coding/platformapi/services/NotificationUserService.java`
- Modify: `platform-data/src/main/java/com/coding/data/mapper/auth/PlatformUserTenantRoleMapper.java` + XML（按租户取用户 id）
- Test: `platform-api/src/test/java/com/coding/platformapi/services/NotificationUserServiceTest.java`

**Interfaces:**
- Consumes: `NotificationChannelService`（Task 3）
- Produces:
  - HTTP：`POST /notification-channels/list`、`POST /notification-channels`、
    `PUT /notification-channels/{code}`、`DELETE /notification-channels/{code}`、
    `POST /notification-channels/options`、`POST /notification-users/list`
  - `NotificationUserService#listByTenant(String tenantId) → List<NotificationUserView>`
  - `NotificationUserService#tenantAdminUserIds(String tenantId) → List<String>`
  - `NotificationUserService#emailsOf(Collection<String> userIds) → List<String>`（**过滤掉空邮箱**，投递引擎用）
  - `NotificationUserView(id, displayName, email)`

- [ ] **Step 1: 写失败的测试**

```java
package com.coding.platformapi.services;

import com.coding.data.mapper.auth.PlatformUserMapper;
import com.coding.data.mapper.auth.PlatformUserTenantMapper;
import com.coding.data.mapper.auth.PlatformUserTenantRoleMapper;
import com.coding.data.models.auth.PlatformUser;
import com.coding.data.models.auth.PlatformUserTenant;
import com.coding.data.models.auth.PlatformUserTenantRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NotificationUserServiceTest {

    private PlatformUserTenantMapper members;
    private PlatformUserTenantRoleMapper roles;
    private PlatformUserMapper users;
    private NotificationUserService svc;

    @BeforeEach
    void setUp() {
        members = mock(PlatformUserTenantMapper.class);
        roles = mock(PlatformUserTenantRoleMapper.class);
        users = mock(PlatformUserMapper.class);
        svc = new NotificationUserService(members, roles, users);
    }

    private static PlatformUser user(String id, String name, String email) {
        PlatformUser u = new PlatformUser();
        u.setId(id);
        u.setDisplayName(name);
        u.setEmail(email);
        return u;
    }

    /** 只返回该租户的成员，且带上邮箱（卡片里要显示）。 */
    @Test
    void list_returns_the_tenant_members_with_email() {
        PlatformUserTenant m1 = new PlatformUserTenant();
        m1.setUserId("u1");
        PlatformUserTenant m2 = new PlatformUserTenant();
        m2.setUserId("u2");
        when(members.selectByTenantId("t1")).thenReturn(List.of(m1, m2));
        when(users.selectByIds(List.of("u1", "u2")))
                .thenReturn(List.of(user("u1", "张三", "z@x.com"), user("u2", "李四", null)));

        var out = svc.listByTenant("t1");

        assertThat(out).extracting(NotificationUserView::id).containsExactly("u1", "u2");
        assertThat(out.get(0).email()).isEqualTo("z@x.com");
        assertThat(out.get(1).email()).isNull();
    }

    /** 租户没有成员时返回空，不报错。 */
    @Test
    void an_empty_tenant_yields_an_empty_list() {
        when(members.selectByTenantId("t2")).thenReturn(List.of());
        assertThat(svc.listByTenant("t2")).isEmpty();
    }

    /** 取租户的管理员用户 id（默认收件人用）。 */
    @Test
    void tenant_admin_ids_are_resolved_by_role_code() {
        PlatformUserTenantRole r = new PlatformUserTenantRole();
        r.setUserId("u1");
        when(roles.selectUserIdsByTenantAndRoleCode("t1", "admin")).thenReturn(List.of("u1"));

        assertThat(svc.tenantAdminUserIds("t1")).containsExactly("u1");
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -pl platform-api -am test -Dtest=NotificationUserServiceTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 编译失败

- [ ] **Step 3: 加 mapper 方法**

`PlatformUserMapper`（+ XML）：

```java
List<PlatformUser> selectByIds(@Param("ids") List<String> ids);
```

```xml
<select id="selectByIds" resultMap="BaseResultMap">
  select <include refid="Base_Column_List" /> from platform_user
  where deleted_at is null and id in
  <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
</select>
```

`PlatformUserTenantRoleMapper`（+ XML）：

```java
/** 该租户下持指定角色 code 的用户 id（角色表 join） */
List<String> selectUserIdsByTenantAndRoleCode(@Param("tenantId") String tenantId,
                                              @Param("roleCode") String roleCode);
```

```xml
<select id="selectUserIdsByTenantAndRoleCode" resultType="java.lang.String">
  select distinct utr.user_id from platform_user_tenant_role utr
  join platform_role r on r.id = utr.role_id
  where utr.tenant_id = #{tenantId,jdbcType=VARCHAR}
    and r.code = #{roleCode,jdbcType=VARCHAR}
</select>
```

> ⚠️ 先打开 `PlatformRole` / `PlatformRoleMapper` 确认列名（`code` 还是 `role_code`、角色表是否软删），按实际写。

- [ ] **Step 4: 写 view / service / controllers**

```java
package com.coding.platformapi.models;

/** 规则编辑页的收件人候选。email 可能为空（用户没填） */
public record NotificationUserView(String id, String displayName, String email) {}
```

```java
@Service
public class NotificationUserService {
    private final PlatformUserTenantMapper members;
    private final PlatformUserTenantRoleMapper roles;
    private final PlatformUserMapper users;

    public NotificationUserService(PlatformUserTenantMapper members,
                                   PlatformUserTenantRoleMapper roles,
                                   PlatformUserMapper users) { ... }

    public List<NotificationUserView> listByTenant(String tenantId) {
        List<String> ids = members.selectByTenantId(tenantId).stream()
                .map(PlatformUserTenant::getUserId).distinct().toList();
        if (ids.isEmpty()) return List.of();
        Map<String, PlatformUser> byId = users.selectByIds(ids).stream()
                .collect(Collectors.toMap(PlatformUser::getId, u -> u));
        return ids.stream().filter(byId::containsKey)
                .map(id -> new NotificationUserView(id,
                        byId.get(id).getDisplayName(), byId.get(id).getEmail()))
                .toList();
    }

    public List<String> tenantAdminUserIds(String tenantId) {
        return roles.selectUserIdsByTenantAndRoleCode(tenantId, "admin");
    }

    /** 投递用：id → 邮箱，**过滤空邮箱**并去重保序。 */
    public List<String> emailsOf(java.util.Collection<String> userIds) {
        if (userIds == null || userIds.isEmpty()) return List.of();
        return users.selectByIds(List.copyOf(userIds)).stream()
                .map(PlatformUser::getEmail)
                .filter(org.springframework.util.StringUtils::hasText)
                .distinct()
                .toList();
    }
}
```

`NotificationChannelController`：六个端点，直接调 `NotificationChannelService`，用 `ResponseData` 包装。
`options` 的租户从 `AuthContext` 取（token tenantInfo 优先，管理员代管用参数？—— 规则编辑页是租户上下文，直接取 token tenantId；管理员平台视图下传 `tenantId` 查询参数作为回退，缺省时用全局候选）。

```java
@PostMapping("/options")
public ResponseData<List<NotificationChannel>> options(@RequestBody(required = false) OptionsReq req) {
    String tenantId = authContext.currentTenantId();      // 见下
    if (!StringUtils.hasText(tenantId) && req != null) tenantId = req.getTenantId();
    return new ResponseData<>(channelService.options(tenantId));
}
```

`AuthContext` 增加：

```java
/** token 里的租户 id；平台视图返回 null */
public String currentTenantId() {
    TokenUserInfo info = current();
    return info != null && info.getTenantInfo() != null ? info.getTenantInfo().getTenantId() : null;
}
```

`NotificationUserController`：

```java
@PostMapping("/list")
public ResponseData<List<NotificationUserView>> list(@RequestBody(required = false) TenantRef req) {
    String tenantId = authContext.currentTenantId();
    if (!StringUtils.hasText(tenantId) && req != null) tenantId = req.getTenantId();
    if (!StringUtils.hasText(tenantId)) {
        throw new CloudPlatformException(EnumResponseType.NOTIFY_CHANNEL_INVALID, "缺少租户上下文");
    }
    return new ResponseData<>(userService.listByTenant(tenantId));
}
```

- [ ] **Step 5: 跑测试 + 启动校验**

Run: `mvn -pl platform-api -am test -Dtest=NotificationUserServiceTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS（3/3）

启动一次，确认 `[RBAC] 交叉校验通过`，且 Task 1 的 5 条幽灵行消失。

- [ ] **Step 6: 提交**

```bash
git add platform-api/src/main/java/com/coding/platformapi/controllers/NotificationChannelController.java \
        platform-api/src/main/java/com/coding/platformapi/controllers/NotificationUserController.java \
        platform-api/src/main/java/com/coding/platformapi/models/NotificationUserView.java \
        platform-api/src/main/java/com/coding/platformapi/services/NotificationUserService.java \
        platform-api/src/main/java/com/coding/platformapi/security/AuthContext.java \
        platform-api/src/test/java/com/coding/platformapi/services/NotificationUserServiceTest.java \
        platform-data/src/main/java/com/coding/data/mapper/auth/PlatformUserMapper.java \
        platform-data/src/main/resources/mapper/auth/PlatformUserMapper.xml \
        platform-data/src/main/java/com/coding/data/mapper/auth/PlatformUserTenantRoleMapper.java \
        platform-data/src/main/resources/mapper/auth/PlatformUserTenantRoleMapper.xml
git commit -m "feat(notify): 通知方式端点 + 本租户用户候选"
```

---

## Task 5: 前端 —— 通知方式页面

**Files:**
- Modify: `platform-web/src/types.ts`（`NotificationChannel` / `NotificationChannelRequest`）
- Modify: `platform-web/src/api/index.ts`（`notificationChannelApi`）
- Modify: `platform-web/src/pageCodes.ts`（`notifyChannel`）
- Modify: `platform-web/src/router/index.ts`（一条路由）
- Modify: `platform-web/src/layouts/MainLayout.vue`（菜单项，**仅管理员**）
- Create: `platform-web/src/views/NotificationChannelView.vue`

**Interfaces:**
- Consumes: Task 4 的端点
- Produces: 路由 `notification-channels`；`notificationChannelApi`

- [ ] **Step 1: 类型与 api**

```ts
/** 通知方式（平台实体）。config 里的密码已被后端脱敏 */
export interface NotificationChannel {
  id: string
  code: string
  name: string
  /** EMAIL（本轮唯一） */
  type: string
  /** JSON 串；passwordEnc 被脱敏成 ****** */
  config?: string | null
  tenantId?: string | null
  isDefault?: boolean
  enabled?: boolean
  createdAt?: string | null
  updatedAt?: string | null
}

export interface NotificationChannelRequest {
  code: string
  name: string
  type: string
  config: Record<string, string>
  tenantId?: string | null
  isDefault?: boolean
  enabled?: boolean
}
```

```ts
/** 通知方式（管理员维护） */
export const notificationChannelApi = {
  list: () => http.post<never, NotificationChannel[]>('/notification-channels/list', {}),
  create: (body: NotificationChannelRequest) => http.post<never, NotificationChannel>('/notification-channels', body),
  update: (code: string, body: NotificationChannelRequest) =>
    http.put<never, NotificationChannel>(`/notification-channels/${encodeURIComponent(code)}`, body),
  remove: (code: string) => http.delete<never, void>(`/notification-channels/${encodeURIComponent(code)}`),
  options: (tenantId?: string | null) =>
    http.post<never, NotificationChannel[]>('/notification-channels/options', { tenantId: tenantId ?? null }),
}
```

- [ ] **Step 2: 页面码 / 路由 / 菜单**

`pageCodes.ts`：

```ts
  /** 通知方式（平台管理员专属页面） */
  notifyChannel: 'platform:page:notifychannel',
```

`router/index.ts`（放在「监控告警」组，`alerts` 之后）：

```ts
        { path: 'notification-channels', name: 'notification-channels', component: () => import('@/views/NotificationChannelView.vue'), meta: { title: '通知方式', group: '监控告警', context: 'full', requiresPerm: [pageCodes.notifyChannel] } },
```

`MainLayout.vue`（「监控告警」组末尾；组的分隔条件一并加上 `can(pageCodes.notifyChannel)`）：

```vue
        <el-menu-item v-if="can(pageCodes.notifyChannel)" index="/notification-channels">
          <el-icon><Message /></el-icon>
          <template #title>通知方式</template>
        </el-menu-item>
```

- [ ] **Step 3: 页面**

结构：`PageHeader`（标题「通知方式」+ 新建按钮）→ 表格（名称 / code / 类型 / 租户范围 / 默认 / 启停 / 操作）
→ 抽屉表单（名称、code、类型=EMAIL、SMTP host/port/from/username/password、租户范围、默认、启停）。

```vue
<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { notificationChannelApi } from '@/api'
import type { NotificationChannel, NotificationChannelRequest } from '@/types'
import PageHeader from '@/components/PageHeader.vue'
import EmptyState from '@/components/EmptyState.vue'

const rows = ref<NotificationChannel[]>([])
const loading = ref(false)
const drawer = ref(false)
const editing = ref<string | null>(null)          // code；null = 新建
const saving = ref(false)

function emptyForm(): NotificationChannelRequest {
  return {
    code: '', name: '', type: 'EMAIL', tenantId: null, isDefault: false, enabled: true,
    config: { host: '', port: '465', from: '', username: '', password: '' },
  }
}
const form = reactive<NotificationChannelRequest>(emptyForm())

async function refresh(): Promise<void> {
  loading.value = true
  try { rows.value = await notificationChannelApi.list() } catch { rows.value = [] }
  finally { loading.value = false }
}

function openCreate(): void {
  editing.value = null
  Object.assign(form, emptyForm())
  drawer.value = true
}

function openEdit(row: NotificationChannel): void {
  editing.value = row.code
  const cfg = (row.config ? JSON.parse(row.config) : {}) as Record<string, string>
  Object.assign(form, {
    code: row.code, name: row.name, type: row.type,
    tenantId: row.tenantId ?? null, isDefault: !!row.isDefault, enabled: row.enabled !== false,
    config: {
      host: cfg.host ?? '', port: cfg.port ?? '465', from: cfg.from ?? '',
      username: cfg.username ?? '', password: '',          // 密码不回填
    },
  })
  drawer.value = true
}

async function submit(): Promise<void> {
  saving.value = true
  try {
    const body: NotificationChannelRequest = { ...form, config: { ...form.config } }
    if (!body.config.password) delete body.config.password   // 留空 = 不修改
    if (editing.value) await notificationChannelApi.update(editing.value, body)
    else await notificationChannelApi.create(body)
    ElMessage.success(editing.value ? '已保存' : '已创建')
    drawer.value = false
    await refresh()
  } catch { /* 拦截器已提示 */ } finally { saving.value = false }
}

async function onDelete(row: NotificationChannel): Promise<void> {
  try {
    await ElMessageBox.confirm(`确认删除通知方式「${row.name}」？`, '提示', { type: 'warning' })
  } catch { return }
  try {
    await notificationChannelApi.remove(row.code)
    ElMessage.success('已删除')
    await refresh()
  } catch { /* 拦截器已提示 */ }
}

onMounted(refresh)
</script>

<template>
  <div>
    <PageHeader title="通知方式" description="告警通知的发送渠道（当前支持邮件）">
      <el-button @click="refresh">刷新</el-button>
      <el-button type="primary" @click="openCreate">新建通知方式</el-button>
    </PageHeader>

    <EmptyState
      v-if="rows.length === 0 && !loading"
      title="还没有通知方式"
      description="先建一个邮件渠道并设为默认；未绑定通知方式的规则会走它。"
    />

    <div v-else class="panel table-panel">
      <el-table v-loading="loading" :data="rows" stripe>
        <el-table-column prop="name" label="名称" min-width="160" />
        <el-table-column prop="code" label="code" width="180">
          <template #default="{ row }"><code class="res-name">{{ row.code }}</code></template>
        </el-table-column>
        <el-table-column prop="type" label="类型" width="100" />
        <el-table-column label="范围" width="140">
          <template #default="{ row }">{{ row.tenantId ? row.tenantId : '全局' }}</template>
        </el-table-column>
        <el-table-column label="默认" width="90">
          <template #default="{ row }">
            <el-tag v-if="row.isDefault" size="small" type="success" effect="plain">默认</el-tag>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column label="启用" width="90">
          <template #default="{ row }">{{ row.enabled === false ? '停用' : '启用' }}</template>
        </el-table-column>
        <el-table-column label="操作" width="140" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button link type="danger" @click="onDelete(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <el-drawer v-model="drawer" :title="editing ? `编辑 ${editing}` : '新建通知方式'" size="560px">
      <el-form label-width="120px" label-position="left">
        <el-form-item label="名称" required><el-input v-model="form.name" /></el-form-item>
        <el-form-item label="code" required>
          <el-input v-model="form.code" :disabled="!!editing"
                    placeholder="如 ops-mail（K8s label 合法，建后不可改）" />
        </el-form-item>
        <el-form-item label="类型"><el-input v-model="form.type" disabled /></el-form-item>
        <el-form-item label="SMTP host" required><el-input v-model="form.config.host" /></el-form-item>
        <el-form-item label="端口"><el-input v-model="form.config.port" /></el-form-item>
        <el-form-item label="发件人" required><el-input v-model="form.config.from" /></el-form-item>
        <el-form-item label="用户名"><el-input v-model="form.config.username" /></el-form-item>
        <el-form-item label="密码">
          <el-input v-model="form.config.password" type="password" show-password
                    :placeholder="editing ? '留空表示不修改' : ''" />
        </el-form-item>
        <el-form-item label="租户">
          <el-input v-model="form.tenantId" placeholder="留空 = 全局（所有租户可用）" />
        </el-form-item>
        <el-form-item label="设为默认">
          <el-switch v-model="form.isDefault" />
          <span class="form-tip">会清掉同范围（全局/该租户）的旧默认</span>
        </el-form-item>
        <el-form-item label="启用"><el-switch v-model="form.enabled" /></el-form-item>
      </el-form>
      <div class="form-actions">
        <el-button @click="drawer = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submit">保存</el-button>
      </div>
    </el-drawer>
  </div>
</template>
```

关键点：

- `code` 新建后**不可改**（编辑时禁用）；
- 密码框 placeholder：`留空表示不修改`（编辑时不回填）；
- 「设为默认」提交时提示「会清掉同范围（全局/本租户）的旧默认」；
- 删除前 `ElMessageBox.confirm`，失败（仍被规则使用）由拦截器提示。

测完 `npm run type-check`。

- [ ] **Step 4: 浏览器实测**

起 dev server，用管理员身份（有 `platform:page:notifychannel`）：
- 菜单出现「通知方式」，页面能进；
- 新建一条 `ops-mail`（EMAIL + SMTP 配置）→ 列表出现，密码列显示 `******`；
- 再建一条并勾默认 → 第一条的「默认」标记消失；
- 用租户管理员身份：菜单**不出现**，直连 `/notification-channels` 被守卫拦回。

- [ ] **Step 5: 提交**

```bash
git add platform-web/src/types.ts platform-web/src/api/index.ts platform-web/src/pageCodes.ts \
        platform-web/src/router/index.ts platform-web/src/layouts/MainLayout.vue \
        platform-web/src/views/NotificationChannelView.vue
git commit -m "feat(notify): 通知方式管理页（仅管理员）"
```

---

# 块 2 · 规则绑定

## Task 6: `RuleSourceResolver` —— 把「告警 → 来源 CR」抽成共用组件

**Files:**
- Create: `platform-api/src/main/java/com/coding/platformapi/services/RuleSourceResolver.java`
- Modify: `platform-api/src/main/java/com/coding/platformapi/services/AlertService.java`（改用 resolver）
- Test: `platform-api/src/test/java/com/coding/platformapi/services/RuleSourceResolverTest.java`

**Interfaces:**
- Consumes: `K8sClient`、`PrometheusRuleDTO`
- Produces:
  - `RuleSourceResolver#resolve(String clusterId, String tenantId, List<PlatformAlertEvent> rows) → Map<String, List<AlertSource>>`
    （key = `namespace + "\u0000" + alertname`，value = 去重后的来源）
  - `RuleSourceResolver#crNameOf(String clusterId, String tenantId, PlatformAlertEvent e) → String | null`
    （单条查询，内部复用同一套索引逻辑；投递引擎按它找绑定）
  - `RuleSourceResolver.key(String namespace, String alertname) → String`（static）

> 为什么抽出来：告警列表的「来源」列（已实现）与投递引擎（Task 11）都要做同一件事，
> 两处各写一份迟早会漂。

- [ ] **Step 1: 写失败的测试**

```java
package com.coding.platformapi.services;

import com.coding.common.models.k8s.dto.PrometheusRuleDTO;
import com.coding.data.models.k8s.AlertSource;
import com.coding.data.models.k8s.PlatformAlertEvent;
import com.coding.platformapi.k8s.K8sClient;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class RuleSourceResolverTest {

    private static PlatformAlertEvent event(String ns, String alert) {
        PlatformAlertEvent e = new PlatformAlertEvent();
        e.setNamespace(ns);
        e.setAlertname(alert);
        return e;
    }

    private static PrometheusRuleDTO cr(String ns, String name, String group, String alert) {
        PrometheusRuleDTO d = new PrometheusRuleDTO();
        d.setNamespace(ns);
        d.setName(name);
        PrometheusRuleDTO.Rule r = new PrometheusRuleDTO.Rule();
        r.setAlert(alert);
        PrometheusRuleDTO.Group g = new PrometheusRuleDTO.Group();
        g.setName(group);
        g.setRules(List.of(r));
        d.setGroups(List.of(g));
        return d;
    }

    @Test
    void resolves_by_namespace_and_alertname() {
        K8sClient k8s = mock(K8sClient.class);
        RuleSourceResolver resolver = new RuleSourceResolver(k8s);
        doReturn(List.of(cr("mon-app", "app-rules", "app", "HighCPU"))).when(k8s).list(any());

        var map = resolver.resolve("c1", "t1", List.of(event("mon-app", "HighCPU")));

        assertThat(map.get("mon-app\u0000HighCPU"))
                .containsExactly(new AlertSource("app-rules", "app"));
    }

    /** 同一 CR/组重复定义同名规则时去重。 */
    @Test
    void duplicate_definitions_are_collapsed() {
        K8sClient k8s = mock(K8sClient.class);
        RuleSourceResolver resolver = new RuleSourceResolver(k8s);
        PrometheusRuleDTO d = cr("ns", "cr", "g", "A");
        PrometheusRuleDTO.Rule again = new PrometheusRuleDTO.Rule();
        again.setAlert("A");
        d.getGroups().get(0).setRules(List.of(again, again));
        doReturn(List.of(d)).when(k8s).list(any());

        assertThat(resolver.resolve("c1", "t1", List.of(event("ns", "A")))
                .get("ns\u0000A")).hasSize(1);
    }

    /** 管理员（tenantId 为空）走 listAll；租户走按 namespace list。 */
    @Test
    void admin_uses_list_all_and_tenant_uses_namespace_list() {
        K8sClient k8s = mock(K8sClient.class);
        RuleSourceResolver resolver = new RuleSourceResolver(k8s);
        doReturn(List.of()).when(k8s).listAll(any());
        doReturn(List.of()).when(k8s).list(any());

        resolver.resolve("c1", null, List.of(event("ns", "A")));
        verify(k8s).listAll(any());
        verify(k8s, never()).list(any());

        resolver.resolve("c1", "t1", List.of(event("ns", "A")));
        verify(k8s).list(any());
    }
}
```

- [ ] **Step 2: 跑测试确认失败**（类不存在）

- [ ] **Step 3: 写 resolver，并把 AlertService 改成用它**

`RuleSourceResolver`：

```java
@Component
public class RuleSourceResolver {
    private final K8sClient k8s;

    public Map<String, List<AlertSource>> resolve(String clusterId, String tenantId, List<PlatformAlertEvent> rows) {
        if (rows == null || rows.isEmpty() || !StringUtils.hasText(clusterId)) return Map.of();
        List<PrometheusRuleDTO> rules;
        try {
            rules = loadRules(rows, clusterId, tenantId);
        } catch (Exception e) {
            log.warn("反查告警来源失败（cluster={}）: {}", clusterId, e.getMessage());
            return Map.of();
        }
        Map<String, List<AlertSource>> index = new HashMap<>();
        for (PrometheusRuleDTO cr : rules) { ... }   // 与 AlertService 原实现一致
        return index;
    }
    // loadRules / sourceKey 与 AlertService 原实现一致

    /** 单条查询：这条告警来自哪个 CR（找不到返回 null）。 */
    public String crNameOf(String clusterId, String tenantId, PlatformAlertEvent e) {
        List<AlertSource> hits = resolve(clusterId, tenantId, List.of(e))
                .get(key(e.getNamespace(), e.getAlertname()));
        return hits == null || hits.isEmpty() ? null : hits.get(0).cr();
    }

    public static String key(String namespace, String alertname) {
        return (namespace == null ? "" : namespace) + "\u0000" + (alertname == null ? "" : alertname);
    }
}
```

`AlertService.attachSources` 改为：

```java
private void attachSources(List<PlatformAlertEvent> rows, String clusterId, String tokenTenantId) {
    Map<String, List<AlertSource>> index = ruleSourceResolver.resolve(clusterId, tokenTenantId, rows);
    for (PlatformAlertEvent row : rows) {
        List<AlertSource> hits = index.get(RuleSourceResolver.key(row.getNamespace(), row.getAlertname()));
        row.setSources(hits == null ? List.of() : hits);
    }
}
```

`AlertService` 构造器把 `K8sClient` 换成 `RuleSourceResolver`（两处构造器 + 两处测试同步）。

- [ ] **Step 4: 跑测试**

Run: `mvn -pl platform-api -am test -Dtest='RuleSourceResolverTest,AlertServiceTest,AlertSelfTestTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 全 PASS（原 AlertService 的来源反查用例仍然绿）

- [ ] **Step 5: 提交**

```bash
git add platform-api/src/main/java/com/coding/platformapi/services/RuleSourceResolver.java \
        platform-api/src/main/java/com/coding/platformapi/services/AlertService.java \
        platform-api/src/test/java/com/coding/platformapi/services/RuleSourceResolverTest.java \
        platform-api/src/test/java/com/coding/platformapi/services/AlertServiceTest.java \
        platform-api/src/test/java/com/coding/platformapi/services/AlertSelfTestTest.java
git commit -m "refactor(notify): 抽出 RuleSourceResolver（告警 → 来源 CR），投递引擎复用"
```

---

## Task 7: 规则保存时写绑定 + 代打 `platform_notify` label

**Files:**
- Modify: `platform-common/src/main/java/com/coding/common/models/k8s/dto/PrometheusRuleDTO.java`（两个平台侧字段）
- Modify: `platform-api/src/main/java/com/coding/platformapi/services/PrometheusRuleService.java`
- Create: `platform-api/src/main/java/com/coding/platformapi/models/RuleNotifyBinder.java`（绑定写入 + 校验）
- Test: `platform-api/src/test/java/com/coding/platformapi/services/RuleNotifyBinderTest.java`
- Test: `PrometheusRuleServiceTest` 增补用例

**Interfaces:**
- Consumes: `NotificationChannelService`（Task 3）、`RuleNotifyMapper`（Task 2）
- Produces:
  - `RuleNotifyBinder#bind(PrometheusRuleDTO body) → void`（校验渠道、写表）
  - `PrometheusRuleService` 的 create/update 在 `stampNamespace` 之后调用 `bind`

- [ ] **Step 1: 写失败的测试**

```java
package com.coding.platformapi.services;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.models.k8s.dto.PrometheusRuleDTO;
import com.coding.data.mapper.alert.RuleNotifyMapper;
import com.coding.data.models.alert.NotificationChannel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class RuleNotifyBinderTest {

    private NotificationChannelService channels;
    private RuleNotifyMapper mapper;
    private RuleNotifyBinder binder;

    @BeforeEach
    void setUp() {
        channels = mock(NotificationChannelService.class);
        mapper = mock(RuleNotifyMapper.class);
        binder = new RuleNotifyBinder(channels, mapper);
    }

    private static PrometheusRuleDTO rule(String ns, String name, String channel, List<String> users) {
        PrometheusRuleDTO d = new PrometheusRuleDTO();
        d.setClusterId("c1");
        d.setTenantId("t1");
        d.setNamespace(ns);
        d.setName(name);
        d.setNotifyChannel(channel);
        d.setNotifyUserIds(users);
        return d;
    }

    /** 写了渠道 → upsert 一行绑定，user_ids 是 JSON。 */
    @Test
    void a_selected_channel_is_persisted() {
        NotificationChannel c = new NotificationChannel();
        c.setCode("ops-mail");
        when(channels.resolve("ops-mail")).thenReturn(c);

        binder.bind(rule("mon-app", "app-rules", "ops-mail", List.of("u1", "u2")));

        ArgumentCaptor<com.coding.data.models.alert.RuleNotify> cap =
                ArgumentCaptor.forClass(com.coding.data.models.alert.RuleNotify.class);
        verify(mapper).upsert(cap.capture());
        assertThat(cap.getValue().getChannelCode()).isEqualTo("ops-mail");
        assertThat(cap.getValue().getUserIds()).contains("u1").contains("u2");
    }

    /** 渠道不存在 → 拒绝（不能把规则存进一个没人认识的 code）。 */
    @Test
    void an_unknown_channel_is_rejected() {
        when(channels.resolve("nope")).thenReturn(null);

        assertThatThrownBy(() -> binder.bind(rule("ns", "r", "nope", List.of())))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("通知方式");

        verify(mapper, never()).upsert(any());
    }

    /** 没选渠道 → 不写绑定（历史行为：走默认）。 */
    @Test
    void an_empty_channel_writes_nothing() {
        binder.bind(rule("ns", "r", null, List.of()));

        verifyNoInteractions(mapper);
    }
}
```

并在 `PrometheusRuleServiceTest` 增补：

```java
/** 保存时给每条 alert 规则代打 platform_notify；record 规则不打。 */
@Test
void create_stamps_the_notify_label_on_alert_rules_only() {
    PrometheusRuleDTO dto = sample("my-app");
    dto.setNotifyChannel("ops-mail");
    PrometheusRuleDTO.Rule rec = new PrometheusRuleDTO.Rule();
    rec.setRecord("job:x:rate5m");
    rec.setExpr("vector(1)");
    dto.getGroups().get(0).setRules(List.of(dto.getGroups().get(0).getRules().get(0), rec));
    when(k8s.create(any())).thenAnswer(i -> i.getArgument(0));

    svc.create(dto);

    PrometheusRuleDTO sent = captured();
    assertThat(sent.getGroups().get(0).getRules().get(0).getLabels())
            .containsEntry("platform_notify", "ops-mail");
    assertThat(sent.getGroups().get(0).getRules().get(1).getLabels())
            .doesNotContainKey("platform_notify");
    // 平台侧字段不下发
    assertThat(sent.getNotifyChannel()).isNull();
    assertThat(sent.getNotifyUserIds()).isNull();
}
```

- [ ] **Step 2: 跑测试确认失败**

- [ ] **Step 3: DTO 加字段**

```java
    /**
     * 平台侧字段（**不是 K8s 对象的一部分**）：规则选中的通知方式 code。
     * converter 不映射；save 时由 RuleNotifyBinder 写绑定表，并由
     * PrometheusRuleService 代打 `labels.platform_notify` 后剥离。
     */
    private String notifyChannel;

    /** 平台侧字段：收件人用户 id 列表（只进绑定表，不下发） */
    private List<String> notifyUserIds;
```

- [ ] **Step 4: 写 binder**

```java
@Service
@RequiredArgsConstructor
public class RuleNotifyBinder {
    private final NotificationChannelService channels;
    private final RuleNotifyMapper mapper;
    private final JsonMapper json = JsonMapper.builder().build();

    public void bind(PrometheusRuleDTO body) {
        String code = body.getNotifyChannel();
        if (!StringUtils.hasText(code)) return;

        NotificationChannel channel = channels.resolve(code);
        if (channel == null) {
            throw new CloudPlatformException(EnumResponseType.NOTIFY_CHANNEL_INVALID,
                    "通知方式不存在: " + code);
        }
        RuleNotify r = new RuleNotify();
        r.setId(UUID.randomUUID().toString().replace("-", ""));
        r.setClusterId(body.getClusterId());
        r.setNamespace(body.getNamespace());
        r.setRuleName(body.getName());
        r.setChannelCode(code);
        r.setUserIds(json.writeValueAsString(
                body.getNotifyUserIds() == null ? List.of() : body.getNotifyUserIds()));
        Date now = new Date();
        r.setCreatedAt(now);
        r.setUpdatedAt(now);
        mapper.upsert(r);
    }
}
```

- [ ] **Step 5: 接进 `PrometheusRuleService`**

`stampNamespace` 里，`hasAlert` 分支改为：

```java
                if (hasAlert) {
                    Map<String, String> labels = r.getLabels() != null
                            ? new LinkedHashMap<>(r.getLabels()) : new LinkedHashMap<>();
                    labels.put("namespace", body.getNamespace());
                    // 通知方式：选了才写（没选=走默认，不能写空串）
                    if (StringUtils.hasText(body.getNotifyChannel())) {
                        labels.put("platform_notify", body.getNotifyChannel());
                    }
                    r.setLabels(labels);
                }
```

`create` / `update`：

```java
    public PrometheusRuleDTO create(PrometheusRuleDTO body) {
        stampNamespace(body);
        notifyBinder.bind(body);
        stripPlatformFields(body);
        return k8s.create(body);
    }
```

`stripPlatformFields`：

```java
    private void stripPlatformFields(PrometheusRuleDTO body) {
        body.setNotifyChannel(null);
        body.setNotifyUserIds(null);
    }
```

> ⚠️ `update` 同样要在**打 label 之前**拿到 `body.getNotifyChannel()`，顺序不能反。

- [ ] **Step 6: 跑测试**

Run: `mvn -pl platform-api -am test -Dtest='RuleNotifyBinderTest,PrometheusRuleServiceTest,PrometheusRuleRuntimeEnrichTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 全 PASS

> ⚠️ 注意 `PrometheusRuleServiceTest` 的构造器会多一个 `RuleNotifyBinder` 参数（或 mock `NotificationChannelService` + `RuleNotifyMapper` 后自行构造 binder），同步改。

- [ ] **Step 7: 提交**

```bash
git add platform-common/src/main/java/com/coding/common/models/k8s/dto/PrometheusRuleDTO.java \
        platform-api/src/main/java/com/coding/platformapi/services/PrometheusRuleService.java \
        platform-api/src/main/java/com/coding/platformapi/models/RuleNotifyBinder.java \
        platform-api/src/test/java/com/coding/platformapi/services/RuleNotifyBinderTest.java \
        platform-api/src/test/java/com/coding/platformapi/services/PrometheusRuleServiceTest.java
git commit -m "feat(notify): 保存规则时写绑定并代打 platform_notify label"
```

---

## Task 8: 前端 —— 规则编辑页选通知方式与收件人

**Files:**
- Modify: `platform-web/src/views/resource/PrometheusRuleEditorView.vue`
- Modify: `platform-web/src/types.ts`（`K8sPrometheusRule` 加两个平台侧字段）
- Modify: `platform-web/src/api/index.ts`（`notificationUserApi`）
- Test: 浏览器实测

**Interfaces:**
- Consumes: `notificationChannelApi.options`（Task 5）、`notificationUserApi.list`（Task 4）
- Produces: 编辑页提交体里带 `notifyChannel` + `notifyUserIds`

- [ ] **Step 1: 类型 + api**

```ts
export interface K8sPrometheusRule {
  ...
  /** 平台侧字段（不是 K8s 对象）：选中的通知方式 code */
  notifyChannel?: string | null
  /** 平台侧字段：收件人用户 id */
  notifyUserIds?: string[] | null
}
```

```ts
/** 本租户用户（规则编辑页的收件人候选） */
export const notificationUserApi = {
  list: (tenantId?: string | null) =>
    http.post<never, { id: string; displayName: string; email: string | null }[]>(
      '/notification-users/list', { tenantId: tenantId ?? null }),
}
```

- [ ] **Step 2: 编辑页加两块表单**

在「基础信息」卡片后加一张卡：

```vue
<el-card shadow="never" class="sec-card">
  <template #header><span class="sec-title">通知</span></template>
  <el-form label-width="140px" label-position="left">
    <el-form-item label="通知方式">
      <el-select v-model="form.notifyChannel" clearable placeholder="留空 = 使用默认通知方式" style="width: 320px">
        <el-option v-for="c in channelOptions" :key="c.code" :label="c.name" :value="c.code" />
      </el-select>
      <div class="form-tip">默认通知方式：{{ defaultChannelName || '未配置' }}。留空时走默认。</div>
    </el-form-item>
    <el-form-item label="通知给谁">
      <el-select v-model="form.notifyUserIds" multiple filterable placeholder="留空 = 该命名空间所属租户的管理员" style="width: 100%; max-width: 720px">
        <el-option v-for="u in userOptions" :key="u.id" :label="`${u.displayName}（${u.email || '无邮箱'}）`" :value="u.id" />
      </el-select>
      <div class="form-tip">只能选本租户用户；没有邮箱的用户选了也发不出去。</div>
    </el-form-item>
  </el-form>
</el-card>
```

`script` 里：

```ts
const channelOptions = ref<NotificationChannel[]>([])
const userOptions = ref<{ id: string; displayName: string; email: string | null }[]>([])
const defaultChannelName = computed(() => channelOptions.value.find((c) => c.isDefault)?.name ?? '')

onMounted(async () => {
  ...
  try {
    channelOptions.value = await notificationChannelApi.options(state.tenantId)
    userOptions.value = await notificationUserApi.list(state.tenantId)
  } catch { /* 拦截器已提示；下拉为空即可 */ }
})
```

回填（`loadDetail`）：`form.notifyChannel = d.notifyChannel ?? null`、`form.notifyUserIds = [...(d.notifyUserIds ?? [])]`。

提交（`submit`）：body 里带上 `notifyChannel: form.notifyChannel || null`、`notifyUserIds: form.notifyUserIds`。

- [ ] **Step 3: 类型检查 + 浏览器实测**

Run: `npm run type-check`

实测（preview + stub）：
- 下拉能看到渠道（含租户渠道 + 全局渠道）；
- 选一个渠道 + 两个用户 → 保存，请求体里有 `notifyChannel` 与 `notifyUserIds`；
- 清空渠道再保存 → 请求体 `notifyChannel: null`；
- 编辑已绑定的规则 → 回填正确。

- [ ] **Step 4: 提交**

```bash
git add platform-web/src/views/resource/PrometheusRuleEditorView.vue \
        platform-web/src/types.ts platform-web/src/api/index.ts
git commit -m "feat(notify): 规则编辑页选择通知方式与收件人"
```

---

## Task 9: 告警列表「通知方式」列

**Files:**
- Modify: `platform-web/src/views/AlertView.vue`
- Modify: `platform-web/src/types.ts`（`PlatformAlertEvent` 加 `notifyChannelName`）
- Modify: `platform-api/src/main/java/com/coding/platformapi/services/AlertService.java`（回填渠道名）

**Interfaces:**
- Consumes: `NotificationChannelMapper`
- Produces: 告警列表每行带 `notifyChannelName`（label 里的 code → 渠道名；查不到显示 code 本身）

- [ ] **Step 1: 写失败的测试**（`AlertServiceTest` 增补）

```java
/** 告警行回填渠道显示名：能找到就回名字，找不到回 code 本身。 */
@Test
void notify_channel_name_is_filled_from_the_label() {
    asPlatformAdmin();
    PlatformAlertEvent e = event("mon-app", "HighCPU");
    e.setLabels("{\"platform_notify\":\"ops-mail\"}");
    when(mapper.query(any())).thenReturn(List.of(e));
    NotificationChannel c = new NotificationChannel();
    c.setCode("ops-mail");
    c.setName("运维邮件");
    when(channels.selectByCode("ops-mail")).thenReturn(c);

    svc.list(req());

    assertThat(e.getNotifyChannelName()).isEqualTo("运维邮件");
}
```

（`AlertService` 增加 `NotificationChannelMapper` 依赖；Task 6 已把它改成
`RuleSourceResolver` + 现有依赖，**两个构造器都要再加一个参数**，`AlertServiceTest` /
`AlertSelfTestTest` 的 `new AlertService(...)` 同步。）

- [ ] **Step 2: 跑测试确认失败**

- [ ] **Step 3: 实现**

`PlatformAlertEvent` 加非持久字段 `notifyChannelName`；前端 `types.ts` 同步：

```ts
  /** 通知方式显示名（后端由 labels.platform_notify 反查渠道得到） */
  notifyChannelName?: string | null
```


`AlertService.list` 在 `attachSources` 之后：

```java
private void attachNotifyChannel(List<PlatformAlertEvent> rows) {
    for (PlatformAlertEvent row : rows) {
        String code = labelValue(row.getLabels(), "platform_notify");
        if (!StringUtils.hasText(code)) continue;
        NotificationChannel c = channels.selectByCode(code);
        row.setNotifyChannelName(c == null ? code : c.getName());
    }
}
```

`labelValue`：解析 `labels` JSON 取键（解析失败返回 null）。

- [ ] **Step 4: 前端加列**

```vue
<el-table-column label="通知方式" width="160">
  <template #default="{ row }">
    <span v-if="row.notifyChannelName">{{ row.notifyChannelName }}</span>
    <span v-else class="muted" title="规则没有绑定通知方式，按默认渠道发送">—（默认）</span>
  </template>
</el-table-column>
```

详情抽屉也加一项。

- [ ] **Step 5: 跑测试 + type-check + 提交**

---

# 块 3 · 投递引擎

## Task 10: 邮件发送器 + 异步配置

**Files:**
- Modify: `platform-api/pom.xml`（`spring-boot-starter-mail`）
- Create: `platform-api/src/main/java/com/coding/platformapi/notify/NotificationSender.java`
- Create: `platform-api/src/main/java/com/coding/platformapi/notify/EmailSender.java`
- Create: `platform-api/src/main/java/com/coding/platformapi/configs/AsyncConfig.java`
- Test: `platform-api/src/test/java/com/coding/platformapi/notify/EmailSenderTest.java`

**Interfaces:**
- Produces:
  - `interface NotificationSender { String type(); void send(NotificationChannel channel, List<String> recipients, List<PlatformAlertEvent> events) throws Exception; }`
  - `EmailSender implements NotificationSender`（`type() = "EMAIL"`）
  - Bean `alertDispatchExecutor`（`ThreadPoolTaskExecutor`，core=4 / max=4 / queue=200）

- [ ] **Step 1: 加依赖**

`platform-api/pom.xml`：

```xml
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-mail</artifactId>
        </dependency>
```

- [ ] **Step 2: 写失败的测试**

```java
package com.coding.platformapi.notify;

import com.coding.common.utils.AESUtils;
import com.coding.data.models.alert.NotificationChannel;
import com.coding.data.models.k8s.PlatformAlertEvent;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmailSenderTest {

    private static final String KEY = "test-key";

    private static NotificationChannel channel() {
        NotificationChannel c = new NotificationChannel();
        c.setCode("ops-mail");
        c.setName("运维邮件");
        c.setType("EMAIL");
        c.setConfig("{\"host\":\"smtp.example.com\",\"port\":\"465\",\"from\":\"a@example.com\","
                + "\"username\":\"a@example.com\",\"passwordEnc\":\""
                + AESUtils.encrypt("secret", KEY) + "\"}");
        return c;
    }

    private static PlatformAlertEvent event(String status) {
        PlatformAlertEvent e = new PlatformAlertEvent();
        e.setAlertname("HighCPU");
        e.setNamespace("mon-app");
        e.setSeverity("critical");
        e.setStatus(status);
        e.setStartsAt(new java.util.Date());
        return e;
    }

    /** 主题里带状态与条数；收件人是传入的邮箱列表。 */
    @Test
    void the_message_subject_and_recipients_are_built_from_the_batch() throws Exception {
        EmailSender sender = spy(new EmailSender(KEY));
        JavaMailSenderImpl mail = mock(JavaMailSenderImpl.class);
        when(mail.createMimeMessage()).thenReturn(new MimeMessage(Session.getInstance(new Properties())));
        org.mockito.Mockito.doReturn(mail).when(sender).mailSender(any());

        sender.send(channel(), List.of("a@x.com", "b@x.com"),
                List.of(event("firing"), event("resolved")));

        verify(mail).send(any(MimeMessage.class));
    }

    /** 没有有效收件人 → 直接失败（不能发一封没人收的邮件）。 */
    @Test
    void an_empty_recipient_list_fails_loudly() {
        EmailSender sender = new EmailSender(KEY);

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> sender.send(channel(), List.of(), List.of(event("firing"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("收件人");
    }
}
```

- [ ] **Step 3: 实现**

```java
public interface NotificationSender {
    String type();
    void send(NotificationChannel channel, List<String> recipients, List<PlatformAlertEvent> events) throws Exception;
}
```

`EmailSender` 要点：
- `mailSender(channel)` 方法（包级可见，便于测试替换）：按渠道 config 构建 `JavaMailSenderImpl`
  （host/port/username/password/`mail.smtp.auth`/`mail.smtp.ssl.enable`/`mail.smtp.starttls.enable`）。
- 主题：`[firing] N 条告警 · <渠道名>`；混合时 `[告警] N 条（M firing / K resolved）`。
- 正文：纯文本，firing / resolved 分块；每条 `alertname | namespace | severity | 摘要`。
- 空收件人列表 → `IllegalStateException("没有有效收件人")`。

`AsyncConfig`：

```java
@Configuration
@EnableAsync
public class AsyncConfig {
    @Bean("alertDispatchExecutor")
    public Executor alertDispatchExecutor() {
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(4);
        ex.setMaxPoolSize(4);
        ex.setQueueCapacity(200);
        ex.setThreadNamePrefix("alert-dispatch-");
        ex.initialize();
        return ex;
    }
}
```

- [ ] **Step 4: 跑测试 + 提交**

---

## Task 11: `AlertDispatchService` —— 决策、去重、重试

**Files:**
- Create: `platform-api/src/main/java/com/coding/platformapi/services/AlertDispatchService.java`
- Test: `platform-api/src/test/java/com/coding/platformapi/services/AlertDispatchServiceTest.java`

**Interfaces:**
- Consumes: `NotificationChannelService`、`RuleNotifyMapper`、`RuleSourceResolver`、`AlertDeliveryMapper`、
  `NotificationUserService`（`emailsOf` / `tenantAdminUserIds`）、`PlatformTenantNamespaceMapper`、
  `List<NotificationSender>`（Spring 注入所有实现，按 `type()` 建索引）
- Produces: `AlertDispatchService#dispatch(String clusterId, List<PlatformAlertEvent> events)`（`@Async("alertDispatchExecutor")`）

`tenantOf`（告警归属：namespace → 分配表的第一个租户；没有分配返回 null）：

```java
private String tenantOf(String clusterId, String namespace) {
    if (!StringUtils.hasText(namespace)) return null;
    List<PlatformTenantNamespace> rows = allocs.selectByClusterAndNs(clusterId, namespace);
    return rows == null || rows.isEmpty() ? null : rows.get(0).getTenantId();
}
```

- [ ] **Step 1: 写失败的测试**（决策是核心，全部用 mock）

用例：
1. `labels.platform_notify=ops-mail` → 用该渠道；
2. 没有 label → 用默认渠道（租户默认优先 → 全局默认）；
3. 渠道 code 不存在 → 回落默认；
4. 渠道 `enabled=false` → **仍然发送**（停用只影响新选择）；
4.1 渠道是**其他租户**专用的（`tenantId` 非空且 ≠ 告警所属租户）→ 回落默认；
5. 绑定表命中 → 收件人 = 绑定用户中**有邮箱**的那些；
6. 绑定表未命中（规则已删/改名）→ 收件人 = 该命名空间所属租户的 tenant-admin 用户；
7. 没有任何收件人 → 该 event 的 delivery 记 `FAILED("无有效收件人")`，不调用 sender；
8. 同一 `(event, channel, status)` 第二次 dispatch → `insertIfAbsent` 返回 0 → **不发送**；
9. sender 抛异常 → 重试 3 次后 `FAILED`，`attempts=3`；
10. 一次 dispatch 里两个 event 同渠道 → **只调用一次 sender**（合并）。

- [ ] **Step 2: 跑测试确认失败**

- [ ] **Step 3: 实现（关键代码）**

```java
@Slf4j
@Service
public class AlertDispatchService {

    private static final int MAX_ATTEMPTS = 3;
    private static final long[] BACKOFF_MS = {1_000, 5_000, 30_000};

    /** 发送前钩子：本轮空实现，下一轮压制挂在这里。 */
    protected List<PlatformAlertEvent> beforeSend(List<PlatformAlertEvent> events) { return events; }

    @Async("alertDispatchExecutor")
    public void dispatch(String clusterId, List<PlatformAlertEvent> events) {
        List<PlatformAlertEvent> toSend = beforeSend(events);
        if (toSend.isEmpty()) return;

        // 1) 解析每条 event 的渠道
        Map<String, List<PlatformAlertEvent>> byChannel = new LinkedHashMap<>();
        for (PlatformAlertEvent e : toSend) {
            String tenantId = tenantOf(clusterId, e.getNamespace());
            NotificationChannel c = channels.resolve(labelValue(e, "platform_notify"));
            // 跨租户渠道不允许：规则租户与渠道租户不一致（且渠道非全局）→ 回落默认
            if (c != null && StringUtils.hasText(c.getTenantId())
                    && !c.getTenantId().equals(tenantId)) {
                log.warn("规则绑定了其他租户的通知方式，回落默认（code={} tenant={}）", c.getCode(), tenantId);
                c = null;
            }
            if (c == null) c = channels.defaultFor(tenantId);
            if (c == null) {
                markFail(e, null, "未配置通知渠道");
                continue;
            }
            byChannel.computeIfAbsent(c.getCode(), k -> new ArrayList<>()).add(e);
        }

        // 2) 按渠道合并发送
        for (var entry : byChannel.entrySet()) {
            NotificationChannel channel = channels.resolve(entry.getKey());
            List<PlatformAlertEvent> batch = entry.getValue();

            // 2.1 幂等领取：只有第一次才发
            List<AlertDelivery> claimed = new ArrayList<>();
            for (PlatformAlertEvent e : batch) {
                AlertDelivery d = new AlertDelivery();
                d.setEventId(e.getId());
                d.setChannelCode(channel.getCode());
                d.setAlertStatus(e.getStatus());
                d.setStatus("PENDING");
                d.setCreatedAt(new Date());
                if (deliveries.insertIfAbsent(d) == 1) claimed.add(d);
            }
            if (claimed.isEmpty()) continue;

            // 2.2 收件人
            List<String> recipients = recipientsFor(clusterId, batch);
            if (recipients.isEmpty()) {
                claimed.forEach(d -> deliveries.markFailed(d.getId(), "无有效收件人", 0));
                continue;
            }

            // 2.3 发送 + 重试
            NotificationSender sender = senders.get(channel.getType());
            if (sender == null) {
                claimed.forEach(d -> deliveries.markFailed(d.getId(), "不支持的通知类型: " + channel.getType(), 0));
                continue;
            }
            int attempts = 0;
            Exception last = null;
            for (int i = 0; i < MAX_ATTEMPTS; i++) {
                attempts++;
                try {
                    sender.send(channel, recipients, batch);
                    claimed.forEach(d -> deliveries.markSent(d.getId()));
                    last = null;
                    break;
                } catch (Exception ex) {
                    last = ex;
                    log.warn("告警投递失败（channel={} attempt={}）: {}", channel.getCode(), attempts, ex.getMessage());
                    if (i < MAX_ATTEMPTS - 1) sleep(BACKOFF_MS[i]);
                }
            }
            if (last != null) {
                String msg = last.getMessage() == null ? last.getClass().getSimpleName() : last.getMessage();
                int n = attempts;
                claimed.forEach(d -> deliveries.markFailed(d.getId(), truncate(msg), n));
            }
        }
    }
}
```

`markFail`（没有渠道可发时也要留痕）：

```java
/** 连渠道都没有：直接落一条 FAILED 记录（channel_code 用空串占位）。 */
private void markFail(PlatformAlertEvent e, String channelCode, String error) {
    AlertDelivery d = new AlertDelivery();
    d.setEventId(e.getId());
    d.setChannelCode(channelCode == null ? "" : channelCode);
    d.setAlertStatus(e.getStatus());
    d.setStatus("FAILED");
    d.setCreatedAt(new Date());
    if (deliveries.insertIfAbsent(d) == 1) {
        deliveries.markFailed(d.getId(), error, 0);
    }
}
```

`recipientsFor`：

```java
private List<String> recipientsFor(String clusterId, List<PlatformAlertEvent> batch) {
    Set<String> userIds = new LinkedHashSet<>();
    for (PlatformAlertEvent e : batch) {
        String tenantId = tenantOf(clusterId, e.getNamespace());
        String ruleName = ruleSourceResolver.crNameOf(clusterId, tenantId, e);   // 复用来源反查
        RuleNotify binding = ruleName == null ? null
                : bindings.selectByRule(clusterId, e.getNamespace(), ruleName);
        if (binding != null) {
            userIds.addAll(parseIds(binding.getUserIds()));
        } else if (tenantId != null) {
            userIds.addAll(userService.tenantAdminUserIds(tenantId));   // 默认收件人
        }
    }
    return userService.emailsOf(userIds);      // 过滤空邮箱
}
```

> `RuleSourceResolver` 在 Task 6 只有批量 `resolve`；本任务给它加一个按单条查询的便捷方法
> `crNameOf(clusterId, tenantId, event)`（内部走同一个索引逻辑），并在 Task 6 的测试里补一条用例。

- [ ] **Step 4: 跑测试 + 变异验证**

变异点：把 `if (deliveries.insertIfAbsent(d) == 1)` 改成无条件发送 → 用例 8 必须红。

- [ ] **Step 5: 提交**

---

## Task 12: ingest 返回事件 + webhook 接线

**Files:**
- Modify: `platform-api/src/main/java/com/coding/platformapi/services/AlertIngestService.java`
- Modify: `platform-api/src/main/java/com/coding/platformapi/controllers/AlertWebhookController.java`
- Modify: `platform-data/src/main/java/com/coding/data/mapper/k8s/PlatformAlertEventMapper.java` + XML（按幂等键回查 id）
- Test: `AlertIngestServiceTest` / `AlertWebhookControllerTest` 增补

**Interfaces:**
- Produces: `AlertIngestService#ingest(...) → List<PlatformAlertEvent>`（成功落库的行，**含 id**）

- [ ] **Step 1: 写失败的测试**

```java
/** 返回落库后的事件（含 id），供投递使用。 */
@Test
void ingest_returns_the_upserted_events_with_ids() {
    when(allocs.selectByClusterAndNs(anyString(), anyString())).thenReturn(List.of(alloc("t1", "ns")));
    PlatformAlertEvent stored = new PlatformAlertEvent();
    stored.setId(42L);
    when(mapper.selectByEpisode("c1", "fp-1", any())).thenReturn(stored);

    var out = svc.ingest("c1", List.of(alert("firing", "ns", "2026-10-17T10:00:00Z")));

    assertThat(out).hasSize(1);
    assertThat(out.get(0).getId()).isEqualTo(42L);
}
```

**再补一条 webhook 用例（Review Focus #5）**：

```java
/** 投递引擎抛任何异常都不能影响 webhook 的 200（它已是异步，异常只进日志）。 */
@Test
void a_dispatch_failure_never_changes_the_webhook_status() throws Exception {
    when(ingest.ingest(anyString(), any())).thenReturn(List.of());
    doThrow(new RuntimeException("dispatch exploded"))
            .when(dispatch).dispatch(anyString(), any());

    mvc.perform(post(URL).queryParam("clusterId", "c1")
                    .header("Authorization", "Bearer " + TOKEN)
                    .contentType(MediaType.APPLICATION_JSON).content(REAL_ENVELOPE))
            .andExpect(status().isOk());
}
```

> 注：真实环境下 `@Async` 会把异常吞在 executor 里；这条用例守的是「即使同步抛出也不影响响应」。

- [ ] **Step 2: 实现**

`PlatformAlertEventMapper` 加：

```java
PlatformAlertEvent selectByEpisode(@Param("clusterId") String clusterId,
                                   @Param("fingerprint") String fingerprint,
                                   @Param("startsAt") Date startsAt);
```

XML 按 `uk_alert_episode` 精确查。

`AlertIngestService.ingest`：成功 upsert 后把 row 按幂等键回查（`ON DUPLICATE KEY UPDATE` 不回填自增 id），收集返回。
`AlertWebhookController`：`int accepted = ingest.ingest(...).size()`，并在 200 之前调用
`dispatchService.dispatch(clusterId, events)`（异步，方法内部不等待）。

> ⚠️ 控制器的构造器要多一个 `AlertDispatchService` 参数，`AlertWebhookControllerTest` 的
> `new AlertWebhookController(ingest, tokenCheck)` 同步改成三参（`dispatch` 用 mock）。

> 组装顺序：`ingest` 返回列表 → 记 `accepted` → 触发 `dispatch` → 返回 200。
> `dispatch` 抛任何异常都不能影响响应（它已经是异步的，异常只会进日志）。

- [ ] **Step 3: 跑测试 + 提交**

---

## Task 13: 告警列表「投递状态」列

**Files:**
- Modify: `platform-data/src/main/java/com/coding/data/models/k8s/PlatformAlertEvent.java`（非持久字段）
- Modify: `platform-api/src/main/java/com/coding/platformapi/services/AlertService.java`
- Modify: `platform-web/src/types.ts` / `AlertView.vue`

- [ ] **Step 1: 后端**

`PlatformAlertEvent.deliveryStatus`（非持久）。`AlertService.list` 末尾：按返回行的 id 批量查
`AlertDeliveryMapper.selectLatestByEventIds(ids)`，取每个 event 的**最新一条**（状态 + 错误）。
没有记录 → `null`（前端显示「—」）。

```java
List<Long> ids = rows.stream().map(PlatformAlertEvent::getId).filter(Objects::nonNull).toList();
if (!ids.isEmpty()) {
    Map<Long, AlertDelivery> latest = new HashMap<>();
    for (AlertDelivery d : deliveries.selectLatestByEventIds(ids)) {
        latest.putIfAbsent(d.getEventId(), d);     // SQL 已按 id desc，第一条就是最新
    }
    for (PlatformAlertEvent row : rows) {
        AlertDelivery d = latest.get(row.getId());
        if (d != null) {
            row.setDeliveryStatus(d.getStatus());
            row.setDeliveryError(d.getLastError());
        }
    }
}
```

前端 `types.ts`：

```ts
  /** 最新一次投递的状态（PENDING/SENT/FAILED）；null = 还没投递 */
  deliveryStatus?: string | null
  deliveryError?: string | null
```

- [ ] **Step 2: 前端**

```vue
<el-table-column label="投递" width="110">
  <template #default="{ row }">
    <el-tag v-if="row.deliveryStatus === 'SENT'" size="small" type="success" effect="plain">已发送</el-tag>
    <el-tag v-else-if="row.deliveryStatus === 'FAILED'" size="small" type="danger" effect="plain" :title="row.deliveryError">失败</el-tag>
    <el-tag v-else-if="row.deliveryStatus === 'PENDING'" size="small" type="info" effect="plain">发送中</el-tag>
    <span v-else class="muted">—</span>
  </template>
</el-table-column>
```

- [ ] **Step 3: 提交**

---

## Task 14: 真跑验证

**Files:** 无新增

- [ ] **Step 1: 准备一条可用的 SMTP**

在「通知方式」页建一条 `ops-mail`（EMAIL，填真实 SMTP；本机可用企业邮箱 / 内网 SMTP）。
把它设为默认。

- [ ] **Step 2: 触发**

在告警页点「自检」（会自动往平台投两条 `PlatformSelfTest`）。

- [ ] **Step 3: 核对**

- 邮箱收到一封（resolved + firing 两条在同一封里分块）；
- 告警列表两条都显示「通知方式 = 运维邮件」「投递 = 已发送」；
- 再点一次自检：`startsAt` 不同（自检每次用当前时间）→ 是新的会话，应再收到一封；
  若把同一条告警重复推送（可以用 curl 回放同一 payload），**不应**再收到邮件。

- [ ] **Step 4: 失败路径**

把渠道的 SMTP 密码改错 → 再点自检 → 列表显示「投递 = 失败」，悬停看 `last_error`；webhook 仍回 200。

---

## 收尾

- [ ] 全项目 `mvn test`（任何失败都要点名，包括不是本批造成的）
- [ ] `cd platform-web && npm run build`
- [ ] 迁移：确认 `V2026_10_19_8` 已重放
- [ ] 台账 `docs/task-ledger-2026-10-12.md`：S2.2.4 从「待定」改为「已实施待部署」，写部署清单
- [ ] 部署清单：先重放迁移 → 重建 platform-data / platform-common / platform-api / platform-web →
      **重新登录** → 在通知方式页配置一条渠道并设为默认
