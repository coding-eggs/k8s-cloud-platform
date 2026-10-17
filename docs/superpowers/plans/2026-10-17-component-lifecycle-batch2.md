# S3.6 批②（其余 10 个组件的安装契约）实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让集群概览里的其余 10 个组件都能从平台安装/升级，且 spec §5 的配置契约全部落进 recipe。

**Architecture:** 沿用批①的统一 Helm 执行器。新增两件基础设施：① recipe 的 values 支持嵌套与占位符（`${cluster.name}`、`${thanos.receive.url}`），否则 `prometheus.prometheusSpec.externalLabels.cluster_name` 这类按集群变化的值写不出来；② chart 一律 `helm pull` 官方/社区包原样入库。组件依赖（`requires`）在批①已实现，本批只填数据。

**Tech Stack:** Java 21 / Spring Boot / Helm 4.2.0 / Vue 3（本批前端零改动）

**Spec:** `docs/superpowers/specs/2026-10-17-component-lifecycle-design.md`（§5 逐组件契约）

## Global Constraints

- **官方 chart 原样入库，不修改模板**；没有官方 chart 的组件用社区 chart，provenance 必须标注「上游无官方 chart」（用户 2026-10-17 裁定）。
- **版本与上游对齐**：目录名 = chart 版本；appVersion 与上游 release 对齐。
- **契约值（🔒）只在 recipe 里写一遍**，用户不可覆盖；可编辑值（⚙️）才进 `editableValues`。
- 既有约束不变：不接管已有 release；不提供卸载；凭据不回显；chart 覆盖靠"版本找不到就报错"。
- 本批**不做**功能级验收（用户 2026-10-17 裁定，单独一批）。
- 迁移文件不需要新增（本批无表结构变化）；权限行无变化。

## Review Focus

1. **嵌套 values 的合并顺序**：上次快照 < 本次可编辑 < 契约。深合并写错会让契约被历史值覆盖（例如 `externalLabels.cluster_name` 被上次的值顶掉）。
2. **占位符缺失时静默下发空串**：`remoteWrite[0].url=""` 装出来的 Prometheus 永远上报不了，且没有任何报错 —— 必须失败退出。
3. **同 release 被两个组件 code 引用**（tekton/tekton-operator、calico/tigera-operator）：并发锁是按 `(cluster, component)` 的，两个 code 可以同时装同一个 release → 必须靠 `requires` 串行（前置必须 HEALTHY），且 values 必须一致。
4. **kube-prometheus-stack 装进 `monitor` 命名空间**：探测判据就写死在那里；装错 ns 的后果是"装成功了但面板永远显示未安装"。
5. **Calico 的 CIDR 契约**：装错直接断网；本批只允许安装到新集群，UI 必须有二次确认并且文案明示风险。

---

### Task 1: values 深合并 + 占位符 + thanos 配置

**Files:**
- Create: `platform-api/src/main/java/com/coding/platformapi/components/ValueTemplate.java`
- Create: `platform-api/src/main/java/com/coding/platformapi/components/DeepMerge.java`
- Modify: `platform-api/src/main/java/com/coding/platformapi/services/ComponentInstallService.java`
- Modify: `platform-api/src/main/resources/application.yaml`（新增 `platform.thanos.receive-url`）
- Test: `platform-api/src/test/java/com/coding/platformapi/components/ValueTemplateTest.java`
- Test: `platform-api/src/test/java/com/coding/platformapi/services/ComponentInstallServiceTest.java`

**Interfaces:**
- Produces: `ValueTemplate.resolve(Map<String,Object> values, Map<String,String> context)` — 递归替换字符串里的 `${key}`；未知 key 抛 `CloudPlatformException`；值为空串且 key 以 `.url` 结尾时抛错（禁止空 URL 下发）。
- Produces: `DeepMerge.merge(Map<String,Object> base, Map<String,Object> overlay)` — 递归合并 Map，overlay 覆盖；List 整体替换。
- Produces: `ComponentInstallService` 构造器新增 `@Value("${platform.thanos.receive-url:}") String thanosReceiveUrl`。

