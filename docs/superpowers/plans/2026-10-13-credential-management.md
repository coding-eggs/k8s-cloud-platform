# 凭据管理 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 给平台加一个平台级凭据库（git / registry 连接），凭据加密落库、永不回显，并能一键测试连接是否真的可用。

**Architecture:** 新表 `platform_credential` 一张、新 Controller 一族（`/credential/**`）、一个新前端页。
凭据明文存成**加密的 JSON blob**（不是 username/password 强类型列），所以将来加 SSH 认证零迁移。
「测试连接」经 `CredentialProbe`（纯规则，可单测）+ `CredentialProbeClient`（出站 HTTP）两段实现。
审计**复用已有的 `@Auditable` + `AuditAspect`**，零新机制。

**Tech Stack:** Spring Boot 4.1 / Java 21 / MyBatis（手写 mapper + XML）/ MySQL / Vue 3 + Element Plus + TypeScript

**Spec:** `docs/superpowers/specs/2026-10-13-credential-management-design.md`

## Global Constraints

- **平台名/包名**：后端 `com.coding.platformapi`（API 层）、`com.coding.data`（数据层）、`com.coding.common`（公共）；
  前端 `platform-web/src`。**新增文件一律放在既有目录下，不新建顶层模块。**
- **没有 Flyway**：`db/migration/*.sql` 是**手工重放**的，应用不会自动执行。启动日志里永远没有 `Migrating schema`。
- **MySQL 注释 `--` 后面必须跟空白字符**，否则整个文件重放失败（`V2026_10_13_1` / `V2026_10_13_2` / `V2026_10_09_1` 都踩过）。
- **迁移可重放**：建表 `IF NOT EXISTS`；权限行与角色关联**显式 id + `ON DUPLICATE KEY UPDATE`**；
  角色关联**按显式 id 列表，绝不用 LIKE**（id 里的 `_` 是 LIKE 的单字符通配符）。
- **每个新端点必须有 API 域权限行**，否则 `PermissionCrossCheckRunner` 启动期拒启（fail-closed）。
- **幂等主键**：实体 id 用 `ULIDGenerator.generateULID()`（16 位 hex）。
- **不加外键**（架构原则）。
- **错误码**：`EnumResponseType` 当前最大是 `10031`，新码从 **10032** 起。
- **测试命令必须带 `-Dsurefire.failIfNoSpecifiedTests=false`**（`-DfailIfNoTests=false` 在 `-am` 下无效，会让构建失败）。
- **既有失败勿当本批引入**：`k8s-core` 的 `WorkloadConverterStaticIpTest` 在 @HEAD 即 NPE。
- **不提交** `k8s-core/src/test/.../WorkloadConverterStaticIpTest.java` 的删除（工作区里预存的那处改动与本批无关）。

## Review Focus

以下五类是本模块最容易被真实输入打穿、而任何单测都不会替你想到的地方。**每条都在它所属的任务里有一条测试钉住**：

1. **凭据内容从任何响应里泄露** —— 管理员把密码打进去，返回体里就带回来了（或某个新加的字段悄悄带上了）。
   合理预期：列表与所有写接口的响应里，**明文与用户名都不出现**。→ Task 5 的反射断言 + Task 7 的响应结构。
2. **401 被解释成「密码错误」** —— 真实原因却是「这条 token 对这个仓库没权限」，管理员会去改一个没问题的密码。
   合理预期：凭据类失败一律二义文案。→ Task 4。
3. **探针把「200 + 登录页 HTML」判成通过** —— 反向代理/captive portal 的典型行为。
   合理预期：只看状态码之外还要看响应体。→ Task 4。
4. **`endpoint` 带了尾部斜杠或少了 scheme** —— 用户手输的常态，拼出来就是 `https://h//v2/` 或 `h/v2/`。
   合理预期：URL 构造对这两种输入都产出合法地址。→ Task 4。
5. **主密钥没配却正常启动** —— 于是用一把空/默认密钥加密了真实凭据，事后无法察觉。
   合理预期：拒绝启动。→ Task 3。

---

### Task 1: 迁移脚本（建表 + 权限行 + 角色关联）

**Files:**
- Create: `platform-data/src/main/resources/db/migration/V2026_10_13_3__credential.sql`

**Interfaces:**
- Consumes: 无
- Produces: 表 `platform_credential`；权限码 `platform:credential:read` / `platform:credential:write` / `platform:page:credential`；6 行权限 + 6 行 admin 关联

- [ ] **Step 1: 写迁移文件**

创建 `platform-data/src/main/resources/db/migration/V2026_10_13_3__credential.sql`：

```sql
-- ============================================================================================
-- S0.2 凭据管理：建表 + 权限行（6 行）+ admin 角色关联
-- ============================================================================================
-- 背景：平台没有凭据库。本批新增 platform_credential，以及 platform-api 的 /credential/** 端点。
-- 设计全文见 docs/superpowers/specs/2026-10-13-credential-management-design.md。
--
-- 范围：git / registry 两类连接的 CRUD + 测试连接。不含投递（命名空间 Secret / imagePullSecret /
-- 构建 Pod 注入）—— 那些留给 S3.1 / S3.5。
--
-- 权限码（3 个）：
--   · platform:credential:read   —— 列表（脱敏）+ 测试连接
--   · platform:credential:write  —— 新建 / 更新 / 删除
--   · platform:page:credential   —— 前端页面可见性（Page 域）
-- 只给 admin：纯平台级凭据，无租户对应页面（spec 已定决策「凭据归属」）。
--
-- ⚠️ 测试连接与列表**共用 read**（有意）：本批三个码全绑 admin，独立成码不产生任何角色差异。
--    后果是「能看列表 = 能点测试」。设计与理由见 spec §6.2。
--
-- 有意不做：
--   · 不加 deleted_at —— 硬删除。凭据的删除语义是「让它消失」，软删除会把密文留在库里，
--     而如果删除的原因是「这个 token 泄露了」，软删除正好使它失效（spec 已定决策「删除语义」）。
--   · 不加 tenant_id / scope —— 纯平台级 + 全局可用，留个空列只会让人以为有个没实现的功能。
--
-- ⚠️ 部署顺序：先重放本文件再起新构建 —— 无行的新端点会被 PermissionCrossCheckRunner 拒启（fail-closed）。
-- ⚠️ 重放后需重新登录或等权限缓存 TTL（30s）：token 里已无权限码，闭包在请求期现算。
-- ⚠️ 本仓没有 Flyway，本文件是手工重放的；启动日志里永远不会出现 Migrating schema。
-- 幂等：表用 IF NOT EXISTS；权限行与角色关联显式 id + ODKU；角色关联按【显式 id 列表】，不用 LIKE
-- （id 里的 `_` 是 LIKE 的单字符通配符，V2026_10_08_5 已踩过）。
-- 注意：MySQL 注释 `--` 后面必须跟空白字符，否则整个文件重放失败。
-- ============================================================================================

CREATE TABLE IF NOT EXISTS `platform_credential` (
  `id`                 varchar(64)  NOT NULL COMMENT 'ULIDGenerator（16 位 hex），同库内其它表惯例',
  `name`               varchar(64)  NOT NULL COMMENT '显示名，全局唯一',
  `type`               varchar(32)  NOT NULL COMMENT 'GIT / REGISTRY（v1 仅此两类）',
  `endpoint`           varchar(512) NOT NULL COMMENT 'host 基址：GIT=https://gitlab.example.com，REGISTRY=harbor.example.com',
  `auth_type`          varchar(32)  NOT NULL COMMENT 'BASIC（v1 唯一实现）；加 SSH 时在此扩展，不动表',
  `secret_encrypted`   text                  DEFAULT NULL COMMENT 'AES 密文（含 v1: 前缀），明文是认证字段 JSON。v1 恒非空（BASIC 必填）；可空是为将来 NONE 型（匿名）留位',
  `secret_fingerprint` varchar(64)           DEFAULT NULL COMMENT 'SHA-256(认证字段 JSON) 前 16 hex；用于区分「改了凭据」与「只改了描述」',
  `description`        varchar(255)          DEFAULT NULL,
  `enabled`            tinyint      NOT NULL DEFAULT 1 COMMENT '0=停用：仍在列表里，但消费方不应选它',
  `last_verified_at`   datetime              DEFAULT NULL COMMENT '上次**测试通过**的时刻；失败不改它',
  `last_verify_error`  varchar(255)          DEFAULT NULL COMMENT '上次测试的失败原因；成功则置 NULL',
  `created_at`         datetime              DEFAULT CURRENT_TIMESTAMP,
  `updated_at`         datetime              DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_name`(`name` ASC) USING BTREE,
  INDEX `idx_type`(`type` ASC) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC
  COMMENT='平台级凭据（git / registry 连接）';

-- ===== 权限行：API 域 5 行 + Page 域 1 行 =====
INSERT INTO platform_permission (id, domain, resource, action, code, description) VALUES
 ('perm_credential_list',   'API','/credential/list',   'POST','platform:credential:read',
  '凭据列表（脱敏，永不回显凭据内容）'),
 ('perm_credential_test',   'API','/credential/test',   'POST','platform:credential:read',
  '测试连接（与列表同码：能看即能测）'),
 ('perm_credential_create', 'API','/credential/create', 'POST','platform:credential:write',
  '新建凭据'),
 ('perm_credential_update', 'API','/credential/update', 'POST','platform:credential:write',
  '更新凭据（可轮换密钥）'),
 ('perm_credential_delete', 'API','/credential/delete', 'POST','platform:credential:write',
  '删除凭据（硬删除）'),
 ('perm_page_credential',   'Page','/credentials',      'view','platform:page:credential',
  '凭据管理页可见性')
ON DUPLICATE KEY UPDATE description=VALUES(description), resource=VALUES(resource), action=VALUES(action);

-- ===== 角色关联：admin 全给（显式 id 列表） =====
INSERT INTO platform_role_permission (id, role_id, permission_id) VALUES
 ('rp_admin_credential_list',   'builtin_role_admin','perm_credential_list'),
 ('rp_admin_credential_test',   'builtin_role_admin','perm_credential_test'),
 ('rp_admin_credential_create', 'builtin_role_admin','perm_credential_create'),
 ('rp_admin_credential_update', 'builtin_role_admin','perm_credential_update'),
 ('rp_admin_credential_delete', 'builtin_role_admin','perm_credential_delete'),
 ('rp_admin_credential_page',   'builtin_role_admin','perm_credential_page')
ON DUPLICATE KEY UPDATE permission_id=VALUES(permission_id);

-- tenant-admin / tenant-member 有意不给：纯平台级凭据，无租户对应页面。
```

- [ ] **Step 2: 重放迁移（第 1 次）**

本仓没有 mysql CLI，用 jshell + JDBC：

```bash
cd /d/coding/k8s-cloud-platform
cat > /tmp/replay-cred.jsh <<'EOF'
import java.sql.*;
import java.nio.file.*;
void main() throws Exception {
    String url = "jdbc:mysql://192.168.31.80:1234/k8s_cloud_platform?useUnicode=true&characterEncoding=utf8&allowMultiQueries=true&useSSL=false&serverTimezone=GMT%2B8&allowPublicKeyRetrieval=true";
    String sql = Files.readString(Path.of("platform-data/src/main/resources/db/migration/V2026_10_13_3__credential.sql"));
    try (Connection c = DriverManager.getConnection(url, "root", "coding");
         Statement st = c.createStatement()) {
        st.execute(sql);
    }
    try (Connection c = DriverManager.getConnection(url, "root", "coding");
         Statement st = c.createStatement()) {
        ResultSet rs = st.executeQuery("select count(*) from platform_credential");
        rs.next();
        System.out.println("TABLE_OK rows=" + rs.getInt(1));
        rs = st.executeQuery("select count(*) from platform_permission where id like 'perm_credential%' or id='perm_page_credential'");
        rs.next();
        System.out.println("PERMISSION_ROWS=" + rs.getInt(1));
        rs = st.executeQuery("select count(*) from platform_role_permission where id like 'rp_admin_credential%'");
        rs.next();
        System.out.println("ROLE_LINKS=" + rs.getInt(1));
    }
}
EOF
jshell --execution local -q /tmp/replay-cred.jsh
```

Expected:
```
TABLE_OK rows=0
PERMISSION_ROWS=6
ROLE_LINKS=6
```

（`TABLE_OK rows=0` 是新建空表，正常。）

- [ ] **Step 3: 重放第 2 次，验证幂等**

再跑一遍 **Step 2 的同一条命令**。Expected：输出**完全相同**（不报 `Duplicate entry` / 行数不变）。

- [ ] **Step 4: 验证权限行内容对得上**

```bash
cd /d/coding/k8s-cloud-platform
cat > /tmp/check-cred.jsh <<'EOF'
import java.sql.*;
void main() throws Exception {
    String url = "jdbc:mysql://192.168.31.80:1234/k8s_cloud_platform?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=GMT%2B8&allowPublicKeyRetrieval=true";
    try (Connection c = DriverManager.getConnection(url, "root", "coding");
         Statement st = c.createStatement()) {
        ResultSet rs = st.executeQuery(
            "select domain, resource, action, code from platform_permission "
          + "where id in ('perm_credential_list','perm_credential_test','perm_credential_create',"
          + "'perm_credential_update','perm_credential_delete','perm_page_credential') order by id");
        while (rs.next()) {
            System.out.println(rs.getString(1) + " | " + rs.getString(2) + " | "
                             + rs.getString(3) + " | " + rs.getString(4));
        }
    }
}
EOF
jshell --execution local -q /tmp/check-cred.jsh
```

Expected（6 行，顺序按 id）：

```
API | /credential/create | POST | platform:credential:write
API | /credential/delete | POST | platform:credential:write
API | /credential/list | POST | platform:credential:read
API | /credential/test | POST | platform:credential:read
API | /credential/update | POST | platform:credential:write
Page | /credentials | view | platform:page:credential
```

- [ ] **Step 5: 提交**

```bash
git add platform-data/src/main/resources/db/migration/V2026_10_13_3__credential.sql
git commit -m "feat(credential): 建 platform_credential 表 + 6 行权限 + admin 关联（S0.2）"
```

---

### Task 2: 数据层（model + mapper 接口 + mapper XML）

**Files:**
- Create: `platform-data/src/main/java/com/coding/data/models/system/PlatformCredential.java`
- Create: `platform-data/src/main/java/com/coding/data/mapper/system/PlatformCredentialMapper.java`
- Create: `platform-data/src/main/resources/mapper/system/PlatformCredentialMapper.xml`

**Interfaces:**
- Consumes: Task 1 的表
- Produces:
  - `PlatformCredential`（getter/setter，字段名对应下面 XML 的 snake_case）
  - `PlatformCredentialMapper.insert(PlatformCredential) : int`
  - `PlatformCredentialMapper.update(PlatformCredential) : int` —— **只写用户可改列，不碰 `last_*`**
  - `PlatformCredentialMapper.deleteById(String id) : int`
  - `PlatformCredentialMapper.selectById(String id) : PlatformCredential | null`
  - `PlatformCredentialMapper.selectByName(String name) : PlatformCredential | null`
  - `PlatformCredentialMapper.selectAll() : List<PlatformCredential>`
  - `PlatformCredentialMapper.updateVerifyResult(String id, boolean ok, String error, Date at) : int`

- [ ] **Step 1: 写实体**

创建 `platform-data/src/main/java/com/coding/data/models/system/PlatformCredential.java`：

```java
package com.coding.data.models.system;

import lombok.Data;

import java.util.Date;

