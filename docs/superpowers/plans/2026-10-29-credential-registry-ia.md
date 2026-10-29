# 凭据与镜像仓库信息架构调整 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把散在三个入口的镜像/凭据能力收成「持续集成」组下的三页（流水线 / Git 凭据管理 / 镜像仓库），并第一次把凭据按租户开放。

**Architecture:** 不新增表。"仓库"是现有数据的聚合视角 —— Harbor 安装记录 ∪ 按 `endpoint` 去重的凭据行 ∪ 内置 Docker Hub。后端加一个只读聚合端点，其余端点复用；前端把 `RegistryView` 重写成主从两栏。

**Tech Stack:** Java 21 / Spring Boot / MyBatis（注解 mapper，无 Flyway）/ Vue 3 + Element Plus + TypeScript（无单测，靠 `vue-tsc` 类型检查）

**Spec:** `docs/superpowers/specs/2026-10-29-credential-registry-ia-design.md`

## Global Constraints

- **本仓没有 Flyway**：`platform-data/src/main/resources/db/migration/*.sql` 一律手工重放；`--` 注释后必须跟空白字符。
- **表驱动授权**：新增任何 HTTP 端点必须同时有权限行，否则 `PermissionCrossCheckRunner` 在启动期 fail-closed。新权限行必须**显式绑角色**（不按 code 继承）。
- **`CredentialView` 永不承载凭据内容**：`CredentialServiceTest.viewDtoHasNoCredentialField()` 是反射断言，字段名含 `secret` / `password` / `username` 会让测试失败（`hasSecret` 是唯一例外）。
- **租户角色不能持有 `platform:` 码**（角色不变量）。平台+租户共用的能力必须用 `tenant:` 码并同时绑 `admin`。
- 前端 `requiresPerm` 与菜单 `canAny` 都是 **ANY-of** 语义（`perm.hasAny`）。
- 前端没有单元测试：每个前端任务的验证是 `cd platform-web && npm run build`（含 `vue-tsc` 类型检查）。
- 后端单测命令：`mvn -q -pl platform-api -am test -Dtest=<类名> -Dsurefire.failIfNoSpecifiedTests=false`
- 部署顺序：先重放本批 SQL，再起新的 platform-api / k8s-server / platform-web 构建。

## Review Focus

这些是 spec 隐含、但很容易做错的地方。每条都在"Owner"任务里有一条测试钉住。

1. **租户看到别人租户的仓库** —— `warehouses()` 的 `projectCount` 或连接列表走了未过滤的 `selectAll()`。Owner: Task 2。
2. **内置 Docker Hub 开关关掉后左栏仍冒出一行** —— `warehouses()` 绕过了 `dockerHubEnabled`。Owner: Task 2。
3. **平台 Harbor 的手工新建被放行** —— 用户输入的地址带 scheme / 带路径，`isPlatformHost` 没归一化就比。Owner: Task 4。
4. **只改描述的一次保存悄悄改掉归属** —— `update` 的"缺省 = 保持不变"约定被新加的归属逻辑破坏。Owner: Task 4。
5. **新增端点漏权限行** —— 重放 SQL 与部署代码不同批，启动期直接拒启。Owner: Task 2 / Task 3。

---

## 文件结构

**后端（platform-api）**

| 文件 | 职责 |
|---|---|
| `registry/HarborInstallLocator.java`（新） | 「Harbor 装在哪些集群、地址是什么」的唯一真相源 |
| `models/RegistryWarehouseView.java`（新） | 仓库清单的一行（只读 DTO） |
| `models/CredentialListRequest.java`（新） | `/credential/list` 的可选过滤入参 |
| `services/RegistryService.java`（改） | 加 `warehouses(hatTenantId)`；`Source` 带上 `verifyError` |
| `services/CredentialService.java`（改） | 列表过滤、写侧归属与内部 Harbor 拒绝、`CredentialView.host` |
| `services/HarborRegistryService.java`（改） | `harborAccess` 改为委托 Locator，删掉自己的 `Access` 与快照解析 |
| `controllers/RegistryController.java`（改） | 新增 `POST /registry/warehouses` |
| `controllers/CredentialController.java`（改） | 列表收 body 与租户帽；写操作传租户帽 |
| `models/CredentialView.java`（改） | 加 `host` 派生字段 |

**数据库**

| 文件 | 内容 |
|---|---|
| `V2026_10_29_2__registry_warehouses.sql`（新） | `/registry/warehouses` 的 1 条 API 行 + 3 条角色绑定 |
| `V2026_10_29_3__credential_tenant_open.sql`（新） | 5 条 API 行 + 8 条绑定 + 1 条 Page 行 + 1 条 UPDATE |

**前端（platform-web）**

| 文件 | 职责 |
|---|---|
| `views/GitCredentialView.vue`（由 CredentialView 改写） | Git 连接 CRUD |
| `views/RegistryView.vue`（重写） | 仓库清单（左）+ 连接/项目/浏览（右） |
| `views/CredentialView.vue`（删除） | 由 `GitCredentialView` 取代 |
| `components/cluster/HarborProjectsDialog.vue`（删除） | 内容并入镜像仓库页 |
| `components/cluster/ClusterComponentsPanel.vue`（改） | 去掉弹窗入口 |
| `layouts/MainLayout.vue`（改） | 新增「持续集成」分组 |
| `router/index.ts`（改） | 三条路由改路径/分组 |
| `pageCodes.ts`（改） | 加 `credentialTenant` |
| `api/index.ts`（改） | 加 `warehouses()`；`list` 加可选 type |
| `types.ts`（改） | 加 `RegistryWarehouse`、`CredentialView.host` |

---

## Task 1: Harbor 安装位置收成一个真相源

**Files:**
- Create: `platform-api/src/main/java/com/coding/platformapi/registry/HarborInstallLocator.java`
- Modify: `platform-api/src/main/java/com/coding/platformapi/services/HarborRegistryService.java:77-120`（删掉 `Access` 与快照解析，改为委托）
- Test: `platform-api/src/test/java/com/coding/platformapi/registry/HarborInstallLocatorTest.java`

**Interfaces:**
- Consumes: 无（本任务是链条起点）
- Produces:
  - `HarborInstallLocator.Access(String host, boolean https)`，方法 `baseUrl()`
  - `HarborInstallLocator.InstalledHarbor(String clusterId, String clusterName, Access access, String exposeMode)`
  - `List<InstalledHarbor> all()`
  - `Access access(String clusterId)`（没装 → null）
  - `boolean isPlatformHost(String endpoint)`
  - `static String hostOf(String endpoint)`
  - `HarborRegistryService.harborHost(String)` / `harborAccess(String)` **签名不变**，内部改为委托

**为什么要单独一个类：** `CredentialService` 要拒掉「地址指向平台 Harbor」的手工新建，而 `HarborRegistryService` 依赖 `CredentialService`。把定位逻辑放进 `HarborRegistryService` 会造出 Spring 构造器循环依赖。

- [ ] **Step 1: 写失败的测试**

`platform-api/src/test/java/com/coding/platformapi/registry/HarborInstallLocatorTest.java`

```java
package com.coding.platformapi.registry;

import com.coding.data.mapper.system.PlatformComponentOperationMapper;
import com.coding.data.models.k8s.K8sCluster;
import com.coding.data.models.system.PlatformComponentOperation;
import com.coding.platformapi.services.K8sProvisioningService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 「Harbor 装在哪些集群、地址是什么」只有这一个真相源。 */
class HarborInstallLocatorTest {

    PlatformComponentOperationMapper operations = mock(PlatformComponentOperationMapper.class);
    K8sProvisioningService provisioning = mock(K8sProvisioningService.class);
    HarborInstallLocator locator = new HarborInstallLocator(operations, provisioning);

    private static K8sCluster cluster(String id, String name) {
        K8sCluster c = new K8sCluster();
        c.setClusterId(id);
        c.setClusterName(name);
        return c;
    }

    private static PlatformComponentOperation op(String snapshot) {
        PlatformComponentOperation o = new PlatformComponentOperation();
        o.setValuesSnapshot(snapshot);
        return o;
    }

    @Test
    void listsOnlyClustersWithASuccessfulHarborInstall() {
        when(provisioning.enabledClusters())
                .thenReturn(List.of(cluster("c1", "主集群"), cluster("c2", "业务集群")));
        when(operations.selectLatestSuccess("c1", "harbor"))
                .thenReturn(op("{\"__platform\":{\"harbor\":"
                        + "{\"host\":\"harbor.ackcp.cloud\",\"https\":true,\"exposeMode\":\"route\"}}}"));
        when(operations.selectLatestSuccess("c2", "harbor")).thenReturn(null);

        List<HarborInstallLocator.InstalledHarbor> all = locator.all();

        assertThat(all).hasSize(1);
        assertThat(all.get(0).clusterId()).isEqualTo("c1");
        assertThat(all.get(0).clusterName()).isEqualTo("主集群");
        assertThat(all.get(0).access().host()).isEqualTo("harbor.ackcp.cloud");
        assertThat(all.get(0).access().https()).isTrue();
        assertThat(all.get(0).access().baseUrl()).isEqualTo("https://harbor.ackcp.cloud");
        assertThat(all.get(0).exposeMode()).isEqualTo("route");
    }

    @Test
    void fallsBackToHostWhenExternalHostIsMissing() {
        when(provisioning.enabledClusters()).thenReturn(List.of(cluster("c1", "主集群")));
        when(operations.selectLatestSuccess("c1", "harbor"))
                .thenReturn(op("{\"__platform\":{\"harbor\":{\"host\":\"192.168.31.80:30002\"}}}"));

        assertThat(locator.all().get(0).access().host()).isEqualTo("192.168.31.80:30002");
        assertThat(locator.all().get(0).access().https()).isFalse();
    }

    @Test
    void unparsableSnapshotIsTreatedAsNotInstalled() {
        when(provisioning.enabledClusters()).thenReturn(List.of(cluster("c1", "主集群")));
        when(operations.selectLatestSuccess("c1", "harbor")).thenReturn(op("not json"));

        assertThat(locator.all()).isEmpty();
        assertThat(locator.access("c1")).isNull();
    }

    @Test
    void isPlatformHostIgnoresSchemePathAndCase() {
        when(provisioning.enabledClusters()).thenReturn(List.of(cluster("c1", "主集群")));
        when(operations.selectLatestSuccess("c1", "harbor"))
                .thenReturn(op("{\"__platform\":{\"harbor\":{\"host\":\"harbor.ackcp.cloud\"}}}"));

        assertThat(locator.isPlatformHost("https://harbor.ackcp.cloud/v2/")).isTrue();
        assertThat(locator.isPlatformHost("HARBOR.ACKCP.CLOUD")).isTrue();
        assertThat(locator.isPlatformHost("harbor.other.corp")).isFalse();
        assertThat(locator.isPlatformHost(null)).isFalse();
    }

    @Test
    void hostOfStripsSchemeAndPath() {
        assertThat(HarborInstallLocator.hostOf("https://harbor.x:8443/a/b")).isEqualTo("harbor.x:8443");
        assertThat(HarborInstallLocator.hostOf("harbor.x")).isEqualTo("harbor.x");
        assertThat(HarborInstallLocator.hostOf(null)).isEmpty();
    }
}
```

- [ ] **Step 2: 跑测试，确认红**

```bash
mvn -q -pl platform-api -am test -Dtest=HarborInstallLocatorTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: 编译失败 —— `HarborInstallLocator` 不存在。

- [ ] **Step 3: 写实现**

`platform-api/src/main/java/com/coding/platformapi/registry/HarborInstallLocator.java`

```java
package com.coding.platformapi.registry;

