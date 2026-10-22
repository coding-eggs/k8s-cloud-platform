# 中间件商店 · 批①-1（底座 + Redis 打通）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 下线通用 chart 商店，建立「固定清单 + Operator 实例」的底座，并用 Redis 一条链（Operator 安装 → 单节点/哨兵/集群实例 → 列表/删除）把整条路走通。

**Architecture:** Operator 由管理员经 helm 安装（chart 随 k8s-server 内置）；实例由 platform-api 校验后调 k8s-server 创建 CR；租户身份与分配表校验在 k8s-server 的 `ResourceAccessResolver`；实例台账与操作记录落 platform-data。

**Tech Stack:** Spring Boot 4 / Java 21、fabric8 7.9、MyBatis、Helm 4.2、Vue 3 + Element Plus。

**Spec:** [docs/superpowers/specs/2026-10-22-middleware-store-design.md](../specs/2026-10-22-middleware-store-design.md)

## Global Constraints

- 本仓**没有 Flyway**：`db/migration/*.sql` 手工重放；新端点必须先有权限行，否则 `PermissionCrossCheckRunner` 拒启。
- k8s-server **零业务逻辑**：只做边界、执行与事实读取；目录/配额/租户判定在 platform-api（唯一例外：配额预检需要集群访问，放在 k8s-server，见 Task 6 Ruling）。
- **同类资源不抽公共基类**：每款中间件一个适配器，宁可重复。
- 新权限行**必须显式绑定角色**（不按 code 继承）；共用能力的码用 `tenant:` 前缀并同时绑 `builtin_role_admin`。
- 任意 helm 操作：`--rollback-on-failure --force-conflicts --timeout 10m`（沿用 S3.6）。
- 密码等凭据不进日志、不进 `spec_json`、不进审计。
- 每个任务结束：`mvn -q -pl <module> -am test` 全绿 + 提交。

## Review Focus

- **helm release 名与命名空间**：`platform-middleware-<code>` / `platform-middleware-system`。写错会装出第二个 Operator 或撞 CRD。
- **CRD 归属**：已有 CRD 不是本 release 管理时拒绝安装（避免接管别人的 CRD）。
- **租户身份**：k8s-server 必须走 `resolveNamespacedAccess`，不能用 admin client 替租户写（否则绕过分配表与 K8s RBAC）。
- **凭据**：Secret 名与 CR 引用必须一致；Secret 不出现在返回体。
- **删除语义**：删 CR 是否保留 PVC 按中间件不同；Redis 的 `persistenceEnabled` 与 PVC 保留策略必须写进确认文案。
- **幂等**：重复安装同一 Operator 走 upgrade，不报错；重复删除已删实例返回成功。

---

## Task 1: 下线旧应用商店（目录 + 通用安装）

**Files:**
- Delete:
  - `platform-api/src/main/java/com/coding/platformapi/controllers/AppController.java`
  - `platform-api/src/main/java/com/coding/platformapi/services/AppInstallService.java`
  - `platform-api/src/main/java/com/coding/platformapi/services/AppCatalogService.java`
  - `platform-api/src/main/java/com/coding/platformapi/models/AppInstallRequest.java`
  - `AppUpgradeRequest.java` / `AppUninstallRequest.java` / `AppPreviewRequest.java` / `AppQueryRequest.java` / `AppOperationQuery.java`
  - `AppCatalogCreateRequest.java` / `AppCatalogDeleteRequest.java`
  - `platform-api/src/test/java/com/coding/platformapi/services/AppInstallServiceTest.java`
  - `platform-api/src/test/java/com/coding/platformapi/services/AppCatalogServiceTest.java`
  - `platform-api/src/test/java/com/coding/platformapi/controllers/AppControllerTest.java`
  - `platform-data/src/main/java/com/coding/data/models/system/PlatformAppCatalog.java`
  - `platform-data/src/main/java/com/coding/data/mapper/system/PlatformAppCatalogMapper.java`
  - `platform-data/src/main/resources/mapper/system/PlatformAppCatalogMapper.xml`
  - `platform-data/src/main/resources/db/migration/V2026_10_21_2__app_catalog.sql`
- Keep: `K8sChartClient`、`Chart*DTO`、`HelmRunner`、`ChartFetcher`、`ManifestInspector`、`/chart/**`（Nacos 与 Operator chart 还要用）。
- Keep: `platform_app` / `platform_app_operation` 模型与 mapper（表保留，本计划不再调用，后续清理）。

**Interfaces:**
- Produces: 无（纯删除）。

- [ ] **Step 1: 删除上列文件，并清理引用**

删除后全局搜索并清掉引用：

```bash
grep -rn "AppInstallService\|AppCatalogService\|PlatformAppCatalog\|AppCatalogCreateRequest" platform-api platform-data --include=*.java
```

`AppController` 是 `/app/**` 的唯一入口，删除后 `PermissionCrossCheckRunner` 会把这些权限行判为幽灵行（只 WARN，不影响启动）。

- [ ] **Step 2: 编译与全量测试**

Run: `mvn -q test`
Expected: BUILD SUCCESS（exit 0），无编译错误。

- [ ] **Step 3: 提交**

```bash
git add -A
git commit -m "refactor(middleware): 下线通用 chart 商店（目录/上架/通用安装），保留 /chart 执行链"
```

---

## Task 2: 迁移（三张表 + 权限）

**Files:**
- Create: `platform-data/src/main/resources/db/migration/V2026_10_22_1__middleware_store.sql`

