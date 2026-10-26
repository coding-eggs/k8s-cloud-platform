# Harbor 组件与租户镜像项目集成 · 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 Harbor 作为可安装的集群组件接入平台，并在装完后按租户自动创建 Harbor project、robot 账号与租户归属的 REGISTRY 凭据，使流水线镜像节点开箱可用且租户之间相互隔离。

**Architecture:** 组件安装走既有 S3.6 链路（内置 chart + recipe + 探测 + 表单 + 平台生成的管理口令 Secret）。集成侧由 platform-api 的 `HarborRegistryService` 用 Harbor REST API 建 project/robot，凭据加密写入 `platform_credential`（新增 `tenant_id` / `source`），项目映射写新表 `platform_registry_project`；凭据的可见性按请求租户帽过滤。

**Tech Stack:** Java 21 / Spring Boot 4 / MyBatis / fabric8（k8s-server）/ Helm 4.2 / Vue 3 + Element Plus / Harbor chart 1.19.4（app 2.15.4）。

**Spec:** `docs/superpowers/specs/2026-10-27-harbor-registry-design.md`

## Global Constraints

- 组件 code `harbor`；release `platform-harbor`；namespace `harbor`；探测 Deployment `harbor-core`（镜像含 `goharbor/harbor-core`），`fullnameOverride: harbor`。
- chart 版本 `1.19.4`，appVersion `2.15.4`（`ComponentRecipeCatalogTest` 会与内置 `Chart.yaml` 交叉校验）。
- 暴露：`expose.type=route`，parentRef `istio-system/external-gateway`，`externalURL: http://${harbor.host}`；前置组件 `istio`。
- 管理口令 Secret：`harbor/platform-harbor-secrets`，键 `HARBOR_ADMIN_PASSWORD`（32 位）与 `secretKey`（**16 位**）；已存在不轮换；已初始化但 Secret 丢失时**必须报错**，不得重新生成。
- 项目命名：租户 `t-<tenant.serviceAccount>`；平台共享 `platform`。robot 名 `ci`，project 级 push+pull，不过期。
- 凭据：`type=HARBOR`、`authType=BASIC`、`usableByPipelines=1`、`source=HARBOR`、租户行带 `tenant_id`。
- 可见性判据：`hat==null || (tenantId==null && source=='MANUAL') || hat.equals(tenantId)`。
- 新权限行必须显式绑 `builtin_role_admin`（不靠 code 继承）。
- 迁移 `V2026_10_27_1__harbor_registry.sql` 手工重放，先于新构建；MySQL 注释 `--` 后必须跟空白。
- 文档与代码同目录：组件 README 在 `charts/components/harbor/README.md`。

## Review Focus

- `host` 带 `http://` / 路径 / 大写 / 空串时，表单与后端都必须拒绝 —— 否则装出一个 token 服务地址错误的 Harbor，push 全失败。
- Harbor 已初始化（`harbor-core` 存在）但管理 Secret 丢失时，必须拒绝安装/升级并提示人工恢复 —— 静默生成新口令会让后续所有同步 401。
- 租户 A 手工传租户 B 的 credentialId 调 `/registry/browse` 或在画布里引用时，必须被拒 —— 否则等于租户 A 用 B 的 robot 推 B 的项目。
- `source=HARBOR` 且 `tenant_id` 为空的平台共享凭据，对租户必须不可见 —— 否则租户能推 `platform` 项目。
- 重复同步必须幂等（第二次零创建请求、不轮换 robot 口令）；`force` 重建必须先删旧 robot 再建；同名但非平台建、名字恰为 `ci` 的 robot 会被接管删除，结果消息里要写明。

---

### Task 1: 迁移与数据模型（凭据两列 + 项目台账 + 权限行）

**Files:**
- Create: `platform-data/src/main/resources/db/migration/V2026_10_27_1__harbor_registry.sql`
- Modify: `platform-data/src/main/java/com/coding/data/models/system/PlatformCredential.java`
- Modify: `platform-data/src/main/java/com/coding/data/mapper/system/PlatformCredentialMapper.java`
- Modify: `platform-data/src/main/resources/mapper/system/PlatformCredentialMapper.xml`
- Create: `platform-data/src/main/java/com/coding/data/models/system/PlatformRegistryProject.java`
- Create: `platform-data/src/main/java/com/coding/data/mapper/system/PlatformRegistryProjectMapper.java`
- Create: `platform-data/src/main/resources/mapper/system/PlatformRegistryProjectMapper.xml`

**Interfaces:**
- Produces:
  - `PlatformCredential.getTenantId()/setTenantId(String)`、`getSource()/setSource(String)`
  - `PlatformRegistryProjectMapper`: `insert(PlatformRegistryProject)`、`updateResult(PlatformRegistryProject)`、`selectById(String)`、`selectByCluster(String)`、`selectByClusterAndTenant(String clusterId, String tenantId)`、`selectByClusterAndProject(String clusterId, String projectName)`、`selectByCredentialId(String)`、`selectByTenant(String tenantId)`、`deleteById(String)`
  - `PlatformCredentialMapper.updateEnabledByTenantAndSource(String tenantId, String source, int enabled)`、`deleteByTenantAndSource(String tenantId, String source)`

- [ ] **Step 1: 写迁移文件**

创建 `platform-data/src/main/resources/db/migration/V2026_10_27_1__harbor_registry.sql`：

```sql
-- ============================================================================================
-- Harbor 集群组件 + 租户镜像项目集成（2026-10-27）
-- ============================================================================================
-- 设计：docs/superpowers/specs/2026-10-27-harbor-registry-design.md
-- ⚠️ 本仓没有 Flyway，手工重放；幂等（information_schema 条件式 ADD COLUMN + IF NOT EXISTS）。
-- ⚠️ MySQL 注释 `--` 后面必须跟空白字符。
-- ⚠️ 先重放本文件再起新构建：新端点无权限行会被启动期交叉校验拒启。
-- ============================================================================================

SET @col := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'platform_credential'
               AND COLUMN_NAME = 'tenant_id');
SET @ddl := IF(@col = 0,
  'ALTER TABLE platform_credential ADD COLUMN tenant_id varchar(64) NULL COMMENT ''归属租户；NULL = 平台级（现有全局凭据语义）''',
  'SET @harbor_tenant_skip = 1');
PREPARE st FROM @ddl; EXECUTE st; DEALLOCATE PREPARE st;

SET @col := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'platform_credential'
               AND COLUMN_NAME = 'source');
SET @ddl := IF(@col = 0,
  'ALTER TABLE platform_credential ADD COLUMN source varchar(16) NOT NULL DEFAULT ''MANUAL'' COMMENT ''MANUAL / HARBOR（Harbor 自动维护，凭据页只读）''',
  'SET @harbor_source_skip = 1');
PREPARE st FROM @ddl; EXECUTE st; DEALLOCATE PREPARE st;

SET @idx := (SELECT COUNT(*) FROM information_schema.STATISTICS
             WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'platform_credential'
               AND INDEX_NAME = 'idx_credential_tenant');
SET @ddl := IF(@idx = 0,
  'ALTER TABLE platform_credential ADD INDEX idx_credential_tenant (tenant_id)',
  'SET @harbor_idx_skip = 1');
PREPARE st FROM @ddl; EXECUTE st; DEALLOCATE PREPARE st;

CREATE TABLE IF NOT EXISTS platform_registry_project (
  id             varchar(64)  NOT NULL COMMENT 'ULIDGenerator',
  cluster_id     varchar(64)  NOT NULL,
  tenant_id      varchar(64)  NULL COMMENT 'NULL = 平台共享项目（platform）',
  host           varchar(255) NOT NULL COMMENT 'Harbor 访问地址（host[:port]，不带 scheme）',
  project_name   varchar(255) NOT NULL,
  credential_id  varchar(64)  NULL COMMENT 'platform_credential.id（source=HARBOR）',
  robot_id       bigint       NULL COMMENT 'Harbor 机器人账号 id（重建时按它删）',
  status         varchar(16)  NOT NULL DEFAULT 'READY' COMMENT 'READY / ERROR',
  message        varchar(1024) NULL,
  last_synced_at datetime     NULL,
  created_at     datetime     NOT NULL,
  updated_at     datetime     NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_registry_project (cluster_id, project_name),
  KEY idx_registry_project_tenant (tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='租户在某个 Harbor 上的镜像项目台账';

INSERT INTO platform_permission (id, domain, resource, action, code, description) VALUES
 ('perm_registry_projects_list',    'API','/registry/projects/list',    'POST','platform:registry:project:read',
  'Harbor 租户镜像项目列表（含凭据状态）'),
 ('perm_registry_projects_sync',    'API','/registry/projects/sync',    'POST','platform:registry:project:manage',
  '同步全部租户的 Harbor 项目与机器人账号'),
 ('perm_registry_projects_rebuild', 'API','/registry/projects/rebuild', 'POST','platform:registry:project:manage',
  '重建某个租户（或平台共享）的 Harbor 机器人账号与凭据')
ON DUPLICATE KEY UPDATE description=VALUES(description), resource=VALUES(resource), action=VALUES(action);

INSERT INTO platform_role_permission (id, role_id, permission_id) VALUES
 ('rp_admin_registry_projects_list',    'builtin_role_admin','perm_registry_projects_list'),
 ('rp_admin_registry_projects_sync',    'builtin_role_admin','perm_registry_projects_sync'),
 ('rp_admin_registry_projects_rebuild', 'builtin_role_admin','perm_registry_projects_rebuild')
ON DUPLICATE KEY UPDATE permission_id=VALUES(permission_id);
```

- [ ] **Step 2: 给 `PlatformCredential` 加两个字段**

在 `createdAt` 之前加：

```java
    /**
     * 归属租户；null = 平台级（现有全局凭据语义：所有租户可用）。
     * 只有 {@code source=HARBOR} 的行会写它。
     */
    private String tenantId;

    /**
     * MANUAL（手工建的）/ HARBOR（Harbor 组件同步维护）。
     * HARBOR 行在凭据页只读：改 endpoint 或删掉会让流水线静默失效。
     */
    private String source;
```

- [ ] **Step 3: 改 `PlatformCredentialMapper` 与 XML**

接口新增：

```java
    /** 租户停用/启用时跟随：只动该租户的 Harbor 自动凭据 */
    int updateEnabledByTenantAndSource(@Param("tenantId") String tenantId,
                                       @Param("source") String source,
                                       @Param("enabled") int enabled);

    /** 删租户时回收它名下的 Harbor 自动凭据 */
    int deleteByTenantAndSource(@Param("tenantId") String tenantId,
                                @Param("source") String source);
```

XML：`BaseResultMap` 加

```xml
    <result column="tenant_id"          jdbcType="VARCHAR"   property="tenantId" />
    <result column="source"             jdbcType="VARCHAR"   property="source" />
```

`Base_Column_List` 末尾追加 `, tenant_id, source`；`insert` 的列与值各加 `tenant_id, source` / `#{tenantId,jdbcType=VARCHAR}, #{source,jdbcType=VARCHAR}`；`update` 的 set 加

```xml
        tenant_id = #{tenantId,jdbcType=VARCHAR},
        source = #{source,jdbcType=VARCHAR},
```

并新增两个语句：

```xml
  <update id="updateEnabledByTenantAndSource">
    update platform_credential set enabled = #{enabled}
    where tenant_id = #{tenantId,jdbcType=VARCHAR} and source = #{source,jdbcType=VARCHAR}
  </update>

  <delete id="deleteByTenantAndSource">
    delete from platform_credential
    where tenant_id = #{tenantId,jdbcType=VARCHAR} and source = #{source,jdbcType=VARCHAR}
  </delete>
```

- [ ] **Step 4: 建台账模型与 mapper**

`PlatformRegistryProject.java`：

```java
package com.coding.data.models.system;

import lombok.Data;

import java.util.Date;

/** 租户在某个 Harbor 上的镜像项目台账（表 platform_registry_project）。 */
@Data
public class PlatformRegistryProject {

    private String id;

    private String clusterId;

    /** null = 平台共享项目（platform） */
    private String tenantId;

    /** host[:port]，不带 scheme */
    private String host;

    private String projectName;

    private String credentialId;

    /** Harbor 机器人账号 id */
    private Long robotId;

    /** READY / ERROR */
    private String status;

    private String message;

    private Date lastSyncedAt;

    private Date createdAt;

    private Date updatedAt;
}
```

`PlatformRegistryProjectMapper.java`：

```java
package com.coding.data.mapper.system;

import com.coding.data.models.system.PlatformRegistryProject;
import org.apache.ibatis.annotations.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/** 租户镜像项目台账（表 platform_registry_project）。 */
@Repository
public interface PlatformRegistryProjectMapper {

    int insert(PlatformRegistryProject record);

    /** 同步结果回写：host / project / credential / robot / status / message / last_synced_at */
    int updateResult(PlatformRegistryProject record);

    PlatformRegistryProject selectById(@Param("id") String id);

    List<PlatformRegistryProject> selectByCluster(@Param("clusterId") String clusterId);

    /** tenantId 为 null 时匹配 tenant_id is null */
    PlatformRegistryProject selectByClusterAndTenant(@Param("clusterId") String clusterId,
                                                     @Param("tenantId") String tenantId);

    PlatformRegistryProject selectByClusterAndProject(@Param("clusterId") String clusterId,
                                                      @Param("projectName") String projectName);

    PlatformRegistryProject selectByCredentialId(@Param("credentialId") String credentialId);

    List<PlatformRegistryProject> selectByTenant(@Param("tenantId") String tenantId);

    int deleteById(@Param("id") String id);
}
```

