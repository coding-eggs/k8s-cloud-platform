# 平台权限与鉴权体系 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 补齐平台管理层 RBAC 管理面（用户/角色/权限/租户成员），建立"平台族/租户族"两作用域鉴权，并把租户上下文并入 session-renewal 单通道签发。

**Architecture:** 权限点与角色定义全局一份（`platform_role` 加 `scope`），租户维度只出现在授权层。签发时由 auth-server 计算"角色→权限"闭包塞进 JWT `data.permissions`；platform-api 的表驱动 `PermissionAuthorizationManager` 按 URL 匹配权限点做拦截（默认拒绝 + 启动期交叉校验）；k8s 资源边界仍由既有 `ResourceAccessResolver` + 分配表独家负责，本次不动。切租户 = `session-renewal` 带 `tenant_id` 重签，单 token 模型。

**Tech Stack:** Java 21 / Spring Boot 4.0.6 / Spring Authorization Server 2.x / MyBatis / MySQL / JWT(nimbus) / Vue 3 + TS + Element Plus。

**Spec:** `docs/superpowers/specs/2026-09-24-rbac-management-design.md`（先读它再执行；本计划从它论证）

## Global Constraints

- 包根 `com.coding`；platform-api=`com.coding.platformapi`、platform-data=`com.coding.data`、platform-auth=`com.coding.auth`、platform-common=`com.coding.common`、k8s-server=`com.coding.k8sserver`。
- ID 生成统一用 `com.coding.common.utils.ULIDGenerator.generateULID()`（返回 16 位 hex String）。
- 业务异常统一 `throw new CloudPlatformException(EnumResponseType.XXX)`；已有码：`USER_UN_LOGIN(4003)`、`NON_AUTH_ENTRY_POINT(403)`、`TENANT_MISMATCH(10006,"租户ID不匹配")`、`TOKEN_TENANT_MISSING(10007)`、`SERVICE_ACCOUNT_NAME_EXIST`、`BEAN_VALIDATION_EXCEPTION`。新增码见 Task 2。
- 管理域接口（本计划新增）**全 POST + `@RequestBody`，禁用路径变量**（spec §6.2）。参数校验沿用现有手写 `StringUtils.hasText` 风格，不引 `@Valid`（现有 controller 未用）。
- 不加数据库外键；不变量（spec §3.4）由服务层同事务保证。跨多表写的方法标 `@Transactional`（`org.springframework.transaction.annotation`）。
- Mapper 双份：接口在 `platform-data/src/main/java/com/coding/data/mapper/auth/`，XML 在 `platform-data/src/main/resources/mapper/auth/`，namespace 与接口全限定名一致。
- authority 前缀约定：平台族角色 `PLATFORM:<code>`（既有），权限点 `PERM:<code>`（新增，本计划）。
- 提交信息结尾必须附：`Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`。
- 后端测试放 `platform-api/src/test/java/...`，纯 Mockito 单测（参考 `HpaServiceTest`：`mock(Mapper.class)` + 构造 service + AssertJ）。**platform-auth 无测试依赖，其改动以人工/集成验证（Task 12 给出验证脚本），不写单测。**

## 依赖顺序

Task 1 → 2 → 3（数据层）→ 4（seed）→ 5（模型/工具）→ 6（签发闭包+tenant_id 通道）→ 7（删死码，独立）→ 8（PERM authorities）→ 9/10/11（registry→manager→交叉校验）→ 12（auth 集成 spike，验证 6 的透传）→ 13（上下文一致器）→ 14/15/16（服务+controller，16 依赖 13 与 Task 19 的 AuthContext——AuthContext 在 16 里先建即可）→ 19（/user/me、/user/my-tenants）→ 17（前端 token）→ 18（切换器/菜单）→ 20（三页面）。收尾验证最后。

---

## Task 1: platform_role 迁移 + 模型加 scope/builtIn + 枚举

**Files:**
- Create: `platform-data/src/main/java/com/coding/data/models/auth/RoleScope.java`
- Modify: `platform-data/src/main/java/com/coding/data/models/auth/PlatformRole.java`
- Modify: `platform-data/src/main/resources/mapper/auth/PlatformRoleMapper.xml`
- Create: `platform-data/src/main/resources/db/migration/V2026_09_24_1__rbac_schema.sql`（若无 flyway，见 Step 5：改为手动执行 + 记录到 `k8s_cloud_platform.sql`）

**Interfaces:**
- Produces: `enum RoleScope { PLATFORM, TENANT }`；`PlatformRole.getScope():String`、`getBuiltIn():Byte`（列 `built_in`，字段驼峰 `builtIn`）。
- Consumes: 无（起点）。

- [ ] **Step 1: 建 RoleScope 枚举**

```java
package com.coding.data.models.auth;

/** 角色族：PLATFORM=平台管理动作；TENANT=租户内管理动作。见 spec §3。 */
public enum RoleScope {
    PLATFORM, TENANT;

    public static boolean isValid(String v) {
        for (RoleScope s : values()) if (s.name().equals(v)) return true;
        return false;
    }
}
```

- [ ] **Step 2: PlatformRole 加两字段**

在 `PlatformRole.java` 的 `private String description;` 之后插入：

```java
    /** 角色族：PLATFORM / TENANT（RoleScope.name()） */
    private String scope;

    /** 内置角色：1 不可删、code/scope 不可改；0 可编辑 */
    private Byte builtIn;
```

- [ ] **Step 3: PlatformRoleMapper.xml 加列映射与 selectByPrimaryKey 覆盖**

在 `<resultMap id="BaseResultMap">` 内 `<result column="description" .../>` 之后加两行：

```xml
    <result column="scope" jdbcType="VARCHAR" property="scope" />
    <result column="built_in" jdbcType="TINYINT" property="builtIn" />
```

改 `<sql id="Base_Column_List">` 为：

```xml
  <sql id="Base_Column_List">
    id, `name`, code, description, scope, built_in, `status`, created_at, updated_at, deleted_at
  </sql>
```

（`insertSelective`/`updateByPrimaryKeySelective` 用了 `<if test="... != null">`，新字段自动被覆盖，无需改；若 `insert` 全列版存在则同步补两列——本 XML 无全列 `insert`，跳过。）

- [ ] **Step 4: 写迁移 SQL**

Create `platform-data/src/main/resources/db/migration/V2026_09_24_1__rbac_schema.sql`：

```sql
ALTER TABLE platform_role
  ADD COLUMN scope     varchar(16) NOT NULL DEFAULT 'PLATFORM' COMMENT '角色族：PLATFORM/TENANT',
  ADD COLUMN built_in  tinyint     NOT NULL DEFAULT 0  COMMENT '内置角色：不可删除、code/scope 不可改';
-- 既有内置 admin 角色标记为不可改
UPDATE platform_role SET scope='PLATFORM', built_in=1 WHERE code='admin';
```

- [ ] **Step 5: 应用到 dev 库 + 同步全量 dump**

若项目用 flyway（`db/migration/` 存在即可能）：重启 platform-api/auth 自动迁移。若无：手动执行该 SQL 到 dev MySQL，并把两列的 DDL 与 `admin` 行的 seed 追加进根目录 `k8s_cloud_platform.sql` 的 `platform_role` 段（保持 dump 与实库一致，供新环境初始化）。

Run（手动应用时）: `mysql -u<user> -p<pwd> <db> < platform-data/src/main/resources/db/migration/V2026_09_24_1__rbac_schema.sql`

- [ ] **Step 6: 编译校验**

Run: `mvn -q -pl platform-data -am compile`
Expected: BUILD SUCCESS（`getScope()`/`getBuiltIn()` 由 Lombok `@Data` 生成）。

- [ ] **Step 7: Commit**

```bash
git add platform-data/src/main/java/com/coding/data/models/auth/RoleScope.java \
        platform-data/src/main/java/com/coding/data/models/auth/PlatformRole.java \
        platform-data/src/main/resources/mapper/auth/PlatformRoleMapper.xml \
        platform-data/src/main/resources/db/migration/V2026_09_24_1__rbac_schema.sql
git commit -m "feat(rbac): platform_role 增加 scope/built_in 列与 RoleScope 枚举

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Task 2: 新增关联表模型 + mapper（role_permission / user_tenant / user_tenant_role）

三张表**在 schema 里已存在**（见 `k8s_cloud_platform.sql`），本任务只补 Java model + mapper + XML（当前完全没有）。这是写入口的地基。

**Files:**
- Create: `platform-data/.../models/auth/PlatformRolePermission.java`, `PlatformUserTenant.java`, `PlatformUserTenantRole.java`
- Create: `platform-data/.../mapper/auth/PlatformRolePermissionMapper.java` + `.xml`
- Create: `platform-data/.../mapper/auth/PlatformUserTenantMapper.java` + `.xml`
- Create: `platform-data/.../mapper/auth/PlatformUserTenantRoleMapper.java` + `.xml`
- Modify: `platform-common/.../exception/EnumResponseType.java`（新增错误码）

**Interfaces:**
- Produces（供后续任务消费的精确签名）：
  - `PlatformRolePermission`: `id/roleId/permissionId/createdAt`
  - `PlatformUserTenant`: `id/userId/tenantId/createdAt`
  - `PlatformUserTenantRole`: `id/userId/tenantId/roleId/createdAt`
  - `PlatformRolePermissionMapper`: `int insert(PlatformRolePermission r)`, `int deleteByRoleId(String roleId)`, `List<String> selectPermissionIdsByRoleId(String roleId)`, `List<String> selectPermissionCodesByRoleId(String roleId)`, `List<String> selectRoleIdsByPermissionId(String permissionId)`
  - `PlatformUserTenantMapper`: `int insert(PlatformUserTenant r)`, `int deleteByUserAndTenant(String userId, String tenantId)`, `boolean exists(String userId, String tenantId)`, `List<String> selectTenantIdsByUser(String userId)`, `List<PlatformUserTenant> selectByTenantId(String tenantId)`
  - `PlatformUserTenantRoleMapper`: `int insert(PlatformUserTenantRole r)`, `int delete(String userId, String tenantId, String roleId)`, `int deleteByUserAndTenant(String userId, String tenantId)`, `List<PlatformUserTenantRole> selectByTenantAndUser(String tenantId, String userId)`, `List<String> selectRoleIdsByUserAndTenant(String userId, String tenantId)`, `int countByTenantAndRole(String tenantId, String roleId)`

- [ ] **Step 1: 三个 model**

`PlatformRolePermission.java`:

```java
package com.coding.data.models.auth;
import java.io.Serializable;
import java.util.Date;
import lombok.Data;
/** platform_role_permission：角色↔权限点关联 */
@Data
public class PlatformRolePermission implements Serializable {
    private String id;
    private String roleId;
    private String permissionId;
    private Date createdAt;
}
```

`PlatformUserTenant.java`（表 `platform_user_tenant`，成员资格）：

```java
package com.coding.data.models.auth;
import java.io.Serializable;
import java.util.Date;
import lombok.Data;
/** platform_user_tenant：用户↔租户成员资格（无角色） */
@Data
public class PlatformUserTenant implements Serializable {
    private String id;
    private String userId;
    private String tenantId;
    private Date createdAt;
}
```

`PlatformUserTenantRole.java`（表 `platform_user_tenant_role`，租户内角色）：

```java
package com.coding.data.models.auth;
import java.io.Serializable;
import java.util.Date;
import lombok.Data;
/** platform_user_tenant_role：用户在某租户内被授予的角色（租户域授权权威表） */
@Data
public class PlatformUserTenantRole implements Serializable {
    private String id;
    private String userId;
    private String tenantId;
    private String roleId;
    private Date createdAt;
}
```

- [ ] **Step 2: EnumResponseType 加码**

在 `TENANT_MISMATCH(10006,...)` 附近追加（沿用枚举既有构造风格，code 递增）：

```java
    ROLE_BUILTIN_READONLY(10020, "内置角色不可修改或删除"),
    ROLE_SCOPE_MISMATCH(10021, "角色族与权限点族不一致"),
    TENANT_ADMIN_REQUIRED(10022, "租户至少需保留一名管理员"),
    TENANT_MEMBER_NOT_FOUND(10023, "用户不是该租户成员"),
    PERMISSION_NOT_FOUND(10024, "权限点不存在"),
```

（若构造签名不同，按现有枚举项实际参数补，例如带 msg 的 `(int, String)`；先看文件顶部确认。）

- [ ] **Step 3: PlatformRolePermissionMapper 接口**

```java
package com.coding.data.mapper.auth;
import com.coding.data.models.auth.PlatformRolePermission;
import org.apache.ibatis.annotations.Param;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface PlatformRolePermissionMapper {
    int insert(PlatformRolePermission record);
    int deleteByRoleId(@Param("roleId") String roleId);
    List<String> selectPermissionIdsByRoleId(@Param("roleId") String roleId);
    List<String> selectPermissionCodesByRoleId(@Param("roleId") String roleId);
    List<String> selectRoleIdsByPermissionId(@Param("permissionId") String permissionId);
}
```

- [ ] **Step 4: PlatformRolePermissionMapper.xml**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="com.coding.data.mapper.auth.PlatformRolePermissionMapper">
  <insert id="insert">
    insert into platform_role_permission (id, role_id, permission_id, created_at)
    values (#{id}, #{roleId}, #{permissionId}, #{createdAt})
  </insert>
  <delete id="deleteByRoleId">
    delete from platform_role_permission where role_id = #{roleId}
  </delete>
  <select id="selectPermissionIdsByRoleId" resultType="java.lang.String">
    select permission_id from platform_role_permission where role_id = #{roleId}
  </select>
  <select id="selectPermissionCodesByRoleId" resultType="java.lang.String">
    select p.code from platform_role_permission rp
    inner join platform_permission p on p.id = rp.permission_id
    where rp.role_id = #{roleId}
  </select>
  <select id="selectRoleIdsByPermissionId" resultType="java.lang.String">
    select role_id from platform_role_permission where permission_id = #{permissionId}
  </select>
</mapper>
```

- [ ] **Step 5: PlatformUserTenantMapper 接口 + XML**

接口：

```java
package com.coding.data.mapper.auth;
import com.coding.data.models.auth.PlatformUserTenant;
import org.apache.ibatis.annotations.Param;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface PlatformUserTenantMapper {
    int insert(PlatformUserTenant record);
    int deleteByUserAndTenant(@Param("userId") String userId, @Param("tenantId") String tenantId);
    boolean exists(@Param("userId") String userId, @Param("tenantId") String tenantId);
    List<String> selectTenantIdsByUser(@Param("userId") String userId);
    List<PlatformUserTenant> selectByTenantId(@Param("tenantId") String tenantId);
}
```

