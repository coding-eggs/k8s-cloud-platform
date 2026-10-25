# Milvus 中间件（operator 模式）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在中间件商店新增第 8 款 `milvus`：Operator 安装、单节点/集群两形态、对象存储只走外部（平台 SeaweedFS 桶或用户自带 S3）。

**Architecture:** 与既有 7 款同构 —— `MiddlewareCatalog`（platform-api）管"卖什么/指标/连接方式"，`MiddlewareOperatorCatalog` + `MilvusMiddlewareAdapter`（k8s-server）管"装哪个 Operator、CR 怎么拼"，前端只渲染后端下发的目录与可选项。新增两个适配器默认方法（额外凭据 Secret、CR 异常判定）承载 Milvus 特有的两处差异。

**Tech Stack:** Java 21 / Spring Boot 4.x、fabric8 Kubernetes Client、Helm、Vue 3 + Element Plus + TypeScript、MySQL 迁移 SQL。

**Spec:** `docs/superpowers/specs/2026-10-25-milvus-middleware-design.md`

## Global Constraints

- 中间件 code 固定为 `milvus`；K8s 对象名由 platform-api 生成，形如 `mw-milvus-<8 hex>`。
- Operator chart 固定 `milvus-operator` **1.3.12**；Milvus 镜像固定 `milvusdb/milvus:v2.6.25`；平台版本清单 `["2.6.25"]`。
- Operator 装在 `platform-middleware-system`，release 名 `platform-middleware-milvus`；`enableWebhook: false`、`installCRDs: true`。
- **不在集群里创建对象存储**（不部署 minio/silo）：`spec.dependencies.storage.external: true`、`type: S3`。
- Secret 键固定 `accesskey` / `secretkey`；AK/SK **不落库、不记日志、不回传前端**。
- 每个容器必须带 requests + limits（Milvus 各组件、etcd）。
- Milvus 的 TLS 开关必须写两处：`dependencies.storage.ssl.enabled` 与 `config.milvus.minio.useSSL`。
- 平台标签 `platform.coding/middleware-instance=<实例 id>` 要打在 Milvus 本地数据盘 PVC 与 etcd PVC 上（删实例时按它回收）。
- 权限行：**不新增**。新增 SQL 只有租户 RBAC 模板的 `milvus.io` 组。
- 测试命令统一带 `-Dsurefire.failIfNoSpecifiedTests=false`（`-am` 会带上没有匹配测试的模块）。

## Review Focus

- **CR 字段写错但静默生效**：endpoint 带 scheme 会让端口解析错；`config` 多包一层 `milvus` 会让桶/TLS 配置整段不生效 —— 期望 endpoint 一律规整成 `host:port`、`config.minio` 是顶层键（Task 2 的 `hostPortKeepsOnlyHostAndPort` 与 `storageIsAlwaysExternalS3WithSecretRef`）。
- **`useSSL` 两处不一致**：只写 `storage.ssl.enabled` 时 operator 探测通过、Milvus 客户端仍走明文 —— 期望两处同步（Task 2 的 `tlsWritesSslFlagInBothPlaces`）。
- **单节点本地盘成为孤儿 PVC**：`keepPvc=false` 删实例后，`<name>-data` 与 `etcd` 的 PVC 必须能被平台按标签找到 —— 期望两类 PVC 都带 `platform.coding/middleware-instance`（Task 2 的两个 PVC 断言）。
- **自家 S3 缺字段**：用户只填了一半应直接报错，而不是建出一个连不上存储的实例 —— 期望 adapter 与 API 各挡一次（Task 2 的 `rejectsMissingObjectStorageParameters`、Task 6 的 `createRejectsCustomStorageWithoutCredentials`）。
- **删实例误删用户的桶**：`CUSTOM` 接法下平台只能删 Secret，桶属于用户 —— 期望 `objectStorageBucketId` 为空时删除路径什么都不做（Task 6 的 `deleteWithKeepPvcKeepsBucket` / `deleteWithoutKeepPvcDeletesPlatformBucket`）。

---

## Task 1: 内置 Operator chart 与目录项

**Files:**
- Create: `k8s-server/src/main/resources/charts/middleware/milvus/1.3.12/chart/**`（上游 chart 原样内置）
- Create: `k8s-server/src/main/resources/charts/middleware/milvus/README.md`
- Create: `k8s-server/src/main/resources/charts/middleware/milvus/1.3.12/README.md`
- Modify: `k8s-server/src/main/java/com/coding/k8sserver/middleware/MiddlewareOperatorCatalog.java:87`
- Test: `k8s-server/src/test/java/com/coding/k8sserver/middleware/MiddlewareOperatorCatalogTest.java`

**Interfaces:**
- Consumes: 无（仓库现有 `Entry` 记录与 `ChartBundle.chartDir("middleware", code, version)` 约定）
- Produces: `MiddlewareOperatorCatalog.require("milvus")` → `Entry(code="milvus", chartVersion="1.3.12", requiredCrds=[milvuses.milvus.io, milvusclusters.milvus.io, milvusupgrades.milvus.io], helmValues=..., crdsShipInCrdsDir=false)`

- [ ] **Step 1: 下载并内置 Operator chart**

```bash
cd /d/coding/k8s-cloud-platform
curl -sSL -m 180 -o /tmp/milvus-operator-1.3.12.tgz \
  https://ghfast.top/https://github.com/zilliztech/milvus-operator/releases/download/milvus-operator-1.3.12/milvus-operator-1.3.12.tgz
rm -rf /tmp/milvus-op-extract && mkdir -p /tmp/milvus-op-extract
tar xzf /tmp/milvus-operator-1.3.12.tgz -C /tmp/milvus-op-extract
mkdir -p k8s-server/src/main/resources/charts/middleware/milvus/1.3.12
rm -rf k8s-server/src/main/resources/charts/middleware/milvus/1.3.12/chart
cp -r /tmp/milvus-op-extract/milvus-operator k8s-server/src/main/resources/charts/middleware/milvus/1.3.12/chart
```

校验（两个数字必须对上）：

```bash
grep -m1 '^version:' k8s-server/src/main/resources/charts/middleware/milvus/1.3.12/chart/Chart.yaml   # 期望 1.3.12
grep -c 'kind: CustomResourceDefinition' k8s-server/src/main/resources/charts/middleware/milvus/1.3.12/chart/templates/crds.yaml  # 期望 3
```

> 直连 GitHub 在本机会被重置；`ghfast.top` 镜像可用；若镜像失效，可用 `curl -x http://127.0.0.1:7890` 直连下载。

- [ ] **Step 2: 写组件级 README**

`k8s-server/src/main/resources/charts/middleware/milvus/README.md`：

```markdown
# Milvus（中间件 code: `milvus`）

> **组件级 README（跨版本）**：平台怎么建实例、约定是什么、踩过哪些坑、加新版本要改哪里。
> 具体 chart 版本的事实（来源/CRD 清单/镜像）在 `1.3.12/README.md`。
> 设计文档：`docs/superpowers/specs/2026-10-25-milvus-middleware-design.md`。

## 1. 概览

| 项 | 值 |
|---|---|
| 部署方式 | Operator（`milvus-operator`，`milvus.io/v1beta1`） |
| Operator chart | `1.3.12`，release `platform-middleware-milvus` → `platform-middleware-system` |
| 实例形态 | `STANDALONE`（1 个 Milvus Pod）/ `CLUSTER`（proxy/mixcoord/datanode/querynode/streamingnode） |
| 数据存储 | **外部对象存储**（平台 SeaweedFS 桶 / 用户自带 S3）；集群里不拉起 minio/silo |
| etcd | Operator 在租户命名空间拉起，1–3 节点（表单选择，创建后不可改） |
| 凭据 | 无（Milvus 默认不开鉴权）；对象存储 AK/SK 写在 `<实例名>-milvus-storage` |
| 端口 | gRPC 19530；指标/健康 9091 |
| 业务指标 | 各组件自带 Prometheus（9091 `/metrics`），平台只建 Service + ServiceMonitor |

## 2. 平台约定（所有版本都适用）

- **实例名**：`mw-milvus-<8 位随机>`，即 `Milvus` CR 名；Service 名是 `<实例名>-milvus`；
- **受管标签**：CR 带 `platform.coding/managed-by=middleware` + `platform.coding/middleware-instance=<id>`；
- **PVC 标签**：单节点本地盘（`<实例名>-data`）与 etcd PVC 都带同一个 `platform.coding/middleware-instance`，
  删实例且 `keepPvc=false` 时平台按它回收；
- **资源**：Milvus 组件与 etcd **必须**写 requests + limits（租户命名空间有 quota）；
- **租户 RBAC**：模板需要 `milvus.io`（`V2026_10_25_7`）+ **重下发全部模板**；
- **状态**：Pod 选择器 `app.kubernetes.io/instance=<实例名>,app.kubernetes.io/name=milvus`。

## 3. 形态与 CR 对应

| 形态 | CR 要点 |
|---|---|
| `STANDALONE` | `mode: standalone`；`persistence` 开 PVC（本地 WAL/临时盘）；WAL 用 rocksmq |
| `CLUSTER` | `mode: cluster` + `dependencies.msgStreamType: woodpecker`；`components.queryNode.replicas` 由表单控制，其余组件固定 1；不配 persistence（woodpecker 写对象存储） |

## 4. 坑（实施与真跑时核对）

1. **对象存储一律 external**：`storage.external=true`、`type=S3`、`endpoint` 只写 `host:port`（手机会被解析错端口）；
2. **TLS 写两处**：`dependencies.storage.ssl.enabled` + `config.milvus.minio.useSSL`；
3. **SeaweedFS 用路径风格**：`config.milvus.minio.useVirtualHost=false`；
4. **etcd chart 默认不写 resources**：平台必须补，否则租户 quota 直接拒；
5. **单节点本地盘 PVC 没有 OwnerReference**：删 CR 不会带走它，必须靠标签回收；
6. **`installCRDs: true` 且 CRD 在 `templates/`**：手工卸载 release 会连 CRD 与全部实例一起删，平台不提供卸载入口。

## 5. 业务指标

- 9091 `/metrics`，所有组件都暴露；Service/Pod 选择器与状态判定一致；
- 指标定义在 platform-api `MiddlewareCatalog.MILVUS_METRICS`；
- 开启指标（编辑里）不重启 Pod。

## 6. 加一个新版本怎么做

**升级 Operator chart**：

1. `helm pull`/下载新 chart 到 `charts/middleware/milvus/<新版本>/chart/`，新建 `<新版本>/README.md`；
2. 改 `MiddlewareOperatorCatalog` 的 `chartVersion` 与 `helmValues.image.tag`；
3. 核对新 Operator 的默认 Milvus 版本（上游 `pkg/config/config.go`），必要时同步 platform-api 的版本清单与 `MilvusMiddlewareAdapter.DEFAULT_VERSION`；
4. 跑测试 → 真集群装一次 → 建单节点/集群各一个 → 看 9091 出数。

**升级 Milvus 版本**：改平台版本清单 + 适配器默认值；大版本（2.x → 3.x）先按官方兼容表在测试集群验证 CRD 字段。
```

- [ ] **Step 3: 写版本级 README**

`k8s-server/src/main/resources/charts/middleware/milvus/1.3.12/README.md`：

```markdown
# milvus-operator 1.3.12（平台内置）

| 项 | 值 |
|---|---|
| 来源 | `https://github.com/zilliztech/milvus-operator/releases/download/milvus-operator-1.3.12/milvus-operator-1.3.12.tgz` |
| appVersion | 1.3.12 |
| 镜像 | `milvusdb/milvus-operator:v1.3.12` |
| 平台安装 values | `enableWebhook: false`、`installCRDs: true`、显式 `image.repository/tag` |
| CRD（在 `templates/crds.yaml`） | `milvuses.milvus.io`、`milvusclusters.milvus.io`、`milvusupgrades.milvus.io` |
| 默认 Milvus 版本 | `v2.6.25`（`pkg/config/config.go` 的 `DefaultMilvusVersion`） |
| 内置依赖 chart（本版本） | etcd 8.12.0、silo 7.0.2（存储）、pulsar/kafka（平台不使用）、cert-manager（`enabled=false`） |