import com.coding.data.mapper.system.PlatformComponentOperationMapper;
import com.coding.data.models.k8s.K8sCluster;
import com.coding.data.models.system.PlatformComponentOperation;
import com.coding.platformapi.services.K8sProvisioningService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 「Harbor 装在哪些集群、访问地址是什么」的**唯一真相源**。
 *
 * <p>抽出来是因为它有两个消费方，而其中一个会把另一个拖进循环依赖：
 * {@code CredentialService} 要拒掉「地址指向平台 Harbor」的手工新建（spec §6.4.2 规则 2），
 * 而 {@code HarborRegistryService} 依赖 {@code CredentialService}。
 * 放在本类里，两边都只往下指。
 *
 * <p>判据是**组件安装的成功记录**（{@code values_snapshot.__platform.harbor}），不是活探测 ——
 * 这里只回答"平台认为它装在哪"，Harbor 此刻健不健康是组件页的事。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HarborInstallLocator {

    /** 组件 code（与 {@code ClusterComponentCatalog} 一致） */
    static final String COMPONENT = "harbor";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final PlatformComponentOperationMapper operations;
    private final K8sProvisioningService provisioning;

    /** 访问方式：地址（可能带端口）+ 是否 HTTPS */
    public record Access(String host, boolean https) {
        /** 带 scheme 的基址，例如 {@code https://harbor.ackcp.cloud} */
        public String baseUrl() {
            return (https ? "https://" : "http://") + host;
        }
    }

    /** 某个集群上装着的 Harbor */
    public record InstalledHarbor(String clusterId, String clusterName, Access access,
                                  String exposeMode) {
    }

    public List<InstalledHarbor> all() {
        List<InstalledHarbor> out = new ArrayList<>();
        for (K8sCluster c : provisioning.enabledClusters()) {
            PlatformComponentOperation op = operations.selectLatestSuccess(c.getClusterId(), COMPONENT);
            if (op == null) {
                continue;
            }
            Map<?, ?> harbor = harborBlock(op.getValuesSnapshot());
            Access access = accessOf(harbor);
            if (access == null) {
                continue;
            }
            out.add(new InstalledHarbor(c.getClusterId(), c.getClusterName(), access,
                    harbor.get("exposeMode") instanceof String s ? s : null));
        }
        return out;
    }

    /** 该集群装了 Harbor 就返回访问方式，否则 null */
    public Access access(String clusterId) {
        PlatformComponentOperation op = operations.selectLatestSuccess(clusterId, COMPONENT);
        return op == null ? null : accessOf(harborBlock(op.getValuesSnapshot()));
    }

    /**
     * 这个地址是不是**平台自己装的那台** Harbor。
     * <p>比较前两边都归一成 {@code host[:port]}（去 scheme、去路径、忽略大小写）——
     * 用户输入的形态不受控，直接比字符串必然漏。
     */
    public boolean isPlatformHost(String endpoint) {
        String h = hostOf(endpoint);
        if (h.isEmpty()) {
            return false;
        }
        return all().stream().anyMatch(i -> h.equalsIgnoreCase(i.access().host()));
    }

    /** endpoint → {@code host[:port]}；容错到「喂什么都返回一个字符串」 */
    public static String hostOf(String endpoint) {
        String e = endpoint == null ? "" : endpoint.trim();
        int scheme = e.indexOf("://");
        if (scheme >= 0) {
            e = e.substring(scheme + 3);
        }
        int slash = e.indexOf('/');
        return (slash >= 0 ? e.substring(0, slash) : e).trim();
    }

    private static Map<?, ?> harborBlock(String snapshot) {
        if (snapshot == null) {
            return null;
        }
        try {
            Map<?, ?> root = JSON.readValue(snapshot, Map.class);
            if (!(root.get("__platform") instanceof Map<?, ?> platform)
                    || !(platform.get("harbor") instanceof Map<?, ?> harbor)) {
                return null;
            }
            return harbor;
        } catch (Exception e) {
            log.warn("Harbor 安装快照无法解析：{}", e.getMessage());
            return null;
        }
    }

    private static Access accessOf(Map<?, ?> harbor) {
        if (harbor == null) {
            return null;
        }
        // externalHost = 拼好端口的 authority（nodePort / loadBalancer 必须带端口）
        Object host = harbor.get("externalHost");
        if (!(host instanceof String s) || s.isBlank()) {
            host = harbor.get("host");
        }
        if (!(host instanceof String authority) || authority.isBlank()) {
            return null;
        }
        return new Access(authority, Boolean.TRUE.equals(harbor.get("https")));
    }
}
```

- [ ] **Step 4: 跑测试，确认绿**

```bash
mvn -q -pl platform-api -am test -Dtest=HarborInstallLocatorTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: `Tests run: 5, Failures: 0, Errors: 0`

- [ ] **Step 5: 让 `HarborRegistryService` 委托给它**

在 `HarborRegistryService` 里：

1. 删掉嵌套的 `public record Access(...)`（含 `baseUrl()`）；
2. 删掉 `harborAccess(String)` 的整段实现与 `Access harbor` 相关 import 中不再需要的；
3. 注入 `private final HarborInstallLocator harborInstalls;`；
4. 改成：

```java
    /** 该集群上次成功安装 Harbor 的访问地址（**不带 scheme**，镜像路径要用它）；没装/无记录 → null */
    public String harborHost(String clusterId) {
        HarborInstallLocator.Access access = harborInstalls.access(clusterId);
        return access == null ? null : access.host();
    }

    /** 该集群 Harbor 的访问方式（地址 + scheme）；没装/无记录 → null。 */
    public HarborInstallLocator.Access harborAccess(String clusterId) {
        return harborInstalls.access(clusterId);
    }
```

5. 类内所有 `Access` 引用改成 `HarborInstallLocator.Access`（`ensureAll` / `requireAccess` 里各一处）。

- [ ] **Step 6: 跑全模块测试，确认没打坏别处**

```bash
mvn -q -pl platform-api -am test
```

Expected: 全绿（原 `HarborRegistryServiceTest` 若因 `Access` 类型变化需同步改类型名，改完再跑）。

- [ ] **Step 7: 提交**

```bash
git add platform-api/src/main/java/com/coding/platformapi/registry/HarborInstallLocator.java \
        platform-api/src/main/java/com/coding/platformapi/services/HarborRegistryService.java \
        platform-api/src/test/java/com/coding/platformapi/registry/HarborInstallLocatorTest.java
git commit -m "refactor(registry): Harbor 装在哪收成 HarborInstallLocator 一个真相源"
```

---

## Task 2: 仓库清单聚合端点

**Files:**
- Create: `platform-api/src/main/java/com/coding/platformapi/models/RegistryWarehouseView.java`
- Create: `platform-data/src/main/resources/db/migration/V2026_10_29_2__registry_warehouses.sql`
- Modify: `platform-api/src/main/java/com/coding/platformapi/services/RegistryService.java`（构造器 + `warehouses()` + `Source` 加 `verifyError`）
- Modify: `platform-api/src/main/java/com/coding/platformapi/controllers/RegistryController.java`
- Test: `platform-api/src/test/java/com/coding/platformapi/services/RegistryWarehouseTest.java`
- Test: `platform-api/src/test/java/com/coding/platformapi/security/RegistryWarehousePermissionMigrationTest.java`

**Interfaces:**
- Consumes: `HarborInstallLocator.all()` / `hostOf(String)`（Task 1）
- Produces: `List<RegistryWarehouseView> RegistryService.warehouses(String hatTenantId)`；
  `POST /registry/warehouses` → `ResponseData<List<RegistryWarehouseView>>`（无请求体）

- [ ] **Step 1: 写失败的测试**

`platform-api/src/test/java/com/coding/platformapi/services/RegistryWarehouseTest.java`

```java
package com.coding.platformapi.services;

import com.coding.data.mapper.system.PlatformCredentialMapper;
import com.coding.data.mapper.system.PlatformRegistryProjectMapper;
import com.coding.data.models.system.PlatformCredential;
import com.coding.data.models.system.PlatformRegistryProject;
import com.coding.platformapi.cache.RedisJsonCache;
import com.coding.platformapi.credential.CredentialCipher;
import com.coding.platformapi.models.RegistryWarehouseView;
import com.coding.platformapi.registry.HarborInstallLocator;
import com.coding.platformapi.registry.RegistryDialectRegistry;
import com.coding.platformapi.registry.RegistrySuggestExecutor;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 仓库清单 = 安装记录 ∪ 凭据行 ∪ 内置 Docker Hub，按 host 去重。 */
class RegistryWarehouseTest {

    PlatformCredentialMapper mapper = mock(PlatformCredentialMapper.class);
    PlatformRegistryProjectMapper projectMapper = mock(PlatformRegistryProjectMapper.class);
    CredentialCipher cipher = new CredentialCipher("test-only-master-key-0123456789abcdef");
    HarborInstallLocator locator = mock(HarborInstallLocator.class);

    private RegistryService service(boolean dockerHubEnabled) {
        return new RegistryService(mapper, projectMapper, cipher,
                new RegistryDialectRegistry(List.of()), mock(RedisJsonCache.class),
                new RegistrySuggestExecutor(), dockerHubEnabled, locator);
    }

    private static PlatformCredential cred(String id, String name, String type, String endpoint,
                                           String tenantId, String source) {
        PlatformCredential c = new PlatformCredential();
        c.setId(id);
        c.setName(name);
        c.setType(type);
        c.setEndpoint(endpoint);
        c.setAuthType("BASIC");
        c.setEnabled(1);
        c.setUsableByPipelines(1);
        c.setTenantId(tenantId);
        c.setSource(source);
        return c;
    }

    private static HarborInstallLocator.InstalledHarbor installed(String clusterId, String name,
                                                                  String host) {
        return new HarborInstallLocator.InstalledHarbor(clusterId, name,
                new HarborInstallLocator.Access(host, false), "nodePort");
    }

    @Test
    void groupsCredentialRowsByHostAndTagsThemAsCredentialSourced() {
        when(locator.all()).thenReturn(List.of());
        when(projectMapper.selectAll()).thenReturn(List.of());
        when(mapper.selectAll()).thenReturn(List.of(
                cred("c1", "corp-harbor", "HARBOR", "http://harbor.corp.x", null, "MANUAL"),
                cred("c2", "corp-harbor-2", "HARBOR", "harbor.corp.x:8443", null, "MANUAL")));

        List<RegistryWarehouseView> all = service(true).warehouses(null);

        assertThat(all).extracting(RegistryWarehouseView::host)
                .containsExactlyInAnyOrder("harbor.corp.x", "harbor.corp.x:8443");
        assertThat(all).allMatch(w -> w.origin().equals("CREDENTIAL"));
        assertThat(all).allMatch(w -> w.kind().equals("HARBOR"));
    }

    @Test
    void mergesTheInstalledHarborIntoAnInstalledWarehouse() {
        when(locator.all()).thenReturn(List.of(installed("cl1", "主集群", "harbor.ackcp.cloud")));
        when(projectMapper.selectAll()).thenReturn(List.of());
        when(mapper.selectAll()).thenReturn(List.of(
                cred("c1", "t-team-a", "HARBOR", "https://harbor.ackcp.cloud",
                        "t1", "HARBOR")));

        List<RegistryWarehouseView> all = service(true).warehouses(null);

        assertThat(all).hasSize(1);
        RegistryWarehouseView w = all.get(0);
        assertThat(w.origin()).isEqualTo("INSTALLED");
        assertThat(w.clusterId()).isEqualTo("cl1");
        assertThat(w.exposeMode()).isEqualTo("nodePort");
        assertThat(w.connectionCount()).isEqualTo(1);
    }

    @Test
    void adminAlsoSeesInstalledHarborsThatHaveNoCredentialsYet() {
        when(locator.all()).thenReturn(List.of(installed("cl1", "主集群", "harbor.ackcp.cloud")));
        when(projectMapper.selectAll()).thenReturn(List.of());
        when(mapper.selectAll()).thenReturn(List.of());

        assertThat(service(true).warehouses(null))
                .extracting(RegistryWarehouseView::origin).containsExactly("INSTALLED");
    }

    @Test
    void tenantDoesNotSeeAnInstalledHarborWithNoVisibleCredential() {
        when(locator.all()).thenReturn(List.of(installed("cl1", "主集群", "harbor.ackcp.cloud")));
        when(projectMapper.selectAll()).thenReturn(List.of());
        // 只有平台共享 robot（tenant_id = null + source = HARBOR）—— 对租户不可见
        when(mapper.selectAll()).thenReturn(List.of(
                cred("c1", "platform", "HARBOR", "harbor.ackcp.cloud", null, "HARBOR")));

        assertThat(service(true).warehouses("t1")).isEmpty();
    }

    @Test
    void tenantProjectCountOnlyCountsItsOwnRows() {
        when(locator.all()).thenReturn(List.of(installed("cl1", "主集群", "harbor.ackcp.cloud")));
        when(mapper.selectAll()).thenReturn(List.of(
                cred("c1", "t-team-a", "HARBOR", "harbor.ackcp.cloud", "t1", "HARBOR")));
        PlatformRegistryProject mine = new PlatformRegistryProject();
        mine.setHost("harbor.ackcp.cloud");
        mine.setTenantId("t1");
        PlatformRegistryProject other = new PlatformRegistryProject();
        other.setHost("harbor.ackcp.cloud");
        other.setTenantId("t2");
        when(projectMapper.selectAll()).thenReturn(List.of(mine, other));

        assertThat(service(true).warehouses("t1").get(0).projectCount()).isEqualTo(1);
        assertThat(service(true).warehouses(null).get(0).projectCount()).isEqualTo(2);
    }

    @Test
    void builtinDockerHubAppearsOnlyWhenItsSwitchIsOn() {
        when(locator.all()).thenReturn(List.of());
        when(projectMapper.selectAll()).thenReturn(List.of());
        when(mapper.selectAll()).thenReturn(List.of());

        List<RegistryWarehouseView> on = service(true).warehouses(null);
        assertThat(on).hasSize(1);
        assertThat(on.get(0).id()).isEqualTo("builtin:dockerhub");
        assertThat(on.get(0).kind()).isEqualTo("DOCKER");
        assertThat(on.get(0).origin()).isEqualTo("BUILTIN");
        assertThat(on.get(0).host()).isEqualTo("docker.io");

        assertThat(service(false).warehouses(null)).isEmpty();
    }

    @Test
    void aCredentialWithAVerifyErrorMarksTheWarehouseAsError() {
        when(locator.all()).thenReturn(List.of());
        when(projectMapper.selectAll()).thenReturn(List.of());
        PlatformCredential bad = cred("c1", "corp", "HARBOR", "harbor.corp.x", null, "MANUAL");
        bad.setLastVerifyError("401 Unauthorized");
        when(mapper.selectAll()).thenReturn(List.of(bad));

        RegistryWarehouseView w = service(true).warehouses(null).get(0);
        assertThat(w.health()).isEqualTo("ERROR");
        assertThat(w.message()).isEqualTo("401 Unauthorized");
    }
}
```

