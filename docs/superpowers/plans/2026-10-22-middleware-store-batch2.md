# 中间件商店 · 批①-2（MySQL / Kafka / ES + Nacos）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在批①-1 的框架上补齐 MySQL、Kafka、Elasticsearch 三款 Operator 中间件与 Nacos（chart）的实例创建/删除/状态，商店清单从 1 个可用条目扩到 5 个。

**Architecture:** 三款 Operator 中间件各写一个 `MiddlewareAdapter`（不抽基类）；Nacos 不是 CR，单独一个 chart 安装器并在实例服务里按 code 分支。operator chart 随 k8s-server 镜像内置，版本钉死。

**Tech Stack:** Spring Boot 4 / Java 21、fabric8 7.9、Helm 4.2、Vue 3 + Element Plus。

**Spec:** [docs/superpowers/specs/2026-10-22-middleware-store-design.md](../specs/2026-10-22-middleware-store-design.md)

## Global Constraints

- 本仓没有 Flyway；本批**无新表、无新权限行**（复用批①-1 的端点与权限）。
- 同类资源不抽公共基类：每款中间件一个适配器。
- 密码/凭据不进日志、不进 `spec_json`、不进审计。
- Operator chart 版本钉死：`ps-operator 1.2.0`、`strimzi-kafka-operator 1.3.0`、`eck-operator 3.5.0`、`nacos 1.2.0`。
- 每个任务结束：`mvn -q -pl <module> -am test` 全绿 + 提交。

## Review Focus

- **PS Operator 的凭据**：Percona 用自己生成的 `<cr>-secrets`，平台不能覆盖；适配器必须声明"不用平台密码"，否则会写出一个谁都不引用的 Secret。
- **Strimzi 1.x 的 KafkaNodePool**：Kafka CR 必须带 `strimzi.io/node-pools: enabled` 注解，否则 node pool 不生效、集群起不来。
- **ECK 的版本**：`spec.version` 必须是 ECK 3.5 支持的 ES 版本；写错时 ECK 会拒绝而不是降级。
- **Nacos 的 storage 模式**：Nacos 3.x 集群模式用 embedded（JRaft），不再依赖外置 MySQL；chart 默认 `service.type=NodePort/30000`，必须覆盖成 ClusterIP，否则多实例撞端口。
- **状态 selector**：四款各不相同（见各任务），写错会让状态恒为 CREATING。
- **删除**：Nacos 走 `helm uninstall`；Operator 类删 CR。keepPvc 语义对 chart 不适用（PVC 由 chart 的 persistence 控制）。

---

## Task 1: 内置 chart + k8s 目录条目

**Files:**
- Create: `k8s-server/src/main/resources/charts/middleware/mysql/1.2.0/chart/`（ps-operator）
- Create: `k8s-server/src/main/resources/charts/middleware/kafka/1.3.0/chart/`（strimzi）
- Create: `k8s-server/src/main/resources/charts/middleware/elasticsearch/3.5.0/chart/`（eck-operator）
- Create: `k8s-server/src/main/resources/charts/middleware/nacos/1.2.0/chart/`（nacos）
- 每个目录写 `README.md`（来源 URL、版本、拉取日期）
- Modify: `k8s-server/src/main/java/com/coding/k8sserver/middleware/MiddlewareOperatorCatalog.java`
- Test: `k8s-server/src/test/java/com/coding/k8sserver/middleware/MiddlewareOperatorCatalogTest.java`

**Interfaces:**
- Produces: `MiddlewareOperatorCatalog.require(code)` 支持 mysql/kafka/elasticsearch（nacos 不在 operator 目录里）。

- [ ] **Step 1: 失败测试**

```java
@Test
void requiredCrdsMatchEachOperatorsNaming() {
    assertThat(catalog.require("mysql").requiredCrds())
            .containsExactly("perconaservermysqls.ps.percona.com");
    assertThat(catalog.require("kafka").requiredCrds())
            .containsExactly("kafkas.kafka.strimzi.io", "kafkanodepools.kafka.strimzi.io");
    assertThat(catalog.require("elasticsearch").requiredCrds())
            .containsExactly("elasticsearches.elasticsearch.k8s.elastic.co");
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -q -pl k8s-server -am -Dtest=MiddlewareOperatorCatalogTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: 失败（mysql 不在目录）。

- [ ] **Step 3: 补目录条目**

```java
m.put("mysql", new Entry("mysql", "1.2.0", List.of("perconaservermysqls.ps.percona.com")));
m.put("kafka", new Entry("kafka", "1.3.0",
        List.of("kafkas.kafka.strimzi.io", "kafkanodepools.kafka.strimzi.io")));
m.put("elasticsearch", new Entry("elasticsearch", "3.5.0",
        List.of("elasticsearches.elasticsearch.k8s.elastic.co")));