- [ ] **Step 1: 写失败测试（ValueTemplate）**

```java
@Test
void replacesNestedPlaceholders() {
    Map<String, Object> in = Map.of("prometheus", Map.of("prometheusSpec",
            Map.of("externalLabels", Map.of("cluster_name", "${cluster.name}"))));
    assertThat(ValueTemplate.resolve(in, Map.of("cluster.name", "coding-cluster")))
            .isEqualTo(Map.of("prometheus", Map.of("prometheusSpec",
                    Map.of("externalLabels", Map.of("cluster_name", "coding-cluster")))));
}

@Test
void anUnknownPlaceholderFailsLoudly() {
    assertThatThrownBy(() -> ValueTemplate.resolve(Map.of("a", "${nope}"), Map.of()))
            .isInstanceOf(CloudPlatformException.class).hasMessageContaining("nope");
}

@Test
void anEmptyUrlPlaceholderFailsLoudly() {
    // 空 remoteWrite url 装出来的 Prometheus 永远上报不了，且没有报错
    assertThatThrownBy(() -> ValueTemplate.resolve(Map.of("remoteWrite", List.of(Map.of("url", "${thanos.receive.url}"))),
            Map.of("thanos.receive.url", "")))
            .isInstanceOf(CloudPlatformException.class);
}
```

- [ ] **Step 2: 跑测试确认失败**（类不存在 → 编译失败即 RED）
  Run: `mvn -pl platform-api -am -Dtest=ValueTemplateTest -Dsurefire.failIfNoSpecifiedTests=false test`

- [ ] **Step 3: 实现 ValueTemplate + DeepMerge**

```java
public final class ValueTemplate {
    private static final Pattern P = Pattern.compile("\\$\\{([a-zA-Z0-9._-]+)}");
    public static Object resolve(Object value, Map<String, String> context) { /* 递归 Map/List/String */ }
}
```
字符串里含占位符时：全部替换后若结果为空且原模板以 `.url}` 结尾 → 抛错；未知 key → 抛错。

- [ ] **Step 4: 跑测试确认通过**

- [ ] **Step 5: 服务层接入（先写失败测试）**

```java
@Test
void theContractPlaceholdersAreResolvedWithTheClusterNameAndThanosUrl() {
    // apply 后捕获发给 k8s-server 的 values，断言 cluster_name 是真实集群名
}
```

`ComponentInstallService.apply` 在 `mergeValues` 之后、`toDtos` 之前调用
`ValueTemplate.resolve(...)`；context = `Map.of("cluster.name", clusterService.require(clusterId).getClusterName(), "thanos.receive.url", thanosReceiveUrl)`。

- [ ] **Step 6: mergeValues 改为深合并**

可编辑值的 key 以路径形式声明（如 `prometheus.prometheusSpec.retention`），先转成嵌套 Map 再 `DeepMerge.merge`。测试：
```java
@Test
void anEditablePathIsMergedIntoTheNestedContractWithoutOverridingIt() { ... }
```

- [ ] **Step 7: 提交**

```bash
git add platform-api/src/main/java/com/coding/platformapi/components/ValueTemplate.java \
        platform-api/src/main/java/com/coding/platformapi/components/DeepMerge.java \
        platform-api/src/main/java/com/coding/platformapi/services/ComponentInstallService.java \
        platform-api/src/main/resources/application.yaml \
        platform-api/src/test/java/com/coding/platformapi/components/ValueTemplateTest.java \
        platform-api/src/test/java/com/coding/platformapi/services/ComponentInstallServiceTest.java
git commit -m "feat(components): recipe values 支持嵌套合并与集群占位符"
```

---

### Task 2: 官方/社区 chart 入库