**Interfaces:**
- Produces: 表 `platform_middleware_operator` / `platform_middleware_instance` / `platform_middleware_operation`；权限码 `tenant:middleware:read`、`tenant:middleware:write`、`platform:middleware:manage`。

- [ ] **Step 1: 写迁移**

```sql
-- ============================================================================================
-- 中间件商店 · 批①-1：Operator 状态 / 实例台账 / 操作记录 + 权限行
-- ============================================================================================
-- ⚠️ 本仓没有 Flyway，手工重放；重放前不要起新构建（无权限行的新端点会被拒启）。
-- ⚠️ MySQL 注释 `--` 后面必须跟空白字符。

CREATE TABLE IF NOT EXISTS platform_middleware_operator (
  id            varchar(64)  NOT NULL,
  cluster_id    varchar(64)  NOT NULL,
  code          varchar(32)  NOT NULL COMMENT 'mysql/redis/kafka/elasticsearch/postgresql/rabbitmq/nacos',
  chart_version varchar(64)  NOT NULL,
  status        varchar(16)  NOT NULL COMMENT 'NOT_INSTALLED/INSTALLING/READY/FAILED/DEGRADED',
  message       varchar(1024) NULL,
  installed_at  datetime     NULL,
  updated_at    datetime     NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_mwo_cluster_code (cluster_id, code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='中间件 Operator 安装状态';

CREATE TABLE IF NOT EXISTS platform_middleware_instance (
  id           varchar(64)  NOT NULL,
  cluster_id   varchar(64)  NOT NULL,
  namespace    varchar(128) NOT NULL,
  code         varchar(32)  NOT NULL,
  mode         varchar(16)  NOT NULL COMMENT 'SINGLE/CLUSTER/SENTINEL/STANDALONE/HA',
  name         varchar(63)  NOT NULL COMMENT 'K8s 对象名（平台生成 mw-<code>-<8hex>）',
  display_name varchar(128) NOT NULL,
  version      varchar(32)  NOT NULL,
  spec_json    MEDIUMTEXT   NULL COMMENT '表单规格（不含凭据）',
  status       varchar(16)  NOT NULL COMMENT 'CREATING/READY/DEGRADED/FAILED/DELETING',
  message      varchar(1024) NULL,
  tenant_id    varchar(64)  NULL,
  created_by   varchar(128) NULL,
  created_at   datetime     NOT NULL,
  updated_at   datetime     NOT NULL,
  deleted_at   datetime     NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_mwi_name (cluster_id, namespace, name),
  KEY idx_mwi_code (cluster_id, code, deleted_at),
  KEY idx_mwi_tenant (tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='中间件实例';

CREATE TABLE IF NOT EXISTS platform_middleware_operation (
  id          bigint       NOT NULL AUTO_INCREMENT,
  cluster_id  varchar(64)  NOT NULL,
  instance_id varchar(64)  NOT NULL,
  `action`    varchar(16)  NOT NULL COMMENT 'CREATE/DELETE/SCALE/UPGRADE',
  `status`    varchar(16)  NOT NULL COMMENT 'PENDING/RUNNING/SUCCESS/FAILED/UNKNOWN',
  message     varchar(1024) NULL,
  log_tail    MEDIUMTEXT   NULL,
  `operator`  varchar(128) NULL,
  started_at  datetime     NOT NULL,
  finished_at datetime     NULL,
  running_lock varchar(160) GENERATED ALWAYS AS
               (IF(`status` IN ('PENDING','RUNNING'), CONCAT(cluster_id,'|',instance_id), NULL)) STORED,
  PRIMARY KEY (id),
  UNIQUE KEY uk_mwo_running (running_lock),
  KEY idx_mwo_instance (instance_id, started_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='中间件实例操作';

-- ===== 权限行 =====
INSERT INTO platform_permission (id, domain, resource, action, code, description) VALUES
 ('perm_mw_catalog',        'API','/middleware/catalog',           'POST','tenant:middleware:read',   '中间件清单与 Operator 状态'),
 ('perm_mw_instance_list',  'API','/middleware/instance/list',     'POST','tenant:middleware:read',   '实例列表'),
 ('perm_mw_instance_get',   'API','/middleware/instance/get',      'POST','tenant:middleware:read',   '实例详情'),
 ('perm_mw_op_get',         'API','/middleware/operation/get',     'POST','tenant:middleware:read',   '实例操作（轮询）'),
 ('perm_mw_instance_create','API','/middleware/instance/create',   'POST','tenant:middleware:write',  '创建实例'),
 ('perm_mw_instance_scale', 'API','/middleware/instance/scale',    'POST','tenant:middleware:write',  '扩容实例'),
 ('perm_mw_instance_delete','API','/middleware/instance/delete',   'POST','tenant:middleware:write',  '删除实例'),
 ('perm_mw_operator_install','API','/middleware/operator/install', 'POST','platform:middleware:manage','安装/升级中间件 Operator')
ON DUPLICATE KEY UPDATE
  domain=VALUES(domain), resource=VALUES(resource), action=VALUES(action),
  code=VALUES(code), description=VALUES(description);

-- ===== 角色绑定（显式，不继承）=====
INSERT INTO platform_role_permission (id, role_id, permission_id) VALUES
 ('rp_admin_mw_catalog',        'builtin_role_admin','perm_mw_catalog'),
 ('rp_admin_mw_instance_list',  'builtin_role_admin','perm_mw_instance_list'),
 ('rp_admin_mw_instance_get',   'builtin_role_admin','perm_mw_instance_get'),
 ('rp_admin_mw_op_get',         'builtin_role_admin','perm_mw_op_get'),
 ('rp_admin_mw_instance_create','builtin_role_admin','perm_mw_instance_create'),
 ('rp_admin_mw_instance_scale', 'builtin_role_admin','perm_mw_instance_scale'),
 ('rp_admin_mw_instance_delete','builtin_role_admin','perm_mw_instance_delete'),
 ('rp_admin_mw_operator_install','builtin_role_admin','perm_mw_operator_install'),
 ('rp_tadmin_mw_catalog',        'builtin_role_tenant_admin','perm_mw_catalog'),
 ('rp_tadmin_mw_instance_list',  'builtin_role_tenant_admin','perm_mw_instance_list'),
 ('rp_tadmin_mw_instance_get',   'builtin_role_tenant_admin','perm_mw_instance_get'),
 ('rp_tadmin_mw_op_get',         'builtin_role_tenant_admin','perm_mw_op_get'),
 ('rp_tadmin_mw_instance_create','builtin_role_tenant_admin','perm_mw_instance_create'),
 ('rp_tadmin_mw_instance_scale', 'builtin_role_tenant_admin','perm_mw_instance_scale'),
 ('rp_tadmin_mw_instance_delete','builtin_role_tenant_admin','perm_mw_instance_delete'),
 ('rp_tmember_mw_catalog',       'builtin_role_tenant_member','perm_mw_catalog'),
 ('rp_tmember_mw_instance_list', 'builtin_role_tenant_member','perm_mw_instance_list'),
 ('rp_tmember_mw_instance_get',  'builtin_role_tenant_member','perm_mw_instance_get'),
 ('rp_tmember_mw_op_get',        'builtin_role_tenant_member','perm_mw_op_get')
ON DUPLICATE KEY UPDATE permission_id=VALUES(permission_id);
```