/**
 * 平台级凭据（表 platform_credential）：git / registry 连接。
 *
 * <p>设计见 docs/superpowers/specs/2026-10-13-credential-management-design.md。
 *
 * <p><b>认证字段不建模成强类型列</b>：{@link #secretEncrypted} 解密后是一段 JSON
 * （v1 形如 {@code {"username":"…","secret":"…"}}）。这样加 SSH 等新认证方式是「blob 里多两个键」，
 * 零迁移；而 username/password 强类型列的唯一好处是「DB 里能直接读到用户名」——
 * 那本来就是不该可读的敏感数据。
 *
 * <p><b>没有 tenant_id / scope</b>：纯平台级 + 全局可用（spec 已定决策）。
 * <b>没有 deletedAt</b>：硬删除，理由见 spec「删除语义」一行。
 */
@Data
public class PlatformCredential {

    /** ULIDGenerator.generateULID() */
    private String id;

    private String name;

    /** GIT / REGISTRY */
    private String type;

    /** host 基址（spec §1.1） */
    private String endpoint;

    /** BASIC（v1 唯一实现） */
    private String authType;

    /** AES 密文，含 "v1:" 前缀；明文是认证字段 JSON */
    private String secretEncrypted;

    /** SHA-256(认证字段 JSON) 前 16 hex */
    private String secretFingerprint;

    private String description;

    /** 1 启用 / 0 停用 */
    private Integer enabled;

    /** 上次测试通过的时刻 */
    private Date lastVerifiedAt;

    /** 上次测试的失败原因；成功则 NULL */
    private String lastVerifyError;

    private Date createdAt;

    private Date updatedAt;
}
```

- [ ] **Step 2: 写 mapper 接口**

创建 `platform-data/src/main/java/com/coding/data/mapper/system/PlatformCredentialMapper.java`：

```java
package com.coding.data.mapper.system;

import com.coding.data.models.system.PlatformCredential;
import org.apache.ibatis.annotations.Param;
import org.springframework.stereotype.Repository;

import java.util.Date;
import java.util.List;

/**
 * 凭据读写。设计见 docs/superpowers/specs/2026-10-13-credential-management-design.md。
 *
 * <p><b>{@link #update} 与 {@link #updateVerifyResult} 分开</b>：前者写用户可改的列，
 * 后者只写测试结果两列。合成一个「整体更新」会让「保存」与「测试」互相覆盖 ——
 * 用户在测试结果落库前保存了表单，就会把上次的验证时间抹掉。
 */
@Repository
public interface PlatformCredentialMapper {

    int insert(PlatformCredential c);

    /**
     * 只写用户可改列（name、type、endpoint、auth_type、secret 的两个列、description、enabled），
     * <b>不碰 last_verified_at / last_verify_error</b>。
     */
    int update(PlatformCredential c);

    int deleteById(@Param("id") String id);

    PlatformCredential selectById(@Param("id") String id);

    PlatformCredential selectByName(@Param("name") String name);

    List<PlatformCredential> selectAll();

    /** 测试结果回写：ok=true 时置 last_verified_at 并清空 error，ok=false 时只写 error */
    int updateVerifyResult(@Param("id") String id,
                           @Param("ok") boolean ok,
                           @Param("error") String error,
                           @Param("at") Date at);
}
```

- [ ] **Step 3: 写 mapper XML**

创建 `platform-data/src/main/resources/mapper/system/PlatformCredentialMapper.xml`：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="com.coding.data.mapper.system.PlatformCredentialMapper">

  <resultMap id="BaseResultMap" type="com.coding.data.models.system.PlatformCredential">
    <id     column="id"                 jdbcType="VARCHAR"   property="id" />
    <result column="name"               jdbcType="VARCHAR"   property="name" />
    <result column="type"               jdbcType="VARCHAR"   property="type" />
    <result column="endpoint"           jdbcType="VARCHAR"   property="endpoint" />
    <result column="auth_type"          jdbcType="VARCHAR"   property="authType" />
    <result column="secret_encrypted"   jdbcType="LONGVARCHAR" property="secretEncrypted" />
    <result column="secret_fingerprint" jdbcType="VARCHAR"   property="secretFingerprint" />
    <result column="description"        jdbcType="VARCHAR"   property="description" />
    <result column="enabled"            jdbcType="TINYINT"   property="enabled" />
    <result column="last_verified_at"   jdbcType="TIMESTAMP" property="lastVerifiedAt" />
    <result column="last_verify_error"  jdbcType="VARCHAR"   property="lastVerifyError" />
    <result column="created_at"         jdbcType="TIMESTAMP" property="createdAt" />
    <result column="updated_at"         jdbcType="TIMESTAMP" property="updatedAt" />
  </resultMap>

  <sql id="Base_Column_List">
    id, name, `type`, endpoint, auth_type, secret_encrypted, secret_fingerprint,
    description, enabled, last_verified_at, last_verify_error, created_at, updated_at
  </sql>

  <insert id="insert" parameterType="com.coding.data.models.system.PlatformCredential">
    insert into platform_credential (id, name, `type`, endpoint, auth_type,
      secret_encrypted, secret_fingerprint, description, enabled)
    values (#{id,jdbcType=VARCHAR}, #{name,jdbcType=VARCHAR}, #{type,jdbcType=VARCHAR},
      #{endpoint,jdbcType=VARCHAR}, #{authType,jdbcType=VARCHAR},
      #{secretEncrypted,jdbcType=LONGVARCHAR}, #{secretFingerprint,jdbcType=VARCHAR},
      #{description,jdbcType=VARCHAR}, #{enabled,jdbcType=TINYINT})
  </insert>

  <update id="update" parameterType="com.coding.data.models.system.PlatformCredential">
    update platform_credential
    set name = #{name,jdbcType=VARCHAR},
        `type` = #{type,jdbcType=VARCHAR},
        endpoint = #{endpoint,jdbcType=VARCHAR},
        auth_type = #{authType,jdbcType=VARCHAR},
        secret_encrypted = #{secretEncrypted,jdbcType=LONGVARCHAR},
        secret_fingerprint = #{secretFingerprint,jdbcType=VARCHAR},
        description = #{description,jdbcType=VARCHAR},
        enabled = #{enabled,jdbcType=TINYINT}
    where id = #{id,jdbcType=VARCHAR}
  </update>

  <update id="updateVerifyResult">
    update platform_credential
    <choose>
      <when test="ok">
        set last_verified_at = #{at,jdbcType=TIMESTAMP}, last_verify_error = null
      </when>
      <otherwise>
        set last_verify_error = #{error,jdbcType=VARCHAR}
      </otherwise>
    </choose>
    where id = #{id,jdbcType=VARCHAR}
  </update>

  <delete id="deleteById">
    delete from platform_credential where id = #{id,jdbcType=VARCHAR}
  </delete>

  <select id="selectById" resultMap="BaseResultMap">
    select <include refid="Base_Column_List" /> from platform_credential
    where id = #{id,jdbcType=VARCHAR}
  </select>

  <select id="selectByName" resultMap="BaseResultMap">
    select <include refid="Base_Column_List" /> from platform_credential
    where name = #{name,jdbcType=VARCHAR}
  </select>

  <select id="selectAll" resultMap="BaseResultMap">
    select <include refid="Base_Column_List" /> from platform_credential
    order by `type` asc, name asc
  </select>

</mapper>
```

⚠️ `type` 与 `name` 都不是 MySQL 保留字，但 `type` 在本仓其它 mapper 里也加了反引号（如 `platform_audit_log` 的 `namespace` / `action` / `result`）——
**统一加反引号**，避免将来换列名时踩保留字。

- [ ] **Step 4: 编译**

```bash
cd /d/coding/k8s-cloud-platform
mvn -o -q -pl platform-data -am compile
```

Expected: 退出码 0，无输出。

- [ ] **Step 5: 核对列名与表结构逐一对得上**

XML 里的列名写错不会编译报错，只会在运行时炸。用 JDBC 把表结构打出来手工核对：

```bash
cd /d/coding/k8s-cloud-platform
cat > /tmp/cols.jsh <<'EOF'
import java.sql.*;
void main() throws Exception {
    String url = "jdbc:mysql://192.168.31.80:1234/k8s_cloud_platform?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=GMT%2B8&allowPublicKeyRetrieval=true";
    try (Connection c = DriverManager.getConnection(url, "root", "coding");
         Statement st = c.createStatement()) {
        ResultSet rs = st.executeQuery(
            "select column_name from information_schema.columns "
          + "where table_schema=database() and table_name='platform_credential' order by ordinal_position");
        while (rs.next()) System.out.println(rs.getString(1));
    }
}
EOF
jshell --execution local -q /tmp/cols.jsh
```

Expected（13 行，与 `Base_Column_List` 一一对应）：

```
id
name
type
endpoint
auth_type
secret_encrypted
secret_fingerprint
description
enabled
last_verified_at
last_verify_error
created_at
updated_at
```

- [ ] **Step 6: 提交**

```bash
git add platform-data/src/main/java/com/coding/data/models/system/PlatformCredential.java \
        platform-data/src/main/java/com/coding/data/mapper/system/PlatformCredentialMapper.java \
        platform-data/src/main/resources/mapper/system/PlatformCredentialMapper.xml
git commit -m "feat(credential): 数据层（model + mapper + XML）"
```

---

### Task 3: `CredentialCipher`（加密 / 解密 / 指纹 / 主密钥校验）

**Files:**
- Create: `platform-api/src/main/java/com/coding/platformapi/credential/CredentialCipher.java`
- Modify: `platform-common/src/main/java/com/coding/common/exception/EnumResponseType.java`（加两个码，见 Step 5）
- Test: `platform-api/src/test/java/com/coding/platformapi/credential/CredentialCipherTest.java`

**Interfaces:**
- Consumes: `AESUtils.encrypt/decrypt(String, String)`
- Produces:
  - `new CredentialCipher(String aesKey)`（Spring 用 `@Value` 注入）
  - `String encrypt(String plainJson)` → `"v1:" + 密文`
  - `String decrypt(String stored)` → 明文 JSON；前缀不认识时抛 `CloudPlatformException`
  - `static String fingerprint(String plainJson)` → 16 位 hex

- [ ] **Step 1: 先加两个错误码**

在 `platform-common/src/main/java/com/coding/common/exception/EnumResponseType.java` 的
`PERMISSION_ENDPOINT_UNCOVERED(10031, ...)` 这一行（第 57 行）**之后**插入：

```java
    CREDENTIAL_KEY_MISSING(10032, "凭据加密主密钥未配置"),
    CREDENTIAL_CIPHERTEXT_INVALID(10033, "凭据密文格式无法识别"),
```

- [ ] **Step 2: 写失败的测试**

创建 `platform-api/src/test/java/com/coding/platformapi/credential/CredentialCipherTest.java`：

```java
package com.coding.platformapi.credential;

import com.coding.common.exception.CloudPlatformException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link CredentialCipher}：加密往返、版本前缀、指纹、主密钥缺失的 fail-closed。
 *
 * <p>主密钥是**独立于 kubeconfig 那把**的（spec §3.1），所以本测试全部用测试专用密钥，不读配置。
 */
class CredentialCipherTest {

    private static final String KEY = "test-only-master-key-0123456789abcdef";

    CredentialCipher cipher = new CredentialCipher(KEY);

    @Test
    void encryptThenDecryptRoundTrips() {
        String plain = "{\"username\":\"robot$platform+ci\",\"secret\":\"s3cr3t\"}";

        assertThat(cipher.decrypt(cipher.encrypt(plain))).isEqualTo(plain);
    }

    @Test
    void ciphertextCarriesV1Prefix() {
        assertThat(cipher.encrypt("{\"secret\":\"x\"}")).startsWith("v1:");
    }

    @Test
    void samePlaintextEncryptsDifferentlyEachTime() {
        String plain = "{\"secret\":\"x\"}";

        // AESUtils 每次随机 salt + IV —— 指纹必须取明文，否则完全没有意义（spec §2.3）
        assertThat(cipher.encrypt(plain)).isNotEqualTo(cipher.encrypt(plain));
    }

    @Test
    void decryptRejectsUnknownPrefixInsteadOfGuessing() {
        // 静默降级会把「密钥配错了」伪装成「凭据错了」（spec §3.2）
        assertThatThrownBy(() -> cipher.decrypt("v9:whatever"))
                .isInstanceOf(CloudPlatformException.class);
        assertThatThrownBy(() -> cipher.decrypt("plaintext-without-prefix"))
                .isInstanceOf(CloudPlatformException.class);
    }

    @Test
    void decryptWithWrongKeyNeverYieldsThePlaintext() {
        String plain = "{\"secret\":\"x\"}";
        String stored = cipher.encrypt(plain);
        CredentialCipher other = new CredentialCipher("a-totally-different-master-key");

        // 不能写成 assertThatThrownBy：AES-CBC + PKCS5 下，错密钥解出的填充**有约 1/256 的概率碰巧合法**，
        // 那时 decrypt 会返回垃圾而不抛 —— 断言「必抛」就是一条约 0.4% 概率假红的 flaky 测试。
        // 真正要钉的不变量是「错密钥绝不静默地解出原文」，这条对两种分支都成立、且完全确定。
        try {
            assertThat(other.decrypt(stored)).isNotEqualTo(plain);
        } catch (CloudPlatformException expected) {
            // 常见分支：填充校验失败 → 转成业务码。这里吞掉是**刻意的**，见上面的注释。
        }
    }

    @Test
    void fingerprintIsStableAndIndependentOfAesRandomness() {
        String plain = "{\"username\":\"u\",\"secret\":\"s\"}";

        assertThat(CredentialCipher.fingerprint(plain))
                .hasSize(16)
                .isEqualTo(CredentialCipher.fingerprint(plain));
        assertThat(CredentialCipher.fingerprint(plain))
                .isNotEqualTo(CredentialCipher.fingerprint("{\"username\":\"u\",\"secret\":\"t\"}"));
    }

    @Test
    void blankOrMissingMasterKeyRefusesToStart() {
        // 关键回归：不能像 kubeconfig 那把密钥一样有硬编码默认值（spec §3.3）——
        // 否则「忘了配环境变量」会静默地用公开默认值加密真实凭据。
        assertThatThrownBy(() -> new CredentialCipher("").afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("platform.credential.aes-key");
        assertThatThrownBy(() -> new CredentialCipher(null).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void presentMasterKeyStarts() {
        assertThatCode(() -> new CredentialCipher(KEY).afterPropertiesSet()).doesNotThrowAnyException();
    }
}
```

- [ ] **Step 3: 跑测试，确认失败**

```bash
cd /d/coding/k8s-cloud-platform
mvn -o -pl platform-api -am test -Dtest=CredentialCipherTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: **编译失败** —— `找不到符号: 类 CredentialCipher`。

- [ ] **Step 4: 写实现**

创建 `platform-api/src/main/java/com/coding/platformapi/credential/CredentialCipher.java`：

```java
package com.coding.platformapi.credential;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.utils.AESUtils;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 凭据密文的加解密与指纹。设计见 docs/superpowers/specs/2026-10-13-credential-management-design.md §3。
 *
 * <h3>复用算法，不复用主密钥</h3>
 * 算法复用 {@link AESUtils}（kubeconfig 的先例），但**主密钥是独立配的**
 * （{@code platform.credential.aes-key}）。理由：轮换边界独立；且不扩散
 * {@code k8s.cloud.kubeconfig.aes-key} 那个把默认密钥硬编码在 application.yaml 里的坏味道。
 *
 * <h3>为什么密钥缺失要拒绝启动</h3>
 * 有默认值的话，「忘了配环境变量」会**静默地**用那把公开的默认密钥加密真实凭据 ——
 * 事后无法察觉，因为一切看起来都正常。这里取 fail-closed。
 *
 * <h3>为什么密文带 v1: 前缀</h3>
 * {@link AESUtils} 自身没有版本概念。加前缀是零成本，而将来换算法时能识别旧密文。
 * 遇到不认识的前缀**必须抛**，绝不静默当明文或按 v1 硬解 —— 那会把「密钥配错了」
 * 伪装成「凭据错了」，把排查引到完全错的方向。
 */