**Files（每个 chart 一个目录 + provenance.yaml；目录名 = chart 名，版本号与上游一致）:**
- Create: `k8s-server/src/main/resources/charts/components/prometheus-operator/92.10.1/chart/`（kube-prometheus-stack）
- Create: `k8s-server/src/main/resources/charts/components/thanos/0.47.2/chart/`（thanos-community）
- Create: `k8s-server/src/main/resources/charts/components/gateway-api/1.6.2/chart/`（wiremind，experimental）
- Create: `k8s-server/src/main/resources/charts/components/istio-base/1.30.6/chart/`
- Create: `k8s-server/src/main/resources/charts/components/istio-istiod/1.30.6/chart/`
- Create: `k8s-server/src/main/resources/charts/components/istio-gateway/1.30.6/chart/`
- Create: `k8s-server/src/main/resources/charts/components/istio-cni/1.30.6/chart/`
- Create: `k8s-server/src/main/resources/charts/components/istio-ztunnel/1.30.6/chart/`
- Create: `k8s-server/src/main/resources/charts/components/cert-manager/v1.21.2/chart/`
- Create: `k8s-server/src/main/resources/charts/components/otel-operator/0.126.0/chart/`
- Create: `k8s-server/src/main/resources/charts/components/tigera-operator/v3.33.0/chart/`
- Create: `k8s-server/src/main/resources/charts/components/tekton-operator/0.81.2/chart/`（GitHub release asset）

**注意：** Istio 的 base/istiod/gateway/cni/ztunnel 是**五个独立官方 chart**，版本号都是 1.30.6；
用不同的 `chartCode`（目录名）区分，**不往版本号里塞后缀**。

- [ ] **Step 1: 逐个 `helm pull`（走 7890 代理）并原样入库**
- [ ] **Step 2: 每个目录写 provenance.yaml**（source / repo / version / appVersion / fetched_at / fetch_command / note）
- [ ] **Step 3: 校验 Chart.yaml 版本与目录名一致**
- [ ] **Step 4: 提交**

---

### Task 3: cert-manager + otel-operator recipes

**Files:**
- Modify: `platform-api/src/main/java/com/coding/platformapi/components/ComponentRecipeCatalog.java`
- Test: `platform-api/src/test/java/com/coding/platformapi/components/ComponentRecipeCatalogTest.java`

**契约：**
- cert-manager：release `platform-cert-manager`，ns `cert-manager`，chart `cert-manager` @ `v1.21.2`，契约 `crds.enabled=true`、`crds.keep=true`。
- otel-operator：release `platform-otel-operator`，ns `opentelemetry-operator-system`，chart `otel-operator` @ `0.126.0`，契约 `admissionWebhooks.certManager.enabled=true`，`requires: [cert-manager]`。

- [ ] 写测试断言两个 recipe 的 release/ns/version/requires/契约键
- [ ] 跑测试（RED）
- [ ] 填 catalog
- [ ] 跑测试（GREEN）
- [ ] `helm template` 用契约值渲染两个 chart，确认 values 路径存在
- [ ] 提交

---

### Task 4: gateway-api recipe

**契约：** release `platform-gateway-api`，ns `kube-system`，chart `gateway-api` @ `1.6.2`（experimental CRD chart）；无契约值；无 requires。
**验收：** `helm template` 输出包含 TCPRoute/TLSRoute/UDPRoute 三个 CRD。

---

### Task 5: istio + istio-ambient recipes

**契约：**
- istio：三个 release —— `istio-base`（chart base）、`istiod`（chart istiod）、`external-gateway`（chart gateway），全部 ns `istio-system`。
- istio-ambient：两个 release —— `istio-cni`（chart cni）、`ztunnel`（chart ztunnel），ns `istio-system`，`requires: [istio]`。
**可编辑：** `pilot.replicaCount`（istiod）、`autoscaleEnabled`。

---

### Task 6: tigera-operator + calico recipes

**契约：**
- tigera-operator：release `platform-tigera-operator`，ns `tigera-operator`，chart `tigera-operator` @ `v3.33.0`；契约 `installation.enabled=true`。
- calico：**同一个 release**（幂等），`requires: [tigera-operator]`；契约 `installation.calicoNetwork.ipPools[0].cidr=${calico.podCidr}`、`installation.calicoNetwork.encapsulation=${calico.encapsulation}`（新增两个平台配置，默认值与集群现状一致）。
**UI：** 升级/安装 calico 时二次确认文案必须包含「可能中断集群网络」。