> 页面码复用 `tenant:page:app`（路由 `/apps` 不变，菜单改名「中间件」），本迁移不新增页面码。

- [ ] **Step 2: 校验 SQL 不带中文全角分号、每条语句以分号结束**

Run: `grep -n "；" platform-data/src/main/resources/db/migration/V2026_10_22_1__middleware_store.sql`
Expected: 无输出。

- [ ] **Step 3: 提交**

```bash
git add platform-data/src/main/resources/db/migration/V2026_10_22_1__middleware_store.sql
git commit -m "feat(middleware): 数据表与权限迁移（operator/instance/operation）"
```

---

## Task 3: platform-data 模型与 mapper

**Files:**
- Create: `platform-data/src/main/java/com/coding/data/models/system/PlatformMiddlewareOperator.java`
- Create: `.../PlatformMiddlewareInstance.java`
- Create: `.../PlatformMiddlewareOperation.java`
- Create: `platform-data/src/main/java/com/coding/data/mapper/system/PlatformMiddlewareOperatorMapper.java`
- Create: `.../PlatformMiddlewareInstanceMapper.java`
- Create: `.../PlatformMiddlewareOperationMapper.java`
- Create: `platform-data/src/main/resources/mapper/system/PlatformMiddlewareOperatorMapper.xml`
- Create: `.../PlatformMiddlewareInstanceMapper.xml`
- Create: `.../PlatformMiddlewareOperationMapper.xml`

**Interfaces:**
- Produces（后续任务依赖）：
  - `PlatformMiddlewareInstanceMapper.insert / updateById / selectByPrimaryKey / selectByCluster(clusterId, code) / softDelete(id)`
  - `PlatformMiddlewareOperationMapper.insert / updateById / selectByPrimaryKey / selectStaleRunning(int minutes)`
  - `PlatformMiddlewareOperatorMapper.insert / updateById / selectByClusterAndCode(clusterId, code) / selectByCluster(clusterId)`

- [ ] **Step 1: 三个模型（Lombok `@Data`，字段与 Task 2 列一一对应）**

`PlatformMiddlewareOperator`：`id, clusterId, code, chartVersion, status, message, installedAt, updatedAt`。
`PlatformMiddlewareInstance`：`id, clusterId, namespace, code, mode, name, displayName, version, specJson, status, message, tenantId, createdBy, createdAt, updatedAt, deletedAt`。
`PlatformMiddlewareOperation`：`id(Long), clusterId, instanceId, action, status, message, logTail, operator, startedAt, finishedAt`。
（`running_lock` 是生成列，模型里不出现。）

- [ ] **Step 2: 三个 mapper 接口（按 Task 3 Interfaces 的方法签名）**

`selectStaleRunning` 的 SQL 参照 `PlatformAppOperationMapper.xml` 的写法：按 `started_at < now() - interval #{minutes} minute` 且 `status IN ('PENDING','RUNNING')`。

- [ ] **Step 3: 三个 XML（照 `PlatformAppMapper.xml` 的 resultMap/列清单模式）**

`platform_middleware_instance` 的 `selectByCluster`：

```xml
<select id="selectByCluster" resultMap="BaseResultMap">
  select <include refid="Base_Column_List" />
  from platform_middleware_instance
  where cluster_id = #{clusterId,jdbcType=VARCHAR}
    and deleted_at is null
  <if test="code != null and code != ''">
    and code = #{code,jdbcType=VARCHAR}
  </if>
  order by created_at desc
</select>
```

`platform_middleware_instance.name`、`status`、`action`、`operator` 等 MySQL 关键字列一律加反引号。

- [ ] **Step 4: 编译**

Run: `mvn -q -pl platform-data -am -DskipTests install`
Expected: BUILD SUCCESS。

- [ ] **Step 5: 提交**

```bash
git add platform-data
git commit -m "feat(middleware): 模型与 mapper"
```

