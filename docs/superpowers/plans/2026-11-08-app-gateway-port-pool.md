# 应用网关端口池（第一期）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 应用服务除了 NodePort 之外，还可以选「网关 HTTP / 网关 TCP」暴露。平台从 Istio 入口的端口池里分配一个端口，
为命名空间生成 ListenerSet，为服务生成 HTTPRoute / TCPRoute，访问地址是 `节点IP:端口`。

**Architecture:**
- 端口池是 Istio 组件表单里的一个范围，渲染 helm values 时展开成 `external-gateway` Service 的端口（nodePort 等于端口）。
  Gateway 补上 `allowedListeners`，只放行带保留标签的命名空间。
- platform-api 负责：在库里分配端口；按命名空间重算 ListenerSet 并经 k8s-server 下发；
  把路由参数交给应用 workload chart 渲染。
- k8s-server 只按调用方给的参数下发对象，不做业务判断。

**Tech Stack:** Spring Boot 4 / MyBatis + PageHelper / fabric8 GenericKubernetesResource（SSA）/ Helm（应用 workload chart）/ Vue 3 + Element Plus。

**Spec:** `docs/superpowers/specs/2026-11-08-app-istio-exposure-design.md`（§0 实测结果、§5、§6）

## Global Constraints

- k8s-server 零业务：端口选择、listener 列表、标签键值都由 platform-api 算好再传过去，k8s-server 只校验格式后 SSA。
- 一页一 controller；不加外键；同类资源不抽公共基类；分页用 PageHelper（mapper 里不写 LIMIT）。
- 本仓没有 Flyway：SQL 放在 `platform-data/src/main/resources/db/migration/V2026_11_08_1__ingress_port_pool.sql`，部署时手工重放。
  MySQL 注释 `--` 后面必须跟空白。
- 新权限行必须显式绑定角色（本计划**不新增**对外端点，所以不新增权限行）。
- 端口池默认 `20000-20999`，上限 5000 个端口；范围超过 1000 时，表单提示「核对 kube-proxy 规则同步耗时」。
  本集群 kube-proxy 是 ipvs 模式，已核实。
- **只分配网关 Service 上已经声明的端口**。用了未声明的端口，整个 Gateway 会变成 `Programmed=False（AddressNotUsable）`（spec §0 A3'）。
- TCPRoute 用 `gateway.networking.k8s.io/v1`；ListenerSet 用 `gateway.networking.k8s.io/v1`。
- 命名空间放行标签 `ingress.platform.coding/ports=true` 是**保留标签**，用户经平台编辑命名空间时不能写入或删除。
- 命名空间的 ListenerSet 名固定为 `platform-ingress`，listener 名为 `p<端口>`，`allowedRoutes.namespaces.from: Same`。
- 端口释放后冷却 10 分钟才能再分配。
- 现有 NodePort 校验（30000-32767）保持不变，端口池默认段在这个范围之外，两者天然不重叠。
- 提交信息结尾加 `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`；直接在 main 上提交，不推送。

**不在本计划**：NodePort 暴露留空时由 K8s 在 1-65535 里随机分配（spec §5.3 末尾），作为现有功能的缺陷单独修。

## Review Focus

1. **并发分配同一个端口**：两个应用同时保存，必须靠 `(cluster_id, port)` 唯一索引兜底，后到的请求换一个端口重试，或者明确报错，不能两个都拿到。
2. **缩小端口池时，被删掉的那部分里还有在用的端口**：Istio 组件编辑保存时必须拒绝，并列出占用的应用。否则 Service 上少了这个端口，整个 Gateway 会变红。
3. **服务从「网关 HTTP」改成「网关 TCP」**（或者反过来）：端口保持不变，listener 的协议要跟着变，旧的路由类型要被删掉；不能留下一个 HTTPRoute 挂在 TCP listener 上。
4. **应用下线后再发布**：下线时释放的端口进入冷却。再发布时如果端口已经被别人占了，要分配一个新端口，并在概览里显示新地址，不能报错卡住。
5. **ListenerSet 下发失败**（CRD 缺失、Gateway 不存在）：发布必须在 PRECHECK 阶段就失败并说明原因，不能先下发路由、留下挂不上的 HTTPRoute。

---

### Task 1: k8s-server — Gateway 放行、命名空间 ListenerSet、读取网关 Service 端口