---

### Task 7: prometheus-operator recipe

**契约（BUSINESS 与 MANAGEMENT 相同）：** release `platform-kps`，ns `monitor`，chart `prometheus-operator` @ `92.10.1`；
`prometheus.prometheusSpec.externalLabels.cluster_name=${cluster.name}`、四个 `*SelectorNilUsesHelmValues=false`、四个 `*NamespaceSelector={}`、`alertmanager.alertmanagerSpec.alertmanagerConfigMatcherStrategy.type=OnNamespace`、两个 alertmanagerConfig `*Selector={}`、`remoteWrite=[{url: ${thanos.receive.url}}]`。

**Ruling（执行时定）：平台托管 Thanos 的安装本批不做。**
原因：Thanos 需要对象存储凭据（不能进 values），且必须与集群现有 bucket 布局一致；
本集群已有手工安装的 `th` release，平台按"不接管已有 release"只读。
代价：管理集群的 Thanos 仍需手工安装；`${thanos.receive.url}` 指向既有 Thanos。
chart（thanos 0.47.2）已入库，后续批次补 recipe 即可。

---

### Task 8: tekton + tekton-operator recipes

**契约：** 两个组件指向同一个官方 operator chart release `platform-tekton-operator`（ns `tekton-operator`，chart `tekton-operator` @ `0.81.2`），通过 `extraObjects` 下发 TektonConfig：
```yaml
extraObjects:
  - apiVersion: operator.tekton.dev/v1alpha1
    kind: TektonConfig
    metadata: { name: config }
    spec:
      profile: all
      pipeline:
        coschedule: pipelineruns
        default-container-resource-requirements:
          containers: { requests: {cpu: 100m, memory: 128Mi}, limits: {cpu: "1", memory: 1Gi} }
```
`tekton` 组件 `requires: [tekton-operator]`。

---

### Task 9: 收口

- [ ] `mvn test` 全绿
- [ ] 所有 recipe 的 chart 路径存在性用例自动覆盖
- [ ] 每个 chart 用 `helm template` + 契约值渲染一次（记录在提交说明）
- [ ] 更新 spec §2.3 的组件清单与版本表
- [ ] 更新记忆

## Rulings（执行时做的决定）

1. **没有官方 chart 的组件用社区 chart**（用户 2026-10-17 裁定）：gateway-api → wiremind gateway-api-crds（experimental）；
   thanos → thanos-community。provenance 标注「上游无官方 chart」。
2. **Tekton 走官方 operator chart**（GitHub release asset 里的 `tekton-operator-0.81.2.tgz`），
   TektonConfig 通过 chart 自带的 `extraObjects` 下发 —— 契约值（coschedule / 配额默认资源）只存在于 TektonConfig，
   而 TektonConfig 只有 operator 路径才有。代价：operator 与 pipelines 共用一个 release。
3. **tekton / calico 与其 operator 共用一个 release**（幂等），依赖靠 `requires` 串行。
   代价：第二个组件升级时会重放同一个 release。
4. **Istio 用五个 chartCode**（istio-base / istio-istiod / istio-gateway / istio-cni / istio-ztunnel），
   版本号保持上游 1.30.6，不发明「1.30.6-istiod」这类自定义版本。
5. **空占位符一律硬失败**（不只 URL）：空 CIDR 的 Calico 会断网、空值的 TektonConfig 字段会静默失效。
6. **TektonConfig 的取值以集群实测为准**：`coschedule: workspaces`、`profile: lite`、`targetNamespace: tekton-pipelines`
   （旧记忆里的 `pipelineruns` 与实测不符，已按实测修正）。
7. **功能级验收不在本批**（用户 2026-10-17 裁定）。
8. **平台托管 Thanos 不在本批**（见 Task 7）。