`platform-api/src/test/java/com/coding/platformapi/security/RegistryWarehousePermissionMigrationTest.java`

```java
package com.coding.platformapi.security;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** 新端点没有权限行 = 启动期 fail-closed（PermissionCrossCheckRunner）。 */
class RegistryWarehousePermissionMigrationTest {

    private static final Path SQL = Path.of(
            "../platform-data/src/main/resources/db/migration/V2026_10_29_2__registry_warehouses.sql");

    @Test
    void warehouseEndpointHasAPermissionRowBoundToAllBuiltinRoles() throws Exception {
        String sql = Files.readString(SQL);

        assertThat(sql).contains("'/registry/warehouses'")
                .contains("'POST'")
                .contains("'tenant:registry:catalog'");
        assertThat(sql).contains("'builtin_role_admin'")
                .contains("'builtin_role_tenant_admin'")
                .contains("'builtin_role_tenant_member'");
    }
}
```

- [ ] **Step 2: 跑测试，确认红**

```bash
mvn -q -pl platform-api -am test -Dtest='RegistryWarehouseTest+RegistryWarehousePermissionMigrationTest' -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: 编译失败 —— `RegistryWarehouseView` 不存在，`RegistryService` 构造器参数个数对不上。

- [ ] **Step 3: 写 DTO**

`platform-api/src/main/java/com/coding/platformapi/models/RegistryWarehouseView.java`

```java
package com.coding.platformapi.models;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 镜像仓库页左栏的一行：一个 {@code host[:port]}（内置档用 {@code builtin:dockerhub} 当 id）。
 *
 * <p>⚠️ 「仓库」不是一张表 —— 它是 Harbor 安装记录 ∪ 凭据行 ∪ 内置档的聚合视角
 * （spec §5）。所以本类没有对应的 mapper。
 */
@Schema(description = "镜像仓库（按 host 聚合的只读视图）")
public record RegistryWarehouseView(
        @Schema(description = "内置档为 builtin:dockerhub，其余为 host[:port]") String id,
        String host,
        @Schema(description = "HARBOR / DOCKER") String kind,
        @Schema(description = "INSTALLED（组件安装记录）/ CREDENTIAL（凭据行）/ BUILTIN（内置档）")
        String origin,
        String displayName,
        @Schema(description = "仅 INSTALLED 有值") String clusterId,
        @Schema(description = "仅 INSTALLED 有值") String exposeMode,
        int connectionCount,
        int projectCount,
        @Schema(description = "OK / ERROR") String health,
        String message) {
}
```

- [ ] **Step 4: 写迁移**

`platform-data/src/main/resources/db/migration/V2026_10_29_2__registry_warehouses.sql`

```sql
-- ============================================================================================
-- 镜像仓库页的仓库清单（按 host 聚合的只读视图）。
-- 设计：docs/superpowers/specs/2026-10-29-credential-registry-ia-design.md §6.1
--
-- ⚠️ 表驱动授权：新端点没有权限行时 PermissionCrossCheckRunner 会在启动期 fail-closed。
-- ⚠️ 新权限行必须显式绑角色（不按 code 继承）；三条内置角色都要绑。
-- 注意：MySQL 注释 `--` 后面必须跟空白字符。
-- ============================================================================================

INSERT INTO platform_permission (id, domain, resource, action, code, description) VALUES
 ('perm_registry_warehouses', 'API','/registry/warehouses','POST','tenant:registry:catalog',
  '镜像仓库清单（安装记录 ∪ 凭据行 ∪ 内置档，按 host 聚合）')
ON DUPLICATE KEY UPDATE
  domain=VALUES(domain), resource=VALUES(resource), action=VALUES(action),
  code=VALUES(code), description=VALUES(description);

INSERT INTO platform_role_permission (id, role_id, permission_id) VALUES
 ('rp_admin_reg_warehouses',   'builtin_role_admin','perm_registry_warehouses'),
 ('rp_tadmin_reg_warehouses',  'builtin_role_tenant_admin','perm_registry_warehouses'),
 ('rp_tmember_reg_warehouses', 'builtin_role_tenant_member','perm_registry_warehouses')
ON DUPLICATE KEY UPDATE permission_id=VALUES(permission_id);
```

- [ ] **Step 5: 给 `Source` 加 `verifyError` 并写 `warehouses()`**

`RegistryService` 里：

1. 构造器末尾加一个参数 `HarborInstallLocator harborInstalls`（字段 `private final`）；
2. 补 `import java.util.Objects;`（`toWarehouse` 里要用）；
3. `Source` 记录加第 7 个分量 `String verifyError`：
   - `builtin(BuiltinRegistry b)` → `new Source(b.id(), b.displayName(), b.type(), b.endpoint(), null, null, null)`
   - `from(PlatformCredential c, String project)` → 末尾加 `c.getLastVerifyError()`
4. 新增：

```java
    /**
     * 仓库清单（spec §3.3 / §6.1）：一个 {@code host[:port]} 一行。
     *
     * <p>起点是 {@link #allSources}（已经过 {@code visibleTo} 过滤、已含内置档），
     * 所以租户天然看不到别人的仓库与平台共享 robot。管理员**额外**看到
     * 「装了 Harbor 但还没同步出任何凭据」的集群 —— 否则管理员没有入口去触发同步。
     */
    public List<RegistryWarehouseView> warehouses(String hatTenantId) {
        Map<String, List<Source>> byHost = new LinkedHashMap<>();
        for (Source s : allSources(hatTenantId)) {
            String key = BuiltinRegistry.DOCKER_HUB.id().equals(s.id())
                    ? BuiltinRegistry.DOCKER_HUB.id()
                    : HarborInstallLocator.hostOf(s.endpoint());
            byHost.computeIfAbsent(key, k -> new ArrayList<>()).add(s);
        }

        List<HarborInstallLocator.InstalledHarbor> installed = harborInstalls.all();
        Map<String, HarborInstallLocator.InstalledHarbor> installedByHost = new LinkedHashMap<>();
        for (HarborInstallLocator.InstalledHarbor i : installed) {
            installedByHost.put(i.access().host(), i);
        }
        if (hatTenantId == null) {
            installedByHost.keySet().forEach(h -> byHost.computeIfAbsent(h, k -> new ArrayList<>()));
        }

        // 项目台账：租户只数自己的，管理员数全部（spec §6.4.1）
        List<PlatformRegistryProject> projects = projectMapper.selectAll().stream()
                .filter(p -> hatTenantId == null || hatTenantId.equals(p.getTenantId()))
                .toList();

        return byHost.entrySet().stream()
                .map(e -> toWarehouse(e.getKey(), e.getValue(), installedByHost, projects))
                .toList();
    }

    private static RegistryWarehouseView toWarehouse(
            String key, List<Source> sources,
            Map<String, HarborInstallLocator.InstalledHarbor> installedByHost,
            List<PlatformRegistryProject> projects) {
        boolean builtin = BuiltinRegistry.DOCKER_HUB.id().equals(key);
        HarborInstallLocator.InstalledHarbor inst = builtin ? null : installedByHost.get(key);
        Source first = sources.isEmpty() ? null : sources.get(0);

        String host = builtin ? BuiltinRegistry.DOCKER_HUB.endpoint() : key;
        String kind = builtin || (first != null && "DOCKERHUB".equals(first.type()))
                ? "DOCKER" : "HARBOR";
        String origin = builtin ? "BUILTIN" : (inst != null ? "INSTALLED" : "CREDENTIAL");
        String displayName = builtin ? BuiltinRegistry.DOCKER_HUB.displayName()
                : inst != null ? "Harbor · " + inst.clusterName() : host;
        int projectCount = (int) projects.stream()
                .filter(p -> key.equalsIgnoreCase(HarborInstallLocator.hostOf(p.getHost())))
                .count();
        // connectionCount：没有凭据行的不计（内置档恒 0）
        int connectionCount = sources.size();
        String message = sources.stream()
                .map(Source::verifyError).filter(Objects::nonNull).findFirst().orElse(null);
        return new RegistryWarehouseView(key, host, kind, origin, displayName,
                inst == null ? null : inst.clusterId(),
                inst == null ? null : inst.exposeMode(),
                connectionCount, projectCount,
                message == null ? "OK" : "ERROR", message);
    }
```

- [ ] **Step 6: 加控制器端点**

`RegistryController` 里加：

```java
    @Operation(summary = "仓库清单",
            description = "按 host 聚合的只读视图：安装记录 ∪ 凭据行 ∪ 内置 Docker Hub。"
                    + "租户只看得到自己可见的那些（与 /registry/connections 同一套可见性规则）")
    @PostMapping("/warehouses")
    public ResponseData<List<RegistryWarehouseView>> warehouses() {
        return new ResponseData<>(registryService.warehouses(auth.hatTenantId()));
    }