@Slf4j
@Component
public class CredentialCipher {

    static final String PREFIX_V1 = "v1:";

    /** 指纹取 SHA-256 的前 16 位 hex（8 字节） */
    static final int FINGERPRINT_CHARS = 16;

    private final String aesKey;

    public CredentialCipher(@Value("${platform.credential.aes-key:}") String aesKey) {
        this.aesKey = aesKey;
    }

    /**
     * 启动期校验。方法名刻意不叫 init 之类，就是为了让单测能直接调它而不起 Spring 上下文。
     */
    @PostConstruct
    public void afterPropertiesSet() {
        if (!StringUtils.hasText(aesKey)) {
            throw new IllegalStateException(
                    "凭据加密主密钥未配置：请设置 platform.credential.aes-key（环境变量 CREDENTIAL_AES_KEY）。"
                  + "可用 AESUtils.generateStrongKey() 生成一把。"
                  + "本模块有意不提供默认密钥 —— 否则忘配时会静默地用公开默认值加密真实凭据。");
        }
    }

    /** 加密认证字段 JSON → {@code "v1:" + base64} */
    public String encrypt(String plainJson) {
        return PREFIX_V1 + AESUtils.encrypt(plainJson, aesKey);
    }

    /** 解密；前缀不认识或密钥不对都抛，不降级。 */
    public String decrypt(String stored) {
        if (!StringUtils.hasText(stored)) {
            throw new CloudPlatformException(EnumResponseType.CREDENTIAL_CIPHERTEXT_INVALID,
                    "凭据密文为空");
        }
        if (!stored.startsWith(PREFIX_V1)) {
            throw new CloudPlatformException(EnumResponseType.CREDENTIAL_CIPHERTEXT_INVALID,
                    "凭据密文缺少 " + PREFIX_V1 + " 前缀，拒绝按旧格式猜测");
        }
        try {
            return AESUtils.decrypt(stored.substring(PREFIX_V1.length()), aesKey);
        } catch (RuntimeException e) {
            // AESUtils 抛的是 RuntimeException("数据解密失败…")：转成业务码，别让它冒成 500
            throw new CloudPlatformException(EnumResponseType.CREDENTIAL_CIPHERTEXT_INVALID,
                    "凭据解密失败（密文被篡改，或主密钥与写入时不一致）");
        }
    }

    /**
     * 指纹 = SHA-256(明文 JSON) 前 16 位 hex。
     *
     * <p><b>取明文而非密文</b>：{@link AESUtils} 每次加密用随机 salt + IV，密文每次都不同，
     * 拿密文做指纹毫无意义。指纹的用途是区分「改了凭据」与「只改了描述」。
     */
    public static String fingerprint(String plainJson) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(plainJson.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(FINGERPRINT_CHARS);
            for (int i = 0; i < FINGERPRINT_CHARS / 2; i++) {
                sb.append(String.format("%02x", digest[i]));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
```

- [ ] **Step 5: 跑测试，确认通过**

```bash
cd /d/coding/k8s-cloud-platform
mvn -o -pl platform-api -am test -Dtest=CredentialCipherTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: `Tests run: 8, Failures: 0, Errors: 0, Skipped: 0`

- [ ] **Step 6: 提交**

```bash
git add platform-api/src/main/java/com/coding/platformapi/credential/CredentialCipher.java \
        platform-api/src/test/java/com/coding/platformapi/credential/CredentialCipherTest.java \
        platform-common/src/main/java/com/coding/common/exception/EnumResponseType.java
git commit -m "feat(credential): CredentialCipher（v1 前缀 + 指纹 + 主密钥 fail-closed）"
```

---

### Task 4: `CredentialProbe`（URL 构造 + 响应判定的纯规则）

**Files:**
- Create: `platform-api/src/main/java/com/coding/platformapi/credential/ProbeResult.java`
- Create: `platform-api/src/main/java/com/coding/platformapi/credential/CredentialProbe.java`
- Test: `platform-api/src/test/java/com/coding/platformapi/credential/CredentialProbeTest.java`

**Interfaces:**
- Consumes: 无（纯函数，无 IO）
- Produces:
  - `record ProbeResult(boolean ok, String message, String suggestedUrl)`
  - `static String CredentialProbe.gitProbeUrl(String repoUrl)`
  - `static String CredentialProbe.registryProbeUrl(String endpoint)`
  - `static String CredentialProbe.repoUrlFromLocation(String location)`
  - `static ProbeResult CredentialProbe.classifyGit(int status, String body, String location)`
  - `static ProbeResult CredentialProbe.classifyRegistry(int status)`

- [ ] **Step 1: 写失败的测试**

创建 `platform-api/src/test/java/com/coding/platformapi/credential/CredentialProbeTest.java`：

```java
package com.coding.platformapi.credential;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link CredentialProbe} 的纯规则。**不含 IO，所以这里的每一条都是确定性断言。**
 *
 * <p>本测试的用例全部来自 2026-10-13 的实测（spec §0）：
 * GitLab 少 `.git` 返回 301、gitee 返回 200；公共仓库带错凭据返回 401（不是 200）；
 * 不存在的仓库也是 401；200 的响应体以 git pkt-line 头开头。
 */
class CredentialProbeTest {

    private static final String PKT_LINE = "001e# service=git-upload-pack";

    // ---- URL 构造 ----

    @Test
    void gitProbeUrlStripsTrailingSlashes() {
        assertThat(CredentialProbe.gitProbeUrl("https://gitlab.example.com/g/r.git/"))
                .isEqualTo("https://gitlab.example.com/g/r.git/info/refs?service=git-upload-pack");
        assertThat(CredentialProbe.gitProbeUrl("https://gitlab.example.com/g/r.git"))
                .isEqualTo("https://gitlab.example.com/g/r.git/info/refs?service=git-upload-pack");
    }

    @Test
    void gitProbeUrlDoesNotGuessDotGitSuffix() {
        // spec §5.2：不猜后缀。补了的话在别的 host 上是假阴性，用户还看不出为什么。
        assertThat(CredentialProbe.gitProbeUrl("https://gitlab.example.com/g/r"))
                .isEqualTo("https://gitlab.example.com/g/r/info/refs?service=git-upload-pack");
    }

    @Test
    void registryProbeUrlAddsSchemeAndStripsTrailingSlash() {
        assertThat(CredentialProbe.registryProbeUrl("harbor.example.com"))
                .isEqualTo("https://harbor.example.com/v2/");
        assertThat(CredentialProbe.registryProbeUrl("harbor.example.com/"))
                .isEqualTo("https://harbor.example.com/v2/");
        assertThat(CredentialProbe.registryProbeUrl("https://harbor.example.com/"))
                .isEqualTo("https://harbor.example.com/v2/");
        assertThat(CredentialProbe.registryProbeUrl("http://harbor.internal:5000"))
                .isEqualTo("http://harbor.internal:5000/v2/");
    }

    @Test
    void repoUrlFromLocationStripsProbePath() {
        assertThat(CredentialProbe.repoUrlFromLocation(
                "https://gitlab.com/g/r.git/info/refs?service=git-upload-pack"))
                .isEqualTo("https://gitlab.com/g/r.git");
        assertThat(CredentialProbe.repoUrlFromLocation(null)).isNull();
    }

    // ---- git 判定 ----

    @Test
    void git200WithSmartProtocolBodyPasses() {
        assertThat(CredentialProbe.classifyGit(200, PKT_LINE + "\n0000014e8a", null).ok()).isTrue();
    }

    @Test
    void git200WithHtmlBodyIsNotAPass() {
        // 反向代理登录页 / captive portal 的典型行为：200 + HTML。只判状态码会判成「通过」（spec §5.3）。
        ProbeResult r = CredentialProbe.classifyGit(200, "<!DOCTYPE html><html>login</html>", null);

        assertThat(r.ok()).isFalse();
        assertThat(r.message()).contains("非 git");
    }

    @Test
    void git3xxTellsUserTheAddressInsteadOfPassword() {
        // spec §5.2：3xx 多半是地址少了 .git，不是对端故障 —— 要给可操作的提示
        ProbeResult r = CredentialProbe.classifyGit(301, "",
                "https://gitlab.com/g/r.git/info/refs?service=git-upload-pack");

        assertThat(r.ok()).isFalse();
        assertThat(r.suggestedUrl()).isEqualTo("https://gitlab.com/g/r.git");
    }

    @Test
    void git401NeverSaysPassword() {
        // spec §5.4：401 分不清「凭据错」与「凭据对但无权访问此仓库」（实测：不存在的仓库也是 401）。
        // 文案里出现「密码」会把管理员引去改一个没问题的密码。
        for (int status : new int[] {401, 403}) {
            ProbeResult r = CredentialProbe.classifyGit(status, "", null);
            assertThat(r.ok()).isFalse();
            assertThat(r.message()).contains("无权访问");
            assertThat(r.message()).doesNotContain("密码");
        }
    }

    @Test
    void git404BlamesTheAddressNotTheCredential() {
        ProbeResult r = CredentialProbe.classifyGit(404, "", null);

        assertThat(r.ok()).isFalse();
        assertThat(r.message()).contains("地址");
        assertThat(r.message()).doesNotContain("密码");
    }

    @Test
    void git500IsReportedWithStatusCode() {
        assertThat(CredentialProbe.classifyGit(500, "", null).message()).contains("500");
    }

    // ---- registry 判定 ----

    @Test
    void registry200Passes() {
        assertThat(CredentialProbe.classifyRegistry(200).ok()).isTrue();
    }

    @Test
    void registry401NeverSaysPassword() {
        for (int status : new int[] {401, 403}) {
            ProbeResult r = CredentialProbe.classifyRegistry(status);
            assertThat(r.ok()).isFalse();
            assertThat(r.message()).contains("凭据");
            assertThat(r.message()).doesNotContain("密码");
        }
    }

    @Test
    void registry404BlamesTheAddress() {
        assertThat(CredentialProbe.classifyRegistry(404).message()).contains("registry");
    }
}
```

- [ ] **Step 2: 跑测试，确认失败**

```bash
cd /d/coding/k8s-cloud-platform
mvn -o -pl platform-api -am test -Dtest=CredentialProbeTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: **编译失败** —— `找不到符号: 类 CredentialProbe` / `类 ProbeResult`。

- [ ] **Step 3: 写 `ProbeResult`**

创建 `platform-api/src/main/java/com/coding/platformapi/credential/ProbeResult.java`：

```java
package com.coding.platformapi.credential;

/**
 * 一次测试连接的结果。
 *
 * @param ok           通过与否
 * @param message      给用户看的原因（**凭据类失败一律二义文案**，见 spec §5.4）
 * @param suggestedUrl 非空时表示「对端要求改用这个地址」（3xx 的 Location 剥出来的），
 *                     前端要提供一键填入，别让用户手工抄一个长 URL
 */
public record ProbeResult(boolean ok, String message, String suggestedUrl) {

    public static ProbeResult pass() {
        return new ProbeResult(true, "连接成功", null);
    }

    public static ProbeResult fail(String message) {
        return new ProbeResult(false, message, null);
    }

    public static ProbeResult failWithUrl(String message, String suggestedUrl) {
        return new ProbeResult(false, message, suggestedUrl);
    }
}
```

- [ ] **Step 4: 写 `CredentialProbe`**

创建 `platform-api/src/main/java/com/coding/platformapi/credential/CredentialProbe.java`：

```java
package com.coding.platformapi.credential;

/**
 * 测试连接的**纯规则**：URL 构造与响应判定。**不含 IO**，所以可单测。
 * 出站那一半在 {@link CredentialProbeClient}。
 *
 * <p>设计见 docs/superpowers/specs/2026-10-13-credential-management-design.md §5，
 * 每条规则的依据都在 §0 的实测表里。
 */
public final class CredentialProbe {

    /**
     * git 智能协议的 pkt-line 头。**200 的响应体必须以此开头才算通过**（spec §5.3）。
     *
     * <p>只看状态码会被一整类假阳性骗过：反向代理的登录页、SSO 跳转、captive portal
     * 都可能对 {@code info/refs} 返回 200 + HTML。
     */
    static final String GIT_SMART_PREFIX = "001e# service=git-upload-pack";

    private CredentialProbe() {
    }

    /**
     * 规范化 git 探测 URL：去尾部斜杠 + 拼 {@code /info/refs?service=git-upload-pack}。
     *
     * <p><b>不猜 {@code .git} 后缀</b>（spec §5.2）：实测 GitLab 省略它是 301、gitee 是 200，
     * 各 host 不一致。补后缀是一种猜测，猜错在别的 host 上就是假阴性，且用户看不出为什么。
     */
    public static String gitProbeUrl(String repoUrl) {
        return stripTrailingSlashes(repoUrl) + "/info/refs?service=git-upload-pack";
    }

    /** 规范化 registry 探测 URL：补 scheme（缺省 https）+ 去尾斜杠 + {@code /v2/}。 */
    public static String registryProbeUrl(String endpoint) {
        String base = stripTrailingSlashes(endpoint);
        if (!base.startsWith("http://") && !base.startsWith("https://")) {
            base = "https://" + base;
        }
        return base + "/v2/";
    }

    /**
     * 把 3xx 的 {@code Location} 还原成仓库地址（剥掉 {@code /info/refs…} 之后的全部内容），
     * 供「对端要求地址为 X」的提示与一键回填。
     */
    public static String repoUrlFromLocation(String location) {
        if (location == null || location.isBlank()) {
            return null;
        }
        int idx = location.indexOf("/info/refs");
        return idx > 0 ? location.substring(0, idx) : location;
    }

    /**
     * git 探测的结果判定。
     *
     * <p><b>401 / 403 的文案必须二义</b>（spec §5.4）：实测「不存在的仓库」与「凭据错」
     * 返回同一个 401，所以分不清。写「密码错误」会把管理员引去改一个没问题的密码。
     */
    public static ProbeResult classifyGit(int status, String body, String location) {
        if (status == 200) {
            return body != null && body.startsWith(GIT_SMART_PREFIX)
                    ? ProbeResult.pass()
                    : ProbeResult.fail("对端返回了非 git 协议内容，地址可能指向了网关或登录页");
        }
        if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
            String repo = repoUrlFromLocation(location);
            return repo != null
                    ? ProbeResult.failWithUrl("对端要求跳转，请改用该地址", repo)
                    : ProbeResult.fail("对端返回了 " + status + " 跳转，但未给出目标地址");
        }
        if (status == 401 || status == 403) {
            return ProbeResult.fail("凭据不通过，或该凭据无权访问此仓库");
        }
        if (status == 404) {
            return ProbeResult.fail("地址不对或仓库不存在");
        }
        return ProbeResult.fail("对端返回 " + status);
    }

    /**
     * registry 探测的结果判定。{@code /v2/} 是 OCI Distribution 规范的一部分，所有 registry 都实现，
     * 所以不需要额外的探测入参。
     */
    public static ProbeResult classifyRegistry(int status) {
        if (status == 200) {
            return ProbeResult.pass();
        }
        if (status == 401 || status == 403) {
            return ProbeResult.fail("凭据不通过，或该凭据无权访问此 registry");
        }
        if (status == 404) {
            return ProbeResult.fail("地址不对：该地址不是 registry（/v2/ 不存在）");
        }
        return ProbeResult.fail("对端返回 " + status);
    }

    private static String stripTrailingSlashes(String s) {
        String base = s == null ? "" : s.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base;
    }
}
```

- [ ] **Step 5: 跑测试，确认通过**

```bash
cd /d/coding/k8s-cloud-platform
mvn -o -pl platform-api -am test -Dtest=CredentialProbeTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: `Tests run: 13, Failures: 0, Errors: 0, Skipped: 0`

- [ ] **Step 6: 提交**

```bash
git add platform-api/src/main/java/com/coding/platformapi/credential/ProbeResult.java \
        platform-api/src/main/java/com/coding/platformapi/credential/CredentialProbe.java \
        platform-api/src/test/java/com/coding/platformapi/credential/CredentialProbeTest.java
git commit -m "feat(credential): CredentialProbe 纯规则（URL 规范化 + 响应判定）"
```

---

### Task 5: `CredentialProbeClient`（出站 HTTP）

**Files:**
- Create: `platform-api/src/main/java/com/coding/platformapi/credential/CredentialProbeClient.java`
- Test: `platform-api/src/test/java/com/coding/platformapi/credential/CredentialProbeClientTest.java`

**Interfaces:**
- Consumes: `CredentialProbe.*`（Task 4）
- Produces:
  - `ProbeResult CredentialProbeClient.probeGit(String repoUrl, String username, String secret)`
  - `ProbeResult CredentialProbeClient.probeRegistry(String endpoint, String username, String secret)`
  - 包私有构造器 `CredentialProbeClient(RestClient)` —— 供测试注入 `MockRestServiceServer`

- [ ] **Step 1: 写失败的测试**

创建 `platform-api/src/test/java/com/coding/platformapi/credential/CredentialProbeClientTest.java`：

```java
package com.coding.platformapi.credential;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * {@link CredentialProbeClient} 的 **HTTP 装配**：URL 拼出来对不对、Basic 头带没带、响应有没有喂给规则。
 *
 * <p>规则本身的用例全在 {@link CredentialProbeTest}（纯函数）。这里只验「发出去的东西对不对」——
 * 两半分开测，是因为混在一起时一次失败看不出是 URL 拼错了还是判定写错了。
 */
class CredentialProbeClientTest {

    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final CredentialProbeClient client = new CredentialProbeClient(builder.build());

    @Test
    void gitHitsInfoRefsWithBasicAuthAndReadsBody() {
        server.expect(requestTo("https://g.example.com/r.git/info/refs?service=git-upload-pack"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Basic " + base64("ci-bot:tok")))
                .andRespond(withSuccess("001e# service=git-upload-pack\n0000", MediaType.TEXT_PLAIN));

        assertThat(client.probeGit("https://g.example.com/r.git", "ci-bot", "tok").ok()).isTrue();
        server.verify();
    }

    @Test
    void git401BecomesTheAmbiguousMessage() {
        server.expect(requestTo("https://g.example.com/r.git/info/refs?service=git-upload-pack"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        ProbeResult r = client.probeGit("https://g.example.com/r.git", "u", "p");

        assertThat(r.ok()).isFalse();
        assertThat(r.message()).doesNotContain("密码");
    }

    @Test
    void registryHitsV2() {
        server.expect(requestTo("https://harbor.example.com/v2/"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        assertThat(client.probeRegistry("harbor.example.com", "u", "p").ok()).isTrue();
    }

    private static String base64(String s) {
        return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }
}
```

- [ ] **Step 2: 跑测试，确认失败**

```bash
cd /d/coding/k8s-cloud-platform
mvn -o -pl platform-api -am test -Dtest=CredentialProbeClientTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: **编译失败** —— `找不到符号: 类 CredentialProbeClient`。

- [ ] **Step 3: 写实现**

创建 `platform-api/src/main/java/com/coding/platformapi/credential/CredentialProbeClient.java`：

```java
package com.coding.platformapi.credential;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 测试连接的**出站**那一半。规则全在 {@link CredentialProbe}（纯函数、可单测），
 * 本类只负责「发请求 + 把响应喂给规则」。
 *
 * <p>超时范式与 {@code PromDiscoveryClient} 一致（{@code SimpleClientHttpRequestFactory} 显式设置），
 * **绝不用默认的无限等待** —— 对端黑洞会把请求线程挂住（spec §5.5）。
 *
 * <p><b>重定向策略 {@code NEVER}</b>（spec §5.2）：跟随重定向会让「通不通」变成「对端愿不愿意带你跳」，
 * 而且 {@code Authorization} 头在重定向中的传递规则是 host / 跳转类型相关的 ——
 * 一旦被丢弃就是假阴性，现象是「地址明明对、凭据明明对，就是不通」，极难排查。
 * 因此 3xx 一律按状态码返回，由 {@link CredentialProbe} 转成「对端要求地址为 X」的可操作提示。
 */
@Slf4j
@Component
public class CredentialProbeClient {

    private static final int CONNECT_TIMEOUT_MS = 5_000;

    private static final int READ_TIMEOUT_MS = 10_000;

    private final RestClient restClient;

    public CredentialProbeClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT_MS);
        factory.setReadTimeout(READ_TIMEOUT_MS);
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    /**
     * 测试用：注入一个绑定了 {@code MockRestServiceServer} 的 RestClient。
     * 默认构造器造的客户端不可注入，URL 拼接与 Basic 头就只能靠部署后手验。
     */
    CredentialProbeClient(RestClient restClient) {
        this.restClient = restClient;
    }

    public ProbeResult probeGit(String repoUrl, String username, String secret) {
        return probe(CredentialProbe.gitProbeUrl(repoUrl), username, secret, true);
    }

    public ProbeResult probeRegistry(String endpoint, String username, String secret) {
        return probe(CredentialProbe.registryProbeUrl(endpoint), username, secret, false);
    }

    private ProbeResult probe(String url, String username, String secret, boolean git) {
        try {
            var response = restClient.get()
                    .uri(java.net.URI.create(url))
                    .header(HttpHeaders.AUTHORIZATION, basic(username, secret))
                    .exchange((request, res) -> {
                        String body = new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8);
                        String location = res.getHeaders().getFirst(HttpHeaders.LOCATION);
                        int status = res.getStatusCode().value();
                        return git
                                ? CredentialProbe.classifyGit(status, body, location)
                                : CredentialProbe.classifyRegistry(status);
                    });
            return response != null ? response : ProbeResult.fail("对端返回空响应");
        } catch (Exception e) {
            // 不可达是最需要提示到位的一类：平台服务器必须能出站访问该地址（spec §0.1 的环境提醒）
            log.warn("凭据测试连接失败 url={}", url, e);
            return ProbeResult.fail("网络不可达：" + e.getMessage()
                    + "（请确认平台服务器能出站访问该地址）");
        }
    }

