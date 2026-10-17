# 组件生命周期 · 批①（引擎 + metrics-server 试点）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让管理员能在集群详情页对 `metrics-server` 一键安装/升级：一条异步操作记录、k8s-server 内置 chart 与 helm 执行链、前端轮询进度，装完以 S1.2 探测为准刷新状态。

**Architecture:** platform-api 解析 recipe（业务配置）→ 落 `platform_component_operation` → 虚拟线程调 k8s-server（PLATFORM 边界）→ `HelmRunner` 用内置 helm 4.2.0 对内置 chart 目录执行 `helm upgrade --install` → 回写操作状态 → 触发组件探测刷新。chart 以**目录形态**内置在 k8s-server（非 tgz），git 里是可读文本。

**Tech Stack:** Java 21 / Spring Boot 4.1.1 / MyBatis / MySQL（**无 Flyway，手工重放**）/ Helm 4.2.0（k8s-server 镜像内置）/ Vue 3 + Element Plus + TypeScript

**Spec:** `docs/superpowers/specs/2026-10-17-component-lifecycle-design.md` —— 本计划从它派生；冲突时**以 spec 为准**。

## Global Constraints

- **只做目录内组件；批① 只接 `metrics-server` 一条**（试点）。其余 10 条在批②。
- **只做安装 + 升级**，不做卸载/回滚按钮。
- **`platform:cluster:manage` + PLATFORM 边界**：安装是 cluster-admin 级写入，租户不可见。
- **release 名与命名空间由 recipe 固定**：`platform-metrics-server` / `kube-system`，用户不能改。
- **只升级平台自己装的 release**（平台操作记录里有 release 名）；手工装的一律只读。
- **成功判定以 S1.2 探测为准**：helm exit 0 只代表下发成功。
- **Helm 4 用 `--rollback-on-failure`**（3.x 的 `--atomic` 在 4.x 不存在）。
- **预检分级**：`helm template` 硬失败；`--dry-run=server` 只记警告（CRD 假阴性）。
- **chart 目录内置在 k8s-server**：`k8s-server/charts/components/<code>/<version>/chart/`。
- **本仓没有 Flyway**：迁移手工重放；启动日志永远没有 `Migrating schema`。**先重放再起新构建**。
- **新权限行必须显式绑角色**（不按 code 继承）。
- 中文注释与提交信息；技术名词保留英文。

## Review Focus

按可能性降序。每一条都必须在下面某个任务的测试里被钉住。

1. **helm 参数写错导致"看起来成功、实际什么都没装"** —— 例如漏 `--install`、漏 `--create-namespace`、用了 Helm 3 的 `--atomic`。→ Task 4 Step 1 用假 helm 脚本断言完整 argv。
2. **临时 kubeconfig 泄漏**（异常路径没删 / 权限不是 0600）—— 它是 cluster-admin 明文。→ Task 4 Step 1 断言 finally 删除 + 文件权限。
3. **操作永远停在 RUNNING**（进程重启 / helm 卡住）—— 用户不知道到底装没装。→ Task 7 Step 1 的"超时/重启"用例 + Task 7 实现里的 stale 标定。
4. **重复点击安装**（同一组件并发两次操作）→ 两个 helm 进程互相踩。→ Task 7 Step 1 的"同组件有 RUNNING 时拒绝新操作"用例。
5. **chart 版本漂移**（platform-api 的 recipe 指向 k8s-server 里不存在的版本）→ k8s-server 必须明确报错而不是回退到别的版本。→ Task 5 Step 1 + Task 6 Step 1 的跨模块一致性测试。

---

### Task 1: 数据库迁移（cluster_role + 操作表 + 权限）

**Files:**
- Create: `platform-data/src/main/resources/db/migration/V2026_10_18_1__component_lifecycle.sql`
- Create: `platform-api/src/test/java/com/coding/platformapi/components/ComponentLifecycleMigrationTest.java`

**Interfaces:**
- Produces: 表 `platform_component_operation`；列 `k8s_cluster.cluster_role`；两条权限行 `/cluster/components/apply`、`/cluster/components/operations/get`、`/cluster/components/operations/list`。

- [ ] **Step 1: 写失败的测试**

```java
package com.coding.platformapi.components;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 批①的两个迁移约束：cluster_role 列 + 三条新端点的权限行。
 * 读 SQL 文件而不是连库：这条护栏要在任何环境下都能跑。
 */
class ComponentLifecycleMigrationTest {

    private static final Path SQL = Path.of(
            "../platform-data/src/main/resources/db/migration/V2026_10_18_1__component_lifecycle.sql");

    @Test
    void clusterRoleColumnIsAddedWithBusinessDefault() throws Exception {
        String sql = Files.readString(SQL);
        assertThat(sql).containsIgnoringCase("add column cluster_role");
        assertThat(sql).containsIgnoringCase("default 'BUSINESS'");
    }

    @Test
    void theOperationTableExists() throws Exception {
        String sql = Files.readString(SQL);
        assertThat(sql).containsIgnoringCase("create table if not exists platform_component_operation");
        for (String col : new String[]{"cluster_id", "component_code", "action", "profile",
                "from_version", "to_version", "release_names", "values_snapshot",
                "status", "message", "log_tail", "operator", "started_at", "finished_at"}) {
            assertThat(sql).as("缺列 %s", col).contains(col);
        }
    }

    @Test
    void allThreeEndpointsHavePermissionRowsBoundToAdmin() throws Exception {
        String sql = Files.readString(SQL);
        assertThat(sql).contains("'/cluster/components/apply'");
        assertThat(sql).contains("'/cluster/components/operations/get'");
        assertThat(sql).contains("'/cluster/components/operations/list'");
        assertThat(sql).contains("builtin_role_admin");
        // 三条都是 platform:cluster:manage
        assertThat(count(sql, "'platform:cluster:manage'")).isGreaterThanOrEqualTo(3);
    }

    private static int count(String haystack, String needle) {
        int n = 0;
        int i = 0;
        while ((i = haystack.indexOf(needle, i)) >= 0) {
            n++;
            i += needle.length();
        }
        return n;
    }
}
```

- [ ] **Step 2: 跑测试确认它失败**

Run: `mvn -pl platform-api test -Dtest=ComponentLifecycleMigrationTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL —— 文件不存在（`NoSuchFileException`）。

- [ ] **Step 3: 写迁移 SQL**

```sql
-- ============================================================================================
-- S3.6 批①：组件生命周期（安装 / 升级）
-- ============================================================================================
-- 内容：
--   1) k8s_cluster.cluster_role —— 画像（MANAGEMENT / BUSINESS），决定组件 recipe 取哪套
--   2) platform_component_operation —— 一次安装/升级操作的记录（异步状态 + 失败原因）
--   3) 三条端点权限行：apply / operations/get / operations/list
--
-- ⚠️ 部署顺序：先重放本文件再起新构建 —— 无行的新端点会被 PermissionCrossCheckRunner 拒启。
-- ⚠️ 本仓没有 Flyway，手工重放。
-- 幂等：列用 IF NOT EXISTS；权限行显式 id + ODKU；角色关联按显式 id。
-- 注意：MySQL 注释 `--` 后面必须跟空白字符。
-- ============================================================================================

-- ===== 1) 集群画像 =====
ALTER TABLE k8s_cluster
  ADD COLUMN IF NOT EXISTS cluster_role VARCHAR(16) NOT NULL DEFAULT 'BUSINESS'
  COMMENT '集群画像：MANAGEMENT=平台自身所在集群（多装 Thanos 等底座）；BUSINESS=业务集群';

-- ⚠️ 重放后请把平台自身所在集群手工改为 MANAGEMENT（唯一一条 UPDATE，按实际情况改条件）：
--   UPDATE k8s_cluster SET cluster_role='MANAGEMENT' WHERE cluster_name='coding-cluster';