XML（`namespace` = 上面全限定名）：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="com.coding.data.mapper.auth.PlatformUserTenantMapper">
  <resultMap id="Base" type="com.coding.data.models.auth.PlatformUserTenant">
    <id column="id" property="id"/>
    <result column="user_id" property="userId"/>
    <result column="tenant_id" property="tenantId"/>
    <result column="created_at" property="createdAt"/>
  </resultMap>
  <insert id="insert">
    insert into platform_user_tenant (id, user_id, tenant_id, created_at)
    values (#{id}, #{userId}, #{tenantId}, #{createdAt})
  </insert>
  <delete id="deleteByUserAndTenant">
    delete from platform_user_tenant where user_id = #{userId} and tenant_id = #{tenantId}
  </delete>
  <select id="exists" resultType="boolean">
    select count(1) > 0 from platform_user_tenant where user_id = #{userId} and tenant_id = #{tenantId}
  </select>
  <select id="selectTenantIdsByUser" resultType="java.lang.String">
    select ut.tenant_id from platform_user_tenant ut
    inner join platform_tenant t on t.id = ut.tenant_id and t.deleted_at is null
    where ut.user_id = #{userId}
  </select>
  <select id="selectByTenantId" resultMap="Base">
    select id, user_id, tenant_id, created_at from platform_user_tenant where tenant_id = #{tenantId}
  </select>
</mapper>
```

- [ ] **Step 6: PlatformUserTenantRoleMapper 接口 + XML**

接口：

```java
package com.coding.data.mapper.auth;
import com.coding.data.models.auth.PlatformUserTenantRole;
import org.apache.ibatis.annotations.Param;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface PlatformUserTenantRoleMapper {
    int insert(PlatformUserTenantRole record);
    int delete(@Param("userId") String userId, @Param("tenantId") String tenantId, @Param("roleId") String roleId);
    int deleteByUserAndTenant(@Param("userId") String userId, @Param("tenantId") String tenantId);
    List<PlatformUserTenantRole> selectByTenantAndUser(@Param("tenantId") String tenantId, @Param("userId") String userId);
    List<String> selectRoleIdsByUserAndTenant(@Param("userId") String userId, @Param("tenantId") String tenantId);
    int countByTenantAndRole(@Param("tenantId") String tenantId, @Param("roleId") String roleId);
}
```

XML：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="com.coding.data.mapper.auth.PlatformUserTenantRoleMapper">
  <resultMap id="Base" type="com.coding.data.models.auth.PlatformUserTenantRole">
    <id column="id" property="id"/>
    <result column="user_id" property="userId"/>
    <result column="tenant_id" property="tenantId"/>
    <result column="role_id" property="roleId"/>
    <result column="created_at" property="createdAt"/>
  </resultMap>
  <insert id="insert">
    insert into platform_user_tenant_role (id, user_id, tenant_id, role_id, created_at)
    values (#{id}, #{userId}, #{tenantId}, #{roleId}, #{createdAt})
  </insert>
  <delete id="delete">
    delete from platform_user_tenant_role
    where user_id = #{userId} and tenant_id = #{tenantId} and role_id = #{roleId}
  </delete>
  <delete id="deleteByUserAndTenant">
    delete from platform_user_tenant_role where user_id = #{userId} and tenant_id = #{tenantId}
  </delete>
  <select id="selectByTenantAndUser" resultMap="Base">
    select id, user_id, tenant_id, role_id, created_at from platform_user_tenant_role
    where tenant_id = #{tenantId} and user_id = #{userId}
  </select>
  <select id="selectRoleIdsByUserAndTenant" resultType="java.lang.String">
    select role_id from platform_user_tenant_role where user_id = #{userId} and tenant_id = #{tenantId}
  </select>
  <select id="countByTenantAndRole" resultType="int">
    select count(1) from platform_user_tenant_role where tenant_id = #{tenantId} and role_id = #{roleId}
  </select>
</mapper>
```

- [ ] **Step 7: 编译**

Run: `mvn -q -pl platform-data -am compile`
Expected: BUILD SUCCESS。

- [ ] **Step 8: Commit**

```bash
git add platform-data/src/main/java/com/coding/data/models/auth/PlatformRolePermission.java \
        platform-data/src/main/java/com/coding/data/models/auth/PlatformUserTenant.java \
        platform-data/src/main/java/com/coding/data/models/auth/PlatformUserTenantRole.java \
        platform-data/src/main/java/com/coding/data/mapper/auth/PlatformRolePermissionMapper.java \
        platform-data/src/main/java/com/coding/data/mapper/auth/PlatformUserTenantMapper.java \
        platform-data/src/main/java/com/coding/data/mapper/auth/PlatformUserTenantRoleMapper.java \
        platform-data/src/main/resources/mapper/auth/PlatformRolePermissionMapper.xml \
        platform-data/src/main/resources/mapper/auth/PlatformUserTenantMapper.xml \
        platform-data/src/main/resources/mapper/auth/PlatformUserTenantRoleMapper.xml \
        platform-common/src/main/java/com/coding/common/exception/EnumResponseType.java
git commit -m "feat(rbac): 补 role_permission/user_tenant/user_tenant_role 三表 model 与 mapper

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Task 3: 权限点闭包查询（role→permission codes，供签发与配置页）

platform-api 的 `PlatformPermissionMapper` 目前只有 CRUD，缺"按 code 查"和"按角色集合查权限 code"。auth-server 签发闭包、配置页读目录都靠它。

**Files:**
- Modify: `platform-data/.../mapper/auth/PlatformPermissionMapper.java`
- Modify: `platform-data/.../mapper/auth/PlatformRoleMapper.java`
- Modify: `platform-data/src/main/resources/mapper/auth/PlatformPermissionMapper.xml`
- Modify: `platform-data/src/main/resources/mapper/auth/PlatformRoleMapper.xml`

**Interfaces:**
- Produces:
  - `List<PlatformPermission> selectAllActive()`（未删除全量，配置页目录）
  - `PlatformPermission selectByCode(String code)`
  - `List<String> selectPermissionCodesByRoleIds(Collection<String> roleIds)`（闭包核心：给定角色 id 集合 → 去重权限 code；调用方保证非空集合，见 Task 6 codesOf）
  - `PlatformRole selectByCode(String code)`（供种 owner 角色 id、tenant-admin id 反查）
  - `PlatformUserRoleMapper.selectRoleIdsByUser(userId)`（Task 3 Step 4 补）

- [ ] **Step 1: PlatformPermissionMapper 接口加方法**

```java
    List<PlatformPermission> selectAllActive();
    PlatformPermission selectByCode(@Param("code") String code);
    List<String> selectPermissionCodesByRoleIds(@Param("roleIds") java.util.Collection<String> roleIds);
```

- [ ] **Step 2: PlatformPermissionMapper.xml**

先看现有 resultMap 列名（应含 `id,domain,resource,action,code,description,created_at,deleted_at`），补：

```xml
  <select id="selectAllActive" resultMap="BaseResultMap">
    select id, domain, resource, action, code, description, created_at, deleted_at
    from platform_permission where deleted_at is null order by domain, code
  </select>
  <select id="selectByCode" resultMap="BaseResultMap">
    select id, domain, resource, action, code, description, created_at, deleted_at
    from platform_permission where code = #{code} and deleted_at is null
  </select>
  <select id="selectPermissionCodesByRoleIds" resultType="java.lang.String">
    select distinct p.code
    from platform_role_permission rp
    inner join platform_permission p on p.id = rp.permission_id and p.deleted_at is null
    where rp.role_id in
    <foreach collection="roleIds" item="rid" open="(" separator="," close=")">#{rid}</foreach>
  </select>
```

- [ ] **Step 3: PlatformRoleMapper 接口加 `selectByCode` + 域内角色查询**

```java
    PlatformRole selectByCode(@Param("code") String code);
```

（`selectPermissionCodesByRoleIds` 在 permission mapper，此处只需 role id。用户角色 id 的取数放到 auth 侧 service，Task 5 建 `PlatformUserRoleMapper.selectRoleIdsByUser` — 见下。）

- [ ] **Step 4: PlatformUserRoleMapper 加 selectRoleIdsByUser**

`PlatformUserRoleMapper.java` 现只有 `selectRoleCodesByUser`。加：

```java
    List<String> selectRoleIdsByUser(@Param("userId") String userId);
```

`PlatformUserRoleMapper.xml` 加（镜像既有 `selectRoleCodesByUser`，把 `r.code` 换成 `r.id`）：

```xml
    <select id="selectRoleIdsByUser" resultType="java.lang.String">
        SELECT r.id
        FROM platform_user_role ur
        INNER JOIN platform_role r ON ur.role_id = r.id
        WHERE ur.user_id = #{userId} AND r.deleted_at IS NULL AND r.status = 1
    </select>
```

- [ ] **Step 5: PlatformRoleMapper.xml 加 selectByCode**

```xml
  <select id="selectByCode" resultMap="BaseResultMap">
    select <include refid="Base_Column_List"/> from platform_role
    where code = #{code} and deleted_at is null
  </select>
```

- [ ] **Step 6: 编译 + Commit**

Run: `mvn -q -pl platform-data -am compile` → SUCCESS。

```bash
git add platform-data/src/main/java/com/coding/data/mapper/auth/PlatformPermissionMapper.java \
        platform-data/src/main/java/com/coding/data/mapper/auth/PlatformRoleMapper.java \
        platform-data/src/main/java/com/coding/data/mapper/auth/PlatformUserRoleMapper.java \
        platform-data/src/main/resources/mapper/auth/PlatformPermissionMapper.xml \
        platform-data/src/main/resources/mapper/auth/PlatformRoleMapper.xml \
        platform-data/src/main/resources/mapper/auth/PlatformUserRoleMapper.xml
git commit -m "feat(rbac): 权限点按 code/角色集合查询 + 用户角色 id 查询

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Task 4: 权限目录 seed（权限点 + 内置角色 + 关联）

spec §3.3 的目录以一次性迁移 SQL 落地（运行时只读，Task 6 交叉校验依赖这些 API 行）。

**Files:**
- Create: `platform-data/src/main/resources/db/migration/V2026_09_24_2__rbac_seed.sql`

**Interfaces:**
- Produces: 数据库中存在权限点（domain=API，code 如 `platform:tenant:provision`）、内置角色 `admin`(PLATFORM)/`tenant-admin`(TENANT)/`tenant-member`(TENANT) 及其 `platform_role_permission` 关联。`admin` 关联全部 PLATFORM 权限。

- [ ] **Step 1: 写 seed（幂等：INSERT ... ON DUPLICATE KEY UPDATE）**

因 `platform_permission.code` 有唯一索引、`platform_role.code` 唯一，用 `INSERT ... ON DUPLICATE KEY UPDATE` 保持幂等。`platform_role_permission` 有 `uk_role_perm` 唯一索引，同理。

```sql
-- ===== 权限点（API 域，resource=URL 模式，action=HTTP 方法，本平台全 POST）=====
INSERT INTO platform_permission (id,domain,resource,action,code,description) VALUES
 ('perm_tenant_provision','API','/tenant/create','POST','platform:tenant:provision','建租户'),
 ('perm_tenant_provision_upd','API','/tenant/update','POST','platform:tenant:update','改租户'),
 ('perm_tenant_delete','API','/tenant/delete','POST','platform:tenant:delete','删租户'),
 ('perm_tenant_provision_sa','API','/tenant/provision','POST','platform:tenant:provision:sa','重开通租户 SA'),
 ('perm_tenant_read','API','/tenant/list','POST','platform:tenant:read','租户列表'),
 ('perm_tenant_get','API','/tenant/get','POST','platform:tenant:get','租户详情'),
 ('perm_alloc_manage','API','/tenant/namespace/allocate','POST','platform:allocation:manage','分配命名空间'),
 ('perm_alloc_manage_del','API','/tenant/namespace/deallocate','POST','platform:allocation:manage','取消分配'),
 ('perm_alloc_list','API','/tenant/namespace/list','POST','platform:allocation:list','分配列表'),
 ('perm_cluster_manage','API','/cluster/create','POST','platform:cluster:manage','建集群'),
 ('perm_user_manage','API','/user/create','POST','platform:user:manage','建用户'),
 ('perm_user_manage2','API','/user/update','POST','platform:user:manage','改用户'),
 ('perm_user_manage3','API','/user/delete','POST','platform:user:manage','删用户'),
 ('perm_user_manage4','API','/user/platformRole/grant','POST','platform:user:manage','授予平台角色'),
 ('perm_user_manage5','API','/user/platformRole/revoke','POST','platform:user:manage','回收平台角色'),
 ('perm_user_list','API','/user/list','POST','platform:user:manage','用户列表'),
 ('perm_user_get','API','/user/get','POST','platform:user:manage','用户详情'),
 ('perm_role_manage','API','/role/create','POST','platform:role:manage','建角色'),
 ('perm_role_manage2','API','/role/update','POST','platform:role:manage','改角色'),
 ('perm_role_manage3','API','/role/delete','POST','platform:role:manage','删角色'),
 ('perm_role_manage4','API','/role/permission/save','POST','platform:role:manage','保存角色权限'),
 ('perm_role_read','API','/role/list','POST','platform:role:read','角色列表'),
 ('perm_perm_read','API','/permission/list','POST','platform:role:read','权限目录'),
 ('perm_member_manage_self','API','/tenant/member/add','POST','tenant:member:manage','加入成员(自管/代管 ANY-of)'),
 ('perm_member_manage_del','API','/tenant/member/remove','POST','tenant:member:manage','移除成员'),
 ('perm_member_role_grant','API','/tenant/member/role/grant','POST','tenant:member:manage','授予租户角色'),
 ('perm_member_role_revoke','API','/tenant/member/role/revoke','POST','tenant:member:manage','回收租户角色'),
 ('perm_member_delegate','API','/tenant/member/add','POST','platform:member:manage','代管成员加入'),
 ('perm_member_delegate2','API','/tenant/member/remove','POST','platform:member:manage','代管成员移除'),
 ('perm_member_delegate3','API','/tenant/member/role/grant','POST','platform:member:manage','代管授予角色'),
 ('perm_member_delegate4','API','/tenant/member/role/revoke','POST','platform:member:manage','代管回收角色'),
 ('perm_member_list','API','/tenant/member/list','POST','tenant:overview:view','看本租户成员')
ON DUPLICATE KEY UPDATE description=VALUES(description), resource=VALUES(resource), action=VALUES(action);

-- ===== 内置角色（admin 已存在于 Task 1，补 scope/built_in；两个租户族角色新建）=====
INSERT INTO platform_role (id,name,code,description,scope,built_in,status) VALUES
 ('builtin_role_tenant_admin','租户管理员','tenant-admin','租户内管成员与看边界','TENANT',1,1),
 ('builtin_role_tenant_member','租户成员','tenant-member','租户内只读边界','TENANT',1,1)
ON DUPLICATE KEY UPDATE description=VALUES(description), scope=VALUES(scope), built_in=VALUES(built_in);

-- ===== admin 平台角色 → 全部 PLATFORM 权限 =====
INSERT INTO platform_role_permission (id,role_id,permission_id)
SELECT CONCAT('rp_admin_', p.id), 'builtin_role_admin', p.id
FROM platform_permission p
WHERE p.domain='API' AND p.code LIKE 'platform:%' AND p.deleted_at IS NULL
ON DUPLICATE KEY UPDATE permission_id=VALUES(permission_id);

-- ===== tenant-admin → 全部 tenant:* 权限 =====
INSERT INTO platform_role_permission (id,role_id,permission_id)
SELECT CONCAT('rp_tadmin_', p.id), 'builtin_role_tenant_admin', p.id
FROM platform_permission p
WHERE p.domain='API' AND p.code LIKE 'tenant:%' AND p.deleted_at IS NULL
ON DUPLICATE KEY UPDATE permission_id=VALUES(permission_id);

-- ===== tenant-member → tenant:overview:view =====
INSERT INTO platform_role_permission (id,role_id,permission_id)
SELECT CONCAT('rp_tmember_', p.id), 'builtin_role_tenant_member', p.id
FROM platform_permission p
WHERE p.domain='API' AND p.code='tenant:overview:view' AND p.deleted_at IS NULL
ON DUPLICATE KEY UPDATE permission_id=VALUES(permission_id);
```

> 说明：`/tenant/member/**` 同时有 `tenant:member:manage` 与 `platform:member:manage` 两行（不同 permission id、不同 code），实现 spec §5.4 的"同 URL ANY-of 双权限码"。交叉校验（Task 11）以 endpoint 是否被**任一**权限行覆盖为准。

- [ ] **Step 2: 补全存量管理 endpoint 的权限行（交叉校验的前置，Task 11 会强制）**

现有 controller（ClusterController / RbacTemplateController / NamespaceController / 既有 TenantController 全部方法 / Prometheus 发现等）的每个管理 endpoint 都必须有一行权限或落在豁免内，否则 Task 11 上线后 platform-api 拒绝启动。枚举方法：

Run: `grep -rn "@PostMapping\|@GetMapping\|@PutMapping\|@DeleteMapping" platform-api/src/main/java/com/coding/platformapi/controllers --include="*.java" -o | sort | uniq`

对照上表逐一补 `INSERT INTO platform_permission (id,domain,resource,action,code,description) VALUES (...)` 行进本 seed 文件，归族原则：

- 集群/模板/租户生命周期/命名空间分配 → `platform:cluster:manage`、`platform:role:read` 之外新起 `platform:template:manage`（RBAC 模板）、沿用 `platform:allocation:manage`；
- 资源元数据类（如 `/namespace/list`）若属租户可见 → 并入 `tenant:overview:view`（带 ANY-of 双行同 member 模式）。
- `/prometheus/**`、`/metrics` 类发现端点按 memory「Prom discovery 端点豁免租户边界」保持现状：并入 `tenant:overview:view` + `platform:*` 双 ANY-of 或直接入 ExemptPaths（executor 二选一并在 PR 描述注明）。

- [ ] **Step 3: 应用到 dev 库 + 同步 dump**（同 Task 1 Step 5）

Run: `mysql -u<user> -p<pwd> <db> < platform-data/src/main/resources/db/migration/V2026_09_24_2__rbac_seed.sql`

- [ ] **Step 4: 人工核对查询**

Run: `mysql ... -e "select code from platform_permission order by code; select r.code, count(*) from platform_role_permission rp join platform_role r on r.id=rp.role_id group by r.code;"`
Expected: PLATFORM/TENANT 权限点齐全；admin→若干 platform:*, tenant-admin→tenant:*, tenant-member→1。

- [ ] **Step 5: Commit**

```bash
git add platform-data/src/main/resources/db/migration/V2026_09_24_2__rbac_seed.sql
git commit -m "chore(rbac): seed 权限点目录与内置角色关联

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Task 5: TokenUserInfo 加 permissions + 闭包组装器（platform-common 可测）

把"角色→权限 code"闭包算出来放进签发 token 的 `data`。组装逻辑做成 platform-common 的纯函数，好测（platform-auth 无测试依赖）。

**Files:**
- Modify: `platform-data/.../models/system/TokenUserInfo.java`（加 `private List<String> permissions;`）
- Create: `platform-common/.../components/jwt/PermissionAuthorityNames.java`（前缀常量，converter 与 manager 共用，防漂移）

**Interfaces:**
- Produces:
  - `TokenUserInfo.getPermissions(): List<String>` / `setPermissions(List<String>)`
  - `PermissionAuthorityNames.PERM_PREFIX = "PERM:"`, `.platformAuthority(String code) -> "PLATFORM:"+code`, `.permAuthority(String code) -> "PERM:"+code`
- Consumes: Task 3 的 `selectPermissionCodesByRoleIds`。

- [ ] **Step 1: TokenUserInfo 加字段**

在 `private List<String> platformRoles;` 之后：

```java
    /** 当前上下文权限点 code 闭包（平台族角色 + 若有租户上下文再并租户族角色） */
    private java.util.List<String> permissions;
```

- [ ] **Step 2: PermissionAuthorityNames 常量类**

```java
package com.coding.common.components.jwt;

/** authority 前缀集中定义，避免 auth-server 写入、converter 展开、manager 校验三处字面量漂移。 */
public final class PermissionAuthorityNames {
    public static final String PLATFORM_PREFIX = "PLATFORM:";
    public static final String PERM_PREFIX = "PERM:";
    private PermissionAuthorityNames() {}
    public static String platform(String code) { return PLATFORM_PREFIX + code; }
    public static String perm(String code) { return PERM_PREFIX + code; }
}
```

（Task 9 的 converter 里 `PLATFORM_AUTHORITY_PREFIX` 常量保留不删，避免动 k8s-server 引用；新增 PERM 用本类。）

- [ ] **Step 3: 写闭包纯函数 + 单测**

Create `platform-common/src/main/java/com/coding/common/components/jwt/PermissionClosure.java`：

```java
package com.coding.common.components.jwt;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class PermissionClosure {
    private PermissionClosure() {}
    /** 合并平台族与租户族权限 code，去重、稳定顺序。 */
    public static List<String> merge(List<String> platformPermCodes, List<String> tenantPermCodes) {
        Set<String> out = new LinkedHashSet<>();
        if (platformPermCodes != null) out.addAll(platformPermCodes);
        if (tenantPermCodes != null) out.addAll(tenantPermCodes);
        return new ArrayList<>(out);
    }
}
```

- [ ] **Step 4: 编译 + Commit**

```bash
git add platform-data/src/main/java/com/coding/data/models/system/TokenUserInfo.java \
        platform-common/src/main/java/com/coding/common/components/jwt/PermissionAuthorityNames.java \
        platform-common/src/main/java/com/coding/common/components/jwt/PermissionClosure.java
git commit -m "feat(rbac): TokenUserInfo 增 permissions + 权限闭包工具与前缀常量

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Task 6: auth-server 签发时填充 permissions 与租户上下文（改造 customizer）

**Files:**
- Modify: `platform-auth/.../config/AuthorizationServerConfig.java`（`jwtTokenExchangeCustomizer` → 重写为 `jwtTokenCustomizer`，读 session-renewal 的 tenant_id，删 TOKEN_EXCHANGE 死分支，加 permissions 闭包）
- Modify: `platform-auth/.../grant/SessionRenewalAuthenticationConverter.java`（解析 `tenant_id`）
- Modify: `platform-auth/.../grant/SessionRenewalAuthenticationToken.java`（承载 tenantId）
- Modify: `platform-auth/.../grant/SessionRenewalAuthenticationProvider.java`（把 tenantId 放进 token context / grant）
- Create: `platform-auth/.../service/TokenExtrasService.java`（封装"给定 userId+可选 tenantId → platformRoles/permissions/tenantInfo"，可注入三 mapper）

**Interfaces:**
- Consumes: Task 3（`selectRoleIdsByUser`、`selectRoleIdsByUserAndTenant`、`selectPermissionCodesByRoleIds`、`selectTenantByUser`）、Task 5（`PermissionClosure.merge`）。
- Produces: 签发的 JWT `data` 含 `platformRoles`、`permissions`、`tenantInfo`（仅当请求带合法 tenant_id）。

- [ ] **Step 1: SessionRenewalAuthenticationToken 加 tenantId 字段（可空）**

字段与构造扩展（替换原构造器）：

```java
    /** 目标租户上下文 id；可空（空=base token 无租户） */
    @Nullable
    private final String tenantId;

    public SessionRenewalAuthenticationToken(String sessionId,
                                             @Nullable Authentication endUserPrincipal,
                                             Authentication clientPrincipal,
                                             @Nullable String tenantId) {
        super(SessionRenewalGrantType.INSTANCE, clientPrincipal, java.util.Collections.emptyMap());
        this.sessionId = sessionId;
        this.endUserPrincipal = endUserPrincipal;
        this.tenantId = (tenantId == null || tenantId.isBlank()) ? null : tenantId;
    }
    @Nullable public String getTenantId() { return this.tenantId; }
```

（`additionalParameters` 传 `tenant_id`，让 customizer 从 `context.getAuthorizationGrant().getAdditionalParameters()` 读到，与旧 exchange 分支同构。旧构造调用点见 Step 2 一并改。）

- [ ] **Step 2: Converter 解析 tenant_id 并传入**

`SessionRenewalAuthenticationConverter.convert` 末尾 return 前：

```java
        String tenantId = request.getParameter("tenant_id");
        return new SessionRenewalAuthenticationToken(sessionId, endUserPrincipal, clientPrincipal,
                (tenantId == null || tenantId.isBlank()) ? null : tenantId);
```

（把原来 `return new SessionRenewalAuthenticationToken(sessionId, endUserPrincipal, clientPrincipal);` 替换掉。）

- [ ] **Step 3: TokenExtrasService（集中查库，避免 customizer 里塞 mapper）**

```java
package com.coding.auth.service;

import com.coding.data.mapper.auth.PlatformRolePermissionMapper;
import com.coding.data.mapper.auth.PlatformTenantMapper;
import com.coding.data.mapper.auth.PlatformUserTenantRoleMapper;
import com.coding.data.mapper.auth.PlatformUserRoleMapper;
import com.coding.data.models.system.UserTenantInfo;
import com.coding.common.components.jwt.PermissionClosure;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

@Service
@RequiredArgsConstructor
public class TokenExtrasService {
    private final PlatformUserRoleMapper userRoleMapper;
    private final PlatformRolePermissionMapper rolePermissionMapper;
    private final PlatformUserTenantRoleMapper userTenantRoleMapper;
    private final PlatformTenantMapper tenantMapper;

    public List<String> platformRoleCodes(String userId) {
        return userRoleMapper.selectRoleCodesByUser(userId);
    }

    /** 租户上下文：校验成员资格后返回 UserTenantInfo，非法租户抛 null（调用方处理） */
    public UserTenantInfo resolveTenant(String username, String tenantId) {
        if (!StringUtils.hasText(tenantId)) return null;
        return tenantMapper.selectTenantByUser(username, tenantId); // 返回 null 表示不属于该租户
    }

    /** 权限闭包 = 平台族角色的权限 ∪（tenantId 非空时）该租户内角色的权限。
     *  注意 selectPermissionCodesByRoleIds 对空 IN() 非法，调用方须先判空。 */
    public List<String> permissions(String userId, String tenantId) {
        List<String> platformPerms = codesOf(userRoleMapper.selectRoleIdsByUser(userId));
        List<String> tenantPerms = StringUtils.hasText(tenantId)
                ? codesOf(userTenantRoleMapper.selectRoleIdsByUserAndTenant(userId, tenantId))
                : List.of();
        return PermissionClosure.merge(platformPerms, tenantPerms);
    }

    private List<String> codesOf(List<String> roleIds) {
        return roleIds.isEmpty() ? List.of() : rolePermissionMapper.selectPermissionCodesByRoleIds(roleIds);
    }
}
```

- [ ] **Step 4: provider 保持现状（tenantId 经 grant 对象字段透传）**

provider **不查库、不改代码**。它现有代码已把本 grant 对象经 `.authorizationGrant(renewalAuthentication)` 传入 `tokenContextBuilder`（见现文件 `SessionRenewalAuthenticationProvider`），customizer 从 `JwtEncodingContext.getAuthorizationGrant()` 拿到的正是这个对象。Step 1 的 `tenant_id` 存在 grant 的**字段**上，customizer 用 `instanceof SessionRenewalAuthenticationToken` 直接 `getTenantId()`，不依赖 SAS 对 additionalParameters 的透传行为。

- [ ] **Step 5: 重写 customizer**

把 `AuthorizationServerConfig` 里 `jwtTokenExchangeCustomizer(...)` 整体替换为：

```java
    @Bean
    public OAuth2TokenCustomizer<JwtEncodingContext> jwtTokenCustomizer(
            PlatformUserMapper userMapper, TokenExtrasService extras) {
        return context -> {
            User securityUser = (User) context.getPrincipal().getPrincipal();
            String username = securityUser.getUsername();
            PlatformUser platformUser = userMapper.selectByUsername(username);

            TokenUserInfo info = TokenUserInfo.builder()
                    .username(platformUser.getUsername())
                    .email(platformUser.getEmail())
                    .displayName(platformUser.getDisplayName())
                    .status(platformUser.getStatus())
                    .build();

            info.setPlatformRoles(extras.platformRoleCodes(platformUser.getId()));

            String tenantId = extractTenantId(context);
            if (tenantId != null) {
                UserTenantInfo tenantInfo = extras.resolveTenant(username, tenantId);
                if (tenantInfo == null) {
                    throw new org.springframework.security.oauth2.core.OAuth2AuthenticationException(
                        new org.springframework.security.oauth2.core.OAuth2Error(
                            org.springframework.security.oauth2.core.OAuth2ErrorCodes.INVALID_GRANT,
                            "用户不属于该租户", "https://datatracker.ietf.org/doc/html/rfc6749#section-5.2"));
                }
                info.setTenantInfo(tenantInfo);
            }
            // 闭包：tenantId 为空时内部自动只算平台族
            info.setPermissions(extras.permissions(platformUser.getId(), tenantId));

            context.getClaims().claim(jwtProperties.getDataKey(), info);
            ClientSettings clientSettings = context.getRegisteredClient().getClientSettings();
            setCustomClientSettings(context, clientSettings);
        };
    }

    private static String extractTenantId(JwtEncodingContext context) {
        Object grant = context.getAuthorizationGrant();
        if (grant instanceof com.coding.auth.grant.SessionRenewalAuthenticationToken t) {
            return t.getTenantId();
        }
        return null;
    }
```

- 删除文件顶部不再使用的 `import ...OAuth2TokenExchangeAuthenticationToken;` 与 `import ...AuthorizationGrantType;`（若无其它引用）。
- 保留方法名 `setCustomClientSettings`（未动）。
- Bean 名由 `jwtTokenExchangeCustomizer` 改为 `jwtTokenCustomizer`（其它处未按名引用 `@Bean`，安全）。
- **DB 配套（exchange 退役收尾，spec §8）**：清掉 `gateway-code-client` 的 token-exchange grant 声明：

```sql
UPDATE oauth2_registered_client
SET grant_types = REPLACE(grant_types, 'urn:ietf:params:oauth:grant-type:token-exchange,', '')
WHERE client_id = 'gateway-code-client';
```

（执行前 SELECT 确认列值形状；改到 dev 库并同步 `k8s_cloud_platform.sql` dump。）

- [ ] **Step 6: 编译 + 打包**

Run: `mvn -q -pl platform-auth -am compile`
Expected: SUCCESS。若 `extractTenantId` 类型不符编译错 → 记为 Task 12 spike 待验，先临时 `return null;` 保证编译，Task 12 用真实请求定形。

- [ ] **Step 7: Commit**

```bash
git add platform-auth/src/main/java/com/coding/auth/config/AuthorizationServerConfig.java \
        platform-auth/src/main/java/com/coding/auth/grant/SessionRenewalAuthenticationConverter.java \
        platform-auth/src/main/java/com/coding/auth/grant/SessionRenewalAuthenticationToken.java \
        platform-auth/src/main/java/com/coding/auth/grant/SessionRenewalAuthenticationProvider.java \
        platform-auth/src/main/java/com/coding/auth/service/TokenExtrasService.java
git commit -m "feat(rbac): 签发时填 permissions 闭包 + session-renewal 承载 tenant_id，删 token-exchange 死分支

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Task 7: 删除 k8s-server 的 TenantValidate 死代码（只做减法）

**Files:**
- Delete: `k8s-server/.../annotations/TenantValidate.java`
- Delete: `k8s-server/.../components/TenantValidateAspect.java`

- [ ] **Step 1: 确认无引用**

Run: `grep -rn "@TenantValidate\|TenantValidateAspect\|annotations.TenantValidate" k8s-server/src --include="*.java" | grep -v "TenantValidateAspect.java\|annotations/TenantValidate.java"`
Expected: 空（仅注释里提过一次，无代码引用）。若命中实际方法 → **停止本任务**，改该方法的边界校验到 `ResourceAccessResolver` 后重来。

- [ ] **Step 2: 删两文件 + 编译**

```bash
git rm k8s-server/src/main/java/com/coding/k8sserver/annotations/TenantValidate.java \
       k8s-server/src/main/java/com/coding/k8sserver/components/TenantValidateAspect.java
```
Run: `mvn -q -pl k8s-server -am compile` → SUCCESS。

- [ ] **Step 3: Commit**

```bash
git commit -m "chore(rbac): 删除未接线的 TenantValidate 注解与切面（职责已由 ResourceAccessResolver 承担）

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Task 8: PlatformJwtAuthenticationConverter 展开 PERM authorities

**Files:**
- Modify: `platform-common/.../components/jwt/PlatformJwtAuthenticationConverter.java`

**Interfaces:**
- Produces: JWT `data.permissions[]` → `PERM:<code>` authorities（叠加于既有 `PLATFORM:`）。

- [ ] **Step 1: 测试（本类无现成测试，新建，platform-common 有 test 依赖则放其下）**

Create `platform-common/src/test/java/com/coding/common/components/jwt/PlatformJwtAuthenticationConverterTest.java`:

```java
package com.coding.common.components.jwt;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

class PlatformJwtAuthenticationConverterTest {
    private Jwt jwt(Map<String,Object> data) {
        Map<String,Object> claims = new HashMap<>();
        claims.put("data", data);
        return new Jwt("raw", Instant.now(), Instant.now().plusSeconds(60), Map.of("alg","none"), claims);
    }
    @Test void expands_permissions_to_PERM_authorities() {
        var data = new LinkedHashMap<String,Object>();
        data.put("platformRoles", List.of("admin"));
        data.put("permissions", List.of("platform:tenant:read","tenant:member:manage"));
        JwtAuthenticationToken tok = (JwtAuthenticationToken)
            new PlatformJwtAuthenticationConverter("data").convert(jwt(data));
        Set<String> a = tok.getAuthorities().stream().map(GrantedAuthority::getAuthority)
            .collect(java.util.stream.Collectors.toSet());
        assertThat(a).contains("PLATFORM:admin","PERM:platform:tenant:read","PERM:tenant:member:manage");
    }
}
```

Run: `mvn -q -pl platform-common -am test -Dtest=PlatformJwtAuthenticationConverterTest`
Expected: FAIL（`PERM:` 断言不过，当前 converter 不读 permissions）。

> 若 platform-common 无 test 依赖：先在 `platform-common/pom.xml` 的 `<dependencies>` 加 `spring-boot-starter-test`(scope test)，与 platform-api 一致。

- [ ] **Step 2: 改 converter 读 permissions**

在 `convert` 内，`platformRoles` 处理块之后、`return` 之前加：

```java
            Object perms = map.get("permissions");
            if (perms instanceof List<?> plist) {
                for (Object p : plist) {
                    if (p != null && !p.toString().isBlank()) {
                        authorities.add(new SimpleGrantedAuthority(PermissionAuthorityNames.perm(p.toString())));
                    }
                }
            }
```

- [ ] **Step 3: 运行测试**

Run: `mvn -q -pl platform-common -am test -Dtest=PlatformJwtAuthenticationConverterTest`
Expected: PASS。

- [ ] **Step 4: Commit**

```bash
git add platform-common/src/main/java/com/coding/common/components/jwt/PlatformJwtAuthenticationConverter.java \
        platform-common/src/test/java/com/coding/common/components/jwt/PlatformJwtAuthenticationConverterTest.java
git commit -m "feat(rbac): JWT data.permissions 展开为 PERM authorities

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Task 9: PermissionRegistry（URL→权限 code，启动加载）

platform-api 的表驱动注册表。纯逻辑，重点单测。

**Files:**
- Create: `platform-api/.../security/PermissionRegistry.java`
- Create: `platform-api/.../security/ExemptPaths.java`
- Test: `platform-api/src/test/java/.../security/PermissionRegistryTest.java`

**Interfaces:**
- Produces:
  - `PermissionRegistry(Collection<PermissionRule> rules)`，`Optional<Set<String>> requiredCodes(String method, String path)`（命中任一规则→该 code 集合，ANY-of；无命中 empty）
  - `record PermissionRule(String method, String pattern, String code)`
  - `ExemptPaths.isExempt(String path): boolean`

- [ ] **Step 1: PermissionRule + PermissionRegistry**

```java
package com.coding.platformapi.security;
import org.springframework.util.AntPathMatcher;
import java.util.*;

public final class PermissionRegistry {
    public record Rule(String method, String pattern, String code) {}
    private final List<Rule> rules;
    private final AntPathMatcher matcher = new AntPathMatcher();
    public PermissionRegistry(List<Rule> rules) { this.rules = List.copyOf(rules); }
    /** 返回匹配该 (method,path) 的权限 code 集合（多行 ANY-of）；无匹配返回 empty。method 比较忽略大小写。 */
    public Optional<Set<String>> requiredCodes(String method, String path) {
        Set<String> codes = new LinkedHashSet<>();
        for (Rule r : rules) {
            if (r.method().equalsIgnoreCase(method) && matcher.match(r.pattern(), path)) codes.add(r.code());
        }
        return codes.isEmpty() ? Optional.empty() : Optional.of(codes);
    }
}
```

- [ ] **Step 2: ExemptPaths**

```java
package com.coding.platformapi.security;
import org.springframework.util.AntPathMatcher;

public final class ExemptPaths {
    private static final String[] PREFIXES = {
        "/resource/**", "/user/me", "/user/my-tenants", "/callback", "/error",
        "/actuator/**", "/doc.html", "/swagger-ui/**", "/v3/api-docs/**", "/favicon.ico"
    };
    private static final AntPathMatcher M = new AntPathMatcher();
    private ExemptPaths() {}
    public static boolean isExempt(String path) {
        for (String p : PREFIXES) if (M.match(p, path)) return true;
        return false;
    }
}
```

- [ ] **Step 3: 单测**

```java
package com.coding.platformapi.security;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Set;
import static org.assertj.core.api.Assertions.assertThat;

class PermissionRegistryTest {
    private PermissionRegistry reg() {
        return new PermissionRegistry(List.of(
            new PermissionRegistry.Rule("POST","/tenant/member/add","tenant:member:manage"),
            new PermissionRegistry.Rule("POST","/tenant/member/add","platform:member:manage"),
            new PermissionRegistry.Rule("POST","/tenant/create","platform:tenant:provision")
        ));
    }
    @Test void any_of_multiple_codes_for_same_url() {
        assertThat(reg().requiredCodes("POST","/tenant/member/add"))
            .contains(Set.of("tenant:member:manage","platform:member:manage"));
    }
    @Test void no_rule_returns_empty() {
        assertThat(reg().requiredCodes("POST","/tenant/member/add")).isPresent();
        assertThat(reg().requiredCodes("GET","/anything")).isEmpty();
    }
    @Test void method_is_case_insensitive() {
        assertThat(reg().requiredCodes("post","/tenant/create"))
            .contains(Set.of("platform:tenant:provision"));
    }
}
```

Run: `mvn -q -pl platform-api -am test -Dtest=PermissionRegistryTest` → PASS（本任务代码全为新建，先写实现再跑；若严格 TDD 可先只写测试看 fail）。

- [ ] **Step 4: Registry 加载 bean（从 DB 读 API 域权限点）**

Create `platform-api/.../security/PermissionRegistryFactory.java`:

```java
package com.coding.platformapi.security;
import com.coding.data.mapper.auth.PlatformPermissionMapper;
import com.coding.data.models.auth.PlatformPermission;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.util.List;

@Configuration
@RequiredArgsConstructor
public class PermissionRegistryFactory {
    private final PlatformPermissionMapper permissionMapper;
    @Bean
    public PermissionRegistry permissionRegistry() {
        List<PermissionRegistry.Rule> rules = permissionMapper.selectAllActive().stream()
            .filter(p -> "API".equals(p.getDomain()))
            .map(p -> new PermissionRegistry.Rule(p.getAction(), p.getResource(), p.getCode()))
            .toList();
        return new PermissionRegistry(rules);
    }
}
```

- [ ] **Step 5: Commit**

```bash
git add platform-api/src/main/java/com/coding/platformapi/security/PermissionRegistry.java \
        platform-api/src/main/java/com/coding/platformapi/security/ExemptPaths.java \
        platform-api/src/main/java/com/coding/platformapi/security/PermissionRegistryFactory.java \
        platform-api/src/test/java/com/coding/platformapi/security/PermissionRegistryTest.java
git commit -m "feat(rbac): PermissionRegistry(URL→code ANY-of) + 豁免路径

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Task 10: PermissionAuthorizationManager + 接入 ResourceServerConfig

**Files:**
- Create: `platform-api/.../security/PermissionAuthorizationManager.java`
- Modify: `platform-api/.../configs/ResourceServerConfig.java:63-66`
- Test: `platform-api/src/test/java/.../security/PermissionAuthorizationManagerTest.java`

**Interfaces:**
- Produces: `class PermissionAuthorizationManager implements AuthorizationManager<RequestAuthorizationContext>`，规则：命中权限行→用户 authorities 需含任一 `PERM:code`；豁免→authenticated 即过；其余→拒绝。
- Consumes: Task 8 authorities（`PERM:`）、Task 9 registry/exempt。

- [ ] **Step 1: 单测（mock registry + Authentication）**

```java
package com.coding.platformapi.security;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PermissionAuthorizationManagerTest {
    private RequestAuthorizationContext ctx(String m, String p) {
        MockHttpServletRequest r = new MockHttpServletRequest(m, p);
        return new RequestAuthorizationContext(r);
    }
    private UsernamePasswordAuthenticationToken auth(String... perms) {
        var list = java.util.Arrays.stream(perms).map(SimpleGrantedAuthority::new).<org.springframework.security.core.GrantedAuthority>map(x->x).toList();
        var a = new UsernamePasswordAuthenticationToken("u","p",list);
        a.setAuthenticated(true); return a;
    }
    @Test void granted_when_has_one_required_perm() {
        PermissionRegistry reg = mock(PermissionRegistry.class);
        when(reg.requiredCodes("POST","/tenant/create")).thenReturn(Optional.of(Set.of("platform:tenant:provision")));
        var mgr = new PermissionAuthorizationManager(reg);
        AuthorizationDecision d = mgr.check(()->auth("PERM:platform:tenant:provision"), ctx("POST","/tenant/create"));
        assertThat(d.isGranted()).isTrue();
    }
    @Test void denied_when_no_required_perm() {
        PermissionRegistry reg = mock(PermissionRegistry.class);
        when(reg.requiredCodes("POST","/tenant/create")).thenReturn(Optional.of(Set.of("platform:tenant:provision")));
        var mgr = new PermissionAuthorizationManager(reg);
        AuthorizationDecision d = mgr.check(()->auth("PERM:tenant:overview:view"), ctx("POST","/tenant/create"));
        assertThat(d.isGranted()).isFalse();
    }
}
```

Run: `mvn -q -pl platform-api -am test -Dtest=PermissionAuthorizationManagerTest` → FAIL（类不存在）。

- [ ] **Step 2: 实现 manager**

```java
package com.coding.platformapi.security;
import com.coding.common.components.jwt.PermissionAuthorityNames;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.stereotype.Component;
import java.util.Set;
import java.util.function.Supplier;

@Component
public class PermissionAuthorizationManager implements AuthorizationManager<RequestAuthorizationContext> {
    private final PermissionRegistry registry;
    public PermissionAuthorizationManager(PermissionRegistry registry) { this.registry = registry; }

    @Override
    public AuthorizationDecision check(Supplier<Authentication> authSupplier, RequestAuthorizationContext ctx) {
        Authentication auth = authSupplier.get();
        if (auth == null || !auth.isAuthenticated()) return new AuthorizationDecision(false);
        String method = ctx.getRequest().getMethod();
        String path = pathOf(ctx);
        if (ExemptPaths.isExempt(path)) return new AuthorizationDecision(true);
        Set<String> required = registry.requiredCodes(method, path).orElse(null);
        if (required == null) return new AuthorizationDecision(false); // 默认拒绝（交叉校验本不应到这）
        for (String code : required) {
            String authority = PermissionAuthorityNames.perm(code);
            boolean ok = auth.getAuthorities().stream().anyMatch(g -> g.getAuthority().equals(authority));
            if (ok) return new AuthorizationDecision(true);
        }
        return new AuthorizationDecision(false);
    }

    private String pathOf(RequestAuthorizationContext ctx) {
        String uri = ctx.getRequest().getRequestURI();
        String cp = ctx.getRequest().getContextPath();
        if (cp != null && !cp.isEmpty() && uri.startsWith(cp)) uri = uri.substring(cp.length());
        return uri;
    }
}
```

- [ ] **Step 3: 接入 filter chain**

`ResourceServerConfig` 注入 `PermissionAuthorizationManager`（改 `securityFilterChain` 形参加 `PermissionAuthorizationManager permMgr`），把 Step `authorizeHttpRequests` 换成：

```java
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(properties.getIgnoreUrls()).permitAll()
                        //表驱动：命中权限行→PERM 校验；豁免→authenticated；其余拒绝（见 spec §5.1）
                        .anyRequest().access(permMgr))
```

（删除原 `.anyRequest().hasAuthority(...PLATFORM_AUTHORITY_PREFIX + "admin")` 整行及其注释。）

- [ ] **Step 4: 运行测试 + 编译**

Run: `mvn -q -pl platform-api -am test -Dtest=PermissionAuthorizationManagerTest` → PASS。
Run: `mvn -q -pl platform-api -am compile` → SUCCESS。

> SS7 API 风险点：若 `AuthorizationManager.check` 在 Spring Security 7 的签名与此不同（返回类型/泛型变动），以 IDE 反编译 `spring-security-core-7.x` 的接口为准对齐 `check` 覆写；测试里对 `AuthorizationDecision` 的断言同步调整。该风险仅影响本类与本测试，不影响 manager 的规则语义（豁免→过；命中行→PERM ANY-of；其余→拒）。

- [ ] **Step 5: Commit**

```bash
git add platform-api/src/main/java/com/coding/platformapi/security/PermissionAuthorizationManager.java \
        platform-api/src/main/java/com/coding/platformapi/configs/ResourceServerConfig.java \
        platform-api/src/test/java/com/coding/platformapi/security/PermissionAuthorizationManagerTest.java
git commit -m "feat(rbac): 表驱动 PermissionAuthorizationManager 接管 platform-api 授权

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Task 11: 启动期交叉校验（endpoint × 权限表）

**Files:**
- Create: `platform-api/.../security/PermissionCrossCheck.java`
- Test: `platform-api/src/test/java/.../security/PermissionCrossCheckTest.java`

**Interfaces:**
- Produces: `PermissionCrossCheck.verify(Set<EndpointKey>, PermissionRegistry, ...)` 静态核账方法（可测），`SmartInitializingSingleton` bean 在启动时对真实 handler mapping 执行，裸 endpoint → 抛异常（启动失败）。
- `record EndpointKey(String method, String pattern)`（来自 RequestMappingHandlerMapping）。

- [ ] **Step 1: 写纯核账方法 + 失败测试**

```java
package com.coding.platformapi.security;
import java.util.List;
public final class PermissionCrossCheck {
    public record Endpoint(String method, String pattern) {}
    private PermissionCrossCheck() {}
    /** 返回未被任何权限行、且非豁免的 endpoint（启动应失败）。*/
    public static List<Endpoint> uncoveredEndpoints(List<Endpoint> endpoints, PermissionRegistry reg) {
        return endpoints.stream()
            .filter(e -> !ExemptPaths.isExempt(e.pattern()))
            .filter(e -> reg.requiredCodes(e.method(), e.pattern()).isEmpty())
            .toList();
    }
}
```

Test:

```java
package com.coding.platformapi.security;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PermissionCrossCheckTest {
    @Test void flagged_uncovered_bare_endpoint() {
        PermissionRegistry reg = mock(PermissionRegistry.class);
        when(reg.requiredCodes(anyString(), anyString())).thenReturn(Optional.empty());
        when(reg.requiredCodes("POST","/tenant/create")).thenReturn(Optional.of(Set.of("platform:tenant:provision")));
        var gaps = PermissionCrossCheck.uncoveredEndpoints(
            List.of(new PermissionCrossCheck.Endpoint("POST","/tenant/create"),
                    new PermissionCrossCheck.Endpoint("POST","/user/create")), reg);
        assertThat(gaps).extracting(PermissionCrossCheck.Endpoint::pattern).containsExactly("/user/create");
    }
    @Test void exempt_endpoints_not_flagged_even_without_rule() {
        PermissionRegistry reg = mock(PermissionRegistry.class);
        when(reg.requiredCodes(anyString(), anyString())).thenReturn(Optional.empty());
        var gaps = PermissionCrossCheck.uncoveredEndpoints(
            List.of(new PermissionCrossCheck.Endpoint("GET","/resource/pods/foo")), reg);
        assertThat(gaps).isEmpty();
    }
}
```

Run: `mvn -q -pl platform-api -am test -Dtest=PermissionCrossCheckTest` → PASS。

- [ ] **Step 2: bean 挂到真实 handler mapping**

```java
package com.coding.platformapi.security;
import com.coding.platformapi.security.PermissionCrossCheck.Endpoint;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import java.util.*;

@Slf4j @Component @RequiredArgsConstructor
public class PermissionCrossCheckRunner implements SmartInitializingSingleton {
    private final RequestMappingHandlerMapping mapping;
    private final PermissionRegistry registry;
    @Override public void afterSingletonsInstantiated() {
        List<Endpoint> eps = new ArrayList<>();
        for (RequestMappingInfo info : mapping.getHandlerMethods().keySet()) {
            var patterns = info.getPatternValues();
            var methods = info.getMethodsCondition().getMethods();
            if (patterns.isEmpty()) continue;
            for (String p : patterns) {
                if (methods.isEmpty()) eps.add(new Endpoint("*", p));
                else for (var m : methods) eps.add(new Endpoint(m.name(), p));
            }
        }
        var gaps = PermissionCrossCheck.uncoveredEndpoints(eps, registry);
        if (!gaps.isEmpty()) {
            String msg = "[RBAC] 以下 endpoint 既无权限行也不在豁免清单，启动失败: " + gaps;
            log.error(msg);
            throw new IllegalStateException(msg);
        }
        log.info("[RBAC] 交叉校验通过：{} 个 endpoint 全部有权限声明或豁免", eps.size());
    }
}
```

> `{var}` 归一化：管理域禁路径变量，故管理行精确匹配即可；若某 endpoint pattern 含 `{...}`（资源域）但落在豁免 `/resource/**`，不受影响。若出现非豁免又含 `{}` 的 endpoint，会被判裸点 → 启动失败 → 修数据（加权限行或加豁免）。这是刻意的"逼你显式表态"。

- [ ] **Step 3: 编译 + 提交**

Run: `mvn -q -pl platform-api -am compile` → SUCCESS。

```bash
git add platform-api/src/main/java/com/coding/platformapi/security/PermissionCrossCheck.java \
        platform-api/src/main/java/com/coding/platformapi/security/PermissionCrossCheckRunner.java \
        platform-api/src/test/java/com/coding/platformapi/security/PermissionCrossCheckTest.java
git commit -m "feat(rbac): 启动期 endpoint×权限表交叉校验（裸 endpoint 阻断启动）

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Task 12: auth-server 接线 spike 验证（tenant_id 透传）+ session-renewal 人工回归

platform-auth 无测试依赖，此任务用集成手段证实 Task 6 的 `additionalParameters` 透传假设，并回归切租户/续期。

**Files:**
- 无（验证 + 可能回改 Task 6 `extractTenantId`）

- [ ] **Step 1: 起 auth + api + redis + db，登录取 base token**

按现有方式 PKCE 登录拿 `access_token`（platform-web-client）。解码 JWT `data`：应见 `platformRoles`（admin 用户）、`permissions`（一串 `platform:*`）、`tenantInfo` 为 null。

- [ ] **Step 2: session-renewal 带 tenant_id**

Run:
```bash
curl -s -X POST "$ISSUER/oauth2/token" \
  -H "Cookie: PLATFORM_SESSION=<会话cookie>" \
  -d "grant_type=urn:coding:grant-type:session-renewal&client_id=platform-web-client&tenant_id=<某租户id>"
```
Expected: 返回新 `access_token`；解码 `data.tenantInfo.tenantId == 传入 id`，`permissions` 含该租户角色展开的 `tenant:*`（若该用户在此租户有角色）。

- [ ] **Step 3: 序列化检查（本 spike 的主要风险）**

确认 provider 里 `authorizationService.save(authorization)` 正常：`SessionRenewalAuthenticationToken` 多了一个 String 字段不影响 JDK 序列化；且 authorization 持久化的是 grant 对象时，JdbcOAuth2AuthorizationService 的 Jackson mapper 能处理本 token（既有实现已在跑，新字段是普通 String，预期无碍；若 save 报序列化错 → 给该类型注册 mixin 或从持久化中排除 grant，属小修）。

- [ ] **Step 4: 非法租户拒绝**

Run: 同上但 `tenant_id=<不属于该用户的租户>` → 期望 `{"error":"invalid_grant",...}`（Step 5 customizer 抛的）。Expected: 拒绝，不签发。

- [ ] **Step 5: 不带 tenant_id 回落 base**

Run: session-renewal 无 `tenant_id` → `tenantInfo` null、`permissions` 仅平台族。Expected: PASS。

- [ ] **Step 6: 若 Task 6 有回改，提交；否则记录验证通过**

```bash
# 仅当 Step 3 触发改动：
git add -A platform-auth && git commit -m "fix(rbac): session-renewal tenant_id 透传方式校正

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Task 13: TenantContextResolver（自管/代管一致性，服务层复用）

**Files:**
- Create: `platform-api/.../security/TenantContextResolver.java`
- Test: `platform-api/src/test/java/.../security/TenantContextResolverTest.java`

**Interfaces:**
- Produces: `String requireContext(String tokenTenantId, String requestTenantId)` — 返回生效 tenantId：token 含租户则强制 == 请求（否则抛 `TENANT_MISMATCH`）；token 无租户（代管）则要求 requestTenantId 非空并返回它。

- [ ] **Step 1: 失败测试**

```java
package com.coding.platformapi.security;
import com.coding.common.exception.CloudPlatformException;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class TenantContextResolverTest {
    private final TenantContextResolver r = new TenantContextResolver();
    @Test void self_managed_must_match_token_tenant() {
        assertThat(r.requireContext("T1","T1")).isEqualTo("T1");
        assertThatThrownBy(() -> r.requireContext("T1","T2"))
            .isInstanceOf(CloudPlatformException.class);
    }
    @Test void delegate_requires_explicit_tenant() {
        assertThat(r.requireContext(null,"T9")).isEqualTo("T9");
        assertThatThrownBy(() -> r.requireContext(null,null)).isInstanceOf(CloudPlatformException.class);
    }
}
```

Run: `mvn -q -pl platform-api -am test -Dtest=TenantContextResolverTest` → FAIL。

- [ ] **Step 2: 实现**

```java
package com.coding.platformapi.security;
import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class TenantContextResolver {
    /** 生效租户 id：自管校验一致，代管要求显式传。 */
    public String requireContext(String tokenTenantId, String requestTenantId) {
        if (StringUtils.hasText(tokenTenantId)) {
            if (!tokenTenantId.equals(requestTenantId))
                throw new CloudPlatformException(EnumResponseType.TENANT_MISMATCH);
            return tokenTenantId;
        }
        if (!StringUtils.hasText(requestTenantId))
            throw new CloudPlatformException(EnumResponseType.TOKEN_TENANT_MISSING);
        return requestTenantId; // 代管：platform:member:manage 已放行
    }
}
```

- [ ] **Step 3: 通过 + 提交**

Run: `mvn -q -pl platform-api -am test -Dtest=TenantContextResolverTest` → PASS。

```bash
git add platform-api/src/main/java/com/coding/platformapi/security/TenantContextResolver.java \
        platform-api/src/test/java/com/coding/platformapi/security/TenantContextResolverTest.java