    private static String basic(String username, String secret) {
        String raw = (username == null ? "" : username) + ":" + (secret == null ? "" : secret);
        return "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }
}
```

⚠️ 若 `exchange` 的回调签名与 Spring 7 实际不一致（例如要求返回 `ResponseEntity` 或 `void`），
**以编译错误为准就地修正**，规则与语义不变。

- [ ] **Step 4: 跑测试，确认通过**

```bash
cd /d/coding/k8s-cloud-platform
mvn -o -pl platform-api -am test -Dtest=CredentialProbeClientTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: `Tests run: 3, Failures: 0, Errors: 0, Skipped: 0`

若报 `MockRestServiceServer` 找不到，说明 `spring-test` 不在 platform-api 的测试依赖里 ——
本仓其它测试已用到 `MockHttpServletRequest`，正常情况下它在。确实不在时，
在 `platform-api/pom.xml` 加 `spring-boot-starter-test`（`<scope>test</scope>`）后重跑。

- [ ] **Step 5: 清理未使用的 import**

```bash
cd /d/coding/k8s-cloud-platform
grep -n "HttpStatus" platform-api/src/main/java/com/coding/platformapi/credential/CredentialProbeClient.java
```

Expected: 无输出。若有，删掉那一行 import（本类只用状态码的 int 值）。

- [ ] **Step 6: 提交**

```bash
git add platform-api/src/main/java/com/coding/platformapi/credential/CredentialProbeClient.java \
        platform-api/src/test/java/com/coding/platformapi/credential/CredentialProbeClientTest.java
git commit -m "feat(credential): CredentialProbeClient（出站探测，超时显式、重定向 NEVER）"
```

---

### Task 6: DTO + `CredentialService`

**Files:**
- Create: `platform-api/src/main/java/com/coding/platformapi/models/CredentialView.java`
- Create: `platform-api/src/main/java/com/coding/platformapi/models/CredentialCreateRequest.java`
- Create: `platform-api/src/main/java/com/coding/platformapi/models/CredentialUpdateRequest.java`
- Create: `platform-api/src/main/java/com/coding/platformapi/models/CredentialIdRequest.java`
- Create: `platform-api/src/main/java/com/coding/platformapi/models/CredentialTestRequest.java`
- Create: `platform-api/src/main/java/com/coding/platformapi/models/CredentialTestResult.java`
- Create: `platform-api/src/main/java/com/coding/platformapi/credential/CredentialSecret.java`
- Create: `platform-api/src/main/java/com/coding/platformapi/services/CredentialService.java`
- Modify: `platform-common/src/main/java/com/coding/common/exception/EnumResponseType.java`（加 3 个码）
- Test: `platform-api/src/test/java/com/coding/platformapi/services/CredentialServiceTest.java`

**Interfaces:**
- Consumes: `PlatformCredentialMapper`（Task 2）、`CredentialCipher`（Task 3）、`CredentialProbeClient`（Task 5）
- Produces:
  - `List<CredentialView> CredentialService.list()`
  - `CredentialView CredentialService.create(CredentialCreateRequest req)`
  - `CredentialView CredentialService.update(CredentialUpdateRequest req)`
  - `void CredentialService.delete(String id)`
  - `CredentialTestResult CredentialService.test(CredentialTestRequest req)`
  - `CredentialSecret CredentialService.resolveSecret(String id)` —— 下游消费方（S3.1/S3.5）经它取明文

- [ ] **Step 1: 加 3 个错误码**

在 `EnumResponseType` 的 `CREDENTIAL_CIPHERTEXT_INVALID(10033, ...)`（Task 3 加的那行）**之后**插入：

```java
    CREDENTIAL_NOT_EXIST(10034, "凭据不存在"),
    CREDENTIAL_NAME_EXIST(10035, "凭据名称已存在"),
    CREDENTIAL_INVALID(10036, "凭据参数非法"),
```

- [ ] **Step 2: 写 DTO**

创建 `platform-api/src/main/java/com/coding/platformapi/models/CredentialView.java`：

```java
package com.coding.platformapi.models;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.Date;

/**
 * 凭据的**对外视图**：列表与创建/更新的响应都是它。
 *
 * <p>⚠️ <b>本类永不携带凭据内容</b> —— 没有 username、没有密码、没有密文（spec §5 已定决策
 * 「永不回显」）。认证字段只以 {@link #hasSecret}（有没有配）与 {@link #fingerprint}
 * （哪一份，用于区分「改了凭据」与「只改了描述」）两种派生形态出现。
 *
 * <p>{@code CredentialServiceTest} 里有一条**反射断言**钉住这一点：任何名字里含
 * {@code secret} / {@code password} / {@code username} 的字段都会让测试失败。
 * 加字段前先读那段注释。
 */
@Data
public class CredentialView {

    private String id;

    private String name;

    /** GIT / REGISTRY */
    private String type;

    @Schema(description = "host 基址")
    private String endpoint;

    /** BASIC */
    private String authType;

    @Schema(description = "是否已配置凭据内容（不是内容本身）")
    private Boolean hasSecret;

    @Schema(description = "凭据指纹：明文认证字段 JSON 的 SHA-256 前 16 位")
    private String fingerprint;

    private String description;

    private Boolean enabled;

    @Schema(description = "上次测试通过的时刻；测失败不改它")
    private Date lastVerifiedAt;

    @Schema(description = "上次测试的失败原因；成功则 null")
    private String lastVerifyError;

    private Date createdAt;

    private Date updatedAt;
}
```

创建 `platform-api/src/main/java/com/coding/platformapi/models/CredentialCreateRequest.java`：

```java
package com.coding.platformapi.models;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/** 新建凭据。{@code username} / {@code secret} 必填（v1 只有 BASIC）。 */
@Data
public class CredentialCreateRequest {

    private String name;

    /** GIT / REGISTRY */
    private String type;

    private String endpoint;

    /** BASIC */
    private String authType;

    private String username;

    @Schema(description = "密码或令牌明文。**只在这一次请求里存在**，落库即加密，此后永不回显")
    private String secret;

    private String description;

    @Schema(description = "缺省 true")
    private Boolean enabled;
}
```

创建 `platform-api/src/main/java/com/coding/platformapi/models/CredentialUpdateRequest.java`：

```java
package com.coding.platformapi.models;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 更新凭据。
 *
 * <p><b>{@code username} / {@code secret} 留空 = 保持不变</b>（逐字段）。这是「永不回显」的必然结果 ——
 * 表单里拿不到旧值，所以「空」只能解读为「不改」。
 *
 * <p><b>例外：{@code authType} 变更时必须重填</b> —— 密文是绑 authType 解释的 JSON，
 * 跨类型沿用会让旧 JSON 的语义对不上。
 */
@Data
public class CredentialUpdateRequest {

    private String id;

    private String name;

    private String type;

    private String endpoint;

    private String authType;

    @Schema(description = "留空 = 保持不变")
    private String username;

    @Schema(description = "留空 = 保持不变")
    private String secret;

    private String description;

    private Boolean enabled;
}
```

创建 `platform-api/src/main/java/com/coding/platformapi/models/CredentialIdRequest.java`：

```java
package com.coding.platformapi.models;

import lombok.Data;

/** 只带 id 的请求（删除）。 */
@Data
public class CredentialIdRequest {

    private String id;
}
```

创建 `platform-api/src/main/java/com/coding/platformapi/models/CredentialTestRequest.java`：

```java
package com.coding.platformapi.models;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 测试连接。**只测已保存的凭据**（spec §5）：不支持「表单里先测再存」——
 * 那会让明文多一条传输路径，且未保存的凭据没有指纹、没有审计目标。
 */
@Data
public class CredentialTestRequest {

    private String id;

    @Schema(description = "GIT 类型必填：用于探测的**完整**仓库 URL（含 .git）。它是本动作的入参，不是连接的属性")
    private String probeUrl;
}
```

创建 `platform-api/src/main/java/com/coding/platformapi/models/CredentialTestResult.java`：

```java
package com.coding.platformapi.models;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 测试连接的结果。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CredentialTestResult {

    private Boolean ok;

    /** 给用户看的原因。凭据类失败一律**二义文案**（spec §5.4） */
    private String message;

    @Schema(description = "非空表示「对端要求改用这个地址」，前端应提供一键填入")
    private String suggestedUrl;
}
```

创建 `platform-api/src/main/java/com/coding/platformapi/credential/CredentialSecret.java`：

```java
package com.coding.platformapi.credential;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 认证字段的**明文**载体，也是 {@code secret_encrypted} 解密后的 JSON 结构。
 *
 * <p>v1 只有 BASIC 一种，所以只有这两个字段。加 SSH 是在这里加字段 —— 表结构不动（spec §4）。
 *
 * <p>⚠️ 本类的实例**不得出现在任何响应体里**。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CredentialSecret {

    private String username;

    private String secret;
}
```

- [ ] **Step 3: 写失败的测试**

创建 `platform-api/src/test/java/com/coding/platformapi/services/CredentialServiceTest.java`：

```java
package com.coding.platformapi.services;

import com.coding.common.exception.CloudPlatformException;
import com.coding.data.mapper.system.PlatformCredentialMapper;
import com.coding.data.models.system.PlatformCredential;
import com.coding.platformapi.credential.CredentialCipher;
import com.coding.platformapi.credential.CredentialProbeClient;
import com.coding.platformapi.credential.CredentialSecret;
import com.coding.platformapi.credential.ProbeResult;
import com.coding.platformapi.models.CredentialCreateRequest;
import com.coding.platformapi.models.CredentialTestRequest;
import com.coding.platformapi.models.CredentialUpdateRequest;
import com.coding.platformapi.models.CredentialView;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link CredentialService}：脱敏契约、留空=保持不变、写回测试结果。
 *
 * <p>外加一条**结构性钉子**（{@link #viewDtoHasNoCredentialField()}）—— 见其 javadoc。
 */
class CredentialServiceTest {

    private static final String KEY = "test-only-master-key-0123456789abcdef";

    PlatformCredentialMapper mapper = mock(PlatformCredentialMapper.class);
    CredentialProbeClient probeClient = mock(CredentialProbeClient.class);
    CredentialCipher cipher = new CredentialCipher(KEY);
    CredentialService svc = new CredentialService(mapper, cipher, probeClient);

    private PlatformCredential stored(String id, String name, String type, String endpoint,
                                     String username, String secret) {
        PlatformCredential c = new PlatformCredential();
        c.setId(id);
        c.setName(name);
        c.setType(type);
        c.setEndpoint(endpoint);
        c.setAuthType("BASIC");
        String json = "{\"username\":\"" + username + "\",\"secret\":\"" + secret + "\"}";
        c.setSecretEncrypted(cipher.encrypt(json));
        c.setSecretFingerprint(CredentialCipher.fingerprint(json));
        c.setEnabled(1);
        return c;
    }

    // ---- 脱敏 ----

    @Test
    void listNeverExposesSecretOrUsername() {
        when(mapper.selectAll()).thenReturn(List.of(
                stored("c1", "harbor-prod", "REGISTRY", "harbor.example.com", "robot$platform+ci", "s3cr3t")));

        CredentialView v = svc.list().get(0);

        assertThat(v.getHasSecret()).isTrue();
        assertThat(v.getFingerprint()).hasSize(16);
        assertThat(v.getEndpoint()).isEqualTo("harbor.example.com");
        // 指纹不能等于任何形式的明文
        assertThat(v.getFingerprint()).doesNotContain("s3cr3t").doesNotContain("robot");
    }

    @Test
    void createResponseIsAlsoRedacted() {
        when(mapper.selectByName(anyString())).thenReturn(null);
        when(mapper.insert(any())).thenReturn(1);

        CredentialCreateRequest req = new CredentialCreateRequest();
        req.setName("gitlab");
        req.setType("GIT");
        req.setEndpoint("https://gitlab.example.com");
        req.setAuthType("BASIC");
        req.setUsername("ci-bot");
        req.setSecret("glpat-xxxxxxxxxxxx");

        CredentialView v = svc.create(req);

        assertThat(v.getHasSecret()).isTrue();
        assertThat(v.getFingerprint()).hasSize(16);
        // 响应对象上根本没有承载明文的字段可断言 —— 由下面那条反射钉子保证
    }

    @Test
    void storedCiphertextIsNotThePlaintext() {
        when(mapper.selectByName(anyString())).thenReturn(null);
        when(mapper.insert(any())).thenReturn(1);

        CredentialCreateRequest req = new CredentialCreateRequest();
        req.setName("gitlab");
        req.setType("GIT");
        req.setEndpoint("https://gitlab.example.com");
        req.setAuthType("BASIC");
        req.setUsername("ci-bot");
        req.setSecret("glpat-xxxxxxxxxxxx");

        svc.create(req);

        var captor = org.mockito.ArgumentCaptor.forClass(PlatformCredential.class);
        verify(mapper).insert(captor.capture());
        PlatformCredential written = captor.getValue();
        assertThat(written.getSecretEncrypted()).startsWith("v1:").doesNotContain("glpat-xxxxxxxxxxxx");
        // 落库后仍能解回原文（这是本批唯一一条「加密真的能往返」的端到端证据）
        assertThat(cipher.decrypt(written.getSecretEncrypted())).contains("glpat-xxxxxxxxxxxx");
    }

    // ---- 参数校验 ----

    @Test
    void createRejectsUnknownType() {
        CredentialCreateRequest req = new CredentialCreateRequest();
        req.setName("x");
        req.setType("SFTP");
        req.setEndpoint("h");
        req.setAuthType("BASIC");
        req.setUsername("u");
        req.setSecret("s");

        assertThatThrownBy(() -> svc.create(req)).isInstanceOf(CloudPlatformException.class);
    }

    @Test
    void createRejectsBlankSecret() {
        CredentialCreateRequest req = new CredentialCreateRequest();
        req.setName("x");
        req.setType("GIT");
        req.setEndpoint("https://g.example.com/r.git");
        req.setAuthType("BASIC");
        req.setUsername("u");
        req.setSecret("   ");

        assertThatThrownBy(() -> svc.create(req)).isInstanceOf(CloudPlatformException.class);
    }

    @Test
    void createRejectsDuplicateName() {
        when(mapper.selectByName("dup")).thenReturn(stored("other", "dup", "GIT", "h", "u", "s"));

        CredentialCreateRequest req = new CredentialCreateRequest();
        req.setName("dup");
        req.setType("GIT");
        req.setEndpoint("https://g.example.com/r.git");
        req.setAuthType("BASIC");
        req.setUsername("u");
        req.setSecret("s");

        assertThatThrownBy(() -> svc.create(req)).isInstanceOf(CloudPlatformException.class);
        verify(mapper, never()).insert(any());
    }

    // ---- 留空 = 保持不变 ----

    @Test
    void updateWithBlankSecretKeepsOldCiphertext() {
        PlatformCredential old = stored("c1", "gitlab", "GIT", "https://g.example.com",
                "ci-bot", "old-secret");
        String oldCipher = old.getSecretEncrypted();
        when(mapper.selectById("c1")).thenReturn(old);

        CredentialUpdateRequest req = new CredentialUpdateRequest();
        req.setId("c1");
        req.setName("gitlab");
        req.setType("GIT");
        req.setEndpoint("https://g.example.com");
        req.setAuthType("BASIC");
        req.setDescription("改了描述");
        req.setEnabled(true);

        svc.update(req);

        var captor = org.mockito.ArgumentCaptor.forClass(PlatformCredential.class);
        verify(mapper).update(captor.capture());
        assertThat(captor.getValue().getSecretEncrypted()).isEqualTo(oldCipher);
        assertThat(captor.getValue().getDescription()).isEqualTo("改了描述");
    }

    @Test
    void updateWithNewSecretRotatesIt() {
        PlatformCredential old = stored("c1", "gitlab", "GIT", "https://g.example.com",
                "ci-bot", "old-secret");
        String oldFingerprint = old.getSecretFingerprint();
        when(mapper.selectById("c1")).thenReturn(old);

        CredentialUpdateRequest req = new CredentialUpdateRequest();
        req.setId("c1");
        req.setName("gitlab");
        req.setType("GIT");
        req.setEndpoint("https://g.example.com");
        req.setAuthType("BASIC");
        req.setSecret("new-secret");
        req.setEnabled(true);

        svc.update(req);

        var captor = org.mockito.ArgumentCaptor.forClass(PlatformCredential.class);
        verify(mapper).update(captor.capture());
        assertThat(captor.getValue().getSecretFingerprint()).isNotEqualTo(oldFingerprint);
        assertThat(cipher.decrypt(captor.getValue().getSecretEncrypted())).contains("new-secret");
    }

    @Test
    void updateUnknownIdFails() {
        when(mapper.selectById("nope")).thenReturn(null);

        CredentialUpdateRequest req = new CredentialUpdateRequest();
        req.setId("nope");

        assertThatThrownBy(() -> svc.update(req)).isInstanceOf(CloudPlatformException.class);
    }

    @Test
    void deleteUnknownIdFails() {
        when(mapper.selectById("nope")).thenReturn(null);

        assertThatThrownBy(() -> svc.delete("nope")).isInstanceOf(CloudPlatformException.class);
        verify(mapper, never()).deleteById(anyString());
    }

    // ---- 测试连接 ----

    @Test
    void testGitUsesDecryptedCredentialAndProbeUrl() {
        when(mapper.selectById("c1")).thenReturn(
                stored("c1", "gitlab", "GIT", "https://g.example.com", "ci-bot", "tok"));
        when(probeClient.probeGit(eq("https://g.example.com/r.git"), eq("ci-bot"), eq("tok")))
                .thenReturn(ProbeResult.pass());

        CredentialTestRequest req = new CredentialTestRequest();
        req.setId("c1");
        req.setProbeUrl("https://g.example.com/r.git");

        assertThat(svc.test(req).getOk()).isTrue();
        verify(mapper).updateVerifyResult(eq("c1"), eq(true), any(), any(Date.class));
    }

    @Test
    void testGitRequiresProbeUrl() {
        when(mapper.selectById("c1")).thenReturn(
                stored("c1", "gitlab", "GIT", "https://g.example.com", "ci-bot", "tok"));

        CredentialTestRequest req = new CredentialTestRequest();
        req.setId("c1");

        assertThatThrownBy(() -> svc.test(req)).isInstanceOf(CloudPlatformException.class);
        verify(probeClient, never()).probeGit(anyString(), anyString(), anyString());
    }

    @Test
    void testRegistryDoesNotNeedProbeUrl() {
        when(mapper.selectById("c1")).thenReturn(
                stored("c1", "harbor", "REGISTRY", "harbor.example.com", "robot", "pw"));
        when(probeClient.probeRegistry("harbor.example.com", "robot", "pw"))
                .thenReturn(ProbeResult.fail("凭据不通过，或该凭据无权访问此 registry"));

        CredentialTestRequest req = new CredentialTestRequest();
        req.setId("c1");

        var result = svc.test(req);

        assertThat(result.getOk()).isFalse();
        assertThat(result.getMessage()).doesNotContain("密码");
        verify(mapper).updateVerifyResult(eq("c1"), eq(false), anyString(), any(Date.class));
    }

    @Test
    void testFailureKeepsLastVerifiedAt() {
        PlatformCredential row = stored("c1", "harbor", "REGISTRY", "harbor.example.com", "r", "pw");
        row.setLastVerifiedAt(new Date(1_700_000_000_000L));
        when(mapper.selectById("c1")).thenReturn(row);
        when(probeClient.probeRegistry(anyString(), anyString(), anyString()))
                .thenReturn(ProbeResult.fail("凭据不通过"));

        CredentialTestRequest req = new CredentialTestRequest();
        req.setId("c1");
        svc.test(req);

        // ok=false 的写回不碰 last_verified_at（那条 SQL 走 otherwise 分支）
        verify(mapper).updateVerifyResult(eq("c1"), eq(false), anyString(), any(Date.class));
    }

    // ---- resolveSecret（下游消费方的入口，§11） ----

    @Test
    void resolveSecretReturnsPlaintextForConsumers() {
        when(mapper.selectById("c1")).thenReturn(
                stored("c1", "gitlab", "GIT", "https://g.example.com", "ci-bot", "tok"));

        CredentialSecret s = svc.resolveSecret("c1");

        assertThat(s.getUsername()).isEqualTo("ci-bot");
        assertThat(s.getSecret()).isEqualTo("tok");
    }

    // ---- 结构性钉子 ----

    /**
     * ⚠️ **结构性钉子**，不是行为测试。
     *
     * <p>「永不回显」这条契约无法靠逐个断言守住 —— 它是**否定性**的（「字段不存在」），
     * 而将来有人给 {@link CredentialView} 加一个 {@code username} 字段时，
     * 既有的断言全都还是绿的。所以这里改成反射扫描：**任何名字含 secret / password / username
     * 的字段都会让本测试失败**。
     *
     * <p>本测试**能**挡住的回归：给响应 DTO 加了承载敏感信息的字段。
     *
     * <p>本测试**挡不住**的：把明文塞进一个改名的普通字段（如 {@code extra}）——
     * 那只能靠 code review。
     */
    @Test
    void viewDtoHasNoCredentialField() {
        List<String> banned = List.of("secret", "password", "passwd", "username", "credential");

        List<String> offenders = Arrays.stream(CredentialView.class.getDeclaredFields())
                .filter(f -> !Modifier.isStatic(f.getModifiers()))
                .filter(f -> banned.stream().anyMatch(f.getName().toLowerCase(Locale.ROOT)::contains))
                .filter(f -> !("hasSecret".equalsIgnoreCase(f.getName()) && f.getType() == Boolean.class))
                .map(Field::getName)
                .toList();

        assertThat(offenders)
                .as("CredentialView 不得出现承载凭据内容的字段（spec「永不回显」）；"
                  + "确实需要新增派生字段时，改的是本条测试的 banned 列表，不是加字段了事")
                .isEmpty();
    }
}
```

- [ ] **Step 4: 跑测试，确认失败**

```bash
cd /d/coding/k8s-cloud-platform
mvn -o -pl platform-api -am test -Dtest=CredentialServiceTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: **编译失败** —— `找不到符号: 类 CredentialService`。

- [ ] **Step 5: 写实现**

创建 `platform-api/src/main/java/com/coding/platformapi/services/CredentialService.java`：

```java
package com.coding.platformapi.services;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.utils.ULIDGenerator;
import com.coding.data.mapper.system.PlatformCredentialMapper;
import com.coding.data.models.system.PlatformCredential;
import com.coding.platformapi.credential.CredentialCipher;
import com.coding.platformapi.credential.CredentialProbeClient;
import com.coding.platformapi.credential.CredentialSecret;
import com.coding.platformapi.credential.ProbeResult;
import com.coding.platformapi.models.CredentialCreateRequest;
import com.coding.platformapi.models.CredentialTestRequest;
import com.coding.platformapi.models.CredentialTestResult;
import com.coding.platformapi.models.CredentialUpdateRequest;
import com.coding.platformapi.models.CredentialView;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import tools.jackson.databind.json.JsonMapper;

import java.util.Date;
import java.util.List;
import java.util.Set;

/**
 * 凭据库。设计见 docs/superpowers/specs/2026-10-13-credential-management-design.md。
 *
 * <h3>三条不可动摇的契约</h3>
 * <ol>
 *   <li><b>永不回显</b>：对外只有 {@link CredentialView}，它没有承载凭据内容的字段。
 *       认证字段只以 {@code hasSecret} / {@code fingerprint} 两种派生形态出现。</li>
 *   <li><b>明文只在进程内</b>：只有 {@link #resolveSecret} 返回明文，它是内部消费方
 *       （S3.1 / S3.5）的入口，**不暴露成 HTTP 端点**。</li>
 *   <li><b>测试连接不改变业务状态</b>：它只写 {@code last_verified_*} 两列。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CredentialService {

    private static final Set<String> TYPES = Set.of("GIT", "REGISTRY");

    private static final String AUTH_BASIC = "BASIC";

    private final PlatformCredentialMapper mapper;

    private final CredentialCipher cipher;

    private final CredentialProbeClient probeClient;

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    public List<CredentialView> list() {
        return mapper.selectAll().stream().map(CredentialService::toView).toList();
    }

    public CredentialView create(CredentialCreateRequest req) {
        validateCommon(req.getName(), req.getType(), req.getEndpoint(), req.getAuthType());
        if (!StringUtils.hasText(req.getUsername()) || !StringUtils.hasText(req.getSecret())) {
            throw new CloudPlatformException(EnumResponseType.CREDENTIAL_INVALID,
                    "认证方式为 BASIC 时，用户名与密码/令牌都必须填写");
        }
        if (mapper.selectByName(req.getName()) != null) {
            throw new CloudPlatformException(EnumResponseType.CREDENTIAL_NAME_EXIST);
        }

        String json = toJson(new CredentialSecret(req.getUsername(), req.getSecret()));
        PlatformCredential c = new PlatformCredential();
        c.setId(ULIDGenerator.generateULID());
        c.setName(req.getName());
        c.setType(req.getType());
        c.setEndpoint(req.getEndpoint().trim());
        c.setAuthType(AUTH_BASIC);
        c.setSecretEncrypted(cipher.encrypt(json));
        c.setSecretFingerprint(CredentialCipher.fingerprint(json));
        c.setDescription(req.getDescription());
        c.setEnabled(req.getEnabled() == null || req.getEnabled() ? 1 : 0);

        mapper.insert(c);
        return toView(c);
    }

    /**
     * 更新。{@code username} / {@code secret} **留空 = 保持不变**（逐字段）——
     * 这是「永不回显」的必然结果：表单里拿不到旧值，「空」只能解读为「不改」。
     *
     * <p>{@code authType} 变更时**必须重填**：密文是绑 authType 解释的 JSON，跨类型沿用语义会对不上。
     */
    public CredentialView update(CredentialUpdateRequest req) {
        PlatformCredential old = mapper.selectById(req.getId());
        if (old == null) {
            throw new CloudPlatformException(EnumResponseType.CREDENTIAL_NOT_EXIST);
        }
        validateCommon(req.getName(), req.getType(), req.getEndpoint(), req.getAuthType());

        PlatformCredential byName = mapper.selectByName(req.getName());
        if (byName != null && !byName.getId().equals(old.getId())) {
            throw new CloudPlatformException(EnumResponseType.CREDENTIAL_NAME_EXIST);
        }

        boolean authTypeChanged = !old.getAuthType().equals(req.getAuthType());
        boolean newSecretGiven = StringUtils.hasText(req.getSecret());
        if (authTypeChanged && !newSecretGiven) {
            throw new CloudPlatformException(EnumResponseType.CREDENTIAL_INVALID,
                    "认证方式变更后必须重新填写凭据");
        }

        PlatformCredential c = new PlatformCredential();
        c.setId(old.getId());
        c.setName(req.getName());
        c.setType(req.getType());
        c.setEndpoint(req.getEndpoint().trim());
        c.setAuthType(AUTH_BASIC);
        c.setDescription(req.getDescription());
        c.setEnabled(req.getEnabled() == null || req.getEnabled() ? 1 : 0);

        if (newSecretGiven || StringUtils.hasText(req.getUsername())) {
            // 逐字段合并：哪个字段空就沿用旧值（旧值要解密一次才拿得到）
            CredentialSecret current = fromJson(cipher.decrypt(old.getSecretEncrypted()));
            String username = StringUtils.hasText(req.getUsername()) ? req.getUsername() : current.getUsername();
            String secret = newSecretGiven ? req.getSecret() : current.getSecret();
            String json = toJson(new CredentialSecret(username, secret));
            c.setSecretEncrypted(cipher.encrypt(json));
            c.setSecretFingerprint(CredentialCipher.fingerprint(json));
        } else {
            c.setSecretEncrypted(old.getSecretEncrypted());
            c.setSecretFingerprint(old.getSecretFingerprint());
        }

        mapper.update(c);
        return toView(c);
    }