---

## Task 4: platform-common DTO

**Files:**
- Create: `platform-common/src/main/java/com/coding/common/models/k8s/dto/middleware/*.java`（6 个）
- Create: `platform-common/src/main/java/com/coding/common/models/middleware/MiddlewareCatalogItemDTO.java`（api ↔ 前端）

**Interfaces（Produces）:**

```java
// 前端 → api
public class MiddlewareCreateRequest { clusterId, code, mode, namespace, displayName, version,
                                       Integer replicas, String cpuRequest, String cpuLimit,
                                       String memoryRequest, String memoryLimit,
                                       String storageSize, String storageClass, String imagePullSecret }

// api → k8s-server（多一个 password，由 api 生成）
public class MiddlewareCreateRequestDTO extends MiddlewareCreateRequest { String instanceId; String name; String password; }

public class MiddlewareScaleRequestDTO { String clusterId, code, namespace, name; Integer replicas;
                                        String cpuRequest, cpuLimit, memoryRequest, memoryLimit, storageSize; }

public class MiddlewareDeleteRequestDTO { String clusterId, code, namespace, name; boolean keepPvc; }

public class MiddlewareStatusRequestDTO { String clusterId, code; String namespace; List<String> names; }
public class MiddlewareStatusResultDTO { String name; String status; String message; Integer readyReplicas; Integer replicas; }

// Operator
public class MiddlewareOperatorStatusDTO { String code; boolean installed; boolean ready; String chartVersion; String message; }
public class MiddlewareOperatorInstallRequestDTO { String clusterId; String code; }
```

- [ ] **Step 1: 写 DTO**（Lombok `@Data`，`MiddlewareCreateRequestDTO` 的 `password` 加 `@JsonIgnore` 到 `toString` 由 Lombok 处理；不写日志。）
- [ ] **Step 2: 编译**

Run: `mvn -q -pl platform-common -am -DskipTests install`
Expected: BUILD SUCCESS。

- [ ] **Step 3: 提交**

```bash
git add platform-common
git commit -m "feat(middleware): 共享 DTO"
```

---

## Task 5: k8s-server Operator 安装与状态

**Files:**
- Modify: `k8s-server/src/main/java/com/coding/k8sserver/helm/ChartBundle.java`（加 area 重载）
- Create: `k8s-server/src/main/java/com/coding/k8sserver/middleware/MiddlewareOperatorCatalog.java`
- Create: `.../middleware/MiddlewareOperatorInstaller.java`
- Create: `k8s-server/src/main/java/com/coding/k8sserver/controllers/middleware/MiddlewareOperatorController.java`
- Test: `k8s-server/src/test/java/com/coding/k8sserver/middleware/MiddlewareOperatorInstallerTest.java`
- Create chart dir: `k8s-server/src/main/resources/charts/middleware/redis/<operator-chart-version>/chart/`（从 `ot-container-kit` helm 仓库拉取后原样放入，并附 `README.md` 记录来源与版本）

**Interfaces:**
- Consumes: `KubernetesClientFactory`、`ChartBundle`、`HelmRunner`。
- Produces:
  - `MiddlewareOperatorCatalog.version(code)`、`releaseName(code)`、`namespace()`、`requiredCrds(code)`
  - `MiddlewareOperatorInstaller.status(clusterId, code) → MiddlewareOperatorStatusDTO`
  - `MiddlewareOperatorInstaller.install(clusterId, code) → MiddlewareOperatorStatusDTO`
  - `POST /middleware/operators/status`、`POST /middleware/operators/install`（PLATFORM 边界）

- [ ] **Step 1: 写失败测试**

```java
class MiddlewareOperatorInstallerTest {

    private final HelmRunner helm = mock(HelmRunner.class);
    private final ChartBundle charts = mock(ChartBundle.class);
    private final KubernetesClientFactory clients = mock(KubernetesClientFactory.class);
    private final MiddlewareOperatorInstaller installer =
            new MiddlewareOperatorInstaller(helm, charts, clients);

    @Test
    void installUsesThePinnedReleaseNameAndNamespace() throws Exception {
        Path chart = Files.createTempDirectory("chart");
        when(charts.chartDir("middleware", "redis", "0.27.0")).thenReturn(chart);
        when(clients.adminKubeconfig("c1")).thenReturn("kc");
        when(helm.apply(eq("kc"), eq(chart), any(), eq("platform-middleware-redis"),
                eq("platform-middleware-system"), any(HelmRunner.InstallOptions.class)))
                .thenReturn(new HelmResult(true, 0, "deployed"));

        installer.install("c1", "redis");

        verify(helm).apply(eq("kc"), eq(chart), any(), eq("platform-middleware-redis"),
                eq("platform-middleware-system"), any(HelmRunner.InstallOptions.class));
    }

    @Test
    void unknownCodeIsRejected() {
        assertThatThrownBy(() -> installer.install("c1", "minio")).hasMessageContaining("不支持");
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -q -pl k8s-server -am -Dtest=MiddlewareOperatorInstallerTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: 编译失败（类不存在）。

- [ ] **Step 3: `ChartBundle` 加重载**

```java
public Path chartDir(String code, String version) {
    return chartDir("components", code, version);
}

public Path chartDir(String area, String code, String version) {
    // 原有校验不变，把 root.resolve("components") 换成 root.resolve(area)
}
```

- [ ] **Step 4: `MiddlewareOperatorCatalog`**

```java
@Component
public class MiddlewareOperatorCatalog {

    public record Entry(String code, String chartVersion, List<String> requiredCrds) {}

