# 应用商店与 Operator 管理 · 计划①：应用引擎 + 管理员安装 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 平台能按 chart 引用（Harbor OCI / HTTP repo）安装、升级、卸载应用到指定命名空间；安装前做静态检查、安装后复核；全过程异步记录可轮询；管理员可通过新页面完成这些动作。

**Architecture:** 复用 S3.6 的 `HelmRunner` 作为唯一写入原语；新增 `ChartFetcher`（`helm pull` 下载并校验 digest）、`ManifestInspector`（纯函数，检查渲染结果）、`ClusterScopeIndex`（内置集群级 kind 表 + CRD scope）。k8s-server 只做事实与边界判定；platform-api 负责应用记录、权限与异步编排。

**Tech Stack:** Java 21 / Spring Boot 4.1.1 / fabric8 7.9.0 / MyBatis / MySQL / Helm 4.2.0 / Vue 3 + Element Plus。

**Spec:** [docs/superpowers/specs/2026-10-21-app-store-and-operator-design.md](../specs/2026-10-21-app-store-and-operator-design.md)

> 本计划只覆盖 spec 的 §3（数据模型）、§4（执行链）、§6.2 的 `/app/**` 端点、§7 的前端安装向导。
> 目录治理、来源搜索、租户自助在**计划②**；Operator 视图在**计划③**。
> 本计划内的安装路径固定为**管理员**：`strict=false`，但跨命名空间仍阻断。

## Global Constraints

- **唯一写入原语**：所有集群写入必须走 `k8s-server` 的 `HelmRunner`，不新增第二套 apply 路径。
- **Helm 4.2 语义**：失败回滚用 `--rollback-on-failure`；冲突用 `--force-conflicts`；超时默认 10 分钟。
- **无 Flyway**：`db/migration/*.sql` 一律手工重放；启动日志不会有 `Migrating schema`。
- **新端点必须有权限行**：否则 `PermissionCrossCheckRunner` 拒绝启动。
- **k8s-server 零业务逻辑**：本层只做渲染、scope 判定、命名空间归属校验、helm 执行。
- **同类资源不抽公共基类**：`platform_app_operation` 与 `platform_component_operation` 各写一份。
- **前端页面码唯一来源**：`platform-web/src/pageCodes.ts`，路由与菜单只能引用它。
- 面向用户的文案一律中文；代码注释解释"为什么"。

## Review Focus

以下五类输入/失败模式 spec 没有逐条展开，但会真的伤到人，各自在对应任务里有测试：

1. `helm template` 输出里的 `# Source:` 注释与空文档不能被当成对象，也不能被当成错误（Task 2）。
2. 同一 chart 在别的命名空间创建对象——**含管理员**——必须阻断（Task 2、Task 5）。
3. digest 填了但与下载内容不一致时必须硬失败，不能"警告后继续"（Task 4）。
4. 同一应用的并发操作（例如两个升级请求）不能产生两条 RUNNING —— `running_lock` 唯一键（Task 1）把第二次插入变成冲突（Task 6）。
   > 锁按 `cluster_id + app_id` 而不是 release 名：install 的 release 名是生成且唯一的，真正需要互斥的是"对同一个应用的第二个操作"。
5. 卸载平台不认识的 release 必须拒绝——不能删掉别人装的东西（Task 6）。

---

## File Structure

**新增（k8s-server）**

| 文件 | 职责 |
|---|---|
| `k8s-server/src/main/java/com/coding/k8sserver/chart/Scope.java` | `NAMESPACED / CLUSTER / UNKNOWN` |
| `k8s-server/src/main/java/com/coding/k8sserver/chart/ScopeResolver.java` | `apiVersion + kind → Scope` 的函数接口（纯测） |
| `k8s-server/src/main/java/com/coding/k8sserver/chart/Violation.java` | 一条检查结果（BLOCK / WARN） |
| `k8s-server/src/main/java/com/coding/k8sserver/chart/ManifestInspector.java` | 解析渲染结果并逐对象判定 |
| `k8s-server/src/main/java/com/coding/k8sserver/chart/ClusterScopeIndex.java` | 内置 kind 表 + CRD `spec.scope`，按集群缓存 |
| `k8s-server/src/main/java/com/coding/k8sserver/chart/ChartFetcher.java` | `helm pull` 下载 tgz、校验 digest、大小上限 |
| `k8s-server/src/main/java/com/coding/k8sserver/chart/ChartInstallService.java` | inspect / preview / apply / uninstall 编排 |
| `k8s-server/src/main/java/com/coding/k8sserver/controllers/chart/ChartInstallController.java` | `/chart/**`（PLATFORM 边界） |

**修改（k8s-server）**

- `helm/HelmRunner.java`：`apply(..., InstallOptions)`、`manifest(...)`、`pull(...)`
- `pom.xml`：加 `tools.jackson.dataformat:jackson-dataformat-yaml`
- `src/test/java/com/coding/k8sserver/helm/FakeHelm.java`：支持模拟 `pull` 产出文件

**新增（platform-common）**

- `models/k8s/dto/ChartRefDTO.java`、`ChartInspectRequestDTO/ResultDTO`、`ChartPreviewRequestDTO/ResultDTO`、
  `ChartObjectDTO`、`ChartViolationDTO`、`ChartApplyRequestDTO/ResultDTO`、`ChartUninstallRequestDTO`

**新增（platform-data）**

- `models/system/PlatformApp.java`、`PlatformAppOperation.java`
- `mapper/system/PlatformAppMapper.java`、`PlatformAppOperationMapper.java`
- `resources/mapper/system/PlatformAppMapper.xml`、`PlatformAppOperationMapper.xml`
- `resources/db/migration/V2026_10_21_1__app_engine.sql`

**新增（platform-api）**

- `k8s/K8sChartClient.java`（调 `/chart/**`）
- `models/AppInstallRequest.java`、`AppUpgradeRequest.java`、`AppUninstallRequest.java`、`AppQueryRequest.java`、`AppOperationQuery.java`
- `services/AppInstallService.java`
- `controllers/AppController.java`
- `audit/AuditAction.java`：加 `APP_INSTALL / APP_UPGRADE / APP_UNINSTALL`

**新增（platform-web）**

- `views/AppStoreView.vue`
- `src/types.ts`：`K8sApp`、`K8sAppOperation`、`ChartViolation` 等
- `src/api/index.ts`：`appApi`
- `src/pageCodes.ts`：`app: 'tenant:page:app'`
- `src/router/index.ts`：路由 `/apps`

---

### Task 1: 迁移（三张表）

**Files:**
- Create: `platform-data/src/main/resources/db/migration/V2026_10_21_1__app_engine.sql`

**Interfaces:**
- Produces: 表 `platform_app_catalog` / `platform_app` / `platform_app_operation`（Task 6 的 mapper 直接用）
- Produces: 权限行 `tenant:app:read`（`/app/list|get|preview|operations/get`）、`tenant:app:install`（`/app/install|upgrade|uninstall`）
- Produces: 页面码 `tenant:page:app`

- [ ] **Step 1: 写迁移文件**

```sql
-- ============================================================================================
-- 应用商店与 Operator 管理 · 计划①：应用引擎（表结构 + 应用端点权限行）
-- ============================================================================================
-- ⚠️ 本仓没有 Flyway，手工重放；重放前不要起新构建（无权限行的新端点会被 PermissionCrossCheckRunner 拒启）。
-- ⚠️ MySQL 注释 `--` 后面必须跟空白字符。

CREATE TABLE IF NOT EXISTS platform_app_catalog (
  id                 varchar(64)  NOT NULL,
  source             varchar(16)  NOT NULL COMMENT 'HARBOR / ARTIFACT_HUB',
  chart_ref          varchar(512) NOT NULL COMMENT 'OCI 地址或 HTTP repo URL',
  chart_name         varchar(128) NOT NULL,
  version            varchar(64)  NOT NULL,
  digest             varchar(128) NOT NULL COMMENT 'chart 包 sha256',
  display_name       varchar(128) NOT NULL,
  description        varchar(512) NULL,
  values_schema_json MEDIUMTEXT   NULL,
  default_values_json MEDIUMTEXT  NULL,
  visible_to         varchar(16)  NOT NULL DEFAULT 'TENANT' COMMENT 'TENANT / ADMIN',
  created_by         varchar(128) NULL,
  created_at         datetime     NOT NULL,
  deleted_at         datetime     NULL,
  PRIMARY KEY (id),
  KEY idx_app_catalog_chart (chart_name, version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='应用商店目录（计划②使用）';

CREATE TABLE IF NOT EXISTS platform_app (
  id           varchar(64)  NOT NULL,
  cluster_id   varchar(64)  NOT NULL,
  namespace    varchar(128) NOT NULL,
  release_name varchar(64)  NOT NULL,
  catalog_id   varchar(64)  NULL,
  source       varchar(16)  NOT NULL,
  chart_ref    varchar(512) NOT NULL,
  chart_name   varchar(128) NOT NULL,
  version      varchar(64)  NOT NULL,
  digest       varchar(128) NOT NULL,
  values_json  MEDIUMTEXT   NULL,
  app_kind     varchar(16)  NOT NULL DEFAULT 'APP' COMMENT 'APP / OPERATOR',
  display_name varchar(128) NOT NULL,
  status       varchar(16)  NOT NULL COMMENT 'INSTALLING / RUNNING / FAILED / UNINSTALLED',
  needs_review tinyint(1)   NOT NULL DEFAULT 0,
  tenant_id    varchar(64)  NULL,
  created_by   varchar(128) NULL,
  created_at   datetime     NOT NULL,
  updated_at   datetime     NOT NULL,
  deleted_at   datetime     NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_app_release (cluster_id, namespace, release_name),
  KEY idx_app_scope (cluster_id, namespace, deleted_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='应用实例';

CREATE TABLE IF NOT EXISTS platform_app_operation (
  id              bigint       NOT NULL AUTO_INCREMENT,
  cluster_id      varchar(64)  NOT NULL,
  app_id          varchar(64)  NOT NULL,
  `action`        varchar(16)  NOT NULL COMMENT 'INSTALL / UPGRADE / UNINSTALL',
  from_version    varchar(64)  NULL,
  to_version      varchar(64)  NULL,
  values_snapshot MEDIUMTEXT   NULL,
  `status`        varchar(16)  NOT NULL COMMENT 'PENDING / RUNNING / SUCCESS / FAILED / UNKNOWN',
  message         varchar(1024) NULL,
  log_tail        MEDIUMTEXT   NULL,
  `operator`      varchar(128) NULL,
  started_at      datetime     NOT NULL,
  finished_at     datetime     NULL,
  running_lock    varchar(160) GENERATED ALWAYS AS
                  (IF(`status` IN ('PENDING','RUNNING'), CONCAT(cluster_id,'|',app_id), NULL)) STORED,
  PRIMARY KEY (id),
  UNIQUE KEY uk_app_op_running (running_lock),
  KEY idx_app_op_app (app_id, started_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='应用安装/升级/卸载操作';

-- ===== 权限行（API）=====
INSERT INTO platform_permission (id, domain, resource, action, code, description) VALUES
 ('perm_app_list',        'API','/app/list',           'POST','tenant:app:read',   '应用列表'),
 ('perm_app_get',         'API','/app/get',            'POST','tenant:app:read',   '应用详情'),
 ('perm_app_preview',     'API','/app/preview',        'POST','tenant:app:read',   '渲染预览（不落盘）'),
 ('perm_app_op_get',      'API','/app/operations/get', 'POST','tenant:app:read',   '查询应用操作（轮询）'),
 ('perm_app_install',     'API','/app/install',        'POST','tenant:app:install','安装应用'),
 ('perm_app_upgrade',     'API','/app/upgrade',        'POST','tenant:app:install','升级应用'),
 ('perm_app_uninstall',   'API','/app/uninstall',      'POST','tenant:app:install','卸载应用'),
 ('perm_page_app',        'Page','/apps',              'view','tenant:page:app',    '应用商店页面可见性')
ON DUPLICATE KEY UPDATE
  domain=VALUES(domain), resource=VALUES(resource), action=VALUES(action),
  code=VALUES(code), description=VALUES(description);

-- ===== 角色关联（显式绑定；admin 同时持 tenant 族）=====
INSERT INTO platform_role_permission (id, role_id, permission_id) VALUES
 ('rp_admin_app_list',      'builtin_role_admin','perm_app_list'),
 ('rp_admin_app_get',       'builtin_role_admin','perm_app_get'),
 ('rp_admin_app_preview',   'builtin_role_admin','perm_app_preview'),
 ('rp_admin_app_op_get',    'builtin_role_admin','perm_app_op_get'),
 ('rp_admin_app_install',   'builtin_role_admin','perm_app_install'),
 ('rp_admin_app_upgrade',   'builtin_role_admin','perm_app_upgrade'),
 ('rp_admin_app_uninstall', 'builtin_role_admin','perm_app_uninstall'),
 ('rp_admin_page_app',      'builtin_role_admin','perm_page_app'),
 ('rp_tadmin_app_list',     'builtin_role_tenant_admin','perm_app_list'),
 ('rp_tadmin_app_get',      'builtin_role_tenant_admin','perm_app_get'),
 ('rp_tadmin_app_preview',  'builtin_role_tenant_admin','perm_app_preview'),
 ('rp_tadmin_app_op_get',   'builtin_role_tenant_admin','perm_app_op_get'),
 ('rp_tadmin_app_install',  'builtin_role_tenant_admin','perm_app_install'),
 ('rp_tadmin_app_upgrade',  'builtin_role_tenant_admin','perm_app_upgrade'),
 ('rp_tadmin_app_uninstall','builtin_role_tenant_admin','perm_app_uninstall'),
 ('rp_tadmin_page_app',     'builtin_role_tenant_admin','perm_page_app'),
 ('rp_tmember_app_list',    'builtin_role_tenant_member','perm_app_list'),
 ('rp_tmember_app_get',     'builtin_role_tenant_member','perm_app_get'),
 ('rp_tmember_app_op_get',  'builtin_role_tenant_member','perm_app_op_get'),
 ('rp_tmember_page_app',    'builtin_role_tenant_member','perm_page_app')
ON DUPLICATE KEY UPDATE permission_id=VALUES(permission_id);
```