-- ===== 2) 操作记录 =====
CREATE TABLE IF NOT EXISTS platform_component_operation (
  id              BIGINT       NOT NULL AUTO_INCREMENT,
  cluster_id      VARCHAR(64)  NOT NULL,
  component_code  VARCHAR(64)  NOT NULL,
  action          VARCHAR(16)  NOT NULL COMMENT 'INSTALL / UPGRADE',
  profile         VARCHAR(16)  NOT NULL COMMENT '本次使用的画像（MANAGEMENT/BUSINESS）',
  from_version    VARCHAR(64)  NULL,
  to_version      VARCHAR(64)  NULL COMMENT '目标 chart 版本',
  release_names   TEXT         NULL COMMENT '本次涉及的 release 名（JSON 数组）',
  values_snapshot MEDIUMTEXT   NULL COMMENT '最终合并后的 values（升级时复用）',
  status          VARCHAR(16)  NOT NULL COMMENT 'PENDING/RUNNING/SUCCESS/FAILED/UNKNOWN',
  message         VARCHAR(1024) NULL,
  log_tail        MEDIUMTEXT   NULL COMMENT 'helm 输出去敏后的尾部',
  operator        VARCHAR(128) NULL,
  started_at      DATETIME     NOT NULL,
  finished_at     DATETIME     NULL,
  PRIMARY KEY (id),
  KEY idx_component_op_cluster_component (cluster_id, component_code, started_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='组件安装/升级操作记录';

-- ===== 3) 权限行 =====
INSERT INTO platform_permission (id, domain, resource, action, code, description) VALUES
 ('perm_res_cluster_components_apply', 'API','/cluster/components/apply','POST','platform:cluster:manage',
  '安装/升级集群组件（helm 执行，cluster-admin 级写入）'),
 ('perm_res_cluster_components_op_get', 'API','/cluster/components/operations/get','POST','platform:cluster:manage',
  '查询一条组件操作记录（轮询进度用）'),
 ('perm_res_cluster_components_op_list', 'API','/cluster/components/operations/list','POST','platform:cluster:manage',
  '按集群列出各组件最近一次操作')
ON DUPLICATE KEY UPDATE
  domain=VALUES(domain), resource=VALUES(resource), action=VALUES(action),
  code=VALUES(code), description=VALUES(description);

INSERT INTO platform_role_permission (id, role_id, permission_id) VALUES
 ('rp_admin_cluster_components_apply', 'builtin_role_admin','perm_res_cluster_components_apply'),
 ('rp_admin_cluster_components_op_get','builtin_role_admin','perm_res_cluster_components_op_get'),
 ('rp_admin_cluster_components_op_list','builtin_role_admin','perm_res_cluster_components_op_list')
ON DUPLICATE KEY UPDATE permission_id=VALUES(permission_id);
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -pl platform-api test -Dtest=ComponentLifecycleMigrationTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS（3 个测试）。

- [ ] **Step 5: 重放到 dev 库并回查**

用 jshell + JDBC（配方见 memory `cluster-access-for-debugging`）把本文件逐条执行，然后：

```sql
select resource, action, code from platform_permission where resource like '/cluster/components/%' order by resource;
show columns from k8s_cluster like 'cluster_role';
select count(*) from platform_component_operation;
```

Expected: 3 行权限（apply / operations/get / operations/list）；`cluster_role` 存在且默认 `BUSINESS`；表存在。

- [ ] **Step 6: 提交**

```bash
git add platform-data/src/main/resources/db/migration/V2026_10_18_1__component_lifecycle.sql \
        platform-api/src/test/java/com/coding/platformapi/components/ComponentLifecycleMigrationTest.java
git commit -m "feat(components): S3.6 批① 迁移（cluster_role + 操作表 + 三条权限行）"
```

---

### Task 2: `cluster_role` 后端（实体 / 请求 / 唯一性校验）

**Files:**
- Modify: `platform-data/src/main/java/com/coding/data/models/k8s/K8sCluster.java`
- Modify: `platform-data/src/main/resources/mapper/k8s/K8sClusterMapper.xml`
- Modify: `platform-data/src/main/java/com/coding/data/mapper/k8s/K8sClusterMapper.java`
- Modify: `platform-api/src/main/java/com/coding/platformapi/models/ClusterCreateRequest.java`
- Modify: `platform-api/src/main/java/com/coding/platformapi/models/ClusterUpdateRequest.java`
- Modify: `platform-api/src/main/java/com/coding/platformapi/services/ClusterService.java`
- Test: `platform-api/src/test/java/com/coding/platformapi/services/ClusterServiceTest.java`（既有，追加用例）

**Interfaces:**
- Produces: `K8sCluster.getClusterRole()/setClusterRole(String)`；`K8sClusterMapper.countByRole(String)`；
  `ClusterService.requireProfile(String clusterId) -> String`（返回 `BUSINESS`/`MANAGEMENT`）。

- [ ] **Step 1: 写失败的测试**

在 `ClusterServiceTest` 里追加（保留既有用例）：

```java
    @Test
    void createRejectsASecondManagementCluster() {
        // 全局只允许一个 MANAGEMENT（平台自身所在集群）
        when(clusterMapper.countByRole("MANAGEMENT")).thenReturn(1);
        ClusterCreateRequest req = new ClusterCreateRequest();
        req.setClusterName("second-mgmt");
        req.setKubeconfig("dummy");
        req.setClusterRole("MANAGEMENT");

        assertThatThrownBy(() -> svc.create(req))
                .hasMessageContaining("已经存在管理集群");
    }

    @Test
    void createDefaultsToBusinessWhenRoleIsBlank() {
        ClusterCreateRequest req = new ClusterCreateRequest();
        req.setClusterName("biz-1");
        req.setKubeconfig("dummy");
        when(clusterMapper.countByRole("MANAGEMENT")).thenReturn(1);

        svc.create(req);

        ArgumentCaptor<K8sCluster> captor = ArgumentCaptor.forClass(K8sCluster.class);
        verify(clusterMapper).insert(captor.capture());
        assertThat(captor.getValue().getClusterRole()).isEqualTo("BUSINESS");
    }
```

（`create` 里已有 `adminClient.probe(...)`，测试用 mock 即可；文件顶部需 `import org.mockito.ArgumentCaptor;`。）

- [ ] **Step 2: 跑测试确认它失败**

Run: `mvn -pl platform-api test -Dtest=ClusterServiceTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 编译失败 —— `setClusterRole` / `countByRole` 不存在。

- [ ] **Step 3: 实体与 mapper**

`K8sCluster` 增加字段（放在 `private String capability;` 之后）：

```java
    /** 集群画像：MANAGEMENT=平台自身所在集群；BUSINESS=业务集群（默认）。见 S3.6 spec §2.1 */
    private String clusterRole;
```

`K8sClusterMapper` 增加：

```java
    /** 某画像的集群数量（创建/编辑时保证 MANAGEMENT 全局唯一） */
    int countByRole(@Param("clusterRole") String clusterRole);
```

`K8sClusterMapper.xml`：
- `resultMap` 加一行 `<result column="cluster_role" jdbcType="VARCHAR" property="clusterRole" />`
- `Base_Column_List` 加 `cluster_role`
- `insert` / `insertSelective` / `updateByPrimaryKeySelective` 增加 `cluster_role` 列与 `#{clusterRole,jdbcType=VARCHAR}`
- 末尾加：

```xml
  <select id="countByRole" resultType="int">
    select count(*) from k8s_cluster where cluster_role = #{clusterRole} and deleted_at is null
  </select>
```

- [ ] **Step 4: 请求对象与校验**

`ClusterCreateRequest` / `ClusterUpdateRequest` 加：

```java
    @Schema(description = "集群画像：MANAGEMENT(平台自身所在集群) / BUSINESS(业务集群，默认)",
            example = "BUSINESS")
    private String clusterRole;
```

`ClusterService.create` 在连通性探测**之前**加：

```java
        String role = normalizeRole(req.getClusterRole());
        if ("MANAGEMENT".equals(role) && clusterMapper.countByRole("MANAGEMENT") > 0) {
            throw new CloudPlatformException(EnumResponseType.BEAN_VALIDATION_EXCEPTION,
                    "已经存在管理集群，全局只允许一个 MANAGEMENT");
        }
```

`create` 里设置 `cluster.setClusterRole(role);`；`update` 里若 `req.getClusterRole()` 非空则同样校验后 `upd.setClusterRole(...)`。

新增私有方法：

```java
    /** 画像归一：空 → BUSINESS；只接受两个值（避免库里出现第三个拼写） */
    static String normalizeRole(String raw) {
        if (!StringUtils.hasText(raw)) {
            return "BUSINESS";
        }
        String role = raw.trim().toUpperCase();
        if (!"MANAGEMENT".equals(role) && !"BUSINESS".equals(role)) {
            throw new CloudPlatformException(EnumResponseType.BEAN_VALIDATION_EXCEPTION,
                    "clusterRole 只能是 MANAGEMENT 或 BUSINESS");
        }
        return role;
    }

    /** 组件安装用：取该集群的画像，缺省 BUSINESS */
    public String requireProfile(String clusterId) {
        K8sCluster cluster = require(clusterId);
        return normalizeRole(cluster.getClusterRole());
    }
```

- [ ] **Step 5: 跑测试确认通过 + 全量**

Run: `mvn -pl platform-api test -Dtest=ClusterServiceTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS。
Run: `mvn -pl platform-api test`
Expected: 全绿。

- [ ] **Step 6: 提交**

```bash
git add platform-data/src/main/java/com/coding/data/models/k8s/K8sCluster.java \
        platform-data/src/main/java/com/coding/data/mapper/k8s/K8sClusterMapper.java \
        platform-data/src/main/resources/mapper/k8s/K8sClusterMapper.xml \
        platform-api/src/main/java/com/coding/platformapi/models/ClusterCreateRequest.java \
        platform-api/src/main/java/com/coding/platformapi/models/ClusterUpdateRequest.java \
        platform-api/src/main/java/com/coding/platformapi/services/ClusterService.java \
        platform-api/src/test/java/com/coding/platformapi/services/ClusterServiceTest.java
git commit -m "feat(components): cluster_role 画像 + MANAGEMENT 全局唯一校验"
```

---

### Task 3: k8s-server 内置 chart 目录 + metrics-server chart

**Files:**
- Create: `k8s-server/charts/components/metrics-server/1.0.0/provenance.yaml`
- Create: `k8s-server/charts/components/metrics-server/1.0.0/values-default.yaml`
- Create: `k8s-server/charts/components/metrics-server/1.0.0/chart/Chart.yaml`
- Create: `k8s-server/charts/components/metrics-server/1.0.0/chart/values.yaml`
- Create: `k8s-server/charts/components/metrics-server/1.0.0/chart/templates/*.yaml`
- Create: `k8s-server/src/main/java/com/coding/k8sserver/helm/ChartBundle.java`
- Test: `k8s-server/src/test/java/com/coding/k8sserver/helm/ChartBundleTest.java`
- Modify: `k8s-server/Dockerfile`
- Modify: `k8s-server/src/main/resources/application.yaml`

**Interfaces:**
- Produces: `ChartBundle.chartDir(String code, String version) throws CloudPlatformException` —— 返回 chart 目录的绝对路径；不存在就报错（**不**回退到别的版本）。

- [ ] **Step 1: 写失败的测试**

```java
package com.coding.k8sserver.helm;

import com.coding.common.exception.CloudPlatformException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChartBundleTest {

    @Test
    void resolvesTheBundledMetricsServerChart(@TempDir Path root) throws Exception {
        Path chart = root.resolve("components/metrics-server/1.0.0/chart");
        Files.createDirectories(chart);
        Files.writeString(chart.resolve("Chart.yaml"), "apiVersion: v2\nname: metrics-server\nversion: 1.0.0\n");

        ChartBundle bundle = new ChartBundle(root.toString());

        assertThat(bundle.chartDir("metrics-server", "1.0.0")).isEqualTo(chart.toAbsolutePath().normalize());
    }

    @Test
    void anUnknownVersionFailsLoudlyInsteadOfFallingBack(@TempDir Path root) {
        ChartBundle bundle = new ChartBundle(root.toString());

        // ⭐ 版本漂移必须报错：静默回退会装出一个用户没选的版本
        assertThatThrownBy(() -> bundle.chartDir("metrics-server", "9.9.9"))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("9.9.9");
    }

    @Test
    void aChartWithoutChartYamlIsRejected(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve("components/metrics-server/1.0.0/chart"));

        assertThatThrownBy(() -> new ChartBundle(root.toString()).chartDir("metrics-server", "1.0.0"))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("Chart.yaml");
    }
}
```

- [ ] **Step 2: 跑测试确认它失败**

Run: `mvn -pl k8s-server -am test -Dtest=ChartBundleTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 编译失败 —— `ChartBundle` 不存在。

- [ ] **Step 3: 实现 `ChartBundle`**

```java
package com.coding.k8sserver.helm;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 内置 chart 的定位器（S3.6）。
 *
 * <p>chart 以**目录形态**内置在 k8s-server（不是 tgz）：git 里可读、可 review，helm 直接吃目录。
 * 目录约定：{@code <charts-dir>/components/<code>/<version>/chart/}。
 *
 * <p>⚠️ 版本找不到时**必须报错**，不能回退到别的版本 —— 否则 platform-api 的 recipe 与
 * k8s-server 的产物一旦漂移，就会静默装出一个用户没选的版本。
 */
@Component
public class ChartBundle {

    private final Path root;

    public ChartBundle(@Value("${platform.components.charts-dir:charts}") String chartsDir) {
        this.root = Path.of(chartsDir).toAbsolutePath().normalize();
    }

    public Path chartDir(String code, String version) {
        if (code == null || code.isBlank() || version == null || version.isBlank()) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "缺少 chart code 或 version");
        }
        if (code.contains("/") || code.contains("..") || version.contains("/") || version.contains("..")) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "非法的 chart code/version");
        }
        Path dir = root.resolve("components").resolve(code).resolve(version).resolve("chart");
        if (!Files.isDirectory(dir) || !Files.isRegularFile(dir.resolve("Chart.yaml"))) {
            throw new CloudPlatformException(EnumResponseType.ERROR,
                    "内置 chart 不存在或缺少 Chart.yaml: " + code + " @" + version + "（" + dir + "）");
        }
        return dir;
    }
}
```

- [ ] **Step 4: 写 metrics-server chart（目录形态）**

`chart/Chart.yaml`：

```yaml
apiVersion: v2
name: metrics-server
description: 平台内置的 metrics-server（S3.6 组件安装用）
type: application
version: 1.0.0
appVersion: "v0.7.2"
```

`chart/values.yaml`：

```yaml
image:
  repository: registry.k8s.io/metrics-server/metrics-server
  tag: v0.7.2
  pullPolicy: IfNotPresent
replicas: 1
# 🔒 由 recipe 传入的平台契约值（--kubelet-insecure-tls 等）
args: []
resources: {}
```

`chart/templates/serviceaccount.yaml`：

```yaml
apiVersion: v1
kind: ServiceAccount
metadata:
  name: metrics-server
  namespace: {{ .Release.Namespace }}
  labels:
    app.kubernetes.io/managed-by: {{ .Release.Service }}
```

`chart/templates/rbac.yaml`：

```yaml
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRole
metadata:
  name: system:aggregated-metrics-reader
  labels:
    app.kubernetes.io/managed-by: {{ .Release.Service }}
rules:
  - apiGroups: ["metrics.k8s.io"]
    resources: ["pods", "nodes"]
    verbs: ["get", "list", "watch"]
---
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRole
metadata:
  name: system:metrics-server
  labels:
    app.kubernetes.io/managed-by: {{ .Release.Service }}
rules:
  - apiGroups: [""]
    resources: ["nodes/metrics"]
    verbs: ["get"]
  - apiGroups: [""]
    resources: ["pods", "nodes"]
    verbs: ["get", "list", "watch"]
---
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRoleBinding
metadata:
  name: system:metrics-server
  labels:
    app.kubernetes.io/managed-by: {{ .Release.Service }}
roleRef:
  apiGroup: rbac.authorization.k8s.io
  kind: ClusterRole
  name: system:metrics-server
subjects:
  - kind: ServiceAccount
    name: metrics-server
    namespace: {{ .Release.Namespace }}
---
apiVersion: rbac.authorization.k8s.io/v1
kind: RoleBinding
metadata:
  name: metrics-server-auth-reader
  namespace: kube-system
  labels:
    app.kubernetes.io/managed-by: {{ .Release.Service }}
roleRef:
  apiGroup: rbac.authorization.k8s.io
  kind: Role
  name: extension-apiserver-authentication-reader