git commit -m "feat(rbac): 自管/代管租户上下文一致性校验器

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Task 14: UserService + 平台角色授予/回收 + controller

**Files:**
- Create: `platform-api/.../models/UserCreateRequest.java`,`UserKeyRequest.java`,`UserRoleGrantRequest.java`
- Create: `platform-api/.../services/UserService.java`
- Create: `platform-api/.../controllers/UserController.java`
- Test: `platform-api/src/test/java/.../services/UserServiceTest.java`
- 需要 mapper 增删查（PlatformUserMapper 已有 CRUD；PlatformUserRoleMapper 需 insert/delete/select — 补方法见 Step 0）

**Interfaces:**
- Consumes: `PlatformUserMapper`, `PlatformUserRoleMapper`（Task 3 加了 `selectRoleIdsByUser`），`PasswordEncoder`（auth 侧有 bean；platform-api 若无则在 `UserCreateRequest` 存前由 service 加密——**确认 platform-api 是否已有 PasswordEncoder bean**，无则本任务加一个 `BCryptPasswordEncoder` bean）。
- Produces: `/user/create|list|update|delete|platformRole/grant|platformRole/revoke`，全部 POST+body。

- [ ] **Step 0: PlatformUserRoleMapper 补 insert/delete/listByUser**

接口加：`int insert(PlatformUserRole r)`, `int deleteByUserAndRole(@Param("userId") String,String)`, `List<String> selectRoleCodesByUser` (已有)。XML 加 insert/delete。`PlatformUserRole` 若无 model，新建（字段 id/userId/roleId/createdAt，仿 PlatformRolePermission）。