```

- [ ] **Step 4: 拉取并内置 chart**

```bash
# 逐个下载 tgz → 解压到 charts/middleware/<code>/<version>/chart
#   https://percona.github.io/percona-helm-charts  ps-operator 1.2.0
#   https://strimzi.io/charts                      strimzi-kafka-operator 1.3.0
#   https://helm.elastic.co                        eck-operator 3.5.0
#   nacos：nacos-group/nacos-k8s 仓库的 helm/ 目录（chart 名 nacos，version 1.2.0）
```

每个目录的 `README.md` 记来源、版本、镜像仓库（quay.io / docker.elastic.co / docker.io）。

- [ ] **Step 5: 测试 + 提交**

Run: `mvn -q -pl k8s-server -am test`
Expected: 全绿。

```bash
git add k8s-server
git commit -m "feat(middleware): 内置 mysql/kafka/es/nacos chart 与 Operator 目录"
```

---

## Task 2: MySQL 适配器（Percona PS）

**Files:**
- Create: `k8s-server/src/main/java/com/coding/k8sserver/middleware/MysqlMiddlewareAdapter.java`
- Modify: `MiddlewareAdapter.java`（加 `usesPlatformPassword()` 默认 true）
- Modify: `MiddlewareInstanceService.java`（按 flag 决定是否写 Secret）
- Test: `k8s-server/src/test/java/com/coding/k8sserver/middleware/MysqlMiddlewareAdapterTest.java`

**Interfaces:**
- Produces: `code()="mysql"`；`buildCreate` 产出 1 个 `PerconaServerMySQL`（`ps.percona.com/v1`）。

- [ ] **Step 1: 失败测试**

```java
@Test
void singleNodeBuildsGroupReplicationWithOneMember() {
    var res = adapter.buildCreate(req("SINGLE", 1)).get(0).resource();
    assertThat(res.getKind()).isEqualTo("PerconaServerMySQL");
    var mysql = (Map<String, Object>) spec(res).get("mysql");
    assertThat(mysql).containsEntry("clusterType", "group-replication").containsEntry("size", 1);
}

@Test
void clusterEnforcesThreeMembers() {
    var mysql = (Map<String, Object>) spec(adapter.buildCreate(req("CLUSTER", 2)).get(0).resource()).get("mysql");
    assertThat(mysql).containsEntry("size", 3);
}

@Test
void usesOperatorGeneratedCredentials() {
    assertThat(adapter.usesPlatformPassword()).isFalse();
    assertThat(res.getAdditionalProperties().toString()).doesNotContain("-credentials");
}
```

- [ ] **Step 2: 实现**

```java
@Component
public class MysqlMiddlewareAdapter implements MiddlewareAdapter {
    private static final String IMAGE = "percona/percona-server:8.4";
    static final CustomResourceDefinitionContext CRD = new CustomResourceDefinitionContext.Builder()
            .withGroup("ps.percona.com").withVersion("v1").withKind("PerconaServerMySQL")
            .withPlural("perconaservermysqls").withScope("Namespaced").build();

    public String code() { return "mysql"; }
    public boolean usesPlatformPassword() { return false; }   // 用 Operator 生成的 <name>-secrets

    public List<CrSpec> buildCreate(MiddlewareCreateRequestDTO req) {
        int size = "CLUSTER".equals(req.getMode()) ? Math.max(3, req.getReplicas() == null ? 3 : req.getReplicas())
                                                   : 1;
        Map<String, Object> mysql = new LinkedHashMap<>();
        mysql.put("clusterType", "group-replication");
        mysql.put("size", size);
        mysql.put("image", IMAGE);
        mysql.put("resources", resources(req));
        mysql.put("volumeSpec", Map.of("persistentVolumeClaim",
                Map.of("resources", Map.of("requests", Map.of("storage",
                        req.getStorageSize() == null || req.getStorageSize().isBlank() ? "10Gi" : req.getStorageSize())))));
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("mysql", mysql);
        spec.put("proxy", Map.of("haproxy", Map.of("enabled", true, "size", size)));
        return List.of(new CrSpec(CRD, resource(req, "PerconaServerMySQL", spec)));
    }