    private static final Map<String, Entry> ENTRIES = Map.of(
            "redis", new Entry("redis", "0.27.0", List.of(
                    "redis.redis.opstreelabs.in",
                    "redisreplications.redis.redis.opstreelabs.in",
                    "redissentinels.redis.redis.opstreelabs.in",
                    "redisclusters.redis.redis.opstreelabs.in")));

    public static final String NAMESPACE = "platform-middleware-system";
    public static String releaseName(String code) { return "platform-middleware-" + code; }
    public Entry require(String code) { ... 不支持 → CloudPlatformException("不支持的中间件：" + code) ... }
}
```

- [ ] **Step 5: `MiddlewareOperatorInstaller`**

```java
@Component
public class MiddlewareOperatorInstaller {

    private final HelmRunner helm;
    private final ChartBundle charts;
    private final KubernetesClientFactory clients;
    private final MiddlewareOperatorCatalog catalog;

    public MiddlewareOperatorStatusDTO install(String clusterId, String code) {
        var entry = catalog.require(code);
        String kubeconfig = clients.adminKubeconfig(clusterId);
        ensureNamespace(clusterId);
        Path chartDir = charts.chartDir("middleware", entry.code(), entry.chartVersion());
        try {
            Path values = Files.createTempFile("mw-operator-", ".yaml");
            Files.writeString(values, "");
            HelmResult r = helm.apply(kubeconfig, chartDir, values,
                    MiddlewareOperatorCatalog.releaseName(code), MiddlewareOperatorCatalog.NAMESPACE,
                    new HelmRunner.InstallOptions(false, false,
                            Map.of("platform.coding/managed-by", "middleware-operator")));
            if (!r.ok()) {
                throw new CloudPlatformException(EnumResponseType.ERROR,
                        "Operator 安装失败：" + tail(r.output()));
            }
        } catch (IOException e) { ... }
        return status(clusterId, code);
    }

    public MiddlewareOperatorStatusDTO status(String clusterId, String code) {
        var entry = catalog.require(code);
        KubernetesClient client = clients.getAdminClient(clusterId);
        boolean crds = entry.requiredCrds().stream()
                .allMatch(n -> client.apiextensions().v1().customResourceDefinitions().withName(n).get() != null);
        var deps = client.apps().deployments().inNamespace(MiddlewareOperatorCatalog.NAMESPACE)
                .withLabel("app.kubernetes.io/instance", MiddlewareOperatorCatalog.releaseName(code)).list().getItems();
        boolean ready = crds && !deps.isEmpty() && deps.stream().allMatch(d -> {
            var st = d.getStatus();
            var spec = d.getSpec();
            return st != null && st.getReadyReplicas() != null
                    && Objects.equals(st.getReadyReplicas(), spec.getReplicas());
        });
        ...组装 DTO（installed = crds || !deps.isEmpty()；ready；message）...
    }

    private void ensureNamespace(String clusterId) {
        var client = clients.getAdminClient(clusterId);
        if (client.namespaces().withName(MiddlewareOperatorCatalog.NAMESPACE).get() == null) {
            client.namespaces().resource(new NamespaceBuilder()
                    .withNewMetadata().withName(MiddlewareOperatorCatalog.NAMESPACE).endMetadata().build()).create();
        }
    }
}
```

- [ ] **Step 6: Controller**

```java
@RestController
@RequestMapping("/middleware/operators")
public class MiddlewareOperatorController implements AccessBoundaryAware {

    @Override public AccessBoundary accessBoundary() { return AccessBoundary.PLATFORM; }

    @PostMapping("/status")
    public ResponseData<List<MiddlewareOperatorStatusDTO>> status(@RequestBody MiddlewareOperatorStatusRequestDTO req) { ... }

    @PostMapping("/install")
    public ResponseData<MiddlewareOperatorStatusDTO> install(@RequestBody MiddlewareOperatorInstallRequestDTO req) { ... }
}
```

- [ ] **Step 7: 运行测试**

Run: `mvn -q -pl k8s-server -am -Dtest=MiddlewareOperatorInstallerTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: PASS。

- [ ] **Step 8: 准备 chart 目录**

从 `ot-container-kit` Helm 仓库拉取 `redis-operator` chart（版本与 `MiddlewareOperatorCatalog` 一致），解压到
`k8s-server/src/main/resources/charts/middleware/redis/<version>/chart/`，并写 `README.md`（来源 URL、版本、拉取日期）。
`ChartBundle` 要求目录下有 `Chart.yaml`。

- [ ] **Step 9: 提交**

```bash
git add k8s-server
git commit -m "feat(middleware): Operator 安装与状态（PLATFORM 边界）"
```

---

## Task 6: k8s-server 实例链（Redis 适配器）

**Files:**
- Create: `k8s-server/src/main/java/com/coding/k8sserver/middleware/MiddlewareAdapter.java`
- Create: `.../middleware/RedisMiddlewareAdapter.java`
- Create: `.../middleware/MiddlewareInstanceService.java`
- Create: `k8s-server/src/main/java/com/coding/k8sserver/controllers/middleware/MiddlewareInstanceController.java`
- Test: `k8s-server/src/test/java/com/coding/k8sserver/middleware/RedisMiddlewareAdapterTest.java`

**Interfaces:**
- Consumes: `ResourceAccessResolver`、`KubernetesClientFactory`、Task 4 DTO。
- Produces:
  - `MiddlewareAdapter.code()`、`buildCreate(MiddlewareCreateRequestDTO, String secretName) → List<GenericKubernetesResource>`、`statusSelector(String name) → String`、`deleteContexts()`
  - `POST /middleware/{code}/instances`、`/instances/scale`、`/instances/delete`、`/status`（TENANT 边界）