## 注意

- 本 chart 含 `charts/cert-manager` 子 chart（`condition: cert-manager.enabled`，默认 false、无 `crds/` 目录），
  原样保留，不裁剪；
- `enableWebhook: false` 时 controller 自己调 `SetDefault` 补默认值，不依赖 cert-manager；
- 官方 release 资产里的 chart 目录名就是 `milvus-operator`，内置时把整目录拷成 `1.3.12/chart`。
```

- [ ] **Step 4: 加目录项**

`MiddlewareOperatorCatalog.java` 的静态块里，在 `rabbitmq` 之后追加：

```java
        m.put("milvus", new Entry("milvus", "1.3.12",
                List.of("milvuses.milvus.io", "milvusclusters.milvus.io", "milvusupgrades.milvus.io"),
                // webhook 关掉：不需要 cert-manager，controller 内部自己补默认值。
                // CRD 在 templates/crds.yaml（不是 crds/ 目录），所以 crdsShipInCrdsDir=false。
                "enableWebhook: false\n"
                        + "installCRDs: true\n"
                        + "image:\n"
                        + "  repository: milvusdb/milvus-operator\n"
                        + "  pullPolicy: IfNotPresent\n"
                        + "  tag: v1.3.12\n", false));
```

- [ ] **Step 5: 写测试**

在 `MiddlewareOperatorCatalogTest` 追加：

```java
    @Test
    void milvusShipsCrdsInTemplatesAndDisablesWebhook() {
        var entry = catalog.require("milvus");
        assertThat(entry.chartVersion()).isEqualTo("1.3.12");
        assertThat(entry.requiredCrds()).containsExactly(
                "milvuses.milvus.io", "milvusclusters.milvus.io", "milvusupgrades.milvus.io");
        // CRD 在 templates/ 里：Helm 会给它们打 release 注解，重装时按"本 release 的 CRD"接管
        assertThat(entry.crdsShipInCrdsDir()).isFalse();
        assertThat(entry.helmValues())
                .contains("enableWebhook: false")
                .contains("installCRDs: true")
                .contains("tag: v1.3.12");
    }
```

- [ ] **Step 6: 跑测试**

```bash
mvn -q -pl k8s-server -am test -Dtest=MiddlewareOperatorCatalogTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: BUILD SUCCESS, 6 tests passed.

- [ ] **Step 7: 提交**

```bash
git add k8s-server/src/main/resources/charts/middleware/milvus \
        k8s-server/src/main/java/com/coding/k8sserver/middleware/MiddlewareOperatorCatalog.java \
        k8s-server/src/test/java/com/coding/k8sserver/middleware/MiddlewareOperatorCatalogTest.java
git commit -m "feat(milvus): 内置 milvus-operator 1.3.12 并登记目录项"
```

---

## Task 2: 契约字段与 Milvus 适配器（CR 生成）

**Files:**
- Modify: `platform-common/src/main/java/com/coding/common/models/middleware/MiddlewareCreateRequestDTO.java`
- Modify: `platform-api/src/main/java/com/coding/platformapi/models/MiddlewareCreateRequest.java`
- Modify: `k8s-server/src/main/java/com/coding/k8sserver/middleware/MiddlewareAdapter.java`
- Create: `k8s-server/src/main/java/com/coding/k8sserver/middleware/MilvusMiddlewareAdapter.java`
- Test: `k8s-server/src/test/java/com/coding/k8sserver/middleware/MilvusMiddlewareAdapterTest.java`

**Interfaces:**
- Consumes: `MiddlewareAdapter`（现有接口）、`MiddlewareCreateRequestDTO`、`MiddlewareScaleRequestDTO`
- Produces:
  - `MilvusMiddlewareAdapter implements MiddlewareAdapter`，`code()` = `"milvus"`
  - `static String MilvusMiddlewareAdapter.hostPort(String endpoint)` → `host:port`
  - `static int MilvusMiddlewareAdapter.etcdReplicas(MiddlewareCreateRequestDTO)` → 1..3
  - `MiddlewareAdapter.extraSecretName(String)` / `extraSecretData(MiddlewareCreateRequestDTO)` / `crProblem(GenericKubernetesResource)`（默认方法，其它中间件不受影响）
  - **不覆写 `pvcSelector`**：用接口默认的 `platform.coding/middleware-instance=<id>`；Milvus 本地盘与 etcd 的 PVC 都打了这个标签
  - DTO 新字段：`etcdReplicas`、`objectStorageType`、`objectStorageEndpoint`、`objectStorageBucket`、`objectStorageAccessKey`、`objectStorageSecretKey`、`objectStorageUseSsl`、`localStorageSize`

- [ ] **Step 1: 先加契约字段**

`MiddlewareCreateRequestDTO.java` 在 `metricsEnabled` 之后追加：

```java
    /** Milvus：etcd 节点数（1~3；null = 单节点 1、集群 3） */
    private Integer etcdReplicas;

    /** Milvus：对象存储来源，PLATFORM / CUSTOM */
    private String objectStorageType;

    /** Milvus：S3 endpoint（CUSTOM 由用户填；PLATFORM 由平台填集群内地址） */
    private String objectStorageEndpoint;

    /** Milvus：桶名（PLATFORM 由平台填；CUSTOM 由用户填） */
    private String objectStorageBucket;

    /** Milvus：S3 AccessKey（只用于写 Secret，不落库） */
    private String objectStorageAccessKey;

    /** Milvus：S3 SecretKey（只用于写 Secret，不落库） */
    private String objectStorageSecretKey;

    /** Milvus：S3 是否走 TLS（null/false = 明文） */
    private Boolean objectStorageUseSsl;

    /** Milvus：单节点本地数据盘（默认 10Gi；集群形态不使用） */
    private String localStorageSize;
```

`platform-api/.../models/MiddlewareCreateRequest.java` 追加同样的 8 个字段（注释同上，可精简）。

接着扩展适配器接口（Milvus 需要的两个钩子；放在本任务里，适配器实现才能编译）：

`MiddlewareAdapter.java` 在 `usesPlatformPassword()` 之后插入：

```java
    /**
     * 平台要额外写的凭据 Secret 名（不属于"初始口令"；目前只有 Milvus 的对象存储 AK/SK）。
     * <p>null = 本中间件不需要。
     */
    default String extraSecretName(String name) {
        return null;
    }

    /** extraSecretName 非空时写入的键值；返回空 map = 请求缺必填字段（调用方报错）。 */
    default Map<String, String> extraSecretData(MiddlewareCreateRequestDTO req) {
        return Map.of();
    }

    /**
     * 本中间件自己的 CR 异常判定（共享的 crFailureMessage 只认 Ready/Available condition）。
     * <p>返回 null = 没发现问题。
     */
    default String crProblem(GenericKubernetesResource live) {
        return null;
    }
```

并在 import 区补 `import java.util.Map;`。

- [ ] **Step 2: 写失败测试**

新建 `MilvusMiddlewareAdapterTest.java`：