```

并在类头 javadoc 的权限行说明里补一句「四个端点共用 `tenant:registry:catalog`」。

- [ ] **Step 7: 修 `RegistryServiceTest` 的构造**

`RegistryServiceTest` 里 `new RegistryService(mapper, projectMapper, cipher, d, cache, new RegistrySuggestExecutor(), builtin)` 末尾加一个 `mock(HarborInstallLocator.class)`；`Source` 若在测试里被直接构造，补齐新分量。

- [ ] **Step 8: 跑测试，确认绿**

```bash
mvn -q -pl platform-api -am test -Dtest='RegistryWarehouseTest+RegistryWarehousePermissionMigrationTest+RegistryServiceTest+RegistryControllerTest' -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: `Failures: 0, Errors: 0`

- [ ] **Step 9: 提交**

```bash
git add platform-api/src/main/java/com/coding/platformapi/models/RegistryWarehouseView.java \
        platform-api/src/main/java/com/coding/platformapi/services/RegistryService.java \
        platform-api/src/main/java/com/coding/platformapi/controllers/RegistryController.java \
        platform-data/src/main/resources/db/migration/V2026_10_29_2__registry_warehouses.sql \
        platform-api/src/test/java/com/coding/platformapi/services/RegistryWarehouseTest.java \
        platform-api/src/test/java/com/coding/platformapi/security/RegistryWarehousePermissionMigrationTest.java \
        platform-api/src/test/java/com/coding/platformapi/services/RegistryServiceTest.java
git commit -m "feat(registry): 仓库清单聚合端点，按 host 收拢安装记录与凭据"
```

---

## Task 3: 凭据列表按类型与租户过滤

**Files:**
- Create: `platform-api/src/main/java/com/coding/platformapi/models/CredentialListRequest.java`
- Create: `platform-data/src/main/resources/db/migration/V2026_10_29_3__credential_tenant_open.sql`
- Modify: `platform-api/src/main/java/com/coding/platformapi/services/CredentialService.java`（`list` + `toView` 加 `host`）
- Modify: `platform-api/src/main/java/com/coding/platformapi/models/CredentialView.java`（加 `host`）
- Modify: `platform-api/src/main/java/com/coding/platformapi/controllers/CredentialController.java`
- Test: `platform-api/src/test/java/com/coding/platformapi/services/CredentialListTest.java`
- Test: `platform-api/src/test/java/com/coding/platformapi/security/CredentialTenantMigrationTest.java`

**Interfaces:**
- Consumes: `HarborInstallLocator.hostOf`（Task 1）
- Produces:
  - `List<CredentialView> CredentialService.list(String type, String hatTenantId)`（原 `list()` 保留，委托 `list(null, null)`）
  - `CredentialView.host`（派生：`hostOf(endpoint)`）
  - `POST /credential/list` 接受可选 body `{"type":"GIT"}`

- [ ] **Step 1: 写失败的测试**

`platform-api/src/test/java/com/coding/platformapi/services/CredentialListTest.java`

```java
package com.coding.platformapi.services;

import com.coding.data.mapper.auth.PlatformTenantMapper;
import com.coding.data.mapper.system.PlatformCredentialMapper;
import com.coding.data.mapper.system.PlatformRegistryProjectMapper;
import com.coding.data.models.system.PlatformCredential;
import com.coding.platformapi.credential.CredentialCipher;
import com.coding.platformapi.credential.CredentialProbeClient;
import com.coding.platformapi.models.CredentialView;
import com.coding.platformapi.registry.DockerHubDialect;
import com.coding.platformapi.registry.HarborDialect;
import com.coding.platformapi.registry.RegistryDialectRegistry;
import com.coding.platformapi.registry.RegistryHttpClient;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 列表按类型过滤 + 按租户可见性过滤（spec §6.2 / §6.4.1）。 */
class CredentialListTest {

    PlatformCredentialMapper mapper = mock(PlatformCredentialMapper.class);
    PlatformTenantMapper tenantMapper = mock(PlatformTenantMapper.class);
    PlatformRegistryProjectMapper projectMapper = mock(PlatformRegistryProjectMapper.class);
    CredentialService svc = new CredentialService(mapper,
            new CredentialCipher("test-only-master-key-0123456789abcdef"),
            mock(CredentialProbeClient.class),
            new RegistryDialectRegistry(List.of(new HarborDialect(mock(RegistryHttpClient.class)),
                    new DockerHubDialect(mock(RegistryHttpClient.class)))),
            tenantMapper, projectMapper);

    private static PlatformCredential cred(String id, String type, String endpoint,
                                           String tenantId, String source) {
        PlatformCredential c = new PlatformCredential();
        c.setId(id);
        c.setName(id);
        c.setType(type);
        c.setEndpoint(endpoint);
        c.setAuthType("BASIC");
        c.setEnabled(1);
        c.setUsableByPipelines(1);
        c.setTenantId(tenantId);
        c.setSource(source);
        return c;
    }

    @Test
    void filtersByType() {
        when(tenantMapper.listAll()).thenReturn(List.of());
        when(projectMapper.selectAll()).thenReturn(List.of());
        when(mapper.selectAll()).thenReturn(List.of(
                cred("g1", "GIT", "gitlab.corp", null, "MANUAL"),
                cred("h1", "HARBOR", "harbor.corp", null, "MANUAL")));

        List<CredentialView> git = svc.list("GIT", null);

        assertThat(git).extracting(CredentialView::getId).containsExactly("g1");
    }

    @Test
    void tenantSeesItsOwnPlusPlatformManualButNotTheSharedRobot() {
        when(tenantMapper.listAll()).thenReturn(List.of());
        when(projectMapper.selectAll()).thenReturn(List.of());
        when(mapper.selectAll()).thenReturn(List.of(
                cred("mine", "HARBOR", "harbor.corp", "t1", "MANUAL"),
                cred("public", "HARBOR", "public.corp", null, "MANUAL"),
                cred("robot", "HARBOR", "harbor.ackcp.cloud", null, "HARBOR"),
                cred("other", "HARBOR", "other.corp", "t2", "MANUAL")));

        assertThat(svc.list(null, "t1")).extracting(CredentialView::getId)
                .containsExactlyInAnyOrder("mine", "public");
        assertThat(svc.list(null, null)).hasSize(4);
    }

    @Test
    void hostIsDerivedFromTheEndpoint() {
        when(tenantMapper.listAll()).thenReturn(List.of());
        when(projectMapper.selectAll()).thenReturn(List.of());
        when(mapper.selectAll()).thenReturn(List.of(
                cred("h1", "HARBOR", "https://harbor.corp:8443/v2", null, "MANUAL")));

        assertThat(svc.list(null, null).get(0).getHost()).isEqualTo("harbor.corp:8443");
    }
}
```

`platform-api/src/test/java/com/coding/platformapi/security/CredentialTenantMigrationTest.java`

```java
package com.coding.platformapi.security;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** 凭据对租户开放（2026-10-29）的迁移护栏。 */
class CredentialTenantMigrationTest {

    private static final Path SQL = Path.of(
            "../platform-data/src/main/resources/db/migration/V2026_10_29_3__credential_tenant_open.sql");

    @Test
    void fiveCredentialEndpointsGetTenantCodes() throws Exception {
        String sql = Files.readString(SQL);

        for (String path : new String[]{"/credential/list", "/credential/test",
                "/credential/create", "/credential/update", "/credential/delete"}) {
            assertThat(sql).as("端点 %s 必须有租户码的权限行", path).contains("'" + path + "'");
        }
        assertThat(sql).contains("'tenant:credential:read'").contains("'tenant:credential:write'");
    }

    @Test
    void writeCodeBindsOnlyAdminAndTenantAdmin() throws Exception {
        String sql = Files.readString(SQL);

        // read 三条角色都有；write 只有 admin 与 tenant_admin（tenant_member 只读）
        assertThat(sql).contains("'builtin_role_tenant_member','perm_tenant_credential_list'");
        assertThat(sql).doesNotContain("'builtin_role_tenant_member','perm_tenant_credential_create'");
    }

    @Test
    void gitCredentialsPageMovesToItsOwnRoute() throws Exception {
        String sql = Files.readString(SQL);

        assertThat(sql).contains("/git-credentials").contains("'tenant:page:credential'");
    }
}
```

- [ ] **Step 2: 跑测试，确认红**

```bash
mvn -q -pl platform-api -am test -Dtest='CredentialListTest+CredentialTenantMigrationTest' -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: 编译失败 —— `list(String,String)` 与 `getHost()` 不存在；迁移文件不存在。

- [ ] **Step 3: 写迁移**

`platform-data/src/main/resources/db/migration/V2026_10_29_3__credential_tenant_open.sql`

```sql
-- ============================================================================================
-- 凭据第一次对租户开放（2026-10-29）。
-- 设计：docs/superpowers/specs/2026-10-29-credential-registry-ia-design.md §6.4
--
-- 范围：Git 凭据 + 外部 Harbor / Docker Hub 连接，租户可自建自管；
--       平台 Harbor 内部（source=HARBOR）的连接仍只对管理员。
--
-- ⚠️ 租户角色不能持有 platform: 码（角色不变量）→ 必须新开 tenant: 这一族，
--    与现有 platform:credential:* 同端点多码并存，授权 ANY-of。
-- ⚠️ 新权限行必须显式绑角色（不按 code 继承）。read 绑三条内置角色，write 只绑 admin + tenant_admin。
-- 注意：MySQL 注释 `--` 后面必须跟空白字符。
-- ============================================================================================

INSERT INTO platform_permission (id, domain, resource, action, code, description) VALUES
 ('perm_tenant_credential_list',   'API','/credential/list',   'POST','tenant:credential:read',
  '连接列表（按租户可见性收窄）'),
 ('perm_tenant_credential_test',   'API','/credential/test',   'POST','tenant:credential:read',
  '测试连接'),
 ('perm_tenant_credential_create', 'API','/credential/create', 'POST','tenant:credential:write',
  '新建连接（归属强制为调用者租户）'),
 ('perm_tenant_credential_update', 'API','/credential/update', 'POST','tenant:credential:write',
  '更新连接（只能改自己租户的）'),
 ('perm_tenant_credential_delete', 'API','/credential/delete', 'POST','tenant:credential:write',
  '删除连接（只能删自己租户的）')
ON DUPLICATE KEY UPDATE
  domain=VALUES(domain), resource=VALUES(resource), action=VALUES(action),
  code=VALUES(code), description=VALUES(description);

INSERT INTO platform_role_permission (id, role_id, permission_id) VALUES
 ('rp_admin_tcred_list',       'builtin_role_admin','perm_tenant_credential_list'),
 ('rp_admin_tcred_test',       'builtin_role_admin','perm_tenant_credential_test'),
 ('rp_admin_tcred_create',     'builtin_role_admin','perm_tenant_credential_create'),
 ('rp_admin_tcred_update',     'builtin_role_admin','perm_tenant_credential_update'),
 ('rp_admin_tcred_delete',     'builtin_role_admin','perm_tenant_credential_delete'),
 ('rp_tadmin_tcred_list',      'builtin_role_tenant_admin','perm_tenant_credential_list'),
 ('rp_tadmin_tcred_test',      'builtin_role_tenant_admin','perm_tenant_credential_test'),
 ('rp_tadmin_tcred_create',    'builtin_role_tenant_admin','perm_tenant_credential_create'),
 ('rp_tadmin_tcred_update',    'builtin_role_tenant_admin','perm_tenant_credential_update'),
 ('rp_tadmin_tcred_delete',    'builtin_role_tenant_admin','perm_tenant_credential_delete'),
 ('rp_tmember_tcred_list',     'builtin_role_tenant_member','perm_tenant_credential_list'),
 ('rp_tmember_tcred_test',     'builtin_role_tenant_member','perm_tenant_credential_test')
ON DUPLICATE KEY UPDATE permission_id=VALUES(permission_id);

-- Git 凭据页：路径从 /credentials 改成 /git-credentials。
-- platform:page:credential 保留（管理员兜底，且可能有自定义角色持有它），
-- 另加一个 tenant: 码给租户 —— 同一页两个 Page 码是过渡态，见 spec §6.4。
UPDATE platform_permission SET resource = '/git-credentials' WHERE id = 'perm_page_credential';