- [ ] **Step 1: 失败测试（create 唯一用户名 + grant 幂等）**

```java
package com.coding.platformapi.services;
import com.coding.common.exception.CloudPlatformException;
import com.coding.data.mapper.auth.PlatformUserMapper;
import com.coding.data.mapper.auth.PlatformUserRoleMapper;
import com.coding.data.models.auth.PlatformUser;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class UserServiceTest {
    PlatformUserMapper userMapper = mock(PlatformUserMapper.class);
    PlatformUserRoleMapper urMapper = mock(PlatformUserRoleMapper.class);
    PasswordEncoder encoder = mock(PasswordEncoder.class);
    UserService svc = new UserService(userMapper, urMapper, encoder);

    @Test void duplicate_username_rejected() {
        when(userMapper.selectByUsername("bob")).thenReturn(new PlatformUser());
        assertThatThrownBy(() -> svc.create(req("bob")))
            .isInstanceOf(CloudPlatformException.class);
    }
    @Test void password_is_encoded() {
        when(userMapper.selectByUsername(any())).thenReturn(null);
        when(encoder.encode("secret")).thenReturn("HASH");
        var u = svc.create(req("bob"));
        assertThat(u.getPassword()).isEqualTo("HASH");
        verify(userMapper).insert(any());
    }
    private com.coding.platformapi.models.UserCreateRequest req(String n){
        var r = new com.coding.platformapi.models.UserCreateRequest();
        r.setUsername(n); r.setPassword("secret"); return r;
    }
}
```