subjects:
  - kind: ServiceAccount
    name: metrics-server
    namespace: {{ .Release.Namespace }}
```

`chart/templates/service.yaml`：

```yaml
apiVersion: v1
kind: Service
metadata:
  name: metrics-server
  namespace: {{ .Release.Namespace }}
  labels:
    k8s-app: metrics-server
    app.kubernetes.io/managed-by: {{ .Release.Service }}
spec:
  selector:
    k8s-app: metrics-server
  ports:
    - name: https
      port: 443
      protocol: TCP
      targetPort: https
```

`chart/templates/deployment.yaml`：

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: metrics-server
  namespace: {{ .Release.Namespace }}
  labels:
    k8s-app: metrics-server
    app.kubernetes.io/managed-by: {{ .Release.Service }}
spec:
  replicas: {{ .Values.replicas }}
  selector:
    matchLabels:
      k8s-app: metrics-server
  template:
    metadata:
      labels:
        k8s-app: metrics-server
    spec:
      serviceAccountName: metrics-server
      containers:
        - name: metrics-server
          image: "{{ .Values.image.repository }}:{{ .Values.image.tag }}"
          imagePullPolicy: {{ .Values.image.pullPolicy }}
          args:
            - --cert-dir=/tmp
            - --secure-port=4443
            - --kubelet-preferred-address-types=InternalIP
            - --kubelet-use-node-status-port
            - --metric-resolution=15s
{{- range .Values.args }}
            - {{ . | quote }}
{{- end }}
          ports:
            - name: https
              containerPort: 4443
              protocol: TCP
          livenessProbe:
            httpGet:
              path: /livez
              port: https
              scheme: HTTPS
            periodSeconds: 10
          readinessProbe:
            httpGet:
              path: /readyz
              port: https
              scheme: HTTPS
            periodSeconds: 10
          securityContext:
            readOnlyRootFilesystem: true
            runAsNonRoot: true
            runAsUser: 1000
          volumeMounts:
            - name: tmp-dir
              mountPath: /tmp
      volumes:
        - name: tmp-dir
          emptyDir: {}
```

`chart/templates/apiservice.yaml`：

```yaml
apiVersion: apiregistration.k8s.io/v1
kind: APIService
metadata:
  name: v1beta1.metrics.k8s.io
  labels:
    app.kubernetes.io/managed-by: {{ .Release.Service }}
spec:
  group: metrics.k8s.io
  groupPriorityMinimum: 100
  insecureSkipTLSVerify: true
  service:
    name: metrics-server
    namespace: {{ .Release.Namespace }}
  version: v1beta1
  versionPriority: 100
```

`values-default.yaml`（平台契约值的初始形态，便于 review）：

```yaml
# 🔒 平台契约值：自签 kubelet 证书的集群必须加 --kubelet-insecure-tls，否则 metrics-server 永远 NotReady
args:
  - --kubelet-insecure-tls
```

`provenance.yaml`：

```yaml
source: https://github.com/kubernetes-sigs/metrics-server/releases/tag/v0.7.2
fetched_at: 2026-10-18
note: 平台自建的 thin chart；对象清单参考官方 components.yaml，values 只暴露 image/replicas/args/resources
```

- [ ] **Step 5: Dockerfile 带上 charts 目录**

在 `k8s-server/Dockerfile` 的 `WORKDIR /app` 之后加：

```dockerfile
# S3.6：组件安装用的内置 chart（文本目录，不是 tgz）
COPY --chown=app:app charts /app/charts
```

并在 `application.yaml` 加：

```yaml
platform:
  components:
    charts-dir: ${COMPONENT_CHARTS_DIR:charts}
```

（放在 `platform:` 既有块内；若没有 `platform:` 块则新建。）

- [ ] **Step 6: 跑测试确认通过**

Run: `mvn -pl k8s-server -am test -Dtest=ChartBundleTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS（3 个测试）。

- [ ] **Step 7: 提交**

```bash
git add k8s-server/charts k8s-server/Dockerfile k8s-server/src/main/resources/application.yaml \
        k8s-server/src/main/java/com/coding/k8sserver/helm/ChartBundle.java \
        k8s-server/src/test/java/com/coding/k8sserver/helm/ChartBundleTest.java
git commit -m "feat(components): k8s-server 内置 chart 目录 + metrics-server thin chart"
```

---

### Task 4: `HelmRunner`（进程执行 + 临时 kubeconfig）

**Files:**
- Create: `k8s-server/src/main/java/com/coding/k8sserver/helm/HelmRunner.java`
- Create: `k8s-server/src/main/java/com/coding/k8sserver/helm/HelmResult.java`
- Modify: `k8s-core/src/main/java/com/coding/k8score/factory/KubernetesClientFactory.java`（加 `adminKubeconfig`）
- Test: `k8s-server/src/test/java/com/coding/k8sserver/helm/HelmRunnerTest.java`
- Test resource: `k8s-server/src/test/resources/fake-helm.sh`

**Interfaces:**
- Produces:
  - `HelmRunner.render(String kubeconfig, Path chartDir, Path valuesFile, String namespace) -> HelmResult`
  - `HelmRunner.apply(String kubeconfig, Path chartDir, Path valuesFile, String release, String namespace,
    Map<String,String> labels) -> HelmResult`
  - `HelmResult(boolean ok, int exitCode, String output)`

- [ ] **Step 1: 写失败的测试**

`k8s-server/src/test/resources/fake-helm.sh`（可执行，测试里用 `bash` 调用所以不依赖 exec 位）：

```bash
#!/usr/bin/env bash
# 假 helm：把 argv 原样记录到 FAKE_HELM_LOG，然后按 FAKE_HELM_EXIT 退出
printf '%s\n' "$@" > "${FAKE_HELM_LOG}"
exit "${FAKE_HELM_EXIT:-0}"
```

```java
package com.coding.k8sserver.helm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class HelmRunnerTest {

    @TempDir
    Path tmp;

    private HelmRunner runner(Path log, int exit) {
        // 用 bash 调脚本，避免依赖可执行位（Windows 上也能跑）；tempDir 指向 @TempDir 以便断言清理
        return new HelmRunner("bash",
                Path.of("src/test/resources/fake-helm.sh").toAbsolutePath().toString(),
                Map.of("FAKE_HELM_LOG", log.toString(), "FAKE_HELM_EXIT", String.valueOf(exit)),
                tmp);
    }

    private Path chart() throws Exception {
        Path chart = tmp.resolve("chart");
        Files.createDirectories(chart);
        Files.writeString(chart.resolve("Chart.yaml"), "apiVersion: v2\nname: x\nversion: 1.0.0\n");
        return chart;
    }

    @Test
    void applyUsesInstallUpgradeRollbackOnFailureAndLabels() throws Exception {
        Path log = tmp.resolve("argv.log");
        Path values = tmp.resolve("values.yaml");
        Files.writeString(values, "replicas: 1\n");

        HelmResult r = runner(log, 0).apply("apiVersion: v1\nkind: Config\n",
                chart(), values, "platform-metrics-server", "kube-system",
                Map.of("platform.coding/managed", "true",
                        "platform.coding/component", "metrics-server",
                        "platform.coding/cluster", "c1"));

        assertThat(r.ok()).isTrue();
        String argv = Files.readString(log);
        // ⭐ 少一个都会导致"看起来成功、实际没装"
        assertThat(argv).contains("upgrade").contains("--install");
        assertThat(argv).contains("--create-namespace");
        assertThat(argv).contains("kube-system");
        assertThat(argv).contains("platform-metrics-server");
        // ⭐ Helm 4：不是 --atomic
        assertThat(argv).contains("--rollback-on-failure");
        assertThat(argv).doesNotContain("--atomic");
        assertThat(argv).contains("platform.coding/managed=true");
        assertThat(argv).contains("--timeout");
    }

    @Test
    void serverDryRunIsAvailableButNotAFailureGate() throws Exception {
        Path values = tmp.resolve("values.yaml");
        Files.writeString(values, "replicas: 1\n");

        HelmResult r = runner(tmp.resolve("argv.log"), 0)
                .dryRunServer("k", chart(), values, "platform-metrics-server", "kube-system");

        assertThat(r.ok()).isTrue();
        assertThat(Files.readString(tmp.resolve("argv.log"))).contains("--dry-run=server");
    }

    @Test
    void templateIsTheHardPrecheck() throws Exception {
        Path log = tmp.resolve("argv.log");
        Path values = tmp.resolve("values.yaml");
        Files.writeString(values, "replicas: 1\n");

        runner(log, 0).render("k", chart(), values, "kube-system");

        assertThat(Files.readString(log)).contains("template");
    }

    @Test
    void aNonZeroExitIsReportedNotThrown() throws Exception {
        Path values = tmp.resolve("values.yaml");
        Files.writeString(values, "replicas: 1\n");

        HelmResult r = runner(tmp.resolve("argv.log"), 3).render("k", chart(), values, "kube-system");

        assertThat(r.ok()).isFalse();
        assertThat(r.exitCode()).isEqualTo(3);
    }

    @Test
    void theTemporaryKubeconfigIsRemovedEvenOnFailure() throws Exception {
        // 通过把 FAKE_HELM_LOG 指到不可写路径制造失败，断言 temp 文件仍被删
        Path bad = tmp.resolve("no-such-dir/argv.log");
        Path values = tmp.resolve("values.yaml");
        Files.writeString(values, "replicas: 1\n");

        runner(bad, 1).render("apiVersion: v1\n", chart(), values, "kube-system");

        try (var stream = Files.list(tmp)) {
            assertThat(stream.filter(p -> p.getFileName().toString().startsWith("kubeconfig-")))
                    .as("临时 kubeconfig 必须清理").isEmpty();
        }
    }
}
```

- [ ] **Step 2: 跑测试确认它失败**

Run: `mvn -pl k8s-server -am test -Dtest=HelmRunnerTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 编译失败 —— `HelmRunner` / `HelmResult` 不存在。

- [ ] **Step 3: 实现 `HelmResult` 与 `HelmRunner`**

`HelmResult.java`：

```java
package com.coding.k8sserver.helm;

/** helm 一次调用的结果。{@code ok=false} 时 exitCode 与脱敏后的输出都保留，便于写进操作记录。 */
public record HelmResult(boolean ok, int exitCode, String output) {
}
```

`HelmRunner.java`：