- [ ] **Step 2: 静态检查文件内容**

Run: `grep -c "CREATE TABLE IF NOT EXISTS" platform-data/src/main/resources/db/migration/V2026_10_21_1__app_engine.sql`
Expected: `3`

Run: `grep -c "tenant:app:" platform-data/src/main/resources/db/migration/V2026_10_21_1__app_engine.sql`
Expected: `7`

- [ ] **Step 3: Commit**

```bash
git add platform-data/src/main/resources/db/migration/V2026_10_21_1__app_engine.sql
git commit -m "feat(app): 应用引擎迁移 —— 应用/操作/目录三表与应用端点权限行"
```

> 迁移重放放到 Task 9（部署）。单测不依赖真实库。

---

### Task 2: ManifestInspector（渲染结果静态检查）

**Files:**
- Create: `k8s-server/src/main/java/com/coding/k8sserver/chart/Scope.java`
- Create: `k8s-server/src/main/java/com/coding/k8sserver/chart/ScopeResolver.java`
- Create: `k8s-server/src/main/java/com/coding/k8sserver/chart/Violation.java`
- Create: `k8s-server/src/main/java/com/coding/k8sserver/chart/ManifestInspector.java`
- Modify: `k8s-server/pom.xml`（新增 YAML 解析依赖）
- Test: `k8s-server/src/test/java/com/coding/k8sserver/chart/ManifestInspectorTest.java`

**Interfaces:**
- Produces: `Scope { NAMESPACED, CLUSTER, UNKNOWN }`
- Produces: `ScopeResolver.scopeOf(String apiVersion, String kind) → Scope`
- Produces: `ManifestInspector.inspect(String manifest, String targetNamespace, boolean strict, ScopeResolver scopes) → List<Violation>`
- Produces: `Violation.blocking()`；`Violation.type()` 取值：`DUPLICATE_OBJECT / HOOK / CLUSTER_SCOPED / CROSS_NAMESPACE / NAMESPACE_OBJECT / INVALID_OBJECT / UNKNOWN_KIND`

- [ ] **Step 1: 加依赖**

`k8s-server/pom.xml` 的 `<dependencies>` 里加（版本由 Spring Boot BOM 管理，不写版本号）：

```xml
<dependency>
    <groupId>tools.jackson.dataformat</groupId>
    <artifactId>jackson-dataformat-yaml</artifactId>
</dependency>
```

- [ ] **Step 2: 写失败测试**

```java
package com.coding.k8sserver.chart;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ManifestInspectorTest {

    /** 测试用 scope 表：Deployment 命名空间级，ClusterRole/CRD 集群级，未知 kind 一律 UNKNOWN */
    private static final ScopeResolver SCOPES = (apiVersion, kind) -> switch (kind) {
        case "Deployment", "Service", "ConfigMap" -> Scope.NAMESPACED;
        case "ClusterRole", "CustomResourceDefinition", "Namespace" -> Scope.CLUSTER;
        default -> Scope.UNKNOWN;
    };

    private static List<Violation> inspect(String manifest) {
        return ManifestInspector.inspect(manifest, "demo", true, SCOPES);
    }

    @Test
    void ignoresSourceCommentsAndEmptyDocuments() {
        String manifest = """
                ---
                # Source: demo/templates/deploy.yaml
                apiVersion: apps/v1
                kind: Deployment
                metadata:
                  name: web
                ---
                ---
                """;

        assertThat(inspect(manifest)).isEmpty();
    }

    @Test
    void rejectsExplicitNamespaceThatIsNotTheTarget() {
        String manifest = """
                apiVersion: apps/v1
                kind: Service
                metadata:
                  name: svc
                  namespace: other
                """;

        assertThat(inspect(manifest))
                .extracting(Violation::type)
                .containsExactly("CROSS_NAMESPACE");
    }

    @Test
    void crossNamespaceIsRejectedEvenWhenNotStrict() {
        // 管理员也不允许：跨 ns 会让"卸载是否删干净"不可预期
        String manifest = """
                apiVersion: apps/v1
                kind: Service
                metadata:
                  name: svc
                  namespace: other
                """;

        assertThat(ManifestInspector.inspect(manifest, "demo", false, SCOPES))
                .anyMatch(v -> v.type().equals("CROSS_NAMESPACE") && v.blocking());
    }

    @Test
    void clusterScopedBlocksTenantButOnlyWarnsAdmin() {
        String manifest = """
                apiVersion: rbac.authorization.k8s.io/v1
                kind: ClusterRole
                metadata:
                  name: reader
                """;

        assertThat(ManifestInspector.inspect(manifest, "demo", true, SCOPES))
                .anyMatch(v -> v.type().equals("CLUSTER_SCOPED") && v.blocking());
        assertThat(ManifestInspector.inspect(manifest, "demo", false, SCOPES))
                .anyMatch(v -> v.type().equals("CLUSTER_SCOPED") && !v.blocking());
    }

    @Test
    void unknownKindIsFailClosedForTenant() {
        String manifest = """
                apiVersion: demo.example.com/v1
                kind: Widget
                metadata:
                  name: w
                """;

        assertThat(ManifestInspector.inspect(manifest, "demo", true, SCOPES))
                .anyMatch(v -> v.type().equals("UNKNOWN_KIND") && v.blocking());
    }

    @Test
    void namespaceObjectIsAlwaysRejected() {
        String manifest = """
                apiVersion: v1
                kind: Namespace
                metadata:
                  name: other
                """;

        assertThat(inspect(manifest))
                .anyMatch(v -> v.type().equals("NAMESPACE_OBJECT") && v.blocking());
    }

    @Test
    void hookObjectsBlockTenantButOnlyWarnAdmin() {
        String manifest = """
                apiVersion: batch/v1
                kind: Job
                metadata:
                  name: migrate
                  annotations:
                    "helm.sh/hook": post-install
                """;

        assertThat(ManifestInspector.inspect(manifest, "demo", true, SCOPES))
                .anyMatch(v -> v.type().equals("HOOK") && v.blocking());
        assertThat(ManifestInspector.inspect(manifest, "demo", false, SCOPES))
                .anyMatch(v -> v.type().equals("HOOK") && !v.blocking());
    }

    @Test
    void duplicateObjectsAreRejected() {
        String doc = """
                apiVersion: apps/v1
                kind: Service
                metadata:
                  name: svc
                """;

        assertThat(inspect(doc + "\n---\n" + doc))
                .anyMatch(v -> v.type().equals("DUPLICATE_OBJECT") && v.blocking());
    }

    @Test
    void objectWithoutNameIsRejected() {
        String manifest = """
                apiVersion: apps/v1
                kind: Service
                metadata: {}
                """;

        assertThat(inspect(manifest))
                .anyMatch(v -> v.type().equals("INVALID_OBJECT") && v.blocking());
    }
}
```

- [ ] **Step 3: 运行确认失败**

Run: `mvn -q -pl k8s-server -am -Dtest=ManifestInspectorTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: 编译失败（`ManifestInspector` 不存在）。

- [ ] **Step 4: 实现**

`Scope.java`：

```java
package com.coding.k8sserver.chart;

/** 一个 kind 是否受命名空间约束。UNKNOWN 表示无法判定（调用方必须 fail-closed）。 */
public enum Scope {
    NAMESPACED, CLUSTER, UNKNOWN
}
```

`ScopeResolver.java`：

```java
package com.coding.k8sserver.chart;

/** 把渲染结果里的 {@code apiVersion + kind} 映射到作用域。纯函数，便于单测。 */
@FunctionalInterface
public interface ScopeResolver {
    Scope scopeOf(String apiVersion, String kind);
}
```

`Violation.java`：

```java
package com.coding.k8sserver.chart;

/**
 * 一条检查结果。
 *
 * <p>BLOCK = 阻断安装；WARN = 只提示（管理员路径下的集群级对象 / hook）。
 * 跨命名空间永远 BLOCK —— 它不是"风险"，是"这份 chart 与目标位置不一致"。
 */
public record Violation(String severity, String type, String object, String message) {

    public static final String BLOCK = "BLOCK";
    public static final String WARN = "WARN";

    public boolean blocking() {
        return BLOCK.equals(severity);
    }

    public static Violation block(String type, String object, String message) {
        return new Violation(BLOCK, type, object, message);
    }

    public static Violation warn(String type, String object, String message) {
        return new Violation(WARN, type, object, message);
    }
}
```

`ManifestInspector.java`：

```java
package com.coding.k8sserver.chart;

import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 渲染结果的静态检查（spec §4.2）。
 *
 * <p>输入是 {@code helm template} 的 stdout，逐文档判定。顺序有意义：
 * 先排除空文档与解析失败，再判跨命名空间/集群级/hook。
 *
 * <p>为什么跨命名空间对管理员也阻断：{@code helm uninstall} 会把跨 ns 的对象一起删掉，
 * 而管理员在装的时候通常没意识到自己授权了这件事。
 */
public final class ManifestInspector {

    private static final YAMLMapper YAML = YAMLMapper.builder().build();

    private ManifestInspector() {
    }

    public static List<Violation> inspect(String manifest, String targetNamespace,
                                          boolean strict, ScopeResolver scopes) {
        List<Violation> out = new ArrayList<>();
        if (manifest == null || manifest.isBlank()) {
            return out;
        }
        Set<String> seen = new HashSet<>();
        for (String doc : manifest.split("(?m)^---[ \t]*$")) {
            if (doc.isBlank()) {
                continue;
            }
            JsonNode root;
            try {
                root = YAML.readTree(doc);
            } catch (RuntimeException e) {
                out.add(Violation.block("INVALID_OBJECT", "?", "无法解析的 YAML 文档：" + e.getMessage()));
                continue;
            }
            if (root == null || root.isNull() || root.isMissingNode()) {
                continue;
            }
            String apiVersion = text(root, "apiVersion");
            String kind = text(root, "kind");
            JsonNode metadata = root.path("metadata");
            String name = metadata.path("name").isMissingNode() ? "" : metadata.path("name").asString("");
            String namespace = metadata.path("namespace").isMissingNode() ? "" : metadata.path("namespace").asString("");
            if (kind.isEmpty() || name.isEmpty()) {
                out.add(Violation.block("INVALID_OBJECT", kind + "/" + name, "对象缺少 kind 或 metadata.name"));
                continue;
            }
            String id = apiVersion + "/" + kind + "/" + namespace + "/" + name;
            if (!seen.add(id)) {
                out.add(Violation.block("DUPLICATE_OBJECT", id, "同一对象在清单里出现多次"));
            }
            boolean hook = metadata.path("annotations").has("helm.sh/hook");
            if (hook) {
                out.add(strict
                        ? Violation.block("HOOK", kind + "/" + name, "租户安装不允许 helm hook 资源")
                        : Violation.warn("HOOK", kind + "/" + name, "chart 含 hook 资源，安装时会按其生命周期执行"));
            }
            if ("Namespace".equals(kind)) {
                out.add(Violation.block("NAMESPACE_OBJECT", kind + "/" + name, "应用不能创建命名空间"));
                continue;
            }
            Scope scope = scopes.scopeOf(apiVersion, kind);
            if (scope == Scope.UNKNOWN) {
                out.add(strict
                        ? Violation.block("UNKNOWN_KIND", kind + "/" + name,
                            "集群里没有该 kind 的 CRD，无法判定作用域（fail-closed）")
                        : Violation.warn("UNKNOWN_KIND", kind + "/" + name,
                            "集群里没有该 kind 的 CRD，作用域未知"));
                continue;
            }
            if (scope == Scope.CLUSTER) {
                out.add(strict
                        ? Violation.block("CLUSTER_SCOPED", kind + "/" + name, "租户安装不允许集群级对象")
                        : Violation.warn("CLUSTER_SCOPED", kind + "/" + name, "该对象是集群级的"));
                continue;
            }
            if (!namespace.isEmpty() && !namespace.equals(targetNamespace)) {
                out.add(Violation.block("CROSS_NAMESPACE", kind + "/" + name,
                        "对象声明了命名空间 " + namespace + "，与目标 " + targetNamespace + " 不一致"));
            }
        }
        return out;
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isMissingNode() || v.isNull() ? "" : v.asString("");
    }
}
```

> 注：`asString("")` 是 Jackson 3 的取值方式；若编译报方法不存在，改用 `v.asText("")`。

- [ ] **Step 5: 运行确认通过**

Run: `mvn -q -pl k8s-server -am -Dtest=ManifestInspectorTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: 全部通过（9 个测试）。