Run → FAIL（类不存在）。

- [ ] **Step 2: 请求 model**

```java
package com.coding.platformapi.models;
import lombok.Data;
@Data
public class UserCreateRequest {
    private String username;
    private String password;
    private String displayName;
    private String email;
    private Integer status;      // 1/0，默认 1
}
```
`UserKeyRequest`: `private String id;`。
`UserRoleGrantRequest`: `private String userId; private String roleId;`。

- [ ] **Step 3: service**

```java
package com.coding.platformapi.services;
import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.utils.ULIDGenerator;
import com.coding.data.mapper.auth.PlatformUserMapper;
import com.coding.data.mapper.auth.PlatformUserRoleMapper;
import com.coding.data.models.auth.PlatformUser;
import com.coding.data.models.auth.PlatformUserRole;
import com.coding.platformapi.models.UserCreateRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import java.util.Date;
import java.util.List;

@Service
@RequiredArgsConstructor
public class UserService {
    private final PlatformUserMapper userMapper;
    private final PlatformUserRoleMapper userRoleMapper;
    private final PasswordEncoder passwordEncoder;

    public PlatformUser create(UserCreateRequest req) {
        if (!StringUtils.hasText(req.getUsername()) || !StringUtils.hasText(req.getPassword()))
            throw new CloudPlatformException(EnumResponseType.BEAN_VALIDATION_EXCEPTION, "用户名与密码必填");
        if (userMapper.selectByUsername(req.getUsername()) != null)
            throw new CloudPlatformException(EnumResponseType.BEAN_VALIDATION_EXCEPTION, "用户名已存在");
        PlatformUser u = new PlatformUser();
        u.setId(ULIDGenerator.generateULID());
        u.setUsername(req.getUsername());
        u.setPassword(passwordEncoder.encode(req.getPassword()));
        u.setDisplayName(req.getDisplayName());
        u.setEmail(req.getEmail());
        u.setStatus(req.getStatus() != null ? req.getStatus().byteValue() : 1);
        u.setType("TENANT_USER");
        u.setSource("LOCAL");
        Date now = new Date(); u.setCreatedAt(now); u.setUpdatedAt(now);
        userMapper.insert(u);
        return u;
    }
    public List<PlatformUser> list() { return userMapper.listAllActive(); }
    public PlatformUser get(String id) { return userMapper.selectByPrimaryKey(id); }

    public void grantPlatformRole(String userId, String roleId) {
        PlatformUserRole r = new PlatformUserRole();
        r.setId(ULIDGenerator.generateULID()); r.setUserId(userId); r.setRoleId(roleId);
        r.setCreatedAt(new Date());
        userRoleMapper.insert(r); // 唯一索引 uk_user_role 兜幂等；捕获重复→视为已存在
    }
    public void revokePlatformRole(String userId, String roleId) {
        userRoleMapper.deleteByUserAndRole(userId, roleId);
    }
}
```