**Files:**
- Modify: `platform-common/src/main/java/com/coding/common/models/k8s/dto/ExternalGatewayRequestDTO.java`（新增 `Map<String,String> allowedListenerLabels`）
- Modify: `k8s-server/src/main/java/com/coding/k8sserver/components/ExternalGatewayObjects.java`
- Create: `platform-common/src/main/java/com/coding/common/models/k8s/dto/IngressListenerSetRequestDTO.java`
- Create: `platform-common/src/main/java/com/coding/common/models/k8s/dto/IngressListenerDTO.java`
- Create: `platform-common/src/main/java/com/coding/common/models/k8s/dto/IngressListenerSetStatusDTO.java`
- Create: `k8s-server/src/main/java/com/coding/k8sserver/components/IngressListenerSetObjects.java`
- Modify: `k8s-server/src/main/java/com/coding/k8sserver/controllers/cluster/ComponentInstallController.java`（新增三个端点）
- Test: `k8s-server/src/test/java/com/coding/k8sserver/components/ExternalGatewayObjectsTest.java`、
  `k8s-server/src/test/java/com/coding/k8sserver/components/IngressListenerSetObjectsTest.java`

**Interfaces:**
- Produces:
  - `ExternalGatewayObjects.buildWithSpecs(String ns, String name, String serviceName, List<Map<String,Object>> specs, Map<String,String> allowedListenerLabels)`：
    - labels 为空时不写 `spec.allowedListeners`；不为空时写成 `{namespaces:{from:Selector, selector:{matchLabels:<labels>}}}`。
    - 原来的四参数重载保留，转调时 labels 传 `null`。
    - merge=true 的路径要先读出现有 Gateway 的 `spec.allowedListeners`，原样带回去。否则 Harbor 合并 443 时会把它冲掉。
  - `IngressListenerDTO { String name; Integer port; String protocol /* HTTP|TCP */; }`
  - `IngressListenerSetRequestDTO { String clusterId; String namespace; String name; String parentNamespace; String parentName; Map<String,String> namespaceLabels; List<IngressListenerDTO> listeners; }`
    - listeners 为空表示删除这个 ListenerSet，同时从命名空间上去掉 namespaceLabels 里的键。
  - `IngressListenerSetStatusDTO { boolean exists; List<Listener> listeners; }`，其中
    `Listener { String name; Integer port; String protocol; boolean programmed; boolean conflicted; String reason; String message; }`。
  - `IngressListenerSetObjects.build(IngressListenerSetRequestDTO) -> GenericKubernetesResource`，生成的对象：
    - apiVersion `gateway.networking.k8s.io/v1`，kind `ListenerSet`，带标签 `platform.coding/managed=true`；
    - `spec.parentRef={group:gateway.networking.k8s.io, kind:Gateway, name, namespace}`；
    - 每个 listener 都写 `allowedRoutes.namespaces.from: Same`。
  - `IngressListenerSetObjects.validate(IngressListenerSetRequestDTO)`：校验规则见 Step 3。
  - 端点：都在 `/components` 下，先 `accessResolver.assertClusterAccess`，再用 admin client。
    - `POST /components/ingress-listenerset` → `ResponseData<Boolean>`
      - listeners 不为空：先 SSA 给命名空间打标签，再 SSA ListenerSet。
      - listeners 为空：删除 ListenerSet，去掉标签。
      - ListenerSet CRD 不存在时返回 false。
    - `GET /components/ingress-listenerset?clusterId&namespace&name` → `ResponseData<IngressListenerSetStatusDTO>`
    - `GET /components/service-ports?clusterId&namespace&name` → `ResponseData<List<Integer>>`
      - 返回 Service 的 `spec.ports[*].port`；Service 不存在时返回空列表。

- [ ] **Step 1: 写失败的测试**

```java
// ExternalGatewayObjectsTest
@Test void allowedListenersWrittenOnlyWhenLabelsGiven() {
    var withLabels = ExternalGatewayObjects.buildWithSpecs("istio-system", "external-gateway", "external-gateway",
            List.of(), Map.of("ingress.platform.coding/ports", "true"));
    assertThat(spec(withLabels).get("allowedListeners")).isEqualTo(Map.of("namespaces", Map.of("from", "Selector",
            "selector", Map.of("matchLabels", Map.of("ingress.platform.coding/ports", "true")))));
    var without = ExternalGatewayObjects.buildWithSpecs("istio-system", "external-gateway", "external-gateway",
            List.of(), null);
    assertThat(spec(without)).doesNotContainKey("allowedListeners");
}

// IngressListenerSetObjectsTest
@Test void buildsListenerSetWithSameNamespaceRoutes() {
    var r = IngressListenerSetObjects.build(req("shop", List.of(
            listener("p20001", 20001, "HTTP"), listener("p20002", 20002, "TCP"))));
    assertThat(r.getApiVersion()).isEqualTo("gateway.networking.k8s.io/v1");
    assertThat(r.getKind()).isEqualTo("ListenerSet");
    assertThat(spec(r).get("parentRef")).isEqualTo(Map.of("group", "gateway.networking.k8s.io", "kind", "Gateway",
            "name", "external-gateway", "namespace", "istio-system"));
    assertThat(listeners(r)).extracting(l -> l.get("allowedRoutes"))
            .containsOnly(Map.of("namespaces", Map.of("from", "Same")));
}

@Test void rejectsBadListeners() {
    assertThatThrownBy(() -> IngressListenerSetObjects.validate(req("shop",
            List.of(listener("p1", 1, "HTTPS"))))).hasMessageContaining("HTTPS");
    assertThatThrownBy(() -> IngressListenerSetObjects.validate(req("shop",
            List.of(listener("a", 20001, "HTTP"), listener("a", 20002, "HTTP"))))).hasMessageContaining("重复");
}
```