```java
package com.coding.k8sserver.helm;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 执行 helm（S3.6）。唯一一个"平台自己往集群写"的原语。
 *
 * <p><b>安全</b>：helm 需要 kubeconfig 文件 —— 明文只落在 0600 的临时文件里，方法返回前删除；
 * 日志绝不打印内容。
 *
 * <p><b>Helm 4</b>：失败回滚用 {@code --rollback-on-failure}（3.x 的 {@code --atomic} 已不存在）。
 *
 * <p><b>预检分级</b>：{@link #render} 是硬门禁（不连集群）；{@code --dry-run=server} 由调用方按警告处理。
 */
@Slf4j
@Component
public class HelmRunner {

    private static final long TIMEOUT_MINUTES = 15;

    private final String binary;
    /** 测试注入：非空时命令变成 `bash <script> <helm args...>` */
    private final String wrapperScript;
    private final Map<String, String> extraEnv;
    /** 临时 kubeconfig 的落点；测试注入 @TempDir 才能断言"确实删了" */
    private final Path tempDir;

    public HelmRunner(@Value("${platform.components.helm-binary:helm}") String binary) {
        this(binary, null, Map.of(), Path.of(System.getProperty("java.io.tmpdir")));
    }

    HelmRunner(String binary, String wrapperScript, Map<String, String> extraEnv, Path tempDir) {
        this.binary = binary;
        this.wrapperScript = wrapperScript;
        this.extraEnv = extraEnv;
        this.tempDir = tempDir;
    }

    /**
     * 服务端 dry-run：**只作警告**。chart 同时装 CRD 与 CR 时（kube-prometheus-stack）会因
     * "CRD 尚不存在"报假阴性，当硬门禁会导致永远装不上。
     */
    public HelmResult dryRunServer(String kubeconfig, Path chartDir, Path valuesFile,
                                   String release, String namespace) {
        List<String> args = new ArrayList<>(List.of(
                "upgrade", "--install", release, chartDir.toString(),
                "--namespace", namespace, "--create-namespace",
                "--values", valuesFile.toString(),
                "--dry-run=server"));
        return exec(kubeconfig, args);
    }

    /** 客户端渲染校验（不连集群）—— 模板/values 错误在这一步硬失败 */
    public HelmResult render(String kubeconfig, Path chartDir, Path valuesFile, String namespace) {
        List<String> args = new ArrayList<>(List.of(
                "template", "precheck", chartDir.toString(),
                "--namespace", namespace,
                "--values", valuesFile.toString()));
        return exec(kubeconfig, args);
    }

    /**
     * 安装或升级。幂等：同一 release 上重复调用就是 upgrade。
     * {@code --rollback-on-failure} 在 Helm 4 里同时把 wait 默认成 watcher。
     */
    public HelmResult apply(String kubeconfig, Path chartDir, Path valuesFile,
                            String release, String namespace, Map<String, String> labels) {
        List<String> args = new ArrayList<>(List.of(
                "upgrade", "--install", release, chartDir.toString(),
                "--namespace", namespace,
                "--create-namespace",
                "--values", valuesFile.toString(),
                "--rollback-on-failure",
                "--timeout", "10m"));
        if (!labels.isEmpty()) {
            List<String> kv = new ArrayList<>();
            labels.forEach((k, v) -> kv.add(k + "=" + v));
            args.add("--labels");
            args.add(String.join(",", kv));
        }
        return exec(kubeconfig, args);
    }

    // ---------- 内部 ----------

    private HelmResult exec(String kubeconfig, List<String> args) {
        Path kubeconfigFile = null;
        try {
            kubeconfigFile = writeTemp(kubeconfig);
            List<String> command = new ArrayList<>();
            if (wrapperScript != null) {
                command.add("bash");
                command.add(wrapperScript);
            } else {
                command.add(binary);
            }
            command.addAll(args);
            command.add("--kubeconfig");
            command.add(kubeconfigFile.toString());
            return run(command, args);
        } catch (Exception e) {
            return new HelmResult(false, -1, "helm 执行失败: " + e.getMessage());
        } finally {
            deleteQuietly(kubeconfigFile);
        }
    }

    private HelmResult run(List<String> command, List<String> logicalArgs) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        pb.environment().putAll(extraEnv);
        Process p = pb.start();
        String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        boolean finished = p.waitFor(TIMEOUT_MINUTES, TimeUnit.MINUTES);
        if (!finished) {
            p.destroyForcibly();
            return new HelmResult(false, -1, "helm 超时（" + TIMEOUT_MINUTES + " 分钟）");
        }
        int exit = p.exitValue();
        log.info("helm {} 退出码 {}（{} 字节输出）", logicalArgs.get(0), exit, output.length());
        return new HelmResult(exit == 0, exit, truncate(output));
    }

    private Path writeTemp(String kubeconfig) throws IOException {
        Path file = Files.createTempFile(tempDir, "kubeconfig-", ".yaml");
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException ignored) {
            // 非 POSIX 文件系统（Windows 测试机）：退化为默认权限，生产镜像里是 Linux
        }
        Files.writeString(file, kubeconfig, StandardCharsets.UTF_8);
        return file;
    }

    private static void deleteQuietly(Path file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.warn("删除临时 kubeconfig 失败（{}）：{}", file, e.getMessage());
        }
    }

    private static String truncate(String output) {
        int max = 64 * 1024;
        return output.length() <= max ? output : output.substring(output.length() - max);
    }
}
```

- [ ] **Step 4: `KubernetesClientFactory` 暴露 admin kubeconfig**

```java
    /**
     * 解密后的 admin kubeconfig（S3.6：helm 需要文件而不是 fabric8 client）。
     * <p>⚠️ 调用方必须立即使用并丢弃，不得写日志、不得落盘到临时目录之外。
     */
    public String adminKubeconfig(String clusterId) {
        K8sCluster k8sCluster = k8sClusterMapper.selectByPrimaryKey(clusterId);
        if (k8sCluster == null) {
            throw new CloudPlatformException(EnumResponseType.CLUSTER_NOT_EXIST);
        }
        return AESUtils.decrypt(k8sCluster.getKubeconfig(), AES_KEY);
    }
```

- [ ] **Step 5: 跑测试确认通过**

Run: `mvn -pl k8s-server -am test -Dtest=HelmRunnerTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS（4 个测试）。

- [ ] **Step 6: 提交**

```bash
git add k8s-server/src/main/java/com/coding/k8sserver/helm/HelmRunner.java \
        k8s-server/src/main/java/com/coding/k8sserver/helm/HelmResult.java \
        k8s-server/src/test/java/com/coding/k8sserver/helm/HelmRunnerTest.java \
        k8s-server/src/test/resources/fake-helm.sh \
        k8s-core/src/main/java/com/coding/k8score/factory/KubernetesClientFactory.java
git commit -m "feat(components): HelmRunner（Helm 4 + 临时 kubeconfig 0600 + 参数契约）"
```

---

### Task 5: k8s-server 安装端点（PLATFORM 边界）

**Files:**
- Create: `platform-common/src/main/java/com/coding/common/models/k8s/dto/ComponentInstallRequestDTO.java`
- Create: `platform-common/src/main/java/com/coding/common/models/k8s/dto/ComponentInstallResultDTO.java`
- Create: `platform-common/src/main/java/com/coding/common/models/k8s/dto/ReleaseInstallSpecDTO.java`
- Create: `k8s-server/src/main/java/com/coding/k8sserver/controllers/cluster/ComponentInstallController.java`
- Test: `k8s-server/src/test/java/com/coding/k8sserver/controllers/cluster/ComponentInstallControllerTest.java`

**Interfaces:**
- Produces: `POST /components/apply`，body `ComponentInstallRequestDTO`，
  `ResponseData<ComponentInstallResultDTO>`；`AccessBoundary.PLATFORM`。

- [ ] **Step 1: 写失败的测试**

```java
package com.coding.k8sserver.controllers.cluster;

import com.coding.common.models.k8s.dto.ComponentInstallRequestDTO;
import com.coding.common.models.k8s.dto.ComponentInstallResultDTO;
import com.coding.common.models.k8s.dto.ReleaseInstallSpecDTO;
import com.coding.k8sserver.components.AccessBoundary;
import com.coding.k8sserver.components.ResourceAccessResolver;
import com.coding.k8sserver.helm.ChartBundle;
import com.coding.k8sserver.helm.HelmResult;
import com.coding.k8sserver.helm.HelmRunner;
import com.coding.k8score.factory.KubernetesClientFactory;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ComponentInstallControllerTest {

    @Test
    void theEndpointIsPlatformBoundary() {
        assertThat(controller(mock(HelmRunner.class)).accessBoundary()).isEqualTo(AccessBoundary.PLATFORM);
    }

    @Test
    void itRendersThenAppliesAndReportsSuccess() {
        HelmRunner helm = mock(HelmRunner.class);
        when(helm.render(anyString(), any(), any(), anyString()))
                .thenReturn(new HelmResult(true, 0, "rendered"));
        when(helm.dryRunServer(anyString(), any(), any(), anyString(), anyString()))
                .thenReturn(new HelmResult(true, 0, "dry-ok"));
        when(helm.apply(anyString(), any(), any(), anyString(), anyString(), anyMap()))
                .thenReturn(new HelmResult(true, 0, "deployed"));

        ComponentInstallResultDTO r = controller(helm).apply(request()).getData();

        assertThat(r.isSuccess()).isTrue();
        assertThat(r.getReleases()).hasSize(1);
        assertThat(r.getReleases().get(0).getStatus()).isEqualTo("SUCCESS");
    }

    @Test
    void aRenderFailureStopsBeforeTouchingTheCluster() {
        HelmRunner helm = mock(HelmRunner.class);
        when(helm.render(anyString(), any(), any(), anyString()))
                .thenReturn(new HelmResult(false, 1, "values path not found"));

        ComponentInstallResultDTO r = controller(helm).apply(request()).getData();

        assertThat(r.isSuccess()).isFalse();
        assertThat(r.getMessage()).contains("预检");
        org.mockito.Mockito.verify(helm, org.mockito.Mockito.never())
                .apply(anyString(), any(), any(), anyString(), anyString(), anyMap());
    }

    private static ComponentInstallController controller(HelmRunner helm) {
        KubernetesClientFactory factory = mock(KubernetesClientFactory.class);
        when(factory.adminKubeconfig("c1")).thenReturn("apiVersion: v1\nkind: Config\n");
        ChartBundle bundle = mock(ChartBundle.class);
        when(bundle.chartDir(anyString(), anyString())).thenReturn(Path.of("/tmp/chart"));
        return new ComponentInstallController(helm, bundle, factory, mock(ResourceAccessResolver.class));
    }

    private static ComponentInstallRequestDTO request() {
        ReleaseInstallSpecDTO spec = new ReleaseInstallSpecDTO();
        spec.setReleaseName("platform-metrics-server");
        spec.setNamespace("kube-system");
        spec.setChartCode("metrics-server");
        spec.setChartVersion("1.0.0");
        spec.setValues(Map.of("args", List.of("--kubelet-insecure-tls")));
        ComponentInstallRequestDTO req = new ComponentInstallRequestDTO();
        req.setClusterId("c1");
        req.setComponentCode("metrics-server");
        req.setAction("INSTALL");
        req.setReleases(List.of(spec));
        return req;
    }
}
```

- [ ] **Step 2: 跑测试确认它失败**

Run: `mvn -pl k8s-server -am test -Dtest=ComponentInstallControllerTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 编译失败 —— DTO / 控制器不存在。

- [ ] **Step 3: 写 DTO**

`ReleaseInstallSpecDTO`：

```java
package com.coding.common.models.k8s.dto;

import lombok.Data;

import java.util.Map;

/** 一个 release 的安装规格（由 platform-api 的 recipe 解析后下发）。 */
@Data
public class ReleaseInstallSpecDTO {
    private String releaseName;
    private String namespace;
    /** 内置 chart 的目录名，如 metrics-server */
    private String chartCode;
    /** 目标 chart 版本，如 1.0.0 */
    private String chartVersion;
    /** 平台契约值 + 用户可编辑值合并后的最终 values */
    private Map<String, Object> values;
}
```

`ComponentInstallRequestDTO`：

```java
package com.coding.common.models.k8s.dto;

import lombok.Data;

import java.util.List;

/** {@code POST /components/apply} 的请求体。 */
@Data
public class ComponentInstallRequestDTO {
    private String clusterId;
    private String componentCode;
    /** INSTALL / UPGRADE（只用于记录与日志，执行路径相同） */
    private String action;
    private List<ReleaseInstallSpecDTO> releases;
}
```

`ComponentInstallResultDTO`：

```java
package com.coding.common.models.k8s.dto;

import lombok.Data;

import java.util.List;

@Data
public class ComponentInstallResultDTO {
    private boolean success;
    private String message;
    private List<ReleaseStatusDTO> releases;

    @Data
    public static class ReleaseStatusDTO {
        private String releaseName;
        private String chartVersion;
        /** SUCCESS / FAILED */
        private String status;
        private String message;
    }
}
```

- [ ] **Step 4: 实现控制器**