> `userMapper.listAllActive()` 若无 → Task 3/此处补 `selectByUsername` 已有，加 `listAllActive`（XML：`select ... where deleted_at is null`）。若已存在别的列表方法名，复用它。

- [ ] **Step 4: PasswordEncoder bean（若 platform-api 无）**

`ResourceServerConfig` 或新建 `SecurityBeans`：

```java
    @Bean
    public PasswordEncoder passwordEncoder() { return new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(); }
```

- [ ] **Step 5: controller（每方法带 @Operation；无 @PreAuthorize，鉴权在 manager）**

```java
package com.coding.platformapi.controllers;
import com.coding.common.models.system.ResponseData;
import com.coding.data.models.auth.PlatformUser;
import com.coding.platformapi.models.*;
import com.coding.platformapi.services.UserService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@Tag(name="用户管理") @RestController @RequestMapping("/user") @RequiredArgsConstructor
public class UserController {
    private final UserService userService;
    @PostMapping("/create") public ResponseData<PlatformUser> create(@RequestBody UserCreateRequest r){ return new ResponseData<>(userService.create(r)); }
    @PostMapping("/list") public ResponseData<List<PlatformUser>> list(){ return new ResponseData<>(userService.list()); }
    @PostMapping("/get") public ResponseData<PlatformUser> get(@RequestBody UserKeyRequest r){ return new ResponseData<>(userService.get(r.getId())); }
    @PostMapping("/platformRole/grant") public ResponseData<Void> grant(@RequestBody UserRoleGrantRequest r){ userService.grantPlatformRole(r.getUserId(), r.getRoleId()); return new ResponseData<>(); }
    @PostMapping("/platformRole/revoke") public ResponseData<Void> revoke(@RequestBody UserRoleGrantRequest r){ userService.revokePlatformRole(r.getUserId(), r.getRoleId()); return new ResponseData<>(); }
}
```

（update/delete/resetPassword 若 v1 需要，仿此补 service 方法 + 权限点已在 Task 4 seed；不做则从 seed 去掉对应行以免交叉校验因"有权限行无 endpoint"WARN——WARN 不阻断，可留。）

- [ ] **Step 6: 测试 + 编译 + 提交**

Run: `mvn -q -pl platform-api -am test -Dtest=UserServiceTest` → PASS。

```bash
git add platform-api/src/main/java/com/coding/platformapi/services/UserService.java \
        platform-api/src/main/java/com/coding/platformapi/controllers/UserController.java \
        platform-api/src/main/java/com/coding/platformapi/models/UserCreateRequest.java \
        platform-api/src/main/java/com/coding/platformapi/models/UserKeyRequest.java \
        platform-api/src/main/java/com/coding/platformapi/models/UserRoleGrantRequest.java \
        platform-data/src/main/java/com/coding/data/mapper/auth/PlatformUserRoleMapper.java \
        platform-data/src/main/resources/mapper/auth/PlatformUserRoleMapper.xml \
        platform-api/src/test/java/com/coding/platformapi/services/UserServiceTest.java
git commit -m "feat(rbac): 用户管理 controller/service + 平台角色授予

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Task 15: RoleService（角色 CRUD + 权限勾选保存，scope 不变量）

**Files:**
- Create: `platform-api/.../models/RoleCreateRequest.java`,`RolePermissionSaveRequest.java`,`RoleKeyRequest.java`
- Create: `platform-api/.../services/RoleService.java`
- Create: `platform-api/.../controllers/RoleController.java`
- Create: `platform-api/.../controllers/PermissionController.java`
- Test: `platform-api/src/test/java/.../services/RoleServiceTest.java`

**Interfaces:**
- Produces: `RoleService.savePermissions(String roleId, List<String> permissionCodes)` 校验 §3.4.1（角色 scope 与每个权限点族一致：TENANT 角色只收 `tenant:` 开头、PLATFORM 只收 `platform:`），`built_in` 角色 code/scope 不可改、不可删（抛 `ROLE_BUILTIN_READONLY`）；全量重存关联（先 delete 后 insert，同事务）。
- `PermissionController.list()` 返回 `List<PlatformPermission>`（目录）。

- [ ] **Step 1: 失败测试（scope 不变量 + built_in 删除拒绝）**

```java
package com.coding.platformapi.services;
import com.coding.common.exception.CloudPlatformException;
import com.coding.data.mapper.auth.*;
import com.coding.data.models.auth.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RoleServiceTest {
    PlatformRoleMapper roleMapper = mock(PlatformRoleMapper.class);
    PlatformPermissionMapper permMapper = mock(PlatformPermissionMapper.class);
    PlatformRolePermissionMapper rpMapper = mock(PlatformRolePermissionMapper.class);
    RoleService svc = new RoleService(roleMapper, permMapper, rpMapper);

    private PlatformRole role(String code,String scope,byte built){
        var r=new PlatformRole(); r.setId("r1"); r.setCode(code); r.setScope(scope); r.setBuiltIn(built); return r;
    }
    private PlatformPermission perm(String code){ var p=new PlatformPermission(); p.setId("p_"+code); p.setCode(code); return p; }

    @Test void tenant_role_rejects_platform_perm() {
        when(roleMapper.selectByPrimaryKey("r1")).thenReturn(role("tenant-admin","TENANT",(byte)1));
        when(permMapper.selectByCode("platform:user:manage")).thenReturn(perm("platform:user:manage"));
        assertThatThrownBy(() -> svc.savePermissions("r1", List.of("platform:user:manage")))
            .isInstanceOf(CloudPlatformException.class);
        verify(rpMapper, never()).deleteByRoleId(any());
    }
    @Test void builtin_role_delete_rejected() {
        when(roleMapper.selectByPrimaryKey("r1")).thenReturn(role("admin","PLATFORM",(byte)1));
        assertThatThrownBy(() -> svc.delete("r1")).isInstanceOf(CloudPlatformException.class);
    }
    @Test void valid_tenant_perm_saves() {
        when(roleMapper.selectByPrimaryKey("r1")).thenReturn(role("tenant-admin","TENANT",(byte)1));
        when(permMapper.selectByCode("tenant:member:manage")).thenReturn(perm("tenant:member:manage"));
        svc.savePermissions("r1", List.of("tenant:member:manage"));
        verify(rpMapper).deleteByRoleId("r1");
        verify(rpMapper).insert(any());
    }
}
```

Run → FAIL。

- [ ] **Step 2: service**

```java
package com.coding.platformapi.services;
import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.utils.ULIDGenerator;
import com.coding.data.mapper.auth.*;
import com.coding.data.models.auth.*;
import com.coding.platformapi.models.RoleCreateRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Date;
import java.util.List;

@Service @RequiredArgsConstructor
public class RoleService {
    private final PlatformRoleMapper roleMapper;
    private final PlatformPermissionMapper permMapper;
    private final PlatformRolePermissionMapper rpMapper;

    public List<PlatformRole> list() { return roleMapper.listAllActive(); }

    public PlatformRole create(RoleCreateRequest req) {
        PlatformRole r = new PlatformRole();
        r.setId(ULIDGenerator.generateULID());
        r.setName(req.getName()); r.setCode(req.getCode()); r.setDescription(req.getDescription());
        r.setScope(req.getScope()); r.setBuiltIn((byte)0); r.setStatus((byte)1);
        Date now=new Date(); r.setCreatedAt(now); r.setUpdatedAt(now);
        roleMapper.insert(r);
        return r;
    }

    public void delete(String roleId) {
        PlatformRole r = require(roleId);
        if (r.getBuiltIn()!=null && r.getBuiltIn()==1) throw new CloudPlatformException(EnumResponseType.ROLE_BUILTIN_READONLY);
        roleMapper.deleteByPrimaryKey(roleId);
        rpMapper.deleteByRoleId(roleId);
    }

    @Transactional
    public void savePermissions(String roleId, List<String> permissionCodes) {
        PlatformRole r = require(roleId);
        rpMapper.deleteByRoleId(roleId);
        Date now = new Date();
        for (String code : permissionCodes) {
            PlatformPermission p = permMapper.selectByCode(code);
            if (p == null) throw new CloudPlatformException(EnumResponseType.PERMISSION_NOT_FOUND);
            assertScopeMatches(r.getScope(), p.getCode());
            PlatformRolePermission rp = new PlatformRolePermission();
            rp.setId(ULIDGenerator.generateULID()); rp.setRoleId(roleId); rp.setPermissionId(p.getId()); rp.setCreatedAt(now);
            rpMapper.insert(rp);
        }
    }

    public List<String> permissionCodesOf(String roleId) { return rpMapper.selectPermissionCodesByRoleId(roleId); }

    private void assertScopeMatches(String roleScope, String permCode) {
        boolean isTenantPerm = permCode.startsWith("tenant:");
        boolean isPlatformPerm = permCode.startsWith("platform:");
        if ("TENANT".equals(roleScope) && !isTenantPerm) throw new CloudPlatformException(EnumResponseType.ROLE_SCOPE_MISMATCH);
        if ("PLATFORM".equals(roleScope) && !isPlatformPerm) throw new CloudPlatformException(EnumResponseType.ROLE_SCOPE_MISMATCH);
    }
    private PlatformRole require(String id){
        PlatformRole r = roleMapper.selectByPrimaryKey(id);
        if (r==null) throw new CloudPlatformException(EnumResponseType.BEAN_VALIDATION_EXCEPTION,"角色不存在");
        return r;
    }
}
```

> `permissionCodesOf` 返回的是 permission **id**；配置页回显要 code。改 `PlatformRolePermissionMapper` 加 `selectPermissionCodesByRoleId(roleId)`（Task 2 的 XML 里再加一个 select：`select p.code from platform_role_permission rp join platform_permission p on p.id=rp.permission_id where rp.role_id=#{roleId}`）。service 用它。