- [ ] **Step 6: Commit**

```bash
git add k8s-server/pom.xml k8s-server/src/main/java/com/coding/k8sserver/chart k8s-server/src/test/java/com/coding/k8sserver/chart
git commit -m "feat(app): 渲染清单静态检查（跨 ns / 集群级 / hook / 未知 kind fail-closed）"
```

---

### Task 3: ClusterScopeIndex（内置 kind 表 + CRD scope）

**Files:**
- Create: `k8s-server/src/main/java/com/coding/k8sserver/chart/ClusterScopeIndex.java`
- Test: `k8s-server/src/test/java/com/coding/k8sserver/chart/ClusterScopeIndexTest.java`

**Interfaces:**
- Consumes: `Scope`、`ScopeResolver`（Task 2）
- Produces: `ClusterScopeIndex.forCluster(String clusterId) → ScopeResolver`（按集群缓存，TTL 5 分钟）
- Produces: 包可见构造器 `ClusterScopeIndex(Function<String, List<CrdScope>> crds, Duration ttl)` 供单测
- Produces: `record CrdScope(String group, String kind, boolean namespaced)`

- [ ] **Step 1: 写失败测试**

```java
package com.coding.k8sserver.chart;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ClusterScopeIndexTest {

    private ClusterScopeIndex index(List<CrdScope> crds) {
        return new ClusterScopeIndex(clusterId -> crds, Duration.ofMinutes(5));
    }

    @Test
    void knowsBuiltInClusterScopedKinds() {
        ScopeResolver r = index(List.of()).forCluster("c1");

        assertThat(r.scopeOf("v1", "Namespace")).isEqualTo(Scope.CLUSTER);
        assertThat(r.scopeOf("v1", "Node")).isEqualTo(Scope.CLUSTER);
        assertThat(r.scopeOf("rbac.authorization.k8s.io/v1", "ClusterRole")).isEqualTo(Scope.CLUSTER);
        assertThat(r.scopeOf("storage.k8s.io/v1", "StorageClass")).isEqualTo(Scope.CLUSTER);
        assertThat(r.scopeOf("admissionregistration.k8s.io/v1", "MutatingWebhookConfiguration"))
                .isEqualTo(Scope.CLUSTER);
    }

    @Test
    void builtInNamespacedKindsAreNamespaced() {
        ScopeResolver r = index(List.of()).forCluster("c1");

        assertThat(r.scopeOf("apps/v1", "Deployment")).isEqualTo(Scope.NAMESPACED);
        assertThat(r.scopeOf("v1", "ConfigMap")).isEqualTo(Scope.NAMESPACED);
    }

    @Test
    void customResourcesUseCrdScope() {
        ScopeResolver r = index(List.of(
                new CrdScope("monitoring.coreos.com", "Prometheus", true),
                new CrdScope("cert-manager.io", "ClusterIssuer", false))).forCluster("c1");

        assertThat(r.scopeOf("monitoring.coreos.com/v1", "Prometheus")).isEqualTo(Scope.NAMESPACED);
        assertThat(r.scopeOf("cert-manager.io/v1", "ClusterIssuer")).isEqualTo(Scope.CLUSTER);
    }

    @Test
    void unknownCustomKindIsUnknown() {
        ScopeResolver r = index(List.of()).forCluster("c1");

        assertThat(r.scopeOf("demo.example.com/v1", "Widget")).isEqualTo(Scope.UNKNOWN);
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -q -pl k8s-server -am -Dtest=ClusterScopeIndexTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: 编译失败（类不存在）。

- [ ] **Step 3: 实现**

```java
package com.coding.k8sserver.chart;