- [ ] **Step 2: 运行测试，确认失败**
  - Run: `mvn -q -pl k8s-server -am test -Dtest='ExternalGatewayObjectsTest,IngressListenerSetObjectsTest' -Dsurefire.failIfNoSpecifiedTests=false`
  - Expected: 编译失败（还没有 `IngressListenerSetObjects`，也没有五参数的 `buildWithSpecs`）

- [ ] **Step 3: 实现 DTO、两个 Objects 类，以及 controller 的三个端点**
  - `validate` 规则：
    - listener 名称不能重复；
    - 端口在 1-65535 之间，且不能重复；
    - 协议只能是 HTTP 或 TCP；
    - 最多 64 条。
  - 命名空间打标签的 SSA 用一个独立的 field manager `platform-ingress-label`。这样它只管这一个标签，不会和命名空间上其他标签的管理互相覆盖。

- [ ] **Step 4: 运行测试，确认通过**（命令同 Step 2）。Expected: PASS

- [ ] **Step 5: 提交** `git commit -m "feat(k8s-server): ingress ListenerSet apply/status, gateway allowedListeners, service ports"`

### Task 2: 端口分配表与 IngressPortService

**Files:**
- Create: `platform-data/src/main/resources/db/migration/V2026_11_08_1__ingress_port_pool.sql`
- Create: `PlatformIngressPort` 实体与 `PlatformIngressPortMapper`（放在 platform-data 里与现有实体、mapper 相同的包和目录，写法也照现有 mapper）
- Modify: `platform-api/src/main/java/com/coding/platformapi/k8s/K8sComponentClient.java`（新增 `List<Integer> servicePorts(String clusterId, String ns, String name)`）
- Create: `platform-api/src/main/java/com/coding/platformapi/services/IngressPortService.java`
- Test: `platform-api/src/test/java/com/coding/platformapi/services/IngressPortServiceTest.java`

表结构（不加外键）：

```sql
CREATE TABLE platform_ingress_port (
  id           VARCHAR(32)  NOT NULL PRIMARY KEY,
  cluster_id   VARCHAR(32)  NOT NULL,
  port         INT          NOT NULL,
  namespace    VARCHAR(63)  NOT NULL,
  app_id       VARCHAR(32)  NOT NULL,
  service_key  VARCHAR(63)  NOT NULL,
  protocol     VARCHAR(8)   NOT NULL,
  status       VARCHAR(16)  NOT NULL,
  released_at  DATETIME     NULL,
  created_at   DATETIME     NOT NULL,
  updated_at   DATETIME     NOT NULL,
  UNIQUE KEY uk_ingress_port (cluster_id, port),
  KEY idx_ingress_port_app (app_id, service_key)
);
```

- protocol 取 HTTP 或 TCP；status 取 ACTIVE 或 RELEASED。
- 释放后的行保留为 RELEASED，用来实现冷却。
- 再次分配某个端口时：如果这个端口有 RELEASED 行，并且 `released_at` 已经早于 10 分钟前，就把这一行改回 ACTIVE 并写上新的归属；没有行时才插入。