```java
package com.coding.k8sserver.controllers.cluster;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.k8s.dto.ComponentInstallRequestDTO;
import com.coding.common.models.k8s.dto.ComponentInstallResultDTO;
import com.coding.common.models.k8s.dto.ReleaseInstallSpecDTO;
import com.coding.common.models.system.ResponseData;
import com.coding.k8score.factory.KubernetesClientFactory;
import com.coding.k8sserver.components.AccessBoundary;
import com.coding.k8sserver.components.AccessBoundaryAware;
import com.coding.k8sserver.components.ResourceAccessResolver;
import com.coding.k8sserver.helm.ChartBundle;
import com.coding.k8sserver.helm.HelmResult;
import com.coding.k8sserver.helm.HelmRunner;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 集群域 - 组件安装/升级（S3.6，**PLATFORM 边界**）。
 *
 * <p>这是平台唯一一处"自己往集群写非资源对象"的入口（helm）。授权在上游 platform-api
 * （{@code platform:cluster:manage}），本层只声明边界并执行。
 *
 * <p>为什么单开一个类：{@code BoundaryAuthorizationManager} 读 handler 所属类；挂到
 * 集群管理的 PLATFORM 类上虽然边界相同，但安装动作的审计与语义不同，独立成类更清楚。
 */
@Tag(name = "集群域-组件安装", description = "安装/升级集群组件（helm，平台管理员）")
@RestController
@RequestMapping("/components")
public class ComponentInstallController implements AccessBoundaryAware {

    private final HelmRunner helm;
    private final ChartBundle charts;
    private final KubernetesClientFactory clientFactory;
    private final ResourceAccessResolver accessResolver;

    public ComponentInstallController(HelmRunner helm, ChartBundle charts,
                                      KubernetesClientFactory clientFactory,
                                      ResourceAccessResolver accessResolver) {
        this.helm = helm;
        this.charts = charts;
        this.clientFactory = clientFactory;
        this.accessResolver = accessResolver;
    }

    @Override
    public AccessBoundary accessBoundary() {
        return AccessBoundary.PLATFORM;
    }

    @PostMapping("/apply")
    @Operation(summary = "安装/升级组件（helm upgrade --install）",
            description = "先 helm template 硬预检，再执行；每个 release 独立回报成功/失败")
    public ResponseData<ComponentInstallResultDTO> apply(@RequestBody ComponentInstallRequestDTO request) {
        accessResolver.assertClusterAccess(request.getClusterId());
        if (request.getReleases() == null || request.getReleases().isEmpty()) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "没有要安装的 release");
        }
        String kubeconfig = clientFactory.adminKubeconfig(request.getClusterId());

        List<ComponentInstallResultDTO.ReleaseStatusDTO> statuses = new ArrayList<>();
        boolean allOk = true;
        String firstError = null;

        for (ReleaseInstallSpecDTO spec : request.getReleases()) {
            ComponentInstallResultDTO.ReleaseStatusDTO st = new ComponentInstallResultDTO.ReleaseStatusDTO();
            st.setReleaseName(spec.getReleaseName());
            st.setChartVersion(spec.getChartVersion());
            Path valuesFile = null;
            try {
                Path chartDir = charts.chartDir(spec.getChartCode(), spec.getChartVersion());
                valuesFile = writeValues(spec.getValues());

                HelmResult rendered = helm.render(kubeconfig, chartDir, valuesFile, spec.getNamespace());
                if (!rendered.ok()) {
                    st.setStatus("FAILED");
                    st.setMessage("预检失败：" + rendered.output());
                    statuses.add(st);
                    allOk = false;
                    if (firstError == null) {
                        firstError = "预检失败：" + rendered.output();
                    }
                    continue;
                }
                // 服务端 dry-run：只把输出记为警告，不阻断（CRD 假阴性见 spec §3.1）
                HelmResult dry = helm.dryRunServer(kubeconfig, chartDir, valuesFile,
                        spec.getReleaseName(), spec.getNamespace());
                if (!dry.ok()) {
                    st.setMessage("服务端预检警告（不阻断）：" + dry.output());
                }
                HelmResult applied = helm.apply(kubeconfig, chartDir, valuesFile,
                        spec.getReleaseName(), spec.getNamespace(), labels(request));
                st.setStatus(applied.ok() ? "SUCCESS" : "FAILED");
                st.setMessage(applied.ok() ? null : applied.output());
                statuses.add(st);
                if (!applied.ok()) {
                    allOk = false;
                    if (firstError == null) {
                        firstError = applied.output();
                    }
                }
            } catch (Exception e) {
                st.setStatus("FAILED");
                st.setMessage(e.getMessage());
                statuses.add(st);
                allOk = false;
                if (firstError == null) {
                    firstError = e.getMessage();
                }
            } finally {
                if (valuesFile != null) {
                    try {
                        Files.deleteIfExists(valuesFile);
                    } catch (Exception ignored) {
                        // 与 kubeconfig 同样的原则：清理失败不改变结果，但要留痕
                    }
                }
            }
        }

        ComponentInstallResultDTO out = new ComponentInstallResultDTO();
        out.setSuccess(allOk);
        out.setMessage(firstError);
        out.setReleases(statuses);
        return new ResponseData<>(out);
    }

    private static Map<String, String> labels(ComponentInstallRequestDTO request) {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("platform.coding/managed", "true");
        labels.put("platform.coding/component", request.getComponentCode());
        labels.put("platform.coding/cluster", request.getClusterId());
        return labels;
    }

    /** values 写成临时 YAML（用 Jackson 序列化，避免手拼 YAML） */
    private static Path writeValues(Map<String, Object> values) throws Exception {
        Path file = Files.createTempFile("values-", ".yaml");
        tools.jackson.databind.json.JsonMapper mapper = tools.jackson.databind.json.JsonMapper.builder().build();
        Files.writeString(file, mapper.writeValueAsString(values));
        return file;
    }
}
```

⚠️ 注意：JSON 是合法的 YAML 子集，所以把 values 以 JSON 写进 `.yaml` 文件，helm 能正确解析。这一条要写进注释。

- [ ] **Step 5: 跑测试确认通过**

Run: `mvn -pl k8s-server -am test -Dtest=ComponentInstallControllerTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS（3 个测试）。

- [ ] **Step 6: 提交**

```bash
git add platform-common/src/main/java/com/coding/common/models/k8s/dto/ComponentInstallRequestDTO.java \
        platform-common/src/main/java/com/coding/common/models/k8s/dto/ComponentInstallResultDTO.java \
        platform-common/src/main/java/com/coding/common/models/k8s/dto/ReleaseInstallSpecDTO.java \
        k8s-server/src/main/java/com/coding/k8sserver/controllers/cluster/ComponentInstallController.java \
        k8s-server/src/test/java/com/coding/k8sserver/controllers/cluster/ComponentInstallControllerTest.java
git commit -m "feat(components): k8s-server 组件安装端点（PLATFORM 边界 + 预检分级）"
```

---

### Task 6: platform-api recipe 目录（metrics-server）

**Files:**
- Create: `platform-api/src/main/java/com/coding/platformapi/components/ReleaseSpec.java`
- Create: `platform-api/src/main/java/com/coding/platformapi/components/ComponentRecipe.java`
- Create: `platform-api/src/main/java/com/coding/platformapi/components/ComponentRecipeCatalog.java`
- Test: `platform-api/src/test/java/com/coding/platformapi/components/ComponentRecipeCatalogTest.java`

**Interfaces:**
- Produces:
  - `ComponentRecipe(profile, List<ReleaseSpec> releases, List<String> requires)`
  - `ReleaseSpec(releaseName, namespace, chartCode, chartVersion, Map<String,Object> contractValues, Map<String,Object> editableValues)`
  - `ComponentRecipeCatalog.recipeOf(String code, String profile) -> ComponentRecipe`（找不到 → null）
  - `ComponentRecipeCatalog.supports(String code) -> boolean`

- [ ] **Step 1: 写失败的测试**

```java
package com.coding.platformapi.components;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ComponentRecipeCatalogTest {

    private final ComponentRecipeCatalog catalog = new ComponentRecipeCatalog();

    @Test
    void metricsServerHasRecipesForBothProfiles() {
        for (String profile : List.of("BUSINESS", "MANAGEMENT")) {
            ComponentRecipe r = catalog.recipeOf("metrics-server", profile);
            assertThat(r).as("profile %s", profile).isNotNull();
            assertThat(r.releases()).hasSize(1);
        }
    }

    @Test
    void metricsServerRecipeCarriesTheKubeletInsecureTlsContract() {
        // 契约值：自签 kubelet 证书的集群不加这个参数，metrics-server 永远 NotReady
        ReleaseSpec spec = catalog.recipeOf("metrics-server", "BUSINESS").releases().get(0);
        assertThat(String.valueOf(spec.contractValues().get("args")))
                .contains("--kubelet-insecure-tls");
    }

    @Test
    void releaseNameAndNamespaceAreFixed() {
        ReleaseSpec spec = catalog.recipeOf("metrics-server", "BUSINESS").releases().get(0);
        assertThat(spec.releaseName()).isEqualTo("platform-metrics-server");
        assertThat(spec.namespace()).isEqualTo("kube-system");
    }

    @Test
    void theChartVersionExistsInTheAdapterBundle() throws Exception {
        // ⭐ 跨模块一致性：recipe 指向的版本必须真的在 k8s-server 的内置 chart 里
        ReleaseSpec spec = catalog.recipeOf("metrics-server", "BUSINESS").releases().get(0);
        Path chart = Path.of("../k8s-server/charts/components/" + spec.chartCode() + "/"
                + spec.chartVersion() + "/chart/Chart.yaml");
        assertThat(Files.exists(chart)).as("缺少内置 chart：%s", chart).isTrue();
    }

    @Test
    void anUnknownComponentOrProfileHasNoRecipe() {
        assertThat(catalog.recipeOf("no-such", "BUSINESS")).isNull();
        assertThat(catalog.supports("metrics-server")).isTrue();
    }
}
```

- [ ] **Step 2: 跑测试确认它失败**

Run: `mvn -pl platform-api test -Dtest=ComponentRecipeCatalogTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 编译失败 —— 类不存在。

- [ ] **Step 3: 实现**

`ReleaseSpec.java`：

```java
package com.coding.platformapi.components;

import java.util.Map;

/**
 * 一个 release 的安装规格。
 *
 * @param releaseName    平台固定（用户不能改，否则探测找不到）
 * @param namespace      平台固定（与 S1.2 探测的命名空间一致）
 * @param chartCode      内置 chart 目录名
 * @param chartVersion   chart 版本（升级判定比的就是它）
 * @param contractValues 🔒 平台契约值（不可被用户覆盖）
 * @param editableValues ⚙️ 少量可编辑字段的默认值
 */
public record ReleaseSpec(String releaseName, String namespace, String chartCode, String chartVersion,
                          Map<String, Object> contractValues, Map<String, Object> editableValues) {
}
```

`ComponentRecipe.java`：

```java
package com.coding.platformapi.components;

import java.util.List;

/**
 * 某画像下一条组件的安装方式。
 *
 * @param profile   MANAGEMENT / BUSINESS
 * @param releases  该画像要装的 release（通常 1 个）
 * @param requires  前置组件 code（安装前必须已 INSTALLED_HEALTHY）
 */
public record ComponentRecipe(String profile, List<ReleaseSpec> releases, List<String> requires) {
}
```

`ComponentRecipeCatalog.java`：

```java
package com.coding.platformapi.components;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 组件的安装方式目录（S3.6，批① 只接 metrics-server）。
 *
 * <p>与 {@link ClusterComponentCatalog} 的分工：那边回答"装没装"（探测判据），
 * 这边回答"怎么装"（chart / 版本 / values 契约）。两者用同一个 component code 关联。
 *
 * <p>批② 在这里逐条补其余 10 个组件；每个组件的契约值以 spec §5 为准。
 */
@Component
public class ComponentRecipeCatalog {

    private static final String BUSINESS = "BUSINESS";
    private static final String MANAGEMENT = "MANAGEMENT";

    private static final ComponentRecipe METRICS_SERVER = new ComponentRecipe(
            BUSINESS,
            List.of(new ReleaseSpec(
                    "platform-metrics-server", "kube-system", "metrics-server", "1.0.0",
                    // 🔒 契约值
                    Map.of("args", List.of("--kubelet-insecure-tls")),
                    // ⚙️ 可编辑默认值
                    Map.of("replicas", 1))),
            List.of());

    private static final Map<String, Map<String, ComponentRecipe>> RECIPES = Map.of(
            "metrics-server", Map.of(
                    BUSINESS, METRICS_SERVER,
                    MANAGEMENT, new ComponentRecipe(MANAGEMENT, METRICS_SERVER.releases(), List.of())));

    /** 没有安装方式的组件（批② 之前）返回 null；调用方据此禁用安装按钮 */
    public ComponentRecipe recipeOf(String code, String profile) {
        Map<String, ComponentRecipe> byProfile = RECIPES.get(code);
        return byProfile == null ? null : byProfile.get(profile);
    }

    public boolean supports(String code) {
        return RECIPES.containsKey(code);
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -pl platform-api test -Dtest=ComponentRecipeCatalogTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS（5 个测试）。

- [ ] **Step 5: 提交**

```bash
git add platform-api/src/main/java/com/coding/platformapi/components/ReleaseSpec.java \
        platform-api/src/main/java/com/coding/platformapi/components/ComponentRecipe.java \
        platform-api/src/main/java/com/coding/platformapi/components/ComponentRecipeCatalog.java \
        platform-api/src/test/java/com/coding/platformapi/components/ComponentRecipeCatalogTest.java