    public String statusSelector(String name) {
        return "app.kubernetes.io/instance=" + name + ",app.kubernetes.io/component=mysql";
    }
    // applyScale：改 mysql.size（CLUSTER 下限 3）+ resources
}
```

> `storageClassName` 不在 PS 的 `volumeSpec` 默认 schema 里时会被 pruned；实现时以 1.2.0 的 CRD 为准，写不进就不写并记录。

- [ ] **Step 3: 测试 + 提交**

Run: `mvn -q -pl k8s-server -am test`

```bash
git commit -am "feat(middleware): MySQL 适配器（Percona PS，单节点/组复制）"
```

---

## Task 3: Kafka 适配器（Strimzi）

**Files:**
- Create: `k8s-server/src/main/java/com/coding/k8sserver/middleware/KafkaMiddlewareAdapter.java`
- Test: `k8s-server/src/test/java/com/coding/k8sserver/middleware/KafkaMiddlewareAdapterTest.java`

- [ ] **Step 1: 失败测试**

```java
@Test
void buildsNodePoolAndKafka() {
    var out = adapter.buildCreate(req("CLUSTER", 3));
    assertThat(out).extracting(c -> c.resource().getKind())
            .containsExactly("KafkaNodePool", "Kafka");
    assertThat(spec(out.get(0).resource())).containsEntry("roles", List.of("controller", "broker"))
            .containsEntry("replicas", 3);
    assertThat(out.get(1).resource().getMetadata().getAnnotations())
            .containsEntry("strimzi.io/node-pools", "enabled");
}

@Test
void clusterNeedsAtLeastThreeBrokers() {
    assertThat(spec(adapter.buildCreate(req("CLUSTER", 1)).get(0).resource())).containsEntry("replicas", 3);
}
```

- [ ] **Step 2: 实现**

- `KafkaNodePool`（`kafka.strimzi.io/v1`）：`roles: [controller, broker]`、`replicas`、`storage: {type: jbod, volumes:[{id:0,type:persistent-claim,size:<storage>,kraftMetadata:shared}]}`；
- `Kafka`（`kafka.strimzi.io/v1`）：annotation `strimzi.io/node-pools: enabled`，`kafka.version`（取自请求 `version`，默认 `4.0.0`），`listeners: [{name: plain, port: 9092, type: internal, tls: false}]`，`config` 的 replication factor 用 `min(replicas, 3)`，`entityOperator: {topicOperator:{}, userOperator:{}}`；
- 状态 selector：`strimzi.io/cluster=<name>`；
- 扩容：改 NodePool 的 `replicas`（下限 3）。

- [ ] **Step 3: 测试 + 提交**

---

## Task 4: Elasticsearch 适配器（ECK）

**Files:**
- Create: `k8s-server/src/main/java/com/coding/k8sserver/middleware/ElasticsearchMiddlewareAdapter.java`
- Test: `k8s-server/src/test/java/com/coding/k8sserver/middleware/ElasticsearchMiddlewareAdapterTest.java`

- [ ] **Step 1: 失败测试**

```java
@Test
void buildsSingleNodeSetWithVersionAndStorage() {
    var res = adapter.buildCreate(req("CLUSTER", 3)).get(0).resource();
    assertThat(res.getApiVersion()).isEqualTo("elasticsearch.k8s.elastic.co/v1");
    assertThat(res.getKind()).isEqualTo("Elasticsearch");
    assertThat(spec(res)).containsEntry("version", "9.5.0");
    var nodeSets = (List<Map<String, Object>>) spec(res).get("nodeSets");
    assertThat(nodeSets).hasSize(1);
    assertThat(nodeSets.get(0)).containsEntry("count", 3);
}
```

- [ ] **Step 2: 实现**

```yaml
apiVersion: elasticsearch.k8s.elastic.co/v1
kind: Elasticsearch
spec:
  version: 9.5.0            # 来自请求 version，默认 9.5.0
  nodeSets:
  - name: default
    count: <replicas>
    config:
      node.store.allow_mmap: false
    podTemplate:
      spec:
        containers:
        - name: elasticsearch
          resources: { requests: ..., limits: ... }
    volumeClaimTemplates:
    - metadata: { name: elasticsearch-data }
      spec:
        accessModes: [ReadWriteOnce]
        resources: { requests: { storage: <storage> } }
        storageClassName: <可选>
```

- 状态 selector：`elasticsearch.k8s.elastic.co/cluster-name=<name>`；
- 扩容：改 `nodeSets[0].count` + resources。

- [ ] **Step 3: 测试 + 提交**

---

## Task 5: Nacos（chart 安装器）

**Files:**
- Create: `k8s-server/src/main/java/com/coding/k8sserver/middleware/NacosChartInstaller.java`
- Modify: `MiddlewareInstanceService.java`（nacos 走 chart 分支）
- Test: `k8s-server/src/test/java/com/coding/k8sserver/middleware/NacosChartInstallerTest.java`

**Interfaces:**
- Consumes: `HelmRunner`、`ChartBundle.chartDir("middleware","nacos","1.2.0")`。
- Produces: create = `helm upgrade --install`；delete = `helm uninstall`；status = 按 `app.kubernetes.io/instance` 数 Pod。

- [ ] **Step 1: 失败测试**

```java
@Test
void clusterModeRendersClusterAndEmbeddedStorage() throws Exception {
    var values = installer.valuesFor(req("CLUSTER", 3));
    assertThat(values).contains("mode: cluster").contains("replicaCount: 3")
            .contains("type: embedded").contains("type: ClusterIP");
}