**Interfaces:**
- Consumes：Task 1 的 `GET /components/service-ports`
- Produces（`IngressPortService`）：
  - `int allocate(String clusterId, String namespace, String appId, String serviceKey, String protocol, PortRange pool)`
    - 同一个 `(appId, serviceKey)` 已经有 ACTIVE 行：返回原端口；协议不同时更新协议。
    - 否则在「`pool` 与 `external-gateway` Service 已声明端口的交集」里，选一个既不是 ACTIVE、也不在冷却期内的最小端口。
    - 遇到唯一键冲突时换下一个端口重试，最多 3 次。
    - 没有可用端口时抛出「端口池已用完」。
  - `Integer portOf(String appId, String serviceKey)`：返回 ACTIVE 的端口；没有时返回 null。
  - `void release(String appId, String serviceKey)` / `void releaseApp(String appId)`
  - `List<PlatformIngressPort> activeInNamespace(String clusterId, String namespace)`
  - `List<PlatformIngressPort> activeOutside(String clusterId, PortRange keep)`：缩小端口池时用，返回落在新范围之外、仍在使用的端口。
  - `record PortRange(int from, int to)`，提供 `contains(int)` 和 `size()`。

- [ ] **Step 1: 写失败的测试**（mapper 和 `servicePorts` 用 Mockito 打桩）

```java
@Test void reusesExistingPortForSameService()  // 已有 ACTIVE 20003 → 返回 20003，不插入新行
@Test void picksLowestFreeDeclaredPort()        // 20000 ACTIVE、20001 冷却中、20002 Service 未声明 → 20003
@Test void reusesReleasedRowAfterCooldown()     // 20000 是 RELEASED、11 分钟前释放 → 返回 20000，走 update 不走 insert
@Test void retriesOnDuplicateKey()              // 第一次 insert 抛 DuplicateKeyException → 第二次成功，insert 调用 2 次
@Test void exhaustedPoolFails()                 // hasMessageContaining("端口池已用完")
@Test void protocolSwitchKeepsPort()            // HTTP → TCP：端口不变，调用了 updateProtocol
```

- [ ] **Step 2: 运行测试，确认失败**
  - Run: `mvn -q -pl platform-api -am test -Dtest=IngressPortServiceTest -Dsurefire.failIfNoSpecifiedTests=false`
  - Expected: 编译失败

- [ ] **Step 3: 实现**：迁移 SQL（文件头写清楚背景和重放说明）、实体、mapper、`servicePorts`、`IngressPortService`

- [ ] **Step 4: 运行测试，确认通过**（命令同 Step 2）

- [ ] **Step 5: 提交** `git commit -m "feat(api): ingress port allocation table and service"`

### Task 3: Istio 组件表单——应用端口池范围

**Files:**
- Modify: `platform-api/src/main/java/com/coding/platformapi/components/ComponentRecipeCatalog.java`
  （external-gateway release 的 editableValues 增加 `platform.ingressPortPool`，默认 `{from:20000,to:20999}`；
  在 `EDITABLE_LABELS` 里登记文案「应用端口池」，在 `EDITABLE_GROUPS` 里归入「对外入口（external-gateway）」）
- Modify: `platform-api/src/main/java/com/coding/platformapi/services/ClusterComponentService.java`（字段类型推断：key 以 `ingressPortPool` 结尾 → `portRange`）
- Modify: `platform-api/src/main/java/com/coding/platformapi/services/ComponentInstallService.java`
- Create: `platform-api/src/main/java/com/coding/platformapi/components/IngressPortPool.java`（纯函数）
- Modify: `platform-web/src/components/cluster/ClusterComponentsPanel.vue`（`portRange` 字段：起始、结束两个数字框，超过 1000 个端口时给提示）
- Test: `platform-api/src/test/java/com/coding/platformapi/components/IngressPortPoolTest.java`、`ComponentInstallServiceTest.java`

**Interfaces:**
- Consumes：Task 2 的 `IngressPortService.activeOutside`、`PortRange`
- Produces（`IngressPortPool`）：
  - `static final String KEY = "platform.ingressPortPool"`，`static final int MAX = 5000`，`static final int WARN = 1000`
  - `static PortRange parse(Object raw)`：null 或空返回 null，表示停用；from、to 都必须是 1-65535 的整数，`from <= to`，个数不超过 5000，否则抛出带中文说明的校验异常
  - `static void assertNoOverlap(PortRange pool, List<Map<String,Object>> portRows)`：不能和端口表里任何一行的 port 或 nodePort 重叠
  - `static List<Map<String,Object>> expand(PortRange pool)`：每个端口展开成一行 `{name:"pool-<p>", port:p, targetPort:p, nodePort:p, protocol:"TCP"}`，不带 listener 字段
  - `static Map<String,Object> render(Map<String,Object> values)`：
    - 把 `platform.ingressPortPool` 展开后追加到 `service.ports` 末尾；
    - 删掉 `platform` 这个键，不把它交给 helm；
    - 不修改入参。