    public void delete(String id) {
        if (mapper.selectById(id) == null) {
            throw new CloudPlatformException(EnumResponseType.CREDENTIAL_NOT_EXIST);
        }
        mapper.deleteById(id);
    }

    public CredentialTestResult test(CredentialTestRequest req) {
        PlatformCredential c = mapper.selectById(req.getId());
        if (c == null) {
            throw new CloudPlatformException(EnumResponseType.CREDENTIAL_NOT_EXIST);
        }
        CredentialSecret s = resolveSecret(req.getId());

        ProbeResult result;
        if ("GIT".equals(c.getType())) {
            if (!StringUtils.hasText(req.getProbeUrl())) {
                throw new CloudPlatformException(EnumResponseType.CREDENTIAL_INVALID,
                        "探测 Git 凭据需要给出一个用于测试的仓库地址"
                      + "（凭据是按 host 配的，能不能用取决于具体仓库的权限）");
            }
            result = probeClient.probeGit(req.getProbeUrl(), s.getUsername(), s.getSecret());
        } else {
            result = probeClient.probeRegistry(c.getEndpoint(), s.getUsername(), s.getSecret());
        }

        mapper.updateVerifyResult(c.getId(), result.ok(),
                result.ok() ? null : truncate(result.message()), new Date());
        return new CredentialTestResult(result.ok(), result.message(), result.suggestedUrl());
    }