import com.coding.k8score.factory.KubernetesClientFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * 判定 kind 的作用域：内置 kind 用静态表，自定义资源读 CRD 的 {@code spec.scope}。
 *
 * <p>为什么不用 API discovery：fabric8 7.x 没有暴露 {@code APIResourceList} 的便捷入口，
 * 而 CRD 对象本身就带 scope —— 对自定义资源这是唯一权威来源。内置 kind 的集群级集合是
 * 封闭且稳定的，写死比"猜"可靠。
 *
 * <p><b>fail-closed</b>：既不认识也不是已安装 CRD 的 kind 返回 {@link Scope#UNKNOWN}，
 * 由调用方决定拒绝（租户）还是提示（管理员）。
 */
@Component
public class ClusterScopeIndex {

    /** 内置集群级 kind：group（核心组用空串）→ kind 集合 */
    private static final Map<String, Set<String>> CLUSTER_SCOPED = Map.ofEntries(
            Map.entry("", Set.of("Namespace", "Node", "PersistentVolume", "ComponentStatus")),
            Map.entry("rbac.authorization.k8s.io", Set.of("ClusterRole", "ClusterRoleBinding")),
            Map.entry("storage.k8s.io", Set.of("StorageClass", "VolumeAttachment", "CSIDriver", "CSINode",
                    "VolumeAttributesClass")),
            Map.entry("scheduling.k8s.io", Set.of("PriorityClass")),
            Map.entry("networking.k8s.io", Set.of("IngressClass", "IPAddress", "ServiceCIDR")),
            Map.entry("node.k8s.io", Set.of("RuntimeClass")),
            Map.entry("apiextensions.k8s.io", Set.of("CustomResourceDefinition")),
            Map.entry("apiregistration.k8s.io", Set.of("APIService")),
            Map.entry("admissionregistration.k8s.io", Set.of("MutatingWebhookConfiguration",
                    "ValidatingWebhookConfiguration", "ValidatingAdmissionPolicy",
                    "ValidatingAdmissionPolicyBinding")),
            Map.entry("certificates.k8s.io", Set.of("CertificateSigningRequest")),
            Map.entry("flowcontrol.apiserver.k8s.io", Set.of("FlowSchema", "PriorityLevelConfiguration")),
            Map.entry("metrics.k8s.io", Set.of("NodeMetrics")));

    /** 我们"认识"的内置组；组在表里但 kind 不在集群级集合 → 命名空间级 */
    private static final Set<String> BUILT_IN_GROUPS = Set.of(
            "", "apps", "batch", "policy", "autoscaling", "coordination", "discovery.k8s.io",
            "rbac.authorization.k8s.io", "storage.k8s.io", "scheduling.k8s.io", "networking.k8s.io",
            "node.k8s.io", "apiextensions.k8s.io", "apiregistration.k8s.io",
            "admissionregistration.k8s.io", "certificates.k8s.io", "flowcontrol.apiserver.k8s.io",
            "authentication.k8s.io", "authorization.k8s.io", "metrics.k8s.io", "events.k8s.io");

    private final Function<String, List<CrdScope>> crdLoader;
    private final Duration ttl;
    private final Map<String, Entry> cache = new ConcurrentHashMap<>();

    public ClusterScopeIndex(KubernetesClientFactory clients,
                             @Value("${platform.chart.discovery-ttl:5m}") Duration ttl) {
        this(clusterId -> clients.getAdminClient(clusterId)
                .apiextensions().v1().customResourceDefinitions().list().getItems().stream()
                .map(c -> new CrdScope(c.getSpec().getGroup(), c.getSpec().getNames().getKind(),
                        "Namespaced".equalsIgnoreCase(c.getSpec().getScope())))
                .toList(), ttl);
    }

    ClusterScopeIndex(Function<String, List<CrdScope>> crdLoader, Duration ttl) {
        this.crdLoader = crdLoader;
        this.ttl = ttl;
    }

    public ScopeResolver forCluster(String clusterId) {
        Entry entry = cache.computeIfAbsent(clusterId, id -> new Entry(Map.of(), 0));
        long now = System.currentTimeMillis();
        if (now - entry.loadedAt > ttl.toMillis()) {
            Map<String, Scope> scopes = new HashMap<>();
            for (CrdScope c : crdLoader.apply(clusterId)) {
                scopes.put(c.group() + "/" + c.kind(), c.namespaced() ? Scope.NAMESPACED : Scope.CLUSTER);
            }
            entry = new Entry(Map.copyOf(scopes), now);
            cache.put(clusterId, entry);
        }
        Map<String, Scope> scopes = entry.scopes;
        return (apiVersion, kind) -> {
            String group = apiVersion == null ? "" : apiVersion.contains("/")
                    ? apiVersion.substring(0, apiVersion.indexOf('/')) : apiVersion;
            if (BUILT_IN_GROUPS.contains(group)) {
                return CLUSTER_SCOPED.getOrDefault(group, Set.of()).contains(kind)
                        ? Scope.CLUSTER : Scope.NAMESPACED;
            }
            return scopes.getOrDefault(group + "/" + kind, Scope.UNKNOWN);
        };
    }

    /** 一条 CRD 的 scope 事实（单测注入用） */
    public record CrdScope(String group, String kind, boolean namespaced) {
    }

    private record Entry(Map<String, Scope> scopes, long loadedAt) {
    }
}
```

- [ ] **Step 4: 运行确认通过**

Run: `mvn -q -pl k8s-server -am -Dtest=ClusterScopeIndexTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: 4 个测试通过。

- [ ] **Step 5: Commit**

```bash
git add k8s-server/src/main/java/com/coding/k8sserver/chart/ClusterScopeIndex.java k8s-server/src/test/java/com/coding/k8sserver/chart/ClusterScopeIndexTest.java
git commit -m "feat(app): kind 作用域索引（内置表 + CRD scope，按集群缓存）"
```

---

### Task 4: HelmRunner 扩展 + ChartFetcher

**Files:**
- Modify: `k8s-server/src/main/java/com/coding/k8sserver/helm/HelmRunner.java`
- Create: `k8s-server/src/main/java/com/coding/k8sserver/chart/ChartFetcher.java`
- Modify: `k8s-server/src/test/java/com/coding/k8sserver/helm/FakeHelm.java`
- Test: `k8s-server/src/test/java/com/coding/k8sserver/helm/HelmRunnerInstallOptionsTest.java`
- Test: `k8s-server/src/test/java/com/coding/k8sserver/chart/ChartFetcherTest.java`

**Interfaces:**
- Produces: `HelmRunner.apply(String kubeconfig, Path chartPath, Path valuesFile, String release, String namespace, InstallOptions options)`
- Produces: `HelmRunner.manifest(String kubeconfig, String release, String namespace) → HelmResult`
- Produces: `HelmRunner.pull(List<String> args) → HelmResult`（不附加 `--kubeconfig`）
- Produces: `record InstallOptions(boolean skipCrds, boolean noHooks, Map<String, String> labels)`
- Produces: `ChartFetcher.fetch(ChartRef ref) → FetchedChart(Path tgz, String digest)`
- Produces: `record ChartRef(String source, String ref, String chartName, String version, String digest, String username, String password)`

- [ ] **Step 1: 扩展 FakeHelm**

在 `FakeHelm.main` 的写日志之后、退出之前加：

```java
        // 模拟 helm pull：按 --destination 生成一个 .tgz（内容来自环境变量），供 ChartFetcher 校验
        String pullContent = System.getenv("FAKE_HELM_PULL_CONTENT");
        if (pullContent != null && args.length > 0 && "pull".equals(args[0])) {
            String destination = null;
            for (int i = 0; i + 1 < args.length; i++) {
                if ("--destination".equals(args[i])) {
                    destination = args[i + 1];
                }
            }
            Path dir = Path.of(destination);
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("fake-chart.tgz"), pullContent, StandardCharsets.UTF_8);
        }
```

- [ ] **Step 2: 写失败测试（InstallOptions 与 manifest）**

```java
package com.coding.k8sserver.helm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class HelmRunnerInstallOptionsTest {

    @TempDir
    Path tmp;

    private HelmRunner runner(Path log) {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String cp = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        return new HelmRunner("helm", List.of(java, "-cp", cp, "com.coding.k8sserver.helm.FakeHelm"),
                Map.of("FAKE_HELM_LOG", log.toString(), "FAKE_HELM_EXIT", "0"), tmp, Duration.ofMinutes(1));
    }

    private Path chart() throws Exception {
        Path chart = tmp.resolve("x-1.0.0.tgz");
        Files.writeString(chart, "fake");
        return chart;
    }

    @Test
    void tenantOptionsAddSkipCrdsAndNoHooks() throws Exception {
        Path log = tmp.resolve("argv.log");
        Path values = tmp.resolve("values.yaml");
        Files.writeString(values, "a: 1\n");

        runner(log).apply("kubeconfig", chart(), values, "app-1", "demo",
                new HelmRunner.InstallOptions(true, true, Map.of("platform.coding/app", "app-1")));

        String argv = Files.readString(log, StandardCharsets.UTF_8);
        assertThat(argv).contains("--skip-crds").contains("--no-hooks").contains("--labels");
    }

    @Test
    void platformOptionsDoNotAddTenantFlags() throws Exception {
        Path log = tmp.resolve("argv.log");
        Path values = tmp.resolve("values.yaml");
        Files.writeString(values, "a: 1\n");

        runner(log).apply("kubeconfig", chart(), values, "app-1", "demo",
                new HelmRunner.InstallOptions(false, false, Map.of()));

        String argv = Files.readString(log, StandardCharsets.UTF_8);
        assertThat(argv).doesNotContain("--skip-crds").doesNotContain("--no-hooks");
    }

    @Test
    void manifestRunsHelmGetManifestWithNamespace() throws Exception {
        Path log = tmp.resolve("argv.log");

        runner(log).manifest("kubeconfig", "app-1", "demo");

        String argv = Files.readString(log, StandardCharsets.UTF_8);
        assertThat(argv).contains("get").contains("manifest").contains("app-1").contains("--namespace");
    }
}
```

- [ ] **Step 3: 写失败测试（ChartFetcher）**

```java
package com.coding.k8sserver.chart;

import com.coding.common.exception.CloudPlatformException;
import com.coding.k8sserver.helm.HelmRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChartFetcherTest {

    @TempDir
    Path tmp;

    private HelmRunner runner(Path log, String content) {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String cp = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        return new HelmRunner("helm", List.of(java, "-cp", cp, "com.coding.k8sserver.helm.FakeHelm"),
                Map.of("FAKE_HELM_LOG", log.toString(), "FAKE_HELM_EXIT", "0",
                        "FAKE_HELM_PULL_CONTENT", content), tmp, Duration.ofMinutes(1));
    }

    private static String sha256(String s) throws Exception {
        byte[] d = java.security.MessageDigest.getInstance("SHA-256")
                .digest(s.getBytes(StandardCharsets.UTF_8));
        return "sha256:" + java.util.HexFormat.of().formatHex(d);
    }

    @Test
    void returnsDigestAndRejectsMismatch() throws Exception {
        Path log = tmp.resolve("argv.log");
        ChartFetcher fetcher = new ChartFetcher(
                runner(log, "chart-bytes"), tmp, 1024 * 1024);

        ChartFetcher.FetchedChart ok = fetcher.fetch(ref("", "oci://harbor/demo/web"));
        assertThat(ok.digest()).isEqualTo(sha256("chart-bytes"));

        assertThatThrownBy(() -> fetcher.fetch(ref(sha256("other"), "oci://harbor/demo/web")))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("digest");
    }

    @Test
    void passesVersionAndCredentials() throws Exception {
        Path log = tmp.resolve("argv.log");
        ChartFetcher fetcher = new ChartFetcher(runner(log, "x"), tmp, 1024 * 1024);

        ChartRef ref = new ChartRef("HARBOR", "oci://harbor/demo/web", "web", "1.2.3",
                "", "user", "pass");
        fetcher.fetch(ref);

        String argv = Files.readString(log, StandardCharsets.UTF_8);
        assertThat(argv).contains("pull").contains("--version").contains("1.2.3")
                .contains("--username").contains("user").contains("--password").contains("pass")
                .contains("--destination");
    }

    @Test
    void rejectsOversizeChart() throws Exception {
        ChartFetcher fetcher = new ChartFetcher(runner(tmp.resolve("argv.log"), "0123456789"),
                tmp, 5);

        assertThatThrownBy(() -> fetcher.fetch(ref("", "oci://harbor/demo/web")))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("体积");
    }

    private static ChartRef ref(String digest, String ociRef) {
        return new ChartRef("HARBOR", ociRef, "web", "1.2.3", digest, null, null);
    }
}
```

- [ ] **Step 4: 运行确认失败**

Run: `mvn -q -pl k8s-server -am -Dtest='HelmRunnerInstallOptionsTest,ChartFetcherTest' -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: 编译失败（`InstallOptions` / `ChartFetcher` 不存在）。

- [ ] **Step 5: 实现 HelmRunner 扩展**

在 `HelmRunner` 中新增（保留原 6 参 `apply` 作为委托，组件路径不受影响）：

```java
    /** 安装选项：租户路径要收掉 chart 自带的 CRD 与 hook。 */
    public record InstallOptions(boolean skipCrds, boolean noHooks, Map<String, String> labels) {
    }

    public HelmResult apply(String kubeconfig, Path chartPath, Path valuesFile,
                            String release, String namespace, InstallOptions options) {
        List<String> args = new ArrayList<>(List.of(
                "upgrade", "--install", release, chartPath.toString(),
                "--namespace", namespace,
                "--create-namespace",
                "--values", valuesFile.toString(),
                "--rollback-on-failure",
                "--force-conflicts",
                "--timeout", "10m"));
        if (options.skipCrds()) {
            args.add("--skip-crds");
        }
        if (options.noHooks()) {
            args.add("--no-hooks");
        }
        if (!options.labels().isEmpty()) {
            List<String> kv = new ArrayList<>();
            options.labels().forEach((k, v) -> kv.add(k + "=" + v));
            args.add("--labels");
            args.add(String.join(",", kv));
        }
        return exec(kubeconfig, args);
    }

    /** 取 release 实际提交给集群的清单（装后复核用）。 */
    public HelmResult manifest(String kubeconfig, String release, String namespace) {
        return exec(kubeconfig, new ArrayList<>(List.of(
                "get", "manifest", release, "--namespace", namespace)));
    }

    /** helm pull：不连集群，因此**不附加** {@code --kubeconfig}。 */
    public HelmResult pull(List<String> args) {
        List<String> command = new ArrayList<>();
        if (commandPrefix != null) {
            command.addAll(commandPrefix);
        } else {
            command.add(binary);
        }
        command.addAll(args);
        try {
            return run(command, args);
        } catch (Exception e) {
            return new HelmResult(false, -1, "helm 执行失败: " + e.getMessage());
        }
    }
```

并把原 6 参 `apply` 的签名改为委托：

```java
    public HelmResult apply(String kubeconfig, Path chartDir, Path valuesFile,
                            String release, String namespace, Map<String, String> labels) {
        return apply(kubeconfig, chartDir, valuesFile, release, namespace,
                new InstallOptions(false, false, labels));
    }
```

- [ ] **Step 6: 实现 ChartFetcher**

```java
package com.coding.k8sserver.chart;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.k8sserver.helm.HelmResult;
import com.coding.k8sserver.helm.HelmRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

/**
 * 拉取 chart 包（{@code helm pull}）并校验 digest。
 *
 * <p>下载的是 **tgz**，不展开：{@code helm template} / {@code helm upgrade} 都直接接受打包后的 chart 路径，
 * 少一层解压就少一类路径漏洞。
 *
 * <p>digest 语义：tar.gz 文件的 sha256（前缀 {@code sha256:}）。上架时记录、安装时逐字节比对 ——
 * 这是"上架时看过的 chart"和"装的时候拿到的 chart"是同一种东西的唯一证据。
 */
@Component
public class ChartFetcher {

    private final HelmRunner helm;
    private final Path workDir;
    private final long maxBytes;

    @Autowired
    public ChartFetcher(HelmRunner helm,
                        @Value("${platform.chart.max-size-bytes:52428800}") long maxBytes) {
        this(helm, Path.of(System.getProperty("java.io.tmpdir")), maxBytes);
    }

    ChartFetcher(HelmRunner helm, Path workDir, long maxBytes) {
        this.helm = helm;
        this.workDir = workDir;
        this.maxBytes = maxBytes;
    }

    public FetchedChart fetch(ChartRef ref) {
        Path dir;
        try {
            dir = Files.createTempDirectory(workDir, "chart-");
        } catch (IOException e) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "无法创建 chart 临时目录：" + e.getMessage());
        }
        List<String> args = new ArrayList<>();
        args.add("pull");
        if ("HARBOR".equalsIgnoreCase(ref.source()) && ref.ref() != null && ref.ref().startsWith("oci://")) {
            args.add(ref.ref());
        } else {
            args.add(ref.chartName());
            args.add("--repo");
            args.add(ref.ref());
        }
        args.add("--version");
        args.add(ref.version());
        args.add("--destination");
        args.add(dir.toString());
        if (ref.username() != null && !ref.username().isBlank()) {
            args.add("--username");
            args.add(ref.username());
            args.add("--password");
            args.add(ref.password() == null ? "" : ref.password());
        }
        HelmResult result = helm.pull(args);
        if (!result.ok()) {
            throw new CloudPlatformException(EnumResponseType.ERROR,
                    "拉取 chart 失败（" + ref.chartName() + " " + ref.version() + "）：" + result.output());
        }
        Path tgz;
        try (Stream<Path> files = Files.list(dir)) {
            tgz = files.filter(p -> p.getFileName().toString().endsWith(".tgz")).findFirst()
                    .orElseThrow(() -> new CloudPlatformException(EnumResponseType.ERROR,
                            "helm pull 未产出 chart 包（" + ref.chartName() + " " + ref.version() + "）"));
            long size = Files.size(tgz);
            if (size > maxBytes) {
                throw new CloudPlatformException(EnumResponseType.ERROR,
                        "chart 体积 " + size + " 字节超过上限 " + maxBytes + " 字节");
            }
        } catch (IOException e) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "读取 chart 包失败：" + e.getMessage());
        }
        String digest = "sha256:" + hex(sha256(tgz));
        if (ref.digest() != null && !ref.digest().isBlank() && !ref.digest().equals(digest)) {
            throw new CloudPlatformException(EnumResponseType.ERROR,
                    "chart digest 与目录记录不一致（期望 " + ref.digest() + "，实际 " + digest + "）");
        }
        return new FetchedChart(tgz, digest);
    }

    private static byte[] sha256(Path file) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file));
        } catch (Exception e) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "计算 digest 失败：" + e.getMessage());
        }
    }

    private static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }

    /** 本次拉取的 chart（tgz 在临时目录，调用方负责清理） */
    public record FetchedChart(Path tgz, String digest) {
    }

    /** chart 引用：source=HARBOR 时 ref 是 OCI 地址，否则 ref 是 HTTP repo URL。 */
    public record ChartRef(String source, String ref, String chartName, String version,
                           String digest, String username, String password) {
    }
}
```

- [ ] **Step 7: 运行确认通过**

Run: `mvn -q -pl k8s-server -am -Dtest='HelmRunnerTest,HelmRunnerInstallOptionsTest,ChartFetcherTest' -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: 全绿（既有 HelmRunnerTest 不能回归）。

- [ ] **Step 8: Commit**

```bash
git add k8s-server/pom.xml k8s-server/src/main/java/com/coding/k8sserver/helm/HelmRunner.java k8s-server/src/main/java/com/coding/k8sserver/chart/ChartFetcher.java k8s-server/src/test/java/com/coding/k8sserver/helm k8s-server/src/test/java/com/coding/k8sserver/chart
git commit -m "feat(app): helm pull + 租户安装参数 + 装后 manifest 读取"
```

---

### Task 5: k8s-server 编排与 `/chart/**` 端点

**Files:**
- Create: `platform-common/src/main/java/com/coding/common/models/k8s/dto/ChartRefDTO.java`
- Create: `.../dto/ChartInspectRequestDTO.java`、`ChartInspectResultDTO.java`
- Create: `.../dto/ChartPreviewRequestDTO.java`、`ChartPreviewResultDTO.java`
- Create: `.../dto/ChartObjectDTO.java`、`ChartViolationDTO.java`
- Create: `.../dto/ChartApplyRequestDTO.java`、`ChartApplyResultDTO.java`
- Create: `.../dto/ChartUninstallRequestDTO.java`
- Create: `k8s-server/src/main/java/com/coding/k8sserver/chart/ChartInstallService.java`
- Create: `k8s-server/src/main/java/com/coding/k8sserver/controllers/chart/ChartInstallController.java`
- Test: `k8s-server/src/test/java/com/coding/k8sserver/chart/ChartInstallServiceTest.java`

**Interfaces:**
- Consumes: `ChartFetcher`、`ManifestInspector`、`ClusterScopeIndex`、`HelmRunner`、`KubernetesClientFactory`
- Produces: `ChartInstallService.preview(ChartPreviewRequestDTO) → ChartPreviewResultDTO`
- Produces: `ChartInstallService.apply(ChartApplyRequestDTO) → ChartApplyResultDTO`
- Produces: `ChartInstallService.uninstall(ChartUninstallRequestDTO) → boolean`
- Produces: `ChartInstallService.inspect(ChartInspectRequestDTO) → ChartInspectResultDTO`
- Produces: HTTP `POST /chart/inspect|preview|apply|uninstall`

- [ ] **Step 1: 定义 DTO（platform-common，全部 `@Data`）**

字段如下（实现时逐个建类）：