INSERT INTO platform_permission (id, domain, resource, action, code, description) VALUES
 ('perm_page_credential_tenant', 'Page','/git-credentials','view','tenant:page:credential',
  'Git 凭据管理页（租户自管）')
ON DUPLICATE KEY UPDATE
  domain=VALUES(domain), resource=VALUES(resource), action=VALUES(action),
  code=VALUES(code), description=VALUES(description);

INSERT INTO platform_role_permission (id, role_id, permission_id) VALUES
 ('rp_admin_tcred_page',   'builtin_role_admin','perm_page_credential_tenant'),
 ('rp_tadmin_tcred_page',  'builtin_role_tenant_admin','perm_page_credential_tenant'),
 ('rp_tmember_tcred_page', 'builtin_role_tenant_member','perm_page_credential_tenant')
ON DUPLICATE KEY UPDATE permission_id=VALUES(permission_id);
```

- [ ] **Step 4: 加 `host` 字段**

`CredentialView` 里，`endpoint` 之后加：

```java
    /**
     * endpoint 归一后的 {@code host[:port]}（去 scheme、去路径）。
     * <p>派生字段、不含任何凭据内容 —— 镜像仓库页靠它把连接挂到左栏的仓库行上，
     * 省得前端再实现一遍归一规则（那就成了第二个真相源）。
     */
    private String host;
```

- [ ] **Step 5: 改 `CredentialService`**

（先补 `import com.coding.platformapi.registry.HarborInstallLocator;` —— 只用到它的静态 `hostOf`，本步不动构造器。）

`toView` 里 `v.setEndpoint(...)` 之后加一行：

```java
        v.setHost(HarborInstallLocator.hostOf(c.getEndpoint()));
```

`list` 改成：

```java
    public List<CredentialView> list() {
        return list(null, null);
    }

    /**
     * @param type        只要这一类型；null = 全类型
     * @param hatTenantId 请求的租户帽；null = 平台管理员（全量）
     */
    public List<CredentialView> list(String type, String hatTenantId) {
        Map<String, String> names = tenantMapper.listAll().stream()
                .collect(Collectors.toMap(PlatformTenant::getId, PlatformTenant::getName,
                        (a, b) -> a));
        List<CredentialView> out = mapper.selectAll().stream()
                .filter(c -> type == null || type.equals(c.getType()))
                .filter(c -> visibleTo(c, hatTenantId))
                .map(CredentialService::toView).toList();
        for (CredentialView v : out) {
            v.setTenantName(v.getTenantId() == null ? null : names.get(v.getTenantId()));
        }
        return out;
    }
```

- [ ] **Step 6: 加请求模型与控制器**

`platform-api/src/main/java/com/coding/platformapi/models/CredentialListRequest.java`

```java
package com.coding.platformapi.models;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/** 连接列表的可选过滤。空 body / 不传 type = 全类型。 */
@Data
public class CredentialListRequest {
    @Schema(description = "GIT / HARBOR / DOCKERHUB；不传 = 全部")
    private String type;
}
```

`CredentialController`：

```java
    @Operation(summary = "凭据列表",
            description = "按 type、name 排序；**不含凭据内容**。租户只看得到自己可见的那些"
                    + "（自己的 + 平台级手工连接；平台共享 Harbor robot 不下发）")
    @PostMapping("/list")
    public ResponseData<List<CredentialView>> list(
            @RequestBody(required = false) CredentialListRequest request) {
        return new ResponseData<>(credentialService.list(
                request == null ? null : request.getType(), auth.hatTenantId()));
    }
```

并给控制器注入：

```java
    /** 租户帽：列表与写操作的归属都按调用者判（与流水线连接可见性同一套） */
    private final AuthContext auth;
```

- [ ] **Step 7: 跑测试，确认绿**

```bash
mvn -q -pl platform-api -am test -Dtest='CredentialListTest+CredentialTenantMigrationTest+CredentialServiceTest+CredentialControllerTest' -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: `Failures: 0, Errors: 0`。`CredentialServiceTest.viewDtoHasNoCredentialField()` 必须仍然绿 —— `host` 不含 `secret/password/username`。

- [ ] **Step 8: 提交**

```bash
git add platform-api/src/main/java/com/coding/platformapi/models/CredentialListRequest.java \
        platform-api/src/main/java/com/coding/platformapi/models/CredentialView.java \
        platform-api/src/main/java/com/coding/platformapi/services/CredentialService.java \
        platform-api/src/main/java/com/coding/platformapi/controllers/CredentialController.java \
        platform-data/src/main/resources/db/migration/V2026_10_29_3__credential_tenant_open.sql \
        platform-api/src/test/java/com/coding/platformapi/services/CredentialListTest.java \
        platform-api/src/test/java/com/coding/platformapi/security/CredentialTenantMigrationTest.java
git commit -m "feat(credential): 列表按类型与租户可见性过滤，凭据对租户开放的权限行"
```

---

## Task 4: 凭据写侧的归属与内部 Harbor 拒绝

**Files:**
- Modify: `platform-api/src/main/java/com/coding/platformapi/services/CredentialService.java`
- Modify: `platform-api/src/main/java/com/coding/platformapi/controllers/CredentialController.java`
- Test: `platform-api/src/test/java/com/coding/platformapi/services/CredentialWriteScopeTest.java`

**Interfaces:**
- Consumes: `HarborInstallLocator.isPlatformHost(String)`（Task 1）
- Produces:
  - `CredentialView create(CredentialCreateRequest req, String hatTenantId)`
  - `CredentialView update(CredentialUpdateRequest req, String hatTenantId)`
  - `void delete(String id, String hatTenantId)`
  - 原单参重载保留（委托 `hatTenantId = null`）

- [ ] **Step 1: 写失败的测试**

`platform-api/src/test/java/com/coding/platformapi/services/CredentialWriteScopeTest.java`

```java
package com.coding.platformapi.services;

import com.coding.common.exception.CloudPlatformException;
import com.coding.data.mapper.auth.PlatformTenantMapper;
import com.coding.data.mapper.system.PlatformCredentialMapper;
import com.coding.data.mapper.system.PlatformRegistryProjectMapper;
import com.coding.data.models.system.PlatformCredential;
import com.coding.platformapi.credential.CredentialCipher;
import com.coding.platformapi.credential.CredentialProbeClient;
import com.coding.platformapi.models.CredentialCreateRequest;
import com.coding.platformapi.models.CredentialUpdateRequest;
import com.coding.platformapi.registry.DockerHubDialect;
import com.coding.platformapi.registry.HarborDialect;
import com.coding.platformapi.registry.HarborInstallLocator;
import com.coding.platformapi.registry.RegistryDialectRegistry;
import com.coding.platformapi.registry.RegistryHttpClient;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 写侧三条校验（spec §6.4.2）：归属由服务端定、内部 Harbor 拒建、不能改别人的行。 */
class CredentialWriteScopeTest {

    PlatformCredentialMapper mapper = mock(PlatformCredentialMapper.class);
    PlatformTenantMapper tenantMapper = mock(PlatformTenantMapper.class);
    PlatformRegistryProjectMapper projectMapper = mock(PlatformRegistryProjectMapper.class);
    HarborInstallLocator locator = mock(HarborInstallLocator.class);
    CredentialService svc = new CredentialService(mapper,
            new CredentialCipher("test-only-master-key-0123456789abcdef"),
            mock(CredentialProbeClient.class),
            new RegistryDialectRegistry(List.of(new HarborDialect(mock(RegistryHttpClient.class)),
                    new DockerHubDialect(mock(RegistryHttpClient.class)))),
            tenantMapper, projectMapper, locator);

    private static CredentialCreateRequest createReq(String type, String endpoint) {
        CredentialCreateRequest r = new CredentialCreateRequest();
        r.setName("n-" + endpoint);
        r.setType(type);
        r.setEndpoint(endpoint);
        r.setAuthType("BASIC");
        r.setUsername("u");
        r.setSecret("p");
        return r;
    }

    private static PlatformCredential existing(String id, String tenantId, String source) {
        PlatformCredential c = new PlatformCredential();
        c.setId(id);
        c.setName("old");
        c.setType("HARBOR");
        c.setEndpoint("harbor.corp.x");
        c.setAuthType("BASIC");
        c.setEnabled(1);
        c.setUsableByPipelines(1);
        c.setSource(source);
        c.setTenantId(tenantId);
        return c;
    }

    @Test
    void createStampsTheCallersTenantAndIgnoresAnyTenantInTheBody() {
        when(mapper.selectByName(any())).thenReturn(null);
        when(locator.isPlatformHost(any())).thenReturn(false);

        svc.create(createReq("HARBOR", "harbor.corp.x"), "t1");

        ArgumentCaptor<PlatformCredential> saved = ArgumentCaptor.forClass(PlatformCredential.class);
        verify(mapper).insert(saved.capture());
        assertThat(saved.getValue().getTenantId()).isEqualTo("t1");
    }

    @Test
    void adminCreatesPlatformLevelRows() {
        when(mapper.selectByName(any())).thenReturn(null);
        when(locator.isPlatformHost(any())).thenReturn(false);

        svc.create(createReq("HARBOR", "harbor.corp.x"), null);

        ArgumentCaptor<PlatformCredential> saved = ArgumentCaptor.forClass(PlatformCredential.class);
        verify(mapper).insert(saved.capture());
        assertThat(saved.getValue().getTenantId()).isNull();
    }

    @Test
    void refusesToCreateAConnectionPointingAtThePlatformHarbor() {
        when(locator.isPlatformHost("https://harbor.ackcp.cloud")).thenReturn(true);

        assertThatThrownBy(() -> svc.create(createReq("HARBOR", "https://harbor.ackcp.cloud"), "t1"))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("平台 Harbor");
        verify(mapper, never()).insert(any());
    }

    @Test
    void refusesToUpdateAnotherTenantsRow() {
        when(mapper.selectById("c1")).thenReturn(existing("c1", "t2", "MANUAL"));
        CredentialUpdateRequest req = new CredentialUpdateRequest();
        req.setId("c1");
        req.setName("old");
        req.setType("HARBOR");
        req.setEndpoint("harbor.corp.x");
        req.setAuthType("BASIC");

        assertThatThrownBy(() -> svc.update(req, "t1"))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("别的租户");
        verify(mapper, never()).update(any());
    }

    @Test
    void refusesToTouchMachineManagedHarborRows() {
        when(mapper.selectById("c1")).thenReturn(existing("c1", "t1", "HARBOR"));
        CredentialUpdateRequest req = new CredentialUpdateRequest();
        req.setId("c1");
        req.setName("old");
        req.setType("HARBOR");
        req.setEndpoint("harbor.ackcp.cloud");
        req.setAuthType("BASIC");

        assertThatThrownBy(() -> svc.update(req, "t1"))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("自动维护");
        assertThatThrownBy(() -> svc.delete("c1", "t1"))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("自动维护");
    }

    @Test
    void updateKeepsTheOriginalOwnerEvenWhenTheBodyOmitsEverything() {
        when(mapper.selectById("c1")).thenReturn(existing("c1", "t1", "MANUAL"));
        when(mapper.selectByName("old")).thenReturn(existing("c1", "t1", "MANUAL"));
        when(locator.isPlatformHost(any())).thenReturn(false);
        CredentialUpdateRequest req = new CredentialUpdateRequest();
        req.setId("c1");
        req.setName("old");
        req.setType("HARBOR");
        req.setEndpoint("harbor.corp.x");
        req.setAuthType("BASIC");
        // 只改描述：usableByPipelines 与 tenantId 都不传

        svc.update(req, "t1");

        ArgumentCaptor<PlatformCredential> saved = ArgumentCaptor.forClass(PlatformCredential.class);
        verify(mapper).update(saved.capture());
        assertThat(saved.getValue().getTenantId()).isEqualTo("t1");
        assertThat(saved.getValue().getUsableByPipelines()).isEqualTo(1);
    }
}
```

- [ ] **Step 2: 跑测试，确认红**