    /**
     * 取明文凭据 —— **内部消费方（S3.1 / S3.5）的唯一入口，不暴露成 HTTP 端点**。
     *
     * <p><b>本方法不记审计</b>：审计由调用方那一层负责。一次 CI 运行可能取用多次，
     * 记在这里会让一次运行产生 N 条记录（spec §11）。
     */
    public CredentialSecret resolveSecret(String id) {
        PlatformCredential c = mapper.selectById(id);
        if (c == null) {
            throw new CloudPlatformException(EnumResponseType.CREDENTIAL_NOT_EXIST);
        }
        return fromJson(cipher.decrypt(c.getSecretEncrypted()));
    }

    private void validateCommon(String name, String type, String endpoint, String authType) {
        if (!StringUtils.hasText(name)) {
            throw new CloudPlatformException(EnumResponseType.CREDENTIAL_INVALID, "名称必填");
        }
        if (!TYPES.contains(type)) {
            throw new CloudPlatformException(EnumResponseType.CREDENTIAL_INVALID,
                    "凭据类型仅支持 GIT / REGISTRY，收到：" + type);
        }
        if (!StringUtils.hasText(endpoint)) {
            throw new CloudPlatformException(EnumResponseType.CREDENTIAL_INVALID, "地址必填");
        }
        if (!AUTH_BASIC.equals(authType)) {
            throw new CloudPlatformException(EnumResponseType.CREDENTIAL_INVALID,
                    "v1 仅支持 BASIC（HTTPS 用户名 + 密码/令牌）认证方式，收到：" + authType);
        }
    }

    private String toJson(CredentialSecret s) {
        return jsonMapper.writeValueAsString(s);
    }

    private CredentialSecret fromJson(String json) {
        try {
            return jsonMapper.readValue(json, CredentialSecret.class);
        } catch (Exception e) {
            // 解密成功但 JSON 解析失败 = 密文或主密钥不对；不降级、不返回半个对象
            throw new CloudPlatformException(EnumResponseType.CREDENTIAL_CIPHERTEXT_INVALID,
                    "凭据内容无法解析（密文损坏或主密钥与写入时不一致）");
        }
    }

    private static String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() <= 255 ? s : s.substring(0, 255);
    }

    /** 实体 → 视图。**这里就是「永不回显」的落点**：密文与用户名都不进视图。 */
    private static CredentialView toView(PlatformCredential c) {
        CredentialView v = new CredentialView();
        v.setId(c.getId());
        v.setName(c.getName());
        v.setType(c.getType());
        v.setEndpoint(c.getEndpoint());
        v.setAuthType(c.getAuthType());
        v.setHasSecret(StringUtils.hasText(c.getSecretEncrypted()));
        v.setFingerprint(c.getSecretFingerprint());
        v.setDescription(c.getDescription());
        v.setEnabled(c.getEnabled() != null && c.getEnabled() == 1);
        v.setLastVerifiedAt(c.getLastVerifiedAt());
        v.setLastVerifyError(c.getLastVerifyError());
        v.setCreatedAt(c.getCreatedAt());
        v.setUpdatedAt(c.getUpdatedAt());
        return v;
    }
}
```

⚠️ Jackson 3（`tools.jackson`）里 `writeValueAsString` / `readValue` 抛的是**非受检**异常
（`JacksonException extends RuntimeException`），所以 `toJson` 不需要 try/catch。若编译报「未报告的异常」，
说明本仓用的是受检版本，就在 `toJson` 上也包一层并转 `CloudPlatformException`。

- [ ] **Step 6: 跑测试，确认通过**

```bash
cd /d/coding/k8s-cloud-platform
mvn -o -pl platform-api -am test -Dtest=CredentialServiceTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: `Tests run: 16, Failures: 0, Errors: 0, Skipped: 0`

- [ ] **Step 7: 提交**

```bash
git add platform-api/src/main/java/com/coding/platformapi/models/Credential*.java \
        platform-api/src/main/java/com/coding/platformapi/credential/CredentialSecret.java \
        platform-api/src/main/java/com/coding/platformapi/services/CredentialService.java \
        platform-api/src/test/java/com/coding/platformapi/services/CredentialServiceTest.java \
        platform-common/src/main/java/com/coding/common/exception/EnumResponseType.java
git commit -m "feat(credential): CredentialService（永不回显 + 留空保持不变 + 测试连接）"
```

---

### Task 7: `AuditAction` 常量

**Files:**
- Modify: `platform-api/src/main/java/com/coding/platformapi/audit/AuditAction.java`
- Test: `platform-api/src/test/java/com/coding/platformapi/audit/AuditActionTest.java`

**Interfaces:**
- Consumes: 无
- Produces: `AuditAction.CREDENTIAL_CREATE` / `CREDENTIAL_UPDATE` / `CREDENTIAL_DELETE` / `CREDENTIAL_TEST`

- [ ] **Step 1: 写失败的测试**

创建 `platform-api/src/test/java/com/coding/platformapi/audit/AuditActionTest.java`：

```java
package com.coding.platformapi.audit;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AuditAction#all()} 是**反射**来的（不手写第二份 List，否则会与常量漂移）。
 * 本测试钉住「新加常量会自动进候选值」这条性质 —— 凭据页的动作码筛选靠它。
 */
class AuditActionTest {

    @Test
    void allExposesCredentialActions() {
        List<String> all = AuditAction.all();

        assertThat(all).contains(
                AuditAction.CREDENTIAL_CREATE,
                AuditAction.CREDENTIAL_UPDATE,
                AuditAction.CREDENTIAL_DELETE,
                AuditAction.CREDENTIAL_TEST);
        assertThat(all).contains("credential:create", "credential:update",
                "credential:delete", "credential:test");
    }

    @Test
    void allIsStillSortedAndComplete() {
        // S0.1 的 23 个 + 本批 4 个
        assertThat(AuditAction.all()).isSorted().hasSizeGreaterThanOrEqualTo(27);
    }
}
```

- [ ] **Step 2: 跑测试，确认失败**

```bash
cd /d/coding/k8s-cloud-platform
mvn -o -pl platform-api -am test -Dtest=AuditActionTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: **编译失败** —— `找不到符号: 变量 CREDENTIAL_CREATE`。

- [ ] **Step 3: 加常量**

在 `AuditAction.java` 的集群段（`CLUSTER_PROVISION`）**之后**、`all()` 方法**之前**插入：

```java
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
```

- [ ] **Step 4: 跑测试，确认通过**

```bash
cd /d/coding/k8s-cloud-platform
mvn -o -pl platform-api -am test -Dtest=AuditActionTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: `Tests run: 2, Failures: 0, Errors: 0, Skipped: 0`

- [ ] **Step 5: 提交**

```bash
git add platform-api/src/main/java/com/coding/platformapi/audit/AuditAction.java \
        platform-api/src/test/java/com/coding/platformapi/audit/AuditActionTest.java
git commit -m "feat(credential): 4 个凭据审计动作码"
```

---

### Task 8: `CredentialController`（+ 审计注解）

**Files:**
- Create: `platform-api/src/main/java/com/coding/platformapi/controllers/CredentialController.java`
- Test: `platform-api/src/test/java/com/coding/platformapi/controllers/CredentialControllerTest.java`

**Interfaces:**
- Consumes: `CredentialService`（Task 6）、`AuditAction`（Task 7）、`@Auditable`
- Produces: 5 个端点 `/credential/list|test|create|update|delete`

- [ ] **Step 1: 写失败的测试（审计目标的结构性钉子）**

创建 `platform-api/src/test/java/com/coding/platformapi/controllers/CredentialControllerTest.java`：

```java
package com.coding.platformapi.controllers;

import com.coding.platformapi.audit.AuditAction;
import com.coding.platformapi.audit.Auditable;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ⚠️ **结构性钉子**，不是行为测试。
 *
 * <p>「审计里不记凭据内容与 endpoint」这条契约无法靠行为测试守住 —— 它是否定性的
 * （「某个东西不在记录里」），而一个有 bug 的实现照样能通过「action 对不对」这类断言。
 * 这里退一步，钉住**它成立的原因**：{@code @Auditable.target} 的 SpEL 只允许取
 * {@code #request.id} / {@code #request.name}，拿不到请求体里的敏感字段
 * （{@code username} / {@code secret} / {@code probeUrl} / {@code endpoint}）。
 *
 * <p>本测试**能**挡住的回归：有人把 {@code target} 改成 {@code #request.secret}
 * 或 {@code #request.endpoint}，把凭据内容抄进审计表。
 *
 * <p>本测试**挡不住**的：{@code AuditAspect} 本身被改成读请求体 ——
 * 那属于 S0.1 的范围，由那边的测试守着。
 */
class CredentialControllerTest {

    /** 允许被写进审计目标的请求字段。加字段前先读类注释。 */
    private static final Set<String> ALLOWED_TARGETS = Set.of("#request.id", "#request.name");

    private List<Method> auditedMethods() {
        return Arrays.stream(CredentialController.class.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(Auditable.class))
                .toList();
    }

    @Test
    void auditedTargetsNeverReachSensitiveRequestFields() {
        var offenders = auditedMethods().stream()
                .map(m -> m.getAnnotation(Auditable.class).target())
                .filter(t -> !ALLOWED_TARGETS.contains(t))
                .toList();

        assertThat(offenders)
                .as("凭据端点的审计 target 只允许 %s；出现别的说明有人把敏感字段写进了审计表", ALLOWED_TARGETS)
                .isEmpty();
    }

    @Test
    void auditedMethodsAreExactlyTheThreeWritesPlusTest() {
        // 列表是纯读，不标审计；其余四个（create/update/delete/test）全标。
        // 测试连接要标 —— 它真的解密凭据并出站打对端，是本批唯一的「使用」路径。
        var actions = auditedMethods().stream()
                .map(m -> m.getAnnotation(Auditable.class).action())
                .sorted()
                .toList();

        assertThat(actions).containsExactly(
                AuditAction.CREDENTIAL_CREATE,
                AuditAction.CREDENTIAL_DELETE,
                AuditAction.CREDENTIAL_TEST,
                AuditAction.CREDENTIAL_UPDATE);
    }
}
```

- [ ] **Step 2: 跑测试，确认失败**

```bash
cd /d/coding/k8s-cloud-platform
mvn -o -pl platform-api -am test -Dtest=CredentialControllerTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: **编译失败** —— `找不到符号: 类 CredentialController`。

- [ ] **Step 3: 写实现**

创建 `platform-api/src/main/java/com/coding/platformapi/controllers/CredentialController.java`：

```java
package com.coding.platformapi.controllers;