- `ChartRefDTO`：`source, ref, chartName, version, digest, username, password`
- `ChartInspectRequestDTO`：`ChartRefDTO chart`
- `ChartInspectResultDTO`：`String digest, String defaultValues`
- `ChartPreviewRequestDTO`：`String clusterId, String namespace, boolean strict, ChartRefDTO chart, String values`
- `ChartPreviewResultDTO`：`String manifest, List<ChartObjectDTO> objects, List<ChartViolationDTO> violations`
- `ChartObjectDTO`：`String apiVersion, kind, name, namespace, scope`
- `ChartViolationDTO`：`String severity, type, object, message`
- `ChartApplyRequestDTO`：`String clusterId, appId, releaseName, namespace, boolean strict, ChartRefDTO chart, String values, Map<String,String> labels`
- `ChartApplyResultDTO`：`boolean success, String message, String logTail, List<ChartViolationDTO> violations`
- `ChartUninstallRequestDTO`：`String clusterId, appId, releaseName, namespace`

- [ ] **Step 2: 写失败测试**

```java
package com.coding.k8sserver.chart;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.models.k8s.dto.ChartApplyRequestDTO;
import com.coding.common.models.k8s.dto.ChartPreviewRequestDTO;
import com.coding.common.models.k8s.dto.ChartRefDTO;
import com.coding.k8sserver.helm.HelmResult;
import com.coding.k8sserver.helm.HelmRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChartInstallServiceTest {

    @TempDir
    Path tmp;

    private final HelmRunner helm = mock(HelmRunner.class);
    private final ChartFetcher fetcher = mock(ChartFetcher.class);
    private final ClusterScopeIndex scopes = mock(ClusterScopeIndex.class);
    private final com.coding.k8score.factory.KubernetesClientFactory clients =
            mock(com.coding.k8score.factory.KubernetesClientFactory.class);

    private final ChartInstallService service = new ChartInstallService(helm, fetcher, scopes, clients);

    private ChartRefDTO chart() {
        ChartRefDTO c = new ChartRefDTO();
        c.setSource("HARBOR");
        c.setRef("oci://harbor/demo/web");
        c.setChartName("web");
        c.setVersion("1.0.0");
        return c;
    }

    private void chartRenders(String manifest) throws Exception {
        Path tgz = tmp.resolve("web-1.0.0.tgz");
        Files.writeString(tgz, "x");
        when(fetcher.fetch(any())).thenReturn(new ChartFetcher.FetchedChart(tgz, "sha256:abc"));
        when(scopes.forCluster(anyString())).thenReturn((apiVersion, kind) -> Scope.NAMESPACED);
        when(helm.render(anyString(), any(), any(), anyString()))
                .thenReturn(new HelmResult(true, 0, manifest));
    }

    @Test
    void previewReturnsBlockingViolationForCrossNamespace() throws Exception {
        chartRenders("""
                apiVersion: v1
                kind: Service
                metadata:
                  name: svc
                  namespace: other
                """);
        when(clients.adminKubeconfig("c1")).thenReturn("kc");

        ChartPreviewRequestDTO req = new ChartPreviewRequestDTO();
        req.setClusterId("c1");
        req.setNamespace("demo");
        req.setStrict(false);
        req.setChart(chart());
        req.setValues("a: 1\n");

        var result = service.preview(req);

        assertThat(result.getViolations())
                .anyMatch(v -> v.getType().equals("CROSS_NAMESPACE") && "BLOCK".equals(v.getSeverity()));
    }

    @Test
    void applyStopsWhenPreviewHasBlockingViolation() throws Exception {
        chartRenders("""
                apiVersion: rbac.authorization.k8s.io/v1
                kind: ClusterRole
                metadata:
                  name: r
                """);
        when(clients.adminKubeconfig("c1")).thenReturn("kc");
        when(scopes.forCluster("c1")).thenReturn((apiVersion, kind) -> Scope.CLUSTER);

        ChartApplyRequestDTO req = new ChartApplyRequestDTO();
        req.setClusterId("c1");
        req.setNamespace("demo");
        req.setReleaseName("app-1");
        req.setStrict(true);
        req.setChart(chart());
        req.setValues("a: 1\n");
        req.setLabels(Map.of());

        assertThatThrownBy(() -> service.apply(req))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("CLUSTER_SCOPED");
    }

    @Test
    void applyReinspectsWhatHelmActuallyApplied() throws Exception {
        String manifest = """
                apiVersion: apps/v1
                kind: Deployment
                metadata:
                  name: web
                  namespace: demo
                """;
        chartRenders(manifest);
        when(clients.adminKubeconfig("c1")).thenReturn("kc");
        when(scopes.forCluster("c1")).thenReturn((apiVersion, kind) -> Scope.NAMESPACED);
        when(helm.apply(anyString(), any(), any(), eq("app-1"), eq("demo"), any()))
                .thenReturn(new HelmResult(true, 0, "release deployed"));
        when(helm.manifest(anyString(), eq("app-1"), eq("demo")))
                .thenReturn(new HelmResult(true, 0, manifest));

        ChartApplyRequestDTO req = new ChartApplyRequestDTO();
        req.setClusterId("c1");
        req.setNamespace("demo");
        req.setReleaseName("app-1");
        req.setStrict(false);
        req.setChart(chart());
        req.setValues("a: 1\n");
        req.setLabels(Map.of());

        var result = service.apply(req);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getViolations()).isEmpty();   // 复核结果来自 helm get manifest，不是预渲染
    }
}
```

- [ ] **Step 3: 运行确认失败**

Run: `mvn -q -pl k8s-server -am -Dtest=ChartInstallServiceTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: 编译失败。

- [ ] **Step 4: 实现 ChartInstallService**

```java
package com.coding.k8sserver.chart;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.k8s.dto.*;
import com.coding.k8score.factory.KubernetesClientFactory;
import com.coding.k8sserver.helm.HelmResult;
import com.coding.k8sserver.helm.HelmRunner;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * chart 应用编排（spec §4）。只做"事实与边界"：
 * 拉取 → 渲染 → 静态检查 → 执行 → 用 helm get manifest 复核实际落盘内容。
 * 目录、权限、租户归属判定都在 platform-api。
 */
@Service
public class ChartInstallService {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final HelmRunner helm;
    private final ChartFetcher fetcher;
    private final ClusterScopeIndex scopes;
    private final KubernetesClientFactory clients;

    public ChartInstallService(HelmRunner helm, ChartFetcher fetcher,
                               ClusterScopeIndex scopes, KubernetesClientFactory clients) {
        this.helm = helm;
        this.fetcher = fetcher;
        this.scopes = scopes;
        this.clients = clients;
    }

    public ChartInspectResultDTO inspect(ChartInspectRequestDTO req) {
        ChartFetcher.FetchedChart chart = fetcher.fetch(toRef(req.getChart()));
        try {
            ChartInspectResultDTO out = new ChartInspectResultDTO();
            out.setDigest(chart.digest());
            HelmResult values = helm.showValues(chart.tgz());
            out.setDefaultValues(values.ok() ? values.output() : "");
            return out;
        } finally {
            deleteQuietly(chart.tgz());
        }
    }

    public ChartPreviewResultDTO preview(ChartPreviewRequestDTO req) {
        String kubeconfig = clients.adminKubeconfig(req.getClusterId());
        ChartFetcher.FetchedChart chart = fetcher.fetch(toRef(req.getChart()));
        Path values = writeValues(req.getValues());
        try {
            HelmResult rendered = helm.render(kubeconfig, chart.tgz(), values, req.getNamespace());
            if (!rendered.ok()) {
                throw new CloudPlatformException(EnumResponseType.ERROR, "渲染失败：" + rendered.output());
            }
            List<Violation> violations = ManifestInspector.inspect(
                    rendered.output(), req.getNamespace(), req.isStrict(), scopes.forCluster(req.getClusterId()));
            ChartPreviewResultDTO out = new ChartPreviewResultDTO();
            out.setManifest(rendered.output());
            out.setViolations(toDtos(violations));
            out.setObjects(objects(rendered.output()));
            return out;
        } finally {
            deleteQuietly(values);
            deleteQuietly(chart.tgz());
        }
    }

    public ChartApplyResultDTO apply(ChartApplyRequestDTO req) {
        String kubeconfig = clients.adminKubeconfig(req.getClusterId());
        ChartFetcher.FetchedChart chart = fetcher.fetch(toRef(req.getChart()));
        Path values = writeValues(req.getValues());
        try {
            HelmResult rendered = helm.render(kubeconfig, chart.tgz(), values, req.getNamespace());
            if (!rendered.ok()) {
                throw new CloudPlatformException(EnumResponseType.ERROR, "渲染失败：" + rendered.output());
            }
            ScopeResolver resolver = scopes.forCluster(req.getClusterId());
            List<Violation> pre = ManifestInspector.inspect(
                    rendered.output(), req.getNamespace(), req.isStrict(), resolver);
            Violation blocking = pre.stream().filter(Violation::blocking).findFirst().orElse(null);
            if (blocking != null) {
                throw new CloudPlatformException(EnumResponseType.ERROR,
                        blocking.type() + "：" + blocking.message());
            }
            HelmRunner.InstallOptions options = req.isStrict()
                    ? new HelmRunner.InstallOptions(true, true, req.getLabels())
                    : new HelmRunner.InstallOptions(false, false, req.getLabels());
            HelmResult applied = helm.apply(kubeconfig, chart.tgz(), values,
                    req.getReleaseName(), req.getNamespace(), options);

            ChartApplyResultDTO out = new ChartApplyResultDTO();
            out.setSuccess(applied.ok());
            out.setMessage(applied.output());
            out.setLogTail(applied.output());
            if (applied.ok()) {
                // 第三道闸：以实际落盘的 manifest 为准
                HelmResult actual = helm.manifest(kubeconfig, req.getReleaseName(), req.getNamespace());
                List<Violation> post = actual.ok()
                        ? ManifestInspector.inspect(actual.output(), req.getNamespace(), req.isStrict(), resolver)
                        : List.of();
                out.setViolations(toDtos(post));
            }
            return out;
        } finally {
            deleteQuietly(values);
            deleteQuietly(chart.tgz());
        }
    }

    public boolean uninstall(ChartUninstallRequestDTO req) {
        String kubeconfig = clients.adminKubeconfig(req.getClusterId());
        return helm.uninstall(kubeconfig, req.getReleaseName(), req.getNamespace()).ok();
    }

    private ChartFetcher.ChartRef toRef(ChartRefDTO dto) {
        if (dto == null) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "缺少 chart 引用");
        }
        return new ChartFetcher.ChartRef(dto.getSource(), dto.getRef(), dto.getChartName(),
                dto.getVersion(), dto.getDigest(), dto.getUsername(), dto.getPassword());
    }

    private static Path writeValues(String values) {
        try {
            Path file = Files.createTempFile("values-", ".yaml");
            Files.writeString(file, values == null || values.isBlank() ? "{}\n" : values,
                    StandardCharsets.UTF_8);
            return file;
        } catch (IOException e) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "写入 values 失败：" + e.getMessage());
        }
    }

    private static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // 清理失败不改变结果（与 kubeconfig 同一原则）
        }
    }

    private static List<ChartViolationDTO> toDtos(List<Violation> violations) {
        List<ChartViolationDTO> out = new ArrayList<>();
        for (Violation v : violations) {
            ChartViolationDTO dto = new ChartViolationDTO();
            dto.setSeverity(v.severity());
            dto.setType(v.type());
            dto.setObject(v.object());
            dto.setMessage(v.message());
            out.add(dto);
        }
        return out;
    }

    private static List<ChartObjectDTO> objects(String manifest) {
        return ChartObjects.scan(manifest);
    }
}
```

`ChartObjects.java`（包内工具，把清单列成 DTO；scope 由 `ClusterScopeIndex` 在调用方补，不在这里判）：

```java
package com.coding.k8sserver.chart;

import com.coding.common.models.k8s.dto.ChartObjectDTO;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

import java.util.ArrayList;
import java.util.List;

/** 把渲染清单解析成对象列表（只读展示；scope 判定不在这里）。 */
final class ChartObjects {

    private static final YAMLMapper YAML = YAMLMapper.builder().build();

    private ChartObjects() {
    }

    static List<ChartObjectDTO> scan(String manifest) {
        List<ChartObjectDTO> out = new ArrayList<>();
        if (manifest == null || manifest.isBlank()) {
            return out;
        }
        for (String doc : manifest.split("(?m)^---[ \t]*$")) {
            if (doc.isBlank()) {
                continue;
            }
            JsonNode root;
            try {
                root = YAML.readTree(doc);
            } catch (RuntimeException e) {
                continue; // 解析失败由 ManifestInspector 报 INVALID_OBJECT
            }
            if (root == null || root.isNull() || root.isMissingNode()) {
                continue;
            }
            ChartObjectDTO dto = new ChartObjectDTO();
            dto.setApiVersion(root.path("apiVersion").asString(""));
            dto.setKind(root.path("kind").asString(""));
            dto.setName(root.path("metadata").path("name").asString(""));
            dto.setNamespace(root.path("metadata").path("namespace").asString(""));
            out.add(dto);
        }
        return out;
    }
}
```

> 为让测试可编译：`HelmRunner` 还需补 `showValues(Path)` 与 `uninstall(kubeconfig, release, ns)`（分别执行
> `helm show values <tgz>`、`helm uninstall <release> -n <ns>`），并在 `HelmRunnerInstallOptionsTest` 里加断言
> （Step 5 一起做）。

- [ ] **Step 5: 补 HelmRunner 的两个命令 + 测试断言**

```java
    public HelmResult showValues(Path chartPath) {
        return pull(new ArrayList<>(List.of("show", "values", chartPath.toString())));
    }

    public HelmResult uninstall(String kubeconfig, String release, String namespace) {
        return exec(kubeconfig, new ArrayList<>(List.of("uninstall", release, "--namespace", namespace)));
    }