`PlatformRegistryProjectMapper.xml`：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="com.coding.data.mapper.system.PlatformRegistryProjectMapper">

  <resultMap id="BaseResultMap" type="com.coding.data.models.system.PlatformRegistryProject">
    <id     column="id"            jdbcType="VARCHAR"   property="id" />
    <result column="cluster_id"    jdbcType="VARCHAR"   property="clusterId" />
    <result column="tenant_id"     jdbcType="VARCHAR"   property="tenantId" />
    <result column="host"          jdbcType="VARCHAR"   property="host" />
    <result column="project_name"  jdbcType="VARCHAR"   property="projectName" />
    <result column="credential_id" jdbcType="VARCHAR"   property="credentialId" />
    <result column="robot_id"      jdbcType="BIGINT"    property="robotId" />
    <result column="status"        jdbcType="VARCHAR"   property="status" />
    <result column="message"       jdbcType="VARCHAR"   property="message" />
    <result column="last_synced_at" jdbcType="TIMESTAMP" property="lastSyncedAt" />
    <result column="created_at"    jdbcType="TIMESTAMP" property="createdAt" />
    <result column="updated_at"    jdbcType="TIMESTAMP" property="updatedAt" />
  </resultMap>

  <sql id="Base_Column_List">
    id, cluster_id, tenant_id, host, project_name, credential_id, robot_id,
    `status`, message, last_synced_at, created_at, updated_at
  </sql>

  <insert id="insert" parameterType="com.coding.data.models.system.PlatformRegistryProject">
    insert into platform_registry_project (id, cluster_id, tenant_id, host, project_name,
      credential_id, robot_id, `status`, message, last_synced_at, created_at, updated_at)
    values (#{id,jdbcType=VARCHAR}, #{clusterId,jdbcType=VARCHAR}, #{tenantId,jdbcType=VARCHAR},
      #{host,jdbcType=VARCHAR}, #{projectName,jdbcType=VARCHAR}, #{credentialId,jdbcType=VARCHAR},
      #{robotId,jdbcType=BIGINT}, #{status,jdbcType=VARCHAR}, #{message,jdbcType=VARCHAR},
      #{lastSyncedAt,jdbcType=TIMESTAMP}, #{createdAt,jdbcType=TIMESTAMP}, #{updatedAt,jdbcType=TIMESTAMP})
  </insert>

  <update id="updateResult" parameterType="com.coding.data.models.system.PlatformRegistryProject">
    update platform_registry_project
    set host = #{host,jdbcType=VARCHAR},
        project_name = #{projectName,jdbcType=VARCHAR},
        credential_id = #{credentialId,jdbcType=VARCHAR},
        robot_id = #{robotId,jdbcType=BIGINT},
        `status` = #{status,jdbcType=VARCHAR},
        message = #{message,jdbcType=VARCHAR},
        last_synced_at = #{lastSyncedAt,jdbcType=TIMESTAMP},
        updated_at = #{updatedAt,jdbcType=TIMESTAMP}
    where id = #{id,jdbcType=VARCHAR}
  </update>

  <select id="selectById" resultMap="BaseResultMap">
    select <include refid="Base_Column_List" /> from platform_registry_project where id = #{id}
  </select>

  <select id="selectByCluster" resultMap="BaseResultMap">
    select <include refid="Base_Column_List" /> from platform_registry_project
    where cluster_id = #{clusterId} order by project_name
  </select>

  <select id="selectByClusterAndTenant" resultMap="BaseResultMap">
    select <include refid="Base_Column_List" /> from platform_registry_project
    where cluster_id = #{clusterId}
    <choose>
      <when test="tenantId == null">and tenant_id is null</when>
      <otherwise>and tenant_id = #{tenantId}</otherwise>
    </choose>
  </select>

  <select id="selectByClusterAndProject" resultMap="BaseResultMap">
    select <include refid="Base_Column_List" /> from platform_registry_project
    where cluster_id = #{clusterId} and project_name = #{projectName}
  </select>

  <select id="selectByCredentialId" resultMap="BaseResultMap">
    select <include refid="Base_Column_List" /> from platform_registry_project
    where credential_id = #{credentialId}
  </select>

  <select id="selectByTenant" resultMap="BaseResultMap">
    select <include refid="Base_Column_List" /> from platform_registry_project
    where tenant_id = #{tenantId}
  </select>

  <delete id="deleteById">
    delete from platform_registry_project where id = #{id,jdbcType=VARCHAR}
  </delete>

</mapper>
```

- [ ] **Step 5: 编译并跑既有测试**

Run:

```bash
mvn -q -pl platform-data -am -DskipTests install
mvn -q -pl platform-api -am -DskipTests compile
```

Expected: BUILD SUCCESS。开发库上手工重放迁移后，`select count(*) from platform_registry_project` 为 0、`show columns from platform_credential like 'tenant_id'` 有一行（真集群验证时执行，见 Task 11）。

- [ ] **Step 6: Commit**

```bash
git add platform-data/src/main/resources/db/migration/V2026_10_27_1__harbor_registry.sql \
        platform-data/src/main/java/com/coding/data/models/system/PlatformCredential.java \
        platform-data/src/main/java/com/coding/data/models/system/PlatformRegistryProject.java \
        platform-data/src/main/java/com/coding/data/mapper/system/PlatformCredentialMapper.java \
        platform-data/src/main/java/com/coding/data/mapper/system/PlatformRegistryProjectMapper.java \
        platform-data/src/main/resources/mapper/system/PlatformCredentialMapper.xml \
        platform-data/src/main/resources/mapper/system/PlatformRegistryProjectMapper.xml
git commit -m "feat(registry): Harbor 项目台账与凭据租户归属（迁移与数据模型）"
```

---

### Task 2: Harbor chart 入库

**Files:**
- Create: `k8s-server/src/main/resources/charts/components/harbor/README.md`
- Create: `k8s-server/src/main/resources/charts/components/harbor/1.19.4/chart/`（上游 chart 原样）
- Create: `k8s-server/src/main/resources/charts/components/harbor/1.19.4/provenance.yaml`
- Create: `k8s-server/src/main/resources/charts/components/harbor/1.19.4/README.md`
- Modify: `k8s-server/src/main/resources/charts/components/README.md`（组件表加一行）

**Interfaces:**
- Produces: `ChartBundle` 能按 `("harbor", "1.19.4")` 找到 chart；`Chart.yaml` 的 `appVersion` 为 `2.15.4`。

- [ ] **Step 1: 拉取并入库**

```bash
mkdir -p k8s-server/src/main/resources/charts/components/harbor/1.19.4
cd k8s-server/src/main/resources/charts/components/harbor/1.19.4
helm pull harbor --repo https://helm.goharbor.io --version 1.19.4 --untar
mv harbor chart
cd -
helm pull harbor --repo https://helm.goharbor.io --version 1.19.4 --destination /tmp
sha256sum /tmp/harbor-1.19.4.tgz
```

Expected: 最后一行输出 `harbor-1.19.4.tgz` 的 sha256（把它填进下一步的 `digest`）。

- [ ] **Step 2: 写 provenance 与两个 README**

`.../1.19.4/provenance.yaml`：

```yaml
source: https://github.com/goharbor/harbor-helm
helm_repo: https://helm.goharbor.io
chart: harbor
version: 1.19.4
appVersion: "2.15.4"
fetched_at: 2026-10-27
fetch_command: helm pull harbor --repo https://helm.goharbor.io --version 1.19.4 --untar
digest: <上一步 sha256sum 的输出>
note: >-
  官方 chart 原样入库，**未修改任何模板**。平台的定制全部通过 recipe 的契约值 +
  安装表单（host / StorageClass / 容量 / Trivy 开关）完成。
  ⚠️ 1.19.x 把 database / redis / registry / jobservice / portal / core 的模板都放在
  本 chart 内（无子 chart）；`expose.type=route` 时不部署 nginx，HTTPRoute 直接指向
  harbor-core / harbor-portal 的 ClusterIP Service。
```

`.../1.19.4/README.md`：

```markdown
# Harbor 1.19.4（app 2.15.4）

- 来源：`https://helm.goharbor.io`，`helm pull harbor --version 1.19.4`
- chart 版本 1.19.4 / 应用版本 2.15.4（镜像 tag 均为 `v2.15.4`）
- 本版本注意：
  - `expose.type=route` 走 Gateway API HTTPRoute（`gateway.networking.k8s.io/v1`），
    不部署 nginx；backend 是同命名空间内的 `harbor-core` / `harbor-portal` Service。
  - `externalURL` 决定 token 服务返回给 docker 客户端的地址，必须与 route host 一致。
  - `existingSecretSecretKey` 期望的键名是 **`secretKey`**（不是 HARBOR_SECRET_KEY）；
    长度必须 16 字符。
  - chart 内的 database / redis 是普通模板（不是子 chart）。
```

`charts/components/harbor/README.md`（组件级）：

```markdown
# Harbor（镜像仓库，集群组件）

> **组件级 README（跨版本）**：平台怎么装、约定是什么、踩过哪些坑、加新版本要改哪里。
> 当前 chart 版本的事实见 `1.19.4/README.md`。

## 1. 概览

| 项 | 值 |
|---|---|
| 组件 code | `harbor` |
| 画像 | BUSINESS / MANAGEMENT |
| release / namespace | `platform-harbor` / `harbor` |
| 探测判据 | `harbor` Deployment `harbor-core`（镜像含 `goharbor/harbor-core`） |
| 平台功能 | `container-registry` |
| 暴露 | `istio-system/external-gateway` 的 HTTPRoute（HTTP） |
| 管理口令 | Secret `platform-harbor-secrets`（`HARBOR_ADMIN_PASSWORD` / `secretKey`） |
| 前置组件 | `istio`（route 必须有 Gateway） |

## 2. 装之前必须确认（DNS）

`host` 必须**同时**能被以下三边解析：

1. 集群内 Pod（流水线的 push/pull）；
2. 平台所在机器（仓库浏览与租户项目同步）；
3. 外部 docker/podman 客户端。

Harbor 的 token 服务按 `externalURL` 返回地址；三边不一致的典型症状是
「登录成功但 push 到错误地址 / 解析失败」。

## 3. 契约 values（🔒）

```yaml
fullnameOverride: harbor
expose:
  type: route
  tls: { enabled: false }
  route:
    parentRefs: [{ name: external-gateway, namespace: istio-system }]
    hosts: ["${harbor.host}"]
externalURL: "http://${harbor.host}"
existingSecretAdminPassword: platform-harbor-secrets
existingSecretSecretKey: platform-harbor-secrets
persistence: { enabled: true, resourcePolicy: keep }
database: { type: internal }
redis: { type: internal }
```

- `fullnameOverride`：探测认 `harbor-core`，不钉名字 release 名一变就永远「未安装」。
- `resourcePolicy: keep`：卸载 release 不删镜像与数据库 PVC。
- `database/redis: internal`：内网单集群，本期不接外部中间件。

## 4. 安装参数（表单，⚙️）

| 字段 | 默认 | 说明 |
|---|---|---|
| `host` | 无（必填） | 不带 scheme/path；写进 `externalURL` 与 route hosts |
| 5 块 PVC 的 StorageClass / 容量 | 空 / 20Gi、1Gi、5Gi、1Gi、5Gi | StorageClass 空 = 跟随集群默认 |
| `trivy.enabled` | `false` | 内网拉不到漏洞库，默认不装 |

## 5. 装完之后

```bash
kubectl -n harbor get deploy,sts,svc
kubectl -n harbor get httproute
kubectl -n harbor get secret platform-harbor-secrets -o jsonpath='{.data}' | jq 'keys'
```

- HTTPRoute `Accepted=True` 且 `AttachedRoutes=1`；
- 平台「集群组件」页 Harbor 行 →「镜像项目」能看到每租户的 project 与凭据状态。

## 6. 管理口令（不可重建自愈）

口令由 k8s-server 生成，只在首次初始化时写进 Harbor 库；`secretKey` 还用于加密库里的字段。
**Secret 丢失且 `harbor-core` 已存在时，平台会拒绝安装/升级** —— 重新生成的口令与库里的哈希
不一致，同步必然 401。此时需人工按 Harbor 官方方式重置管理员口令 / secretKey 后再操作。

## 7. 已知限制

- 资源占用较大（core/registry/db/redis/jobservice 合计约 1~2GB 内存起）；
- 镜像较大，慢网络下 `helm --timeout 10m` 可能不够，失败后重试（upgrade）；
- 本期不做：租户登录 Harbor、项目配额/保留策略/复制/签名、外部 DB、TLS 终止。

## 8. 加一个新版本怎么做

1. `helm pull harbor --repo https://helm.goharbor.io --version <x.y.z> --untar` → 入库
   `harbor/<x.y.z>/chart/` + `provenance.yaml` + `<x.y.z>/README.md`；
2. 核对 values 字段名（本期用到：`expose.type/route/tls`、`externalURL`、
   `existingSecretAdminPassword`、`existingSecretSecretKey`、
   `persistence.persistentVolumeClaim.<registry|jobservice.jobLog|database|redis|trivy>.{storageClass,size}`、
   `trivy.enabled`、各组件 `resources`）；
3. 改 `ComponentRecipeCatalog.HARBOR_RELEASES` 的 `chartVersion` 与 `withAppVersion`（新→旧）；
4. 跑 `ComponentRecipeCatalogTest`（会与 `Chart.yaml` 交叉校验）；
5. 真集群装一次，按 §5 验证；
6. 重建 k8s-server（chart 入镜像）+ platform-api。
```

`charts/components/README.md` 的表格加：

```markdown
| `harbor`（镜像仓库） | [harbor/README.md](harbor/README.md) |
```

- [ ] **Step 3: 渲染验证**

```bash
helm template platform-harbor k8s-server/src/main/resources/charts/components/harbor/1.19.4/chart \
  --namespace harbor \
  --set 'expose.route.hosts[0]=harbor.example.com' \
  --set externalURL=http://harbor.example.com \
  --set existingSecretAdminPassword=platform-harbor-secrets \
  --set existingSecretSecretKey=platform-harbor-secrets \
  > /tmp/harbor-render.yaml
grep -c "kind: HTTPRoute" /tmp/harbor-render.yaml
grep -c "name: harbor-core" /tmp/harbor-render.yaml
grep -c "kind: StatefulSet" /tmp/harbor-render.yaml
```

Expected: HTTPRoute 1 条；`harbor-core` 命中（Deployment 与服务）；StatefulSet 3 条（database / redis；trivy 默认关闭）。

- [ ] **Step 4: Commit**

```bash
git add k8s-server/src/main/resources/charts/components/harbor \
        k8s-server/src/main/resources/charts/components/README.md
git commit -m "feat(components): 入库 Harbor 1.19.4 chart 与组件文档"
```

---

### Task 3: 探测、recipe 与组件状态字段

**Files:**
- Modify: `platform-common/src/main/java/com/coding/common/constants/ProbeNamespaces.java`
- Modify: `platform-api/src/main/java/com/coding/platformapi/components/ClusterComponentCatalog.java`
- Modify: `platform-api/src/main/java/com/coding/platformapi/components/ComponentRecipeCatalog.java`
- Modify: `platform-api/src/main/java/com/coding/platformapi/components/ComponentRecipe.java`
- Create: `platform-api/src/main/java/com/coding/platformapi/models/HarborInstallRequest.java`
- Modify: `platform-api/src/main/java/com/coding/platformapi/models/ComponentStatusDTO.java`
- Modify: `platform-api/src/main/java/com/coding/platformapi/services/ClusterComponentService.java`
- Test: `platform-api/src/test/java/com/coding/platformapi/components/ClusterComponentCatalogTest.java`
- Test: `platform-api/src/test/java/com/coding/platformapi/components/ComponentRecipeCatalogTest.java`

**Interfaces:**
- Consumes: Task 2 的 chart。
- Produces:
  - `ComponentRecipe.harborRegistry()`（boolean，第 12 个 record 分量）
  - `ComponentStatusDTO.isHarborRegistry()` / `getHarborCurrent()`
  - `ComponentRecipeCatalog.recipeOf("harbor", profile)` 的两条 recipe

- [ ] **Step 1: 写失败测试（目录顺序 + recipe 契约）**

在 `ClusterComponentCatalogTest.theCatalogIsExactlyTheAgreedOnes` 的期望列表末尾加 `"harbor"`：

```java
        assertThat(catalog.all()).extracting(ComponentDef::code).containsExactly(
                "metrics-server", "prometheus-operator", "istio",
                "tekton", "calico", "cert-manager", "otel-operator", "seaweedfs", "harbor");
```

在 `ComponentRecipeCatalogTest` 末尾加：

```java
    @Test
    void harborUsesTheRouteExposureAndPinsTheProbeName() {
        ReleaseSpec spec = catalog.recipeOf("harbor", "BUSINESS").releases().get(0);
        assertThat(spec.releaseName()).isEqualTo("platform-harbor");
        assertThat(spec.namespace()).isEqualTo("harbor");
        assertThat(spec.primary()).isTrue();
        assertThat(spec.contractValues().get("fullnameOverride")).isEqualTo("harbor");
        assertThat(spec.contractValues().get("externalURL")).isEqualTo("http://${harbor.host}");
        @SuppressWarnings("unchecked")
        Map<String, Object> expose = (Map<String, Object>) spec.contractValues().get("expose");
        assertThat(expose).containsEntry("type", "route");
        assertThat(expose.get("route").toString())
                .contains("external-gateway").contains("${harbor.host}");
        // 管理口令与 secretKey 都走平台生成的 Secret，不落 values
        assertThat(spec.contractValues())
                .containsEntry("existingSecretAdminPassword", "platform-harbor-secrets")
                .containsEntry("existingSecretSecretKey", "platform-harbor-secrets");
        assertThat(catalog.recipeOf("harbor", "BUSINESS").harborRegistry()).isTrue();
        assertThat(catalog.recipeOf("harbor", "BUSINESS").requires()).containsExactly("istio");
        // Trivy 默认不装（内网拉不到漏洞库）
        assertThat(spec.editableValues()).containsEntry("trivy.enabled", false);
    }
```

- [ ] **Step 2: 跑测试确认失败**

Run:

```bash
mvn -q -pl platform-api -am -Dtest=ClusterComponentCatalogTest,ComponentRecipeCatalogTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: FAIL（`harbor` 不在目录 / recipe 为 null / `harborRegistry()` 方法不存在）。

- [ ] **Step 3: 改探测清单与目录**

`ProbeNamespaces.DEFAULT_CSV` 末尾追加 `,harbor`：

```java
    public static final String DEFAULT_CSV =
            "kube-system,istio-system,monitor,tekton-pipelines,tekton-operator,"
                    + "cert-manager,calico-system,tigera-operator,opentelemetry-operator-system,"
                    + "platform-storage,harbor";
```

`ClusterComponentCatalog.COMPONENTS` 末尾（`seaweedfs` 之后）加：

```java
            // 镜像仓库：chart 直接下发工作负载，判据只有活的工作负载。
            // 认 Deployment harbor-core（chart 的 fullnameOverride 钉成 harbor，release 名变了也不影响探测）。
            new ComponentDef("harbor", "Harbor（镜像仓库）", null,
                    List.of(new ComponentProbe("harbor", "deployment", "goharbor/harbor-core", "harbor-core")),
                    List.of("container-registry")));
```

- [ ] **Step 4: 加 `harborRegistry` 标记**

`ComponentRecipe` 的 record 分量末尾加 `boolean harborRegistry`，所有便捷构造器末尾补 `false`。当前主构造器最长的那些调用点（`prometheus-operator` 管理画像、`tekton`、`seaweedfs`、`otel`）末尾补 `false`。

`ComponentRecipeCatalog` 加（放在 seaweedfs 之后）：

```java
    // ==================== Harbor（镜像仓库） ====================

    /**
     * Harbor 作为可安装组件。
     *
     * <p>暴露走 Gateway API HTTPRoute（`expose.type=route`）：chart 在 route 模式下**不部署 nginx**，
     * 直接把 core / portal 的 ClusterIP Service 挂到 `istio-system/external-gateway`。
     * 前置组件 istio 是硬要求 —— 没有 Gateway，装了也访问不到。
     *
     * <p>管理口令与 secretKey 由平台生成进 Secret `platform-harbor-secrets`，
     * values 里只引用名字，因此不会进操作记录的 values 快照。
     */
    private static final List<ReleaseSpec> HARBOR_RELEASES = List.of(new ReleaseSpec(
            "platform-harbor", "harbor", "harbor", "1.19.4",
            harborContract(), harborEditable()).asPrimary().withAppVersion("2.15.4"));

    private static Map<String, Object> harborContract() {
        Map<String, Object> expose = new LinkedHashMap<>();
        expose.put("type", "route");
        expose.put("tls", Map.of("enabled", false));
        expose.put("route", Map.of(
                "parentRefs", List.of(Map.of("name", "external-gateway", "namespace", "istio-system")),
                "hosts", List.of("${harbor.host}")));

        Map<String, Object> contract = new LinkedHashMap<>();
        contract.put("fullnameOverride", "harbor");
        contract.put("expose", expose);
        contract.put("externalURL", "http://${harbor.host}");
        contract.put("existingSecretAdminPassword", "platform-harbor-secrets");
        contract.put("existingSecretSecretKey", "platform-harbor-secrets");
        contract.put("persistence", Map.of("enabled", true, "resourcePolicy", "keep"));
        contract.put("database", Map.of("type", "internal",
                "internal", Map.of("resources", harborResources("100m", "256Mi", "1", "1Gi"))));
        contract.put("redis", Map.of("type", "internal",
                "internal", Map.of("resources", harborResources("50m", "128Mi", "500m", "256Mi"))));
        contract.put("core", Map.of("resources", harborResources("100m", "256Mi", "1", "1Gi")));
        contract.put("portal", Map.of("resources", harborResources("50m", "128Mi", "500m", "256Mi")));
        contract.put("registry", Map.of("resources", harborResources("100m", "256Mi", "1", "1Gi")));
        contract.put("jobservice", Map.of("resources", harborResources("100m", "256Mi", "1", "1Gi")));
        contract.put("trivy", Map.of("resources", harborResources("100m", "256Mi", "1", "1Gi")));
        return contract;
    }

    private static Map<String, Object> harborResources(String cpuRequest, String memRequest,
                                                       String cpuLimit, String memLimit) {
        return Map.of("requests", Map.of("cpu", cpuRequest, "memory", memRequest),
                "limits", Map.of("cpu", cpuLimit, "memory", memLimit));
    }

    /** Harbor 的 PVC 字段是 chart 原生命名（`*.storageClass` / `*.size`），不是 Prometheus 那套 */
    private static Map<String, Object> harborEditable() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("persistence.persistentVolumeClaim.registry.storageClass", "");
        out.put("persistence.persistentVolumeClaim.registry.size", "20Gi");
        out.put("persistence.persistentVolumeClaim.jobservice.jobLog.storageClass", "");
        out.put("persistence.persistentVolumeClaim.jobservice.jobLog.size", "1Gi");
        out.put("persistence.persistentVolumeClaim.database.storageClass", "");
        out.put("persistence.persistentVolumeClaim.database.size", "5Gi");
        out.put("persistence.persistentVolumeClaim.redis.storageClass", "");
        out.put("persistence.persistentVolumeClaim.redis.size", "1Gi");
        out.put("persistence.persistentVolumeClaim.trivy.storageClass", "");
        out.put("persistence.persistentVolumeClaim.trivy.size", "5Gi");
        out.put("trivy.enabled", false);
        return out;
    }
```

在 `RECIPES` 里加：

```java
            Map.entry("harbor", Map.of(
                    BUSINESS, new ComponentRecipe(BUSINESS, HARBOR_RELEASES, List.of("istio"),
                            false, false, false, false, null, false, null, false, true),
                    MANAGEMENT, new ComponentRecipe(MANAGEMENT, HARBOR_RELEASES, List.of("istio"),
                            false, false, false, false, null, false, null, false, true))),
```

`EDITABLE_LABELS` 加：

```java
            Map.entry("persistence.persistentVolumeClaim.registry.storageClass", "镜像存储类"),
            Map.entry("persistence.persistentVolumeClaim.registry.size", "镜像存储容量"),
            Map.entry("persistence.persistentVolumeClaim.jobservice.jobLog.storageClass", "作业日志存储类"),
            Map.entry("persistence.persistentVolumeClaim.jobservice.jobLog.size", "作业日志容量"),
            Map.entry("persistence.persistentVolumeClaim.database.storageClass", "数据库存储类"),
            Map.entry("persistence.persistentVolumeClaim.database.size", "数据库容量"),
            Map.entry("persistence.persistentVolumeClaim.redis.storageClass", "Redis 存储类"),
            Map.entry("persistence.persistentVolumeClaim.redis.size", "Redis 容量"),
            Map.entry("persistence.persistentVolumeClaim.trivy.storageClass", "扫描器存储类"),
            Map.entry("persistence.persistentVolumeClaim.trivy.size", "扫描器容量"),
            Map.entry("trivy.enabled", "安装 Trivy 镜像扫描"),
```

`EDITABLE_GROUPS` 加：

```java
            Map.entry("persistence.persistentVolumeClaim.registry.storageClass", "存储（Harbor）"),
            Map.entry("persistence.persistentVolumeClaim.registry.size", "存储（Harbor）"),
            Map.entry("persistence.persistentVolumeClaim.jobservice.jobLog.storageClass", "存储（Harbor）"),
            Map.entry("persistence.persistentVolumeClaim.jobservice.jobLog.size", "存储（Harbor）"),
            Map.entry("persistence.persistentVolumeClaim.database.storageClass", "存储（Harbor）"),
            Map.entry("persistence.persistentVolumeClaim.database.size", "存储（Harbor）"),
            Map.entry("persistence.persistentVolumeClaim.redis.storageClass", "存储（Harbor）"),
            Map.entry("persistence.persistentVolumeClaim.redis.size", "存储（Harbor）"),
            Map.entry("persistence.persistentVolumeClaim.trivy.storageClass", "存储（Harbor）"),
            Map.entry("persistence.persistentVolumeClaim.trivy.size", "存储（Harbor）"),
            Map.entry("trivy.enabled", "镜像扫描"),
```

- [ ] **Step 5: 扩展字段类型推断**

`ClusterComponentService.typeOf` 在现有 `.storageClassName` 判断之后加：

```java
        // Harbor 的 PVC 字段名是 chart 原生键（storageClass / size），与 Prometheus 那套不同
        if (key.endsWith(".storageClass")) {
            return "storageClass";
        }
        if (key.endsWith(".size")) {
            return "size";
        }
```

- [ ] **Step 6: 组件状态 DTO 与填充（先建请求模型）**

新建 `HarborInstallRequest.java`（Task 4 与前端都用它）：

```java
package com.coding.platformapi.models;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Harbor 安装参数（仅 harborRegistry 的组件）。
 *
 * <p>{@code host} 是 Harbor 的访问域名（host[:port]，不带 scheme/path）。它同时决定
 * HTTPRoute 的 hostnames 与 {@code externalURL} —— 后者是 Harbor token 服务返回给
 * docker 客户端的地址，写错就会「登录成功但 push 失败」。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class HarborInstallRequest {
    private String host;
}
```

`ComponentStatusDTO` 末尾加：

```java
    /** 安装时是否要填 Harbor 的访问域名（host） */
    private boolean harborRegistry;
    /** **当前生效**的 Harbor 参数（上次成功操作的 {@code __platform.harbor}）；无记录 → null */
    private HarborInstallRequest harborCurrent;
```

`ClusterComponentService`：

在 `s.setSeaweedStorage(...)` 之后加：

```java
        s.setHarborRegistry(recipe != null && recipe.harborRegistry());
```

在 `if (s.isSeaweedStorage()) { s.setSeaweedCurrent(seaweedOf(platform)); }` 之后加：

```java
            if (s.isHarborRegistry()) {
                s.setHarborCurrent(harborOf(platform));
            }
```

加私有方法：

```java
    /** {@code __platform.harbor} → 编辑表单的当前值；没有记录返回 null */
    private static HarborInstallRequest harborOf(Map<String, Object> platform) {
        if (!(platform.get("harbor") instanceof Map<?, ?> m)) {
            return null;
        }
        return new HarborInstallRequest(asString(m.get("host")));
    }
```

（`HarborInstallRequest` 在 `models` 包，`ClusterComponentService` 要补 import
`com.coding.platformapi.models.HarborInstallRequest`。）

- [ ] **Step 7: 跑测试**

Run:

```bash
mvn -q -pl platform-api -am -Dtest=ClusterComponentCatalogTest,ComponentRecipeCatalogTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: PASS。

- [ ] **Step 8: Commit**

```bash
git add platform-common/src/main/java/com/coding/common/constants/ProbeNamespaces.java \
        platform-api/src/main/java/com/coding/platformapi/components \
        platform-api/src/main/java/com/coding/platformapi/models/ComponentStatusDTO.java \
        platform-api/src/main/java/com/coding/platformapi/services/ClusterComponentService.java \
        platform-api/src/test/java/com/coding/platformapi/components
git commit -m "feat(components): Harbor 探测、recipe 与组件状态字段"
```

---

### Task 4: 安装表单（host）、占位符解析与管理口令 Secret

**Files:**
- Modify: `platform-api/src/main/java/com/coding/platformapi/models/ComponentApplyRequest.java`
- Modify: `platform-api/src/main/java/com/coding/platformapi/services/ComponentInstallService.java`
- Modify: `platform-api/src/main/java/com/coding/platformapi/k8s/K8sComponentClient.java`
- Create: `platform-common/src/main/java/com/coding/common/models/k8s/dto/HarborSecretsRequestDTO.java`
- Create: `k8s-server/src/main/java/com/coding/k8sserver/components/HarborSecretObjects.java`
- Modify: `k8s-server/src/main/java/com/coding/k8sserver/controllers/cluster/ComponentInstallController.java`
- Test: `platform-api/src/test/java/com/coding/platformapi/services/ComponentInstallServiceTest.java`
- Test: `k8s-server/src/test/java/com/coding/k8sserver/components/HarborSecretObjectsTest.java`

**Interfaces:**
- Consumes: Task 3 的 `harborRegistry` 标记与 `${harbor.host}` 占位符。
- Produces:
  - `HarborInstallRequest(String host)`（`@Data @NoArgsConstructor @AllArgsConstructor`）
  - `ComponentApplyRequest.getHarbor()/setHarbor(HarborInstallRequest)`
  - `K8sComponentClient.applyHarborSecrets(String clusterId, String namespace, String secretName)`
  - `HarborSecretObjects.ADMIN_PASSWORD_KEY="HARBOR_ADMIN_PASSWORD"`、`SECRET_KEY="secretKey"`、`hasAllKeys(Map<String,String>)`、`build(namespace,name)`、`randomSecretKey()`

- [ ] **Step 1: 写失败测试（host 校验 + Secret 对象）**

`ComponentInstallServiceTest` 加：

```java
    /** 组件依赖的桩：harbor 的 recipe requires istio，安装前会查一次状态 */
    private static ComponentStatusDTO healthy(String code) {
        ComponentStatusDTO s = new ComponentStatusDTO();
        s.setCode(code);
        s.setState(ClusterComponentService.HEALTHY);
        return s;
    }

    @Test
    void harborRequiresAHostWithoutSchemeOrPath() {
        when(clusterService.requireProfile("c1")).thenReturn("BUSINESS");
        when(mapper.countRunning("c1", "harbor")).thenReturn(0);
        when(components.status("c1")).thenReturn(List.of(healthy("istio")));
        for (String bad : new String[]{"", "http://harbor.example.com", "harbor.example.com/foo",
                "Harbor.Example.com", "harbor .example.com"}) {
            ComponentApplyRequest req = new ComponentApplyRequest();
            req.setClusterId("c1");
            req.setComponentCode("harbor");
            req.setAction("INSTALL");
            req.setHarbor(new HarborInstallRequest(bad));
            assertThatThrownBy(() -> service().apply(req, "admin"))
                    .as(bad).hasMessageContaining("Harbor 访问域名");
        }
        verify(mapper, never()).insert(any());
    }

    @Test
    void harborHostIsResolvedIntoTheRouteAndExternalUrl() {
        when(clusterService.requireProfile("c1")).thenReturn("BUSINESS");
        when(clusterService.requireName("c1")).thenReturn("test-cluster");
        when(mapper.countRunning("c1", "harbor")).thenReturn(0);
        when(components.status("c1")).thenReturn(List.of(healthy("istio")));
        when(client.applyComponent(anyString(), anyString(), anyString(), any())).thenReturn(successResult());
        ComponentApplyRequest req = new ComponentApplyRequest();
        req.setClusterId("c1");
        req.setComponentCode("harbor");
        req.setAction("INSTALL");
        req.setHarbor(new HarborInstallRequest("harbor.example.com"));
        service().apply(req, "admin");

        ArgumentCaptor<List<ReleaseInstallSpecDTO>> specs = ArgumentCaptor.forClass(List.class);
        verify(client, timeout(3000)).applyComponent(anyString(), anyString(), anyString(), specs.capture());
        String values = specs.getValue().get(0).getValues().toString();
        assertThat(values)
                .contains("harbor.example.com")
                .doesNotContain("${harbor.host}");
        verify(client, timeout(3000)).applyHarborSecrets("c1", "harbor", "platform-harbor-secrets");
    }
```

`HarborSecretObjectsTest`：

```java
package com.coding.k8sserver.components;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class HarborSecretObjectsTest {

    @Test
    void buildsBothKeysWithTheRequiredLengths() {
        var secret = HarborSecretObjects.build("harbor", "platform-harbor-secrets");
        Map<String, String> data = secret.getStringData();
        assertThat(data).containsKeys(HarborSecretObjects.ADMIN_PASSWORD_KEY,
                HarborSecretObjects.SECRET_KEY);
        assertThat(data.get(HarborSecretObjects.SECRET_KEY)).hasSize(16);
        assertThat(data.get(HarborSecretObjects.ADMIN_PASSWORD_KEY).length()).isGreaterThanOrEqualTo(24);
    }

    @Test
    void hasAllKeysIsFalseWhenEitherKeyIsMissingOrBlank() {
        assertThat(HarborSecretObjects.hasAllKeys(Map.of(
                HarborSecretObjects.ADMIN_PASSWORD_KEY, "x",
                HarborSecretObjects.SECRET_KEY, "0123456789abcdef"))).isTrue();
        assertThat(HarborSecretObjects.hasAllKeys(Map.of(
                HarborSecretObjects.ADMIN_PASSWORD_KEY, "x"))).isFalse();
        assertThat(HarborSecretObjects.hasAllKeys(Map.of(
                HarborSecretObjects.ADMIN_PASSWORD_KEY, " ",
                HarborSecretObjects.SECRET_KEY, "0123456789abcdef"))).isFalse();
        assertThat(HarborSecretObjects.hasAllKeys(null)).isFalse();
    }

    @Test
    void secretKeyIsAlwaysSixteenChars() {
        for (int i = 0; i < 50; i++) {
            assertThat(HarborSecretObjects.randomSecretKey()).hasSize(16);
        }
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run:

```bash
mvn -q -pl platform-api -am -Dtest=ComponentInstallServiceTest -Dsurefire.failIfNoSpecifiedTests=false test
mvn -q -pl k8s-server -am -Dtest=HarborSecretObjectsTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: 编译失败（`HarborInstallRequest` / `HarborSecretObjects` 不存在）。

- [ ] **Step 3: 请求模型字段与 API 载荷**

`HarborInstallRequest` 已在 Task 3 建好（`platform-api/models/HarborInstallRequest.java`），本步只加引用。

`ComponentApplyRequest`：加字段

```java
    /** Harbor 安装参数（仅 harborRegistry 的组件：访问域名） */
    private HarborInstallRequest harbor;
```

并把所有便捷构造器的 `this(...)` 末尾补一个 `null`（主构造器变为 13 参）。

- [ ] **Step 4: 安装流程接入 host**

`ComponentInstallService`：

在 `apply()` 里（`seaweed` 解析之后）加：

```java
        // Harbor：host 必填 —— 它同时是 route 的 hostnames 与 externalURL
        HarborInstallRequest harbor = recipe.harborRegistry()
                ? resolveHarbor(req.getHarbor(), previousPlatform.get("harbor"))
                : null;
        if (recipe.harborRegistry()) {
            requireHarborHost(harbor);
        }
```

`mergeValues` 之后无需额外拼 values（host 走占位符）；`resolveValues` 的签名加 `HarborInstallRequest harbor`，并在 context 里加：

```java
        context.put("harbor.host", harbor != null && harbor.getHost() != null ? harbor.getHost() : "");
```

调用点：

```java
        List<ReleaseSpec> effective = resolveValues(merged, req.getClusterId(), thanosUrl, calico,
                tektonProfile, otel, harbor);
```

`run(...)` 的签名加 `HarborInstallRequest harbor`，并在 helm 之前加：

```java
            // Harbor 的管理口令与 secretKey 必须在 helm 之前就位；values 里只引用 Secret 名
            if (harbor != null) {
                client.applyHarborSecrets(clusterId, "harbor", "platform-harbor-secrets");
            }
```

worker 调用处补 `harbor`。

快照：`snapshotWithPlatformInputs(...)` 签名加 `HarborInstallRequest harbor`，并在写入 `__platform` 时：

```java
        if (recipe.harborRegistry() && harbor != null) {
            platform.put("harbor", Map.of("host", harbor.getHost() == null ? "" : harbor.getHost()));
        }
```

解析与校验：

```java
    /**
     * Harbor 访问域名：不带 scheme / path / 空白；允许 host[:port]。
     *
     * <p>为什么必须严：这个值会同时写进 HTTPRoute 的 hostnames 与 {@code externalURL}
     * （Harbor token 服务按后者返回地址）。写成 {@code http://harbor.example.com} 会得到
     * {@code http://http://...}，装出来的 Harbor 能启动但 push 全失败。
     */
    private static final java.util.regex.Pattern HARBOR_HOST =
            java.util.regex.Pattern.compile("^[a-z0-9]([a-z0-9.-]*[a-z0-9])?(:[0-9]{1,5})?$");

    @SuppressWarnings("unchecked")
    private static HarborInstallRequest resolveHarbor(HarborInstallRequest requested, Object previous) {
        Map<String, Object> prev = previous instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
        String host = firstNonBlank(requested == null ? null : requested.getHost(),
                asString(prev.get("host")));
        return new HarborInstallRequest(host);
    }

    private static void requireHarborHost(HarborInstallRequest harbor) {
        String host = harbor == null ? null : harbor.getHost();
        if (host == null || host.isBlank() || !HARBOR_HOST.matcher(host.trim()).matches()) {
            throw new CloudPlatformException(EnumResponseType.ERROR,
                    "请填写 Harbor 访问域名（如 harbor.example.com，不带 http:// 与路径）");
        }
    }
```

注意 `requireHarborHost` 通过后要把 `host` 归一成 trim 值：在 `resolveHarbor` 里 `host = host == null ? null : host.trim()`。

- [ ] **Step 5: k8s-server 侧口令 Secret**

`HarborSecretsRequestDTO.java`：

```java
package com.coding.common.models.k8s.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Harbor 管理口令与 secretKey 的 Secret（S3.6 组件安装用）。
 *
 * <p>口令由 k8s-server 在集群内生成、只写进 Secret；platform-api 只在真正调 Harbor API
 * 时读取它。已存在（两个键都在）时原样保留，升级不轮换。
 */
@Data
@NoArgsConstructor
public class HarborSecretsRequestDTO {
    private String clusterId;
    /** Secret 所在命名空间（= Harbor 的 release 命名空间） */
    private String namespace;
    /** Secret 名（平台固定 platform-harbor-secrets） */
    private String secretName;
}
```

`HarborSecretObjects.java`：

```java
package com.coding.k8sserver.components;

import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;

import java.security.SecureRandom;
import java.util.Map;

/**
 * Harbor 的管理口令与加密 secretKey（{@code platform-harbor-secrets}）。
 *
 * <p>为什么平台生成：{@code harborAdminPassword} 写进 values 会以明文进入操作记录的
 * values 快照；{@code secretKey} 还用于加密 Harbor 库里的字段。
 *
 * <p>⚠️ 只能在**首次安装**时生成：Harbor 只在初始化时把口令写库，
 * {@code secretKey} 变了会让库里已加密的字段失效。调用方必须先确认 {@code harbor-core}
 * 不存在（见 {@code ComponentInstallController.harborSecrets}）。
 */
public final class HarborSecretObjects {

    public static final String ADMIN_PASSWORD_KEY = "HARBOR_ADMIN_PASSWORD";
    public static final String SECRET_KEY = "secretKey";

    private static final SecureRandom RANDOM = new SecureRandom();

    private HarborSecretObjects() {
    }

    /** 两个键都存在且非空白 → 视为已初始化，不覆盖 */
    public static boolean hasAllKeys(Map<String, String> data) {
        return data != null
                && data.get(ADMIN_PASSWORD_KEY) != null && !data.get(ADMIN_PASSWORD_KEY).isBlank()
                && data.get(SECRET_KEY) != null && !data.get(SECRET_KEY).isBlank();
    }

    public static Secret build(String namespace, String name) {
        return new SecretBuilder()
                .withNewMetadata()
                .withName(name)
                .withNamespace(namespace)
                .addToLabels("platform.coding/managed", "true")
                .addToLabels("platform.coding/component", "harbor")
                .endMetadata()
                .withType("Opaque")
                .addToStringData(ADMIN_PASSWORD_KEY, randomPassword())
                .addToStringData(SECRET_KEY, randomSecretKey())
                .build();
    }

    /** 24 字节随机 → base64url（32 字符） */
    static String randomPassword() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Harbor 硬要求 16 字符：12 字节随机 → 16 个 base64 字符 */
    public static String randomSecretKey() {
        byte[] bytes = new byte[12];
        RANDOM.nextBytes(bytes);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
```

`ComponentInstallController` 加（放在 `seaweedAdminSecret` 方法之后）：

```java
    /**
     * Harbor 的管理口令与 secretKey（S3.6）：首次安装时生成随机值，已存在则原样保留。
     *
     * <p>⚠️ 已初始化（{@code harbor-core} 存在）但 Secret 缺失时**拒绝**：
     * 重新生成的口令与 Harbor 库里的哈希不一致（secretKey 还牵连库里已加密字段），
     * 同步必然 401。这时只能人工按 Harbor 官方方式重置后再安装。
     */
    @PostMapping("/harbor-secrets")
    @Operation(summary = "确保 Harbor 管理口令 Secret 存在（首次生成，已初始化后丢失则拒绝）",
            description = "幂等；由 platform-api 在安装/升级 Harbor 前调用")
    public ResponseData<Void> harborSecrets(@RequestBody HarborSecretsRequestDTO request) {
        accessResolver.assertClusterAccess(request.getClusterId());
        if (isBlank(request.getNamespace()) || isBlank(request.getSecretName())) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "Harbor Secret 参数不完整");
        }
        var client = clientFactory.getAdminClient(request.getClusterId());
        Secret existing = client.secrets().inNamespace(request.getNamespace())
                .withName(request.getSecretName()).get();
        if (existing != null && HarborSecretObjects.hasAllKeys(existing.getData())) {
            return new ResponseData<>();   // 已存在：升级/重装沿用原口令，不轮换
        }
        boolean initialized = client.apps().deployments()
                .inNamespace(request.getNamespace()).withName("harbor-core").get() != null;
        if (initialized) {
            throw new CloudPlatformException(EnumResponseType.ERROR,
                    "管理口令 Secret 丢失，Harbor 已初始化：重新生成的口令与库里的哈希不一致。"
                            + "请人工按 Harbor 官方方式重置管理员口令 / secretKey 后再安装");
        }
        client.secrets().inNamespace(request.getNamespace())
                .resource(HarborSecretObjects.build(request.getNamespace(), request.getSecretName()))
                .fieldManager(ServerSideApply.FIELD_MANAGER).forceConflicts().serverSideApply();
        return new ResponseData<>();
    }
```

补 import：`HarborSecretsRequestDTO`、`HarborSecretObjects`。

`K8sComponentClient` 加：

```java
    /**
     * Harbor 的管理口令与 secretKey：首次安装时由 k8s-server 生成随机值（已存在不轮换）。
     * 口令不经过本层，也不会进 values 快照。
     */
    public void applyHarborSecrets(String clusterId, String namespace, String secretName) {
        HarborSecretsRequestDTO body = new HarborSecretsRequestDTO();
        body.setClusterId(clusterId);
        body.setNamespace(namespace);
        body.setSecretName(secretName);
        gateway.exchange(HttpMethod.POST, "/components/harbor-secrets", null, body,
                gateway.responseType(Void.class));
    }
```

- [ ] **Step 6: 跑测试**

Run:

```bash
mvn -q -pl k8s-server -am -Dtest=HarborSecretObjectsTest -Dsurefire.failIfNoSpecifiedTests=false test
mvn -q -pl platform-api -am -Dtest=ComponentInstallServiceTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: PASS。

- [ ] **Step 7: Commit**

```bash
git add platform-api/src/main/java/com/coding/platformapi/models/HarborInstallRequest.java \
        platform-api/src/main/java/com/coding/platformapi/models/ComponentApplyRequest.java \
        platform-api/src/main/java/com/coding/platformapi/services/ComponentInstallService.java \
        platform-api/src/main/java/com/coding/platformapi/k8s/K8sComponentClient.java \
        platform-common/src/main/java/com/coding/common/models/k8s/dto/HarborSecretsRequestDTO.java \
        k8s-server/src/main/java/com/coding/k8sserver/components/HarborSecretObjects.java \
        k8s-server/src/main/java/com/coding/k8sserver/controllers/cluster/ComponentInstallController.java \
        k8s-server/src/test/java/com/coding/k8sserver/components/HarborSecretObjectsTest.java \
        platform-api/src/test/java/com/coding/platformapi/services/ComponentInstallServiceTest.java
git commit -m "feat(components): Harbor 安装表单 host 解析与管理口令 Secret"
```

---

### Task 5: Harbor 命名规则与 REST 客户端

**Files:**
- Create: `platform-api/src/main/java/com/coding/platformapi/registry/HarborNaming.java`
- Create: `platform-api/src/main/java/com/coding/platformapi/registry/HarborAdminClient.java`
- Test: `platform-api/src/test/java/com/coding/platformapi/registry/HarborNamingTest.java`
- Test: `platform-api/src/test/java/com/coding/platformapi/registry/HarborAdminClientTest.java`

**Interfaces:**
- Produces:
  - `HarborNaming.projectName(String serviceAccount) → "t-"+sa`
  - `HarborNaming.PLATFORM_PROJECT = "platform"`
  - `HarborNaming.credentialName(String project, String clusterId)`
  - `HarborNaming.robotName() → "ci"`
  - `HarborAdminClient.ensureProject(String baseUrl, String user, String pass, String projectName) → long projectId`
  - `HarborAdminClient.createRobot(String baseUrl, String user, String pass, String projectName) → HarborRobot(long id, String name, String secret)`
  - `HarborAdminClient.deleteRobot(String baseUrl, String user, String pass, long robotId)`
  - `HarborAdminClient.deleteRobotByName(String baseUrl, String user, String pass, long projectId, String robotName)`
  - `HarborAdminClient.robotNameInHarbor(String projectName) → "robot$"+project+"+ci"`

- [ ] **Step 1: 写失败测试**

`HarborNamingTest`：

```java
package com.coding.platformapi.registry;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HarborNamingTest {

    @Test
    void tenantProjectIsPrefixedWithT() {
        assertThat(HarborNaming.projectName("acme")).isEqualTo("t-acme");
        assertThat(HarborNaming.PLATFORM_PROJECT).isEqualTo("platform");
    }

    @Test
    void credentialNameCarriesTheClusterToAvoidCrossClusterCollisions() {
        assertThat(HarborNaming.credentialName("t-acme", "01HXABCDEF"))
                .isEqualTo("Harbor t-acme@01HXABCDEF");
    }

    @Test
    void longProjectNamesAreClippedButStayUnique() {
        String project = "t-" + "a".repeat(80);
        String a = HarborNaming.credentialName(project, "01HXABCDEF");
        String b = HarborNaming.credentialName(project + "b", "01HXABCDEF");
        assertThat(a).hasSizeLessThanOrEqualTo(64);
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void robotNameInHarborFollowsThePrefixAndPlusConvention() {
        assertThat(HarborNaming.robotNameInHarbor("t-acme")).isEqualTo("robot$t-acme+ci");
    }
}
```

`HarborAdminClientTest`（MockRestServiceServer，照 `RegistryHttpClientTest` 的形状）：

```java
package com.coding.platformapi.registry;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpMethod.DELETE;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.NOT_FOUND;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class HarborAdminClientTest {

    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final HarborAdminClient client = new HarborAdminClient(builder.build());

    private static final String BASE = "http://harbor.example.com";
    private static final String PROJECT = "t-acme";

    @Test
    void existingProjectIsReused() {
        server.expect(requestTo(BASE + "/api/v2.0/projects/" + PROJECT))
                .andExpect(method(GET))
                .andRespond(withSuccess("{\"project_id\":7,\"name\":\"t-acme\"}", APPLICATION_JSON));

        assertThat(client.ensureProject(BASE, "admin", "pw", PROJECT)).isEqualTo(7L);
    }

    @Test
    void missingProjectIsCreatedThenReadBackForItsId() {
        server.expect(requestTo(BASE + "/api/v2.0/projects/" + PROJECT))
                .andExpect(method(GET))
                .andRespond(withStatus(NOT_FOUND));
        server.expect(requestTo(BASE + "/api/v2.0/projects"))
                .andExpect(method(POST))
                .andExpect(content().json("{\"project_name\":\"t-acme\",\"public\":false}"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.CREATED));
        server.expect(requestTo(BASE + "/api/v2.0/projects/" + PROJECT))
                .andExpect(method(GET))
                .andRespond(withSuccess("{\"project_id\":9,\"name\":\"t-acme\"}", APPLICATION_JSON));

        assertThat(client.ensureProject(BASE, "admin", "pw", PROJECT)).isEqualTo(9L);
    }

    @Test
    void createRobotReturnsTheOneTimeSecret() {
        server.expect(requestTo(BASE + "/api/v2.0/robots"))
                .andExpect(method(POST))
                .andExpect(content().json("""
                        {"name":"ci","level":"project","duration":-1,
                         "permissions":[{"kind":"project","namespace":"t-acme",
                           "access":[{"resource":"repository","action":"push"},
                                     {"resource":"repository","action":"pull"}]}]}"""))
                .andRespond(withSuccess("{\"id\":42,\"name\":\"robot$t-acme+ci\",\"secret\":\"s3cr3t\"}",
                        APPLICATION_JSON));

        HarborAdminClient.HarborRobot robot = client.createRobot(BASE, "admin", "pw", PROJECT);
        assertThat(robot.id()).isEqualTo(42L);
        assertThat(robot.name()).isEqualTo("robot$t-acme+ci");
        assertThat(robot.secret()).isEqualTo("s3cr3t");
    }

    @Test
    void robotConflictMeansWeDeleteByNameThenCreate() {
        // 已存在的同名 robot（上一次同步留下的）：列表里能看到，删掉之后才能建
        server.expect(requestTo(BASE + "/api/v2.0/robots?page_size=100&q=Level%3Dproject,ProjectID%3D7"))
                .andExpect(method(GET))
                .andRespond(withSuccess("""
                        [{"id":41,"name":"robot$t-acme+ci"},{"id":43,"name":"robot$t-acme+other"}]
                        """, APPLICATION_JSON));
        server.expect(requestTo(BASE + "/api/v2.0/robots/41"))
                .andExpect(method(DELETE))
                .andRespond(withSuccess());

        client.deleteRobotByName(BASE, "admin", "pw", 7L, "robot$t-acme+ci");
        server.verify();
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run:

```bash
mvn -q -pl platform-api -am -Dtest=HarborNamingTest,HarborAdminClientTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: 编译失败（两个类不存在）。

- [ ] **Step 3: 实现 `HarborNaming`**

```java
package com.coding.platformapi.registry;

/** Harbor 侧的名字规则（项目 / 机器人 / 平台凭据名）。纯函数，便于单测。 */
public final class HarborNaming {

    /** 平台共享项目（管理员流水线用） */
    public static final String PLATFORM_PROJECT = "platform";

    /** 机器人账号名（Harbor 会加前缀：robot$<project>+ci） */
    public static final String ROBOT_NAME = "ci";

    private static final int CREDENTIAL_NAME_MAX = 64;

    private HarborNaming() {
    }

    /** 租户项目：serviceAccount 已全局唯一且是小写 K8s 名 */
    public static String projectName(String serviceAccount) {
        return "t-" + serviceAccount;
    }

    public static String robotNameInHarbor(String projectName) {
        return "robot$" + projectName + "+" + ROBOT_NAME;
    }

    /**
     * 凭据行名：`Harbor <project>@<clusterId>`。带 clusterId 是因为**同名项目可能出现在多个集群**，
     * 而 `platform_credential.name` 全局唯一。
     *
     * <p>超长时裁项目名并补 4 位 hash：`Harbor <前 35 字>~<hash>@<clusterId>`（总长 64）。
     */
    public static String credentialName(String projectName, String clusterId) {
        String base = "Harbor " + projectName + "@" + clusterId;
        if (base.length() <= CREDENTIAL_NAME_MAX) {
            return base;
        }
        String hash = Integer.toHexString(Math.abs(projectName.hashCode()));
        int keep = CREDENTIAL_NAME_MAX - "Harbor ".length() - 1 - hash.length() - 1 - clusterId.length();
        int end = Math.max(1, Math.min(projectName.length(), keep));
        return "Harbor " + projectName.substring(0, end) + "~" + hash + "@" + clusterId;
    }
}
```

- [ ] **Step 4: 实现 `HarborAdminClient`**

```java
package com.coding.platformapi.registry;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.platformapi.outbound.OutboundProxy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Harbor 的**写入**出站（建项目 / 建删机器人）。与 {@link RegistryHttpClient} 同一套规矩：
 * 显式超时、不跟随重定向、Basic 认证、内网直连（{@link OutboundProxy} 白名单决定代理）。
 *
 * <p>只被 {@code HarborRegistryService} 使用；凭据（管理口令 / robot secret）不落日志。
 */
@Slf4j
@Component
public class HarborAdminClient {

    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int READ_TIMEOUT_MS = 10_000;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final RestClient restClient;

    @Autowired
    public HarborAdminClient(OutboundProxy proxy) {
        HttpClient http = HttpClient.newBuilder()
                .proxy(proxy.selector())
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofMillis(CONNECT_TIMEOUT_MS))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofMillis(READ_TIMEOUT_MS));
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    /** 测试用 */
    HarborAdminClient(RestClient restClient) {
        this.restClient = restClient;
    }

    public record HarborRobot(long id, String name, String secret) {
    }

    /**
     * 确保项目存在并返回 project_id。
     *
     * <p>GET 404 → POST（201）→ 再 GET 取 id（POST 只回 Location，不保证有 body）。
     * 并发建同名项目时 POST 可能 409 —— 再 GET 一次即拿到。
     */
    public long ensureProject(String baseUrl, String user, String pass, String projectName) {
        String url = baseUrl + "/api/v2.0/projects/" + projectName;
        Long id = projectId(baseUrl, user, pass, projectName);
        if (id != null) {
            return id;
        }
        try {
            restClient.post().uri(baseUrl + "/api/v2.0/projects")
                    .header(HttpHeaders.AUTHORIZATION, basic(user, pass))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("project_name", projectName, "public", false))
                    .retrieve().toBodilessEntity();
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() != 409) {
                throw failure("创建 Harbor 项目失败", projectName, e);
            }
        }
        Long created = projectId(baseUrl, user, pass, projectName);
        if (created == null) {
            throw new CloudPlatformException(EnumResponseType.ERROR,
                    "Harbor 项目创建后仍查不到：" + projectName);
        }
        return created;
    }

    /** 建机器人账号（project 级，push+pull，不过期）。返回的 secret **只出现这一次**。 */
    public HarborRobot createRobot(String baseUrl, String user, String pass, String projectName) {
        Map<String, Object> body = Map.of(
                "name", HarborNaming.ROBOT_NAME,
                "level", "project",
                "duration", -1,
                "permissions", List.of(Map.of(
                        "kind", "project",
                        "namespace", projectName,
                        "access", List.of(
                                Map.of("resource", "repository", "action", "push"),
                                Map.of("resource", "repository", "action", "pull")))));
        try {
            String raw = restClient.post().uri(baseUrl + "/api/v2.0/robots")
                    .header(HttpHeaders.AUTHORIZATION, basic(user, pass))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve().body(String.class);
            JsonNode node = JSON.readTree(raw == null ? "{}" : raw);
            String secret = node.path("secret").asString(null);
            if (secret == null || secret.isBlank()) {
                throw new CloudPlatformException(EnumResponseType.ERROR,
                        "Harbor 机器人账号创建成功但没有返回 secret（无法再取回，请重建）");
            }
            return new HarborRobot(node.path("id").asLong(), node.path("name").asString(null), secret);
        } catch (RestClientResponseException e) {
            throw failure("创建 Harbor 机器人账号失败", projectName, e);
        } catch (CloudPlatformException e) {
            throw e;
        } catch (Exception e) {
            throw new CloudPlatformException(EnumResponseType.ERROR,
                    "解析 Harbor 机器人账号响应失败：" + e.getMessage(), e);
        }
    }

    /** 删机器人账号；404 视为已经不在（幂等） */
    public void deleteRobot(String baseUrl, String user, String pass, long robotId) {
        try {
            restClient.method(HttpMethod.DELETE).uri(baseUrl + "/api/v2.0/robots/" + robotId)
                    .header(HttpHeaders.AUTHORIZATION, basic(user, pass))
                    .retrieve().toBodilessEntity();
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) {
                return;
            }
            throw failure("删除 Harbor 机器人账号失败", String.valueOf(robotId), e);
        }
    }

    /** 按 Harbor 内的完整名字删机器人（台账丢了 robot_id 时的兜底） */
    public void deleteRobotByName(String baseUrl, String user, String pass, long projectId,
                                  String harborRobotName) {
        for (long id : robotIds(baseUrl, user, pass, projectId, harborRobotName)) {
            deleteRobot(baseUrl, user, pass, id);
        }
    }

    private List<Long> robotIds(String baseUrl, String user, String pass, long projectId,
                                String harborRobotName) {
        String url = baseUrl + "/api/v2.0/robots?page_size=100&q=Level%3Dproject,ProjectID%3D" + projectId;
        try {
            String raw = restClient.get().uri(URI.create(url))
                    .header(HttpHeaders.AUTHORIZATION, basic(user, pass))
                    .retrieve().body(String.class);
            JsonNode nodes = JSON.readTree(raw == null ? "[]" : raw);
            List<Long> out = new ArrayList<>();
            for (JsonNode n : nodes) {
                if (harborRobotName.equals(n.path("name").asString(null))) {
                    out.add(n.path("id").asLong());
                }
            }
            return out;
        } catch (RestClientResponseException e) {
            throw failure("查询 Harbor 机器人账号失败", String.valueOf(projectId), e);
        } catch (Exception e) {
            throw new CloudPlatformException(EnumResponseType.ERROR,
                    "解析 Harbor 机器人账号列表失败：" + e.getMessage(), e);
        }
    }

    private Long projectId(String baseUrl, String user, String pass, String projectName) {
        try {
            String raw = restClient.get().uri(baseUrl + "/api/v2.0/projects/" + projectName)
                    .header(HttpHeaders.AUTHORIZATION, basic(user, pass))
                    .retrieve().body(String.class);
            JsonNode node = JSON.readTree(raw == null ? "{}" : raw);
            long id = node.path("project_id").asLong(0);
            return id == 0 ? null : id;
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) {
                return null;
            }
            throw failure("查询 Harbor 项目失败", projectName, e);
        } catch (Exception e) {
            throw new CloudPlatformException(EnumResponseType.ERROR,
                    "解析 Harbor 项目响应失败：" + e.getMessage(), e);
        }
    }

    private static String basic(String user, String pass) {
        return "Basic " + Base64.getEncoder()
                .encodeToString((user + ":" + pass).getBytes(StandardCharsets.UTF_8));
    }

    private static CloudPlatformException failure(String what, String target,
                                                  RestClientResponseException e) {
        // 不打印请求头（含 Basic）与 body 原文
        return new CloudPlatformException(EnumResponseType.ERROR,
                what + "：" + target + "（HTTP " + e.getStatusCode().value() + "）");
    }
}
```

- [ ] **Step 5: 跑测试**

Run:

```bash
mvn -q -pl platform-api -am -Dtest=HarborNamingTest,HarborAdminClientTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: PASS。

- [ ] **Step 6: Commit**

```bash
git add platform-api/src/main/java/com/coding/platformapi/registry/HarborNaming.java \
        platform-api/src/main/java/com/coding/platformapi/registry/HarborAdminClient.java \
        platform-api/src/test/java/com/coding/platformapi/registry/HarborNamingTest.java \
        platform-api/src/test/java/com/coding/platformapi/registry/HarborAdminClientTest.java
git commit -m "feat(registry): Harbor 命名规则与项目/机器人写入客户端"
```

---

### Task 6: 凭据托管写入与 Harbor 项目同步服务

**Files:**
- Modify: `platform-api/src/main/java/com/coding/platformapi/services/CredentialService.java`
- Create: `platform-api/src/main/java/com/coding/platformapi/services/HarborRegistryService.java`
- Create: `platform-api/src/main/java/com/coding/platformapi/models/RegistryProjectView.java`
- Test: `platform-api/src/test/java/com/coding/platformapi/services/CredentialServiceTest.java`
- Test: `platform-api/src/test/java/com/coding/platformapi/services/HarborRegistryServiceTest.java`

**Interfaces:**
- Consumes: Task 1 mapper、Task 5 client/naming。
- Produces:
  - `CredentialService.upsertManagedRegistry(String name, String endpoint, String tenantId, String username, String secret, String description) → String credentialId`
  - `HarborRegistryService.SyncItem(String tenantId, String tenantName, String project, String status, String message)`
  - `HarborRegistryService.SyncResult(String clusterId, String host, List<SyncItem> items, int failed)`
  - `HarborRegistryService.ensureAll(String clusterId) → SyncResult`（等价 `ensureAll(clusterId, false)`）
  - `HarborRegistryService.ensureAll(String clusterId, boolean force) → SyncResult`
  - `HarborRegistryService.ensureTenant(String clusterId, String tenantId, boolean force) → SyncItem`
  - `HarborRegistryService.ensureForTenantOnInstalledClusters(String tenantId)`
  - `HarborRegistryService.rebuild(String clusterId, String tenantId) → SyncItem`
  - `HarborRegistryService.onTenantDeleted(String tenantId)`
  - `HarborRegistryService.onTenantStatusChanged(String tenantId, boolean enabled)`
  - `HarborRegistryService.list(String clusterId) → List<RegistryProjectView>`
  - `HarborRegistryService.harborHost(String clusterId) → String|null`

- [ ] **Step 1: 写失败测试**

`CredentialServiceTest` 加两个字段（与现有 `mapper` / `probeClient` 同风格，不用 `private final`）：

```java
    PlatformTenantMapper tenantMapper = mock(PlatformTenantMapper.class);
    PlatformRegistryProjectMapper projectMapper = mock(PlatformRegistryProjectMapper.class);
```
构造处改为 `new CredentialService(mapper, cipher, probeClient, dialects, tenantMapper, projectMapper)`。
补 import：`com.coding.data.mapper.auth.PlatformTenantMapper`、`com.coding.data.mapper.system.PlatformRegistryProjectMapper`。

```java
    @Test
    void managedRegistryCredentialIsHarborSourcedTenantScopedAndPipelineUsable() {
        when(mapper.selectByName("Harbor t-acme@c1")).thenReturn(null);
        String id = svc.upsertManagedRegistry("Harbor t-acme@c1", "harbor.example.com", "tenant-1",
                "robot$t-acme+ci", "s3cr3t", "Harbor 自动创建");

        ArgumentCaptor<PlatformCredential> captor = ArgumentCaptor.forClass(PlatformCredential.class);
        verify(mapper).insert(captor.capture());
        PlatformCredential saved = captor.getValue();
        assertThat(saved.getType()).isEqualTo("HARBOR");
        assertThat(saved.getAuthType()).isEqualTo("BASIC");
        assertThat(saved.getSource()).isEqualTo("HARBOR");
        assertThat(saved.getTenantId()).isEqualTo("tenant-1");
        assertThat(saved.getUsableByPipelines()).isEqualTo(1);
        assertThat(saved.getEnabled()).isEqualTo(1);
        assertThat(saved.getSecretEncrypted()).startsWith("v1:");
        assertThat(id).isEqualTo(saved.getId());
    }
```

`HarborRegistryServiceTest`（mock 依赖，覆盖幂等与重建）：

```java
package com.coding.platformapi.services;

import com.coding.data.mapper.auth.PlatformTenantMapper;
import com.coding.data.mapper.system.PlatformComponentOperationMapper;
import com.coding.data.mapper.system.PlatformCredentialMapper;
import com.coding.data.mapper.system.PlatformRegistryProjectMapper;
import com.coding.data.models.auth.PlatformTenant;
import com.coding.data.models.k8s.K8sCluster;
import com.coding.data.models.system.PlatformComponentOperation;
import com.coding.data.models.system.PlatformCredential;
import com.coding.data.models.system.PlatformRegistryProject;
import com.coding.platformapi.registry.HarborAdminClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class HarborRegistryServiceTest {

    private final PlatformComponentOperationMapper operations = mock(PlatformComponentOperationMapper.class);
    private final PlatformTenantMapper tenants = mock(PlatformTenantMapper.class);
    private final PlatformRegistryProjectMapper projects = mock(PlatformRegistryProjectMapper.class);
    private final PlatformCredentialMapper credentials = mock(PlatformCredentialMapper.class);
    private final CredentialService credentialService = mock(CredentialService.class);
    private final SecretService secrets = mock(SecretService.class);
    private final K8sProvisioningService provisioning = mock(K8sProvisioningService.class);
    private final HarborAdminClient harbor = mock(HarborAdminClient.class);

    private HarborRegistryService svc;

    @BeforeEach
    void setUp() {
        svc = new HarborRegistryService(operations, tenants, projects, credentials, credentialService,
                secrets, provisioning, harbor);
        when(operations.selectLatestSuccess("c1", "harbor")).thenReturn(successOperation());
        K8sCluster cluster = new K8sCluster();
        cluster.setClusterId("c1");
        cluster.setClusterName("test-cluster");
        when(provisioning.enabledClusters()).thenReturn(List.of(cluster));
        when(secrets.get("platform-harbor-secrets", null, "c1", "harbor"))
                .thenReturn(secretWithPassword());
        // 平台共享项目默认已就绪：这些用例只关心租户路径
        when(projects.selectByClusterAndTenant("c1", null)).thenReturn(platformRow());
        when(credentials.selectById("cred-platform")).thenReturn(usableCredential("cred-platform"));
    }

    @Test
    void firstSyncCreatesProjectRobotAndCredential() {
        when(tenants.listAll()).thenReturn(List.of(tenant("tenant-1", "acme")));
        when(projects.selectByClusterAndTenant("c1", "tenant-1")).thenReturn(null);
        when(harbor.ensureProject(anyString(), anyString(), anyString(), eq("t-acme"))).thenReturn(7L);
        when(harbor.createRobot(anyString(), anyString(), anyString(), eq("t-acme")))
                .thenReturn(new HarborAdminClient.HarborRobot(42L, "robot$t-acme+ci", "s3cr3t"));
        when(credentialService.upsertManagedRegistry(anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString())).thenReturn("cred-1");

        HarborRegistryService.SyncResult result = svc.ensureAll("c1");

        assertThat(result.failed()).isZero();
        verify(harbor).createRobot(anyString(), anyString(), anyString(), eq("t-acme"));
        verify(credentialService).upsertManagedRegistry(eq("Harbor t-acme@c1"), eq("harbor.example.com"),
                eq("tenant-1"), eq("robot$t-acme+ci"), eq("s3cr3t"), anyString());
        verify(projects).insert(any());
    }

    @Test
    void secondSyncIsIdempotentAndDoesNotRotateTheSecret() {
        PlatformRegistryProject row = new PlatformRegistryProject();
        row.setId("p1");
        row.setClusterId("c1");
        row.setTenantId("tenant-1");
        row.setProjectName("t-acme");
        row.setCredentialId("cred-1");
        row.setRobotId(42L);
        row.setStatus("READY");
        row.setHost("harbor.example.com");
        when(tenants.listAll()).thenReturn(List.of(tenant("tenant-1", "acme")));
        when(projects.selectByClusterAndTenant("c1", "tenant-1")).thenReturn(row);
        PlatformCredential cred = new PlatformCredential();
        cred.setId("cred-1");
        cred.setSecretEncrypted("v1:xxx");
        cred.setEnabled(1);
        when(credentials.selectById("cred-1")).thenReturn(cred);

        HarborRegistryService.SyncResult result = svc.ensureAll("c1");

        assertThat(result.failed()).isZero();
        verify(harbor, never()).createRobot(anyString(), anyString(), anyString(), anyString());
        verify(credentialService, never()).upsertManagedRegistry(anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString());
    }

    @Test
    void errorRowIsRetriedInsteadOfSkipped() {
        PlatformRegistryProject row = new PlatformRegistryProject();
        row.setId("p1");
        row.setClusterId("c1");
        row.setTenantId("tenant-1");
        row.setProjectName("t-acme");
        row.setCredentialId("cred-1");
        row.setRobotId(42L);
        row.setStatus("ERROR");
        row.setHost("harbor.example.com");
        when(tenants.selectByPrimaryKey("tenant-1")).thenReturn(tenant("tenant-1", "acme"));
        when(projects.selectByClusterAndTenant("c1", "tenant-1")).thenReturn(row);
        when(harbor.ensureProject(anyString(), anyString(), anyString(), eq("t-acme"))).thenReturn(7L);
        when(harbor.createRobot(anyString(), anyString(), anyString(), eq("t-acme")))
                .thenReturn(new HarborAdminClient.HarborRobot(44L, "robot$t-acme+ci", "fresh"));
        when(credentialService.upsertManagedRegistry(anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString())).thenReturn("cred-1");

        HarborRegistryService.SyncItem item = svc.ensureTenant("c1", "tenant-1", false);

        // 上一次失败的行不能因为「凭据行还在」就跳过：旧 robot 可能已经删掉了
        assertThat(item.status()).isEqualTo("READY");
        verify(harbor).deleteRobot(anyString(), anyString(), anyString(), eq(42L));
        verify(harbor).createRobot(anyString(), anyString(), anyString(), eq("t-acme"));
    }

    @Test
    void forceRebuildDeletesTheOldRobotThenCreatesANewOne() {
        PlatformRegistryProject row = new PlatformRegistryProject();
        row.setId("p1");
        row.setClusterId("c1");
        row.setTenantId("tenant-1");
        row.setProjectName("t-acme");
        row.setCredentialId("cred-1");
        row.setRobotId(42L);
        when(tenants.listAll()).thenReturn(List.of(tenant("tenant-1", "acme")));
        when(tenants.selectByPrimaryKey("tenant-1")).thenReturn(tenant("tenant-1", "acme"));
        when(projects.selectByClusterAndTenant("c1", "tenant-1")).thenReturn(row);
        when(harbor.ensureProject(anyString(), anyString(), anyString(), eq("t-acme"))).thenReturn(7L);
        when(harbor.createRobot(anyString(), anyString(), anyString(), eq("t-acme")))
                .thenReturn(new HarborAdminClient.HarborRobot(43L, "robot$t-acme+ci", "new-secret"));
        when(credentialService.upsertManagedRegistry(anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString())).thenReturn("cred-1");

        HarborRegistryService.SyncItem item = svc.rebuild("c1", "tenant-1");

        assertThat(item.status()).isEqualTo("READY");
        verify(harbor).deleteRobot(anyString(), anyString(), anyString(), eq(42L));
        verify(harbor).createRobot(anyString(), anyString(), anyString(), eq("t-acme"));
        verify(credentialService).upsertManagedRegistry(anyString(), anyString(), anyString(),
                anyString(), eq("new-secret"), anyString());
    }
}
```

测试辅助（放在同一个测试类里）：

```java
    private static PlatformTenant tenant(String id, String sa) {
        PlatformTenant t = new PlatformTenant();
        t.setId(id);
        t.setName(sa);
        t.setServiceAccount(sa);
        t.setStatus((byte) 1);
        return t;
    }

    private static PlatformComponentOperation successOperation() {
        PlatformComponentOperation op = new PlatformComponentOperation();
        op.setClusterId("c1");
        op.setComponentCode("harbor");
        op.setStatus("SUCCESS");
        op.setValuesSnapshot("{\"__platform\":{\"harbor\":{\"host\":\"harbor.example.com\"}}}");
        return op;
    }

    private static com.coding.common.models.k8s.dto.SecretDTO secretWithPassword() {
        var dto = new com.coding.common.models.k8s.dto.SecretDTO();
        dto.setData(java.util.Map.of("HARBOR_ADMIN_PASSWORD", "pw", "secretKey", "0123456789abcdef"));
        return dto;
    }

    private static PlatformRegistryProject platformRow() {
        PlatformRegistryProject row = new PlatformRegistryProject();
        row.setId("platform-row");
        row.setClusterId("c1");
        row.setTenantId(null);
        row.setProjectName("platform");
        row.setCredentialId("cred-platform");
        row.setRobotId(40L);
        row.setStatus("READY");
        row.setHost("harbor.example.com");
        return row;
    }

    private static PlatformCredential usableCredential(String id) {
        PlatformCredential c = new PlatformCredential();
        c.setId(id);
        c.setSecretEncrypted("v1:x");
        c.setEnabled(1);
        return c;
    }
```

- [ ] **Step 2: 跑测试确认失败**

Run:

```bash
mvn -q -pl platform-api -am -Dtest=CredentialServiceTest,HarborRegistryServiceTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: 编译失败。

- [ ] **Step 3: `CredentialService`：托管凭据写入**

构造器加两个依赖：

```java
    private final PlatformTenantMapper tenantMapper;
    private final PlatformRegistryProjectMapper projectMapper;
```

`create()` 里补：

```java
        c.setSource("MANUAL");
        c.setTenantId(null);
```

`update()` 里保留原归属（手工编辑不得改变来源）：

```java
        c.setSource(old.getSource());
        c.setTenantId(old.getTenantId());
```

新增：

```java
    /**
     * Harbor 组件托管的镜像仓库凭据（source=HARBOR）：按 name 存在则覆盖密文与地址，
     * 否则新建。只被 {@code HarborRegistryService} 调用。
     *
     * <p>{@code tenantId} 为 null = 平台共享（`platform` 项目）。调用方负责保证
     * name/endpoint/robot 名来自 Harbor 的真实响应。
     *
     * @return 凭据 id
     */
    public String upsertManagedRegistry(String name, String endpoint, String tenantId,
                                        String username, String secret, String description) {
        PlatformCredential existing = mapper.selectByName(name);
        PlatformCredential c = existing != null ? existing : new PlatformCredential();
        if (existing == null) {
            c.setId(ULIDGenerator.generateULID());
            c.setName(name);
        }
        c.setType("HARBOR");
        c.setEndpoint(normalizeEndpoint("HARBOR", endpoint));
        c.setAuthType(AUTH_BASIC);
        String json = toJson(new CredentialSecret(username, secret));
        c.setSecretEncrypted(cipher.encrypt(json));
        c.setSecretFingerprint(CredentialCipher.fingerprint(json));
        c.setDescription(description);
        c.setEnabled(1);
        c.setUsableByPipelines(1);
        c.setTenantId(tenantId);
        c.setSource("HARBOR");
        if (existing == null) {
            mapper.insert(c);
        } else {
            mapper.update(c);
        }
        return c.getId();
    }
```

- [ ] **Step 4: 实现 `HarborRegistryService`**

```java
package com.coding.platformapi.services;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.k8s.dto.SecretDTO;
import com.coding.common.utils.ULIDGenerator;
import com.coding.data.mapper.auth.PlatformTenantMapper;
import com.coding.data.mapper.system.PlatformComponentOperationMapper;
import com.coding.data.mapper.system.PlatformCredentialMapper;
import com.coding.data.mapper.system.PlatformRegistryProjectMapper;
import com.coding.data.models.auth.PlatformTenant;
import com.coding.data.models.k8s.K8sCluster;
import com.coding.data.models.system.PlatformComponentOperation;
import com.coding.data.models.system.PlatformCredential;
import com.coding.data.models.system.PlatformRegistryProject;
import com.coding.platformapi.models.RegistryProjectView;
import com.coding.platformapi.registry.HarborAdminClient;
import com.coding.platformapi.registry.HarborNaming;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * Harbor 的租户镜像项目：每租户一个 project + 一个 push/pull robot + 一条租户凭据。
 *
 * <p>装完 Harbor、新建/删除/停用租户时触发；管理员也可手动同步/重建。
 * 幂等：已有可用凭据就跳过，不轮换 robot 口令。
 *
 * <p>管理口令来自集群里的 Secret（{@link SecretService}），只在内存里拼 Basic 头。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HarborRegistryService {

    static final String COMPONENT = "harbor";
    static final String NAMESPACE = "harbor";
    static final String SECRET_NAME = "platform-harbor-secrets";
    static final String ADMIN_USER = "admin";
    static final String SOURCE = "HARBOR";

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Object LOCK = new Object();

    private final PlatformComponentOperationMapper operations;
    private final PlatformTenantMapper tenants;
    private final PlatformRegistryProjectMapper projects;
    private final PlatformCredentialMapper credentials;
    private final CredentialService credentialService;
    private final SecretService secrets;
    private final K8sProvisioningService provisioning;
    private final HarborAdminClient harbor;

    public record SyncItem(String tenantId, String tenantName, String project, String status,
                           String message) {
    }

    public record SyncResult(String clusterId, String host, List<SyncItem> items, int failed) {
    }

    /** 该集群上次成功安装 Harbor 的访问地址；没装/无记录 → null */
    public String harborHost(String clusterId) {
        PlatformComponentOperation op = operations.selectLatestSuccess(clusterId, COMPONENT);
        if (op == null || op.getValuesSnapshot() == null) {
            return null;
        }
        try {
            Map<String, Object> snapshot = JSON.readValue(op.getValuesSnapshot(), Map.class);
            if (!(snapshot.get("__platform") instanceof Map<?, ?> platform)
                    || !(platform.get("harbor") instanceof Map<?, ?> harbor)) {
                return null;
            }
            Object host = harbor.get("host");
            return host instanceof String s && !s.isBlank() ? s : null;
        } catch (Exception e) {
            log.warn("集群 {} 的 Harbor 安装快照无法解析：{}", clusterId, e.getMessage());
            return null;
        }
    }

    /** 全量同步：平台共享 + 全部启用租户。装完后由组件安装流程调用。 */
    public SyncResult ensureAll(String clusterId) {
        return ensureAll(clusterId, false);
    }

    /**
     * @param force true = 即使凭据存在也删掉 robot 重建。**首次安装（INSTALL）用 true**：
     *              手工卸载后用同一地址重装时，旧凭据看起来仍然可用，但新 Harbor 里没有那个 robot
     *              —— 不强制重建就会一路 skip，流水线 push 全 401。
     *              升级/编辑重放用 false，避免每次改配置都轮换口令。
     */
    public SyncResult ensureAll(String clusterId, boolean force) {
        String host = requireHost(clusterId);
        synchronized (LOCK) {
            Basic auth = adminOf(clusterId);
            List<SyncItem> items = new ArrayList<>();
            items.add(ensureOne(clusterId, null, HarborNaming.PLATFORM_PROJECT, "platform",
                    host, auth, force));
            for (PlatformTenant t : enabledTenants()) {
                items.add(ensureOne(clusterId, t.getId(), projectOf(t), t.getName(), host, auth, force));
            }
            int failed = (int) items.stream().filter(i -> "ERROR".equals(i.status())).count();
            return new SyncResult(clusterId, host, items, failed);
        }
    }

    /** 某个集群上单租户同步（tenantId 为 null = 平台共享） */
    public SyncItem ensureTenant(String clusterId, String tenantId, boolean force) {
        String host = requireHost(clusterId);
        synchronized (LOCK) {
            Basic auth = adminOf(clusterId);
            if (tenantId == null) {
                return ensureOne(clusterId, null, HarborNaming.PLATFORM_PROJECT, "platform",
                        host, auth, force);
            }
            PlatformTenant t = tenants.selectByPrimaryKey(tenantId);
            if (t == null) {
                throw new CloudPlatformException(EnumResponseType.TENANT_NOT_EXIST);
            }
            return ensureOne(clusterId, tenantId, projectOf(t), t.getName(), host, auth, force);
        }
    }

    public SyncItem rebuild(String clusterId, String tenantId) {
        return ensureTenant(clusterId, tenantId, true);
    }

    /** 新建租户时：对每个装过 Harbor 的集群 best-effort 建一次 */
    public void ensureForTenantOnInstalledClusters(String tenantId) {
        PlatformTenant t = tenants.selectByPrimaryKey(tenantId);
        if (t == null) {
            return;
        }
        for (K8sCluster c : provisioning.enabledClusters()) {
            if (harborHost(c.getClusterId()) == null) {
                continue;
            }
            try {
                ensureTenant(c.getClusterId(), tenantId, false);
            } catch (Exception e) {
                log.warn("租户 {} 在集群 {} 的 Harbor 项目同步失败（不阻塞建租户）：{}",
                        tenantId, c.getClusterId(), e.getMessage());
            }
        }
    }

    /** 删除租户：回收 robot 与凭据，**保留项目与镜像** */
    public void onTenantDeleted(String tenantId) {
        for (PlatformRegistryProject row : projects.selectByTenant(tenantId)) {
            try {
                String host = harborHost(row.getClusterId());
                if (host != null && row.getRobotId() != null) {
                    Basic auth = adminOf(row.getClusterId());
                    harbor.deleteRobot(base(host), auth.user(), auth.pass(), row.getRobotId());
                }
            } catch (Exception e) {
                log.warn("删除租户 {} 的 Harbor 机器人失败（保留台账待人工清理）：{}",
                        tenantId, e.getMessage());
            }
            projects.deleteById(row.getId());
        }
        credentials.deleteByTenantAndSource(tenantId, SOURCE);
    }

    /** 停用/启用租户：凭据开关跟随（停用后流水线选不到） */
    public void onTenantStatusChanged(String tenantId, boolean enabled) {
        credentials.updateEnabledByTenantAndSource(tenantId, SOURCE, enabled ? 1 : 0);
    }

    public List<RegistryProjectView> list(String clusterId) {
        return projects.selectByCluster(clusterId).stream().map(row -> {
            RegistryProjectView v = new RegistryProjectView();
            v.setId(row.getId());
            v.setClusterId(row.getClusterId());
            v.setTenantId(row.getTenantId());
            v.setTenantName(row.getTenantId() == null ? "平台共享"
                    : tenantNameOf(row.getTenantId()));
            v.setHost(row.getHost());
            v.setProjectName(row.getProjectName());
            v.setCredentialId(row.getCredentialId());
            v.setStatus(row.getStatus());
            v.setMessage(row.getMessage());
            v.setLastSyncedAt(row.getLastSyncedAt());
            return v;
        }).toList();
    }

    // ==================== 内部 ====================

    private record Basic(String user, String pass) {
    }

    private SyncItem ensureOne(String clusterId, String tenantId, String project, String tenantName,
                               String host, Basic auth, boolean force) {
        String baseUrl = base(host);
        try {
            long projectId = harbor.ensureProject(baseUrl, auth.user(), auth.pass(), project);
            PlatformRegistryProject row = projects.selectByClusterAndTenant(clusterId, tenantId);
            // host 变过（Harbor 重装成新地址）也按 force 处理：旧 robot 在新实例里不存在
            boolean hostChanged = row != null && !host.equals(row.getHost());
            // ⚠️ 上一次失败（status=ERROR）的行不能 skip：可能旧 robot 已删、凭据行却还在
            if (!force && !hostChanged && row != null && "READY".equals(row.getStatus())
                    && row.getCredentialId() != null && credentialUsable(row.getCredentialId())) {
                touch(row);
                return new SyncItem(tenantId, tenantName, project, "READY", "已是最新");
            }
            // 旧 robot 先删：Harbor 不返回已存在账号的 secret，只能删后重建
            if (row != null && row.getRobotId() != null) {
                harbor.deleteRobot(baseUrl, auth.user(), auth.pass(), row.getRobotId());
            } else {
                harbor.deleteRobotByName(baseUrl, auth.user(), auth.pass(), projectId,
                        HarborNaming.robotNameInHarbor(project));
            }
            HarborAdminClient.HarborRobot robot = harbor.createRobot(baseUrl, auth.user(), auth.pass(), project);
            String credentialId = credentialService.upsertManagedRegistry(
                    HarborNaming.credentialName(project, clusterId), host, tenantId,
                    robot.name(), robot.secret(),
                    "Harbor 自动创建：" + clusterNameOf(clusterId) + " / project " + project);
            save(clusterId, tenantId, host, project, credentialId, robot.id(),
                    "READY", "已创建（project " + project + "）");
            return new SyncItem(tenantId, tenantName, project, "READY", "已创建");
        } catch (Exception e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            save(clusterId, tenantId, host, project, null, null, "ERROR", truncate(message, 1000));
            log.warn("集群 {} / 租户 {} 的 Harbor 项目同步失败：{}", clusterId, tenantId, message);
            return new SyncItem(tenantId, tenantName, project, "ERROR", message);
        }
    }

    private boolean credentialUsable(String credentialId) {
        PlatformCredential c = credentials.selectById(credentialId);
        return c != null && c.getSecretEncrypted() != null && !c.getSecretEncrypted().isBlank()
                && c.getEnabled() != null && c.getEnabled() == 1;
    }

    private void touch(PlatformRegistryProject row) {
        row.setStatus("READY");
        row.setMessage("已是最新");
        row.setLastSyncedAt(new Date());
        row.setUpdatedAt(new Date());
        projects.updateResult(row);
    }

    private void save(String clusterId, String tenantId, String host, String project,
                      String credentialId, Long robotId, String status, String message) {
        PlatformRegistryProject row = projects.selectByClusterAndTenant(clusterId, tenantId);
        Date now = new Date();
        if (row == null) {
            row = new PlatformRegistryProject();
            row.setId(ULIDGenerator.generateULID());
            row.setClusterId(clusterId);
            row.setTenantId(tenantId);
            row.setCreatedAt(now);
            row.setHost(host);
            row.setProjectName(project);
            row.setCredentialId(credentialId);
            row.setRobotId(robotId);
            row.setStatus(status);
            row.setMessage(message);
            row.setLastSyncedAt(now);
            row.setUpdatedAt(now);
            projects.insert(row);
        } else {
            row.setHost(host);
            row.setProjectName(project);
            if (credentialId != null) {
                row.setCredentialId(credentialId);
            }
            if (robotId != null) {
                row.setRobotId(robotId);
            }
            row.setStatus(status);
            row.setMessage(message);
            row.setLastSyncedAt(now);
            row.setUpdatedAt(now);
            projects.updateResult(row);
        }
    }

    private String requireHost(String clusterId) {
        String host = harborHost(clusterId);
        if (host == null) {
            throw new CloudPlatformException(EnumResponseType.ERROR,
                    "该集群没有可用的 Harbor 安装记录（请先安装 Harbor 组件）");
        }
        return host;
    }

    private Basic adminOf(String clusterId) {
        SecretDTO secret = secrets.get(SECRET_NAME, null, clusterId, NAMESPACE);
        String pass = secret == null || secret.getData() == null
                ? null : secret.getData().get("HARBOR_ADMIN_PASSWORD");
        if (pass == null || pass.isBlank()) {
            throw new CloudPlatformException(EnumResponseType.ERROR,
                    "Harbor 管理口令 Secret 不可用：" + NAMESPACE + "/" + SECRET_NAME);
        }
        return new Basic(ADMIN_USER, pass);
    }

    private static String base(String host) {
        return "http://" + host;
    }

    private static String projectOf(PlatformTenant t) {
        return HarborNaming.projectName(t.getServiceAccount());
    }

    private List<PlatformTenant> enabledTenants() {
        return tenants.listAll().stream()
                .filter(t -> t.getStatus() != null && t.getStatus() == 1)
                .toList();
    }

    private String tenantNameOf(String tenantId) {
        PlatformTenant t = tenants.selectByPrimaryKey(tenantId);
        return t == null ? tenantId : t.getName();
    }

    private String clusterNameOf(String clusterId) {
        for (K8sCluster c : provisioning.enabledClusters()) {
            if (clusterId.equals(c.getClusterId())) {
                return c.getClusterName();
            }
        }
        return clusterId;
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}
```

`RegistryProjectView.java`：

```java
package com.coding.platformapi.models;

import lombok.Data;

import java.util.Date;

/** 管理员看到的「租户镜像项目」一行（Harbor 组件面板）。 */
@Data
public class RegistryProjectView {
    private String id;
    private String clusterId;
    private String tenantId;
    private String tenantName;
    private String host;
    private String projectName;
    private String credentialId;
    /** READY / ERROR */
    private String status;
    private String message;
    private Date lastSyncedAt;
}
```

⚠️ 注意 `JSON.readValue(..., Map.class)` 在 Jackson 3 下返回 `Map<String,Object>`，用 `Map.class` 需要 `@SuppressWarnings`；若编译告警不影响，保持即可。

- [ ] **Step 5: 跑测试**

Run:

```bash
mvn -q -pl platform-api -am -Dtest=CredentialServiceTest,HarborRegistryServiceTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: PASS。

- [ ] **Step 6: Commit**

```bash
git add platform-api/src/main/java/com/coding/platformapi/services/CredentialService.java \
        platform-api/src/main/java/com/coding/platformapi/services/HarborRegistryService.java \
        platform-api/src/main/java/com/coding/platformapi/models/RegistryProjectView.java \
        platform-api/src/test/java/com/coding/platformapi/services/CredentialServiceTest.java \
        platform-api/src/test/java/com/coding/platformapi/services/HarborRegistryServiceTest.java
git commit -m "feat(registry): 凭据托管写入与 Harbor 租户项目同步服务"
```

---

### Task 7: 同步触发与管理员端点

**Files:**
- Modify: `platform-api/src/main/java/com/coding/platformapi/services/ComponentInstallService.java`
- Modify: `platform-api/src/main/java/com/coding/platformapi/services/TenantService.java`
- Create: `platform-api/src/main/java/com/coding/platformapi/controllers/RegistryProjectController.java`
- Modify: `platform-api/src/main/java/com/coding/platformapi/audit/AuditAction.java`
- Create: `platform-api/src/main/java/com/coding/platformapi/models/RegistryProjectRequest.java`
- Test: `platform-api/src/test/java/com/coding/platformapi/services/ComponentInstallServiceTest.java`

**Interfaces:**
- Consumes: Task 6 的 `HarborRegistryService`。
- Produces:
  - `POST /registry/projects/list`、`/sync`、`/rebuild`（权限码见 Task 1 迁移）
  - 安装成功钩子：`harborRegistry.ensureAll(clusterId)`，失败 → 操作记 FAILED
  - 租户生命周期：create / delete / update(status)

- [ ] **Step 1: 写失败测试（安装成功后同步；失败则操作 FAILED）**

`ComponentInstallServiceTest` 加 `HarborRegistryService harborRegistry = mock(HarborRegistryService.class);`，
`service(...)` 构造补该参数；加：

```java
    @Test
    void harborInstallTriggersTheRegistrySync() {
        when(clusterService.requireProfile("c1")).thenReturn("BUSINESS");
        when(clusterService.requireName("c1")).thenReturn("test-cluster");
        when(mapper.countRunning("c1", "harbor")).thenReturn(0);
        when(components.status("c1")).thenReturn(List.of(healthy("istio")));
        when(client.applyComponent(anyString(), anyString(), anyString(), any())).thenReturn(successResult());
        when(harborRegistry.ensureAll("c1", true)).thenReturn(new HarborRegistryService.SyncResult(
                "c1", "harbor.example.com", List.of(), 0));

        ComponentApplyRequest req = new ComponentApplyRequest();
        req.setClusterId("c1");
        req.setComponentCode("harbor");
        req.setAction("INSTALL");
        req.setHarbor(new HarborInstallRequest("harbor.example.com"));
        service().apply(req, "admin");

        verify(harborRegistry, timeout(3000)).ensureAll("c1", true);
    }

    @Test
    void harborSyncFailureMarksTheOperationFailedButSaysTheComponentIsInstalled() {
        when(clusterService.requireProfile("c1")).thenReturn("BUSINESS");
        when(clusterService.requireName("c1")).thenReturn("test-cluster");
        when(mapper.countRunning("c1", "harbor")).thenReturn(0);
        when(components.status("c1")).thenReturn(List.of(healthy("istio")));
        when(client.applyComponent(anyString(), anyString(), anyString(), any())).thenReturn(successResult());
        when(harborRegistry.ensureAll("c1", true)).thenThrow(new IllegalStateException("harbor unreachable"));

        ComponentApplyRequest req = new ComponentApplyRequest();
        req.setClusterId("c1");
        req.setComponentCode("harbor");
        req.setAction("INSTALL");
        req.setHarbor(new HarborInstallRequest("harbor.example.com"));
        service().apply(req, "admin");

        ArgumentCaptor<PlatformComponentOperation> upd = ArgumentCaptor.forClass(PlatformComponentOperation.class);
        verify(mapper, timeout(3000).atLeastOnce()).updateById(upd.capture());
        assertThat(upd.getAllValues()).anySatisfy(o -> {
            assertThat(o.getStatus()).isEqualTo("FAILED");
            assertThat(o.getMessage()).contains("组件已安装").contains("harbor unreachable");
        });
    }
```

- [ ] **Step 2: 跑测试确认失败**

Run:

```bash
mvn -q -pl platform-api -am -Dtest=ComponentInstallServiceTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: FAIL（构造器参数不匹配 / 未调用 ensureAll）。

- [ ] **Step 3: 安装钩子**

`ComponentInstallService` 构造器加 `HarborRegistryService harborRegistry`。

在 `run(...)` 里、写 SUCCESS 之前（与告警联动同一层）加：

```java
            // Harbor 装好后立刻把租户接进去（建 project / robot / 凭据）。
            // 失败时整条操作记 FAILED：组件本身已装好，但流水线还推不了镜像 —— 必须让人看见。
            if (result.isSuccess() && "harbor".equals(componentCode)) {
                try {
                    // INSTALL 强制重建：手工卸载后同地址重装时，旧凭据还在但新 Harbor 里没有那个 robot
                    HarborRegistryService.SyncResult sync =
                            harborRegistry.ensureAll(clusterId, "INSTALL".equals(action));
                    if (sync.failed() > 0) {
                        throw new IllegalStateException(sync.failed() + " 个租户同步失败（见镜像项目面板）");
                    }
                } catch (Exception e) {
                    throw new IllegalStateException(
                            "组件已安装，但镜像项目/凭据同步失败：" + e.getMessage(), e);
                }
            }
```

- [ ] **Step 4: 租户生命周期钩子**

`TenantService` 构造器加 `HarborRegistryService harborRegistry`（放在 `memberSvc` 之后）。

`create()` 在 `provisionTenantSas(...)` 之后（`return tenant;` 之前）加：

```java
        // Harbor 项目/凭据：best-effort，失败不阻塞建租户（可在集群组件面板手动同步）。
        // 停用状态建的租户不同步 —— 凭据会被同步流程跳过。
        if (tenant.getStatus() == 1) {
            try {
                harborRegistry.ensureForTenantOnInstalledClusters(tenant.getId());
            } catch (Exception e) {
                log.warn("租户 {} 的 Harbor 项目同步失败（不阻塞创建）：{}", tenant.getId(), e.getMessage());
            }
        }
```

`update()` 在 `tenantMapper.updateByPrimaryKeySelective(upd);` 之后加：

```java
        // 停用/启用租户 → Harbor 凭据开关跟随（停用后流水线选不到）
        if (req.getStatus() != null) {
            try {
                harborRegistry.onTenantStatusChanged(req.getId(), req.getStatus() == 1);
            } catch (Exception e) {
                log.warn("租户 {} 的 Harbor 凭据状态同步失败：{}", req.getId(), e.getMessage());
            }
        }
```

`delete()` 在 `adminClient.cleanupTenant(cleanup);` 之后加：

```java
        try {
            harborRegistry.onTenantDeleted(tenant.getId());
        } catch (Exception e) {
            log.warn("租户 {} 的 Harbor 项目/凭据回收失败：{}", tenant.getId(), e.getMessage());
        }
```

- [ ] **Step 5: 管理员端点与审计码**

`AuditAction` 加：

```java
    // ---- 镜像仓库（Harbor 租户项目）----

    /** 同步全部租户的 Harbor 项目与机器人账号 */
    public static final String REGISTRY_SYNC_PROJECTS = "registry:sync-projects";

    /** 重建单个租户（或平台共享）的 Harbor 机器人账号 */
    public static final String REGISTRY_REBUILD_PROJECT = "registry:rebuild-project";
```

`RegistryProjectRequest.java`：

```java
package com.coding.platformapi.models;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 租户镜像项目端点入参：list/sync 只用 clusterId；rebuild 还要 tenantId（空 = 平台共享）。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RegistryProjectRequest {
    private String clusterId;
    private String tenantId;
}
```

**不要动 `RegistryController`**：它有一条结构性钉子 `RegistryControllerTest.noneOfTheEntriesIsAudited()`
（三个只读入口都不得标审计）。管理员写入口单开一个 controller，路径前缀同为 `/registry`：

`RegistryProjectController.java`：

```java
package com.coding.platformapi.controllers;

import com.coding.common.models.system.ResponseData;
import com.coding.platformapi.audit.AuditAction;
import com.coding.platformapi.audit.Auditable;
import com.coding.platformapi.models.RegistryProjectRequest;
import com.coding.platformapi.models.RegistryProjectView;
import com.coding.platformapi.services.HarborRegistryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Harbor 的租户镜像项目管理（**平台管理员**）。与 {@code RegistryController} 分开：
 * 那三个是租户只读入口、**有意不标审计**（有结构性钉子钉着）；这三个是写操作，必须留痕。
 *
 * <p>权限行见迁移 {@code V2026_10_27_1}：list → {@code platform:registry:project:read}，
 * sync / rebuild → {@code platform:registry:project:manage}。
 */
@Tag(name = "镜像仓库-租户项目", description = "Harbor 的租户 project / robot 账号 / 凭据同步（平台管理员）")
@RestController
@RequestMapping("/registry/projects")
@RequiredArgsConstructor
public class RegistryProjectController {

    private final HarborRegistryService harborRegistry;

    @Operation(summary = "租户镜像项目列表",
            description = "管理员视图：每个租户/平台共享在集群 Harbor 上的 project、凭据与上次同步结果")
    @PostMapping("/list")
    public ResponseData<List<RegistryProjectView>> projects(@RequestBody RegistryProjectRequest request) {
        return new ResponseData<>(harborRegistry.list(request.getClusterId()));
    }

    @Operation(summary = "同步全部租户的 Harbor 项目",
            description = "全量对账：缺的补、host 变了的重建；返回逐租户结果（含失败原因）")
    @PostMapping("/sync")
    @Auditable(action = AuditAction.REGISTRY_SYNC_PROJECTS, target = "#request.clusterId")
    public ResponseData<HarborRegistryService.SyncResult> syncProjects(
            @RequestBody RegistryProjectRequest request) {
        return new ResponseData<>(harborRegistry.ensureAll(request.getClusterId()));
    }

    @Operation(summary = "重建某个租户的 Harbor 机器人账号",
            description = "tenantId 为空 = 平台共享项目。先删旧 robot 再建新的，凭据密文同步覆盖")
    @PostMapping("/rebuild")
    @Auditable(action = AuditAction.REGISTRY_REBUILD_PROJECT, target = "#request.tenantId")
    public ResponseData<HarborRegistryService.SyncItem> rebuildProject(
            @RequestBody RegistryProjectRequest request) {
        return new ResponseData<>(harborRegistry.rebuild(request.getClusterId(), request.getTenantId()));
    }
}
```

- [ ] **Step 6: 跑测试**

Run:

```bash
mvn -q -pl platform-api -am -Dtest=ComponentInstallServiceTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: PASS。

- [ ] **Step 7: Commit**

```bash
git add platform-api/src/main/java/com/coding/platformapi/services/ComponentInstallService.java \
        platform-api/src/main/java/com/coding/platformapi/services/TenantService.java \
        platform-api/src/main/java/com/coding/platformapi/controllers/RegistryProjectController.java \
        platform-api/src/main/java/com/coding/platformapi/audit/AuditAction.java \
        platform-api/src/main/java/com/coding/platformapi/models/RegistryProjectRequest.java \
        platform-api/src/test/java/com/coding/platformapi/services/ComponentInstallServiceTest.java
git commit -m "feat(registry): Harbor 同步触发（安装/租户生命周期）与管理员端点"
```

---

### Task 8: 凭据的租户隔离与流水线可见性

**Files:**
- Modify: `platform-api/src/main/java/com/coding/platformapi/services/CredentialService.java`
- Modify: `platform-api/src/main/java/com/coding/platformapi/services/RegistryService.java`
- Modify: `platform-api/src/main/java/com/coding/platformapi/controllers/RegistryController.java`
- Modify: `platform-api/src/main/java/com/coding/platformapi/controllers/PipelineController.java`
- Modify: `platform-api/src/main/java/com/coding/platformapi/services/PipelineService.java`
- Modify: `platform-api/src/main/java/com/coding/platformapi/services/PipelineRunService.java`
- Modify: `platform-api/src/main/java/com/coding/platformapi/models/CredentialView.java`
- Test: `platform-api/src/test/java/com/coding/platformapi/services/CredentialServiceTest.java`
- Test: `platform-api/src/test/java/com/coding/platformapi/services/RegistryServiceTest.java`
- Test: `platform-api/src/test/java/com/coding/platformapi/services/PipelineServiceTest.java`

**Interfaces:**
- Consumes: Task 1 的 `tenant_id` / `source`、Task 6 的 `platform_registry_project`。
- Produces:
  - `CredentialService.pipelineUsableConnections(String type, String hatTenantId)`
  - `CredentialService.pipelineUsableRegistryConnections(String hatTenantId)`
  - `CredentialService.assertUsableByPipelines(String id, String expectedType, String hatTenantId)`
  - `CredentialService.endpointOnlyRef(String id, String expectedType, String hatTenantId)`
  - `CredentialService.resolveForPipeline(String id, String expectedType, String hatTenantId)`
  - `RegistryService.suggest(String prefix, String hatTenantId)` / `browse(req, hatTenantId)` / `connections(hatTenantId)`
  - `CredentialService.ConnectionView(String id, String name, String endpoint, String project)`
  - `CredentialView.tenantId/tenantName/source`

- [ ] **Step 1: 写失败测试**

`CredentialServiceTest` 加：

```java
    @Test
    void tenantSeesItsOwnHarborCredentialButNotAnotherTenants() {
        PlatformCredential mine = registryCredential("c1", "t-acme", "HARBOR", "Harbor t-acme@c1");
        PlatformCredential other = registryCredential("c2", "t-other", "HARBOR", "Harbor t-other@c1");
        PlatformCredential manual = registryCredential("c3", null, "MANUAL", "dockerhub");
        PlatformCredential platformHarbor = registryCredential("c4", null, "HARBOR", "Harbor platform@c1");
        when(mapper.selectAll()).thenReturn(List.of(mine, other, manual, platformHarbor));

        assertThat(svc.pipelineUsableRegistryConnections("tenant-1"))
                .extracting(CredentialService.ConnectionView::name)
                .containsExactly("Harbor t-acme@c1", "dockerhub");
        // 管理员（无帽）看全量
        assertThat(svc.pipelineUsableRegistryConnections(null)).hasSize(4);
    }

    @Test
    void anotherTenantsCredentialCannotBeReferencedByHand() {
        when(mapper.selectById("c2")).thenReturn(
                registryCredential("c2", "t-other", "HARBOR", "Harbor t-other@c1"));
        assertThatThrownBy(() -> svc.assertUsableByPipelines("c2", null, "tenant-1"))
                .hasMessageContaining("未授权");
        assertThatThrownBy(() -> svc.assertUsableByPipelines("c4", null, "tenant-1"))
                .hasMessageContaining("未授权");   // 平台共享的 HARBOR 凭据也不给租户
    }
```

`registryCredential` 辅助：

```java
    private static PlatformCredential registryCredential(String id, String tenantId,
                                                         String source, String name) {
        PlatformCredential c = new PlatformCredential();
        c.setId(id);
        c.setName(name);
        c.setType("HARBOR");
        c.setEndpoint("harbor.example.com");
        c.setAuthType("BASIC");
        c.setEnabled(1);
        c.setUsableByPipelines(1);
        c.setSecretEncrypted("v1:x");
        c.setTenantId(tenantId);
        c.setSource(source);
        return c;
    }
```

`RegistryServiceTest` 加：

```java
    @Test
    void tenantCannotBrowseWithAnotherTenantsCredential() {
        PlatformCredential other = new PlatformCredential();
        other.setId("c2");
        other.setName("harbor-other");
        other.setType("HARBOR");
        other.setEndpoint("harbor.example.com");
        other.setAuthType("BASIC");
        other.setEnabled(1);
        other.setUsableByPipelines(1);
        other.setTenantId("t-other");
        other.setSource("HARBOR");
        when(mapper.selectAll()).thenReturn(List.of(other));

        RegistryBrowseRequest req = new RegistryBrowseRequest();
        req.setCredentialId("c2");
        req.setPath(List.of());
        assertThatThrownBy(() -> svc.browse(req, "tenant-1"))
                .hasMessageContaining("不存在或已停用");
    }
```

`PipelineServiceTest` 把 `when(credentials.endpointOnlyRef("reg-1", null))` 改成
`when(credentials.endpointOnlyRef("reg-1", null, null))`（两处），因为生产代码改走三参重载。

- [ ] **Step 2: 跑测试确认失败**

Run:

```bash
mvn -q -pl platform-api -am -Dtest=CredentialServiceTest,RegistryServiceTest,PipelineServiceTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: 编译失败（三参方法不存在）。

- [ ] **Step 3: `CredentialService` 可见性与租户过滤**

加：

```java
    /**
     * 凭据对当前请求是否可见。
     *
     * <p>规则：管理员（无租户帽）看全量；租户看**自己的** + **手工建的平台级**凭据。
     * ⚠️ `source=HARBOR` 且 `tenant_id=null` 的是平台 `platform` 项目的机器人账号，
     * 只给管理员用 —— 不能因为「平台级」就下发给租户。
     */
    static boolean visibleTo(PlatformCredential c, String hatTenantId) {
        if (hatTenantId == null) {
            return true;
        }
        if (hatTenantId.equals(c.getTenantId())) {
            return true;
        }
        return c.getTenantId() == null && "MANUAL".equals(c.getSource());
    }
```

把现有 `pipelineUsableConnections(String type)` 改成重载：

```java
    public List<ConnectionView> pipelineUsableConnections(String type) {
        return pipelineUsableConnections(type, null);
    }

    public List<ConnectionView> pipelineUsableConnections(String type, String hatTenantId) {
        Map<String, String> projectByCredential = projectByCredential();
        return mapper.selectAll().stream()
                .filter(c -> type.equals(c.getType()))
                .filter(c -> c.getUsableByPipelines() != null && c.getUsableByPipelines() == 1)
                .filter(c -> c.getEnabled() != null && c.getEnabled() == 1)
                .filter(c -> visibleTo(c, hatTenantId))
                .sorted(Comparator.comparing(PlatformCredential::getName,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .map(c -> new ConnectionView(c.getId(), c.getName(), c.getEndpoint(),
                        projectByCredential.get(c.getId())))
                .toList();
    }

    public List<ConnectionView> pipelineUsableRegistryConnections() {
        return pipelineUsableRegistryConnections(null);
    }

    public List<ConnectionView> pipelineUsableRegistryConnections(String hatTenantId) {
        Map<String, String> projectByCredential = projectByCredential();
        return mapper.selectAll().stream()
                .filter(c -> !"GIT".equals(c.getType()))
                .filter(c -> c.getUsableByPipelines() != null && c.getUsableByPipelines() == 1)
                .filter(c -> c.getEnabled() != null && c.getEnabled() == 1)
                .filter(c -> visibleTo(c, hatTenantId))
                .sorted(...)
                .map(c -> new ConnectionView(c.getId(), c.getName(), c.getEndpoint(),
                        projectByCredential.get(c.getId())))
                .toList();
    }

    private Map<String, String> projectByCredential() {
        return projectMapper.selectAll().stream()
                .filter(p -> p.getCredentialId() != null)
                .collect(Collectors.toMap(PlatformRegistryProject::getCredentialId,
                        PlatformRegistryProject::getProjectName, (a, b) -> a));
    }
```

`assertUsableByPipelines` 重载：

```java
    public void assertUsableByPipelines(String id, String expectedType) {
        assertUsableByPipelines(id, expectedType, null);
    }

    public void assertUsableByPipelines(String id, String expectedType, String hatTenantId) {
        ... // 现有三项校验之后再加：
        if (!visibleTo(c, hatTenantId)) {
            throw new CloudPlatformException(EnumResponseType.CREDENTIAL_INVALID,
                    "该连接未授权在流水线里使用：" + c.getName());
        }
    }
```

`endpointOnlyRef` / `resolveForPipeline` 加重载并透传。

`ConnectionView` 改：

```java
    public record ConnectionView(String id, String name, String endpoint, String project) {
        /** 老调用/测试：不带项目 */
        public ConnectionView(String id, String name, String endpoint) {
            this(id, name, endpoint, null);
        }
    }
```

`CredentialView` 加：

```java
    /** 归属租户；null = 平台级 */
    private String tenantId;

    /** 租户名（列表展示用；平台级为 null） */
    private String tenantName;

    /** MANUAL / HARBOR */
    private String source;
```

`toView` 保持静态，只补 `tenantId` / `source` 两个字段；`tenantName` 在 `list()` 里一次查表回填（避免 N+1）：

```java
    public List<CredentialView> list() {
        Map<String, String> names = tenantMapper.listAll().stream()
                .collect(Collectors.toMap(PlatformTenant::getId, PlatformTenant::getName,
                        (a, b) -> a));
        List<CredentialView> out = mapper.selectAll().stream().map(CredentialService::toView).toList();
        for (CredentialView v : out) {
            v.setTenantName(v.getTenantId() == null ? null : names.get(v.getTenantId()));
        }
        return out;
    }
```

`toView` 里补：

```java
        v.setTenantId(c.getTenantId());
        v.setSource(c.getSource());
```

`CredentialService` 顶部还要补 import：`com.coding.data.models.auth.PlatformTenant`、
`com.coding.data.mapper.auth.PlatformTenantMapper`、`com.coding.data.mapper.system.PlatformRegistryProjectMapper`、
`java.util.Map`、`java.util.stream.Collectors`。

`PlatformRegistryProjectMapper` 需要一个 `selectAll()`（`projectByCredential` 用）。在 Task 1 的 mapper 里补：

```java
    List<PlatformRegistryProject> selectAll();
```

XML：

```xml
  <select id="selectAll" resultMap="BaseResultMap">
    select <include refid="Base_Column_List" /> from platform_registry_project
  </select>
```

- [ ] **Step 4: `RegistryService` 与 controller**

`RegistryService` 三个方法加重载（老签名委托 `null`），`allSources(String hatTenantId)` 里加：

```java
                .filter(c -> CredentialService.visibleTo(c, hatTenantId))
```

`RegistryController` 注入 `AuthContext auth`，三个入口改为：

```java
        return new ResponseData<>(registryService.suggest(request.getImagePrefix(), auth.hatTenantId()));
        return new ResponseData<>(registryService.browse(request, auth.hatTenantId()));
        return new ResponseData<>(registryService.connections(auth.hatTenantId()));
```

- [ ] **Step 5: 流水线两处调用点**

`PipelineController`：

```java
        return new ResponseData<>(credentialService.pipelineUsableConnections("GIT", auth.hatTenantId()));
        return new ResponseData<>(credentialService.pipelineUsableRegistryConnections(auth.hatTenantId()));
```

`PipelineService.previewConnections`：

```java
            out.add(credentials.endpointOnlyRef(ref.id(), ref.expectedType(), auth.hatTenantId()));
```

`PipelineRunService.resolveConnections` 加参数并透传 `ctx.tenantId()`：

```java
    private List<...> resolveConnections(String canvasJson, String tenantId) {
        ...
            out.add(credentials.resolveForPipeline(ref.id(), ref.expectedType(), tenantId));
```

两个调用点改为 `resolveConnections(toRun, ctx.tenantId())` / `resolveConnections(trimmed, ctx.tenantId())`。

- [ ] **Step 6: 跑测试**

Run:

```bash
mvn -q -pl platform-api -am -Dtest=CredentialServiceTest,RegistryServiceTest,PipelineServiceTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: PASS。

- [ ] **Step 7: Commit**

```bash
git add platform-api/src/main/java/com/coding/platformapi/services/CredentialService.java \
        platform-api/src/main/java/com/coding/platformapi/services/RegistryService.java \
        platform-api/src/main/java/com/coding/platformapi/services/PipelineService.java \
        platform-api/src/main/java/com/coding/platformapi/services/PipelineRunService.java \
        platform-api/src/main/java/com/coding/platformapi/controllers/RegistryController.java \
        platform-api/src/main/java/com/coding/platformapi/controllers/PipelineController.java \
        platform-api/src/main/java/com/coding/platformapi/models/CredentialView.java \
        platform-data/src/main/java/com/coding/data/mapper/system/PlatformRegistryProjectMapper.java \
        platform-data/src/main/resources/mapper/system/PlatformRegistryProjectMapper.xml \
        platform-api/src/test/java/com/coding/platformapi/services
git commit -m "feat(registry): 凭据按租户隔离（流水线/仓库浏览）"
```

---

### Task 9: 前端（安装表单、镜像项目面板、凭据页、镜像节点）

**Files:**
- Modify: `platform-web/src/types.ts`
- Modify: `platform-web/src/api/index.ts`
- Modify: `platform-web/src/composables/useComponentOperations.ts`
- Modify: `platform-web/src/views/ClusterDetailView.vue`
- Modify: `platform-web/src/components/cluster/ClusterComponentsPanel.vue`
- Create: `platform-web/src/components/cluster/HarborProjectsDialog.vue`
- Modify: `platform-web/src/views/CredentialView.vue`
- Modify: `platform-web/src/components/pipeline/NodePropsPanel.vue`

**Interfaces:**
- Consumes: Task 3 的 `harborRegistry` / `harborCurrent`、Task 7 的三个端点、Task 8 的 `project` 字段。
- Produces: 安装表单的 `harbor` 载荷；镜像项目面板。

- [ ] **Step 1: 类型与 API**

`types.ts` 加：

```ts
/** Harbor 安装参数（仅 harborRegistry 的组件） */
export interface K8sHarborInstall {
  /** 访问域名，host[:port]，不带 scheme/path */
  host: string
}

/** 租户镜像项目（Harbor 组件面板） */
export interface K8sRegistryProject {
  id: string
  clusterId: string
  tenantId?: string | null
  tenantName: string
  host: string
  projectName: string
  credentialId?: string | null
  status: 'READY' | 'ERROR'
  message?: string | null
  lastSyncedAt?: string | null
}
```

`K8sComponentStatus` 加：

```ts
  /** 安装时是否要填 Harbor 访问域名 */
  harborRegistry?: boolean
  /** 当前生效的 Harbor 参数（上次成功操作快照） */
  harborCurrent?: K8sHarborInstall | null
```

`PipelineConnection` 加：

```ts
  /** 租户镜像项目名（Harbor 自动凭据才有；手工凭据为 null） */
  project?: string | null
```

`CredentialView`（`types.ts`；`CredentialView.vue` 里以别名 `Credential` 引用）加：

```ts
  tenantId?: string | null
  tenantName?: string | null
  source?: 'MANUAL' | 'HARBOR'
```

`api/index.ts`：先把 `K8sHarborInstall`、`K8sRegistryProject` 加进顶部从 `@/types` 的 import 列表。

`clusterApi.componentApply` 入参加：

```ts
    /** Harbor 安装参数（仅 harborRegistry 的组件） */
    harbor?: K8sHarborInstall
```

`registryApi` 加：

```ts
  /** 租户镜像项目（管理员） */
  projects: (clusterId: string) =>
    http.post<never, K8sRegistryProject[]>('/registry/projects/list', { clusterId }),
  /** 同步全部租户（安装后/新增租户后的手动兜底） */
  syncProjects: (clusterId: string) =>
    http.post<never, { clusterId: string; host: string; failed: number;
      items: { tenantId: string | null; tenantName: string; project: string;
               status: string; message: string }[] }>('/registry/projects/sync', { clusterId }),
  /** 重建单个租户（tenantId 空 = 平台共享） */
  rebuildProject: (clusterId: string, tenantId: string | null) =>
    http.post<never, { status: string; message: string }>('/registry/projects/rebuild',
      { clusterId, tenantId }),
```

- [ ] **Step 2: 应用参数透传**

`useComponentOperations.apply` 末尾加 `harbor?: K8sHarborInstall`（记得加进顶部 `@/types` import），并放进 `componentApply` 载荷。
`ClusterDetailView.onApply` 的入参类型与 `operations.apply(...)` 调用末尾加 `p.harbor`；`type` import 补 `K8sHarborInstall`。

- [ ] **Step 3: 面板表单**

`ClusterComponentsPanel.vue`：

1. import 加 `K8sHarborInstall` 与 `HarborProjectsDialog`。
2. `FEATURE_LABELS` 加 `'container-registry': '镜像仓库'`。
3. `defineEmits` 的 `apply` 载荷类型加 `harbor?: K8sHarborInstall`。
4. `EMPTY_HARBOR`：

```ts
/** Harbor 表单默认值：host 必填（同时决定 route hostnames 与 externalURL） */
const EMPTY_HARBOR: K8sHarborInstall = { host: '' }
```

5. `applyDialog` 初始值与类型（`ApplyDialog` 接口）加：

```ts
// ApplyDialog 接口里：
  showHarbor: boolean
  harbor: K8sHarborInstall
  initialHarbor: string

// applyDialog 的初始值里：
  showHarbor: false, harbor: { ...EMPTY_HARBOR }, initialHarbor: JSON.stringify(EMPTY_HARBOR),
```

6. `openApply` 里（seaweed 之后）：

```ts
  // Harbor：编辑时回填上次成功的域名；安装时留空必填
  const harborMerged: K8sHarborInstall = editing && row.harborCurrent
    ? { ...EMPTY_HARBOR, ...row.harborCurrent }
    : { ...EMPTY_HARBOR }
  const harbor: K8sHarborInstall = { host: harborMerged.host || '' }
```
并放进 `applyDialog.value = { ... showHarbor: !!row.harborRegistry, harbor, initialHarbor: JSON.stringify(harbor) }`。

7. `confirmApply` 里加校验与提交：

```ts
  if (d.showHarbor) {
    const host = d.harbor.host.trim()
    if (!HARBOR_HOST_RE.test(host)) {
      ElMessage.error('Harbor 访问域名形如 harbor.example.com（不带 http:// 与路径）')
      return
    }
  }
```
提交载荷：

```ts
    harbor: d.showHarbor && (d.action === 'INSTALL' || JSON.stringify(d.harbor) !== d.initialHarbor)
      ? { host: d.harbor.host.trim() } : undefined,
```

正则（与后端一致）：

```ts
const HARBOR_HOST_RE = /^[a-z0-9]([a-z0-9.-]*[a-z0-9])?(:[0-9]{1,5})?$/
```

8. 模板里（Seaweed 块之前）加：

```vue
      <div v-if="applyDialog.showHarbor" class="cc-section-title">镜像仓库（Harbor）</div>
      <div v-if="applyDialog.showHarbor" class="cc-form-row">
        <span class="cc-form-label">访问域名</span>
        <div class="cc-form-control">
          <el-input v-model="applyDialog.harbor.host"
                    placeholder="harbor.example.com" spellcheck="false" />
          <div class="cc-form-hint">
            不带 <code>http://</code> 与路径。它同时是 HTTPRoute 的 hostname 与 Harbor 的
            <code>externalURL</code>；必须能从集群内 Pod、平台、外部 docker 客户端三边解析
          </div>
        </div>
      </div>
```

9. 行操作区（「编辑」按钮旁）加：

```vue
              <el-button v-if="row.code === 'harbor'
                               && (row.state === 'INSTALLED_HEALTHY' || row.state === 'INSTALLED_UNHEALTHY')"
                         link type="primary" @click="openHarborProjects()">
                镜像项目
              </el-button>
```

10. 面板需要集群 id：`props` 加 `clusterId: string`；`ClusterDetailView.vue` 的
   `<ClusterComponentsPanel ...>`（约 740 行）补一行 `:cluster-id="clusterId"`。脚本里加：

```ts
const harborProjects = ref(false)
function openHarborProjects(): void {
  harborProjects.value = true
}
```

11. 模板末尾（操作详情弹窗之后）加：

```vue
    <HarborProjectsDialog v-model:visible="harborProjects" :cluster-id="props.clusterId" />
```

- [ ] **Step 4: 新建 `HarborProjectsDialog.vue`**

```vue
<script setup lang="ts">
/**
 * Harbor 的「租户镜像项目」面板：每租户/平台共享的 project、凭据状态与同步入口。
 *
 * 数据源是 Harbor 组件的平台侧台账（/registry/projects/*），不直接问 Harbor。
 */
import { ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { registryApi } from '@/api'
import { fmtDate } from '@/utils/format'
import type { K8sRegistryProject } from '@/types'

const props = defineProps<{ visible: boolean; clusterId: string }>()
const emit = defineEmits<{ (e: 'update:visible', v: boolean): void }>()

const rows = ref<K8sRegistryProject[]>([])
const loading = ref(false)
const syncing = ref(false)

async function load(): Promise<void> {
  if (!props.clusterId) return
  loading.value = true
  try {
    rows.value = await registryApi.projects(props.clusterId)
  } catch {
    rows.value = []
  } finally {
    loading.value = false
  }
}

watch(() => props.visible, (v) => { if (v) void load() })

async function syncAll(): Promise<void> {
  syncing.value = true
  try {
    const result = await registryApi.syncProjects(props.clusterId)
    if (result.failed > 0) {
      ElMessage.warning(`同步完成，但有 ${result.failed} 个租户失败（见下表）`)
    } else {
      ElMessage.success(`同步完成（${result.items.length} 项）`)
    }
    await load()
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    syncing.value = false
  }
}

async function rebuild(row: K8sRegistryProject): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `重建「${row.tenantName}」的 Harbor 机器人账号？旧账号会被删除，凭据密文会覆盖。`,
      '确认重建', { type: 'warning' })
  } catch {
    return
  }
  try {
    const item = await registryApi.rebuildProject(props.clusterId, row.tenantId ?? null)
    if (item.status === 'READY') ElMessage.success('已重建')
    else ElMessage.error(item.message || '重建失败')
    await load()
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}

function close(): void {
  emit('update:visible', false)
}
</script>

<template>
  <el-dialog :model-value="visible" title="Harbor 租户镜像项目" width="820px"
             @update:model-value="(v: boolean) => emit('update:visible', v)">
    <el-alert type="info" :closable="false" show-icon
              title="装完 Harbor 与新建租户时会自动同步"
              description="每个租户一个 project + 一个 push/pull 机器人账号，并登记成一条租户归属的 REGISTRY 凭据。失败的行可在这里重试或重建。" />
    <div class="hp-actions">
      <el-button type="primary" :loading="syncing" @click="syncAll">同步全部</el-button>
      <el-button :disabled="loading" @click="load">刷新</el-button>
    </div>
    <el-table v-loading="loading" :data="rows" stripe>
      <el-table-column prop="tenantName" label="归属" min-width="120" />
      <el-table-column prop="projectName" label="项目" min-width="160" />
      <el-table-column prop="host" label="Harbor" min-width="180" show-overflow-tooltip />
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="row.status === 'READY' ? 'success' : 'danger'" size="small">
            {{ row.status === 'READY' ? '就绪' : '失败' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="说明" min-width="220" show-overflow-tooltip>
        <template #default="{ row }">{{ row.message || '—' }}</template>
      </el-table-column>
      <el-table-column label="上次同步" width="160">
        <template #default="{ row }">{{ row.lastSyncedAt ? fmtDate(row.lastSyncedAt) : '—' }}</template>
      </el-table-column>
      <el-table-column label="操作" width="100" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="rebuild(row)">重建账号</el-button>
        </template>
      </el-table-column>
    </el-table>
    <template #footer>
      <el-button @click="close">关闭</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.hp-actions {
  display: flex;
  gap: 8px;
  margin: 12px 0;
}
</style>
```

- [ ] **Step 5: 凭据页**

`CredentialView.vue`：

1. 表格加两列（`名称` 之后）：

```vue
      <el-table-column label="归属" width="160">
        <template #default="{ row }">
          <span v-if="row.source === 'HARBOR'">
            {{ row.tenantName ? `${row.tenantName}（Harbor 自动）` : '平台（Harbor 自动）' }}
          </span>
          <span v-else-if="row.tenantName">{{ row.tenantName }}</span>
          <span v-else class="dim">平台</span>
        </template>
      </el-table-column>
```

2. 操作列按钮加禁用条件：

```vue
          <el-button link type="primary"
                     :disabled="row.source === 'HARBOR' || !perm.has(apiCodes.credentialWrite)"
                     @click="openEdit(row)">编辑</el-button>
          <el-button link type="danger"
                     :disabled="row.source === 'HARBOR' || !perm.has(apiCodes.credentialWrite)"
                     @click="remove(row)">删除</el-button>
```

3. 顶部 alert 的 description 末尾加一句：

```
Harbor 自动创建的租户凭据（归属列显示「Harbor 自动」）只读，请在集群组件面板的「镜像项目」里同步或重建。
```

- [ ] **Step 6: 镜像节点下拉显示项目**

`NodePropsPanel.vue` 的连接下拉 label 改为：

```vue
          <el-option v-for="c in registryConnections" :key="c.id"
                     :label="c.project ? `${c.name}（项目 ${c.project}）` : c.name" :value="c.id" />
```

- [ ] **Step 7: 类型检查与构建**

Run:

```bash
cd platform-web && npm run build
```

Expected: `vue-tsc` 与 vite build 均通过。

- [ ] **Step 8: Commit**

```bash
git add platform-web/src/types.ts platform-web/src/api/index.ts \
        platform-web/src/composables/useComponentOperations.ts \
        platform-web/src/views/ClusterDetailView.vue \
        platform-web/src/components/cluster/ClusterComponentsPanel.vue \
        platform-web/src/components/cluster/HarborProjectsDialog.vue \
        platform-web/src/views/CredentialView.vue \
        platform-web/src/components/pipeline/NodePropsPanel.vue
git commit -m "feat(web): Harbor 安装表单、镜像项目面板与凭据归属展示"
```

---

### Task 10: 文档、全量测试与真集群验收

**Files:**
- Modify: `docs/development/cluster-prerequisites.md`（把「目标仓库优先用集群内可达的 Harbor」补一句平台组件）
- Create/Modify: `docs/superpowers/plans/2026-10-27-harbor-registry.md`（本文件，执行完打勾）

**Interfaces:**
- 无新代码。

- [ ] **Step 1: 全量后端测试**

Run:

```bash
mvn -q test
```

Expected: 全绿（含 platform-api / k8s-server / k8s-core / platform-common / platform-data / platform-auth）。

- [ ] **Step 2: 前端构建**

Run:

```bash
cd platform-web && npm run build
```

Expected: 通过。

- [ ] **Step 3: 真集群验收（手工，按清单）**

前置：目标集群已装 istio（external-gateway）、集群默认 StorageClass 可用、`harbor.example.com` 三边可解析。

1. 重放迁移（开发库/平台库）：
   ```bash
   mysql -h <host> -u <user> -p <db> < platform-data/src/main/resources/db/migration/V2026_10_27_1__harbor_registry.sql
   ```
2. 重建 k8s-server / platform-api / platform-web，重新登录。
3. 集群组件页安装 Harbor：`host=harbor.example.com`，StorageClass 选集群默认，Trivy 关闭。
4. 验证：
   ```bash
   kubectl -n harbor get pods
   kubectl -n harbor get httproute
   kubectl -n harbor get secret platform-harbor-secrets -o jsonpath='{.data}' | jq 'keys'
   ```
   期望：core/portal/registry/jobservice/db/redis 全部 Running；HTTPRoute `Accepted=True`、`AttachedRoutes=1`；Secret 有 `HARBOR_ADMIN_PASSWORD` 与 `secretKey`。
5. 组件行「镜像项目」→「同步全部」：Harbor 里能看到 `platform` 与每个租户的 `t-<sa>` 项目、`robot$t-<sa>+ci`；凭据页出现归属为「Harbor 自动」的行。
   ⚠️ 这一步同时是 **Harbor REST API 字段的实机核对**（spec §4.7）：在建项目 / 建 robot 的响应上确认
   `project_id`、`robots[].id/name/secret`、`duration=-1` 的语义与 Task 5 的假设一致。
   不一致就改 `HarborAdminClient` 与 `HarborAdminClientTest`，重跑 Task 5 的测试再继续。
6. 租户 A 登录 → 流水线编辑器 → 镜像节点：只看到自己的连接（label 带项目名）；保存运行，确认镜像 push 到 `<host>/t-<sa>/<repo>:<tag>`。
7. 租户 B 登录：看不到 A 的连接；用 A 的 credentialId 直接调 `/registry/browse` 返回「不存在或已停用」。
8. 停用租户 A → 凭据页该行变「停用」；启用后恢复。
9. 删除一个测试租户 → 凭据行消失、Harbor 里项目与镜像仍在、robot 被删。

- [ ] **Step 4: 更新部署说明并提交计划勾选**

在组件 README 的「装完之后」补一条「平台镜像项目同步」指向组件面板；`docs/development/cluster-prerequisites.md`
里「目标仓库优先用**集群内可达的 Harbor**」那句后面补：

```markdown
（Harbor 可由平台在「集群组件」里直接安装，并在该组件的「镜像项目」面板按租户自动建 project
与 robot 账号 —— 见 `charts/components/harbor/README.md`。装之前先确认访问域名三边可解析。）
```

然后提交：

```bash
git add docs/development/cluster-prerequisites.md \
        docs/superpowers/plans/2026-10-27-harbor-registry.md \
        k8s-server/src/main/resources/charts/components/harbor/README.md
git commit -m "docs(harbor): 验收清单与部署说明"
```

---

## 附：部署与回滚

- **部署顺序**：重放 `V2026_10_27_1__harbor_registry.sql` → 重建 k8s-server（chart 入镜像 + 新 Secret 端点）→ platform-api → platform-web → 重新登录 → 装 Harbor → 同步。
- **回滚**：本批无破坏性数据变更（新列可空、新表独立）。回滚代码后 Harbor 组件仍可运行；自动凭据行需手工删除（`delete from platform_credential where source='HARBOR'`），项目与镜像在 Harbor 侧保留。