- [ ] **Step 1: 失败测试（表驱动）**

```java
class RedisMiddlewareAdapterTest {

    private final RedisMiddlewareAdapter adapter = new RedisMiddlewareAdapter();

    @Test
    void standaloneBuildsOneRedisResource() {
        var req = req("STANDALONE", 1);
        var out = adapter.buildCreate(req, "mw-redis-abc12345");

        assertThat(out).hasSize(1);
        assertThat(out.get(0).getApiVersion()).isEqualTo("redis.redis.opstreelabs.in/v1beta2");
        assertThat(out.get(0).getKind()).isEqualTo("Redis");
        assertThat(out.get(0).getMetadata().getName()).isEqualTo("mw-redis-abc12345");
    }

    @Test
    void sentinelBuildsReplicationAndSentinel() {
        var out = adapter.buildCreate(req("SENTINEL", 3), "mw-redis-abc12345");

        assertThat(out).extracting(GenericKubernetesResource::getKind)
                .containsExactly("RedisReplication", "RedisSentinel");
        assertThat(spec(out.get(1))).containsEntry("clusterSize", 3)
                .containsEntry("redisReplicationName", "mw-redis-abc12345");
    }

    @Test
    void clusterBuildsRedisClusterWithReplicas() {
        var out = adapter.buildCreate(req("CLUSTER", 3), "mw-redis-abc12345");

        assertThat(out.get(0).getKind()).isEqualTo("RedisCluster");
        assertThat(spec(out.get(0))).containsEntry("clusterSize", 3);
    }

    @Test
    void passwordIsReferencedBySecretNotInlined() {
        var req = req("STANDALONE", 1);
        req.setPassword("s3cret");
        var out = adapter.buildCreate(req, "mw-redis-abc12345");

        assertThat(out.get(0).toString()).doesNotContain("s3cret");
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -q -pl k8s-server -am -Dtest=RedisMiddlewareAdapterTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: 编译失败。

- [ ] **Step 3: 实现适配器**

要点：

- `RApiVersion = "redis.redis.opstreelabs.in/v1beta2"`；
- 三种 mode：
  - `STANDALONE` → 1 个 `Redis`；
  - `SENTINEL` → `RedisReplication`（`clusterSize = replicas`）+ `RedisSentinel`（`clusterSize = 3`，`redisSentinelConfig.redisReplicationName = name`）；
  - `CLUSTER` → `RedisCluster`（`clusterSize = replicas`）；
- 公共 spec：`kubernetesConfig.image`（默认 `quay.io/opstree/redis:v7`）、`kubernetesConfig.resources`（requests/limits）、
  `kubernetesConfig.redisSecret = {name: <name>-credentials, key: password}`；
- `persistenceEnabled` 按 `storageSize` 是否非空；`storage` 走 `storageClassName` + `resources.requests.storage`（按 v1beta2 的字段名，如与 sample 不一致以实现时的 CRD 为准，并更新本文件）；
- 对象名与 namespace = 请求里的；labels 加 `platform.coding/managed-by=middleware`、`platform.coding/middleware-instance=<instanceId>`；
- **密码只写 Secret**：`MiddlewareInstanceService` 在创建 CR 前用 admin/tenant client 创建
  `Secret(name + "-credentials", data.password = base64)`，适配器只在 spec 里引用 Secret 名。

- [ ] **Step 4: `MiddlewareInstanceService`**

```java
public void create(MiddlewareCreateRequestDTO req) {
    var ctx = accessResolver.resolveNamespacedAccess(null, req.getClusterId(), req.getNamespace());
    KubernetesClient client = ctx.adminMode() ? clients.getAdminClient(req.getClusterId())
                                              : clients.getTenantClient(req.getClusterId());
    quotaCheck(client, ctx, req);          // Ruling：配额预检放在本层（唯一有集群访问的一侧）
    createCredentialSecret(client, req);
    for (var res : adapter(req.getCode()).buildCreate(req, secretName(req))) {
        client.genericKubernetesResources(contextOf(res)).inNamespace(req.getNamespace()).resource(res).create();
    }
}
```

- `contextOf`：按 `apiVersion/kind/复数` 构建 `ResourceDefinitionContext`（group、version、plural、namespaced=true）；
- `quotaCheck`：读 `ResourceQuota`，估算 `replicas × requests`，超限抛 `CloudPlatformException` 并给出差额；
- `scale`：读取现有 CR → 改 `clusterSize` + `kubernetesConfig.resources` → `update()`；
- `delete`：按 `keepPvc` 决定是否保留 PVC（Redis 侧看 `persistenceEnabled` 与 operator 的 PVC 保留策略）；
- `status`：按 `adapter.statusSelector(name)` 列出 Pod，`ready = 全部 Pod Ready`；CR 不存在 → `FAILED`（附 message）。

- [ ] **Step 5: Controller（TENANT 边界）**

```java
@RestController
@RequestMapping("/middleware/{code}")
public class MiddlewareInstanceController implements AccessBoundaryAware {

    @Override public AccessBoundary accessBoundary() { return AccessBoundary.TENANT; }