```

在 `HelmRunnerInstallOptionsTest` 补：

```java
    @Test
    void uninstallRunsHelmUninstallWithNamespace() throws Exception {
        Path log = tmp.resolve("argv.log");

        runner(log).uninstall("kubeconfig", "app-1", "demo");

        String argv = Files.readString(log, StandardCharsets.UTF_8);
        assertThat(argv).contains("uninstall").contains("app-1").contains("--namespace").contains("demo");
    }
```

- [ ] **Step 6: 控制器**

```java
package com.coding.k8sserver.controllers.chart;

import com.coding.common.models.k8s.dto.*;
import com.coding.common.models.system.ResponseData;
import com.coding.k8sserver.chart.ChartInstallService;
import com.coding.k8sserver.components.AccessBoundary;
import com.coding.k8sserver.components.AccessBoundaryAware;
import com.coding.k8sserver.components.ResourceAccessResolver;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 应用商店执行链（**PLATFORM 边界**）。授权在 platform-api（tenant:app:install / platform:app:manage），
 * 本层只声明边界并执行。
 */
@Tag(name = "应用商店-执行", description = "chart 渲染/预览/安装/卸载（helm）")
@RestController
@RequestMapping("/chart")
public class ChartInstallController implements AccessBoundaryAware {

    private final ChartInstallService service;
    private final ResourceAccessResolver accessResolver;

    public ChartInstallController(ChartInstallService service, ResourceAccessResolver accessResolver) {
        this.service = service;
        this.accessResolver = accessResolver;
    }

    @Override
    public AccessBoundary accessBoundary() {
        return AccessBoundary.PLATFORM;
    }

    @PostMapping("/inspect")
    public ResponseData<ChartInspectResultDTO> inspect(@RequestBody ChartInspectRequestDTO request) {
        return new ResponseData<>(service.inspect(request));
    }

    @PostMapping("/preview")
    public ResponseData<ChartPreviewResultDTO> preview(@RequestBody ChartPreviewRequestDTO request) {
        accessResolver.assertClusterAccess(request.getClusterId());
        return new ResponseData<>(service.preview(request));
    }

    @PostMapping("/apply")
    public ResponseData<ChartApplyResultDTO> apply(@RequestBody ChartApplyRequestDTO request) {
        accessResolver.assertClusterAccess(request.getClusterId());
        return new ResponseData<>(service.apply(request));
    }

    @PostMapping("/uninstall")
    public ResponseData<Boolean> uninstall(@RequestBody ChartUninstallRequestDTO request) {
        accessResolver.assertClusterAccess(request.getClusterId());
        return new ResponseData<>(service.uninstall(request));
    }
}
```

- [ ] **Step 7: 运行确认通过**

Run: `mvn -q -pl k8s-server -am -Dtest='ChartInstallServiceTest,HelmRunnerTest,HelmRunnerInstallOptionsTest,ChartFetcherTest,ManifestInspectorTest,ClusterScopeIndexTest' -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: 全绿。

- [ ] **Step 8: Commit**

```bash
git add platform-common/src/main/java/com/coding/common/models/k8s/dto k8s-server/src/main/java/com/coding/k8sserver/chart k8s-server/src/main/java/com/coding/k8sserver/controllers/chart k8s-server/src/test/java/com/coding/k8sserver
git commit -m "feat(app): /chart 执行链（预览/安装/卸载 + 装后复核）"
```

---

### Task 6: platform-api 数据层 + AppInstallService

**Files:**
- Create: `platform-data/src/main/java/com/coding/data/models/system/PlatformApp.java`
- Create: `.../PlatformAppOperation.java`
- Create: `platform-data/src/main/java/com/coding/data/mapper/system/PlatformAppMapper.java`
- Create: `.../PlatformAppOperationMapper.java`
- Create: `platform-data/src/main/resources/mapper/system/PlatformAppMapper.xml`
- Create: `.../PlatformAppOperationMapper.xml`
- Create: `platform-api/src/main/java/com/coding/platformapi/k8s/K8sChartClient.java`
- Create: `platform-api/src/main/java/com/coding/platformapi/models/AppInstallRequest.java`、`AppUpgradeRequest.java`、`AppUninstallRequest.java`、`AppQueryRequest.java`、`AppOperationQuery.java`
- Create: `platform-api/src/main/java/com/coding/platformapi/services/AppInstallService.java`
- Test: `platform-api/src/test/java/com/coding/platformapi/services/AppInstallServiceTest.java`

**Interfaces:**
- Consumes: `K8sChartClient.preview/apply/uninstall`（对应 Task 5 的端点）
- Produces: `AppInstallService.install(AppInstallRequest, String operator) → Long`（返回 operationId）
- Produces: `AppInstallService.upgrade(AppUpgradeRequest, String) → Long`
- Produces: `AppInstallService.uninstall(AppUninstallRequest, String) → Long`
- Produces: `AppInstallService.get(Long opId) → PlatformAppOperation`
- Produces: `AppInstallService.list(String clusterId, String namespace) → List<PlatformApp>`
- Produces: `AppInstallService.getApp(String appId) → PlatformApp`

- [ ] **Step 1: 实体 + mapper（照 `PlatformComponentOperation` 的形态逐字写）**

`PlatformApp` 字段与表列一一对应（`id, clusterId, namespace, releaseName, catalogId, source, chartRef,
chartName, version, digest, valuesJson, appKind, displayName, status, needsReview, tenantId, createdBy,
createdAt, updatedAt, deletedAt`）。

`PlatformAppOperation` 字段：`id, clusterId, appId, action, fromVersion, toVersion, valuesSnapshot, status,
message, logTail, operator, startedAt, finishedAt`。

Mapper 方法（XML 对应）：

```java
public interface PlatformAppMapper {
    int insert(PlatformApp record);
    int updateById(PlatformApp record);
    PlatformApp selectByPrimaryKey(@Param("id") String id);
    List<PlatformApp> selectByCluster(@Param("clusterId") String clusterId,
                                      @Param("namespace") String namespace);
}
```

```java
public interface PlatformAppOperationMapper {
    int insert(PlatformAppOperation record);      // useGeneratedKeys
    int updateById(PlatformAppOperation record);
    PlatformAppOperation selectByPrimaryKey(@Param("id") Long id);
    PlatformAppOperation selectLatestSuccess(@Param("appId") String appId);
    List<PlatformAppOperation> selectByApp(@Param("appId") String appId);
}
```

> `insert` 必须有 `useGeneratedKeys="true" keyProperty="id"`（S3.6 评审踩过：没有 id 回填，后续更新全 NULL）。
> `selectByCluster` 的 namespace 为空时返回该集群全部（`<if test="namespace != null and namespace != ''">`）。

`platform-api/src/main/java/com/coding/platformapi/models/` 下的请求体（全部 `@Data`）：

- `AppInstallRequest`：`String clusterId, namespace, displayName, values; ChartRefDTO chartRef`
- `AppPreviewRequest`：`String clusterId, namespace, values; ChartRefDTO chartRef`
- `AppUpgradeRequest`：`String appId, version, digest, values`
- `AppUninstallRequest`：`String appId`
- `AppQueryRequest`：`String clusterId, namespace, appId`
- `AppOperationQuery`：`Long operationId`

- [ ] **Step 2: 写失败测试**

```java
package com.coding.platformapi.services;

import com.coding.common.models.k8s.dto.ChartApplyResultDTO;
import com.coding.common.models.k8s.dto.ChartPreviewResultDTO;
import com.coding.data.mapper.system.PlatformAppMapper;
import com.coding.data.mapper.system.PlatformAppOperationMapper;
import com.coding.data.models.system.PlatformApp;
import com.coding.data.models.system.PlatformAppOperation;
import com.coding.platformapi.k8s.K8sChartClient;
import com.coding.platformapi.models.AppInstallRequest;
import com.coding.platformapi.models.AppUpgradeRequest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class AppInstallServiceTest {

    private final PlatformAppMapper apps = mock(PlatformAppMapper.class);
    private final PlatformAppOperationMapper ops = mock(PlatformAppOperationMapper.class);
    private final K8sChartClient client = mock(K8sChartClient.class);

    private final AppInstallService service = new AppInstallService(apps, ops, client);

    @org.junit.jupiter.api.BeforeEach
    void insertReturnsIds() {
        doAnswer(inv -> {
            inv.getArgument(0, PlatformAppOperation.class).setId(1L);
            return 1;
        }).when(ops).insert(any());
    }

    private AppInstallRequest request() {
        AppInstallRequest r = new AppInstallRequest();
        r.setClusterId("c1");
        r.setNamespace("demo");
        r.setDisplayName("Web");
        r.setChartRef(ociChart());
        r.setValues("replicas: 1\n");
        return r;
    }

    private com.coding.common.models.k8s.dto.ChartRefDTO ociChart() {
        var c = new com.coding.common.models.k8s.dto.ChartRefDTO();
        c.setSource("HARBOR");
        c.setRef("oci://harbor/demo/web");
        c.setChartName("web");
        c.setVersion("1.0.0");
        return c;
    }

    @Test
    void installGeneratesReleaseNameAndRecordsSuccess() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        when(client.apply(any())).thenAnswer(inv -> {
            ChartApplyResultDTO ok = new ChartApplyResultDTO();
            ok.setSuccess(true);
            ok.setMessage("ok");
            return ok;
        });
        doAnswer(inv -> { done.countDown(); return 1; }).when(apps).updateById(any());

        Long opId = service.install(request(), "admin");

        assertThat(opId).isEqualTo(1L);
        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();

        ArgumentCaptor<PlatformApp> appCaptor = ArgumentCaptor.forClass(PlatformApp.class);
        verify(apps).insert(appCaptor.capture());
        assertThat(appCaptor.getValue().getReleaseName()).startsWith("app-");
        assertThat(appCaptor.getValue().getStatus()).isEqualTo("INSTALLING");

        ArgumentCaptor<PlatformAppOperation> opCaptor = ArgumentCaptor.forClass(PlatformAppOperation.class);
        verify(ops, atLeastOnce()).updateById(opCaptor.capture());
        assertThat(opCaptor.getAllValues()).anyMatch(o -> "SUCCESS".equals(o.getStatus()));
    }

    @Test
    void installMarksFailureWhenHelmFails() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        when(client.apply(any())).thenReturn(failedApply());
        doAnswer(inv -> { done.countDown(); return 1; }).when(apps).updateById(any());

        service.install(request(), "admin");
        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();

        ArgumentCaptor<PlatformApp> appCaptor = ArgumentCaptor.forClass(PlatformApp.class);
        verify(apps, atLeastOnce()).updateById(appCaptor.capture());
        assertThat(appCaptor.getAllValues()).anyMatch(a -> "FAILED".equals(a.getStatus()));
    }

    @Test
    void concurrentOperationsOnSameAppAreRejected() {
        // running_lock = cluster_id|app_id 的唯一键：同一个应用不能同时有两个进行中的操作
        when(ops.insert(any())).thenThrow(new DuplicateKeyException("running_lock"));

        assertThatThrownBy(() -> service.install(request(), "admin"))
                .hasMessageContaining("正在安装");
    }

    @Test
    void upgradeRequiresAPlatformOwnedApp() {
        AppUpgradeRequest r = new AppUpgradeRequest();
        r.setAppId("app-missing");

        assertThatThrownBy(() -> service.upgrade(r, "admin"))
                .hasMessageContaining("不是平台安装");
    }

    @Test
    void uninstallRequiresAPlatformOwnedApp() {
        var r = new com.coding.platformapi.models.AppUninstallRequest();
        r.setAppId("app-missing");

        assertThatThrownBy(() -> service.uninstall(r, "admin"))
                .hasMessageContaining("不是平台安装");
    }

    private static ChartApplyResultDTO failedApply() {
        ChartApplyResultDTO bad = new ChartApplyResultDTO();
        bad.setSuccess(false);
        bad.setMessage("helm failed");
        return bad;
    }
}
```

- [ ] **Step 3: 运行确认失败**

Run: `mvn -q -pl platform-api -am -Dtest=AppInstallServiceTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: 编译失败。

- [ ] **Step 4: 实现 K8sChartClient**

```java
package com.coding.platformapi.k8s;

import com.coding.common.models.k8s.dto.*;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;

/** 平台 → k8s-server 的 chart 执行链（长任务走慢速通道）。 */
@Component
public class K8sChartClient {