- `ComponentInstallService` 的改动：
  - `toDtos` 对 `istio-gateway` 先 `IngressPortPool.render`，再 `withoutGatewayListenerFields`；
  - 安装或编辑 istio 之前做三项校验：`parse` + `assertNoOverlap`；新范围之外不能有 ACTIVE 端口，有就报错并逐条列出「应用 / 服务 / 端口」；
  - 调用 `ensureExternalGateway` 时带上 `allowedListenerLabels = {"ingress.platform.coding/ports":"true"}`，前提是端口池已启用。
- `IngressPortPool.current(clusterId)`：从 istio 组件「上次成功」的 values 里读出当前范围，给 Task 5 用；读不到返回 null。

- [ ] **Step 1: 写失败的测试**

```java
@Test void parseValidatesRange() {
    assertThat(IngressPortPool.parse(Map.of("from", 20000, "to", 20999))).isEqualTo(new PortRange(20000, 20999));
    assertThat(IngressPortPool.parse(null)).isNull();
    assertThatThrownBy(() -> IngressPortPool.parse(Map.of("from", 20000, "to", 26000))).hasMessageContaining("5000");
    assertThatThrownBy(() -> IngressPortPool.parse(Map.of("from", 21000, "to", 20000))).hasMessageContaining("起始");
}
@Test void overlapWithPortTableRejected() {
    assertThatThrownBy(() -> IngressPortPool.assertNoOverlap(new PortRange(400, 500),
            List.of(Map.of("name", "https", "port", 443, "nodePort", 443)))).hasMessageContaining("443");
}
@Test void renderExpandsPoolAndDropsPlatformKey() {
    Map<String, Object> out = IngressPortPool.render(Map.of(
            "service", Map.of("type", "NodePort", "ports", List.of(Map.of("name", "http2", "port", 80))),
            "platform", Map.of("ingressPortPool", Map.of("from", 20000, "to", 20002))));
    assertThat(out).doesNotContainKey("platform");
    assertThat(ports(out)).extracting(p -> p.get("name")).containsExactly("http2", "pool-20000", "pool-20001", "pool-20002");
    assertThat(ports(out).get(1)).containsEntry("nodePort", 20000).doesNotContainKey("listener");
}
// ComponentInstallServiceTest
@Test void shrinkingPoolBelowActivePortsRejected()   // activeOutside 返回 shop/api:20500 → 报错消息含 "shop" 和 "20500"
@Test void gatewayGetsAllowedListenersWhenPoolEnabled()  // ensureExternalGateway 收到 allowedListenerLabels
```

- [ ] **Step 2: 运行测试，确认失败**
  - Run: `mvn -q -pl platform-api -am test -Dtest='IngressPortPoolTest,ComponentInstallServiceTest' -Dsurefire.failIfNoSpecifiedTests=false`
  - Expected: 编译失败或断言失败

- [ ] **Step 3: 实现后端**
  - 「上次成功」的读取方式跟现有编辑表单回填 `currentValue` 走同一个来源。
  - 先确认 `ReleaseSpec.contractValues()` 里点号路径是怎么变成嵌套 Map 的（看现有 `service.ports` 的处理），`platform.ingressPortPool` 走同样的路径。
  - 在 `docs/development/` 的 Istio 组件说明，或 `istio-gateway` 的 chart README 里补一段「应用端口池」。

- [ ] **Step 4: 前端** `portRange` 字段：
  - 两个 `el-input-number`，起始和结束，范围 1-65535；两个都留空表示停用。
  - 显示「共 N 个端口」；N 大于 1000 时显示警告「上线前核对 kube-proxy 规则同步耗时」。
  - 提交时拼成 `{from,to}`，比较「有没有改动」的口径和其他字段一致（写进 `initial`）。
  - 运行 `cd platform-web && npm run build`，Expected: 构建通过。

- [ ] **Step 5: 运行后端测试**（命令同 Step 2），Expected: PASS；然后提交 `git commit -m "feat(component): istio ingress port pool range"`

### Task 4: 应用 workload chart——HTTPRoute / TCPRoute 模板

**Files:**
- Modify: `k8s-server/src/main/java/com/coding/k8sserver/app/AppWorkloadValues.java`
- Create: `k8s-server/src/main/resources/charts/app/workload/1.0.0/chart/templates/route.yaml`
- Modify: `k8s-server/src/main/resources/charts/app/workload/1.0.0/chart/values.yaml`、`k8s-server/src/main/resources/charts/app/workload/README.md`
- Test: `AppWorkloadValues` 的现有测试类（`k8s-server/src/test/java/com/coding/k8sserver/app/` 下），加上该 chart 的渲染测试（如果已有就沿用）

沿用 `a1d7f476` 的做法，在 1.0.0 目录里直接改，不升 chart 版本。