    @PostMapping("/instances")        // create
    @PostMapping("/instances/scale")
    @PostMapping("/instances/delete")
    @PostMapping("/status")
}
```

每个 handler 内调用 `resolveNamespacedAccess(null, clusterId, namespace)`；不认识的 code 返回 400。

- [ ] **Step 6: 运行测试**

Run: `mvn -q -pl k8s-server -am test`
Expected: 全绿。

- [ ] **Step 7: 提交**

```bash
git add k8s-server
git commit -m "feat(middleware): 实例创建/扩容/删除/状态与 Redis 适配器"
```

---

## Task 7: platform-api 目录、实例服务与端点

**Files:**
- Create: `platform-api/src/main/java/com/coding/platformapi/middleware/MiddlewareCatalog.java`
- Create: `platform-api/src/main/java/com/coding/platformapi/k8s/K8sMiddlewareClient.java`
- Create: `platform-api/src/main/java/com/coding/platformapi/services/MiddlewareService.java`
- Create: `platform-api/src/main/java/com/coding/platformapi/controllers/MiddlewareController.java`
- Create: `platform-api/src/main/java/com/coding/platformapi/models/Middleware*Request.java`（4 个）
- Test: `platform-api/src/test/java/com/coding/platformapi/services/MiddlewareServiceTest.java`

**Interfaces:**
- Consumes: Task 3 mappers、Task 4 DTO、`K8sMiddlewareClient`。
- Produces: `/middleware/**` 8 个端点（spec §6.2）。

- [ ] **Step 1: 失败测试**

```java
class MiddlewareServiceTest {

    @Test
    void createRejectsWhenOperatorIsNotReady() {
        when(client.operatorStatus(any())).thenReturn(List.of(status("redis", false, false)));
        var req = createRequest();
        assertThatThrownBy(() -> service.create(req, "admin", null))
                .hasMessageContaining("Operator");
    }

    @Test
    void createGeneratesNameAndPasswordButStoresNoPassword() throws Exception {
        when(client.operatorStatus(any())).thenReturn(List.of(status("redis", true, true)));
        when(client.create(any())).thenReturn(new MiddlewareStatusResultDTO());

        service.create(createRequest(), "admin", null);

        ArgumentCaptor<MiddlewareCreateRequestDTO> sent = ArgumentCaptor.forClass(MiddlewareCreateRequestDTO.class);
        verify(client).create(sent.capture());
        assertThat(sent.getValue().getName()).startsWith("mw-redis-");
        assertThat(sent.getValue().getPassword()).isNotBlank();

        ArgumentCaptor<PlatformMiddlewareInstance> saved = ArgumentCaptor.forClass(PlatformMiddlewareInstance.class);
        verify(instances).insert(saved.capture());
        assertThat(saved.getValue().getSpecJson()).doesNotContain(sent.getValue().getPassword());
    }

    @Test
    void catalogMergesOperatorStatusIntoItems() {
        when(client.operatorStatus(any())).thenReturn(List.of(status("redis", true, true)));
        var items = service.catalog("c1");
        assertThat(items).anyMatch(i -> i.getCode().equals("redis") && i.isOperatorReady());
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -q -pl platform-api -am -Dtest=MiddlewareServiceTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: 编译失败。

- [ ] **Step 3: `MiddlewareCatalog`（api 侧展示元数据）**

```java
public record Item(String code, String name, String description, List<Mode> modes) {}
public record Mode(String mode, String label, int minReplicas, String help) {}

private static final List<Item> ITEMS = List.of(
    new Item("redis", "Redis", "内存键值存储", List.of(
        new Mode("STANDALONE", "单节点", 1, "开发/测试"),
        new Mode("SENTINEL", "哨兵主从", 3, "高可用，≥3 哨兵"),
        new Mode("CLUSTER", "集群分片", 3, "≥3 主分片"))),
    new Item("mysql", "MySQL", "关系型数据库", List.of()),
    ...其余 5 项 modes 先留空（批①-2 补齐；前端对空 modes 显示"即将支持"）
);
```

- [ ] **Step 4: `K8sMiddlewareClient`（照 `K8sChartClient`）**

`operatorStatus / operatorInstall / create / scale / delete / status`，全部 `gateway.exchange`；create 用 `exchangeSlow`（可能要等 Operator 响应）。

- [ ] **Step 5: `MiddlewareService`**

- `catalog(clusterId)`：合并 Operator 状态；
- `create(req, operator, tenantId)`：校验 code/mode、Operator ready、生成 `name = "mw-<code>-<8hex>"`、生成 32 位随机密码、
  写 `platform_middleware_instance(CREATING)` + `operation(PENDING)` → `K8sServerGateway.runAs(callerAuthorization(), …)` 异步调 k8s-server → 更新状态与操作；
- `scale / delete` 同构；
- `list(clusterId, namespace, tenantId)`：管理员看全部，租户按 `tenant_id` + 分配表收窄；
- 租户校验：租户上下文（token 有 tenantInfo）时 `tenantId` 取 token；管理员可显式传 tenantId 代操作。

- [ ] **Step 6: `MiddlewareController` + 审计**

```java
@PostMapping("/catalog")          // tenant:middleware:read
@PostMapping("/instance/list")    // tenant:middleware:read
@PostMapping("/instance/get")     // tenant:middleware:read
@PostMapping("/instance/create")  // @Auditable(action = AuditAction.MIDDLEWARE_CREATE, target = "#request.code")
@PostMapping("/instance/scale")
@PostMapping("/instance/delete")
@PostMapping("/operation/get")    // tenant:middleware:read
@PostMapping("/operator/install") // platform:middleware:manage, @Auditable
```

`AuditAction` 新增 `MIDDLEWARE_CREATE = "middleware:create"`、`MIDDLEWARE_SCALE`、`MIDDLEWARE_DELETE`、`MIDDLEWARE_OPERATOR_INSTALL`。

- [ ] **Step 7: 运行测试**

Run: `mvn -q -pl platform-api -am test`
Expected: 全绿。

- [ ] **Step 8: 提交**

```bash
git add platform-api
git commit -m "feat(middleware): 目录/实例服务与端点"
```

---

## Task 8: 前端（中间件商店页）

**Files:**
- Delete: `platform-web/src/views/AppStoreView.vue`
- Create: `platform-web/src/views/MiddlewareStoreView.vue`
- Create: `platform-web/src/components/middleware/MiddlewareIcon.vue`（复用 `components/app/AppIcon.vue` 的样式，改名并补 mysql/pg/rabbitmq/nacos 映射）
- Create: `platform-web/src/components/middleware/middlewareForms.ts`（字段描述符）
- Modify: `platform-web/src/router/index.ts`（`/apps` 指向新页面）
- Modify: `platform-web/src/components/layout/MainLayout.vue`（菜单文案「应用商店」→「中间件」）
- Modify: `platform-web/src/api/index.ts` + `platform-web/src/types.ts`
- Verify: `npx vue-tsc --noEmit`；scratch 页预览（Task 8 Step 5）

**Interfaces:**
- Consumes: `/middleware/**` 端点。
- Produces: 三个 tab：商店 / 实例 / Operator（管理员）。

- [ ] **Step 1: types + api**

```ts
export interface MiddlewareMode { mode: string; label: string; minReplicas: number; help?: string | null }
export interface MiddlewareCatalogItem {
  code: string; name: string; description?: string | null;
  modes: MiddlewareMode[];
  operatorInstalled: boolean; operatorReady: boolean; chartVersion?: string | null; operatorMessage?: string | null;
  instanceCount: number;
}
export interface MiddlewareInstance {
  id: string; clusterId: string; namespace: string; code: string; mode: string; name: string;
  displayName: string; version: string; status: string; message?: string | null;
  createdBy?: string | null; createdAt?: string | null;
}
```

`middlewareApi = { catalog, instanceList, instanceGet, create, scale, remove, operation, operatorInstall }`。

- [ ] **Step 2: 表单描述符**

```ts
export interface Field { key: string; label: string; type: 'number' | 'text' | 'select'; default?: any;
                         min?: number; max?: number; options?: {label: string; value: any}[]; help?: string }

export const FIELDS: Record<string, Field[]> = {
  redis: [
    { key: 'replicas', label: '节点数', type: 'number', default: 1, min: 1, max: 9 },
    { key: 'cpuRequest', label: 'CPU 请求', type: 'text', default: '100m' },
    { key: 'cpuLimit', label: 'CPU 上限', type: 'text', default: '500m' },
    { key: 'memoryRequest', label: '内存请求', type: 'text', default: '128Mi' },
    { key: 'memoryLimit', label: '内存上限', type: 'text', default: '512Mi' },
    { key: 'storageSize', label: '存储', type: 'text', default: '10Gi', help: '留空 = 不持久化' },
    { key: 'storageClass', label: 'StorageClass', type: 'text', default: '' },
  ],
}
```

- [ ] **Step 3: 页面**

- 卡片网格：`catalog` 里的每一项；`modes.length === 0` → 「即将支持」禁用；`!operatorReady` → 禁用并提示
  「需要管理员先安装 X Operator」（管理员额外显示「安装 Operator」）；
- 点卡片 → 创建对话框（namespace / displayName / mode + 描述符字段）；
- 实例 tab：统一列表（类型/名称/命名空间/形态/版本/状态/创建时间，操作：删除）；
- Operator tab（仅 `perm.isAdmin`）：状态、版本、安装/升级；
- 轮询：沿用组件 tab 的 2 秒轮询与 5 分钟上限。

- [ ] **Step 4: 类型检查**

Run: `cd platform-web && npx vue-tsc --noEmit`
Expected: 无输出（通过）。

- [ ] **Step 5: 浏览器预览（桩数据）**

按既有 scratch 方案：`scratch.vite.config.ts` 别名替换 `@/api` 与 `@/stores/permission`，挂载真实组件，
验证卡片三态（即将支持 / 未装 Operator / 可用）、创建对话框字段、实例列表。验证后删除 scratch 文件。

- [ ] **Step 6: 提交**

```bash
git add platform-web
git commit -m "feat(middleware): 中间件商店页面（卡片/实例/Operator）"
```

---

## Task 9: 文档与部署说明

**Files:**
- Create: `docs/development/middleware-store.md`
- Modify: `docs/superpowers/specs/2026-10-22-middleware-store-design.md`（如有实现期偏差）
- Ledger: `.superpowers/sdd/2026-10-22-middleware-store-batch1/progress.md`

- [ ] **Step 1: 写文档**：执行链、Operator 清单与版本、实例形态表、租户权限前提（CRD apiGroup + 重下发模板）、
  常见失败（Operator 未装、镜像拉不到、配额不足、Nacos 无 MySQL）。
- [ ] **Step 2: 提交**

```bash
git add docs .superpowers 2>/dev/null || git add docs
git commit -m "docs(middleware): 中间件商店说明与台账"
```

---

## 后续计划（不在本计划内）

| 计划 | 内容 |
|---|---|
| 批①-2 | MySQL / Kafka / ES 适配器 + Nacos（chart 链 + 依赖 MySQL 实例） |
| 批①-3 | 资源保护规则（平台组件 / 实例资源只读，前后端） |
| 批② | PostgreSQL / RabbitMQ |

每份计划都以本计划建立的框架为模板，只新增适配器、表单描述符与目录条目。