    private final K8sServerGateway gateway;

    public K8sChartClient(K8sServerGateway gateway) {
        this.gateway = gateway;
    }

    public ChartPreviewResultDTO preview(ChartPreviewRequestDTO body) {
        return gateway.exchange(HttpMethod.POST, "/chart/preview", null, body,
                gateway.responseType(ChartPreviewResultDTO.class));
    }

    public ChartApplyResultDTO apply(ChartApplyRequestDTO body) {
        return gateway.exchangeSlow(HttpMethod.POST, "/chart/apply", null, body,
                gateway.responseType(ChartApplyResultDTO.class));
    }

    public Boolean uninstall(ChartUninstallRequestDTO body) {
        return gateway.exchangeSlow(HttpMethod.POST, "/chart/uninstall", null, body,
                gateway.responseType(Boolean.class));
    }
}
```

> `K8sServerGateway` 的方法签名照 `K8sComponentClient` 里的既有用法（`exchange` / `exchangeSlow` /
> `responseType`）—— 直接对照 `platform-api/src/main/java/com/coding/platformapi/k8s/K8sComponentClient.java`。

- [ ] **Step 5: 实现 AppInstallService**

```java
package com.coding.platformapi.services;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.k8s.dto.ChartApplyRequestDTO;
import com.coding.common.models.k8s.dto.ChartApplyResultDTO;
import com.coding.common.models.k8s.dto.ChartUninstallRequestDTO;
import com.coding.common.models.k8s.dto.ChartViolationDTO;
import com.coding.data.mapper.system.PlatformAppMapper;
import com.coding.data.mapper.system.PlatformAppOperationMapper;
import com.coding.data.models.system.PlatformApp;
import com.coding.data.models.system.PlatformAppOperation;
import com.coding.platformapi.k8s.K8sChartClient;
import com.coding.platformapi.models.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;

/**
 * 应用安装/升级/卸载（spec §4.5）。异步执行 + 操作记录，形态与 S3.6 的组件安装一致。
 *
 * <p>本计划只有管理员路径（strict=false）。租户闸门（目录白名单、分配表）在计划②，
 * 但 <b>strict 由服务端决定，不接受前端传入</b>，计划②只需改这一处取值。
 */
@Service
public class AppInstallService {

    private final PlatformAppMapper apps;
    private final PlatformAppOperationMapper ops;
    private final K8sChartClient client;

    private final java.util.concurrent.ExecutorService executor =
            Executors.newVirtualThreadPerTaskExecutor();

    public AppInstallService(PlatformAppMapper apps, PlatformAppOperationMapper ops, K8sChartClient client) {
        this.apps = apps;
        this.ops = ops;
        this.client = client;
    }

    public Long install(AppInstallRequest req, String operator) {
        requireBasic(req.getClusterId(), req.getNamespace());
        PlatformApp app = new PlatformApp();
        app.setId("app_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        app.setClusterId(req.getClusterId());
        app.setNamespace(req.getNamespace());
        app.setReleaseName("app-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8));
        app.setSource(req.getChartRef().getSource());
        app.setChartRef(req.getChartRef().getRef());
        app.setChartName(req.getChartRef().getChartName());
        app.setVersion(req.getChartRef().getVersion());
        app.setDigest(req.getChartRef().getDigest() == null ? "" : req.getChartRef().getDigest());
        app.setValuesJson(req.getValues());
        app.setAppKind("APP");
        app.setDisplayName(req.getDisplayName() == null || req.getDisplayName().isBlank()
                ? req.getChartRef().getChartName() : req.getDisplayName());
        app.setStatus("INSTALLING");
        app.setNeedsReview(false);
        app.setCreatedBy(operator);
        app.setCreatedAt(new Date());
        app.setUpdatedAt(new Date());
        apps.insert(app);

        PlatformAppOperation op = newOperation(app, "INSTALL", null, app.getVersion(), operator, req.getValues());
        run(op, () -> client.apply(applyRequest(app, req.getValues(), false)), app);
        return op.getId();
    }

    public Long upgrade(AppUpgradeRequest req, String operator) {
        PlatformApp app = requireOwned(req.getAppId());
        if (req.getVersion() == null || req.getVersion().isBlank()) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "请选择目标版本");
        }
        String from = app.getVersion();
        app.setVersion(req.getVersion());
        if (req.getDigest() != null) {
            app.setDigest(req.getDigest());
        }
        app.setStatus("INSTALLING");
        app.setUpdatedAt(new Date());
        apps.updateById(app);

        PlatformAppOperation op = newOperation(app, "UPGRADE", from, req.getVersion(), operator, req.getValues());
        run(op, () -> client.apply(applyRequest(app, req.getValues(), false)), app);
        return op.getId();
    }

    public Long uninstall(AppUninstallRequest req, String operator) {
        PlatformApp app = requireOwned(req.getAppId());
        PlatformAppOperation op = newOperation(app, "UNINSTALL", app.getVersion(), null, operator, null);
        run(op, () -> {
            Boolean ok = client.uninstall(uninstallRequest(app));
            ChartApplyResultDTO dto = new ChartApplyResultDTO();
            dto.setSuccess(Boolean.TRUE.equals(ok));
            dto.setMessage(Boolean.TRUE.equals(ok) ? "uninstalled" : "helm uninstall failed");
            return dto;
        }, app);
        return op.getId();
    }

    public PlatformAppOperation get(Long operationId) {
        return ops.selectByPrimaryKey(operationId);
    }

    public List<PlatformApp> list(String clusterId, String namespace) {
        return apps.selectByCluster(clusterId, namespace);
    }

    public PlatformApp getApp(String appId) {
        return apps.selectByPrimaryKey(appId);
    }

    /** 渲染预览（不落盘）：安装向导用它显示将要创建的对象与阻断项。 */
    public com.coding.common.models.k8s.dto.ChartPreviewResultDTO preview(AppPreviewRequest req) {
        requireBasic(req.getClusterId(), req.getNamespace());
        var body = new com.coding.common.models.k8s.dto.ChartPreviewRequestDTO();
        body.setClusterId(req.getClusterId());
        body.setNamespace(req.getNamespace());
        body.setStrict(false); // 租户闸门在计划②：那里改为按租户标记取值
        body.setChart(req.getChartRef());
        body.setValues(req.getValues());
        return client.preview(body);
    }

    // ---------- 内部 ----------

    private PlatformApp requireOwned(String appId) {
        PlatformApp app = appId == null ? null : apps.selectByPrimaryKey(appId);
        if (app == null) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "该应用不是平台安装的，平台不接管");
        }
        return app;
    }

    private static void requireBasic(String clusterId, String namespace) {
        if (clusterId == null || clusterId.isBlank()) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "请选择集群");
        }
        if (namespace == null || namespace.isBlank()) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "请选择命名空间");
        }
    }

    private PlatformAppOperation newOperation(PlatformApp app, String action, String from, String to,
                                              String operator, String values) {
        PlatformAppOperation op = new PlatformAppOperation();
        op.setClusterId(app.getClusterId());
        op.setAppId(app.getId());
        op.setAction(action);
        op.setFromVersion(from);
        op.setToVersion(to);
        op.setValuesSnapshot(values);
        op.setStatus("PENDING");
        op.setOperator(operator);
        op.setStartedAt(new Date());
        try {
            ops.insert(op);
        } catch (DuplicateKeyException e) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "该应用正在安装/升级，请等待当前操作结束");
        }
        return op;
    }

    private void run(PlatformAppOperation op, java.util.function.Supplier<ChartApplyResultDTO> call,
                     PlatformApp app) {
        executor.submit(() -> {
            op.setStatus("RUNNING");
            ops.updateById(op);
            try {
                ChartApplyResultDTO result = call.get();
                boolean ok = result != null && result.isSuccess();
                op.setStatus(ok ? "SUCCESS" : "FAILED");
                op.setMessage(truncate(result == null ? "无返回" : result.getMessage(), 1000));
                op.setLogTail(result == null ? null : truncate(result.getLogTail(), 60000));
                app.setStatus(ok ? "RUNNING" : "FAILED");
                app.setNeedsReview(ok && result.getViolations() != null && !result.getViolations().isEmpty());
                app.setUpdatedAt(new Date());
                apps.updateById(app);
            } catch (Exception e) {
                op.setStatus("FAILED");
                op.setMessage(truncate(e.getMessage(), 1000));
                app.setStatus("FAILED");
                app.setUpdatedAt(new Date());
                apps.updateById(app);
            } finally {
                op.setFinishedAt(new Date());
                ops.updateById(op);
            }
        });
    }

    private static ChartApplyRequestDTO applyRequest(PlatformApp app, String values, boolean strict) {
        ChartApplyRequestDTO body = new ChartApplyRequestDTO();
        body.setClusterId(app.getClusterId());
        body.setAppId(app.getId());
        body.setReleaseName(app.getReleaseName());
        body.setNamespace(app.getNamespace());
        body.setStrict(strict);
        var chart = new com.coding.common.models.k8s.dto.ChartRefDTO();
        chart.setSource(app.getSource());
        chart.setRef(app.getChartRef());
        chart.setChartName(app.getChartName());
        chart.setVersion(app.getVersion());
        chart.setDigest(app.getDigest());
        body.setChart(chart);
        body.setValues(values);
        body.setLabels(Map.of(
                "platform.coding/managed", "true",
                "platform.coding/app", app.getId()));
        return body;
    }

    private static ChartUninstallRequestDTO uninstallRequest(PlatformApp app) {
        ChartUninstallRequestDTO body = new ChartUninstallRequestDTO();
        body.setClusterId(app.getClusterId());
        body.setAppId(app.getId());
        body.setReleaseName(app.getReleaseName());
        body.setNamespace(app.getNamespace());
        return body;
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(s.length() - max);
    }
}
```

- [ ] **Step 6: 运行确认通过**

Run: `mvn -q -pl platform-api -am -Dtest=AppInstallServiceTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: 5 个测试通过。

- [ ] **Step 7: Commit**

```bash
git add platform-data/src/main/java/com/coding/data platform-data/src/main/resources/mapper/system platform-api/src/main/java/com/coding/platformapi
git commit -m "feat(app): 应用数据层与安装/升级/卸载异步服务"
```

---

### Task 7: platform-api 端点与审计

**Files:**
- Create: `platform-api/src/main/java/com/coding/platformapi/controllers/AppController.java`
- Modify: `platform-api/src/main/java/com/coding/platformapi/audit/AuditAction.java`
- Test: `platform-api/src/test/java/com/coding/platformapi/controllers/AppControllerTest.java`

**Interfaces:**
- Consumes: `AppInstallService`（Task 6）
- Produces: `POST /app/install|upgrade|uninstall|list|get|operations/get`

- [ ] **Step 1: 加审计动作码**

`AuditAction` 里加：

```java
    // ---- 应用商店 ----

    public static final String APP_INSTALL = "app:install";

    public static final String APP_UPGRADE = "app:upgrade";

    public static final String APP_UNINSTALL = "app:uninstall";
```

- [ ] **Step 2: 写失败测试**

```java
package com.coding.platformapi.controllers;

import com.coding.data.models.system.PlatformApp;
import com.coding.platformapi.models.AppQueryRequest;
import com.coding.platformapi.services.AppInstallService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AppControllerTest {

    @Test
    void listReturnsAppsFromService() {
        AppInstallService service = mock(AppInstallService.class);
        PlatformApp app = new PlatformApp();
        app.setId("app_1");
        when(service.list("c1", "demo")).thenReturn(List.of(app));

        List<PlatformApp> out = new AppController(service).list(query()).getData();

        assertThat(out).extracting(PlatformApp::getId).containsExactly("app_1");
    }

    private static AppQueryRequest query() {
        AppQueryRequest r = new AppQueryRequest();
        r.setClusterId("c1");
        r.setNamespace("demo");
        return r;
    }
}
```

- [ ] **Step 3: 运行确认失败**

Run: `mvn -q -pl platform-api -am -Dtest=AppControllerTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: 编译失败。

- [ ] **Step 4: 实现控制器**

```java
package com.coding.platformapi.controllers;

import com.coding.common.models.system.ResponseData;
import com.coding.data.models.system.PlatformApp;
import com.coding.data.models.system.PlatformAppOperation;
import com.coding.platformapi.audit.AuditAction;
import com.coding.platformapi.audit.Auditable;
import com.coding.platformapi.models.*;
import com.coding.platformapi.services.AppInstallService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 应用商店（计划①：管理员安装/升级/卸载）。一页一 controller。 */
@Tag(name = "应用商店", description = "应用安装/升级/卸载与操作记录")
@RestController
@RequestMapping("/app")
public class AppController {

    private final AppInstallService service;

    public AppController(AppInstallService service) {
        this.service = service;
    }

    @PostMapping("/install")
    @Auditable(action = AuditAction.APP_INSTALL, target = "#request.clusterId")
    @Operation(summary = "安装应用（异步；返回操作 id）")
    public ResponseData<Long> install(@RequestBody AppInstallRequest request) {
        return new ResponseData<>(service.install(request, operator()));
    }