**Interfaces:**
- Consumes（节点参数，由 Task 5 生成，k8s-server 只透传）：`params.route = {kind: "http"|"tcp", parentName, sectionName}`。
  route 不存在、或者是后台进程时，不生成路由。
- Produces：values `workload.route = {enabled, kind, parentName, sectionName, port}`，其中 port 取服务端口。
  模板按 kind 生成下面两种之一：
  - `HTTPRoute`（`gateway.networking.k8s.io/v1`）
  - `TCPRoute`（`gateway.networking.k8s.io/v1`）

  路由的公共字段：
  - 名称与 Service 同名；
  - `parentRefs: [{group: gateway.networking.k8s.io, kind: ListenerSet, name: <parentName>, sectionName: <sectionName>}]`；
  - `backendRefs: [{name: <Service 名>, port: <port>}]`。

  `expose` 仍然是 `none` 或 `nodeport`；网关方式下 Service 保持 ClusterIP（`expose.type=none`）。

- [ ] **Step 1: 写失败的测试**

```java
@Test void routeValuesFromParams() {
    var v = AppWorkloadValues.build(params("port", 8080, "route",
            Map.of("kind", "tcp", "parentName", "platform-ingress", "sectionName", "p20002")), …);
    assertThat(workload(v).get("route")).isEqualTo(Map.of("enabled", true, "kind", "tcp",
            "parentName", "platform-ingress", "sectionName", "p20002", "port", 8080));
}
@Test void noRouteForWorker()   // 后台进程（没有 port）→ route.enabled=false
@Test void routeTemplateRendersTcpRouteV1()   // 渲染 chart：kind=TCPRoute、apiVersion=gateway.networking.k8s.io/v1、parentRefs.kind=ListenerSet
```

- [ ] **Step 2: 运行测试，确认失败**
  - Run: `mvn -q -pl k8s-server -am test -Dtest='AppWorkloadValues*' -Dsurefire.failIfNoSpecifiedTests=false`
  - Expected: FAIL

- [ ] **Step 3: 实现** `AppWorkloadValues`、`route.yaml`、values 默认值 `route.enabled=false`，并补上 README 的参数表

- [ ] **Step 4: 运行测试，确认通过**（命令同 Step 2）

- [ ] **Step 5: 提交** `git commit -m "feat(app-chart): optional HTTPRoute / TCPRoute to a ListenerSet listener"`

### Task 5: 应用侧接入——保存时分配端口、发布时下发 ListenerSet 和路由、下线和删除时释放

**Files:**
- Modify: `platform-api/src/main/java/com/coding/platformapi/app/AppServiceDesired.java`
- Create: `platform-api/src/main/java/com/coding/platformapi/services/IngressListenerSync.java`
- Modify: `platform-api/src/main/java/com/coding/platformapi/services/AppServiceConfigService.java`（`create` / `update` / `remove`）
- Modify: `platform-api/src/main/java/com/coding/platformapi/services/AppReleasePipeline.java`（`check` / `buildRequest`）
- Modify: `platform-api/src/main/java/com/coding/platformapi/services/AppReleaseService.java`（`offline`）
- Modify: `platform-api/src/main/java/com/coding/platformapi/services/ApplicationService.java`（`delete`）
- Modify: `platform-api/src/main/java/com/coding/platformapi/k8s/K8sComponentClient.java`（`applyIngressListenerSet`、`ingressListenerSetStatus`）
- Modify: 命名空间编辑入口（`NamespaceUpsertRequest` 的处理处），拒绝以 `ingress.platform.coding/` 开头的标签键
- Test: `AppServiceDesiredTest`、`AppServiceConfigServiceTest`、`AppReleasePipelineTest`（没有就新建）、
  `IngressListenerSyncTest`、`AppReleaseServiceTest`

**Interfaces:**
- Consumes：Task 1 的端点；Task 2 的 `IngressPortService`；Task 3 的 `IngressPortPool.current`；Task 4 的 `params.route`
- Produces：
  - `AppServiceDesired`：
    - `EXPOSE_GATEWAY_HTTP = "gateway-http"`，`EXPOSE_GATEWAY_TCP = "gateway-tcp"`；
    - `static String gatewayProtocol(Map desired)`：返回 `"HTTP"`、`"TCP"` 或 null。
    - `normalizeExpose` 的规则：接受这两个新值；必须有端口；不能同时填 `nodePort`。
      报错文案改成「不支持的暴露方式：x（可选 NodePort / 网关 HTTP / 网关 TCP）」。
  - `IngressListenerSync`：
    - 常量：`LISTENER_SET = "platform-ingress"`，`NS_LABEL = "ingress.platform.coding/ports"`，
      `GATEWAY_NS = "istio-system"`，`GATEWAY = "external-gateway"`。
    - `void sync(String clusterId, String namespace)`：
      - 用 `activeInNamespace` 生成 listener 列表（名称 `p<端口>`，协议取分配表）后调用 apply；
      - 列表为空时也调用，交给 k8s-server 去删 ListenerSet、去标签；
      - apply 返回 false 时抛出「集群缺少 ListenerSet CRD 或入口 Gateway，不能用网关方式暴露」。
    - `static String sectionName(int port)`：返回 `"p" + port`。