```bash
mvn -q -pl platform-api -am test -Dtest=CredentialWriteScopeTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: 编译失败 —— 带 `hatTenantId` 的重载不存在、构造器参数个数对不上。

- [ ] **Step 3: 改 `CredentialService`**

构造器末尾加 `private final HarborInstallLocator harborInstalls;`
（Task 3 用到的 `hostOf` 是**静态**方法，没有动构造器，所以这里才第一次加字段）。

```java
    public CredentialView create(CredentialCreateRequest req) {
        return create(req, null);
    }

    /**
     * @param hatTenantId 请求的租户帽；null = 平台管理员。
     *                    **归属由它决定，请求体里的 tenantId 一律忽略** —— 与
     *                    {@code PipelineRunService} 那次修的是同一个原则：用调用者身份，不用目标租户。
     */
    public CredentialView create(CredentialCreateRequest req, String hatTenantId) {
        validateCommon(req.getName(), req.getType(), req.getEndpoint());
        validateAuthOnCreate(req.getType(), req.getAuthType(), req.getUsername(), req.getSecret());
        assertNotPlatformHarbor(req.getType(), req.getEndpoint());
        if (mapper.selectByName(req.getName()) != null) {
            throw new CloudPlatformException(EnumResponseType.CREDENTIAL_NAME_EXIST);
        }
        // …原实现不变…
        // 唯一的改动：归属由服务端定
        c.setTenantId(hatTenantId);
        mapper.insert(c);
        return toView(c);
    }

    public CredentialView update(CredentialUpdateRequest req) {
        return update(req, null);
    }

    public CredentialView update(CredentialUpdateRequest req, String hatTenantId) {
        PlatformCredential old = mapper.selectById(req.getId());
        if (old == null) {
            throw new CloudPlatformException(EnumResponseType.CREDENTIAL_NOT_EXIST);
        }
        assertTangible(old, hatTenantId);
        assertNotPlatformHarbor(req.getType(), req.getEndpoint());
        // …原实现不变（含 c.setTenantId(old.getTenantId()) 与 usableByPipelines 的缺省语义）…
    }

    public void delete(String id) {
        delete(id, null);
    }

    public void delete(String id, String hatTenantId) {
        PlatformCredential old = mapper.selectById(id);
        if (old == null) {
            throw new CloudPlatformException(EnumResponseType.CREDENTIAL_NOT_EXIST);
        }
        assertTangible(old, hatTenantId);
        mapper.deleteById(id);
    }

    /**
     * 这行能不能被当前请求改/删。
     * <p>两条：{@code source=HARBOR} 的是同步写出来的（改它靠「重建」，不是改表单）；
     * 租户只能动自己租户的行（平台级 MANUAL 对租户可见但只读，见 {@code visibleTo}）。
     */
    private static void assertTangible(PlatformCredential c, String hatTenantId) {
        if ("HARBOR".equals(c.getSource())) {
            throw new CloudPlatformException(EnumResponseType.CREDENTIAL_INVALID,
                    "这条连接由 Harbor 组件自动维护，不能手工修改或删除"
                            + "（需要换口令请在镜像仓库页「重建」）");
        }
        if (hatTenantId != null && !hatTenantId.equals(c.getTenantId())) {
            throw new CloudPlatformException(EnumResponseType.CREDENTIAL_INVALID,
                    "只能修改或删除自己租户的连接");
        }
    }

    /** 平台自己装的那台 Harbor 不接受手工新建 —— 它的连接是建租户项目时自动发的 robot */
    private void assertNotPlatformHarbor(String type, String endpoint) {
        if ("HARBOR".equals(type) && harborInstalls.isPlatformHost(endpoint)) {
            throw new CloudPlatformException(EnumResponseType.CREDENTIAL_INVALID,
                    "这个地址是平台自己装的 Harbor，它的连接由同步自动创建，不能手工新建");
        }
    }
```

- [ ] **Step 4: 控制器传租户帽**

三个写端点改成：

```java
        return new ResponseData<>(credentialService.create(request, auth.hatTenantId()));
        return new ResponseData<>(credentialService.update(request, auth.hatTenantId()));
        credentialService.delete(request.getId(), auth.hatTenantId());
```

- [ ] **Step 5: 跑测试，确认绿**

```bash
mvn -q -pl platform-api -am test -Dtest='CredentialWriteScopeTest+CredentialServiceTest+CredentialControllerTest+CredentialListTest' -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: `Failures: 0, Errors: 0`。构造器多了一个参数，`CredentialServiceTest` 与 `CredentialListTest` 都要补一个 `mock(HarborInstallLocator.class)`；后者还要把 `isPlatformHost` 桩成 `false`（默认 `false`，Mockito 不必显式桩）。

- [ ] **Step 6: 跑全模块，确认没打坏**

```bash
mvn -q -pl platform-api -am test
```

- [ ] **Step 7: 提交**

```bash
git add platform-api/src/main/java/com/coding/platformapi/services/CredentialService.java \
        platform-api/src/main/java/com/coding/platformapi/controllers/CredentialController.java \
        platform-api/src/test/java/com/coding/platformapi/services/CredentialWriteScopeTest.java
git commit -m "feat(credential): 写操作按调用者定归属，平台 Harbor 不接受手工新建"
```

---

## Task 5: 前端骨架 —— 持续集成分组与 Git 凭据页

**Files:**
- Modify: `platform-web/src/pageCodes.ts`
- Modify: `platform-web/src/router/index.ts:53-54,64-69`
- Modify: `platform-web/src/layouts/MainLayout.vue:156-260`
- Create: `platform-web/src/views/GitCredentialView.vue`（由 `CredentialView.vue` 复制后收窄）
- Delete: `platform-web/src/views/CredentialView.vue`
- Modify: `platform-web/src/api/index.ts:854`
- Modify: `platform-web/src/types.ts:2245`（`CredentialView.host`）

**Interfaces:**
- Consumes: `POST /credential/list` 的可选 `{type}` body（Task 3）
- Produces: `pageCodes.credentialTenant`；路由 `/git-credentials`（name `git-credentials`）

- [ ] **Step 1: 加页面码与能力位**

`pageCodes.ts` 在 `credential` 下面加：

```ts
  credential: 'platform:page:credential',
  /** Git 凭据页的租户码（与上一行同路由，任一持有即可见 —— 过渡态，见 spec §6.4） */
  credentialTenant: 'tenant:page:credential',
```

`apiCodes.ts` 在 `credentialWrite` 下面加（租户角色不能持 `platform:` 码，所以写按钮必须认两个）：

```ts
  credentialWrite: 'platform:credential:write',
  /** 租户自管连接的写权限（同一按钮，ANY-of） */
  credentialWriteTenant: 'tenant:credential:write',
```

- [ ] **Step 2: 改类型与 api**

`types.ts` 的 `CredentialView` 里，`endpoint` 之后加：

```ts
  /** endpoint 归一后的 host[:port]（后端算好，前端别再自己解析） */
  host: string
```

`api/index.ts` 的 `credentialApi.list` 改成：

```ts
  /** 可选过滤：GIT / HARBOR / DOCKERHUB。镜像仓库页传类型集合，Git 页传 GIT */
  list: (type?: string) => http.post<never, CredentialView[]>('/credential/list', { type }),
```

- [ ] **Step 3: 建 `GitCredentialView.vue`**

把 `CredentialView.vue` 整份复制成 `GitCredentialView.vue`，然后改五处：

1. `TYPE_OPTIONS` 删到只剩 Git：

```ts
/** 本页只管 Git 连接。镜像仓库类的连接在「镜像仓库」页，见 spec §3.2 */
const TYPE_OPTIONS = [{ value: 'GIT', label: 'Git 仓库' }]
```

2. 删掉 `DOCKER_HUB` / `isDockerHub` / `authOptions` 里针对 Docker Hub 的分支，`AUTH_OPTIONS` 只留匿名与 BASIC 两项原样；
3. 删掉 `onTypeChange` 里 `DOCKER_HUB` 的分支，只留清空地址那半段；
4. `load()` 改成：

```ts
    rows.value = await credentialApi.list('GIT')
```

5. 标题文案 `<h2>凭据管理</h2>` → `<h2>Git 凭据管理</h2>`；抽屉里类型下拉保留（只有一项，用于以后扩展）；
6. **写按钮的门控改成 ANY-of** —— 租户管理员没有 `platform:credential:write`，只认它会把按钮一直灰着：

```vue
      <el-button type="primary"
                 :disabled="!perm.hasAny([apiCodes.credentialWrite, apiCodes.credentialWriteTenant])"
                 @click="openCreate">
        新建凭据
      </el-button>
```

- [ ] **Step 4: 改路由**

`router/index.ts`：

```ts
        { path: 'git-credentials', name: 'git-credentials', component: () => import('@/views/GitCredentialView.vue'), meta: { title: 'Git 凭据管理', group: '持续集成', requiresPerm: [pageCodes.credential, pageCodes.credentialTenant] } },
```

`registries` 那行 `group: '资源管理'` → `'持续集成'`；`pipelines`、`pipelines/editor`、`pipelines/runs`、`pipelines/run` 四行的 `group: '资源管理'` → `'持续集成'`。

- [ ] **Step 5: 改侧边栏**

`MainLayout.vue`：

1. **平台管理** 分组的 `v-if` 去掉 `|| can(pageCodes.credential)`，并删掉其下的凭据菜单项（`index="/credentials"` 那一段）；
2. **配置管理** 分组里删掉「镜像仓库」与「流水线」两个菜单项，分组 `v-if` 去掉 `|| can(pageCodes.registry)` 与 `pageCodes.pipeline`；
3. 在「应用交付」分组的 `</el-menu-item>` 之后、`配置管理` 分组之前插入：

```vue
        <div v-if="canAny([pageCodes.credential, pageCodes.credentialTenant]) || can(pageCodes.registry) || can(pageCodes.pipeline)" class="menu-group">持续集成</div>
        <el-menu-item v-if="can(pageCodes.pipeline)" index="/pipelines">
          <el-icon><Operation /></el-icon>
          <template #title>流水线</template>
        </el-menu-item>
        <el-menu-item v-if="canAny([pageCodes.credential, pageCodes.credentialTenant])" index="/git-credentials">
          <el-icon><Tickets /></el-icon>
          <template #title>Git 凭据管理</template>
        </el-menu-item>
        <el-menu-item v-if="can(pageCodes.registry)" index="/registries">
          <el-icon><Collection /></el-icon>
          <template #title>镜像仓库</template>
        </el-menu-item>
```

- [ ] **Step 6: 删旧页面**

```bash
rm platform-web/src/views/CredentialView.vue
```

- [ ] **Step 7: 验证构建**

```bash
cd platform-web && npm run build
```

Expected: 无 TS 错误、构建成功。若报 `CredentialView` 仍被引用，全仓搜一遍：

```bash
grep -rn "CredentialView\|'/credentials'" platform-web/src
```

Expected: 只应剩 `GitCredentialView` 自引用。

- [ ] **Step 8: 提交**

```bash
git add platform-web/src/pageCodes.ts platform-web/src/apiCodes.ts platform-web/src/router/index.ts \
        platform-web/src/layouts/MainLayout.vue platform-web/src/views/GitCredentialView.vue \
        platform-web/src/api/index.ts platform-web/src/types.ts
git rm platform-web/src/views/CredentialView.vue
git commit -m "feat(web): 侧边栏新增「持续集成」分组，凭据页收窄为 Git 凭据管理"
```

---

## Task 6: 镜像仓库页（主从只读）

**Files:**
- Modify: `platform-web/src/types.ts`（加 `RegistryWarehouse`）
- Modify: `platform-web/src/api/index.ts`（加 `warehouses`）
- Rewrite: `platform-web/src/views/RegistryView.vue`

**Interfaces:**
- Consumes: `POST /registry/warehouses`（Task 2）、`credentialApi.list(type)`（Task 3）、
  `registryApi.browse(id, path)`、`registryApi.projects(clusterId)`（既有）