```java
package com.coding.k8sserver.middleware;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.models.middleware.MiddlewareCreateRequestDTO;
import com.coding.common.models.middleware.MiddlewareScaleRequestDTO;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MilvusMiddlewareAdapterTest {

    private final MilvusMiddlewareAdapter adapter = new MilvusMiddlewareAdapter();

    private MiddlewareCreateRequestDTO req(String mode) {
        MiddlewareCreateRequestDTO r = new MiddlewareCreateRequestDTO();
        r.setInstanceId("mwi_1");
        r.setClusterId("c1");
        r.setCode("milvus");
        r.setMode(mode);
        r.setNamespace("demo");
        r.setName("mw-milvus-abc12345");
        r.setReplicas(1);
        r.setVersion("2.6.25");
        r.setCpuRequest("500m");
        r.setCpuLimit("2");
        r.setMemoryRequest("2Gi");
        r.setMemoryLimit("4Gi");
        r.setStorageSize("20Gi");
        r.setLocalStorageSize("10Gi");
        r.setObjectStorageType("CUSTOM");
        r.setObjectStorageEndpoint("s3.example.com:9000");
        r.setObjectStorageBucket("milvus-bucket");
        r.setObjectStorageAccessKey("AK");
        r.setObjectStorageSecretKey("SK");
        r.setObjectStorageUseSsl(false);
        return r;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> spec(GenericKubernetesResource res) {
        return (Map<String, Object>) res.getAdditionalProperties().get("spec");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o) {
        return (Map<String, Object>) o;
    }

    private static Map<String, Object> etcdValues(GenericKubernetesResource res) {
        Map<String, Object> etcd = map(map(spec(res).get("dependencies")).get("etcd"));
        return map(map(etcd.get("inCluster")).get("values"));
    }

    @Test
    void standaloneBuildsStandaloneModeWithLocalVolume() {
        var res = adapter.buildCreate(req("STANDALONE")).get(0).resource();
        Map<String, Object> spec = spec(res);

        assertThat(res.getApiVersion()).isEqualTo("milvus.io/v1beta1");
        assertThat(res.getKind()).isEqualTo("Milvus");
        assertThat(spec).containsEntry("mode", "standalone");
        assertThat(map(spec.get("components"))).containsEntry("image", "milvusdb/milvus:v2.6.25");
        assertThat(map(map(spec.get("components")).get("resources")).get("limits"))
                .isEqualTo(Map.of("cpu", "2", "memory", "4Gi"));

        Map<String, Object> pvc = map(map(spec.get("persistence")).get("persistentVolumeClaim"));
        assertThat(pvc.get("labels")).isEqualTo(Map.of("platform.coding/middleware-instance", "mwi_1"));
        assertThat(map(map(pvc.get("spec")).get("resources")).get("requests"))
                .isEqualTo(Map.of("storage", "10Gi"));
    }

    @Test
    void standaloneEtcdGetsResourcesAndPlatformPvcLabel() {
        var res = adapter.buildCreate(req("STANDALONE")).get(0).resource();
        Map<String, Object> deps = map(spec(res).get("dependencies"));

        assertThat(deps).doesNotContainKey("msgStreamType");
        Map<String, Object> etcd = etcdValues(res);
        assertThat(etcd).containsEntry("replicaCount", 1);
        assertThat(map(etcd.get("persistence")).get("labels"))
                .isEqualTo(Map.of("platform.coding/middleware-instance", "mwi_1"));
        assertThat(map(etcd.get("resources")).get("limits"))
                .isEqualTo(Map.of("cpu", "500m", "memory", "512Mi"));
    }

    @Test
    void storageIsAlwaysExternalS3WithSecretRef() {
        var res = adapter.buildCreate(req("STANDALONE")).get(0).resource();
        Map<String, Object> storage = map(map(spec(res).get("dependencies")).get("storage"));

        assertThat(storage).containsEntry("external", true)
                .containsEntry("type", "S3")
                .containsEntry("endpoint", "s3.example.com:9000")
                .containsEntry("secretRef", "mw-milvus-abc12345-milvus-storage");
        assertThat(map(spec(res).get("config")).toString())
                .contains("bucketName=milvus-bucket")
                .contains("useVirtualHost=false");
        // operator 把 spec.config 直接并进 milvus.yaml：多包一层 milvus 就等于没配
        assertThat(map(spec(res).get("config"))).containsKey("minio").doesNotContainKey("milvus");
    }

    @Test
    void clusterUsesWoodpeckerAndQueryNodeReplicas() {
        var r = req("CLUSTER");
        r.setReplicas(3);
        var spec = spec(adapter.buildCreate(r).get(0).resource());

        assertThat(spec).containsEntry("mode", "cluster");
        assertThat(spec).doesNotContainKey("persistence");
        assertThat(map(spec.get("dependencies"))).containsEntry("msgStreamType", "woodpecker");
        assertThat(map(map(spec.get("components")).get("queryNode"))).containsEntry("replicas", 3);
        // 集群形态默认 3 个 etcd
        assertThat(etcdValues(res)).containsEntry("replicaCount", 3);
    }

    @Test
    void tlsWritesSslFlagInBothPlaces() {
        var r = req("STANDALONE");
        r.setObjectStorageEndpoint("https://s3.example.com:9443/");
        r.setObjectStorageUseSsl(true);
        var spec = spec(adapter.buildCreate(r).get(0).resource());

        Map<String, Object> storage = map(map(spec.get("dependencies")).get("storage"));
        assertThat(storage).containsEntry("endpoint", "s3.example.com:9443");
        assertThat(map(storage.get("ssl"))).containsEntry("enabled", true);
        assertThat(map(spec.get("config")).toString()).contains("useSSL=true");
    }

    @Test
    void hostPortKeepsOnlyHostAndPort() {
        assertThat(MilvusMiddlewareAdapter.hostPort("http://platform-seaweedfs-s3.platform-storage:8333"))
                .isEqualTo("platform-seaweedfs-s3.platform-storage:8333");
        assertThat(MilvusMiddlewareAdapter.hostPort("s3.example.com:9000")).isEqualTo("s3.example.com:9000");
        assertThat(MilvusMiddlewareAdapter.hostPort("https://s3.example.com/x/y")).isEqualTo("s3.example.com");
    }

    @Test
    void rejectsEtcdReplicasOutsideRange() {
        var r = req("CLUSTER");
        r.setEtcdReplicas(4);
        assertThatThrownBy(() -> adapter.buildCreate(r))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("etcd");
    }

    @Test
    void rejectsMissingObjectStorageParameters() {
        var r = req("STANDALONE");
        r.setObjectStorageBucket("  ");
        assertThatThrownBy(() -> adapter.buildCreate(r))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("对象存储");
    }

    @Test
    void extraSecretUsesOperatorKeyNames() {
        assertThat(adapter.extraSecretName("mw-milvus-abc12345"))
                .isEqualTo("mw-milvus-abc12345-milvus-storage");
        assertThat(adapter.extraSecretData(req("STANDALONE")))
                .isEqualTo(Map.of("accesskey", "AK", "secretkey", "SK"));
        var missing = req("STANDALONE");
        missing.setObjectStorageSecretKey("");
        assertThat(adapter.extraSecretData(missing)).isEmpty();
    }

    @Test
    void statusSelectorAndMetricsMatchOperatorLabels() {
        assertThat(adapter.statusSelector("mw-milvus-abc12345", "Milvus"))
                .isEqualTo("app.kubernetes.io/instance=mw-milvus-abc12345,app.kubernetes.io/name=milvus");
        var metrics = adapter.metricsSpec("mw-milvus-abc12345", "Milvus", "mwi_1");
        assertThat(metrics.port()).isEqualTo(9091);
        assertThat(metrics.path()).isEqualTo("/metrics");
        assertThat(metrics.scheme()).isEqualTo("http");
    }

    @Test
    void desiredPodsSumsComponentReplicas() {
        GenericKubernetesResource live = new GenericKubernetesResource();
        Map<String, Object> qn = new LinkedHashMap<>();
        qn.put("replicas", 3);
        Map<String, Object> components = new LinkedHashMap<>();
        components.put("image", "milvusdb/milvus:v2.6.25");
        components.put("proxy", Map.of("replicas", 1));
        components.put("mixcoord", Map.of("replicas", 1));
        components.put("datanode", Map.of("replicas", 1));
        components.put("queryNode", qn);
        components.put("streamingnode", Map.of("replicas", 1));
        components.put("standalone", Map.of("replicas", 0));
        live.setAdditionalProperty("spec", Map.of("components", components));

        assertThat(adapter.desiredPods(live, "Milvus")).isEqualTo(7);
    }

    @Test
    void applyScaleRejectsStandaloneReplicaChange() {
        GenericKubernetesResource live = new GenericKubernetesResource();
        live.setAdditionalProperty("spec", new LinkedHashMap<>(Map.of("mode", "standalone")));
        var req = new MiddlewareScaleRequestDTO();
        req.setMode("STANDALONE");
        req.setReplicas(2);

        assertThatThrownBy(() -> adapter.applyScale(live, MilvusMiddlewareAdapter.CRD, req))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("单节点");
    }

    @Test
    void applyScaleUpdatesQueryNodeAndResources() {
        GenericKubernetesResource live = new GenericKubernetesResource();
        Map<String, Object> components = new LinkedHashMap<>();
        components.put("queryNode", new LinkedHashMap<>(Map.of("replicas", 1)));
        live.setAdditionalProperty("spec", new LinkedHashMap<>(Map.of("mode", "cluster", "components", components)));

        var req = new MiddlewareScaleRequestDTO();
        req.setMode("CLUSTER");
        req.setReplicas(4);
        req.setCpuRequest("1");
        req.setCpuLimit("4");
        req.setMemoryRequest("4Gi");
        req.setMemoryLimit("8Gi");
        adapter.applyScale(live, MilvusMiddlewareAdapter.CRD, req);

        Map<String, Object> after = map(spec(live).get("components"));
        assertThat(map(after.get("queryNode"))).containsEntry("replicas", 4);
        assertThat(map(after.get("resources")).get("limits"))
                .isEqualTo(Map.of("cpu", "4", "memory", "8Gi"));
    }

    @Test
    void crProblemSurfacesStorageConditionMessage() {
        GenericKubernetesResource live = new GenericKubernetesResource();
        live.setAdditionalProperty("status", Map.of(
                "status", "Unhealthy",
                "conditions", List.of(
                        Map.of("type", "StorageReady", "status", "False",
                                "message", "no such bucket: milvus-bucket"))));

        assertThat(adapter.crProblem(live)).isEqualTo("StorageReady：no such bucket: milvus-bucket");
    }

    @Test
    void crProblemIgnoresEmptyMessagesAndHealthyStatus() {
        GenericKubernetesResource live = new GenericKubernetesResource();
        live.setAdditionalProperty("status", Map.of(
                "status", "Healthy",
                "conditions", List.of(Map.of("type", "MilvusReady", "status", "False", "message", ""))));

        assertThat(adapter.crProblem(live)).isNull();
    }
}
```

- [ ] **Step 3: 跑测试确认失败**

```bash
mvn -q -pl k8s-server -am test -Dtest=MilvusMiddlewareAdapterTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: 编译失败（`MilvusMiddlewareAdapter` 不存在）。若编译错误只是因为类未创建，符合预期。

- [ ] **Step 4: 实现适配器**

新建 `MilvusMiddlewareAdapter.java`：

```java
package com.coding.k8sserver.middleware;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.middleware.MiddlewareCreateRequestDTO;
import com.coding.common.models.middleware.MiddlewareScaleRequestDTO;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Milvus 适配器（milvus-operator，`milvus.io/v1beta1`）。
 *
 * <p>数据全部在外部对象存储里：CR 只声明 `dependencies.storage.external`，平台绝不在集群里拉起 minio/silo。
 * etcd 由 Operator 在租户命名空间内拉起（1~3 节点）。
 *
 * <p><b>两个必须成对写的开关</b>：`dependencies.storage.ssl.enabled` 供 operator 做 StorageReady 探测，
 * `config.milvus.minio.useSSL` 才是 Milvus 客户端的开关 —— 只写一个会"探测说通、实例连不上"。
 */
@Component
public class MilvusMiddlewareAdapter implements MiddlewareAdapter {

    static final String GROUP = "milvus.io";
    static final String VERSION = "v1beta1";
    static final String DEFAULT_VERSION = "2.6.25";
    static final String IMAGE_PREFIX = "milvusdb/milvus:v";
    static final String STORAGE_SECRET_SUFFIX = "-milvus-storage";
    static final int METRICS_PORT = 9091;
    static final String ETCD_STORAGE = "5Gi";
    static final String INSTANCE_LABEL = "platform.coding/middleware-instance";
    static final Map<String, Object> ETCD_RESOURCES = Map.of(
            "requests", Map.of("cpu", "100m", "memory", "256Mi"),
            "limits", Map.of("cpu", "500m", "memory", "512Mi"));

    static final CustomResourceDefinitionContext CRD = new CustomResourceDefinitionContext.Builder()
            .withGroup(GROUP).withVersion(VERSION).withKind("Milvus")
            .withPlural("milvuses").withScope("Namespaced").build();

    @Override
    public String code() {
        return "milvus";
    }

    /** Milvus 默认不开鉴权：平台不生成口令 Secret。 */
    @Override
    public boolean usesPlatformPassword() {
        return false;
    }

    @Override
    public String extraSecretName(String name) {
        return name + STORAGE_SECRET_SUFFIX;
    }

    @Override
    public Map<String, String> extraSecretData(MiddlewareCreateRequestDTO req) {
        if (isBlank(req.getObjectStorageAccessKey()) || isBlank(req.getObjectStorageSecretKey())) {
            return Map.of();
        }
        // 键名是 operator 的常量 AccessKey / SecretKey
        return Map.of("accesskey", req.getObjectStorageAccessKey(),
                "secretkey", req.getObjectStorageSecretKey());
    }

    @Override
    public List<CrSpec> buildCreate(MiddlewareCreateRequestDTO req) {
        if (isBlank(req.getObjectStorageEndpoint()) || isBlank(req.getObjectStorageBucket())) {
            throw new CloudPlatformException(EnumResponseType.ERROR,
                    "缺少对象存储参数（endpoint / bucket）");
        }
        boolean cluster = "CLUSTER".equals(req.getMode());
        boolean ssl = Boolean.TRUE.equals(req.getObjectStorageUseSsl());

        Map<String, Object> components = new LinkedHashMap<>();
        components.put("image", IMAGE_PREFIX + versionOf(req));
        components.put("resources", resourcesOf(req));
        if (cluster) {
            Map<String, Object> queryNode = new LinkedHashMap<>();
            queryNode.put("replicas", Math.max(1, req.getReplicas() == null ? 1 : req.getReplicas()));
            components.put("queryNode", queryNode);
        }

        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("mode", cluster ? "cluster" : "standalone");
        spec.put("components", components);
        if (!cluster) {
            spec.put("persistence", persistenceOf(req));
        }
        spec.put("dependencies", dependenciesOf(req, cluster));
        // spec.config 直接并进 milvus.yaml：minio 是顶层键，**不要**再包一层 milvus
        spec.put("config", Map.of("minio", Map.of(
                "bucketName", req.getObjectStorageBucket(),
                "useVirtualHost", false,   // SeaweedFS 只支持路径风格
                "useSSL", ssl)));

        GenericKubernetesResource res = new GenericKubernetesResource();
        res.setApiVersion(GROUP + "/" + VERSION);
        res.setKind("Milvus");
        ObjectMeta meta = new ObjectMeta();
        meta.setName(req.getName());
        meta.setNamespace(req.getNamespace());
        meta.setLabels(Map.of(
                "platform.coding/managed-by", "middleware",
                INSTANCE_LABEL, instanceLabel(req)));
        res.setMetadata(meta);
        res.setAdditionalProperty("spec", spec);
        return List.of(new CrSpec(CRD, res));
    }

    @Override
    public List<CustomResourceDefinitionContext> contexts() {
        return List.of(CRD);
    }