@Test
void deleteUsesHelmUninstall() {
    installer.delete("c1", "demo", "mw-nacos-abc12345");
    verify(helm).uninstall("kc", "mw-nacos-abc12345", "demo");
}
```

- [ ] **Step 2: 实现**

values 模板（写临时文件再 `helm.apply`）：

```yaml
mode: cluster            # standalone → standalone
nacos:
  replicaCount: <N>
  image:
    repository: nacos/nacos-server
    tag: v3.2.4
  storage:
    type: embedded
persistence:
  enabled: <storageSize 非空>
  data:
    resources:
      requests:
        storage: <storage>
    storageClassName: <可选>
service:
  type: ClusterIP
  port: 8848
```

- 状态 selector：`app.kubernetes.io/instance=<release>`；
- 扩容：重新 `helm upgrade` 改 `nacos.replicaCount`；
- `keepPvc` 不适用（PVC 由 chart 的 `persistence` 控制，删除 release 不删 PVC）。

- [ ] **Step 3: 服务分支 + 测试 + 提交**

`MiddlewareInstanceService`：`if ("nacos".equals(code)) chartInstaller... else adapter...`（create/delete/status/scale 四处）。

---

## Task 6: platform-api 目录与校验

**Files:**
- Modify: `platform-api/src/main/java/com/coding/platformapi/middleware/MiddlewareCatalog.java`（4 个条目补 modes）
- Modify: `MiddlewareService.java`（nacos 不走 Operator 就绪校验；mysql/kafka/es 走）
- Modify: `common/models/middleware/MiddlewareCatalogItemDTO.java`（加 `chartBacked`）
- Test: `MiddlewareServiceTest`

- [ ] **Step 1: 目录 modes**

```java
new Item("mysql", "MySQL", "关系型数据库（Percona Operator）", List.of(
        new Mode("SINGLE", "单节点", 1, "开发/测试；Group Replication 单成员"),
        new Mode("CLUSTER", "高可用集群", 3, "Group Replication，单主强一致，≥3 成员"))),
new Item("kafka", ..., List.of(new Mode("CLUSTER", "KRaft 集群", 3, "brokers ≥3，同时承担 controller"))),
new Item("elasticsearch", ..., List.of(new Mode("CLUSTER", "集群", 1, "生产建议 ≥3 节点"))),
new Item("nacos", ..., List.of(
        new Mode("STANDALONE", "单节点", 1, "开发/测试"),
        new Mode("CLUSTER", "集群", 3, "内嵌存储（JRaft），≥3 节点")))
```

- [ ] **Step 2: chartBacked 标记**

`MiddlewareCatalogItemDTO` 加 `boolean chartBacked`；catalog 组装时 `"nacos".equals(code)` 置 true；前端据此显示"无需 Operator"。

- [ ] **Step 3: 测试 + 提交**

---

## Task 7: 前端表单

**Files:**
- Modify: `platform-web/src/components/middleware/middlewareForms.ts`（4 份字段）
- Modify: `platform-web/src/views/MiddlewareStoreView.vue`（chartBacked 状态、版本选择）
- Modify: `platform-web/src/types.ts`（`chartBacked`）

- [ ] **Step 1: 字段**

| code | 字段 |
|---|---|
| mysql | replicas（SINGLE 固定 1/CLUSTER ≥3）、cpu/mem、storageSize、storageClass |
| kafka | replicas（≥3）、cpu/mem、storageSize、storageClass、version（默认 `4.0.0`） |
| elasticsearch | replicas（≥1）、cpu/mem、storageSize、storageClass、version（默认 `9.5.0`） |
| nacos | replicas（≥1）、cpu/mem、storageSize |

- [ ] **Step 2: 卡片状态**

`chartBacked` 的条目不受 Operator 就绪门控：标签显示「无需 Operator」，按钮直接「创建实例」。

- [ ] **Step 3: 验证 + 提交**

`npx vue-tsc --noEmit` + scratch 预览（四种表单 + 卡片状态）。

---

## Task 8: 文档与验证

- 更新 `docs/development/middleware-store.md`：清单扩到 5 个、四种形态、各 selector、Nacos 不用 MySQL 的说明；
- 台账 `.superpowers/sdd/2026-10-22-middleware-store-batch2/progress.md`；
- 提交。

## 部署

- 无新迁移、无新权限行；只重建 k8s-server + platform-api + platform-web；
- 管理员逐款安装 Operator（镜像来自 quay.io / docker.elastic.co / docker.io，集群需可达）；
- 真集群验证每款：装 Operator → 建单节点 → 建集群 → 删除。