- Produces: 无（叶子页面）

- [ ] **Step 1: 加类型**

`types.ts`：

```ts
/**
 * 镜像仓库页左栏的一行。一个 host[:port]（内置档 id 是 builtin:dockerhub）。
 * 后端把「安装记录 ∪ 凭据行 ∪ 内置档」聚合好，前端不再做任何推导。
 */
export interface RegistryWarehouse {
  id: string
  host: string
  kind: 'HARBOR' | 'DOCKER'
  origin: 'INSTALLED' | 'CREDENTIAL' | 'BUILTIN'
  displayName: string
  clusterId?: string | null
  exposeMode?: string | null
  connectionCount: number
  projectCount: number
  health: 'OK' | 'ERROR'
  message?: string | null
}
```

- [ ] **Step 2: 加 api**

`api/index.ts` 的 `registryApi` 里加：

```ts
  /** 仓库清单：按 host 聚合的只读视图 */
  warehouses: () => http.post<never, RegistryWarehouse[]>('/registry/warehouses'),
```

- [ ] **Step 3: 重写 `RegistryView.vue`**

整体替换为：

```vue
<script setup lang="ts">
/**
 * 镜像仓库页（spec §3.3）：左栏是「仓库」（一个 host 一行），右栏是选中仓库的
 * 连接 / 项目 / 浏览三块。
 *
 * ⚠️ 本页不做任何 host 归一或来源判定 —— 那两件事都在后端（RegistryService.warehouses）。
 * 前端再做一遍就是第二个真相源。
 */
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { credentialApi, registryApi } from '@/api'
import { usePermission } from '@/stores/permission'
import { apiCodes } from '@/apiCodes'
import type {
  CredentialView, K8sRegistryProject, RegistryBrowseResult, RegistryNode, RegistryWarehouse,
} from '@/types'
import { writeClipboard } from '@/utils/clipboard'
import { fmtDate } from '@/utils/format'

const perm = usePermission()

const warehouses = ref<RegistryWarehouse[]>([])
const selectedId = ref('')
const connections = ref<CredentialView[]>([])
const projects = ref<K8sRegistryProject[]>([])
const path = ref<string[]>([])
const result = ref<RegistryBrowseResult | null>(null)

const loading = ref(false)
const detailLoading = ref(false)
/** 与「这一层是空的」区分开 —— 浏览失败必须让用户看见 */
const error = ref('')

const selected = computed(() => warehouses.value.find((w) => w.id === selectedId.value) ?? null)
/** 选中的仓库下、当前用户可见的连接（后端已按可见性过滤，这里只按 host 分组）
 *  ⚠️ 必须排除 GIT —— 同主机的 Git 连接与镜像仓库无关 */
const selectedConnections = computed(() =>
  connections.value.filter(
    (c) => c.type !== 'GIT' && c.host === (selected.value?.host ?? ''),
  ))

async function loadWarehouses(): Promise<void> {
  loading.value = true
  try {
    warehouses.value = await registryApi.warehouses()
    const first = warehouses.value[0]
    if (first && !selectedId.value) selectedId.value = first.id
  } catch (e) {
    ElMessage.error((e as Error).message)
    warehouses.value = []
  } finally {
    loading.value = false
  }
}

async function loadDetail(): Promise<void> {
  const w = selected.value
  if (!w) return
  detailLoading.value = true
  error.value = ''
  result.value = null
  path.value = []
  try {
    // 连接：一次取全部可见的（量小），按 host 过滤 —— 免得为「按 host 查连接」再造一个端点
    connections.value = await credentialApi.list()
    projects.value = w.clusterId ? await registryApi.projects(w.clusterId) : []
    const browsable = selectedConnections.value.find((c) => c.enabled)
    if (browsable) {
      result.value = await registryApi.browse(browsable.id, [])
    } else if (w.origin === 'BUILTIN') {
      result.value = await registryApi.browse(w.id, [])
    }
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    detailLoading.value = false
  }
}

async function browse(credentialId: string, p: string[]): Promise<void> {
  path.value = p
  detailLoading.value = true
  error.value = ''
  try {
    result.value = await registryApi.browse(credentialId, p)
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    detailLoading.value = false
  }
}

function drillInto(node: RegistryNode): void {
  if (!node.hasChildren) return
  const c = selectedConnections.value.find((x) => x.enabled)
  const id = c ? c.id : selectedId.value
  void browse(id, [...path.value, node.name])
}

function goTo(p: string[]): void {
  const c = selectedConnections.value.find((x) => x.enabled)
  void browse(c ? c.id : selectedId.value, p)
}

async function copyImage(node: RegistryNode): Promise<void> {
  if (!node.copyText) return
  const ok = await writeClipboard(node.copyText)
  if (ok) ElMessage.success('已复制镜像地址')
  else ElMessage.warning('当前浏览器不允许自动复制，请手动选中复制')
}

watch(selectedId, () => void loadDetail())
onMounted(loadWarehouses)
</script>
```

模板部分：

```vue
<template>
  <div class="registry-page">
    <div class="page-head"><h2>镜像仓库</h2></div>

    <div class="registry-body" v-loading="loading">
      <aside class="wh-list">
        <div v-for="w in warehouses" :key="w.id"
             class="wh-item" :class="{ active: w.id === selectedId }"
             @click="selectedId = w.id">
          <div class="wh-name">
            {{ w.displayName }}
            <el-tag size="small" :type="w.origin === 'INSTALLED' ? 'primary'
                        : w.origin === 'BUILTIN' ? 'info' : 'warning'">
              {{ w.origin === 'INSTALLED' ? '平台' : w.origin === 'BUILTIN' ? '公共' : '外部' }}
            </el-tag>
          </div>
          <div class="wh-host">{{ w.host }}</div>
          <div class="wh-meta">
            <span>{{ w.kind === 'DOCKER' ? 'Docker' : 'Harbor' }}</span>
            <span v-if="w.connectionCount">{{ w.connectionCount }} 个连接</span>
            <span v-if="w.projectCount">{{ w.projectCount }} 个项目</span>
            <el-tag v-if="w.health === 'ERROR'" size="small" type="danger">异常</el-tag>
          </div>
        </div>
        <el-empty v-if="!warehouses.length" description="还没有可用的镜像仓库" />
      </aside>

      <section class="wh-detail" v-loading="detailLoading">
        <el-alert v-if="error" type="error" :closable="false" show-icon :title="error" />

        <template v-if="selected">
          <h3>连接</h3>
          <el-table :data="selectedConnections" size="small" stripe>
            <el-table-column prop="name" label="名称" min-width="160" />
            <el-table-column label="归属" width="120">
              <template #default="{ row }">{{ row.tenantName || '平台级' }}</template>
            </el-table-column>
            <el-table-column label="认证" width="90">
              <template #default="{ row }">{{ row.authType === 'NONE' ? '匿名' : 'Basic' }}</template>
            </el-table-column>
            <el-table-column label="上次验证" min-width="150">
              <template #default="{ row }">
                {{ row.lastVerifiedAt ? fmtDate(row.lastVerifiedAt) : '—' }}
              </template>
            </el-table-column>
            <el-table-column label="流水线可用" width="110">
              <template #default="{ row }">{{ row.usableByPipelines ? '是' : '否' }}</template>
            </el-table-column>
          </el-table>

          <template v-if="projects.length">
            <h3>项目</h3>
            <el-table :data="projects" size="small" stripe>
              <el-table-column prop="tenantName" label="归属" min-width="120" />
              <el-table-column prop="projectName" label="项目" min-width="160" />
              <el-table-column label="状态" width="100">
                <template #default="{ row }">
                  <el-tag :type="row.status === 'READY' ? 'success' : 'danger'" size="small">
                    {{ row.status === 'READY' ? '就绪' : '失败' }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column label="上次同步" width="160">
                <template #default="{ row }">{{ row.lastSyncedAt ? fmtDate(row.lastSyncedAt) : '—' }}</template>
              </el-table-column>
            </el-table>
          </template>

          <h3>浏览</h3>
          <div class="crumbs">
            <el-button link @click="goTo([])">{{ selected.displayName }}</el-button>
            <template v-for="(c, i) in result?.breadcrumb ?? []" :key="i">
              <span class="sep">/</span>
              <el-button link @click="goTo(c.path)">{{ c.label }}</el-button>
            </template>
          </div>
          <el-table :data="result?.children ?? []" size="small" stripe>
            <el-table-column label="名称" min-width="220">
              <template #default="{ row }">
                <el-button v-if="row.hasChildren" link type="primary" @click="drillInto(row)">
                  {{ row.name }}
                </el-button>
                <span v-else>{{ row.name }}</span>
              </template>
            </el-table-column>
            <el-table-column v-for="d in result?.children?.[0]?.details ?? []" :key="d.label"
                             :label="d.label" min-width="140">
              <template #default="{ row }">
                {{ row.details.find((x: { label: string }) => x.label === d.label)?.value ?? '' }}
              </template>
            </el-table-column>
            <el-table-column label="操作" width="130" fixed="right">
              <template #default="{ row }">
                <el-button v-if="row.copyText" link type="primary" @click="copyImage(row)">复制地址</el-button>
              </template>
            </el-table-column>
          </el-table>
        </template>
      </section>
    </div>
  </div>
</template>
```

样式（非 scoped 之外的普通 scoped 即可）：

```vue
<style scoped>
.page-head { margin-bottom: 12px; }
.registry-body { display: flex; gap: 16px; align-items: flex-start; }
.wh-list { width: 280px; flex: none; border-right: 1px solid var(--el-border-color-lighter); padding-right: 12px; }
.wh-item { padding: 10px 12px; border-radius: 6px; cursor: pointer; }
.wh-item.active { background: var(--el-color-primary-light-9); }
.wh-name { font-weight: 600; display: flex; gap: 8px; align-items: center; }
.wh-host { color: var(--el-text-color-secondary); font-size: 12px; margin-top: 2px; }
.wh-meta { color: var(--el-text-color-secondary); font-size: 12px; margin-top: 4px; display: flex; gap: 10px; align-items: center; }
.wh-detail { flex: 1; min-width: 0; }
.wh-detail h3 { margin: 16px 0 8px; font-size: 14px; }
.crumbs { display: flex; align-items: center; flex-wrap: wrap; margin-bottom: 8px; }
.sep { color: var(--el-text-color-placeholder); margin: 0 2px; }
</style>
```

- [ ] **Step 4: 验证构建**

```bash
cd platform-web && npm run build
```

Expected: 无 TS 错误。`apiCodes` 若在本页未用到，删掉这行 import。

- [ ] **Step 5: 浏览器实测（关键路径）**

1. 管理员进入「持续集成 → 镜像仓库」，左栏应出现：平台 Harbor（带「平台」标签）+ 公共 Docker Hub（「公共」）+ 外部 Harbor（如有）。
2. 点平台 Harbor，右栏显示连接表（自己的 robot）与项目表。
3. 点公共 Docker Hub，浏览能翻到 `library` 下的仓库。
4. 切到一个租户账号，左栏不应出现 `platform` 共享 robot 那一行对应的仓库（若该租户在平台 Harbor 上无项目）；有项目时应出现且只列自己的连接。

- [ ] **Step 6: 提交**

```bash
git add platform-web/src/types.ts platform-web/src/api/index.ts platform-web/src/views/RegistryView.vue
git commit -m "feat(web): 镜像仓库页改为主从两栏，仓库/连接/项目/浏览一处看全"
```

---

## Task 7: 连接写操作与移除 Harbor 项目弹窗

**Files:**
- Modify: `platform-web/src/views/RegistryView.vue`（加连接表单与动作）
- Modify: `platform-web/src/components/cluster/ClusterComponentsPanel.vue`
- Delete: `platform-web/src/components/cluster/HarborProjectsDialog.vue`

**Interfaces:**
- Consumes: `credentialApi.create/update/remove/test`（既有）、`registryApi.syncProjects/rebuildProject`（既有）
- Produces: 无