- [ ] **Step 3: roleMapper 补 listAllActive + PlatformRoleMapper.xml**

XML：`select <Base_Column_List> from platform_role where deleted_at is null order by scope, code`。

- [ ] **Step 4: models + controllers**

请求模型：`RoleCreateRequest`（`name/code/description/scope`）、`RoleKeyRequest`（`id`）、`RolePermissionSaveRequest`（`roleId/List<String> permissionCodes`），均 `@Data`。

```java
package com.coding.platformapi.controllers;
import com.coding.common.models.system.ResponseData;
import com.coding.data.models.auth.PlatformRole;
import com.coding.platformapi.models.*;
import com.coding.platformapi.services.RoleService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@Tag(name="角色管理") @RestController @RequestMapping("/role") @RequiredArgsConstructor
public class RoleController {
    private final RoleService roleService;
    @PostMapping("/list") public ResponseData<List<PlatformRole>> list(){ return new ResponseData<>(roleService.list()); }
    @PostMapping("/create") public ResponseData<PlatformRole> create(@RequestBody RoleCreateRequest r){ return new ResponseData<>(roleService.create(r)); }
    @PostMapping("/delete") public ResponseData<Void> delete(@RequestBody RoleKeyRequest r){ roleService.delete(r.getId()); return new ResponseData<>(); }
    @PostMapping("/permission/save") public ResponseData<Void> save(@RequestBody RolePermissionSaveRequest r){ roleService.savePermissions(r.getRoleId(), r.getPermissionCodes()); return new ResponseData<>(); }
    @PostMapping("/permission/list") public ResponseData<List<String>> permsOf(@RequestBody RoleKeyRequest r){ return new ResponseData<>(roleService.permissionCodesOf(r.getId())); }
}
```

```java
package com.coding.platformapi.controllers;
import com.coding.common.models.system.ResponseData;
import com.coding.data.mapper.auth.PlatformPermissionMapper;
import com.coding.data.models.auth.PlatformPermission;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@Tag(name="权限目录") @RestController @RequestMapping("/permission") @RequiredArgsConstructor
public class PermissionController {
    private final PlatformPermissionMapper permissionMapper;
    @PostMapping("/list") public ResponseData<List<PlatformPermission>> list(){ return new ResponseData<>(permissionMapper.selectAllActive()); }
}
```

- [ ] **Step 5: 测试 + 编译 + 提交**

Run: `mvn -q -pl platform-api -am test -Dtest=RoleServiceTest` → PASS。

```bash
git add platform-api/src/main/java/com/coding/platformapi/services/RoleService.java \
        platform-api/src/main/java/com/coding/platformapi/controllers/RoleController.java \
        platform-api/src/main/java/com/coding/platformapi/controllers/PermissionController.java \
        platform-api/src/main/java/com/coding/platformapi/models/Role*.java \
        platform-data/src/main/resources/mapper/auth/PlatformRolePermissionMapper.xml \
        platform-data/src/main/java/com/coding/data/mapper/auth/PlatformRolePermissionMapper.java \
        platform-data/src/main/resources/mapper/auth/PlatformRoleMapper.xml \
        platform-data/src/main/java/com/coding/data/mapper/auth/PlatformRoleMapper.java \
        platform-api/src/test/java/com/coding/platformapi/services/RoleServiceTest.java
git commit -m "feat(rbac): 角色 CRUD + 权限勾选保存（scope 不变量/内置只读）+ 权限目录

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Task 16: 租户成员管理 + create-with-owner（原子写，§4.5）

**Files:**
- Create: `platform-api/.../models/MemberAddRequest.java`,`MemberRoleRequest.java`,`MemberRemoveRequest.java`
- Modify: `platform-api/.../models/TenantCreateRequest.java`（加 `ownerUserId`）
- Modify: `platform-api/.../services/TenantService.java`（create 原子写 owner；新增成员方法）
- Modify: `platform-api/.../controllers/TenantController.java`（加 `/tenant/member/*`）
- Test: `platform-api/src/test/java/.../services/TenantMemberServiceTest.java`

**Interfaces:**
- Produces:
  - `TenantService.create(...)`：若 `ownerUserId` 非空，同事务写 `user_tenant` + `user_tenant_role(owner→tenant-admin)`（角色 id 由 `roleMapper.selectByCode("tenant-admin")`）。
  - `memberAdd(tenantId, userId, roleId?)`：不变量 2（先有 member 再 role）。
  - `memberRemove(tenantId, userId)`：删 member + 级联删其 role；不变量 3（不能移空最后一个 admin）。
  - `grantMemberRole/revokeMemberRole`；revoke tenant-admin 时校验不变量 3。
  - `listMembers(tenantId)`（结合 TenantContextResolver：自管只能列自己租户）。

- [ ] **Step 1: 失败测试（不变量 2 + 3）**

```java
package com.coding.platformapi.services;
import com.coding.common.exception.CloudPlatformException;
import com.coding.data.mapper.auth.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TenantMemberServiceTest {
    PlatformUserTenantMapper utm = mock(PlatformUserTenantMapper.class);
    PlatformUserTenantRoleMapper utr = mock(PlatformUserTenantRoleMapper.class);
    PlatformRoleMapper roleMapper = mock(PlatformRoleMapper.class);
    PlatformUserMapper userMapper = mock(PlatformUserMapper.class);

    @Test void add_member_without_tenant_row_rejected() {
        when(utm.exists("u1","t1")).thenReturn(false);
        var svc = memberSvc();
        assertThatThrownBy(() -> svc.grantRole("t1","u1","roleX"))
            .isInstanceOf(CloudPlatformException.class);
    }
    @Test void cannot_revoke_last_admin() {
        var svc = memberSvc();
        when(roleMapper.selectByCode("tenant-admin")).thenReturn(adminRole());
        when(utr.countByTenantAndRole("t1","adminRole")).thenReturn(1);
        assertThatThrownBy(() -> svc.revokeRole("t1","u1","adminRole"))
            .isInstanceOf(CloudPlatformException.class);
    }
    private com.coding.data.models.auth.PlatformRole adminRole(){
        var r=new com.coding.data.models.auth.PlatformRole(); r.setId("adminRole"); r.setCode("tenant-admin"); r.setScope("TENANT"); r.setBuiltIn((byte)1); return r; }
    private TenantMemberService memberSvc(){ return new TenantMemberService(utm, utr, roleMapper, userMapper); }
}
```

Run → FAIL。

- [ ] **Step 2: TenantMemberService（从 TenantService 拆出，独立可测）**

```java
package com.coding.platformapi.services;
import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.utils.ULIDGenerator;
import com.coding.data.mapper.auth.*;
import com.coding.data.models.auth.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Date;

@Service @RequiredArgsConstructor
public class TenantMemberService {
    private final PlatformUserTenantMapper utMapper;
    private final PlatformUserTenantRoleMapper utrMapper;
    private final PlatformRoleMapper roleMapper;
    private final PlatformUserMapper userMapper;

    @Transactional
    public void addMember(String tenantId, String userId) {
        if (!utMapper.exists(userId, tenantId)) {
            PlatformUserTenant r = new PlatformUserTenant();
            r.setId(ULIDGenerator.generateULID()); r.setUserId(userId); r.setTenantId(tenantId); r.setCreatedAt(new Date());
            utMapper.insert(r);
        }
    }

    @Transactional
    public void grantRole(String tenantId, String userId, String roleId) {
        if (!utMapper.exists(userId, tenantId)) throw new CloudPlatformException(EnumResponseType.TENANT_MEMBER_NOT_FOUND);
        PlatformUserTenantRole r = new PlatformUserTenantRole();
        r.setId(ULIDGenerator.generateULID()); r.setUserId(userId); r.setTenantId(tenantId); r.setRoleId(roleId); r.setCreatedAt(new Date());
        utrMapper.insert(r);
    }

    @Transactional
    public void revokeRole(String tenantId, String userId, String roleId) {
        String adminRoleId = adminRoleId();
        if (roleId.equals(adminRoleId) && utrMapper.countByTenantAndRole(tenantId, adminRoleId) <= 1)
            throw new CloudPlatformException(EnumResponseType.TENANT_ADMIN_REQUIRED);
        utrMapper.delete(userId, tenantId, roleId);
    }

    @Transactional
    public void removeMember(String tenantId, String userId) {
        String adminRoleId = adminRoleId();
        var roles = utrMapper.selectByTenantAndUser(tenantId, userId);
        boolean isAdmin = roles.stream().anyMatch(x -> x.getRoleId().equals(adminRoleId));
        if (isAdmin && utrMapper.countByTenantAndRole(tenantId, adminRoleId) <= 1)
            throw new CloudPlatformException(EnumResponseType.TENANT_ADMIN_REQUIRED);
        utrMapper.deleteByUserAndTenant(userId, tenantId);
        utMapper.deleteByUserAndTenant(userId, tenantId);
    }

    private String adminRoleId() {
        PlatformRole r = roleMapper.selectByCode("tenant-admin");
        if (r == null) throw new IllegalStateException("内置角色 tenant-admin 缺失（seed 未执行）");
        return r.getId();
    }
}
```

- [ ] **Step 3: TenantService.create 接 owner（DB 写原子，provision 在事务外）**

`TenantCreateRequest` 加字段 `private String ownerUserId;`。为不把 K8s 外部调用圈进长事务，抽一个只包 DB 三写的 `@Transactional` 方法，`create()` 在 `tenantMapper.insert(tenant)` 之后调用它，provision 保持在方法外：

```java
    // 注入： private final TenantMemberService memberSvc; private final PlatformRoleMapper roleMapper;

    @Transactional
    public void bindOwner(String tenantId, String ownerUserId) {
        memberSvc.addMember(tenantId, ownerUserId);
        PlatformRole admin = roleMapper.selectByCode("tenant-admin");
        if (admin == null) throw new CloudPlatformException(EnumResponseType.PERMISSION_NOT_FOUND);
        memberSvc.grantRole(tenantId, ownerUserId, admin.getId());
    }
```

在 `create()` 里 `tenantMapper.insert(tenant);` 之后、`if (tenant.getStatus()==1) provisionTenantSas(...)` 之前插入：

```java
        if (StringUtils.hasText(req.getOwnerUserId())) {
            bindOwner(tenant.getId(), req.getOwnerUserId());
        }
```

> 注意 `bindOwner` 走代理才生效事务——`create()` 与 `bindOwner` 同类，`@Transactional` 需经 Spring 代理调用。简单起见把 owner 三写直接内联进 `create()`，并对 `create()` 整体不加 `@Transactional`（tenant 行先落、provision 失败已由既有回滚逻辑处理；owner 两表写失败则整个 create 抛错、tenant 行成为孤儿——v1 可接受，或改用 `TransactionTemplate` 显式包裹三写。executor 择一，测试覆盖不变量即可）。

- [ ] **Step 4: TenantController 加成员端点（自管/代管一致收口）**

`TenantController` 注入 `TenantMemberService memberSvc` + `TenantContextResolver ctx` + `AuthContext auth`（Task 19 定义；如先于 Task 19 做，就在本任务先建 AuthContext）。每个成员接口：先从当前 JWT 解出 `tenantInfo`（base token → null），用 `ctx.requireContext(tokenTenantId, reqTenantId)` 得生效 tenantId，再执行。

请求模型（Create）：

```java
package com.coding.platformapi.models;
import lombok.Data;
@Data public class MemberAddRequest { private String tenantId; private String userId; }
```

```java
package com.coding.platformapi.models;
import lombok.Data;
@Data public class MemberRemoveRequest { private String tenantId; private String userId; }
```

```java
package com.coding.platformapi.models;
import lombok.Data;
@Data public class MemberRoleRequest { private String tenantId; private String userId; private String roleId; }
```

成员行视图（给 list 用）：

```java
package com.coding.platformapi.models;
import lombok.Data;
import java.util.List;
@Data public class TenantMemberView {
    private String userId; private String username; private String displayName;
    private List<String> roleIds; private boolean owner;
}
```

controller 方法（`tokenTenantId()` = `auth.current()` 的 `tenantInfo.getTenantId()`，无租户返回 null）：

```java
    private String tokenTenantId() {
        var info = auth.current();
        return (info != null && info.getTenantInfo() != null) ? info.getTenantInfo().getTenantId() : null;
    }

    @PostMapping("/member/add")
    public ResponseData<Void> memberAdd(@RequestBody MemberAddRequest r) {
        String tid = ctx.requireContext(tokenTenantId(), r.getTenantId());
        memberSvc.addMember(tid, r.getUserId());
        return new ResponseData<>();
    }

    @PostMapping("/member/remove")
    public ResponseData<Void> memberRemove(@RequestBody MemberRemoveRequest r) {
        String tid = ctx.requireContext(tokenTenantId(), r.getTenantId());
        memberSvc.removeMember(tid, r.getUserId());
        return new ResponseData<>();
    }

    @PostMapping("/member/role/grant")
    public ResponseData<Void> memberRoleGrant(@RequestBody MemberRoleRequest r) {
        String tid = ctx.requireContext(tokenTenantId(), r.getTenantId());
        memberSvc.grantRole(tid, r.getUserId(), r.getRoleId());
        return new ResponseData<>();
    }

    @PostMapping("/member/role/revoke")
    public ResponseData<Void> memberRoleRevoke(@RequestBody MemberRoleRequest r) {
        String tid = ctx.requireContext(tokenTenantId(), r.getTenantId());
        memberSvc.revokeRole(tid, r.getUserId(), r.getRoleId());
        return new ResponseData<>();
    }

    @PostMapping("/member/list")
    public ResponseData<List<TenantMemberView>> memberList(@RequestBody MemberAddRequest r) {
        String tid = ctx.requireContext(tokenTenantId(), r.getTenantId());
        return new ResponseData<>(memberSvc.listMembers(tid));
    }
```

`TenantMemberService.listMembers(tenantId)`：`utMapper.selectByTenantId` 取成员 → 每人 `utrMapper.selectByTenantAndUser` 取角色 id + 反查 username（`userMapper.selectByPrimaryKey`），`owner` = 角色含 tenant-admin id。为此 `TenantMemberService` 再注入 `PlatformUserMapper userMapper`。

`/tenant/namespace/list` 对租户用户列本租户 ns——在 Task 4 seed 追加一行使该 URL 对 `tenant:overview:view` 亦 ANY-of：

```sql
INSERT INTO platform_permission (id,domain,resource,action,code,description)
VALUES ('perm_alloc_list_self','API','/tenant/namespace/list','POST','tenant:overview:view','看本租户分配')
ON DUPLICATE KEY UPDATE description=VALUES(description);
```

（`NamespaceAllocationService.list` 内对仅持 `tenant:overview:view`、无 `platform:allocation:list` 的请求，强制查询 `tenantId=token 租户`；判定放在 service：`auth.current().getTenantInfo()!=null` 时用 token 租户覆盖入参 tenantId。此约束与成员一致，走 `TenantContextResolver`。）

- [ ] **Step 5: 测试 + 编译 + 提交**

Run: `mvn -q -pl platform-api -am test -Dtest=TenantMemberServiceTest` → PASS。

```bash
git add platform-api/src/main/java/com/coding/platformapi/services/TenantMemberService.java \
        platform-api/src/main/java/com/coding/platformapi/services/TenantService.java \
        platform-api/src/main/java/com/coding/platformapi/controllers/TenantController.java \
        platform-api/src/main/java/com/coding/platformapi/models/Member*.java \
        platform-api/src/main/java/com/coding/platformapi/models/TenantMemberView.java \
        platform-api/src/main/java/com/coding/platformapi/models/TenantCreateRequest.java \
        platform-api/src/main/java/com/coding/platformapi/services/TenantService.java \
        platform-api/src/main/java/com/coding/platformapi/security/AuthContext.java \
        platform-api/src/test/java/com/coding/platformapi/services/TenantMemberServiceTest.java
git commit -m "feat(rbac): 租户成员/角色管理 + 建租户指定 owner（不变量 2/3）

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Task 17: 前端 token 流转（oauth.ts：switchTenant + current_tenant + 续期带租户）

**Files:**
- Modify: `platform-web/src/auth/oauth.ts`

**Interfaces:**
- Produces: `switchTenant(tenantId: string | null): Promise<boolean>`；`getCurrentTenant(): {tenantId,tenantName}|null`；`renewAccessToken()` 自动带当前租户 tenant_id。

- [ ] **Step 1: 加 current_tenant 常量与读写**

```ts
const CURRENT_TENANT_KEY = 'platform_current_tenant'

export interface TenantContext { tenantId: string; tenantName?: string }
export function getCurrentTenant(): TenantContext | null {
  try { const raw = localStorage.getItem(CURRENT_TENANT_KEY); return raw ? JSON.parse(raw) : null } catch { return null }
}
function setCurrentTenant(t: TenantContext | null): void {
  if (t) localStorage.setItem(CURRENT_TENANT_KEY, JSON.stringify(t)); else localStorage.removeItem(CURRENT_TENANT_KEY)
}
```

- [ ] **Step 2: 新增 switchTenant（走 session-renewal + tenant_id）**

```ts
/** 切换/进入租户：用会话 Cookie 重新签发含租户上下文的 token。null=退回平台视图(base)。 */
export async function switchTenant(tenantId: string | null, tenantName?: string): Promise<boolean> {
  const body = new URLSearchParams({ grant_type: RENEW_GRANT, client_id: CLIENT_ID })
  if (tenantId) body.set('tenant_id', tenantId)
  try {
    const resp = await fetch(`${ISSUER}/oauth2/token`, {
      method: 'POST', credentials: 'include',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body: body.toString(),
    })
    if (!resp.ok) { return false }
    const data = (await resp.json()) as { access_token?: string }
    if (!data.access_token) return false
    localStorage.setItem(TOKEN_KEY, data.access_token)   // 单 token：覆盖
    setCurrentTenant(tenantId ? { tenantId, tenantName } : null)
    scheduleRenew()   // 用新 token 的 exp 重排续期
    return true
  } catch { return false }
}
```

- [ ] **Step 3: renewAccessToken 带当前租户（关键：否则到期冲掉上下文，spec §4.3 静默坑）**

`renewAccessToken()` 里 body 追加：

```ts
  const t = getCurrentTenant()
  if (t?.tenantId) body.set('tenant_id', t.tenantId)