git commit -m "feat(components): recipe 目录（metrics-server 试点，含契约值）"
```

---

### Task 7: platform-api 异步操作（表实体 / 服务 / 状态机）

**Files:**
- Create: `platform-data/src/main/java/com/coding/data/models/system/PlatformComponentOperation.java`
- Create: `platform-data/src/main/java/com/coding/data/mapper/system/PlatformComponentOperationMapper.java`
- Create: `platform-data/src/main/resources/mapper/system/PlatformComponentOperationMapper.xml`
- Create: `platform-api/src/main/java/com/coding/platformapi/services/ComponentInstallService.java`
- Create: `platform-api/src/main/java/com/coding/platformapi/models/ComponentApplyRequest.java`
- Test: `platform-api/src/test/java/com/coding/platformapi/services/ComponentInstallServiceTest.java`

**Interfaces:**
- Produces:
  - `ComponentInstallService.apply(ComponentApplyRequest req, String operator) -> Long operationId`
  - `ComponentInstallService.get(Long id) -> PlatformComponentOperation`
  - `ComponentInstallService.latestByCluster(String clusterId) -> List<PlatformComponentOperation>`
  - 状态常量 `PENDING/RUNNING/SUCCESS/FAILED/UNKNOWN`

- [ ] **Step 1: 写失败的测试**

```java
package com.coding.platformapi.services;

import com.coding.data.mapper.system.PlatformComponentOperationMapper;
import com.coding.data.models.system.PlatformComponentOperation;
import com.coding.platformapi.components.ComponentRecipeCatalog;
import com.coding.platformapi.k8s.K8sComponentClient;
import com.coding.platformapi.models.ComponentApplyRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ComponentInstallServiceTest {

    private final PlatformComponentOperationMapper mapper = mock(PlatformComponentOperationMapper.class);
    private final K8sComponentClient client = mock(K8sComponentClient.class);
    private final ClusterService clusterService = mock(ClusterService.class);
    private final ClusterComponentService components = mock(ClusterComponentService.class);

    @org.junit.jupiter.api.BeforeEach
    void insertReturnsAnId() {
        // MyBatis 的 useGeneratedKeys 在真实 DB 上回填 id；mock 必须模拟这件事
        org.mockito.Mockito.doAnswer(inv -> {
            inv.getArgument(0, PlatformComponentOperation.class).setId(1L);
            return 1;
        }).when(mapper).insert(any());
    }

    private ComponentInstallService service() {
        return new ComponentInstallService(mapper, client, new ComponentRecipeCatalog(),
                clusterService, components);
    }

    @Test
    void applyRejectsWhenTheComponentHasNoRecipe() {
        ComponentApplyRequest req = new ComponentApplyRequest("c1", "gateway-api", "INSTALL", null, Map.of());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service().apply(req, "admin"))
                .hasMessageContaining("尚未支持安装");
        verify(mapper, never()).insert(any());
    }

    @Test
    void applyRejectsASecondRunningOperationForTheSameComponent() {
        when(clusterService.requireProfile("c1")).thenReturn("BUSINESS");
        when(mapper.countRunning("c1", "metrics-server")).thenReturn(1);
        ComponentApplyRequest req = new ComponentApplyRequest("c1", "metrics-server", "INSTALL", null, Map.of());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service().apply(req, "admin"))
                .hasMessageContaining("正在执行");
        verify(mapper, never()).insert(any());
    }

    @Test
    void aSuccessfulRunMarksSuccessAndRefreshesTheProbe() throws Exception {
        when(clusterService.requireProfile("c1")).thenReturn("BUSINESS");
        when(mapper.countRunning("c1", "metrics-server")).thenReturn(0);
        when(client.applyComponent(anyString(), any())).thenReturn(successResult());
        CountDownLatch done = new CountDownLatch(1);
        org.mockito.Mockito.doAnswer(inv -> {
            done.countDown();
            return null;
        }).when(components).refresh("c1");

        Long id = service().apply(new ComponentApplyRequest(
                "c1", "metrics-server", "INSTALL", null, Map.of()), "admin");

        assertThat(id).isEqualTo(1L);
        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        // ⭐ 装完必须刷新探测，否则页面还显示"未安装"
        verify(components).refresh("c1");
        verify(mapper, timeout(5000)).updateById(org.mockito.ArgumentMatchers.argThat(
                op -> "SUCCESS".equals(op.getStatus())));
    }

    @Test
    void aFailedRunRecordsTheHelmMessage() throws Exception {
        when(clusterService.requireProfile("c1")).thenReturn("BUSINESS");
        when(mapper.countRunning("c1", "metrics-server")).thenReturn(0);
        when(client.applyComponent(anyString(), any())).thenReturn(failureResult());

        service().apply(new ComponentApplyRequest(
                "c1", "metrics-server", "INSTALL", null, Map.of()), "admin");

        verify(mapper, timeout(5000)).updateById(org.mockito.ArgumentMatchers.argThat(
                op -> "FAILED".equals(op.getStatus()) && op.getMessage().contains("预检")));
    }

    @Test
    void staleRunningOperationsAreMarkedUnknown() {
        PlatformComponentOperation stale = new PlatformComponentOperation();
        stale.setId(7L);
        stale.setStatus("RUNNING");
        when(mapper.selectStaleRunning(15)).thenReturn(List.of(stale));

        service().markStaleRunning();

        verify(mapper).updateById(org.mockito.ArgumentMatchers.argThat(
                op -> "UNKNOWN".equals(op.getStatus())));
    }

    private static com.coding.common.models.k8s.dto.ComponentInstallResultDTO successResult() {
        var r = new com.coding.common.models.k8s.dto.ComponentInstallResultDTO();
        r.setSuccess(true);
        var s = new com.coding.common.models.k8s.dto.ComponentInstallResultDTO.ReleaseStatusDTO();
        s.setReleaseName("platform-metrics-server");
        s.setStatus("SUCCESS");
        r.setReleases(List.of(s));
        return r;
    }

    private static com.coding.common.models.k8s.dto.ComponentInstallResultDTO failureResult() {
        var r = successResult();
        r.setSuccess(false);
        r.setMessage("预检失败：values path not found");
        r.getReleases().get(0).setStatus("FAILED");
        return r;
    }
}
```

- [ ] **Step 2: 跑测试确认它失败**

Run: `mvn -pl platform-api test -Dtest=ComponentInstallServiceTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 编译失败 —— 实体/服务/DTO 不存在。

- [ ] **Step 3: 实体 + mapper + XML**

`PlatformComponentOperation`（`@Data`，字段与 Task 1 的表一一对应：id Long、clusterId、componentCode、action、profile、fromVersion、toVersion、releaseNames、valuesSnapshot、status、message、logTail、operator、startedAt、finishedAt）。

`PlatformComponentOperationMapper`：

```java
int insert(PlatformComponentOperation record);           // useGeneratedKeys
int updateById(PlatformComponentOperation record);       // 只更新 status/message/log_tail/finished_at/to_version/release_names/values_snapshot
PlatformComponentOperation selectByPrimaryKey(@Param("id") Long id);
List<PlatformComponentOperation> selectLatestPerComponent(@Param("clusterId") String clusterId);
/** 最近一次成功的操作（升级时用它取 fromVersion） */
PlatformComponentOperation selectLatestSuccess(@Param("clusterId") String clusterId,
                                              @Param("componentCode") String componentCode);
int countRunning(@Param("clusterId") String clusterId, @Param("componentCode") String componentCode);
List<PlatformComponentOperation> selectStaleRunning(@Param("minutes") int minutes);
```

XML 要点：
- `selectLatestPerComponent`：`select o.* from platform_component_operation o join (select component_code, max(id) mid from platform_component_operation where cluster_id=#{clusterId} group by component_code) t on o.id=t.mid`
- `countRunning`：`status in ('PENDING','RUNNING')`
- `selectStaleRunning`：`status='RUNNING' and started_at < now() - interval #{minutes} minute`

- [ ] **Step 4: `K8sComponentClient.applyComponent`**

```java
    /**
     * 安装/升级（长任务）：POST /components/apply。
     * 用**慢速通道**（30 分钟读超时）——helm 装一个栈可能要几分钟，常规 120s 会提前断开。
     */
    public ComponentInstallResultDTO applyComponent(String clusterId, List<ReleaseInstallSpecDTO> releases) {
        ComponentInstallRequestDTO body = new ComponentInstallRequestDTO();
        body.setClusterId(clusterId);
        body.setReleases(releases);
        return gateway.exchangeSlow(HttpMethod.POST, "/components/apply", null, body,
                gateway.responseType(ComponentInstallResultDTO.class));
    }
```

`K8sServerGateway` 增加 `exchangeSlow(...)`：与 `exchange(...)` 完全同形，但用 `streamRestClient`（30 分钟读超时）。**不要复制反序列化逻辑**：把 `exchange` 的实现抽成私有 `exchangeWith(RestClient client, ...)`，两个公开方法各传一个 client。

- [ ] **Step 5: `ComponentApplyRequest`**

```java
package com.coding.platformapi.models;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/** {@code POST /cluster/components/apply} 的请求体。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ComponentApplyRequest {
    private String clusterId;
    private String componentCode;
    /** INSTALL / UPGRADE */
    private String action;
    /** 目标 recipe 版本（空 = 用 recipe 的当前版本） */
    private String targetVersion;
    /** 用户可编辑的 values（只允许 recipe 声明的字段） */
    private Map<String, Object> editableValues;
}
```

- [ ] **Step 6: `ComponentInstallService`**