import com.coding.common.models.system.ResponseData;
import com.coding.platformapi.audit.AuditAction;
import com.coding.platformapi.audit.Auditable;
import com.coding.platformapi.models.CredentialCreateRequest;
import com.coding.platformapi.models.CredentialIdRequest;
import com.coding.platformapi.models.CredentialTestRequest;
import com.coding.platformapi.models.CredentialTestResult;
import com.coding.platformapi.models.CredentialUpdateRequest;
import com.coding.platformapi.models.CredentialView;
import com.coding.platformapi.services.CredentialService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 凭据管理（平台管理面，仅 admin）。本层只做 HTTP 绑定与 {@link ResponseData} 包装；
 * 业务在 {@link CredentialService}（分层约定见 docs/development/backend-layering.md）。
 *
 * <p>权限行见 V2026_10_13_3：{@code /credential/list} 与 {@code /credential/test} →
 * {@code platform:credential:read}；create / update / delete → {@code platform:credential:write}。
 * **五个端点都必须有行**，否则启动期交叉校验拒启。测试连接与列表**有意共用 read**
 * （能看即能测，spec §6.2）。
 *
 * <p><b>审计只标写操作与测试连接</b>：列表是纯读，不标。测试连接要标 ——
 * 它真的解密凭据并出站打对端，是本批唯一的「使用」路径（spec §7）。
 *
 * <p>{@code target} 对 create 取 name、其余取 id：请求体里只有 id。
 * **这与 {@code ClusterController} 完全一致**（cluster 的 update / delete / provision 同样是
 * {@code #request.clusterId}），是有意对齐，不是漏改。
 *
 * <p><b>没有单条详情端点</b>：列表返回的字段已足够，编辑表单的数据从列表行来。
 */
@Tag(name = "凭据管理", description = "平台级 git / registry 连接：CRUD + 测试连接（凭据内容永不回显）")
@RestController
@RequestMapping("/credential")
@RequiredArgsConstructor
public class CredentialController {

    private final CredentialService credentialService;

    @Operation(summary = "凭据列表", description = "按 type、name 排序；**不含凭据内容**，只有是否已配置与指纹")
    @PostMapping("/list")
    public ResponseData<List<CredentialView>> list() {
        return new ResponseData<>(credentialService.list());
    }

    @Operation(summary = "测试连接",
            description = "解密凭据并真打一次对端：GIT 探 info/refs（需传 probeUrl），REGISTRY 探 /v2/。"
                    + "不改变业务状态，只写 last_verified_at / last_verify_error")
    @PostMapping("/test")
    @Auditable(action = AuditAction.CREDENTIAL_TEST, target = "#request.id")
    public ResponseData<CredentialTestResult> test(@RequestBody CredentialTestRequest request) {
        return new ResponseData<>(credentialService.test(request));
    }

    @Operation(summary = "新建凭据", description = "凭据明文落库即加密，此后永不回显")
    @PostMapping("/create")
    @Auditable(action = AuditAction.CREDENTIAL_CREATE, target = "#request.name")
    public ResponseData<CredentialView> create(@RequestBody CredentialCreateRequest request) {
        return new ResponseData<>(credentialService.create(request));
    }

    @Operation(summary = "更新凭据",
            description = "username / secret 留空 = 保持不变；authType 变更时必须重填")
    @PostMapping("/update")
    @Auditable(action = AuditAction.CREDENTIAL_UPDATE, target = "#request.id")
    public ResponseData<CredentialView> update(@RequestBody CredentialUpdateRequest request) {
        return new ResponseData<>(credentialService.update(request));
    }

    @Operation(summary = "删除凭据", description = "硬删除。被流水线引用时不做保护（不加外键是既定原则）")
    @PostMapping("/delete")
    @Auditable(action = AuditAction.CREDENTIAL_DELETE, target = "#request.id")
    public ResponseData<Void> delete(@RequestBody CredentialIdRequest request) {
        credentialService.delete(request.getId());
        return new ResponseData<>(null);
    }
}
```

- [ ] **Step 4: 跑测试，确认通过**

```bash
cd /d/coding/k8s-cloud-platform
mvn -o -pl platform-api -am test -Dtest=CredentialControllerTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: `Tests run: 2, Failures: 0, Errors: 0, Skipped: 0`

- [ ] **Step 5: 起一次应用，确认交叉校验通过**

⚠️ 这一步会**真的去连数据库和集群**。先确认 `CREDENTIAL_AES_KEY` 已设置（Task 9 会写进配置；
这一步可以先用临时环境变量），再起：

```bash
cd /d/coding/k8s-cloud-platform
CREDENTIAL_AES_KEY="dev-only-key-do-not-use-in-prod-0001" \
  mvn -o -pl platform-api spring-boot:run 2>&1 | grep -E "RBAC|启动失败|endpoint 全部有权限声明|APPLICATION FAILED" &
sleep 90
```

Expected: 看到 `[RBAC] 交叉校验通过：NNN 个 endpoint 全部有权限声明或豁免`，
**没有** `[RBAC] 以下 endpoint 既无权限行也不在豁免清单，启动失败`。

看到那行后**停掉进程**（`kill %1` 或 Ctrl-C；用 `run_in_background` 时用 `TaskStop`）。

- [ ] **Step 6: 提交**

```bash
git add platform-api/src/main/java/com/coding/platformapi/controllers/CredentialController.java \
        platform-api/src/test/java/com/coding/platformapi/controllers/CredentialControllerTest.java
git commit -m "feat(credential): CredentialController（5 端点 + 审计注解标写操作与测试连接）"
```

---

### Task 9: 配置（主密钥）

**Files:**
- Modify: `platform-api/src/main/resources/application.yaml`

**Interfaces:**
- Consumes: 无
- Produces: 配置项 `platform.credential.aes-key`（env `CREDENTIAL_AES_KEY`）

- [ ] **Step 1: 加配置**

在 `platform-api/src/main/resources/application.yaml` 的 `platform:` 块下（`namespace:` 之前）插入：

```yaml
platform:
  credential:
    # 凭据库（platform_credential）的 AES 主密钥。**独立于 k8s.cloud.kubeconfig.aes-key**：
    # 轮换边界独立，且不扩散后者把默认密钥硬编码在配置里的坏味道。
    # ⚠️ 没有默认值：留空应用会拒绝启动（CredentialCipher 的启动校验）。
    # 生成方式：调用 AESUtils.generateStrongKey()，把结果填进环境变量。
    aes-key: ${CREDENTIAL_AES_KEY:}
```

⚠️ **合并时注意**：`platform:` 顶层键在文件里已存在（`platform.namespace.protected-namespaces`）。
**不要写成第二个 `platform:` 块** —— YAML 里重复的顶层键会让后者覆盖前者，把
`protected-namespaces` 整个吞掉。正确做法是在既有的 `platform:` 下加一个 `credential:` 子块。

- [ ] **Step 2: 验证 YAML 没吃掉既有配置**

```bash
cd /d/coding/k8s-cloud-platform
grep -n "^platform:" -A 4 platform-api/src/main/resources/application.yaml
grep -c "^platform:" platform-api/src/main/resources/application.yaml
```

Expected: `^platform:` 只出现 **1 次**，且其下同时能看到 `credential:` 与 `namespace:`。

- [ ] **Step 3: 验证未设环境变量时拒绝启动**

```bash
cd /d/coding/k8s-cloud-platform
mvn -o -pl platform-api spring-boot:run 2>&1 | grep -E "凭据加密主密钥未配置|APPLICATION FAILED TO START" &
sleep 75
```

Expected: 出现 `凭据加密主密钥未配置：请设置 platform.credential.aes-key`，
且应用**没有**完成启动。之后停掉进程。

- [ ] **Step 4: 提交**

```bash
git add platform-api/src/main/resources/application.yaml
git commit -m "chore(credential): 加 platform.credential.aes-key 配置项（无默认值）"
```

---

### Task 10: 前端 types + API + 常量

**Files:**
- Modify: `platform-web/src/types.ts`
- Modify: `platform-web/src/api/index.ts`
- Modify: `platform-web/src/pageCodes.ts`
- Modify: `platform-web/src/apiCodes.ts`
- Fix: `platform-web/src/api/index.ts:618`（过期注释，见 Step 2）

**Interfaces:**
- Consumes: Task 8 的 5 个端点
- Produces:
  - `CredentialView` / `CredentialCreateBody` / `CredentialUpdateBody` / `CredentialTestResult` 类型
  - `credentialApi.list() / test(id, probeUrl?) / create(body) / update(body) / remove(id)`
  - `pageCodes.credential`
  - `apiCodes.credentialWrite`

- [ ] **Step 1: 加类型**

在 `platform-web/src/types.ts` 的 `AuditQuery` 之后追加：

```ts
/**
 * 凭据（S0.2）。**本类型不含凭据内容** —— 后端永不回显，只有 hasSecret（有没有配）
 * 与 fingerprint（哪一份）两种派生形态。加字段前先读后端 spec 的「永不回显」一节。
 */
export interface CredentialView {
  id: string
  name: string
  /** GIT / REGISTRY */
  type: 'GIT' | 'REGISTRY'
  /** host 基址 */
  endpoint: string
  /** BASIC */
  authType: string
  hasSecret: boolean
  /** 明文认证字段 JSON 的 SHA-256 前 16 位 */
  fingerprint?: string | null
  description?: string | null
  enabled: boolean
  /** 上次测试通过的时刻；测失败不改它 */
  lastVerifiedAt?: string | null
  /** 上次测试的失败原因；成功则 null */
  lastVerifyError?: string | null
  createdAt?: string | null
  updatedAt?: string | null
}

/** 新建凭据。username / secret 必填。 */
export interface CredentialCreateBody {
  name: string
  type: 'GIT' | 'REGISTRY'
  endpoint: string
  authType: string
  username: string
  secret: string
  description?: string | null
  enabled?: boolean
}

/** 更新凭据。username / secret **留空 = 保持不变**；authType 变更时必须重填。 */
export interface CredentialUpdateBody {
  id: string
  name: string
  type: 'GIT' | 'REGISTRY'
  endpoint: string
  authType: string
  username?: string | null
  secret?: string | null
  description?: string | null
  enabled?: boolean
}

/** 测试连接结果。suggestedUrl 非空 = 「对端要求改用这个地址」，要提供一键填入。 */
export interface CredentialTestResult {
  ok: boolean
  message: string
  suggestedUrl?: string | null
}
```

- [ ] **Step 2: 加 API，并顺手修一处过期注释**

在 `platform-web/src/api/index.ts` 的 `auditApi` **之后**追加：

```ts
export const credentialApi = {
  list: () => http.post<never, CredentialView[]>('/credential/list'),
  /** GIT 类型必须给 probeUrl：凭据是按 host 配的，能不能用取决于具体仓库的权限 */
  test: (id: string, probeUrl?: string) =>
    http.post<never, CredentialTestResult>('/credential/test', { id, probeUrl }),
  create: (body: CredentialCreateBody) => http.post<never, CredentialView>('/credential/create', body),
  update: (body: CredentialUpdateBody) => http.post<never, CredentialView>('/credential/update', body),
  remove: (id: string) => http.post<never, void>('/credential/delete', { id }),
}
```

同文件顶部 `import type { ... }` 里按字母序插入 `CredentialCreateBody,`、`CredentialTestResult,`、
`CredentialUpdateBody,`、`CredentialView,`（遵循既有排序）。

**同时修掉第 618 行那条过期注释**（是上一批 `listActions` 改实现时漏改的，现在说的和做的相反）：

```ts
  /** 动作码候选值 = AuditAction 常量（动作码）∪ API 域权限码；取全集而非审计表观测值 */
  actions: () => http.post<never, string[]>('/audit/actions'),
```

- [ ] **Step 3: 加页面码与能力位**

在 `platform-web/src/pageCodes.ts` 的 `audit` 一行**之后**插入：

```ts
  /** S0.2 凭据管理页（平台管理面，仅 admin） */
  credential: 'platform:page:credential',
```

在 `platform-web/src/apiCodes.ts` 的平台族段落里（`podListAll` 之后）插入：

```ts
  /** 凭据写（新建/更新/删除）—— 页面内的写按钮置灰用 */
  credentialWrite: 'platform:credential:write',
```

- [ ] **Step 4: 类型检查**

```bash
cd /d/coding/k8s-cloud-platform/platform-web
npx vue-tsc --noEmit -p tsconfig.json 2>&1 | tail -20
```

Expected: 退出码 0，无输出。

- [ ] **Step 5: 提交**

```bash
git add platform-web/src/types.ts platform-web/src/api/index.ts \
        platform-web/src/pageCodes.ts platform-web/src/apiCodes.ts
git commit -m "feat(credential): 前端 types + credentialApi + pageCodes/apiCodes（并修 auditApi 过期注释）"
```

---

### Task 11: 前端页面 `CredentialView.vue`

**Files:**
- Create: `platform-web/src/views/CredentialView.vue`

**Interfaces:**
- Consumes: Task 10 的 `credentialApi`、`CredentialView` 类型、`pageCodes.credential`、`apiCodes.credentialWrite`
- Produces: 路由组件 `CredentialView`

- [ ] **Step 1: 写页面**

创建 `platform-web/src/views/CredentialView.vue`：

```vue
<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { credentialApi } from '@/api'
import type { CredentialTestResult, CredentialView as Credential } from '@/types'
import { usePermission } from '@/stores/permission'
import { apiCodes } from '@/apiCodes'

const perm = usePermission()

const rows = ref<Credential[]>([])
const loading = ref(false)

/** 表单：新建与编辑共用。id 为空 = 新建 */
const form = reactive({
  visible: false,
  id: '',
  name: '',
  type: 'GIT' as 'GIT' | 'REGISTRY',
  endpoint: '',
  username: '',
  secret: '',
  description: '',
  enabled: true,
})
/** 编辑态下认证方式的选择；BASIC 是 v1 唯一实现，保留字段是为了切换时能提示必须重填 */
const authType = ref('BASIC')

const isEdit = computed(() => !!form.id)

const TYPE_OPTIONS = [
  { value: 'GIT', label: 'Git 仓库' },
  { value: 'REGISTRY', label: '镜像仓库' },
]

function typeLabel(t: string): string {
  return TYPE_OPTIONS.find((o) => o.value === t)?.label ?? t
}

function fmtDate(v?: string | null): string {
  if (!v) return ''
  const d = new Date(v)
  if (Number.isNaN(d.getTime())) return ''
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
}

async function load(): Promise<void> {
  loading.value = true
  try {
    rows.value = await credentialApi.list()
  } finally {
    loading.value = false
  }
}

function openCreate(): void {
  Object.assign(form, {
    visible: true,
    id: '',
    name: '',
    type: 'GIT',
    endpoint: '',
    username: '',
    secret: '',
    description: '',
    enabled: true,
  })
  authType.value = 'BASIC'
}

function openEdit(row: Credential): void {
  Object.assign(form, {
    visible: true,
    id: row.id,
    name: row.name,
    type: row.type,
    endpoint: row.endpoint,
    // 敏感字段永远是空的：后端不回显，所以「空」只能解读为「保持不变」
    username: '',
    secret: '',
    description: row.description ?? '',
    enabled: row.enabled,
  })
  authType.value = row.authType
}

async function save(): Promise<void> {
  if (!form.name.trim() || !form.endpoint.trim()) {
    ElMessage.warning('名称与地址必填')
    return
  }
  if (!isEdit.value && (!form.username.trim() || !form.secret)) {
    ElMessage.warning('新建时必须填写用户名与密码/令牌')
    return
  }
  if (isEdit.value && form.secret && !form.username.trim()) {
    // 只改密码时用户名可留空（沿用旧值），但完全不给用户名又给了密码是自相矛盾的输入
    ElMessage.warning('填写了新凭据内容时，请一并确认用户名')
    return
  }

  const body = {
    name: form.name.trim(),
    type: form.type,
    endpoint: form.endpoint.trim(),
    authType: authType.value,
    username: form.username,
    secret: form.secret,
    description: form.description.trim() || null,
    enabled: form.enabled,
  }

  if (isEdit.value) {
    await credentialApi.update({ ...body, id: form.id })
    ElMessage.success('已更新')
  } else {
    await credentialApi.create(body)
    ElMessage.success('已创建')
  }
  form.visible = false
  await load()
}

async function remove(row: Credential): Promise<void> {
  await ElMessageBox.confirm(
    `删除凭据「${row.name}」？此操作不可恢复（硬删除，密文一并消失）。`,
    '确认删除',
    { type: 'warning' },
  )
  await credentialApi.remove(row.id)
  ElMessage.success('已删除')
  await load()
}

// ---- 测试连接 ----

const testVisible = ref(false)
const testRow = ref<Credential | null>(null)
const probeUrl = ref('')
const testing = ref(false)
const testResult = ref<CredentialTestResult | null>(null)

/** GIT 需要用户给一个探测仓库地址；REGISTRY 直接打 /v2/，不需要额外输入 */
const needsProbeUrl = computed(() => testRow.value?.type === 'GIT')

function openTest(row: Credential): void {
  testRow.value = row
  probeUrl.value = ''
  testResult.value = null
  // ⚠️ 两种类型都要开弹窗 —— 结果 alert 只在这个弹窗里，不开就等于点了没反应。
  // （GIT 是「先填地址再点测试」，REGISTRY 是「开了就直接打」。）
  testVisible.value = true
  if (!needsProbeUrl.value) {
    void runTest()
  }
}

async function runTest(): Promise<void> {
  if (!testRow.value) return
  if (needsProbeUrl.value && !probeUrl.value.trim()) {
    ElMessage.warning('请填写一个用于测试的仓库地址')
    return
  }
  testing.value = true
  testResult.value = null
  try {
    testResult.value = await credentialApi.test(testRow.value.id, probeUrl.value.trim() || undefined)
    await load()
  } finally {
    testing.value = false
  }
}

/** 3xx 的「对端要求地址为 X」—— 一键回填，别让用户手工抄一个长 URL */
function applySuggested(): void {
  if (testResult.value?.suggestedUrl) {
    probeUrl.value = testResult.value.suggestedUrl
    testResult.value = null
  }
}

onMounted(load)
</script>

<template>
  <div class="credential-page">
    <div class="page-head">
      <h2>凭据管理</h2>
      <el-button type="primary" :disabled="!perm.has(apiCodes.credentialWrite)" @click="openCreate">
        新建凭据
      </el-button>
    </div>

    <el-alert
      type="info"
      :closable="false"
      show-icon
      title="凭据内容永不回显"
      description="列表与详情只显示「是否已配置」与指纹。编辑时输入框留空表示保持不变。凭据是平台级的：用它做的操作在 GitLab / Harbor 侧都记在平台名下，租户间的仓库隔离请靠对端各自的 token / robot account 权限。"
      class="hint"
    />

    <el-table v-loading="loading" :data="rows" stripe>
      <el-table-column prop="name" label="名称" min-width="160" />
      <el-table-column label="类型" width="110">
        <template #default="{ row }">{{ typeLabel(row.type) }}</template>
      </el-table-column>
      <el-table-column prop="endpoint" label="地址" min-width="220" show-overflow-tooltip />
      <el-table-column prop="authType" label="认证方式" width="100" />
      <el-table-column label="指纹" width="150">
        <template #default="{ row }">
          <span v-if="row.fingerprint" class="mono">{{ row.fingerprint }}</span>
          <span v-else class="dim">—</span>
        </template>
      </el-table-column>
      <el-table-column label="上次验证" min-width="200">
        <template #default="{ row }">
          <span v-if="row.lastVerifyError" class="err">{{ row.lastVerifyError }}</span>
          <span v-else-if="row.lastVerifiedAt">通过（{{ fmtDate(row.lastVerifiedAt) }}）</span>
          <span v-else class="dim">未验证</span>
        </template>
      </el-table-column>
      <el-table-column label="启用" width="80">
        <template #default="{ row }">
          <el-tag :type="row.enabled ? 'success' : 'info'" size="small">
            {{ row.enabled ? '启用' : '停用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="200" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openTest(row)">测试连接</el-button>
          <el-button link type="primary" :disabled="!perm.has(apiCodes.credentialWrite)" @click="openEdit(row)">
            编辑
          </el-button>
          <el-button link type="danger" :disabled="!perm.has(apiCodes.credentialWrite)" @click="remove(row)">
            删除
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 新建 / 编辑 -->
    <el-drawer v-model="form.visible" :title="isEdit ? '编辑凭据' : '新建凭据'" size="520px">
      <el-form label-width="100px">
        <el-form-item label="名称" required>
          <el-input v-model="form.name" placeholder="如 harbor-prod / gitlab-main" />
        </el-form-item>
        <el-form-item label="类型" required>
          <el-select v-model="form.type" style="width: 100%">
            <el-option v-for="o in TYPE_OPTIONS" :key="o.value" :label="o.label" :value="o.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="地址" required>
          <el-input
            v-model="form.endpoint"
            :placeholder="form.type === 'GIT' ? 'https://gitlab.example.com' : 'harbor.example.com'"
          />
          <div class="tip">填 host 基址（站点级），不是具体仓库或 project</div>
        </el-form-item>
        <el-form-item label="用户名">
          <el-input
            v-model="form.username"
            :placeholder="isEdit ? '留空 = 保持不变' : 'robot$platform+ci / ci-bot'"
          />
        </el-form-item>
        <el-form-item label="密码/令牌">
          <el-input
            v-model="form.secret"
            type="password"
            show-password
            :placeholder="isEdit ? '留空 = 保持不变' : 'PAT / robot account 密钥'"
          />
        </el-form-item>
        <el-form-item label="说明">
          <el-input v-model="form.description" />
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="form.enabled" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="form.visible = false">取消</el-button>
        <el-button type="primary" @click="save">保存</el-button>
      </template>
    </el-drawer>

    <!-- 测试连接：GIT 要先填探测地址，REGISTRY 打开即测 -->
    <el-dialog
      v-model="testVisible"
      :title="needsProbeUrl ? '测试 Git 凭据' : '测试镜像仓库连接'"
      width="560px"
    >
      <el-form v-if="needsProbeUrl" label-width="120px">
        <el-form-item label="探测仓库地址" required>
          <el-input
            v-model="probeUrl"
            placeholder="https://gitlab.example.com/group/repo.git"
          />
          <div class="tip">
            凭据是按 host 配的，能不能用取决于具体仓库的权限 —— 请填完整地址（带上 .git）
          </div>
        </el-form-item>
      </el-form>
      <el-alert
        v-if="testResult && !testResult.ok"
        type="error"
        :closable="false"
        show-icon
        :title="testResult.message"
        class="result"
      >
        <el-button v-if="testResult.suggestedUrl" link type="primary" @click="applySuggested">
          改用 {{ testResult.suggestedUrl }}
        </el-button>
      </el-alert>
      <el-alert
        v-else-if="testResult?.ok"
        type="success"
        :closable="false"
        show-icon
        title="连接成功"
        class="result"
      />
      <template #footer>
        <el-button @click="testVisible = false">关闭</el-button>
        <el-button type="primary" :loading="testing" @click="runTest">测试</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.credential-page {
  padding: 16px;
}
.page-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}
.page-head h2 {
  margin: 0;
  font-size: 18px;
}
.hint {
  margin-bottom: 12px;
}
.mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
}
.err {
  color: var(--el-color-danger);
}
.dim {
  color: var(--text-3);
}
</style>
```

⚠️ **`el-dialog` / `el-drawer` 的样式坑**：这两个组件的**内部**节点不带 `data-v-*`，scoped 的 `:deep()`
匹配不到它们。所以下面那段 `.tip` 与 `.result` 的样式**必须放在非 scoped 的 `<style>` 块**里，
并且用页面前缀包住（本仓已踩过一次）。

- [ ] **Step 2: 补非 scoped 样式块**

在 Step 1 的 `<style scoped>` 块**之后**追加：

```vue
<style>
/* ⚠️ 非 scoped：el-drawer / el-dialog 的内部节点没有 data-v-*，scoped 的 :deep() 匹配不到。
   用 .credential-page 前缀限制作用域（本仓既有约定）。 */
.el-drawer .tip,
.el-dialog .tip {
  margin-top: 4px;
  color: var(--text-3);
  font-size: 12px;
  line-height: 1.5;
}
.el-dialog .result {
  margin-top: 8px;
}
</style>
```

- [ ] **Step 3: 类型检查**

```bash
cd /d/coding/k8s-cloud-platform/platform-web
npx vue-tsc --noEmit -p tsconfig.json 2>&1 | tail -20
```

Expected: 退出码 0，无输出。

- [ ] **Step 4: 提交**

```bash
git add platform-web/src/views/CredentialView.vue
git commit -m "feat(credential): 凭据管理页（列表 + 抽屉表单 + 测试连接）"
```

---

### Task 12: 前端路由 + 菜单

**Files:**
- Modify: `platform-web/src/router/index.ts`
- Modify: `platform-web/src/layouts/MainLayout.vue`

**Interfaces:**
- Consumes: `pageCodes.credential`（Task 10）、`CredentialView.vue`（Task 11）
- Produces: 路由 `/credentials` + 菜单项

- [ ] **Step 1: 加路由**

在 `platform-web/src/router/index.ts` 的 `audit` 那一行（第 48 行）**之后**插入：

```ts
        { path: 'credentials', name: 'credentials', component: () => import('@/views/CredentialView.vue'), meta: { title: '凭据管理', group: '平台管理', requiresPerm: [pageCodes.credential] } },
```

- [ ] **Step 2: 加菜单项 + 改分组条件**

在 `platform-web/src/layouts/MainLayout.vue`：

① 第 149 行的分组标题 `v-if` **末尾加一个条件**（分组标题与其下条目必须同源，否则会出现「有标题没条目」的空分组）：

```vue
        <div v-if="can(pageCodes.cluster) || can(pageCodes.node.list) || can(pageCodes.namespace.list) || can(pageCodes.template) || can(pageCodes.audit) || can(pageCodes.credential)" class="menu-group">平台管理</div>
```

② 在审计菜单项（第 166-169 行）**之后**插入：

```vue
        <el-menu-item v-if="can(pageCodes.credential)" index="/credentials">
          <el-icon><Key /></el-icon>
          <template #title>凭据管理</template>
        </el-menu-item>
```

③ 在 `<script setup>` 里从 `@element-plus/icons-vue` 导入 `Key`（**按既有 import 的字母序插入**）。

- [ ] **Step 3: 类型检查**

```bash
cd /d/coding/k8s-cloud-platform/platform-web
npx vue-tsc --noEmit -p tsconfig.json 2>&1 | tail -20
```

Expected: 退出码 0，无输出。

- [ ] **Step 4: 前端构建**

```bash
cd /d/coding/k8s-cloud-platform/platform-web
npm run build 2>&1 | tail -15
```

Expected: 构建成功（`✓ built in ...`）。

- [ ] **Step 5: 提交**

```bash
git add platform-web/src/router/index.ts platform-web/src/layouts/MainLayout.vue
git commit -m "feat(credential): 挂 /credentials 路由与平台管理菜单项"
```

---

### Task 13: 收尾验证与台账

**Files:**
- Modify: `docs/task-ledger-2026-10-12.md`

**Interfaces:**
- Consumes: 前 12 个任务的全部成果
- Produces: 全量构建绿 + 台账更新

- [ ] **Step 1: 全量后端测试**

```bash
cd /d/coding/k8s-cloud-platform
mvn -o test -Dsurefire.failIfNoSpecifiedTests=false 2>&1 | grep -E "Tests run|BUILD|ERROR" | tail -30
```

Expected: `BUILD SUCCESS`。本批新增的测试类全绿。
**唯一允许的失败**是 `k8s-core` 的 `WorkloadConverterStaticIpTest`（@HEAD 即 NPE，非本批引入）。

- [ ] **Step 2: 全量前端类型检查与构建**

```bash
cd /d/coding/k8s-cloud-platform/platform-web
npx vue-tsc --noEmit -p tsconfig.json && npm run build 2>&1 | tail -8
```

Expected: 退出码 0，构建成功。

- [ ] **Step 3: 确认迁移可整体重放**

按本仓惯例，`db/migration/` 下的文件应能被**整体重放**（S0.1 时发现 `V2026_10_09_1` 因注释缺空格
而无法重放）。抽验新文件：

```bash
cd /d/coding/k8s-cloud-platform
grep -nE '^--[^ ]' platform-data/src/main/resources/db/migration/V2026_10_13_3__credential.sql
```

Expected: **无输出**（`--` 后一定跟了空白字符）。

- [ ] **Step 4: 更新台账**

在 `docs/task-ledger-2026-10-12.md` 的 S0.2 段落末尾追加：

```markdown
> **✅ 实施完成（2026-10-13）**：表 `platform_credential`（13 列，硬删除）+ 6 行权限（3 个码，
> 只绑 admin）。范围 = **凭据库内核 + 测试连接**；投递（命名空间 Secret / imagePullSecret /
> 构建 Pod 注入）留给 S3.1.3 / S3.5.13。
>
> **两条口径在实现期被实测改掉**（spec §0 有完整实测表）：
> 1. 「公开仓库会忽略错凭据 → `info/refs` 探针假阳性」的假设**被推翻**：实测 gitee / gitlab
>    带错密码对公共仓库返回 **401**。所以不引入 `provider` 字段、不做按 host 分派。
> 2. 但发现两类**新的**假阳性/假阴性：`200 + HTML`（代理登录页）会骗过只看状态码的判定
>    → 改为**校验响应体以 git pkt-line 头开头**；GitLab 少 `.git` 是 301
>    → **不猜后缀、不跟随重定向**，3xx 时把 `Location` 给用户一键回填。
>
> **401 是二义的**（实测：不存在的仓库与错密码同码）→ 文案一律「凭据不通过或无权访问此仓库」，
> **不得写「密码错误」**。
>
> **有意不做**：SSH、按租户授权、匿名连接、主密钥轮换、删除保护、凭据版本历史、其它 `type`。
>
> **未验证（留给部署后）**：github.com 的 git 端点（实测机器连不上那个 IP）、
> 有效凭据 + 需认证仓库的成功路径、Harbor `/v2/` 的 401 行为。
> 「真实服务器出站可达性」见 spec §0.1 的环境提醒。
```

- [ ] **Step 5: 提交**

```bash
git add docs/task-ledger-2026-10-12.md
git commit -m "docs(ledger): S0.2 凭据管理标记实施完成"
```

---

## 附：部署（不属于任一任务，交付后执行）

1. **先重放** `V2026_10_13_3__credential.sql`（手工，本仓没有 Flyway）
2. **再配** `CREDENTIAL_AES_KEY`（不配则应用**拒绝启动**）
3. **最后**起新构建
4. 重放后重新登录或等权限缓存 TTL（30s）
5. 按 spec §10.4 的待验清单逐条走：真 GitLab / GitHub 凭据测试连接、错密码的二义文案、
   Harbor 凭据、列表确认无凭据内容、审计确认 4 类动作且不含 endpoint