```

- [ ] **Step 4: logout 清 current_tenant**

```ts
  localStorage.removeItem(CURRENT_TENANT_KEY)
```

- [ ] **Step 5: 构建校验**

Run: `npm run build --prefix platform-web`
Expected: 通过（无类型错误）。

- [ ] **Step 6: Commit**

```bash
git add platform-web/src/auth/oauth.ts
git commit -m "feat(rbac): 前端 switchTenant 与续期携带 tenant_id（单 token 模型）

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Task 18: 顶栏租户切换器 + 权限驱动菜单显隐

**Files:**
- Create: `platform-web/src/stores/permission.ts`（decode JWT 暴露 permissions/platformRoles/currentTenant）
- Create: `platform-web/src/components/TenantSwitcher.vue`
- Modify: `platform-web/src/layouts/MainLayout.vue`（挂切换器 + 菜单按权限过滤）
- Modify: `platform-web/src/router/index.ts`（路由 meta.perm 守卫）

**Interfaces:**
- Produces: `usePermission()` 返回 `{ has(code), isAdmin, currentTenant, permissions }`；`has('platform:user:manage')` 控菜单。路由 `meta.requiresPerm`。

- [ ] **Step 1: permission store（从 access_token 的 data claim 解权限）**

```ts
import { computed, ref } from 'vue'
import { getAccessToken, getCurrentTenant } from '@/auth/oauth'

function decode(): { permissions: string[]; platformRoles: string[]; tenantId?: string } {
  const raw = getAccessToken(); if (!raw) return { permissions: [], platformRoles: [] }
  try {
    const payload = raw.split('.')[1]
    const claims = JSON.parse(atob(payload.replace(/-/g,'+').replace(/_/g,'/')))
    const d = claims.data ?? {}
    return { permissions: d.permissions ?? [], platformRoles: d.platformRoles ?? [], tenantId: d.tenantInfo?.tenantId }
  } catch { return { permissions: [], platformRoles: [] } }
}
const state = ref(decode())
export function refreshPermission() { state.value = decode() }
export const usePermission = () => ({
  permissions: computed(() => state.value.permissions),
  isAdmin: computed(() => state.value.platformRoles.includes('admin')),
  currentTenant: computed(() => getCurrentTenant()),
  has: (code: string) => state.value.permissions.includes(code),
})
```

> 注意：JWE 加密的 token 前端解不出 payload（仅展示用，权威在后端）。platform-web-client 的 jwtType=JWS（seed 见 `oauth2_registered_client`），可解。若将来改 JWE，改走后端 `/user/me` 拉权限。

- [ ] **Step 2: 登录/切租户后调 refreshPermission**

在 `handleCallback` 成功、`switchTenant` 成功后 `refreshPermission()`。

- [ ] **Step 3: TenantSwitcher.vue**

拉 `/user/my-tenants`（Task 19 提供，或登录响应），渲染下拉；选择→`switchTenant(id,name)`→刷新当前页数据。admin（无 tenantInfo）显示"筛选器"占位或复用同组件但标注代管。

- [ ] **Step 4: 路由守卫按权限**

`router/index.ts` 给管理页加 `meta: { requiresPerm: 'platform:user:manage' }`，`beforeEach` 里 `usePermission().has(...)` 否则跳首页/toast。

- [ ] **Step 5: 构建 + 手动验证 + Commit**

Run: `npm run build --prefix platform-web` → PASS。按 memory「UI preview 验证法」挂后端验证切租户后菜单变化。

```bash
git add platform-web/src/stores/permission.ts platform-web/src/components/TenantSwitcher.vue \
        platform-web/src/layouts/MainLayout.vue platform-web/src/router/index.ts platform-web/src/auth/oauth.ts
git commit -m "feat(rbac): 顶栏租户切换器 + 权限驱动菜单/路由显隐

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Task 19: /user/me 与 /user/my-tenants（登录即，供前端）

**Files:**
- Modify: `platform-api/.../controllers/UserController.java`（加两个登录即接口）
- Create: `platform-api/.../services/CurrentUserQuery.java`（从 JWT 解 userId + 查租户列表）

**Interfaces:**
- Produces: `/user/me`（返回 username/displayName/permissions/platformRoles/currentTenantId，登录即可，已在 ExemptPaths）；`/user/my-tenants`（返回用户所属租户简表，供切换器）。

- [ ] **Step 1: AuthContext 组件（解当前 JWT data）**

```java
package com.coding.platformapi.security;
import com.coding.common.components.jwt.JwtProperties;
import com.coding.data.models.system.TokenUserInfo;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Component @RequiredArgsConstructor
public class AuthContext {
    private final JsonMapper jsonMapper;
    private final JwtProperties jwtProperties;
    public TokenUserInfo current() {
        Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        if (!(principal instanceof Jwt jwt)) return null;
        return jsonMapper.convertValue(jwt.getClaim(jwtProperties.getDataKey()), new TypeReference<>() {});
    }
}
```

- [ ] **Step 2: CurrentUserQuery**

```java
package com.coding.platformapi.services;
import com.coding.data.mapper.auth.PlatformUserMapper;
import com.coding.data.mapper.auth.PlatformTenantMapper;
import com.coding.data.models.auth.PlatformTenant;
import com.coding.data.models.auth.PlatformUser;
import com.coding.data.models.system.TokenUserInfo;
import com.coding.platformapi.security.AuthContext;
import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.List;

@Service @RequiredArgsConstructor
public class CurrentUserQuery {
    private final AuthContext auth;
    private final PlatformUserMapper userMapper;
    private final PlatformTenantMapper tenantMapper;

    public TokenUserInfo me() {
        var t = auth.current();
        if (t == null) throw new CloudPlatformException(EnumResponseType.USER_UN_LOGIN);
        return t;
    }
    public List<PlatformTenant> myTenants() {
        var t = auth.current();
        if (t == null) throw new CloudPlatformException(EnumResponseType.USER_UN_LOGIN);
        PlatformUser u = userMapper.selectByUsername(t.getUsername());
        return tenantMapper.listTenantsByUser(u.getId()); // Task 加 mapper 方法
    }
}
```

- [ ] **Step 3: PlatformTenantMapper.listTenantsByUser + XML**

```xml
  <select id="listTenantsByUser" resultType="...PlatformTenant">
    select t.id, t.name, t.service_account, t.status, t.created_at, t.updated_at
    from platform_user_tenant ut join platform_tenant t on t.id=ut.tenant_id
    where ut.user_id = #{userId} and t.deleted_at is null
  </select>
```

- [ ] **Step 4: controller 端点**

`/user/me`, `/user/my-tenants`（POST+body 可空）。已在 ExemptPaths（`/user/me`,`/user/my-tenants`）。

- [ ] **Step 5: 编译 + 交叉校验通过（这两个 path 走豁免，不报裸 endpoint）+ 提交**

Run: `mvn -q -pl platform-api -am compile` → SUCCESS。启动 platform-api，看日志 `[RBAC] 交叉校验通过`。

```bash
git add platform-api/src/main/java/com/coding/platformapi/security/AuthContext.java \
        platform-api/src/main/java/com/coding/platformapi/services/CurrentUserQuery.java \
        platform-api/src/main/java/com/coding/platformapi/controllers/UserController.java \
        platform-data/src/main/java/com/coding/data/mapper/auth/PlatformTenantMapper.java \
        platform-data/src/main/resources/mapper/auth/PlatformTenantMapper.xml
git commit -m "feat(rbac): /user/me 与 /user/my-tenants（前端上下文数据源）

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Task 20: 三个管理页面（用户 / 角色与权限 / 租户详情-成员）

**Files:**
- Create: `platform-web/src/views/UserView.vue`
- Create: `platform-web/src/views/RoleView.vue`
- Modify: `platform-web/src/views/TenantView.vue`（详情 tab：命名空间分配 / 成员与角色）
- Modify: `platform-web/src/router/index.ts`（注册路由 + requiresPerm）

**Interfaces:** 消费 Task 14–19 接口。

- [ ] **Step 1: UserView.vue** — 列表 + 新建 + "平台角色"授予弹窗（调 `/role/list` 过滤 scope=PLATFORM 选择，`/user/platformRole/grant|revoke`）。菜单项 `has('platform:user:manage')`。
- [ ] **Step 2: RoleView.vue** — 左列角色（分 PLATFORM/TENANT 组，built_in 只读锁），右列权限勾选树（`/permission/list` 按 domain 分组）；保存调 `/role/permission/save`，回显 `/role/permission/list`。
- [ ] **Step 3: TenantView.vue 成员 tab** — 详情页 el-tabs：现有"命名空间分配"保留；新增"成员与角色"：列 `/tenant/member/list`，"添加成员"搜索平台用户（`/user/list`）→ `/tenant/member/add`→`/tenant/member/role/grant`。owner（持 tenant-admin）加徽标。
- [ ] **Step 4: 路由注册** — 三条路由加 `meta.requiresPerm`；admin 才见"角色与权限"，成员页租户用户可见。
- [ ] **Step 5: 构建 + 浏览器验证**（memory「UI preview 验证法」：假 token + XHR stub 或直接连 dev 后端）→ 覆盖：admin 建用户→建租户带 owner→切到该租户→成员 tab 加人。
- [ ] **Step 6: Commit**

```bash
git add platform-web/src/views/UserView.vue platform-web/src/views/RoleView.vue platform-web/src/views/TenantView.vue platform-web/src/router/index.ts
git commit -m "feat(rbac): 用户/角色权限/租户成员 三管理页

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## 收尾验证（全部任务后）

- [ ] 全量后端测试：`mvn -q test`（platform-api / platform-common）
- [ ] 交叉校验：启动 platform-api，日志 `[RBAC] 交叉校验通过`，无裸 endpoint 阻断
- [ ] 端到端：admin 建租户→指定 owner→owner 账号 session-renewal 带 tenant_id 拿帽子 token→其租户内加成员/授 tenant-member→成员 token `data.permissions` 含 `tenant:overview:view` 不含 `platform:*`→成员调 `/tenant/create` 被 403（表驱动拦截）
- [ ] 回归：既有租户 CRUD / ns 分配 / 资源页透传链路行为不变；k8s-server `/admin/**` 仍限 PLATFORM:admin
- [ ] 有意的行为收紧（非 bug）：管理端点从"登录即可"改为"持对应 PERM 才可"——现网只有 admin 用户故无感；将来纯租户用户访问 `/tenant/list`、`/cluster/*` 等管理页将 403，其可见面 = 豁免端点（`/resource/**`、`/user/me`、`/user/my-tenants`）+ 租户族 ANY-of 端点（`/tenant/namespace/list`、`/tenant/member/list`）。