```java
package com.coding.platformapi.services;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.k8s.dto.ComponentInstallResultDTO;
import com.coding.common.models.k8s.dto.ReleaseInstallSpecDTO;
import com.coding.data.mapper.system.PlatformComponentOperationMapper;
import com.coding.data.models.system.PlatformComponentOperation;
import com.coding.platformapi.components.ComponentRecipe;
import com.coding.platformapi.components.ComponentRecipeCatalog;
import com.coding.platformapi.components.ReleaseSpec;
import com.coding.platformapi.k8s.K8sComponentClient;
import com.coding.platformapi.models.ComponentApplyRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * 组件安装/升级的编排（S3.6）。
 *
 * <p><b>为什么异步</b>：helm 装一个栈可能几分钟，同步 HTTP 撑不住（网关常规读超时 120s）。
 * 操作记录先落 PENDING，虚拟线程执行，前端轮询。
 *
 * <p><b>事务边界</b>：{@code @Transactional} 只包 insert —— 虚拟线程必须在事务提交后启动，
 * 否则 RUNNING 的更新可能读不到未提交的行。
 *
 * <p><b>成功判定</b>：helm 成功后会调用 {@link ClusterComponentService#refresh} 重探；
 * 页面上的"已装"以探测为准，不以 helm exit code 为准。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ComponentInstallService {

    public static final String PENDING = "PENDING";
    public static final String RUNNING = "RUNNING";
    public static final String SUCCESS = "SUCCESS";
    public static final String FAILED = "FAILED";
    public static final String UNKNOWN = "UNKNOWN";

    /** 超过这个分钟数仍 RUNNING → 判定为中断 */
    private static final int STALE_MINUTES = 15;

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final PlatformComponentOperationMapper mapper;
    private final K8sComponentClient client;
    private final ComponentRecipeCatalog recipes;
    private final ClusterService clusterService;
    private final ClusterComponentService components;

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    @Transactional
    public Long apply(ComponentApplyRequest req, String operator) {
        String profile = clusterService.requireProfile(req.getClusterId());
        ComponentRecipe recipe = recipes.recipeOf(req.getComponentCode(), profile);
        if (recipe == null) {
            throw new CloudPlatformException(EnumResponseType.ERROR,
                    "该组件尚未支持安装（recipe 未配置）：" + req.getComponentCode());
        }
        if (mapper.countRunning(req.getClusterId(), req.getComponentCode()) > 0) {
            throw new CloudPlatformException(EnumResponseType.ERROR,
                    "该组件正在执行安装/升级，请等待当前操作结束");
        }
        requireDependencies(req.getClusterId(), recipe);

        List<ReleaseSpec> effective = mergeValues(recipe.releases(), req.getEditableValues());
        String targetVersion = resolveTargetVersion(effective, req.getTargetVersion());
        PlatformComponentOperation last =
                mapper.selectLatestSuccess(req.getClusterId(), req.getComponentCode());

        PlatformComponentOperation op = new PlatformComponentOperation();
        op.setClusterId(req.getClusterId());
        op.setComponentCode(req.getComponentCode());
        op.setAction(req.getAction() == null ? "INSTALL" : req.getAction());
        op.setProfile(profile);
        op.setFromVersion(last == null ? null : last.getToVersion());
        op.setToVersion(targetVersion);
        op.setReleaseNames(JSON.writeValueAsString(
                effective.stream().map(ReleaseSpec::releaseName).toList()));
        op.setValuesSnapshot(JSON.writeValueAsString(effective));
        op.setStatus(PENDING);
        op.setOperator(operator);
        op.setStartedAt(new Date());
        mapper.insert(op);

        Long id = op.getId();
        executor.submit(() -> run(id, req.getClusterId(), List.copyOf(effective)));
        return id;
    }

    /** 前置组件必须已 INSTALLED_HEALTHY（批① 的 metrics-server 无前置，批② 起生效） */
    private void requireDependencies(String clusterId, ComponentRecipe recipe) {
        if (recipe.requires() == null || recipe.requires().isEmpty()) {
            return;
        }
        Map<String, String> states = components.status(clusterId).stream()
                .collect(Collectors.toMap(
                        com.coding.platformapi.models.ComponentStatusDTO::getCode,
                        com.coding.platformapi.models.ComponentStatusDTO::getState));
        for (String dep : recipe.requires()) {
            if (!ClusterComponentService.HEALTHY.equals(states.get(dep))) {
                throw new CloudPlatformException(EnumResponseType.ERROR,
                        "前置组件未就绪：" + dep + "（需先安装并健康）");
            }
        }
    }

    /**
     * 合并 values：契约值打底，用户值只能覆盖 recipe **声明过**的可编辑 key。
     * 注意这是**顶层 key** 的合并；批② 的嵌套 values（如 prometheus.prometheusSpec.*）
     * 要么用扁平 key，要么在这里加路径式合并 —— 届时补用例。
     */
    private static List<ReleaseSpec> mergeValues(List<ReleaseSpec> releases, Map<String, Object> editable) {
        if (editable == null || editable.isEmpty()) {
            return releases;
        }
        List<ReleaseSpec> out = new ArrayList<>();
        for (ReleaseSpec r : releases) {
            Map<String, Object> merged = new LinkedHashMap<>(r.contractValues());
            for (String key : r.editableValues().keySet()) {
                if (editable.containsKey(key)) {
                    merged.put(key, editable.get(key));
                }
            }
            out.add(new ReleaseSpec(r.releaseName(), r.namespace(), r.chartCode(),
                    r.chartVersion(), merged, r.editableValues()));
        }
        return out;
    }

    /** 目标版本必须存在于 recipe 里（批① 只有一个版本；批② 起一个组件可有多个） */
    private static String resolveTargetVersion(List<ReleaseSpec> releases, String requested) {
        if (requested == null || requested.isBlank()) {
            return releases.get(0).chartVersion();
        }
        boolean known = releases.stream().anyMatch(r -> requested.equals(r.chartVersion()));
        if (!known) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "未知的目标版本：" + requested);
        }
        return requested;
    }

    private void run(Long id, String clusterId, List<ReleaseSpec> releases) {
        PlatformComponentOperation running = new PlatformComponentOperation();
        running.setId(id);
        running.setStatus(RUNNING);
        mapper.updateById(running);
        try {
            ComponentInstallResultDTO result = client.applyComponent(clusterId, toDtos(releases));
            PlatformComponentOperation done = new PlatformComponentOperation();
            done.setId(id);
            done.setStatus(result.isSuccess() ? SUCCESS : FAILED);
            done.setMessage(result.getMessage());
            done.setLogTail(tailOf(result));
            done.setFinishedAt(new Date());
            mapper.updateById(done);
            if (result.isSuccess()) {
                // 以探测为准：装完刷新一次，页面状态跟着变
                components.refresh(clusterId);
            }
        } catch (Exception e) {
            log.warn("组件操作 {} 执行失败: {}", id, e.getMessage());
            PlatformComponentOperation failed = new PlatformComponentOperation();
            failed.setId(id);
            failed.setStatus(FAILED);
            failed.setMessage(e.getMessage());
            failed.setFinishedAt(new Date());
            mapper.updateById(failed);
        }
    }

    private static List<ReleaseInstallSpecDTO> toDtos(List<ReleaseSpec> releases) {
        List<ReleaseInstallSpecDTO> out = new ArrayList<>();
        for (ReleaseSpec r : releases) {
            ReleaseInstallSpecDTO dto = new ReleaseInstallSpecDTO();
            dto.setReleaseName(r.releaseName());
            dto.setNamespace(r.namespace());
            dto.setChartCode(r.chartCode());
            dto.setChartVersion(r.chartVersion());
            dto.setValues(r.contractValues());
            out.add(dto);
        }
        return out;
    }

    private static String tailOf(ComponentInstallResultDTO result) {
        if (result.getReleases() == null) {
            return null;
        }
        String joined = result.getReleases().stream()
                .map(r -> r.getReleaseName() + ": " + r.getStatus()
                        + (r.getMessage() == null ? "" : " — " + r.getMessage()))
                .collect(Collectors.joining("\n"));
        return joined.length() <= 8192 ? joined : joined.substring(joined.length() - 8192);
    }

    /** 启动时调用：超过 15 分钟仍 RUNNING 的标 UNKNOWN（进程重启/超时中断） */
    public int markStaleRunning() {
        List<PlatformComponentOperation> stale = mapper.selectStaleRunning(STALE_MINUTES);
        for (PlatformComponentOperation op : stale) {
            PlatformComponentOperation upd = new PlatformComponentOperation();
            upd.setId(op.getId());
            upd.setStatus(UNKNOWN);
            upd.setMessage("操作中断（进程重启或超时）：请查看 release 状态后重试");
            upd.setFinishedAt(new Date());
            mapper.updateById(upd);
        }
        return stale.size();
    }

    public PlatformComponentOperation get(Long id) {
        return mapper.selectByPrimaryKey(id);
    }

    public PlatformComponentOperation latest(String clusterId, String componentCode) {
        return mapper.selectLatestSuccess(clusterId, componentCode);
    }

    public List<PlatformComponentOperation> latestByCluster(String clusterId) {
        return mapper.selectLatestPerComponent(clusterId);
    }
}
```

> ⚠️ `latest()` 用 `selectLatestSuccess` 会让"正在跑的操作"查不到 —— 轮询走的是 `get(id)`，
> 页面首屏用的是 `latestByCluster`（它取每个组件 id 最大的那条，含进行中的）。两个语义不要混。

- [ ] **Step 7: 启动时标定 stale**

```java
@Component
@RequiredArgsConstructor
public class ComponentOperationStaleMarker implements ApplicationRunner {
    private final ComponentInstallService service;
    @Override public void run(ApplicationArguments args) { service.markStaleRunning(); }
}
```

- [ ] **Step 8: 跑测试确认通过 + 全量**

Run: `mvn -pl platform-api test -Dtest=ComponentInstallServiceTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS（5 个测试）。
Run: `mvn -pl platform-api test`
Expected: 全绿。

- [ ] **Step 9: 提交**

```bash
git add platform-data/src/main/java/com/coding/data/models/system/PlatformComponentOperation.java \
        platform-data/src/main/java/com/coding/data/mapper/system/PlatformComponentOperationMapper.java \
        platform-data/src/main/resources/mapper/system/PlatformComponentOperationMapper.xml \
        platform-api/src/main/java/com/coding/platformapi/services/ComponentInstallService.java \
        platform-api/src/main/java/com/coding/platformapi/services/ComponentOperationStaleMarker.java \
        platform-api/src/main/java/com/coding/platformapi/models/ComponentApplyRequest.java \
        platform-api/src/main/java/com/coding/platformapi/k8s/K8sComponentClient.java \
        platform-api/src/main/java/com/coding/platformapi/k8s/K8sServerGateway.java \
        platform-api/src/test/java/com/coding/platformapi/services/ComponentInstallServiceTest.java
git commit -m "feat(components): 组件安装异步操作（状态机 + 并发拒绝 + 探测联动）"
```

---

### Task 8: platform-api 三个端点 + 审计

**Files:**
- Modify: `platform-api/src/main/java/com/coding/platformapi/controllers/ClusterController.java`
- Modify: `platform-api/src/main/java/com/coding/platformapi/audit/AuditAction.java`
- Test: `platform-api/src/test/java/com/coding/platformapi/controllers/ComponentLifecycleEndpointsTest.java`

**Interfaces:**
- Produces:
  - `POST /cluster/components/apply` → `ResponseData<Long>`（operationId）
  - `POST /cluster/components/operations/get` → `ResponseData<PlatformComponentOperation>`（body: `{operationId}` 或 `{clusterId, componentCode}`）
  - `POST /cluster/components/operations/list` → `ResponseData<List<PlatformComponentOperation>>`

- [ ] **Step 1: 写失败的测试**

MockMvc 三条路径委托 + `PermissionCrossCheck` 会用到的路径字符串（照 `ClusterComponentEndpointsTest` 的形态）。断言：
- apply 返回 operationId；
- operations/get 支持按 id；
- operations/list 按 clusterId。

- [ ] **Step 2: 跑测试确认失败**

- [ ] **Step 3: 加端点**

```java
    @PostMapping("/components/apply")
    @Auditable(action = AuditAction.COMPONENT_APPLY, target = "#request.componentCode")
    @Operation(summary = "安装/升级集群组件（异步；返回操作 id）")
    public ResponseData<Long> applyComponent(@RequestBody ComponentApplyRequest request) {
        TokenUserInfo me = auth.current();
        String operator = me != null ? me.getUsername() : "unknown";
        return new ResponseData<>(componentInstallService.apply(request, operator));
    }

    @PostMapping("/components/operations/get")
    // 注意：返回实体本身（只含操作字段，无敏感信息）
    @Operation(summary = "查询一条组件操作（轮询进度）")
    public ResponseData<PlatformComponentOperation> componentOperation(@RequestBody ComponentOperationQuery request) {
        if (request.getOperationId() != null) {
            return new ResponseData<>(componentInstallService.get(request.getOperationId()));
        }
        return new ResponseData<>(componentInstallService.latest(request.getClusterId(), request.getComponentCode()));
    }

    @PostMapping("/components/operations/list")
    @Operation(summary = "按集群列出各组件最近一次操作")
    public ResponseData<List<PlatformComponentOperation>> componentOperations(@RequestBody ClusterKeyRequest request) {
        return new ResponseData<>(componentInstallService.latestByCluster(request.getClusterId()));
    }
```

`AuditAction` 加 `COMPONENT_APPLY = "component:apply"`。

`ComponentOperationQuery`（`platform-api/.../models/ComponentOperationQuery.java`）：

```java
package com.coding.platformapi.models;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/** 查询组件操作：按 id，或按 (clusterId, componentCode) 取最近一条。 */
@Data
public class ComponentOperationQuery {
    @Schema(description = "操作 id（优先）")
    private Long operationId;
    private String clusterId;
    private String componentCode;
}
```

`ClusterController` 注入 `private final AuthContext auth;`（该类目前是 `@Autowired` 字段注入，照既有风格加一个字段）。

- [ ] **Step 4: 跑测试 + 全量**

- [ ] **Step 5: 提交**

---

### Task 9: 前端（安装/升级 + 进度轮询 + 集群角色）

**Files:**
- Modify: `platform-web/src/api/index.ts`（clusterApi 加三个方法）
- Modify: `platform-web/src/types.ts`（`K8sComponentOperation`）
- Create: `platform-web/src/composables/useComponentOperations.ts`
- Modify: `platform-web/src/components/cluster/ClusterComponentsPanel.vue`
- Modify: `platform-web/src/views/ClusterDetailView.vue`
- Modify: `platform-web/src/views/ClusterView.vue`（集群角色的表单项与列）

- [ ] **Step 1: 类型与 API**

`types.ts`：

```ts
/** 组件安装/升级操作（S3.6）。chart 版本与应用版本是两套编号 —— from/to 指 chart 版本 */
export type K8sComponentOperationStatus = 'PENDING' | 'RUNNING' | 'SUCCESS' | 'FAILED' | 'UNKNOWN'