- 行为：
  - **保存（create / update）**：
    - 新 desired 是网关方式时：
      1. 组件旗标 `routes` 要可用，走 `AppFeatureGate`，规则仍是「没装不让开、已开可关」；
      2. `IngressPortPool.current(clusterId)` 不能为 null，否则报「集群未启用应用端口池（Istio 组件编辑里配置）」；
      3. 调用 `allocate`，按协议分配并锁定端口。
    - 从网关方式改成别的方式：`release`。
    - 端口不写进 desired（spec §6.1：端口锁定在服务上，不进快照）。
  - **删除服务（remove）**：`release`，然后 `sync`。
  - **PRECHECK（`check`）**：
    1. 对快照里每个网关方式的服务：`portOf` 为 null（下线后又发布、端口已被释放）时重新 `allocate`；
       分配用到的协议取快照里的值；
    2. 然后 `sync(cluster, ns)`。

    失败就抛错。这样在下发任何路由之前就能拦住（Review Focus 5）。
  - **RENDER（`buildRequest`）**：网关方式的服务，`params.expose` 改成 `"none"`，并加上
    `params.route = {kind: "http" 或 "tcp", parentName: "platform-ingress", sectionName: "p<端口>"}`。
    这里的端口取 `portOf`；取不到就报错——正常情况下 PRECHECK 已经分配过了。
  - **下线（offline）**：渲染并卸载完成后，`releaseApp` 加 `sync`。sync 失败只记日志，不改变下线的结论。
  - **删除应用**：同下线。
  - **回滚**：不需要特殊处理。旧快照里是网关方式的服务，会在 PRECHECK 重新拿到端口。

- [ ] **Step 1: 写失败的测试**

```java
// AppServiceDesiredTest
@Test void gatewayExposeNeedsPortAndNoNodePort() {
    assertThat(AppServiceDesired.normalize(svc("expose", "gateway-tcp")).get("expose")).isEqualTo("gateway-tcp");
    assertThatThrownBy(() -> AppServiceDesired.normalize(svc("expose", "gateway-http", "nodePort", 30001)))
            .hasMessageContaining("NodePort");
    assertThatThrownBy(() -> AppServiceDesired.normalize(worker("expose", "gateway-http"))).hasMessageContaining("端口");
}
// AppServiceConfigServiceTest
@Test void savingGatewayExposeAllocatesPort()       // verify(ports).allocate(C, ns, appId, "api", "HTTP", POOL)
@Test void gatewayExposeRejectedWithoutPool()       // IngressPortPool.current 返回 null → hasMessageContaining("端口池")
@Test void switchingToNoneReleasesPort()            // verify(ports).release(appId, "api")
// AppReleasePipelineTest
@Test void renderAddsRouteAndClusterIp()            // params.route.sectionName == "p20003"，params.expose == "none"
@Test void precheckReallocatesAfterOfflineAndSyncs() // portOf 为 null → allocate，随后 sync(C, ns)
@Test void precheckFailsWhenListenerSetUnsupported() // sync 抛错 → check 抛错，消息含 "ListenerSet"
// IngressListenerSyncTest
@Test void listenersFromActivePorts()               // 两个 ACTIVE（HTTP 20001、TCP 20002）→ 请求里两条 listener，namespaceLabels 含 NS_LABEL
@Test void emptyNamespaceSendsEmptyList()
// AppReleaseServiceTest
@Test void offlineReleasesPortsAndSyncs()
```

- [ ] **Step 2: 运行测试，确认失败**
  - Run: `mvn -q -pl platform-api -am test -Dtest='AppServiceDesiredTest,AppServiceConfigServiceTest,AppReleasePipelineTest,IngressListenerSyncTest,AppReleaseServiceTest' -Dsurefire.failIfNoSpecifiedTests=false`
  - Expected: FAIL

- [ ] **Step 3: 实现**：上面列出的各处，外加保留标签的拦截（报「ingress.platform.coding/ 前缀是平台保留标签」）