- [ ] **Step 1: 补两个能力位**

`apiCodes.ts` 里目前没有 `registryProjectManage`，加上（`credentialWriteTenant` 在 Task 5 已加）：

```ts
  registryCatalog: 'tenant:registry:catalog',
  /** Harbor 租户项目的同步 / 重建（平台管理员） */
  registryProjectManage: 'platform:registry:project:manage',
```

- [ ] **Step 2: 在右栏「连接」块加动作**

`RegistryView.vue` 的 script 里补：

```ts
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { apiCodes } from '@/apiCodes'

/**
 * 能不能改：外部连接看凭据写权限 —— **两个码 ANY-of**。
 * 租户角色不能持 platform: 码，只认 platform:credential:write 会把租户管理员的按钮一直灰着。
 */
const canWriteConnections = computed(() =>
  perm.hasAny([apiCodes.credentialWrite, apiCodes.credentialWriteTenant]))
const canManageProjects = computed(() => perm.has(apiCodes.registryProjectManage))

const selectedIsInstalled = computed(() => selected.value?.origin === 'INSTALLED')

const form = reactive({
  visible: false,
  id: '',
  name: '',
  type: 'HARBOR' as 'HARBOR' | 'DOCKERHUB',
  endpoint: '',
  username: '',
  secret: '',
  description: '',
  enabled: true,
  usableByPipelines: false,
})
const isEdit = computed(() => !!form.id)

function openCreate(): void {
  Object.assign(form, {
    visible: true, id: '', name: '',
    // 平台 Harbor 行不允许新建（那是同步的产物），落到这里的一定是外部仓库
    type: selected.value?.kind === 'DOCKER' ? 'DOCKERHUB' : 'HARBOR',
    endpoint: selected.value?.kind === 'DOCKER' ? 'docker.io' : '',
    username: '', secret: '', description: '', enabled: true, usableByPipelines: false,
  })
}

function openEdit(row: CredentialView): void {
  Object.assign(form, {
    visible: true, id: row.id, name: row.name,
    // form.type 只有两档，收窄一次（本表里本来就不会有 GIT）
    type: row.type === 'DOCKERHUB' ? 'DOCKERHUB' : 'HARBOR',
    endpoint: row.endpoint, username: '', secret: '',
    description: row.description ?? '', enabled: row.enabled,
    usableByPipelines: !!row.usableByPipelines,
  })
}

async function save(): Promise<void> {
  try {
    const body = {
      name: form.name, type: form.type, endpoint: form.endpoint,
      authType: 'BASIC' as const,
      username: form.username || undefined,
      secret: form.secret || undefined,
      description: form.description || null,
      enabled: form.enabled,
      usableByPipelines: form.usableByPipelines,
    }
    if (isEdit.value) await credentialApi.update({ ...body, id: form.id })
    else await credentialApi.create(body)
    ElMessage.success('已保存')
    form.visible = false
    await loadDetail()
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}

async function remove(row: CredentialView): Promise<void> {
  try {
    await ElMessageBox.confirm(`删除连接「${row.name}」？被流水线引用时会直接失效。`,
      '确认删除', { type: 'warning' })
  } catch {
    return
  }
  try {
    await credentialApi.remove(row.id)
    ElMessage.success('已删除')
    await loadDetail()
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}

async function syncAll(): Promise<void> {
  const clusterId = selected.value?.clusterId
  if (!clusterId) return
  try {
    const r = await registryApi.syncProjects(clusterId)
    if (r.failed > 0) ElMessage.warning(`同步完成，${r.failed} 个租户失败`)
    else ElMessage.success(`同步完成（${r.items.length} 项）`)
    await loadDetail()
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}

async function rebuild(row: K8sRegistryProject): Promise<void> {
  const clusterId = selected.value?.clusterId
  if (!clusterId) return
  try {
    await ElMessageBox.confirm(
      `重建「${row.tenantName}」的 Harbor 机器人账号？旧账号会被删除，凭据密文会覆盖。`,
      '确认重建', { type: 'warning' })
  } catch {
    return
  }
  try {
    const item = await registryApi.rebuildProject(clusterId, row.tenantId ?? null)
    if (item.status === 'READY') ElMessage.success('已重建')
    else ElMessage.error(item.message || '重建失败')
    await loadDetail()
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}
```

「连接」块的表格加两列与工具栏：

```vue
          <div class="block-head">
            <h3>连接</h3>
            <div>
              <!-- 平台 Harbor 的连接是同步出来的 robot，不能手工新建 -->
              <el-button v-if="canWriteConnections && !selectedIsInstalled"
                         type="primary" size="small" @click="openCreate">新建连接</el-button>
              <el-button v-if="canManageProjects && selectedIsInstalled"
                         size="small" @click="syncAll">同步全部</el-button>
            </div>
          </div>
```

连接表操作列：

```vue
            <el-table-column label="操作" width="200" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" @click="openEdit(row)"
                           :disabled="!canWriteConnections || row.source === 'HARBOR'">编辑</el-button>
                <el-button link type="danger" @click="remove(row)"
                           :disabled="!canWriteConnections || row.source === 'HARBOR'">删除</el-button>
              </template>
            </el-table-column>
```

项目表操作列（仅管理员）：

```vue
              <el-table-column v-if="canManageProjects" label="操作" width="110" fixed="right">
                <template #default="{ row }">
                  <el-button link type="primary" @click="rebuild(row)">重建账号</el-button>
                </template>
              </el-table-column>
```

抽屉表单（地址字段按 spec §3.3 的三条规则）：

```vue
    <el-drawer v-model="form.visible" :title="isEdit ? '编辑连接' : '新建连接'" size="480px">
      <el-form label-width="110px">
        <el-form-item label="名称" required>
          <el-input v-model="form.name" maxlength="64" />
        </el-form-item>
        <el-form-item label="类型" required>
          <el-select v-model="form.type" :disabled="isEdit">
            <!-- 平台 Harbor 不在选项里：它的连接由同步自动创建 -->
            <el-option value="HARBOR" label="Harbor（外部）" />
            <el-option value="DOCKERHUB" label="Docker Hub（私有账号）" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="form.type === 'HARBOR'" label="地址" required>
          <el-input v-model="form.endpoint" placeholder="harbor.corp.example.com" />
          <div class="hint">只填主机名（可带端口），不要带 https:// 与路径</div>
        </el-form-item>
        <el-form-item v-else label="地址">
          <el-input model-value="docker.io" disabled />
          <div class="hint">Docker Hub 的地址是固定的，不用填</div>
        </el-form-item>
        <el-form-item label="用户名"><el-input v-model="form.username" /></el-form-item>
        <el-form-item :label="isEdit ? '密码/令牌（留空不改）' : '密码/令牌'">
          <el-input v-model="form.secret" type="password" show-password />
        </el-form-item>
        <el-form-item label="说明"><el-input v-model="form.description" type="textarea" :rows="2" /></el-form-item>
        <el-form-item label="启用"><el-switch v-model="form.enabled" /></el-form-item>
        <el-form-item label="允许流水线使用"><el-switch v-model="form.usableByPipelines" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="form.visible = false">取消</el-button>
        <el-button type="primary" @click="save">保存</el-button>
      </template>
    </el-drawer>
```

补样式：

```css
.block-head { display: flex; align-items: center; justify-content: space-between; }
.hint { color: var(--el-text-color-secondary); font-size: 12px; line-height: 1.4; }
```

> 用到 `apiCodes.credentialWrite` / `apiCodes.registryProjectManage` 前先确认它们在 `platform-web/src/apiCodes.ts` 里存在；不存在就按 `apiCodes` 现有命名风格补上（值分别是 `platform:credential:write` 与 `platform:registry:project:manage`）。

- [ ] **Step 3: 从组件页摘掉项目弹窗**

`ClusterComponentsPanel.vue`：

1. 删 `import HarborProjectsDialog from './HarborProjectsDialog.vue'`；
2. 删 `const harborProjects = ref(false)` 与 `function openHarborProjects()`；
3. 删模板里 `<HarborProjectsDialog ... />` 那一行；
4. 原来触发 `openHarborProjects()` 的按钮改成跳转：

```vue
        <el-button v-if="row.code === 'harbor'" link type="primary"
                   @click="router.push('/registries')">管理项目与连接 →</el-button>
```

（`router` 已在 `ClusterComponentsPanel` 里可用；若没有则 `import { useRouter } from 'vue-router'` 并 `const router = useRouter()`。）

- [ ] **Step 4: 删弹窗文件**

```bash
git rm platform-web/src/components/cluster/HarborProjectsDialog.vue
```

- [ ] **Step 5: 验证构建**

```bash
cd platform-web && npm run build
```

Expected: 无 TS 错误、无未使用符号告警。

- [ ] **Step 6: 浏览器实测**

1. 管理员在镜像仓库页选中「外部 Harbor」→「新建连接」→ 填 host → 保存后左栏出现该仓库。
2. 在新建表单里把地址填成平台 Harbor 的地址 → 保存应被拒，提示"平台自己装的 Harbor…不能手工新建"。
3. 选中平台 Harbor → 只有「同步全部」，没有「新建连接」；robot 行的编辑/删除按钮禁用。
4. 用租户管理员登录 → 能建自己的外部 Harbor 连接；平台 Harbor 的连接只读。
5. 集群 → 组件 → Harbor → 点「管理项目与连接 →」跳到镜像仓库页。

- [ ] **Step 7: 提交**

```bash
git add platform-web/src/views/RegistryView.vue \
        platform-web/src/components/cluster/ClusterComponentsPanel.vue \
        platform-web/src/apiCodes.ts
git rm platform-web/src/components/cluster/HarborProjectsDialog.vue
git commit -m "feat(web): 镜像仓库页支持连接的增删改与项目同步，组件页去掉项目弹窗"
```

---

## 收尾：部署顺序

1. 重放 `V2026_10_29_2__registry_warehouses.sql`
2. 重放 `V2026_10_29_3__credential_tenant_open.sql`
3. 重建 `platform-api`、`platform-web`（`k8s-server` 本批无改动，可不动）
4. 重新登录（权限闭包按请求期现算，30s 缓存；旧 token 里的 `platform:page:credential` 仍然有效，不影响）

---

## 本期不做（与 spec §8 / §9 一致）

- 除 Docker Hub 外的公共源（`quay.io` / `ghcr.io` / `gcr.io`）不进仓库清单
- 平台级连接"只公开一部分"（`platform_credential.public_read`）不加列，本期按"全部公开"实现
- 不拆表（"连接"与"凭据"仍是同一行）、不合并 `HarborRegistryService` 与 `RegistryService`
- 不动 Harbor 组件的安装 / 升级 / 卸载与 recipe
- 不动镜像改写的四级回落（`ImageRegistryService`）
- 不动流水线的两个连接端点（`/pipeline/git-connections`、`/pipeline/registry-connections`）
- `platform:page:credential` **不停用**，先与 `tenant:page:credential` 并存（停用会让持有它的自定义角色静默失去入口）

## Review Focus 与测试的对应

| Review Focus | 钉住它的测试 | 位置 |
|---|---|---|
| 1. 租户看到别人的仓库 | `tenantProjectCountOnlyCountsItsOwnRows`、`tenantDoesNotSeeAnInstalledHarborWithNoVisibleCredential` | Task 2 |
| 2. 内置开关关掉仍出现 | `builtinDockerHubAppearsOnlyWhenItsSwitchIsOn` | Task 2 |
| 3. 平台 Harbor 手工新建放行 | `refusesToCreateAConnectionPointingAtThePlatformHarbor` + `isPlatformHostIgnoresSchemePathAndCase` | Task 4 / Task 1 |
| 4. 只改描述改掉归属 | `updateKeepsTheOriginalOwnerEvenWhenTheBodyOmitsEverything` | Task 4 |
| 5. 新端点漏权限行 | `warehouseEndpointHasAPermissionRowBoundToAllBuiltinRoles` | Task 2 |