    @PostMapping("/upgrade")
    @Auditable(action = AuditAction.APP_UPGRADE, target = "#request.appId")
    @Operation(summary = "升级应用到目标版本（异步）")
    public ResponseData<Long> upgrade(@RequestBody AppUpgradeRequest request) {
        return new ResponseData<>(service.upgrade(request, operator()));
    }

    @PostMapping("/uninstall")
    @Auditable(action = AuditAction.APP_UNINSTALL, target = "#request.appId")
    @Operation(summary = "卸载应用（异步；CRD 与 PVC 不删）")
    public ResponseData<Long> uninstall(@RequestBody AppUninstallRequest request) {
        return new ResponseData<>(service.uninstall(request, operator()));
    }

    @PostMapping("/list")
    @Operation(summary = "按集群/命名空间列出应用")
    public ResponseData<List<PlatformApp>> list(@RequestBody AppQueryRequest request) {
        return new ResponseData<>(service.list(request.getClusterId(), request.getNamespace()));
    }

    @PostMapping("/get")
    @Operation(summary = "应用详情")
    public ResponseData<PlatformApp> get(@RequestBody AppQueryRequest request) {
        return new ResponseData<>(service.getApp(request.getAppId()));
    }

    @PostMapping("/preview")
    @Operation(summary = "渲染预览（不落盘；返回将创建的对象与阻断项）")
    public ResponseData<com.coding.common.models.k8s.dto.ChartPreviewResultDTO> preview(
            @RequestBody AppPreviewRequest request) {
        return new ResponseData<>(service.preview(request));
    }

    @PostMapping("/operations/get")
    @Operation(summary = "查询一条应用操作（轮询）")
    public ResponseData<PlatformAppOperation> operation(@RequestBody AppOperationQuery request) {
        return new ResponseData<>(service.get(request.getOperationId()));
    }

    @Autowired
    private com.coding.platformapi.security.AuthContext auth;

    private String operator() {
        var me = auth.current();
        return me != null ? me.getUsername() : "unknown";
    }
}
```

> `operator()` 与 `ClusterController.applyComponent` 完全同源：注入 `com.coding.platformapi.security.AuthContext`，
> 取当前用户名。

- [ ] **Step 5: 运行确认通过**

Run: `mvn -q -pl platform-api -am -Dtest=AppControllerTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: 1 个测试通过。

- [ ] **Step 6: 全量回归**

Run: `mvn -q -pl platform-api -am test`
Expected: 全绿（本次新增 + 既有 1045+）。

- [ ] **Step 7: Commit**

```bash
git add platform-api/src/main/java/com/coding/platformapi/controllers/AppController.java platform-api/src/main/java/com/coding/platformapi/audit/AuditAction.java platform-api/src/test/java/com/coding/platformapi/controllers/AppControllerTest.java
git commit -m "feat(app): /app 端点与审计"
```

---

### Task 8: 前端（应用商店页 + 安装向导）

**Files:**
- Modify: `platform-web/src/pageCodes.ts`
- Modify: `platform-web/src/router/index.ts`
- Modify: `platform-web/src/types.ts`
- Modify: `platform-web/src/api/index.ts`
- Create: `platform-web/src/views/AppStoreView.vue`

**Interfaces:**
- Consumes: `/app/list|get|install|upgrade|uninstall|operations/get`
- Produces: 路由 `/apps`（`pageCodes.app`），菜单项「应用商店」（平台管理组）

- [ ] **Step 1: 页面码与路由**

`pageCodes.ts` 加：

```ts
  /** 应用商店（管理员与租户共用一个页面码：管理员持 tenant 族码） */
  app: 'tenant:page:app',
```

`router/index.ts` 的 `children` 里加：

```ts
        { path: 'apps', name: 'apps', component: () => import('@/views/AppStoreView.vue'), meta: { title: '应用商店', group: '平台管理', requiresPerm: [pageCodes.app] } },
```

- [ ] **Step 2: 类型与 API**

`types.ts` 加：

```ts
export interface K8sAppOperation {
  id: number
  appId: string
  action: 'INSTALL' | 'UPGRADE' | 'UNINSTALL'
  status: 'PENDING' | 'RUNNING' | 'SUCCESS' | 'FAILED' | 'UNKNOWN'
  message?: string | null
  logTail?: string | null
  startedAt?: string
  finishedAt?: string
}

export interface K8sApp {
  id: string
  clusterId: string
  namespace: string
  releaseName: string
  chartName: string
  version: string
  displayName: string
  status: 'INSTALLING' | 'RUNNING' | 'FAILED' | 'UNINSTALLED'
  needsReview?: boolean
  valuesJson?: string | null
  createdAt?: string
}

export interface ChartViolation {
  severity: 'BLOCK' | 'WARN'
  type: string
  object: string
  message: string
}

export interface ChartPreviewResult {
  manifest: string
  violations: ChartViolation[]
  objects: { apiVersion: string; kind: string; name: string; namespace?: string | null; scope?: string | null }[]
}
```

`api/index.ts` 加：

```ts
export const appApi = {
  list: (clusterId: string, namespace?: string) =>
    http.post<never, K8sApp[]>('/app/list', { clusterId, namespace }),
  get: (appId: string) => http.post<never, K8sApp>('/app/get', { appId }),
  preview: (payload: {
    clusterId: string
    namespace: string
    chartRef: { source: string; ref: string; chartName: string; version: string; digest?: string }
    values?: string
  }) => http.post<never, ChartPreviewResult>('/app/preview', payload),
  install: (payload: {
    clusterId: string
    namespace: string
    displayName?: string
    chartRef: { source: string; ref: string; chartName: string; version: string; digest?: string }
    values?: string
  }) => http.post<never, number>('/app/install', payload),
  upgrade: (payload: { appId: string; version: string; digest?: string; values?: string }) =>
    http.post<never, number>('/app/upgrade', payload),
  uninstall: (appId: string) => http.post<never, number>('/app/uninstall', { appId }),
  operation: (operationId: number) =>
    http.post<never, K8sAppOperation>('/app/operations/get', { operationId }),
}
```

> `/app/preview` 已在本计划的 Task 6/7 落地（`strict=false`）。安装向导在提交前调用它，
> 把 `severity === 'BLOCK'` 的条目显示为红色并**禁用提交**；`WARN` 用黄色提示但允许继续。

- [ ] **Step 3: 页面**

`AppStoreView.vue` 结构（完整实现）：

```
page-header（标题 + 集群选择 + 刷新）
已安装表格：名称 / 命名空间 / chart / 版本 / 状态 / 创建时间 / 操作（卸载）
「安装应用」按钮（管理员）
安装对话框：chart 来源（OCI 地址 或 repo URL + chart 名）/ 版本 / digest（可选）/ 命名空间 / 显示名 / values（YAML）
「预览」按钮 → 调 `/app/preview`，展示对象清单与 violations（BLOCK 红色且禁用「安装」，WARN 黄色）
提交后轮询 /app/operations/get 直到 SUCCESS/FAILED，失败时用 ElMessage.error 显示 message
```

要点（必须照做）：

- 集群列表来自 `clusterApi.list()`，默认选第一个 `enabled === 1` 的集群；
- 命名空间输入用 `el-input`（管理员可填任意）；计划②再换成下拉并从分配表取；
- 轮询用 `setTimeout` 每 2 秒一次，最多 150 次（5 分钟），组件卸载时清除定时器；
- 提交按钮在操作 RUNNING 时禁用；
- 表格状态用 `el-tag`：RUNNING 绿、FAILED 红、INSTALLING 黄、UNINSTALLED 灰；
- `needsReview` 为真时行尾显示红色「需复核」标签。

- [ ] **Step 4: 类型检查**

Run: `cd platform-web && npm run type-check`
Expected: 无输出（通过）。

- [ ] **Step 5: 浏览器冒烟**

用 `preview_start` 起 dev server，登录后进入「应用商店」：列表能渲染（空态也正常）、安装对话框能打开、
提交后按钮进入 loading。**不要求真装**（真集群验证在 Task 9）。

- [ ] **Step 6: Commit**

```bash
git add platform-web/src/pageCodes.ts platform-web/src/router/index.ts platform-web/src/types.ts platform-web/src/api/index.ts platform-web/src/views/AppStoreView.vue
git commit -m "feat(app): 应用商店页面与安装向导（管理员）"
```

---

### Task 9: 真集群验证、文档与部署

**Files:**
- Modify: `docs/task-ledger-2026-10-12.md`（S3.3/S3.4 段落的实施记录）
- Create: `docs/development/app-store.md`（模块说明：来源、digest、三道闸、常见错误）

- [ ] **Step 1: 重放迁移到开发库**

```java
// D:/tmp/ReplaySql.java —— 本仓没有 mysql CLI，用 JDBC 逐条执行迁移（`;` 后换行分割，本文件里没有字符串内分号）
import java.nio.file.*;
import java.sql.*;

public class ReplaySql {
    public static void main(String[] args) throws Exception {
        String sql = Files.readString(Path.of(args[0]));
        try (Connection c = DriverManager.getConnection(
                "jdbc:mysql://192.168.31.80:1234/k8s_cloud_platform?useSSL=false&allowPublicKeyRetrieval=true",
                "root", "coding");
             Statement st = c.createStatement()) {
            for (String stmt : sql.split(";\\s*\\n")) {
                String s = stmt.trim();
                if (!s.isEmpty()) {
                    st.execute(s);
                }
            }
        }
        System.out.println("replayed");
    }
}
```

```bash
java -cp "C:/Users/wsail/.m2/repository/com/mysql/mysql-connector-j/9.7.0/mysql-connector-j-9.7.0.jar" \
  D:/tmp/ReplaySql.java platform-data/src/main/resources/db/migration/V2026_10_21_1__app_engine.sql
```

Expected: `replayed`

回查（同一连接）：

```sql
show tables like 'platform_app%';
select code, resource, action from platform_permission where code like 'tenant:app:%' or code = 'tenant:page:app';
```

Expected: 3 张表；权限行 8 条。

- [ ] **Step 2: 真集群安装（管理员路径）**

在平台上：集群选择 `coding-cluster` → 应用商店 → 安装应用：
- 来源：Harbor OCI（或公网 repo），chart 任选一个**只含 Deployment/Service**的；
- 命名空间：`default`（管理员可任意）；
- 确认页面上应用状态从 INSTALLING → RUNNING；`kubectl -n default get deploy` 能看到它。

反例（必须都被拒，且提示准确）：
1. 一个显式写了 `metadata.namespace: other` 的 chart → `CROSS_NAMESPACE`；
2. 一个含 `ClusterRole` 的 chart → 管理员路径只告警（`WARN`），安装继续；
3. 卸载：应用详情 → 卸载 → 状态 UNINSTALLED，集群里 release 消失。

- [ ] **Step 3: 文档与台账**

`docs/development/app-store.md` 至少写：chart 来源与 digest 语义、三道闸各自的判据、`helm uninstall` 不删
CRD/PVC、常见失败（拉取失败 / 渲染失败 / digest 不一致 / 跨 ns）。

台账 S3.3/S3.4 段落加一行「计划①完成：引擎与管理員安装（date + commit）」，不改状态为完成（租户与 Operator 在计划②③）。

- [ ] **Step 4: 全量验证**

Run: `mvn -q test`
Expected: 全绿。

Run: `cd platform-web && npm run type-check`
Expected: 通过。

- [ ] **Step 5: Commit**

```bash
git add docs
git commit -m "docs(app): 应用商店模块说明与台账记录"
```

---

## Self-Review

**Spec coverage（计划①应覆盖的部分）**

| spec 条款 | 任务 |
|---|---|
| §3 数据模型（三张表） | Task 1 |
| §3.4 权限行（app 端点） | Task 1、Task 7 |
| §4.1 ChartFetcher（digest、大小上限） | Task 4 |
| §4.2 静态检查（跨 ns / 集群级 / hook / Namespace / 未知 kind） | Task 2、Task 3 |
| §4.3 安装参数（租户 `--skip-crds --no-hooks`；本计划只用到管理员路径） | Task 4、Task 5 |
| §4.4 装后 `helm get manifest` 复核 | Task 5 |
| §4.5 状态机与升级/卸载 | Task 6 |
| §6.1 `/chart/**` | Task 5 |
| §6.2 `/app/**`（除 catalog） | Task 7 |
| §7 前端安装向导（管理员版） | Task 8 |
| §10 部署 | Task 9 |

**计划②将补**：`platform_app_catalog` 的增删查与来源搜索、`/app/catalog/**`、`/app/preview` 的严格模式接入、
租户闸门（分配表 + 目录白名单 + `strict=true`）、命名空间下拉、目录页 UI。
**计划③将补**：Operator 探测/详情/升级与 `platform:page:operator`。

**类型一致性检查**：`ChartRefDTO` 在 Task 5 定义、Task 6 的 client 与 service 复用；`InstallOptions` 在 Task 4
定义、Task 5 使用；`ChartApplyResultDTO` 的 `violations` 在 Task 5 填充、Task 6 读取。