- [ ] **Step 4: 运行测试，确认通过**（命令同 Step 2），再跑整个 platform-api：`mvn -q -pl platform-api -am test`。Expected: 全部通过

- [ ] **Step 5: 提交** `git commit -m "feat(app): gateway HTTP/TCP expose via ingress port pool"`

### Task 6: 概览状态与前端

**Files:**
- Modify: `platform-api/src/main/java/com/coding/platformapi/models/AppServiceView.java`（新增 `Integer ingressPort`）
- Modify: `platform-api/src/main/java/com/coding/platformapi/models/AppOverviewView.java`
  （每个服务新增 `ingress {port, protocol, programmed, conflicted, reason, message}`；
  ListenerSet 状态取不到时为 null，前端显示「状态未知」）
- Modify: `AppServiceConfigService`（视图里填 `ingressPort`）、`AppReleaseService.overview`
  （每个应用调用一次 `ingressListenerSetStatus`，按 `p<端口>` 对上服务）
- Modify: `platform-web/src/types.ts`、`platform-web/src/views/ApplicationServiceView.vue`
- Test: `AppReleaseServiceTest`（overview 里的 ingress 字段）

**前端改动**（`ApplicationServiceView.vue` 的「基本」页签，第 140-160 行一带是现有的 expose 逻辑）：
- 暴露方式单选：不暴露 / NodePort / 网关 HTTP / 网关 TCP。
  - 网关两项在 `comps.available('routes')` 为 false 时禁用，用 tooltip 显示 `unavailableReason('routes')`；
  - 已保存的是网关方式时始终可选，不追溯。
- 网关方式下：
  - 显示「对外端口：20003（保存时由平台分配，之后不变）」；还没分配时显示「保存后分配」；
  - 访问地址沿用现有 NodePort 地址的写法（`addr.defaultHost`）：HTTP 显示为 `http://<节点IP>:<端口>`，TCP 显示为 `<节点IP>:<端口>`；
  - 显示 listener 状态：
    - programmed：绿色「已生效」；
    - conflicted：红色，附上 reason / message；
    - 状态未知：灰色。
- 帮助提示（防填错）：
  - 「网关 HTTP 只支持 HTTP，HTTPS 服务请选网关 TCP（TLS 透传）」；
  - 「端口由平台分配，改暴露方式为不暴露后端口会被回收」。
- 运行 `cd platform-web && npm run build`。Expected: 构建通过（用户不需要 UI 测试）。

- [ ] **Step 1: 写失败的后端测试** `overviewShowsIngressStatus`：status 返回 `p20003 programmed` → 服务的 `ingress.programmed == true`、`port == 20003`
- [ ] **Step 2: 运行测试，确认失败**
  - Run: `mvn -q -pl platform-api -am test -Dtest=AppReleaseServiceTest -Dsurefire.failIfNoSpecifiedTests=false`
  - Expected: FAIL
- [ ] **Step 3: 实现后端与前端**
- [ ] **Step 4: 运行测试，确认通过**（命令同 Step 2），再运行 `npm run build`，Expected: PASS
- [ ] **Step 5: 提交** `git commit -m "feat(app-web): gateway expose options, allocated port and status"`

### Task 7: 文档、部署说明、收尾验证

**Files:**
- Modify: `docs/development/application-release.md`
  - §1.5 改成「对外暴露（NodePort / 网关端口）」，写清楚：
    - 三种方式；
    - 端口锁定与冷却；
    - 下线后再发布可能换端口。
  - 部署步骤加上：
    - 重放 `V2026_11_08_1`；
    - 先部署 k8s-server，再部署 platform-api；
    - 到 Istio 组件「编辑」里确认端口池范围并保存一次（生成 Service 端口和 `allowedListeners`）。
  - 验收清单：
    - 网关 HTTP / TCP 都能从 `节点IP:端口` 访问；
    - HTTP 和 TCP 互相切换后端口不变、协议正确；
    - 下线后 ListenerSet 删除、命名空间标签去掉；
    - 缩小端口池会被拦下。
- Modify: `docs/superpowers/specs/2026-11-08-app-istio-exposure-design.md`：§11 标注「第一期已实现（未部署）」
- Modify: memory `app-istio-exposure-plan.md`

- [ ] **Step 1: 全量验证**
  - Run: `mvn -q test`（根目录，全部模块），以及 `cd platform-web && npm run build`
  - Expected: 全部通过。如有失败，按名字逐条写进最终汇报。
- [ ] **Step 2: 更新文档与记忆，并提交** `git commit -m "docs(app): gateway port pool exposure"`