    @Override
    public void applyScale(GenericKubernetesResource live, CustomResourceDefinitionContext context,
                           MiddlewareScaleRequestDTO req) {
        boolean cluster = "CLUSTER".equals(req.getMode());
        if (!cluster && req.getReplicas() != null && req.getReplicas() != 1) {
            throw new CloudPlatformException(EnumResponseType.ERROR,
                    "单节点形态固定 1 个节点，要扩展请删除后按「集群」重建");
        }
        Map<String, Object> spec = specOf(live);
        Map<String, Object> components = asMap(spec.get("components"));
        if (notBlank(req.getCpuRequest()) || notBlank(req.getCpuLimit())
                || notBlank(req.getMemoryRequest()) || notBlank(req.getMemoryLimit())) {
            components.put("resources", resourcesOf(req));
        }
        if (cluster && req.getReplicas() != null) {
            Map<String, Object> queryNode = asMap(components.get("queryNode"));
            queryNode.put("replicas", Math.max(1, req.getReplicas()));
            components.put("queryNode", queryNode);
        }
        spec.put("components", components);
    }

    @Override
    public String statusSelector(String name, String kind) {
        return "app.kubernetes.io/instance=" + name + ",app.kubernetes.io/name=milvus";
    }

    @Override
    public MetricsSpec metricsSpec(String name, String kind, String instanceId) {
        return new MetricsSpec(METRICS_PORT, "/metrics", "http", statusSelector(name, kind));
    }

    @Override
    public Integer desiredPods(GenericKubernetesResource live, String kind) {
        Object components = specOf(live).get("components");
        if (!(components instanceof Map<?, ?> m)) {
            return null;
        }
        int sum = 0;
        boolean any = false;
        for (Object value : m.values()) {
            if (value instanceof Map<?, ?> c && c.get("replicas") instanceof Number n) {
                any = true;
                sum += n.intValue();
            }
        }
        return any && sum > 0 ? sum : null;
    }

    /**
     * Milvus 的 condition 是 EtcdReady / StorageReady / MsgStreamReady / MilvusReady，
     * 共享的 crFailureMessage 只认 Ready/Available —— 这里按根因优先级取第一条非空消息。
     */
    @Override
    public String crProblem(GenericKubernetesResource live) {
        if (live == null) {
            return null;
        }
        Object status = live.getAdditionalProperties().get("status");
        if (!(status instanceof Map<?, ?> st)) {
            return null;
        }
        // 先看条件：StorageReady/EtcdReady 的 message 才是根因（桶配错、etcd 起不来）
        if (st.get("conditions") instanceof List<?> conditions) {
            for (String type : List.of("StorageReady", "EtcdReady", "MsgStreamReady", "MilvusReady")) {
                for (Object c : conditions) {
                    if (!(c instanceof Map<?, ?> cm) || !type.equals(cm.get("type"))) {
                        continue;
                    }
                    if (!"False".equals(cm.get("status"))) {
                        continue;
                    }
                    Object msg = cm.get("message");
                    if (msg != null && !String.valueOf(msg).isBlank()) {
                        return type + "：" + String.valueOf(msg).trim();
                    }
                }
            }
        }
        Object health = st.get("status");
        if ("Unhealthy".equals(health) || "Stopped".equals(health)) {
            return "Milvus 状态 " + health;
        }
        return null;
    }

    // ==================== 内部 ====================

    /** "http://host:8333/x" / "host:8333" → "host:8333"（operator 自己按 useSSL 补默认端口）。 */
    static String hostPort(String endpoint) {
        String s = endpoint == null ? "" : endpoint.trim();
        int scheme = s.indexOf("://");
        if (scheme >= 0) {
            s = s.substring(scheme + 3);
        }
        int slash = s.indexOf('/');
        if (slash >= 0) {
            s = s.substring(0, slash);
        }
        return s;
    }