export interface K8sComponentOperation {
  id: number
  clusterId: string
  componentCode: string
  action: 'INSTALL' | 'UPGRADE'
  profile: string
  fromVersion?: string | null
  toVersion?: string | null
  status: K8sComponentOperationStatus
  message?: string | null
  logTail?: string | null
  operator?: string | null
  startedAt?: string | null
  finishedAt?: string | null
}
```

`api/index.ts` 的 `clusterApi` 里：

```ts
  /** 安装/升级组件（异步，返回 operationId；需 platform:cluster:manage） */
  componentApply: (payload: {
    clusterId: string
    componentCode: string
    action: 'INSTALL' | 'UPGRADE'
    targetVersion?: string
    editableValues?: Record<string, unknown>
  }) => http.post<never, number>('/cluster/components/apply', payload),
  /** 查一条操作（轮询）：按 operationId，或按 (clusterId, componentCode) 取最近一条 */
  componentOperation: (payload: {
    operationId?: number
    clusterId?: string
    componentCode?: string
  }) => http.post<never, K8sComponentOperation>('/cluster/components/operations/get', payload),
  /** 按集群列出各组件最近一次操作（组件 tab 首屏一次拿全） */
  componentOperations: (clusterId: string) =>
    http.post<never, K8sComponentOperation[]>('/cluster/components/operations/list', { clusterId }),
```

- [ ] **Step 2: `useComponentOperations`**

```ts
import { ref, watch, type Ref } from 'vue'
import { clusterApi } from '@/api'
import type { K8sComponentOperation } from '@/types'

/**
 * 组件安装/升级操作（S3.6）。
 *
 * <p>「最近一次操作」按组件 code 索引；轮询用 operationId。轮询间隔 2s，
 * 上限 15 分钟（与 HelmRunner 的进程超时一致：helm 自身 10m + 余量）—— 到点不再轮询，页面显示"仍未结束"。
 *
 * <p>返回普通对象（非 reactive）：消费方显式写 `.value`（同其它 composable 的约定）。
 */
export function useComponentOperations(clusterId: Ref<string | null | undefined>) {
  const latest = ref<Record<string, K8sComponentOperation>>({})
  const loading = ref(false)

  async function load(): Promise<void> {
    const id = clusterId.value
    if (!id) { latest.value = {}; return }
    loading.value = true
    try {
      const rows = await clusterApi.componentOperations(id)
      if (clusterId.value !== id) return
      latest.value = Object.fromEntries((rows ?? []).map((r) => [r.componentCode, r]))
    } catch {
      if (clusterId.value === id) latest.value = {}
    } finally {
      if (clusterId.value === id) loading.value = false
    }
  }

  async function apply(
    componentCode: string,
    action: 'INSTALL' | 'UPGRADE',
    targetVersion?: string,
    editableValues?: Record<string, unknown>,
  ): Promise<number> {
    const id = clusterId.value
    if (!id) throw new Error('缺少集群上下文')
    const operationId = await clusterApi.componentApply({
      clusterId: id, componentCode, action, targetVersion, editableValues,
    })
    await load()
    return operationId
  }

  /** 轮询到终态；超时抛错由调用方展示 */
  async function wait(operationId: number, timeoutMs = 15 * 60 * 1000): Promise<K8sComponentOperation> {
    const deadline = Date.now() + timeoutMs
    for (;;) {
      const op = await clusterApi.componentOperation({ operationId })
      if (op && op.status !== 'PENDING' && op.status !== 'RUNNING') {
        await load()
        return op
      }
      if (Date.now() > deadline) {
        throw new Error('操作仍未结束（超过 15 分钟），请稍后刷新查看')
      }
      await new Promise((r) => setTimeout(r, 2000))
    }
  }

  watch(clusterId, () => void load(), { immediate: true })
  return { latest, loading, load, apply, wait }
}
```

- [ ] **Step 3: 面板加动作**

`ClusterComponentsPanel` 的 props 增加：

```ts
  /** 各组件最近一次操作（由页面统一加载） */
  operations: Record<string, K8sComponentOperation>
  /** 是否有安装 recipe（批① 只有 metrics-server） */
  installable: (code: string) => boolean
```

emits 增加 `apply`：

```ts
const emit = defineEmits<{
  (e: 'refresh'): void
  (e: 'apply', payload: { code: string; action: 'INSTALL' | 'UPGRADE'; targetVersion?: string }): void
}>()
```

操作列（新增一列「操作」，放在「影响的功能」之后）：

```vue
      <el-table-column label="操作" width="120" fixed="right">
        <template #default="{ row }">
          <template v-if="row.state === 'NOT_INSTALLED' && installable(row.code)">
            <el-button link type="primary" @click="emit('apply', { code: row.code, action: 'INSTALL' })">
              安装
            </el-button>
          </template>
          <template v-else-if="row.state === 'INSTALLED_HEALTHY' || row.state === 'INSTALLED_UNHEALTHY'">
            <el-tooltip v-if="!operations[row.code]" content="该组件由外部安装，平台不接管" placement="top">
              <span class="cc-readonly">只读</span>
            </el-tooltip>
            <el-button v-else link type="primary"
                       @click="emit('apply', { code: row.code, action: 'UPGRADE', targetVersion: operations[row.code]?.toVersion ?? undefined })">
              升级
            </el-button>
          </template>
          <!-- UNKNOWN：不给装 —— "不知道"不是"没有" -->
          <span v-else-if="row.state === 'UNKNOWN'" class="cc-readonly">—</span>
        </template>
      </el-table-column>
```

样式补 `.cc-readonly { font-size: 12px; color: var(--text-3); }`。

- [ ] **Step 4: 页面接线（`ClusterDetailView`）**

```ts
const operations = useComponentOperations(computed(() => clusterId.value || null))
const recipeCodes = new Set(['metrics-server']) // 批①；批② 改为从后端下发
const installable = (code: string) => recipeCodes.has(code)
```

模板里给面板补两个 prop：

```vue
          <ClusterComponentsPanel
            :statuses="components.statuses.value"
            :loading="components.statusesLoading.value"
            :can-refresh="components.canManage.value"
            :operations="operations.latest.value"
            :installable="installable"
            @refresh="components.refresh()"
            @apply="onApply"
          />
```

`onApply`：

```ts
const applying = ref<string | null>(null)

async function onApply(p: { code: string; action: 'INSTALL' | 'UPGRADE'; targetVersion?: string }): Promise<void> {
  const target = components.statusOf(p.code)
  try {
    await ElMessageBox.confirm(
      `将 ${p.action === 'INSTALL' ? '安装' : '升级'} ${target?.name ?? p.code}。\n`
      + `release: platform-${p.code} · 命名空间由平台固定 · chart 版本 ${p.targetVersion ?? '当前版本'}\n`
      + '安装过程会向集群写入对象，请确认。',
      '确认操作',
      { type: 'warning' })
  } catch {
    return // 用户取消
  }
  applying.value = p.code
  try {
    const id = await operations.apply(p.code, p.action, p.targetVersion)
    const op = await operations.wait(id)
    if (op.status === 'SUCCESS') {
      ElMessage.success('操作完成，正在刷新探测…')
      await components.refresh()
    } else {
      ElMessage.error(op.message || '操作失败')
    }
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    applying.value = null
  }
}
```

（`ElMessageBox` / `ElMessage` 从 `element-plus` import；页面已有 `ref`/`computed`。）

- [ ] **Step 5: 集群列表加画像**

`ClusterView.vue` 的 form 增加 `clusterRole: 'BUSINESS'`；新增/编辑弹窗加：

```vue
        <el-form-item label="集群画像">
          <el-select v-model="form.clusterRole">
            <el-option label="业务集群（默认）" value="BUSINESS" />
            <el-option label="管理集群（平台自身所在）" value="MANAGEMENT" />
          </el-select>
        </el-form-item>
```

`clusterApi.create/update` 的 payload 带上 `clusterRole: form.clusterRole`；列表加一列展示。

- [ ] **Step 6: type-check**

Run: `cd platform-web && npm run type-check`
Expected: 通过。

- [ ] **Step 7: 浏览器冒烟（假 XHR）**

断言：
1. 未安装行出现「安装」；点开确认框后发出 `/cluster/components/apply`；
2. 轮询 `/cluster/components/operations/get`，收到 SUCCESS 后发出 `/cluster/components/refresh`；
3. `UNKNOWN` 行**没有**安装按钮；
4. 已安装但无平台操作记录的组件显示「只读」。

- [ ] **Step 8: 提交**

```bash
git add platform-web/src/api/index.ts platform-web/src/types.ts \
        platform-web/src/composables/useComponentOperations.ts \
        platform-web/src/components/cluster/ClusterComponentsPanel.vue \
        platform-web/src/views/ClusterDetailView.vue platform-web/src/views/ClusterView.vue
git commit -m "feat(components): 组件安装/升级 UI + 进度轮询 + 集群画像"
```

### Task 10: 收口（全量测试、台账、部署）

**Files:**
- Modify: `docs/task-ledger-2026-10-12.md`

- [ ] **Step 1: 全量后端**

Run: `mvn test`
Expected: 全绿（记录模块数与总数）。

- [ ] **Step 2: 前端**

Run: `cd platform-web && npm run type-check && npm run build`
Expected: 均通过（**type-check 不等于 build**；S3.1 那次 build 才抓到 TS2532）。

- [ ] **Step 3: 真集群试点（**需要用户明确批准**）**

在**非生产**集群上装 `metrics-server`：点安装 → 观察操作状态 → 探测变为 `INSTALLED_HEALTHY` → `kubectl top nodes` 可用。
⚠️ 若没有非生产集群，本步保持未执行并在台账注明（**不得**在管理集群上直接试点）。

- [ ] **Step 4: 台账**

在 §S3.6 下写批① 收口块：做了什么、与 spec 的出入、待办（批② 的 10 条组件）、部署说明。

- [ ] **Step 5: 部署说明**

- 重建 **k8s-server + platform-api + platform-web**；
- **先重放** `V2026_10_18_1__component_lifecycle.sql`；
- 重放后把平台所在集群改为 `MANAGEMENT`（见迁移注释里的 UPDATE）；
- k8s-server 镜像构建需要能下载 helm 4.2.0（内网镜像源或构建机代理），`charts/` 目录随镜像复制；
- 无需外部运行时依赖。

- [ ] **Step 6: 提交**

```bash
git add docs/task-ledger-2026-10-12.md
git commit -m "docs(ledger): S3.6 批①（引擎 + metrics-server 试点）收口"
```

---

## Self-Review 记录

**Spec 覆盖**
- §2 画像与 recipe → Task 2（cluster_role）、Task 6（recipe 目录）。
- §2.3 chart 内置 → Task 3。
- §3 执行链与状态机 → Task 4（helm）、Task 5（端点）、Task 7（异步操作）。
- §4 数据模型与权限 → Task 1、Task 2、Task 8。
- §5 配置契约 → 批① 只覆盖 metrics-server（Task 6 的契约值与 Task 3 的 chart）；其余 10 条在批②。
- §6 与 S1.2 联动 → Task 7 的 `components.refresh` + Task 9 的轮询完成后刷新。
- §7 测试 → 各任务内；真集群试点在 Task 10（需批准）。
- §8/§9 风险与不做 → Global Constraints。
- §10 部署 → Task 3（Dockerfile/charts）与 Task 10。

**已知的有意留白**
- 批① 的 UI 只接 metrics-server；组件 tab 对没有 recipe 的组件显示"暂不支持安装"（不隐藏，明确告知）。
- 批② 才处理 `requires` 的实际使用（metrics-server 无前置）；Task 7 里的 requires 校验按"空列表直接通过"实现，并在批② 补用例。