    static int etcdReplicas(MiddlewareCreateRequestDTO req) {
        Integer n = req.getEtcdReplicas();
        if (n == null) {
            return "CLUSTER".equals(req.getMode()) ? 3 : 1;
        }
        if (n < 1 || n > 3) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "etcd 节点数只能是 1~3");
        }
        return n;
    }

    private static Map<String, Object> persistenceOf(MiddlewareCreateRequestDTO req) {
        Map<String, Object> claimSpec = new LinkedHashMap<>();
        claimSpec.put("accessModes", List.of("ReadWriteOnce"));
        claimSpec.put("resources", Map.of("requests", Map.of("storage",
                isBlank(req.getLocalStorageSize()) ? "10Gi" : req.getLocalStorageSize())));
        if (!isBlank(req.getStorageClass())) {
            claimSpec.put("storageClassName", req.getStorageClass());
        }
        Map<String, Object> claim = new LinkedHashMap<>();
        // 平台标签：删实例（keepPvc=false）时按它回收这块 PVC —— operator 建的 PVC 没有 OwnerReference
        claim.put("labels", Map.of(INSTANCE_LABEL, instanceLabel(req)));
        claim.put("spec", claimSpec);

        Map<String, Object> persistence = new LinkedHashMap<>();
        persistence.put("enabled", true);
        persistence.put("persistentVolumeClaim", claim);
        return persistence;
    }

    private static Map<String, Object> dependenciesOf(MiddlewareCreateRequestDTO req, boolean cluster) {
        Map<String, Object> persistence = new LinkedHashMap<>();
        persistence.put("size", ETCD_STORAGE);
        persistence.put("labels", Map.of(INSTANCE_LABEL, instanceLabel(req)));
        if (!isBlank(req.getStorageClass())) {
            persistence.put("storageClass", req.getStorageClass());
        }
        Map<String, Object> etcdValues = new LinkedHashMap<>();
        etcdValues.put("replicaCount", etcdReplicas(req));
        etcdValues.put("persistence", persistence);
        etcdValues.put("resources", ETCD_RESOURCES);   // etcd chart 默认完全不写 resources，配额会拒

        Map<String, Object> storage = new LinkedHashMap<>();
        storage.put("external", true);
        storage.put("type", "S3");
        storage.put("endpoint", hostPort(req.getObjectStorageEndpoint()));
        storage.put("secretRef", req.getName() + STORAGE_SECRET_SUFFIX);
        storage.put("ssl", Map.of("enabled", Boolean.TRUE.equals(req.getObjectStorageUseSsl())));

        Map<String, Object> dependencies = new LinkedHashMap<>();
        if (cluster) {
            // 2.6 的 WAL 跑在 Milvus 进程内、直接写对象存储：不需要 PVC，也不拉 Pulsar/Kafka
            dependencies.put("msgStreamType", "woodpecker");
        }
        dependencies.put("etcd", Map.of("inCluster", Map.of("values", etcdValues)));
        dependencies.put("storage", storage);
        return dependencies;
    }

    private static Map<String, Object> resourcesOf(MiddlewareCreateRequestDTO req) {
        Map<String, Object> resources = new LinkedHashMap<>();
        resources.put("requests", nonNullMap("cpu", req.getCpuRequest(), "memory", req.getMemoryRequest()));
        resources.put("limits", nonNullMap("cpu", req.getCpuLimit(), "memory", req.getMemoryLimit()));
        return resources;
    }

    private static Map<String, Object> resourcesOf(MiddlewareScaleRequestDTO req) {
        Map<String, Object> resources = new LinkedHashMap<>();
        resources.put("requests", nonNullMap("cpu", req.getCpuRequest(), "memory", req.getMemoryRequest()));
        resources.put("limits", nonNullMap("cpu", req.getCpuLimit(), "memory", req.getMemoryLimit()));
        return resources;
    }

    private static Map<String, Object> nonNullMap(String k1, String v1, String k2, String v2) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (!isBlank(v1)) {
            m.put(k1, v1);
        }
        if (!isBlank(v2)) {
            m.put(k2, v2);
        }
        return m;
    }

    private static String versionOf(MiddlewareCreateRequestDTO req) {
        return isBlank(req.getVersion()) ? DEFAULT_VERSION : req.getVersion().trim();
    }

    private static String instanceLabel(MiddlewareCreateRequestDTO req) {
        return req.getInstanceId() == null ? "-" : req.getInstanceId();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> specOf(GenericKubernetesResource res) {
        Object spec = res.getAdditionalProperties().get("spec");
        return spec instanceof Map ? (Map<String, Object>) spec : new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : new LinkedHashMap<>();
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
```

- [ ] **Step 5: 跑测试确认通过**

```bash
mvn -q -pl k8s-server -am test -Dtest=MilvusMiddlewareAdapterTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: BUILD SUCCESS，16 tests passed。

- [ ] **Step 6: 提交**

```bash
git add platform-common/src/main/java/com/coding/common/models/middleware/MiddlewareCreateRequestDTO.java \
        platform-api/src/main/java/com/coding/platformapi/models/MiddlewareCreateRequest.java \
        k8s-server/src/main/java/com/coding/k8sserver/middleware/MilvusMiddlewareAdapter.java \
        k8s-server/src/test/java/com/coding/k8sserver/middleware/MilvusMiddlewareAdapterTest.java
git commit -m "feat(milvus): 适配器与创建契约（两形态 + 外部对象存储）"
```

---

## Task 3: 对象存储凭据 Secret（k8s-server 侧接线）

**Files:**
- Modify: `k8s-server/src/main/java/com/coding/k8sserver/middleware/MiddlewareInstanceService.java:365`（create）、`:530`（delete）、`:712`（Secret 写入）

**Interfaces:**
- Consumes: `MiddlewareAdapter.extraSecretName(String)` / `extraSecretData(MiddlewareCreateRequestDTO)`（Task 2 已加，Milvus 已实现并单测）
- Produces: `MiddlewareInstanceService.create` 在写平台口令 Secret 之后、建 CR 之前写这个 Secret，失败回滚时删；`delete` 在 `keepPvc=false` 时删它

- [ ] **Step 1: create 里写 Secret**

`MiddlewareInstanceService.create(...)` 把 try 块改成正序：口令 Secret → 额外 Secret → CR：

```java
        MiddlewareAdapter adapter = adapter(req.getCode());
        List<MiddlewareAdapter.CrSpec> created = new ArrayList<>();
        String extraSecret = adapter.extraSecretName(req.getName());
        try {
            if (adapter.usesPlatformPassword()) {
                writeSecret(client, req);
            }
            if (extraSecret != null) {
                Map<String, String> data = adapter.extraSecretData(req);
                if (data.isEmpty()) {
                    throw new CloudPlatformException(EnumResponseType.ERROR, "缺少对象存储凭据");
                }
                writeDataSecret(client, req.getNamespace(), extraSecret, data, req.getInstanceId());
            }
            for (MiddlewareAdapter.CrSpec spec : adapter.buildCreate(req)) {
                client.genericKubernetesResources(spec.context())
                        .inNamespace(req.getNamespace())
                        .resource(spec.resource())
                        .create();
                created.add(spec);
            }
            createMetricsResources(client, adapter, req, created);
        } catch (RuntimeException e) {
            // 部分失败回滚：SENTINEL 是两个 CR，第二个失败时不能把第一个和 Secret 留在集群里
            created.forEach(s -> deleteQuietly(client, s.context(), req.getNamespace(),
                    s.resource().getMetadata().getName()));
            if (adapter.usesPlatformPassword()) {
                deleteSecretQuietly(client, req.getNamespace(), secretName(req));
            }
            if (extraSecret != null) {
                deleteSecretQuietly(client, req.getNamespace(), extraSecret);
            }
            throw e;
        }
```

- [ ] **Step 2: delete 里删 Secret**

在 `delete(...)` 的 `if (!req.isKeepPvc()) { ... }` 块内、`deleteSecretQuietly(... secretName(req))` 之后追加：

```java
            String extraSecret = adapter.extraSecretName(req.getName());
            if (extraSecret != null) {
                deleteSecretQuietly(client, req.getNamespace(), extraSecret);
            }
```

- [ ] **Step 3: 加 writeDataSecret 辅助**

在 `writeSecret(...)` 之后追加：

```java
    /** 写一组字符串键值 Secret（带平台受管标签，便于跟随实例清理）。 */
    private void writeDataSecret(KubernetesClient client, String namespace, String name,
                                 Map<String, String> data, String instanceId) {
        SecretBuilder builder = new SecretBuilder()
                .withNewMetadata()
                .withName(name)
                .withNamespace(namespace)
                .addToLabels("platform.coding/managed-by", "middleware")
                .addToLabels("platform.coding/middleware-instance", instanceId == null ? "-" : instanceId)
                .endMetadata();
        data.forEach(builder::addToStringData);
        client.secrets().inNamespace(namespace).resource(builder.build()).createOrReplace();
    }
```

- [ ] **Step 4: 编译并跑相关测试**

```bash
mvn -q -pl k8s-server -am test -Dtest='MilvusMiddlewareAdapterTest,MiddlewareInstanceServiceTest' -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: BUILD SUCCESS。Secret 的真实写入由 Task 10 的真集群验收覆盖（本仓库既有做法：fabric8 深链 mock 不可靠，只对静态/纯函数写单测）。

- [ ] **Step 5: 提交**

```bash
git add k8s-server/src/main/java/com/coding/k8sserver/middleware/MiddlewareAdapter.java \
        k8s-server/src/main/java/com/coding/k8sserver/middleware/MiddlewareInstanceService.java
git commit -m "feat(milvus): 创建/删除实例时同步对象存储凭据 Secret"
```

---

## Task 4: 状态判定补 Milvus 的 CR 条件

**Files:**
- Modify: `k8s-server/src/main/java/com/coding/k8sserver/middleware/MiddlewareInstanceService.java:581-583`

**Interfaces:**
- Consumes: `crFailureMessage(live)`、`MiddlewareStatusEvaluator`、`MiddlewareAdapter.crProblem(GenericKubernetesResource)`（Task 2 已加，Milvus 已实现并单测）
- Produces: 状态判定的失败消息在共享规则为空时取适配器的 `crProblem`

- [ ] **Step 1: 接到状态判定**

`MiddlewareInstanceService` 的状态循环里，把

```java
            var verdict = MiddlewareStatusEvaluator.evaluate(pods.size(), ready,
                    adapter.desiredPods(live, live.getKind()),
                    crInitializing(live), crFailureMessage(live));
```

改成：

```java
            String failure = crFailureMessage(live);
            if (failure == null) {
                // Milvus 的条件是 StorageReady/EtcdReady/...，共享规则匹配不到
                failure = adapter.crProblem(live);
            }
            var verdict = MiddlewareStatusEvaluator.evaluate(pods.size(), ready,
                    adapter.desiredPods(live, live.getKind()),
                    crInitializing(live), failure);
```

- [ ] **Step 2: 跑测试**

```bash
mvn -q -pl k8s-server -am test -Dtest='MilvusMiddlewareAdapterTest,MiddlewareStatusEvaluatorTest' -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: BUILD SUCCESS（`crProblem` 的两个用例在 Task 2 已写）。

- [ ] **Step 3: 提交**

```bash
git add k8s-server/src/main/java/com/coding/k8sserver/middleware/MiddlewareAdapter.java \
        k8s-server/src/main/java/com/coding/k8sserver/middleware/MiddlewareInstanceService.java
git commit -m "feat(milvus): 状态判定接出 StorageReady/EtcdReady 根因"
```

---

## Task 5: platform-api 目录项、指标、连接方式、表单可选项

**Files:**
- Modify: `platform-api/src/main/java/com/coding/platformapi/middleware/MiddlewareCatalog.java:299`
- Modify: `platform-api/src/main/java/com/coding/platformapi/services/MiddlewareService.java:141`（formOptions）、`:352`（connection）、`:104`（构造器）
- Modify: `platform-common/src/main/java/com/coding/common/models/middleware/MiddlewareFormOptionsDTO.java`
- Modify: `platform-api/src/main/java/com/coding/platformapi/services/ObjectStorageService.java`（新增 `s3Endpoint`）
- Test: `platform-api/src/test/java/com/coding/platformapi/services/MiddlewareServiceTest.java`

**Interfaces:**
- Consumes: `ClusterComponentService.seaweedInstalled(String clusterId)` → `boolean`；`ObjectStorageService.s3Endpoint(String clusterId)` → `String`（含 scheme）
- Produces:
  - `MiddlewareCatalog.Item("milvus", ...)`，`versions = ["2.6.25"]`
  - `MiddlewareFormOptionsDTO.objectStorageAvailable` → `boolean`
  - `MiddlewareService.connection` 的 `case "milvus"`

- [ ] **Step 1: 写失败测试**

`MiddlewareServiceTest` 里先补构造器依赖（见 Step 2），再加：

```java
    @Test
    void catalogListsMilvusWithModesAndVersion() {
        when(client.operatorStatus(any())).thenReturn(List.of());
        when(instances.selectByCluster("c1", null)).thenReturn(List.of());

        var item = service.catalog("c1", null).stream()
                .filter(i -> "milvus".equals(i.getCode())).findFirst().orElseThrow();

        assertThat(item.isChartBacked()).isFalse();
        assertThat(item.getVersions()).containsExactly("2.6.25");
        assertThat(item.getModes()).extracting(m -> m.getMode())
                .containsExactly("STANDALONE", "CLUSTER");
    }

    @Test
    void milvusConnectionPointsAtServicePortsAndStorageSecret() {
        PlatformMiddlewareInstance instance = new PlatformMiddlewareInstance();
        instance.setId("mwi_1");
        instance.setName("mw-milvus-abc12345");
        instance.setNamespace("demo");
        instance.setCode("milvus");
        instance.setMode("CLUSTER");
        instance.setTenantId("t1");
        when(instances.selectByPrimaryKey("mwi_1")).thenReturn(instance);

        var conn = service.connection("mwi_1", "t1");

        assertThat(conn.getEndpoints()).extracting(MiddlewareConnectionDTO.Line::value)
                .contains("mw-milvus-abc12345-milvus.demo.svc.cluster.local:19530",
                        "mw-milvus-abc12345-milvus.demo.svc.cluster.local:9091");
        assertThat(conn.getCredentials()).extracting(MiddlewareConnectionDTO.Line::value)
                .anyMatch(v -> v.contains("mw-milvus-abc12345-milvus-storage"));
    }

    @Test
    void formOptionsReportsObjectStorageAvailability() {
        when(allocations.listByCluster("c1")).thenReturn(List.of());
        when(components.seaweedInstalled("c1")).thenReturn(true);

        assertThat(service.formOptions("c1", null).isObjectStorageAvailable()).isTrue();

        when(components.seaweedInstalled("c1")).thenThrow(new RuntimeException("boom"));
        assertThat(service.formOptions("c1", null).isObjectStorageAvailable()).isFalse();
    }
```

- [ ] **Step 2: 改构造器与依赖**

`MiddlewareService` 增加两个字段并在构造器末尾接收：

```java
    private final ObjectStorageService objectStorage;
    private final ClusterComponentService components;
```

```java
    public MiddlewareService(..., MetricsService metrics,
                             ObjectStorageService objectStorage,
                             ClusterComponentService components) {
        ...
        this.objectStorage = objectStorage;
        this.components = components;
    }
```

同步改测试的构造：

```java
    private final ObjectStorageService objectStorage = mock(ObjectStorageService.class);
    private final ClusterComponentService components = mock(ClusterComponentService.class);

    private final MiddlewareService service = new MiddlewareService(
            instances, operations, operators, client, catalog,
            JsonMapper.builder().build(), k8s, allocations, cache, metricsService,
            objectStorage, components);
```

- [ ] **Step 3: 加目录项与指标**

`MiddlewareCatalog` 里追加（放在 `RABBITMQ_METRICS` 之后）：

```java
    private static final List<Mode> MILVUS_MODES = List.of(
            new Mode("STANDALONE", "单节点", 1, 1, "节点数",
                    "开发/测试；1 个 Milvus Pod + 内置 etcd（固定 1 个 Milvus 节点）"),
            new Mode("CLUSTER", "集群", 1, null, "查询节点数",
                    "proxy/mixcoord/datanode/querynode/streamingnode 各 1 个；WAL 用内嵌 woodpecker"));

    /** Milvus 各组件自带 Prometheus（9091 /metrics）；名字取自 v2.6.25 的 pkg/metrics */
    private static final List<MetricDef> MILVUS_METRICS = List.of(
            MetricDef.chart("req", "请求/秒", "次/秒",
                    "sum(rate(milvus_proxy_req_count{cluster_name=\"%s\",namespace=\"%s\",pod=~\"%s\"}[2m]))"),
            MetricDef.stat("latency", "平均延迟", "秒",
                    "sum(rate(milvus_proxy_req_latency_sum{cluster_name=\"%s\",namespace=\"%s\",pod=~\"%s\"}[2m]))"
                            + " / clamp_min(sum(rate(milvus_proxy_req_latency_count{cluster_name=\"%s\",namespace=\"%s\",pod=~\"%s\"}[2m])), 0.001)"),
            MetricDef.chart("insert_vectors", "写入向量/秒", "个/秒",
                    "sum(rate(milvus_proxy_insert_vectors_count{cluster_name=\"%s\",namespace=\"%s\",pod=~\"%s\"}[2m]))"),
            MetricDef.chart("delete_vectors", "删除向量/秒", "个/秒",
                    "sum(rate(milvus_proxy_delete_vectors_count{cluster_name=\"%s\",namespace=\"%s\",pod=~\"%s\"}[2m]))"),
            MetricDef.stat("collections", "集合数", "个",
                    "max(milvus_rootcoord_collection_num{cluster_name=\"%s\",namespace=\"%s\",pod=~\"%s\"})"),
            MetricDef.stat("entities", "实体数", "个",
                    "max(milvus_rootcoord_entity_num{cluster_name=\"%s\",namespace=\"%s\",pod=~\"%s\"})"),
            MetricDef.stat("qn_entities", "查询节点实体数", "个",
                    "sum(milvus_querynode_entity_num{cluster_name=\"%s\",namespace=\"%s\",pod=~\"%s\"})"),
            MetricDef.chart("sq_req", "查询/秒", "次/秒",
                    "sum(rate(milvus_querynode_sq_req_count{cluster_name=\"%s\",namespace=\"%s\",pod=~\"%s\"}[2m]))"),
            MetricDef.chart("rows", "行写入/秒", "行/秒",
                    "sum(rate(milvus_datanode_msg_rows_count{cluster_name=\"%s\",namespace=\"%s\",pod=~\"%s\"}[2m]))"),
            MetricDef.chart("flush_bytes", "刷盘速率", "字节/秒",
                    "sum(rate(milvus_datanode_flushed_data_size{cluster_name=\"%s\",namespace=\"%s\",pod=~\"%s\"}[2m]))"));
```

并在 `ITEMS` 末尾追加：

```java
            new Item("milvus", "Milvus", "向量数据库（milvus-operator）", MILVUS_MODES,
                    List.of("2.6.25"), MILVUS_METRICS)
```

- [ ] **Step 4: 加连接方式与 form-options**

`connection(...)` 的 `switch` 里追加：

```java
            case "milvus" -> {
                endpoints.add(line("gRPC", svc(ns, name + "-milvus", 19530)));
                endpoints.add(line("指标 / 健康检查", svc(ns, name + "-milvus", 9091)));
                credentials.add(line("对象存储凭据",
                        "Secret " + name + "-milvus-storage，键 accesskey / secretkey"));
                note = "Milvus 默认未开启鉴权；对外暴露前请用命名空间或网格策略控制访问。";
            }
```

`formOptions(...)` 在 `storageClasses` 之后追加：

```java
        try {
            out.setObjectStorageAvailable(components.seaweedInstalled(clusterId));
        } catch (RuntimeException e) {
            out.setObjectStorageAvailable(false);
        }
```

`MiddlewareFormOptionsDTO` 追加：

```java
    /** 本集群是否已装对象存储组件（Milvus 的「平台对象存储」选项要它） */
    private boolean objectStorageAvailable;
```

- [ ] **Step 5: 给 ObjectStorageService 暴露地址**

`ObjectStorageService` 里追加公开方法：

```java
    /** 本集群对象存储的 S3 地址（含 scheme；未装组件时抛出）。 */
    public String s3Endpoint(String clusterId) {
        return endpoint(clusterId);
    }
```

- [ ] **Step 6: 跑测试**

```bash
mvn -q -pl platform-api -am test -Dtest=MiddlewareServiceTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: BUILD SUCCESS。

- [ ] **Step 7: 提交**

```bash
git add platform-api/src/main/java/com/coding/platformapi/middleware/MiddlewareCatalog.java \
        platform-api/src/main/java/com/coding/platformapi/services/MiddlewareService.java \
        platform-api/src/main/java/com/coding/platformapi/services/ObjectStorageService.java \
        platform-common/src/main/java/com/coding/common/models/middleware/MiddlewareFormOptionsDTO.java \
        platform-api/src/test/java/com/coding/platformapi/services/MiddlewareServiceTest.java
git commit -m "feat(milvus): 商店目录项、业务指标、连接方式与表单可选项"
```

---

## Task 6: 创建/删除时管理平台对象存储桶

**Files:**
- Modify: `platform-api/src/main/java/com/coding/platformapi/services/MiddlewareService.java:248`（create）、`:636`（delete）、`:789`（toSpecJson）
- Test: `platform-api/src/test/java/com/coding/platformapi/services/MiddlewareServiceTest.java`

**Interfaces:**
- Consumes: `ObjectStorageService.createBucket(ObjectStorageCreateBucket, String)` → `ObjectStorageBucketView`；`ObjectStorageService.issueCredential(ObjectStorageBucketRef, String)` → `ObjectCredentialDTO`；`ObjectStorageService.deleteBucket(ObjectStorageBucketRef, String)`；`ObjectStorageService.s3Endpoint(String)`；`ClusterComponentService.seaweedInstalled(String)`
- Produces: `MiddlewareService.create` 在 PLATFORM 下先建桶、把 AK/SK 塞进 DTO；`MiddlewareService.delete` 在 `keepPvc=false` 时删桶

- [ ] **Step 1: 写失败测试**

```java
    private MiddlewareCreateRequest milvusRequest(String storageType) {
        MiddlewareCreateRequest r = new MiddlewareCreateRequest();
        r.setClusterId("c1");
        r.setCode("milvus");
        r.setMode("STANDALONE");
        r.setNamespace("demo");
        r.setReplicas(1);
        r.setVersion("2.6.25");
        r.setCpuRequest("500m");
        r.setCpuLimit("2");
        r.setMemoryRequest("2Gi");
        r.setMemoryLimit("4Gi");
        r.setStorageSize("20Gi");
        r.setLocalStorageSize("10Gi");
        r.setEtcdReplicas(1);
        r.setObjectStorageType(storageType);
        if ("CUSTOM".equals(storageType)) {
            r.setObjectStorageEndpoint("s3.example.com:9000");
            r.setObjectStorageBucket("my-bucket");
            r.setObjectStorageAccessKey("AK");
            r.setObjectStorageSecretKey("SK");
        }
        return r;
    }

    @Test
    void milvusPlatformStorageCreatesBucketAndPassesCredentials() {
        when(client.operatorStatus(any())).thenReturn(List.of(status("milvus", true, true)));
        when(client.create(anyString(), any())).thenReturn(true);
        when(components.seaweedInstalled("c1")).thenReturn(true);
        when(objectStorage.s3Endpoint("c1"))
                .thenReturn("http://platform-seaweedfs-s3.platform-storage:8333");

        ObjectStorageBucketView bucket = new ObjectStorageBucketView();
        bucket.setId("obk_1");
        bucket.setBucket("t-1234abcd-milvus-mw-milvus-abc12345");
        when(objectStorage.createBucket(any(), anyString())).thenReturn(bucket);
        when(objectStorage.issueCredential(any(), anyString()))
                .thenReturn(new ObjectCredentialDTO("AK_PLATFORM", "SK_PLATFORM"));

        service.create(milvusRequest("PLATFORM"), "admin", "t1");

        ArgumentCaptor<ObjectStorageCreateBucket> bucketBody =
                ArgumentCaptor.forClass(ObjectStorageCreateBucket.class);
        verify(objectStorage).createBucket(bucketBody.capture(), anyString());
        assertThat(bucketBody.getValue().getTenantId()).isEqualTo("t1");
        assertThat(bucketBody.getValue().getName()).startsWith("milvus-mw-milvus-");
        assertThat(bucketBody.getValue().getQuotaBytes()).isEqualTo(20L * 1024 * 1024 * 1024);

        ArgumentCaptor<MiddlewareCreateRequestDTO> sent =
                ArgumentCaptor.forClass(MiddlewareCreateRequestDTO.class);
        verify(client).create(anyString(), sent.capture());
        assertThat(sent.getValue().getObjectStorageEndpoint())
                .isEqualTo("platform-seaweedfs-s3.platform-storage:8333");
        assertThat(sent.getValue().getObjectStorageBucket()).isEqualTo(bucket.getBucket());
        assertThat(sent.getValue().getObjectStorageAccessKey()).isEqualTo("AK_PLATFORM");
        assertThat(sent.getValue().getObjectStorageSecretKey()).isEqualTo("SK_PLATFORM");

        ArgumentCaptor<PlatformMiddlewareInstance> saved =
                ArgumentCaptor.forClass(PlatformMiddlewareInstance.class);
        verify(instances).insert(saved.capture());
        assertThat(saved.getValue().getSpecJson())
                .contains("obk_1")
                .doesNotContain("AK_PLATFORM")
                .doesNotContain("SK_PLATFORM");
    }

    @Test
    void milvusCreateFailureDeletesTheBucket() {
        when(client.operatorStatus(any())).thenReturn(List.of(status("milvus", true, true)));
        when(client.create(anyString(), any())).thenThrow(new CloudPlatformException(
                com.coding.common.exception.EnumResponseType.ERROR, "配额不足"));
        when(components.seaweedInstalled("c1")).thenReturn(true);
        when(objectStorage.s3Endpoint("c1"))
                .thenReturn("http://platform-seaweedfs-s3.platform-storage:8333");
        ObjectStorageBucketView bucket = new ObjectStorageBucketView();
        bucket.setId("obk_1");
        bucket.setBucket("t-1234abcd-milvus-mw-milvus-abc12345");
        when(objectStorage.createBucket(any(), anyString())).thenReturn(bucket);
        when(objectStorage.issueCredential(any(), anyString()))
                .thenReturn(new ObjectCredentialDTO("AK", "SK"));

        assertThatThrownBy(() -> service.create(milvusRequest("PLATFORM"), "admin", "t1"))
                .hasMessageContaining("配额");

        ArgumentCaptor<ObjectStorageBucketRef> ref = ArgumentCaptor.forClass(ObjectStorageBucketRef.class);
        verify(objectStorage).deleteBucket(ref.capture(), anyString());
        assertThat(ref.getValue().getBucketId()).isEqualTo("obk_1");
    }

    @Test
    void createRejectsCustomStorageWithoutCredentials() {
        when(client.operatorStatus(any())).thenReturn(List.of(status("milvus", true, true)));
        var r = milvusRequest("CUSTOM");
        r.setObjectStorageSecretKey("");

        assertThatThrownBy(() -> service.create(r, "admin", "t1"))
                .hasMessageContaining("AccessKey");
    }

    @Test
    void createRejectsPlatformStorageWithoutTenant() {
        when(client.operatorStatus(any())).thenReturn(List.of(status("milvus", true, true)));

        assertThatThrownBy(() -> service.create(milvusRequest("PLATFORM"), "admin", null))
                .hasMessageContaining("租户");
    }

    @Test
    void deleteWithoutKeepPvcDeletesPlatformBucket() {
        PlatformMiddlewareInstance instance = new PlatformMiddlewareInstance();
        instance.setId("mwi_1");
        instance.setClusterId("c1");
        instance.setNamespace("demo");
        instance.setCode("milvus");
        instance.setName("mw-milvus-abc12345");
        instance.setTenantId("t1");
        instance.setSpecJson("{\"objectStorageType\":\"PLATFORM\",\"objectStorageBucketId\":\"obk_1\"}");
        when(instances.selectByPrimaryKey("mwi_1")).thenReturn(instance);
        when(client.delete(anyString(), any())).thenReturn(true);

        var req = new MiddlewareDeleteRequest();
        req.setInstanceId("mwi_1");
        req.setKeepPvc(false);
        service.delete(req, "admin", "t1");

        ArgumentCaptor<ObjectStorageBucketRef> ref = ArgumentCaptor.forClass(ObjectStorageBucketRef.class);
        verify(objectStorage).deleteBucket(ref.capture(), anyString());
        assertThat(ref.getValue().getBucketId()).isEqualTo("obk_1");
    }

    @Test
    void deleteWithKeepPvcKeepsBucket() {
        PlatformMiddlewareInstance instance = new PlatformMiddlewareInstance();
        instance.setId("mwi_1");
        instance.setClusterId("c1");
        instance.setNamespace("demo");
        instance.setCode("milvus");
        instance.setName("mw-milvus-abc12345");
        instance.setTenantId("t1");
        instance.setSpecJson("{\"objectStorageType\":\"PLATFORM\",\"objectStorageBucketId\":\"obk_1\"}");
        when(instances.selectByPrimaryKey("mwi_1")).thenReturn(instance);
        when(client.delete(anyString(), any())).thenReturn(true);

        var req = new MiddlewareDeleteRequest();
        req.setInstanceId("mwi_1");
        req.setKeepPvc(true);
        service.delete(req, "admin", "t1");

        verify(objectStorage, never()).deleteBucket(any(), anyString());
    }
```

> 检查 `MiddlewareDeleteRequest` 的 setter 名（`setInstanceId` / `setKeepPvc`）——若字段是 `boolean keepPvc` 则用 `setKeepPvc`；若 lombok 生成 `isKeepPvc` 也兼容。

- [ ] **Step 2: 跑测试确认失败**

```bash
mvn -q -pl platform-api -am test -Dtest=MiddlewareServiceTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: 测试失败（没有建桶/删桶逻辑）。

- [ ] **Step 3: 加 StorageGrant 与准备逻辑**

先补 import：`ObjectStorageService` / `ClusterComponentService` 同包不用导；`ObjectStorageCreateBucket`、`ObjectStorageBucketView`、`ObjectStorageBucketRef`（`com.coding.platformapi.models`）与 `ObjectCredentialDTO`（`com.coding.common.models.objectstorage`）要导。

`MiddlewareService` 内部（类末尾）加一个私有 record：

```java
    /** Milvus 外部对象存储的下发参数（AK/SK 只在内存里过一遍，不落库）。 */
    private record StorageGrant(String type, String endpoint, String bucket, String bucketId,
                                String accessKey, String secretKey, boolean useSsl) {
    }
```

新增方法：

```java
    /** Milvus 的对象存储：PLATFORM 建桶 + 发凭据，CUSTOM 直接用请求里的参数。 */
    private StorageGrant prepareObjectStorage(MiddlewareCreateRequest req, String operator,
                                              String tenantId, String name) {
        String type = req.getObjectStorageType();
        if (isBlank(type)) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "请选择对象存储来源");
        }
        if ("CUSTOM".equals(type)) {
            if (isBlank(req.getObjectStorageEndpoint()) || isBlank(req.getObjectStorageBucket())
                    || isBlank(req.getObjectStorageAccessKey()) || isBlank(req.getObjectStorageSecretKey())) {
                throw new CloudPlatformException(EnumResponseType.ERROR,
                        "自带 S3 需要填 endpoint / 桶名 / AccessKey / SecretKey");
            }
            return new StorageGrant(type, hostPort(req.getObjectStorageEndpoint()), req.getObjectStorageBucket(), null,
                    req.getObjectStorageAccessKey(), req.getObjectStorageSecretKey(),
                    Boolean.TRUE.equals(req.getObjectStorageUseSsl()));
        }
        if (!"PLATFORM".equals(type)) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "不支持的对象存储来源：" + type);
        }
        if (isBlank(tenantId)) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "平台对象存储的桶必须归属某个租户，请先选择租户");
        }
        if (!components.seaweedInstalled(req.getClusterId())) {
            throw new CloudPlatformException(EnumResponseType.ERROR,
                    "对象存储组件尚未安装：请先在「集群组件」里安装对象存储");
        }
        ObjectStorageCreateBucket body = new ObjectStorageCreateBucket();
        body.setClusterId(req.getClusterId());
        body.setTenantId(tenantId);
        body.setName("milvus-" + name);
        body.setDisplayName("Milvus " + name);
        long quota = quantityBytes(req.getStorageSize());
        // 0 = 没填/识别不了；createBucket 对 <=0 会直接拒，所以转成 null（= 桶不限容量）
        body.setQuotaBytes(quota <= 0 ? null : quota);
        ObjectStorageBucketView bucket = objectStorage.createBucket(body, operator);
        ObjectStorageBucketRef ref = new ObjectStorageBucketRef();
        ref.setBucketId(bucket.getId());
        ObjectCredentialDTO credential = objectStorage.issueCredential(ref, operator);
        String raw = objectStorage.s3Endpoint(req.getClusterId());
        // CR 里的 endpoint 只能是 host:port（operator 自己按 useSSL 补默认端口）；scheme 只用来定 TLS
        return new StorageGrant(type, hostPort(raw), bucket.getBucket(), bucket.getId(),
                credential.getAccessKey(), credential.getSecretKey(), raw.startsWith("https://"));
    }

    /** "http://host:8333/x" / "host:8333" → "host:8333"（与 k8s-server 适配器的规整规则一致）。 */
    static String hostPort(String endpoint) {
        String s = endpoint == null ? "" : endpoint.trim();
        int scheme = s.indexOf("://");
        if (scheme >= 0) {
            s = s.substring(scheme + 3);
        }
        int slash = s.indexOf('/');
        if (slash >= 0) {
            s = s.substring(0, slash);
        }
        return s;
    }

    /** "20Gi" / "512Mi" → 字节（桶配额要 long）；识别不了返回 0（= 不限）。 */
    static long quantityBytes(String quantity) {
        if (isBlank(quantity)) {
            return 0;
        }
        String s = quantity.trim();
        long mul = 1;
        if (s.endsWith("Gi")) {
            mul = 1024L * 1024 * 1024;
            s = s.substring(0, s.length() - 2);
        } else if (s.endsWith("Mi")) {
            mul = 1024L * 1024;
            s = s.substring(0, s.length() - 2);
        } else if (s.endsWith("Ki")) {
            mul = 1024L;
            s = s.substring(0, s.length() - 2);
        }
        try {
            return (long) (Double.parseDouble(s.trim()) * mul);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void deleteBucketQuietly(String bucketId, String operator) {
        try {
            ObjectStorageBucketRef ref = new ObjectStorageBucketRef();
            ref.setBucketId(bucketId);
            objectStorage.deleteBucket(ref, operator);
        } catch (RuntimeException e) {
            log.warn("删除 Milvus 对象存储桶 {} 失败（需在对象存储页手工清理）：{}", bucketId, e.getMessage());
        }
    }
```

> `quantityBytes` 识别不了时返回 0，上面已经转成 `null`（= 桶不限容量），不会把建桶打挂。

- [ ] **Step 4: 改 create 流程**

在 `create(...)` 里，`String name = "mw-" + ...;` 之后插入：

```java
        // Milvus：对象存储只能是外部。PLATFORM 要先把桶和凭据准备好，k8s-server 才拿得到 AK/SK
        StorageGrant grant = "milvus".equals(req.getCode())
                ? prepareObjectStorage(req, operator, tenantId, name) : null;
```

`instance.setSpecJson(toSpecJson(req));` 改成 `instance.setSpecJson(toSpecJson(req, grant));`

DTO 组装处追加：

```java
            dto.setEtcdReplicas(req.getEtcdReplicas());
            dto.setLocalStorageSize(req.getLocalStorageSize());
            if (grant != null) {
                dto.setObjectStorageType(grant.type());
                dto.setObjectStorageEndpoint(grant.endpoint());
                dto.setObjectStorageBucket(grant.bucket());
                dto.setObjectStorageAccessKey(grant.accessKey());
                dto.setObjectStorageSecretKey(grant.secretKey());
                dto.setObjectStorageUseSsl(grant.useSsl());
            }
```

catch 块里追加（在软删台账之前）：

```java
            if (grant != null && grant.bucketId() != null) {
                deleteBucketQuietly(grant.bucketId(), operator);
            }
```

- [ ] **Step 5: toSpecJson 记录桶信息**

改成重载：

```java
    private String toSpecJson(MiddlewareCreateRequest req, StorageGrant grant) {
        Map<String, Object> spec = new LinkedHashMap<>();
        ... 现有字段 ...
        if (grant != null) {
            spec.put("etcdReplicas", req.getEtcdReplicas());
            spec.put("localStorageSize", req.getLocalStorageSize());
            spec.put("objectStorageType", grant.type());
            spec.put("objectStorageBucket", grant.bucket());
            spec.put("objectStorageBucketId", grant.bucketId());
            spec.put("objectStorageEndpoint", grant.endpoint());
            // AK/SK 不落库
        }
        return jsonMapper.writeValueAsString(spec);
    }
```

（把原 `toSpecJson(req)` 改为调用 `toSpecJson(req, null)`，避免其它调用点编译失败。）

- [ ] **Step 6: 删除时回收桶**

`delete(...)` 里 `client.delete(...)` 成功之后追加：

```java
            Map<String, Object> spec = parseSpec(instance.getSpecJson());
            if (!req.isKeepPvc() && spec.get("objectStorageBucketId") instanceof String bucketId
                    && !bucketId.isBlank()) {
                deleteBucketQuietly(bucketId, operator);
            }
```

- [ ] **Step 7: 加日志器**

类上补 `@lombok.extern.slf4j.Slf4j`（若类上已有其它注解，放在 `@Service` 旁）。

- [ ] **Step 8: 跑测试**

```bash
mvn -q -pl platform-api -am test -Dtest=MiddlewareServiceTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: BUILD SUCCESS。

- [ ] **Step 9: 提交**

```bash
git add platform-api/src/main/java/com/coding/platformapi/services/MiddlewareService.java \
        platform-api/src/test/java/com/coding/platformapi/services/MiddlewareServiceTest.java
git commit -m "feat(milvus): 创建/删除实例时管理平台对象存储桶与凭据"
```

---

## Task 7: 前端表单、图标与详情只读行

**Files:**
- Modify: `platform-web/src/components/middleware/middlewareForms.ts`
- Modify: `platform-web/src/components/middleware/MiddlewareIcon.vue`
- Modify: `platform-web/src/types.ts:2559`（`MiddlewareFormOptions`）、`:1030` 附近的 create payload 类型在 `api/index.ts:1026`
- Modify: `platform-web/src/api/index.ts:1026`（create 入参）
- Modify: `platform-web/src/views/MiddlewareStoreView.vue`
- Modify: `platform-web/src/views/MiddlewareDetailView.vue`

**Interfaces:**
- Consumes: `MiddlewareFormOptions.objectStorageAvailable`、`MiddlewareCatalogItem.versions`
- Produces: 创建请求带 `etcdReplicas` / `objectStorageType` / `objectStorageEndpoint` / `objectStorageBucket` / `objectStorageAccessKey` / `objectStorageSecretKey` / `objectStorageUseSsl` / `localStorageSize`

- [ ] **Step 1: 表单字段**

`middlewareForms.ts` 在 `MIDDLEWARE_FIELDS` 末尾追加：

```ts
  milvus: [
    {
      key: 'replicas', label: '节点数', type: 'number', unit: 'count', default: 1, min: 1, max: 9,
    },
    { key: 'cpuRequest', label: 'CPU 请求', type: 'number', unit: 'cpu', default: 0.5, step: 0.1, min: 0.1 },
    { key: 'cpuLimit', label: 'CPU 上限', type: 'number', unit: 'cpu', default: 2, step: 0.1, min: 0.1 },
    { key: 'memoryRequest', label: '内存请求', type: 'number', unit: 'size', unitDefault: 'Gi', default: 2 },
    { key: 'memoryLimit', label: '内存上限', type: 'number', unit: 'size', unitDefault: 'Gi', default: 4 },
    {
      key: 'localStorageSize', label: '本地数据盘', type: 'number', unit: 'size', unitDefault: 'Gi',
      default: 10, min: 1, help: '单节点形态的 WAL/临时目录；集群形态不使用',
    },
    {
      key: 'storageSize', label: '对象存储容量', type: 'number', unit: 'size', unitDefault: 'Gi',
      default: 20, min: 1, help: '仅「平台对象存储」生效（作为桶配额）；自带 S3 时忽略',
    },
  ],
```

- [ ] **Step 2: 图标**

`MiddlewareIcon.vue` 的 `KNOWN` 数组里，在 `elasticsearch` 那一行之后追加：

```ts
  { keys: ['milvus'], icon: DataLine, color: '#00a1ea' },
```

- [ ] **Step 3: 类型**

`types.ts`：

```ts
export interface MiddlewareFormOptions {
  namespaces: string[]
  storageClasses: string[]
  /** 本集群是否已装对象存储组件（Milvus 的「平台对象存储」选项要它） */
  objectStorageAvailable: boolean
}
```

`api/index.ts` 的 `middlewareApi.create` 入参追加：

```ts
    /** Milvus：etcd 节点数 1~3 */
    etcdReplicas?: number
    /** Milvus：PLATFORM（平台 SeaweedFS 桶）/ CUSTOM（自带 S3） */
    objectStorageType?: string
    objectStorageEndpoint?: string
    objectStorageBucket?: string
    objectStorageAccessKey?: string
    objectStorageSecretKey?: string
    objectStorageUseSsl?: boolean
    /** Milvus：单节点本地数据盘 */
    localStorageSize?: string
```

- [ ] **Step 4: 商店页状态与工具**

`MiddlewareStoreView.vue` 的 `formOptions` 初始值与赋值补 `objectStorageAvailable`：

```ts
const formOptions = ref<{ namespaces: string[]; storageClasses: string[]; objectStorageAvailable: boolean }>({
  namespaces: [],
  storageClasses: [],
  objectStorageAvailable: false,
})
```

`createFields` 改成按形态/存储来源过滤：

```ts
const createFields = computed(() => {
  const all = createItem.value ? (MIDDLEWARE_FIELDS[createItem.value.code] ?? []) : []
  if (createItem.value?.code !== 'milvus') return all
  return all.filter((f) => {
    if (f.key === 'localStorageSize') return createMode.value === 'STANDALONE'
    if (f.key === 'storageSize') return form.value.objectStorageType !== 'CUSTOM'
    return true
  })
})
```

`resetForm` 的初始化对象追加：

```ts
    etcdReplicas: 1,
    objectStorageType: '',
    objectStorageEndpoint: '',
    objectStorageBucket: '',
    objectStorageAccessKey: '',
    objectStorageSecretKey: '',
    objectStorageUseSsl: false,
```

`loadFormOptions` 成功分支追加：

```ts
      objectStorageAvailable: options?.objectStorageAvailable ?? false,
```

并加默认值逻辑：

```ts
    // Milvus：有对象存储组件时默认用平台桶，否则只能自带 S3
    if (createItem.value?.code === 'milvus' && !form.value.objectStorageType) {
      form.value.objectStorageType = formOptions.value.objectStorageAvailable ? 'PLATFORM' : 'CUSTOM'
    }
```

（失败分支里也要 `objectStorageAvailable: false`。）

`onModeChange` 追加 etcd 默认值：

```ts
  if (createItem.value?.code === 'milvus') {
    form.value.etcdReplicas = createMode.value === 'CLUSTER' ? 3 : 1
  }
```

`submitCreate` 里把 `const fields: MiddlewareField[] = MIDDLEWARE_FIELDS[createItem.value.code] ?? []` 改成 `const fields: MiddlewareField[] = createFields.value`，并在 `payload` 构建之后追加：

```ts
    if (createItem.value.code === 'milvus') {
      payload.etcdReplicas = form.value.etcdReplicas
      payload.objectStorageType = form.value.objectStorageType
      if (form.value.objectStorageType === 'CUSTOM') {
        payload.objectStorageEndpoint = form.value.objectStorageEndpoint?.trim() || undefined
        payload.objectStorageBucket = form.value.objectStorageBucket?.trim() || undefined
        payload.objectStorageAccessKey = form.value.objectStorageAccessKey?.trim() || undefined
        payload.objectStorageSecretKey = form.value.objectStorageSecretKey || undefined
        payload.objectStorageUseSsl = !!form.value.objectStorageUseSsl
      }
    }
```

> `localStorageSize` / `storageSize` 由通用循环按单位拼成 quantity 字符串；被 `createFields` 过滤掉的字段不会进 payload。

- [ ] **Step 5: 模板**

在 StoreView 创建表单的 Kibana 表单项之后追加：

```html
        <template v-if="createItem?.code === 'milvus'">
          <el-form-item label="etcd 节点数">
            <el-input-number v-model="form.etcdReplicas" :min="1" :max="3" />
            <div class="mw-form-help">1~3；单节点默认 1、集群默认 3；创建后不可修改</div>
          </el-form-item>
          <el-form-item label="对象存储来源" required>
            <el-radio-group v-model="form.objectStorageType">
              <el-radio value="PLATFORM" :disabled="!formOptions.objectStorageAvailable">平台对象存储</el-radio>
              <el-radio value="CUSTOM">自带 S3</el-radio>
            </el-radio-group>
            <div v-if="!formOptions.objectStorageAvailable" class="mw-form-help">
              本集群未安装对象存储组件，只能用「自带 S3」
            </div>
          </el-form-item>
          <template v-if="form.objectStorageType === 'CUSTOM'">
            <el-form-item label="S3 Endpoint" required>
              <el-input v-model="form.objectStorageEndpoint" spellcheck="false"
                        placeholder="s3.example.com:9000（http:// 前缀可有可无）" />
            </el-form-item>
            <el-form-item label="桶名" required>
              <el-input v-model="form.objectStorageBucket" spellcheck="false" placeholder="milvus-data" />
            </el-form-item>
            <el-form-item label="AccessKey" required>
              <el-input v-model="form.objectStorageAccessKey" spellcheck="false" autocomplete="off" />
            </el-form-item>
            <el-form-item label="SecretKey" required>
              <el-input v-model="form.objectStorageSecretKey" type="password" show-password
                        autocomplete="new-password" />
            </el-form-item>
            <el-form-item label="HTTPS">
              <el-switch v-model="form.objectStorageUseSsl" active-text="S3 使用 TLS" />
            </el-form-item>
          </template>
        </template>
```

表单底部说明 `mw-form-note` 里追加一个分支（放在 `kafka` 分支之后）：

```html
        <template v-else-if="createItem?.code === 'milvus'">
          Milvus 默认未开启鉴权；对象存储的 AK/SK 会写进同命名空间的 Secret（&lt;名称&gt;-milvus-storage）。
        </template>
```

- [ ] **Step 6: 详情页只读行**

> 编辑弹窗**不用改**：它本来就只有显示名、节点数、CPU/内存、指标开关；`fixedReplicas` 已把 `STANDALONE` 的节点数锁成 1，
> `CLUSTER` 才可编辑 —— 与 spec §5.5 一致。这里只把 storage 来源与 etcd 节点数补进只读的「规格」区。

`MiddlewareDetailView.vue` 的「规格」卡片里，在「StorageClass」之后追加：

```html
              <el-descriptions-item v-if="instance?.code === 'milvus'" label="对象存储">
                {{ detail?.spec?.objectStorageType === 'CUSTOM'
                    ? `自带 S3：${detail?.spec?.objectStorageEndpoint ?? '—'}`
                    : `平台桶：${detail?.spec?.objectStorageBucket ?? '—'}` }}
              </el-descriptions-item>
              <el-descriptions-item v-if="instance?.code === 'milvus'" label="etcd 节点数">
                {{ detail?.spec?.etcdReplicas ?? '—' }}
              </el-descriptions-item>
```

- [ ] **Step 7: 类型检查 + 构建**

```bash
cd platform-web && npm run type-check
```

Expected: 无类型错误。再跑一次完整前端构建：

```bash
cd platform-web && npm run build-only
```

Expected: `vite build` 成功。

- [ ] **Step 8: 浏览器验证**

按 `superpowers:efficient-browser-verification` / 仓库记忆里的「UI preview 验证法」：用假 token + XHR stub 打开中间件商店，选 Milvus：

- 形态切到「集群」时「节点数」可编辑、切回单节点变只读 1；
- 「本地数据盘」只在单节点出现；「对象存储容量」在「自带 S3」时消失；
- `objectStorageAvailable=false` 时「平台对象存储」禁用且提示出现；
- 提交时的 payload 含 `etcdReplicas` / `objectStorageType` 等字段。

- [ ] **Step 9: 提交**

```bash
git add platform-web/src/components/middleware/middlewareForms.ts \
        platform-web/src/components/middleware/MiddlewareIcon.vue \
        platform-web/src/types.ts \
        platform-web/src/api/index.ts \
        platform-web/src/views/MiddlewareStoreView.vue \
        platform-web/src/views/MiddlewareDetailView.vue
git commit -m "feat(milvus): 商店表单、图标与详情只读项"
```

---

## Task 8: 租户 RBAC 迁移与文档

**Files:**
- Create: `platform-data/src/main/resources/db/migration/V2026_10_25_7__milvus_tenant_rbac.sql`
- Modify: `docs/development/middleware-store.md`（§1 的「可用的中间件」表、§4「常见失败」表）

**Interfaces:**
- Consumes: 现有 `platform_rbac_template` 表结构
- Produces: 内置租户模板含 `milvus.io` 组

- [ ] **Step 1: 写迁移**

```sql
-- ============================================================================================
-- 租户 RBAC 模板：Milvus CRD 的 apiGroup
-- ============================================================================================
-- 背景：租户要在自己的命名空间创建 Milvus 实例（走租户 client 的第二道闸），
--       内置模板缺 milvus.io 时创建 CR 会 403（K8S_RBAC_FORBIDDEN）。
--
-- ⚠️ 重放后必须点一次「重下发全部模板」（POST /rbacTemplate/sync-all），否则已有租户 ClusterRole 还是旧 rules。
-- 幂等：带自己的 NOT LIKE 守卫（同 V2026_10_22_5 的写法）。
-- 注意：MySQL 注释 `--` 后面必须跟空白字符。
-- ============================================================================================

UPDATE platform_rbac_template
SET rules = JSON_ARRAY_APPEND(rules, '$.rules',
        CAST('{"apiGroups":["milvus.io"],"resources":["milvuses","milvusclusters","milvusupgrades"],"verbs":["*"]}' AS JSON))
WHERE built_in = 1
  AND deleted_at IS NULL
  AND rules NOT LIKE '%milvus.io%';
```

- [ ] **Step 2: 自查 SQL**

```bash
grep -n "milvus.io\|NOT LIKE" platform-data/src/main/resources/db/migration/V2026_10_25_7__milvus_tenant_rbac.sql
```

Expected: 两处都出现；`--` 后都有空格。

- [ ] **Step 3: 文档**

`docs/development/middleware-store.md` 的 **§1「可用的中间件」表**（5 列：条目 / 类型 / 版本（chart）/ 实例形态 / 凭据）末尾追加一行：

```markdown
| Milvus | Operator（milvus-operator） | 1.3.12（Milvus 2.6.25） | 单节点 / 集群（woodpecker WAL） | 无（默认不鉴权）；对象存储 AK/SK → `<名称>-milvus-storage` |
```

**§4「常见失败」表**追加一行：

```markdown
| 租户创建 Milvus 报 403 | 内置 RBAC 模板缺 `milvus.io`，或重放了 `V2026_10_25_7` 但没「重下发全部模板」 |
```

- [ ] **Step 4: 提交**

```bash
git add platform-data/src/main/resources/db/migration/V2026_10_25_7__milvus_tenant_rbac.sql \
        docs/development/middleware-store.md
git commit -m "feat(milvus): 租户 RBAC 模板补 milvus.io + 文档"
```

---

## Task 9: 全量构建与回归

**Files:** 无（只跑命令）

- [ ] **Step 1: 后端全量单测**

```bash
mvn -q -pl k8s-server,platform-api,platform-common,platform-data -am test
```

Expected: BUILD SUCCESS；关注 `MiddlewareServiceTest`、`MilvusMiddlewareAdapterTest`、`MiddlewareOperatorCatalogTest` 全绿。

- [ ] **Step 2: 前端类型检查与构建**

```bash
cd platform-web && npm run build
```

Expected: `type-check` + `vite build` 都成功。

- [ ] **Step 3: 自查改动面**

```bash
git diff --stat HEAD~8..HEAD
git status --short
```

Expected: 只有本计划涉及的文件；没有误提交 `docs/task-ledger-2026-10-12.md` 之类的无关改动。

---

## Task 10: 真集群验收（手工，实施完成的判据）

**Files:** 无

- [ ] **Step 1: 部署前提**

1. 重放 `V2026_10_25_7__milvus_tenant_rbac.sql`；
2. 调 `POST /rbacTemplate/sync-all`；
3. 重建并发布 `k8s-server` / `platform-api` / `platform-web`。

- [ ] **Step 2: 装 Operator**

在中间件商店安装 Milvus Operator，期望：
- 三个 CRD `Established`；
- `platform-middleware-system` 里 `platform-middleware-milvus` Deployment 就绪；
- 商店卡片显示 `operatorInstalled/operatorReady = true`。

- [ ] **Step 3: 单节点（平台桶）**

创建 `STANDALONE` + `PLATFORM`：
- 对象存储页出现桶 `t-<租户短码>-milvus-mw-milvus-xxxx`，配额 = 表单容量；
- 命名空间出现 `<name>-milvus-storage`（两键）；
- `kubectl get milvus -n <ns> <name>` 的 status 变 `Healthy`；
- `9091/metrics` 有数据；业务指标面板出数；
- 用 Milvus 客户端（或 `curl` 到 9091/healthz + 官方 SDK）写入一条向量并检索成功。

- [ ] **Step 4: 集群形态（自带 S3 或平台桶）**

创建 `CLUSTER` + 自选存储：
- proxy/mixcoord/datanode/querynode/streamingnode 全部就绪，**没有** pulsar/kafka/zookeeper Pod；
- etcd Pod 数 = 表单值；
- 无 Pending（配额与 resources 正常）。

- [ ] **Step 5: 生命周期**

- 编辑：改 CPU/内存、改查询节点数（集群）、开业务指标 —— 均生效；
- 单节点改节点数被拒（前端只读 + 后端报错）；
- 删除 `keepPvc=false`：桶、凭据、PVC（`<name>-data` 与 etcd）全部回收；
- 删除 `keepPvc=true`：桶与 PVC 保留。

- [ ] **Step 6: 记录**

把上面每一步的实测结论（含指标名与标签核对结果）补进 `charts/middleware/milvus/README.md` 的「坑」小节。
