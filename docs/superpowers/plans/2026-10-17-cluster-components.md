# 集群组件展示（S1.2）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让平台能看见集群上装了哪些它依赖的组件（装没装 / 版本 / 健康 / 命名空间），并把 CI / 服务网格 / 网络 / HPA 四处门控的判据收到同一份派生实现上。

**Architecture:** 目录（platform-api，11 条组件定义）→ 一次活探测（k8s-core 按 `(namespace, kind, 镜像仓库片段)` 读工作负载 → k8s-server UPSTREAM 窄投影端点，带命名空间清单约束）→ `ClusterComponentService` 合成四态并进 Redis 短 TTL 缓存 → 两个投影（管理员全量 / 租户功能旗标）。API group 那一半继续读 `k8s_cluster.capability` 列，不另探。

**Tech Stack:** Java 21 / Spring Boot 4.1.1 / fabric8 7.6.1 / MyBatis / MySQL（**无 Flyway，迁移手工重放**）/ Vue 3 + Element Plus + TypeScript

**Spec:** `docs/superpowers/specs/2026-10-17-cluster-components-design.md` —— 本计划从它派生；两者冲突时**以 spec 为准**。

## Global Constraints

- **本批不做安装/升级/卸载**，也不提供"交给平台管"的开关（spec §1、§10）。
- **组件目录首批 11 条**，一字不差（spec §2.1）：`metrics-server` · `prometheus-operator` · `gateway-api` · `istio` · `istio-ambient` · `tekton` · `tekton-operator` · `calico` · `tigera-operator` · `cert-manager` · `otel-operator`。
- **`k8s_cluster.capability` 列不动**：`KubernetesOperationsFactory` 的 API 版本分派直接读它。组件清单里"API group 存在性"读它、**不另探**。
- **探测成本固定**：每个 `(namespace, kind)` 恰好一次 `list`，与集群的命名空间数量无关（照 `ClusterAggregationOperations` 的"固定 9 次 list"）。
- **四态而非布尔**：`INSTALLED_HEALTHY` / `INSTALLED_UNHEALTHY` / `NOT_INSTALLED` / `UNKNOWN`。探测失败**不得**说成"未安装"。
- **不猜版本后缀**：`1.30.3-distroless` 原样显示。
- **k8s-server 零业务逻辑**：目录与依赖映射只在 platform-api（适配器不读 DB 列做业务判断）。
- **本仓没有 Flyway**：`db/migration/*.sql` 一律**手工重放**，启动日志永远不会出现 `Migrating schema`。**先重放再起新构建**（无行的新端点被 `PermissionCrossCheckRunner` 拒启）。
- **新权限行必须显式绑角色**（不按 code 前缀继承）；角色关联按**显式 id** 写，**不用 LIKE**（`_` 是通配符）。
- 中文注释与提交信息；技术名词保留英文。

## Review Focus

按可能性降序。每一条都必须在下面某个任务的测试里被钉住 —— 不只是"设计上考虑过"。

1. **集群断开 / 探测失败时打开页面** —— 用户期望看到"探测不到"，而不是"没装"。把 `UNKNOWN` 实现成 `NOT_INSTALLED` 是这批最容易犯的错（也是本仓已经修过一次的坑）。→ Task 4 Step 3。
2. **期望副本为 0 的组件**（被 scale 到 0 的 Deployment）—— `ready=0/desired=0` 判成"健康"是错的：它根本没在跑。→ Task 4 Step 2。
3. **租户身份打开 CI 页** —— flags 端点必须对他可达。两行同路径的权限行漏一行，租户就吃 403，而"CI 能不能跑"正是他要的答案。→ Task 5 Step 4（启动 + 真实调用验证）。
4. **组件装在候选命名空间之外**（用户把 Istio 装到 `my-mesh`）—— 显示"未安装"是错的，要说"在平台已知的位置没找到"。→ Task 4 Step 2。
5. **同一镜像仓库片段命中多个工作负载**（升级残留、新旧两版并存）—— 静默挑一个会让人对着一个不知道是哪台的版本号发愁。→ Task 1 Step 1。

---

### Task 1: k8s-core · 工作负载探测（健康字段按 kind 分支 + 版本提取）

**Files:**
- Create: `platform-common/src/main/java/com/coding/common/models/k8s/dto/WorkloadProbeTargetDTO.java`
- Create: `platform-common/src/main/java/com/coding/common/models/k8s/dto/WorkloadProbeResultDTO.java`
- Create: `k8s-core/src/main/java/com/coding/k8score/operations/core/WorkloadProbeOperations.java`
- Test: `k8s-core/src/test/java/com/coding/k8score/operations/core/WorkloadProbeOperationsTest.java`

**Interfaces:**
- Consumes: 无（本批第一块）。
- Produces:
  - `WorkloadProbeTargetDTO{ String namespace; String kind; String imageRepo; String name; }`（`kind` 小写：`deployment` / `statefulset` / `daemonset`）
  - `WorkloadProbeResultDTO{ boolean found; String namespace; String kind; String name; String version; Integer ready; Integer desired; String detail; String error; }`（`error` 非空 = 探测失败）
  - `WorkloadProbeOperations(KubernetesClient).probe(List<WorkloadProbeTargetDTO>) -> List<WorkloadProbeResultDTO>`

- [ ] **Step 1: 写失败的测试**

```java
package com.coding.k8score.operations.core;

import com.coding.common.models.k8s.dto.WorkloadProbeResultDTO;
import com.coding.common.models.k8s.dto.WorkloadProbeTargetDTO;
import io.fabric8.kubernetes.api.model.apps.DaemonSet;
import io.fabric8.kubernetes.api.model.apps.DaemonSetBuilder;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.NamespaceableResource;
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation;
import io.fabric8.kubernetes.client.dsl.RollableScalableResource;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkloadProbeOperationsTest {

    /** Deployment 的 mock 链：inNamespace(ns).list() → 给定列表 */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private MixedOperation<Deployment, ?, RollableScalableResource<Deployment>> deployChain(
            List<Deployment> items) {
        MixedOperation<Deployment, ?, RollableScalableResource<Deployment>> op = mock(MixedOperation.class);
        NonNamespaceOperation ns = mock(NonNamespaceOperation.class);
        when(op.inNamespace(anyString())).thenReturn(ns);
        when(ns.list()).thenReturn(new DeploymentListStub(items));
        return op;
    }

    // ⚠️ 断言前必须 clearInvocations：stubbing 期的调用会混进 verify
    //    （照 ClusterAggregationOperationsTest.Fixture.wire 的注释）

    @Test
    void daemonSetHealthUsesNumberReadyNotReadyReplicas() {
        // ⭐ 真集群实测：calico-node / ztunnel / kube-proxy 的 status.readyReplicas 是空的，
        //    真正填的是 numberReady / desiredNumberScheduled。用错字段会一律显示 "0/0 不就绪"。
        DaemonSet ds = new DaemonSetBuilder()
                .withNewMetadata().withName("calico-node").withNamespace("calico-system").endMetadata()
                .withNewSpec().withNewTemplate().withNewSpec()
                    .addNewContainer().withName("calico-node")
                        .withImage("docker.io/calico/node:v3.30.3").endContainer()
                .endSpec().endTemplate().endSpec()
                .withNewStatus().withNumberReady(3).withDesiredNumberScheduled(3).endStatus()
                .build();
        // 故意把 readyReplicas 留空 —— 这正是真集群的样子
        WorkloadProbeOperations ops = opsWithDaemonSets(List.of(ds));

        List<WorkloadProbeResultDTO> r = ops.probe(List.of(target("calico-system", "daemonset", "calico/node", null)));

        assertThat(r).hasSize(1);
        assertThat(r.get(0).isFound()).isTrue();
        assertThat(r.get(0).getReady()).isEqualTo(3);
        assertThat(r.get(0).getDesired()).isEqualTo(3);
        assertThat(r.get(0).getDetail()).contains("3/3");
    }

    @Test
    void deploymentHealthUsesReadyReplicas() {
        Deployment d = new DeploymentBuilder()
                .withNewMetadata().withName("metrics-server").withNamespace("kube-system").endMetadata()
                .withNewSpec().withNewTemplate().withNewSpec()
                    .addNewContainer().withName("metrics-server")
                        .withImage("registry.k8s.io/metrics-server/metrics-server:v0.7.2").endContainer()
                .endSpec().endTemplate().endSpec()
                .withNewStatus().withReadyReplicas(1).withReplicas(1).endStatus()
                .build();

        List<WorkloadProbeResultDTO> r = opsWithDeployments(List.of(d))
                .probe(List.of(target("kube-system", "deployment", "metrics-server", null)));

        assertThat(r.get(0).getReady()).isEqualTo(1);
        assertThat(r.get(0).getDesired()).isEqualTo(1);
    }

    @Test
    void versionPrefersTheAppKubernetesIoVersionLabel() {
        Deployment d = new DeploymentBuilder()
                .withNewMetadata().withName("istiod").withNamespace("istio-system")
                    .addToLabels("app.kubernetes.io/version", "1.30.3").endMetadata()
                .withNewSpec().withNewTemplate().withNewSpec()
                    .addNewContainer().withName("discovery")
                        .withImage("registry.istio.io/release/pilot:1.30.3-distroless").endContainer()
                .endSpec().endTemplate().endSpec()
                .build();

        List<WorkloadProbeResultDTO> r = opsWithDeployments(List.of(d))
                .probe(List.of(target("istio-system", "deployment", "release/pilot", null)));

        assertThat(r.get(0).getVersion()).isEqualTo("1.30.3");
    }

    @Test
    void versionFallsBackToImageTagAndStripsTheDigest() {
        // 真集群实测：tekton 的镜像是 `...controller-<hash>:v1.15.3@sha256:...`，digest 必须剥掉
        Deployment d = withImage("tekton-pipelines-controller", "tekton-pipelines",
                "ghcr.io/tektoncd/pipeline/controller-10a3e32:v1.15.3@sha256:b37f1ab9");

        List<WorkloadProbeResultDTO> r = opsWithDeployments(List.of(d))
                .probe(List.of(target("tekton-pipelines", "deployment", "pipeline/controller-", null)));

        assertThat(r.get(0).getVersion()).isEqualTo("v1.15.3");
    }

    @Test
    void versionKeepsSuffixesLikeDistroless() {
        // 不猜后缀：`1.30.3-distroless` 原样显示，不去猜 -distroless 能不能丢
        Deployment d = withImage("ztunnel", "istio-system", "registry.istio.io/release/ztunnel:1.30.3-distroless");

        List<WorkloadProbeResultDTO> r = opsWithDeployments(List.of(d))
                .probe(List.of(target("istio-system", "deployment", "release/ztunnel", null)));

        assertThat(r.get(0).getVersion()).isEqualTo("1.30.3-distroless");
    }

    @Test
    void aRegistryPortIsNotATag() {
        // `registry.internal:5000/foo` 里的冒号是端口不是 tag —— 取最后一个冒号前要看它有没有越过最后一个 /
        Deployment d = withImage("x", "kube-system", "registry.internal:5000/foo/bar");

        List<WorkloadProbeResultDTO> r = opsWithDeployments(List.of(d))
                .probe(List.of(target("kube-system", "deployment", "foo/bar", null)));

        assertThat(r.get(0).getVersion()).isNull();
    }

    @Test
    void matchingByImageRepoFindsReleasePrefixedNames() {
        // ⭐ 真集群实测：`ps-kube-prometheus-stack-operator` —— 按名字写死就找不到，
        //    换个 Helm release 名（monitoring-）同样能找到，这是决策 4 的全部理由
        Deployment d = withImage("ps-kube-prometheus-stack-operator", "monitor",
                "quay.io/prometheus-operator/prometheus-operator:v0.93.1");

        List<WorkloadProbeResultDTO> r = opsWithDeployments(List.of(d))
                .probe(List.of(target("monitor", "deployment", "prometheus-operator/prometheus-operator", null)));

        assertThat(r.get(0).isFound()).isTrue();
        assertThat(r.get(0).getName()).isEqualTo("ps-kube-prometheus-stack-operator");
    }

    @Test
    void multipleHitsPickTheNameSortedFirstAndSaySoInDetail() {
        // Review Focus #5：升级残留 / 新旧两版并存时静默挑一个会让人对着不知道是哪台的版本号发愁
        Deployment a = withImage("calico-typha-b", "calico-system", "docker.io/calico/typha:v3.30.3");
        Deployment b = withImage("calico-typha-a", "calico-system", "docker.io/calico/typha:v3.29.0");

        List<WorkloadProbeResultDTO> r = opsWithDeployments(List.of(a, b))
                .probe(List.of(target("calico-system", "deployment", "calico/typha", null)));

        assertThat(r.get(0).getName()).isEqualTo("calico-typha-a"); // 名字序第一个
        assertThat(r.get(0).getDetail()).contains("命中 2 个");
    }

    @Test
    void oneListPerNamespaceAndKindRegardlessOfHowManyTargets() {
        // ⭐ 成本约束：三个候选共用 (namespace, kind) → 只 list 一次
        MixedOperation<Deployment, ?, ?> chain = deployChain(List.of());
        KubernetesClient client = clientWith(chain);
        List<WorkloadProbeTargetDTO> targets = List.of(
                target("kube-system", "deployment", "a/b", null),
                target("kube-system", "deployment", "c/d", null),
                target("kube-system", "deployment", null, "metrics-server"));
        clearInvocations(chain);

        new WorkloadProbeOperations(client).probe(targets);

        verify(chain, times(1)).inNamespace("kube-system");
        verify(chain, never()).inNamespace("istio-system");
        verify(chain, never()).inAnyNamespace();
    }

    @Test
    void aFailedListIsReportedAsErrorNotEmptyResult() {
        // 探测失败必须有别于"没装"（四态里的 UNKNOWN 全靠这个 error 字段）
        MixedOperation<Deployment, ?, ?> chain = mock(MixedOperation.class);
        NonNamespaceOperation ns = mock(NonNamespaceOperation.class);
        when(chain.inNamespace(anyString())).thenReturn(ns);
        when(ns.list()).thenThrow(new RuntimeException("连接被拒"));

        List<WorkloadProbeResultDTO> r = new WorkloadProbeOperations(clientWith(chain))
                .probe(List.of(target("kube-system", "deployment", "a/b", null)));

        assertThat(r.get(0).isFound()).isFalse();
        assertThat(r.get(0).getError()).contains("连接被拒");
    }
}
```

⚠️ 上面的 `DeploymentListStub` / `withImage` / `clientWith` / `opsWithDeployments` / `opsWithDaemonSets` / `target` 是**本测试类的私有夹具**，照下面的形状补：

```java
    /** fabric8 的 list 结果类型太多，用一个最小的实现顶替 */
    private static class DeploymentListStub
            implements io.fabric8.kubernetes.client.KubernetesResourceList<Deployment> {
        private final List<Deployment> items;
        DeploymentListStub(List<Deployment> items) { this.items = items; }
        @Override public List<Deployment> getItems() { return items; }
        @Override public void setItems(List<Deployment> items) { throw new UnsupportedOperationException(); }
        @Override public io.fabric8.kubernetes.api.model.ListMeta getMetadata() { return null; }
        @Override public void setMetadata(io.fabric8.kubernetes.api.model.ListMeta m) { }
        @Override public String getApiVersion() { return "apps/v1"; }
        @Override public String getKind() { return "DeploymentList"; }
    }

    private static WorkloadProbeTargetDTO target(String ns, String kind, String imageRepo, String name) {
        WorkloadProbeTargetDTO t = new WorkloadProbeTargetDTO();
        t.setNamespace(ns); t.setKind(kind); t.setImageRepo(imageRepo); t.setName(name);
        return t;
    }

    private static Deployment withImage(String name, String ns, String image) {
        return new DeploymentBuilder()
                .withNewMetadata().withName(name).withNamespace(ns).endMetadata()
                .withNewSpec().withNewTemplate().withNewSpec()
                    .addNewContainer().withName("c").withImage(image).endContainer()
                .endSpec().endTemplate().endSpec().build();
    }

    private static KubernetesClient clientWith(MixedOperation<Deployment, ?, ?> deployChain) {
        KubernetesClient client = mock(KubernetesClient.class);
        when(client.apps().deployments()).thenReturn(deployChain);
        return client;
    }

    private static WorkloadProbeOperations opsWithDeployments(List<Deployment> items) {
        return new WorkloadProbeOperations(clientWith(deployChain(items)));
    }
```

（`opsWithDaemonSets` 同形，走 `client.apps().daemonSets()`。）

- [ ] **Step 2: 跑测试确认它失败**

Run: `mvn -pl k8s-core -am test -Dtest=WorkloadProbeOperationsTest`（在仓库根）
Expected: 编译失败 —— `WorkloadProbeOperations` / 两个 DTO 都不存在。

- [ ] **Step 3: 写两个 DTO**

```java
package com.coding.common.models.k8s.dto;

import lombok.Data;

/** 一条工作负载探测候选（platform-api 的组件目录决定探什么，适配器只负责读）。 */
@Data
public class WorkloadProbeTargetDTO {
    private String namespace;
    /** 小写：deployment / statefulset / daemonset（与 K8sWorkload DTO 的 kind 口径一致） */
    private String kind;
    /** 镜像仓库片段 —— **主判据**，跨安装稳定（Helm release 名可变，仓库路径不可变） */
    private String imageRepo;
    /** 工作负载名 —— 快路径，只给名字稳定的组件写（istiod / ztunnel / calico-node） */
    private String name;
}
```

```java
package com.coding.common.models.k8s.dto;

import lombok.Data;

/** 一条工作负载探测结果。{@code error} 非空 = 探测失败（消费方据此判 UNKNOWN，不能说成"未安装"）。 */
@Data
public class WorkloadProbeResultDTO {
    private boolean found;
    private String namespace;
    private String kind;
    /** 命中的工作负载名 */
    private String name;
    /** app.kubernetes.io/version 标签优先，否则 image tag 原文（已剥 digest）；都没有 → null */
    private String version;
    private Integer ready;
    private Integer desired;
    /** 人读的一句话："3/3 就绪" / "命中 2 个（a, b），取第一个" */
    private String detail;
    /** 非空 = 这次探测失败（集群不可达 / 无权限），与 found=false 是两件事 */
    private String error;
}
```

- [ ] **Step 4: 实现 `WorkloadProbeOperations`**

```java
package com.coding.k8score.operations.core;

import com.coding.common.models.k8s.dto.WorkloadProbeResultDTO;
import com.coding.common.models.k8s.dto.WorkloadProbeTargetDTO;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.apps.DaemonSet;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.StatefulSet;
import io.fabric8.kubernetes.client.KubernetesClient;
import lombok.RequiredArgsConstructor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * 按候选读工作负载的状态（组件探测的数据面）。
 *
 * <h2>成本是固定的，与集群规模无关</h2>
 * 每个 {@code (namespace, kind)} 恰好一次 {@code list} —— 三个候选落在同一 (ns, kind) 上也只 list 一次。
 * 这条约束照 {@code ClusterAggregationOperations} 的"固定 9 次 list"同形，有单测盯着。
 *
 * <h2>为什么按镜像仓库匹配而不是按名字</h2>
 * 真集群实测：相当一部分基础设施工作负载的名字带 Helm release 前缀
 * （{@code ps-kube-prometheus-stack-operator} / {@code th-thanos-query}）。按名字写死，
 * 用户换个 release 名就探测不到 —— 而"找得到"正是这个功能要解决的问题。
 * 名字只当快路径（istiod / ztunnel / calico-node 这些名字稳定，能少比几次）。
 */
@RequiredArgsConstructor
public class WorkloadProbeOperations {

    private final KubernetesClient client;

    public List<WorkloadProbeResultDTO> probe(List<WorkloadProbeTargetDTO> targets) {
        Map<String, List<WorkloadProbeTargetDTO>> byChain = new LinkedHashMap<>();
        for (WorkloadProbeTargetDTO t : targets) {
            byChain.computeIfAbsent(t.getNamespace() + "\u0000" + t.getKind(), k -> new ArrayList<>()).add(t);
        }
        List<WorkloadProbeResultDTO> out = new ArrayList<>();
        for (List<WorkloadProbeTargetDTO> group : byChain.values()) {
            // 一次 list 服务这一组的全部候选
            WorkloadProbeTargetDTO head = group.get(0);
            List<Cand> listed;
            String error = null;
            try {
                listed = list(head.getNamespace(), head.getKind());
            } catch (Exception e) {
                listed = List.of();
                error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            }
            for (WorkloadProbeTargetDTO t : group) {
                out.add(error == null ? match(t, listed) : failed(t, error));
            }
        }
        return out;
    }

    // ---------- 内部 ----------

    /** 一个候选命中的统一视图：不同 kind 的对象在这里被拍平成同样的四件事 */
    private record Cand(String name, List<Container> containers, String versionLabel,
                        Integer ready, Integer desired) {}

    private List<Cand> list(String namespace, String kind) {
        return switch (kind) {
            case "deployment" -> client.apps().deployments().inNamespace(namespace).list().getItems().stream()
                    .map(d -> new Cand(d.getMetadata().getName(), containers(d.getSpec().getTemplate().getSpec().getContainers()),
                            versionLabelOf(d.getMetadata()),
                            nz(d.getStatus() == null ? null : d.getStatus().getReadyReplicas()),
                            nz(d.getStatus() == null ? null : d.getStatus().getReplicas())))
                    .toList();
            case "statefulset" -> client.apps().statefulSets().inNamespace(namespace).list().getItems().stream()
                    .map(s -> new Cand(s.getMetadata().getName(), containers(s.getSpec().getTemplate().getSpec().getContainers()),
                            versionLabelOf(s.getMetadata()),
                            nz(s.getStatus() == null ? null : s.getStatus().getReadyReplicas()),
                            nz(s.getStatus() == null ? null : s.getStatus().getReplicas())))
                    .toList();
            // ⚠️ DaemonSet 的健康字段**不是** readyReplicas（实测为空），是 numberReady/desiredNumberScheduled
            case "daemonset" -> client.apps().daemonSets().inNamespace(namespace).list().getItems().stream()
                    .map(s -> new Cand(s.getMetadata().getName(), containers(s.getSpec().getTemplate().getSpec().getContainers()),
                            versionLabelOf(s.getMetadata()),
                            nz(s.getStatus() == null ? null : s.getStatus().getNumberReady()),
                            nz(s.getStatus() == null ? null : s.getStatus().getDesiredNumberScheduled())))
                    .toList();
            default -> throw new IllegalArgumentException("不支持的 kind：" + kind);
        };
    }

    private static String versionLabelOf(io.fabric8.kubernetes.api.model.ObjectMeta md) {
        Map<String, String> labels = md == null ? null : md.getLabels();
        return labels == null ? null : labels.get("app.kubernetes.io/version");
    }

    private static List<Container> containers(List<Container> cs) {
        return cs == null ? List.of() : cs;
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }

    private WorkloadProbeResultDTO match(WorkloadProbeTargetDTO t, List<Cand> listed) {
        // 名字序排序：命中多个时取谁必须稳定（否则同一个集群每次刷新看到的版本号会跳）
        List<Cand> matched = listed.stream().filter(c -> matches(t, c))
                .sorted(java.util.Comparator.comparing(Cand::name)).toList();

        WorkloadProbeResultDTO r = new WorkloadProbeResultDTO();
        r.setNamespace(t.getNamespace());
        r.setKind(t.getKind());
        if (matched.isEmpty()) {
            r.setFound(false);
            // ⚠️ 措辞很讲究：候选命名空间之外的安装位置我们**没去看**，所以不能说"没装"
            r.setDetail("在平台已知的位置（" + t.getNamespace() + "）没有找到");
            return r;
        }
        Cand hit = matched.get(0);
        r.setFound(true);
        r.setName(hit.name());
        r.setReady(hit.ready());
        r.setDesired(hit.desired());
        r.setVersion(versionOf(hit));
        r.setDetail(matched.size() == 1
                ? hit.ready() + "/" + hit.desired() + " 就绪"
                : "命中 " + matched.size() + " 个（" + String.join(", ", matched.stream().map(Cand::name).toList())
                        + "），取 " + hit.name());
        return r;
    }

    private static WorkloadProbeResultDTO failed(WorkloadProbeTargetDTO t, String error) {
        WorkloadProbeResultDTO r = new WorkloadProbeResultDTO();
        r.setNamespace(t.getNamespace());
        r.setKind(t.getKind());
        r.setFound(false);
        r.setError(error);
        return r;
    }

    /** 镜像仓库片段命中**任一**容器的镜像即算（多容器组件存在，如 whisker） */
    private static boolean matches(WorkloadProbeTargetDTO t, Cand c) {
        if (t.getName() != null && !t.getName().isBlank() && t.getName().equals(c.name())) {
            return true;
        }
        if (t.getImageRepo() == null || t.getImageRepo().isBlank()) {
            return false;
        }
        return c.containers().stream()
                .map(Container::getImage)
                .filter(Objects::nonNull)
                .anyMatch(img -> img.contains(t.getImageRepo()));
    }

    /**
     * {@code app.kubernetes.io/version} 标签优先 —— 但实测多数组件没有它
     * （coredns / otel-operator / prometheus-operator 都没有，只有 Helm 装的 istiod 有），
     * 所以 image tag 才是主来源，标签只是"更干净的那个"。
     */
    private static String versionOf(Cand c) {
        if (c.versionLabel() != null && !c.versionLabel().isBlank()) {
            return c.versionLabel();
        }
        return c.containers().stream()
                .map(Container::getImage)
                .filter(Objects::nonNull)
                .map(WorkloadProbeOperations::tagOf)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    /** image ref → tag；无 tag（只带 digest / 无 tag）返回 null */
    static String tagOf(String image) {
        if (image == null) {
            return null;
        }
        // 剥 digest：`x:v1@sha256:…` → `x:v1`
        String noDigest = image.contains("@") ? image.substring(0, image.indexOf('@')) : image;
        int colon = noDigest.lastIndexOf(':');
        int slash = noDigest.lastIndexOf('/');
        // 冒号在最后一个 / 之前 = registry 端口（registry.internal:5000/foo），不是 tag
        return colon <= slash ? null : noDigest.substring(colon + 1);
    }
}
```

本任务**不动** `KubernetesOperationsFactory` —— 拿到 client 的工厂入口
（`getWorkloadProbeOperation(clusterId)`）由 Task 2 那个消费它的端点一起加。

- [ ] **Step 5: 跑测试确认它通过**

Run: `mvn -pl k8s-core -am test -Dtest=WorkloadProbeOperationsTest`
Expected: PASS（10 个测试）。

- [ ] **Step 6: 变异验一下测试真的会咬（两条）**

1. 把 `list()` 里 daemonset 分支的 `getNumberReady()` 改成 `getReadyReplicas()` →
   `daemonSetHealthUsesNumberReadyNotReadyReplicas` 必须变红（ready 变 0）。改回。
2. 把 `probe()` 里按 `(namespace, kind)` 分组的 `byChain` 去掉、改成每个 target 各 list 一次 →
   `oneListPerNamespaceAndKindRegardlessOfHowManyTargets` 必须变红。改回。

- [ ] **Step 7: 提交**

```bash
git add platform-common/src/main/java/com/coding/common/models/k8s/dto/WorkloadProbe*DTO.java \
        k8s-core/src/main/java/com/coding/k8score/operations/core/WorkloadProbeOperations.java \
        k8s-core/src/test/java/com/coding/k8score/operations/core/WorkloadProbeOperationsTest.java
git commit -m "feat(components): k8s-core 工作负载探测（健康字段按 kind 分支、按镜像仓库匹配）"
```

---

### Task 2: k8s-server · 窄投影端点 + 命名空间清单约束

**Files:**
- Create: `platform-common/src/main/java/com/coding/common/constants/ProbeNamespaces.java`
- Create: `platform-common/src/main/java/com/coding/common/models/k8s/dto/WorkloadProbeRequestDTO.java`
- Modify: `k8s-core/src/main/java/com/coding/k8score/factory/KubernetesOperationsFactory.java`（加 `getWorkloadProbeOperation(clusterId)`）
- Create: `k8s-server/src/main/java/com/coding/k8sserver/controllers/cluster/WorkloadProbeController.java`
- Test: `k8s-server/src/test/java/com/coding/k8sserver/controllers/cluster/WorkloadProbeControllerTest.java`

**Interfaces:**
- Consumes: Task 1 的两个 DTO（`WorkloadProbeTargetDTO` / `WorkloadProbeResultDTO`）。
- Produces:
  - `POST /workload-probes`，body `WorkloadProbeRequestDTO{ String clusterId; List<WorkloadProbeTargetDTO> targets; }`，
    返回 `ResponseData<List<WorkloadProbeResultDTO>>`；边界 `AccessBoundary.UPSTREAM`。
  - `KubernetesOperationsFactory.getWorkloadProbeOperation(String clusterId) -> WorkloadProbeOperations`

- [ ] **Step 1: 写失败的测试**

```java
package com.coding.k8sserver.controllers.cluster;

import com.coding.common.models.k8s.dto.WorkloadProbeRequestDTO;
import com.coding.common.models.k8s.dto.WorkloadProbeResultDTO;
import com.coding.common.models.k8s.dto.WorkloadProbeTargetDTO;
import com.coding.common.models.system.ResponseData;
import com.coding.k8score.factory.KubernetesOperationsFactory;
import com.coding.k8score.operations.core.WorkloadProbeOperations;
import com.coding.k8sserver.components.AccessBoundary;
import com.coding.k8sserver.components.ResourceAccessResolver;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WorkloadProbeControllerTest {

    /** 注入两条清单，测试不受真实默认值变化影响 */
    private static final List<String> ALLOWED = List.of("kube-system", "istio-system");

    private static WorkloadProbeTargetDTO t(String ns, String kind, String repo) {
        WorkloadProbeTargetDTO d = new WorkloadProbeTargetDTO();
        d.setNamespace(ns);
        d.setKind(kind);
        d.setImageRepo(repo);
        return d;
    }

    private static WorkloadProbeRequestDTO req(WorkloadProbeTargetDTO... targets) {
        WorkloadProbeRequestDTO r = new WorkloadProbeRequestDTO();
        r.setClusterId("c1");
        r.setTargets(List.of(targets));
        return r;
    }

    private static WorkloadProbeController controller() {
        KubernetesOperationsFactory factory = mock(KubernetesOperationsFactory.class);
        when(factory.getWorkloadProbeOperation("c1")).thenReturn(mock(WorkloadProbeOperations.class));
        return new WorkloadProbeController(factory, mock(ResourceAccessResolver.class), ALLOWED);
    }

    @Test
    void theEndpointIsUpstreamBoundaryNotPlatform() {
        // ⭐ 租户进 CI 页会触发这次探测，PLATFORM 边界会让它 403
        assertThat(controller().accessBoundary()).isEqualTo(AccessBoundary.UPSTREAM);
    }

    @Test
    void aTargetOutsideTheConfiguredListIsRejected() {
        // ⭐ 这条端点是唯一能按命名空间读工作负载的路径。放开等于给租户一个跨租户探测器：
        //    拿自己的 token 传 namespace=milvus 就能问出别人命名空间里有什么。
        assertThatThrownBy(() -> controller().probe(req(t("milvus", "deployment", "x/y"))))
                .hasMessageContaining("milvus");
    }

    @Test
    void oneOutOfBoundsTargetRejectsTheWholeRequest() {
        // 静默跳过越界项 = 平台侧把"被拒"当成"没装"，与「探测失败不能说成没装」同一条原则
        assertThatThrownBy(() -> controller().probe(req(
                t("kube-system", "deployment", "metrics-server"),
                t("milvus", "deployment", "x/y"))))
                .hasMessageContaining("milvus");
    }

    @Test
    void anEmptyTargetListIsHarmless() {
        ResponseData<List<WorkloadProbeResultDTO>> r = controller().probe(req());
        assertThat(r.getData()).isEmpty();
    }
}
```

- [ ] **Step 2: 跑测试确认它失败**

Run: `mvn -pl k8s-server -am test -Dtest=WorkloadProbeControllerTest`
Expected: 编译失败 —— `WorkloadProbeController` / `ProbeNamespaces` 不存在。

- [ ] **Step 3: 写共享的默认清单常量**

```java
package com.coding.common.constants;

import java.util.List;

/**
 * 允许被探测的命名空间清单（组件探测用）。
 *
 * <h2>语义（为什么这个清单重要）</h2>
 * 把一个命名空间加进这个清单，等于**允许任何已认证用户探测该命名空间内工作负载的名称、镜像与副本数**。
 * 它是一道可审计的规则，不是随口给的默认值 —— k8s-server 的 {@code /workload-probes}
 * 是唯一一条能按命名空间读工作负载的路径。
 *
 * <h2>两个模块共用同一份默认值</h2>
 * k8s-server 拿它当配置项默认值（{@code platform.k8s.probe-namespaces}），platform-api 的目录
 * 一致性测试拿它断言"目录里的候选都落在清单内"。**不能各写一份** —— 写两份的结果是
 * 平台侧发出越界探测、被 400 拒掉，而症状是页面上写着"未安装"。
 */
public final class ProbeNamespaces {

    /** 目录真正要探的那几个；清单越小越好 */
    public static final List<String> DEFAULT = List.of(
            "kube-system",
            "istio-system",
            "monitor",
            "tekton-pipelines",
            "tekton-operator",
            "cert-manager",
            "calico-system",
            "tigera-operator",
            "opentelemetry-operator-system");

    /** 供 `@Value("${platform.k8s.probe-namespaces:" + DEFAULT_CSV + "}")` 用（编译期常量，注解里合法） */
    public static final String DEFAULT_CSV = String.join(",", DEFAULT);

    private ProbeNamespaces() {
    }
}
```

请求体 DTO：

```java
package com.coding.common.models.k8s.dto;

import lombok.Data;

import java.util.List;

/** {@code POST /workload-probes} 的请求体。clusterId 在 body 里而不是 query —— 与既有派生查询同形。 */
@Data
public class WorkloadProbeRequestDTO {
    private String clusterId;
    private List<WorkloadProbeTargetDTO> targets;
}
```

工厂入口（加在 `KubernetesOperationsFactory` 里，紧挨 `getClusterAggregationOperation`，同形）：

```java
    /**
     * 工作负载探测（集群级，admin client）：按候选读工作负载状态。非资源 CRUD，独立返回具体类型
     * （同 {@link #getClusterAggregationOperation}）。
     * <p>用 admin client：租户 SA 的 K8s RBAC 模板里没有 kube-system / istio-system 的读权限，
     * 也不该为一次探测开。
     */
    public WorkloadProbeOperations getWorkloadProbeOperation(String clusterId) {
        return new WorkloadProbeOperations(clientFactory.getAdminClient(clusterId));
    }
```

- [ ] **Step 4: 实现 controller**

```java
package com.coding.k8sserver.controllers.cluster;

import com.coding.common.constants.ProbeNamespaces;
import com.coding.common.models.k8s.dto.WorkloadProbeResultDTO;
import com.coding.common.models.k8s.dto.WorkloadProbeTargetDTO;
import com.coding.common.models.k8s.dto.WorkloadProbeRequestDTO;
import com.coding.common.models.system.ResponseData;
import com.coding.k8score.factory.KubernetesOperationsFactory;
import com.coding.k8score.operations.core.WorkloadProbeOperations;
import com.coding.k8sserver.components.AccessBoundary;
import com.coding.k8sserver.components.AccessBoundaryAware;
import com.coding.k8sserver.components.ResourceAccessResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 集群域 - 工作负载探测窄投影（{@code POST /workload-probes}）。
 * 消费方是 platform-api 的组件清单 —— 它要判"这个集群装了没有 / 什么版本 / 健康吗"。
 *
 * <h2>为什么单开一个类（而不是往 ClusterAdminController 加方法）</h2>
 * {@code BoundaryAuthorizationManager} 读的是 <b>handler 所属类</b>声明的 {@link AccessBoundary}。
 * 往 PLATFORM 类上加方法，新方法一样要求调用方持有 {@code PLATFORM_SCOPE} —— 而本端点
 * <b>租户必须能到</b>：门控收口之后，租户进 CI 页 / 网格页就会触发这次探测，
 * 否则冷缓存时只能对他答"不知道"，而"能不能跑 CI"正是他要的答案。
 * 故单开一个类声明 {@code UPSTREAM}，与 {@link StorageClassProvisionerController} /
 * {@link NodeAddressController} 完全同形。
 *
 * <h2>边界：UPSTREAM + 命名空间清单（本类自己兜住的那一半）</h2>
 * 本层不做租户边界判定，收窄在上游 platform-api（各自功能页的权限码）。
 * 但**命名空间清单必须在本层**：它是唯一一条能按命名空间读工作负载的路径，
 * 裸着放开的后果是租户拿自己的 token 传别人的命名空间就能问出"那里有没有叫 X 的 Deployment"。
 * 清单之外的候选 → **整请求 400**，不静默跳过（静默跳过会让平台侧把"被拒"当成"没装"）。
 *
 * <p><b>已知并显式接受的残留</b>：持任意已认证 token 直连本端点，可读到清单内命名空间的
 * 工作负载名称、镜像与副本数。实际新增面很窄 —— 这类集群上那些命名空间本来就分配给租户，
 * 该租户今天用 {@code /workloads/list} 就能读到同样内容。与 PV / 节点 IP / StorageClass
 * 三条同形，闭合它需要"只接受 platform-api 调用"的内部凭证。
 */
@Tag(name = "集群域-工作负载探测", description = "按候选读工作负载状态（组件清单用，UPSTREAM 边界）")
@RestController
@RequestMapping("/workload-probes")
public class WorkloadProbeController implements AccessBoundaryAware {

    private final KubernetesOperationsFactory operationsFactory;
    private final ResourceAccessResolver accessResolver;
    private final List<String> probeNamespaces;

    public WorkloadProbeController(KubernetesOperationsFactory operationsFactory,
                                   ResourceAccessResolver accessResolver,
                                   @Value("${platform.k8s.probe-namespaces:" + ProbeNamespaces.DEFAULT_CSV + "}")
                                   List<String> probeNamespaces) {
        this.operationsFactory = operationsFactory;
        this.accessResolver = accessResolver;
        this.probeNamespaces = probeNamespaces;
    }

    @Override
    public AccessBoundary accessBoundary() {
        return AccessBoundary.UPSTREAM;
    }

    @PostMapping
    @Operation(summary = "按候选读工作负载状态",
            description = "回 found / 命中名 / 版本 / ready / desired / detail / error。"
                    + "候选的命名空间必须在 platform.k8s.probe-namespaces 清单内，否则整请求 400。")
    public ResponseData<List<WorkloadProbeResultDTO>> probe(@RequestBody WorkloadProbeRequestDTO request) {
        accessResolver.assertClusterAccess(request.getClusterId());
        List<WorkloadProbeTargetDTO> targets = request.getTargets() == null ? List.of() : request.getTargets();
        for (WorkloadProbeTargetDTO t : targets) {
            if (!probeNamespaces.contains(t.getNamespace())) {
                throw new CloudPlatformException(EnumResponseType.ERROR,
                        "命名空间 " + t.getNamespace() + " 不在允许探测的清单内");
            }
        }
        if (targets.isEmpty()) {
            return new ResponseData<>(List.of());
        }
        return new ResponseData<>(operationsFactory.getWorkloadProbeOperation(request.getClusterId()).probe(targets));
    }
}
```

（`CloudPlatformException` / `EnumResponseType` 的 import 与构造形态照 `PodLogQuery` 里的用法。）

- [ ] **Step 5: 跑测试确认它通过**

Run: `mvn -pl k8s-server -am test -Dtest=WorkloadProbeControllerTest`
Expected: PASS（4 个测试）。

- [ ] **Step 6: 跑 k8s-server 全量（别把既有测试弄红）**

Run: `mvn -pl k8s-server -am test`
Expected: 全绿。

- [ ] **Step 7: 提交**

```bash
git add platform-common/src/main/java/com/coding/common/constants/ProbeNamespaces.java \
        platform-common/src/main/java/com/coding/common/models/k8s/dto/WorkloadProbeRequestDTO.java \
        k8s-core/src/main/java/com/coding/k8score/factory/KubernetesOperationsFactory.java \
        k8s-server/src/main/java/com/coding/k8sserver/controllers/cluster/WorkloadProbeController.java \
        k8s-server/src/test/java/com/coding/k8sserver/controllers/cluster/WorkloadProbeControllerTest.java
git commit -m "feat(components): k8s-server 工作负载探测窄投影（UPSTREAM + 命名空间清单）"
```

---

### Task 3: platform-api · 组件目录 + 一致性测试

**Files:**
- Create: `platform-api/src/main/java/com/coding/platformapi/components/ComponentDef.java`
- Create: `platform-api/src/main/java/com/coding/platformapi/components/ComponentProbe.java`
- Create: `platform-api/src/main/java/com/coding/platformapi/components/ClusterComponentCatalog.java`
- Test: `platform-api/src/test/java/com/coding/platformapi/components/ClusterComponentCatalogTest.java`

**Interfaces:**
- Consumes: `ProbeNamespaces.DEFAULT`（Task 2）。
- Produces:
  - `ComponentProbe(String namespace, String kind, String imageRepo, String name)`
  - `ComponentDef(String code, String name, String apiGroup, List<ComponentProbe> probes, List<String> features)`
  - `ClusterComponentCatalog`：`all()` / `byCode(String)` / `featureCodes()` / `componentsOf(String featureCode)`

- [ ] **Step 1: 写失败的测试**

```java
package com.coding.platformapi.components;

import com.coding.common.constants.ProbeNamespaces;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ClusterComponentCatalogTest {

    private final ClusterComponentCatalog catalog = new ClusterComponentCatalog();

    @Test
    void firstBatchIsExactlyTheElevenAgreedOnes() {
        assertThat(catalog.all()).extracting(ComponentDef::code).containsExactly(
                "metrics-server", "prometheus-operator", "gateway-api", "istio", "istio-ambient",
                "tekton", "tekton-operator", "calico", "tigera-operator", "cert-manager", "otel-operator");
    }

    @Test
    void everyComponentHasAtLeastOneJudge() {
        // 两个判据都没有的组件永远答不出"装没装"，是不完整的定义
        for (ComponentDef d : catalog.all()) {
            assertThat(d.apiGroup() != null || !d.probes().isEmpty())
                    .as("组件 %s 没有任何判据", d.code()).isTrue();
        }
    }

    @Test
    void everyComponentSaysWhichPlatformFeaturesItSupports() {
        for (ComponentDef d : catalog.all()) {
            assertThat(d.features()).as("组件 %s 没说支撑什么功能", d.code()).isNotEmpty();
        }
    }

    @Test
    void everyProbeNamespaceIsAllowedByTheAdapter() {
        // ⭐ 目录与 k8s-server 清单必须一致。不一致的症状是"页面上写着未安装" ——
        //    因为越界的探测被 400 拒了，而上游把被拒渲染成没装。靠这条测试盯住，不靠人记得改两处。
        for (ComponentDef d : catalog.all()) {
            for (ComponentProbe p : d.probes()) {
                assertThat(ProbeNamespaces.DEFAULT)
                        .as("组件 %s 的候选命名空间 %s 不在 adapter 的探测清单里", d.code(), p.namespace())
                        .contains(p.namespace());
            }
        }
    }

    @Test
    void featureToComponentReverseLookupIsDerivedNotHandWritten() {
        // 依赖关系只写一遍（在组件上写"我支撑哪些功能"），反查是算出来的
        assertThat(catalog.componentsOf("ci")).extracting(ComponentDef::code).contains("tekton");
        assertThat(catalog.componentsOf("service-mesh")).extracting(ComponentDef::code)
                .contains("istio", "gateway-api");
        assertThat(catalog.componentsOf("hpa-resource-metrics")).extracting(ComponentDef::code)
                .containsExactly("metrics-server");
    }

    @Test
    void anUnknownFeatureCodeHasNoComponents() {
        assertThat(catalog.componentsOf("no-such-feature")).isEmpty();
    }
}
```

- [ ] **Step 2: 跑测试确认它失败**

Run: `mvn -pl platform-api -am test -Dtest=ClusterComponentCatalogTest`
Expected: 编译失败 —— `ClusterComponentCatalog` 不存在。

- [ ] **Step 3: 实现 record + 目录**

```java
package com.coding.platformapi.components;

/** 工作负载判据。{@code imageRepo} 与 {@code name} 至少一个（前者跨安装稳定，后者是快路径）。 */
public record ComponentProbe(String namespace, String kind, String imageRepo, String name) {
}
```

```java
package com.coding.platformapi.components;

import java.util.List;

/**
 * 一条组件定义。{@code features} 是**依赖关系的唯一写法** —— 功能 → 组件的反查由
 * {@link ClusterComponentCatalog#componentsOf} 算出来，不手写第二张表。
 *
 * @param apiGroup API group 判据（读 {@code k8s_cluster.capability} 列，不另探）；可空
 * @param probes   工作负载判据；可空
 */
public record ComponentDef(String code, String name, String apiGroup,
                           List<ComponentProbe> probes, List<String> features) {
}
```

```java
package com.coding.platformapi.components;

import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

/**
 * 集群组件目录 —— 本批的<b>唯一出处</b>：哪些组件、凭什么判它装了、它支撑平台哪些功能。
 *
 * <h2>为什么在 platform-api 而不是 k8s-server</h2>
 * 目录里是业务判断（哪个组件、影响哪个功能）。本仓的立场是"适配器不读 DB 列做业务判断"
 * （见 {@code MeshController} 类注释，2026-10-10）：k8s-server 只提供"按候选读工作负载"这件
 * K8s 侧的事，认不认得出那是什么组件由本层决定。
 *
 * <h2>判据为什么可以有两个</h2>
 * API group 回答"这个集群有没有这种 API"（装了才有），工作负载回答"它在不在跑、什么版本、健不健康"。
 * 两者都写就都验：group 在、工作负载也就绪才算「已装且健康」。只有 group 的（如 Gateway API，
 * 纯 CRD）版本列会空着 —— 这是诚实的，不是缺陷。
 *
 * <h2>功能 code 是稳定 id，不是展示文案</h2>
 * 门控按 code 查（{@code flags("ci")}），文案改一次不该让门控失灵。
 */
@Component
public class ClusterComponentCatalog {

    private static final List<ComponentDef> COMPONENTS = List.of(
            new ComponentDef("metrics-server", "metrics-server", "metrics.k8s.io",
                    List.of(new ComponentProbe("kube-system", "deployment", "metrics-server/metrics-server", "metrics-server")),
                    List.of("hpa-resource-metrics")),
            new ComponentDef("prometheus-operator", "Prometheus Operator", "monitoring.coreos.com",
                    List.of(new ComponentProbe("monitor", "deployment", "prometheus-operator/prometheus-operator", null)),
                    List.of("metrics-charts", "servicemonitor")),
            new ComponentDef("gateway-api", "Gateway API", "gateway.networking.k8s.io",
                    List.of(), List.of("service-mesh", "routes")),
            new ComponentDef("istio", "Istio", "networking.istio.io",
                    List.of(new ComponentProbe("istio-system", "deployment", "istio.io/release/pilot", "istiod")),
                    List.of("service-mesh")),
            new ComponentDef("istio-ambient", "Istio ambient 数据面", null,
                    List.of(new ComponentProbe("istio-system", "daemonset", "istio.io/release/ztunnel", "ztunnel")),
                    List.of("ambient-mesh")),
            new ComponentDef("tekton", "Tekton Pipelines", "tekton.dev",
                    List.of(new ComponentProbe("tekton-pipelines", "deployment", "tektoncd/pipeline/controller-", null)),
                    List.of("ci")),
            new ComponentDef("tekton-operator", "Tekton Operator", "operator.tekton.dev",
                    List.of(new ComponentProbe("tekton-operator", "deployment", "tektoncd/operator/operator-", null)),
                    List.of("ci")),
            new ComponentDef("calico", "Calico", "crd.projectcalico.org",
                    List.of(new ComponentProbe("calico-system", "daemonset", "calico/node", "calico-node")),
                    List.of("network", "ipam")),
            new ComponentDef("tigera-operator", "Tigera Operator", "operator.tigera.io",
                    List.of(new ComponentProbe("tigera-operator", "deployment", "tigera/operator", "tigera-operator")),
                    List.of("network")),
            new ComponentDef("cert-manager", "cert-manager", "cert-manager.io",
                    List.of(new ComponentProbe("cert-manager", "deployment", "jetstack/cert-manager-controller", null)),
                    List.of("gateway-tls")),
            new ComponentDef("otel-operator", "OpenTelemetry Operator", "opentelemetry.io",
                    List.of(new ComponentProbe("opentelemetry-operator-system", "deployment",
                            "opentelemetry-operator/opentelemetry-operator", null)),
                    List.of("otel-collection")));

    public List<ComponentDef> all() {
        return COMPONENTS;
    }

    public ComponentDef byCode(String code) {
        return COMPONENTS.stream().filter(d -> d.code().equals(code)).findFirst().orElse(null);
    }

    /** 全部功能 code（稳定 id），按字典序 —— 门控与展示共用 */
    public List<String> featureCodes() {
        return COMPONENTS.stream().flatMap(d -> d.features().stream()).distinct().sorted().toList();
    }

    /** 功能 → 组件：**算出来的**，不手写第二张表 */
    public List<ComponentDef> componentsOf(String featureCode) {
        return COMPONENTS.stream().filter(d -> d.features().contains(featureCode))
                .sorted(Comparator.comparing(ComponentDef::code)).toList();
    }
}
```

- [ ] **Step 4: 跑测试确认它通过**

Run: `mvn -pl platform-api -am test -Dtest=ClusterComponentCatalogTest`
Expected: PASS（6 个测试）。

- [ ] **Step 5: 变异验一下（一条）**

把 `everyProbeNamespaceIsAllowedByTheAdapter` 里 `ProbeNamespaces.DEFAULT` 换成本地 `List.of("kube-system")`
→ 必须变红（说明这条测试真的在比目录与清单，而不是恒真）。改回。

- [ ] **Step 6: 提交**

```bash
git add platform-api/src/main/java/com/coding/platformapi/components/ \
        platform-api/src/test/java/com/coding/platformapi/components/ClusterComponentCatalogTest.java
git commit -m "feat(components): platform-api 组件目录（11 条）+ 与 adapter 清单的一致性测试"
```

---

### Task 4: platform-api · `ClusterComponentService`（四态合成 + 缓存 + 两投影）

**Files:**
- Create: `platform-api/src/main/java/com/coding/platformapi/k8s/K8sComponentClient.java`
- Create: `platform-api/src/main/java/com/coding/platformapi/models/ComponentStatusDTO.java`
- Create: `platform-api/src/main/java/com/coding/platformapi/services/ClusterComponentService.java`
- Test: `platform-api/src/test/java/com/coding/platformapi/services/ClusterComponentServiceTest.java`

**Interfaces:**
- Consumes: `ClusterComponentCatalog`（Task 3）、`ClusterCapabilityService`（既有）、
  `RedisJsonCache`（既有）、`K8sServerGateway`（既有）。
- Produces:
  - `ComponentStatusDTO{ String code; String name; String state; String version; String namespace; Integer ready; Integer desired; String detail; List<String> features; }`
    （`state` ∈ `INSTALLED_HEALTHY` / `INSTALLED_UNHEALTHY` / `NOT_INSTALLED` / `UNKNOWN`）
  - `ClusterComponentService.status(String clusterId) -> List<ComponentStatusDTO>`（**full 投影**）
  - `ClusterComponentService.flags(String clusterId) -> Map<String, ComponentFlagDTO>`，其中
    `ComponentFlagDTO{ boolean available; String reason; List<String> missing; }`
  - `ClusterComponentService.refresh(String clusterId) -> void`（清缓存）
  - `K8sComponentClient.probe(String clusterId, List<WorkloadProbeTargetDTO>) -> List<WorkloadProbeResultDTO>`

- [ ] **Step 1: 写失败的测试**

```java
package com.coding.platformapi.services;

import com.coding.platformapi.cache.RedisJsonCache;
import com.coding.platformapi.components.ClusterComponentCatalog;
import com.coding.platformapi.k8s.K8sComponentClient;
import com.coding.platformapi.models.ComponentStatusDTO;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ClusterComponentServiceTest {

    @Test
    void aMissingComponentIsNotInstalledButStillSaysWhereWeLooked() {
        // Review Focus #4：候选位置之外**没去看**，所以措辞是"在平台已知的位置没找到"，
        // 而不是断言"这个集群没装"
        ClusterComponentService svc = svc(List.of(probe("kube-system", false, null, null, null)));

        ComponentStatusDTO s = find(svc.status("c1"), "metrics-server");

        assertThat(s.getState()).isEqualTo("NOT_INSTALLED");
        assertThat(s.getDetail()).contains("没找到");
    }

    @Test
    void zeroDesiredReplicasIsNotHealthy() {
        // Review Focus #2：被 scale 到 0 的 Deployment，0/0 判成"健康"是错的 —— 它根本没在跑
        ClusterComponentService svc = svc(List.of(probe("kube-system", true, 0, 0, "v0.7.2")));

        assertThat(find(svc.status("c1"), "metrics-server").getState()).isEqualTo("INSTALLED_UNHEALTHY");
    }

    @Test
    void readyBelowDesiredIsUnhealthyAndTheDetailSaysByHowMuch() {
        ClusterComponentService svc = svc(List.of(probe("istio-system", true, 1, 2, "1.30.3")));

        ComponentStatusDTO s = find(svc.status("c1"), "istio");

        assertThat(s.getState()).isEqualTo("INSTALLED_UNHEALTHY");
        assertThat(s.getDetail()).contains("1/2");
    }

    @Test
    void aProbeFailureIsUnknownNotMissing() {
        // ⭐ Review Focus #1：集群断开时页面必须说"探测不到"，而不是"没装"
        ClusterComponentService svc = svc(List.of(probe("kube-system", false, null, null, null, "连接被拒")));

        assertThat(find(svc.status("c1"), "metrics-server").getState()).isEqualTo("UNKNOWN");
    }

    @Test
    void aComponentWithOnlyAnApiGroupJudgeIsHealthyWhenTheGroupExists() {
        // gateway-api 只有 CRD 判据（没有工作负载）—— group 在即"已装"，版本列空着是诚实的
        ClusterComponentService svc = svc(List.of());
        when(svcCapabilityGroups(svc)).thenReturn(Map.of("gateway.networking.k8s.io", List.of("v1")));

        ComponentStatusDTO s = find(svc.status("c1"), "gateway-api");

        assertThat(s.getState()).isEqualTo("INSTALLED_HEALTHY");
        assertThat(s.getVersion()).isNull();
    }

    @Test
    void anUndetectedClusterIsUnknownEverywhere() {
        // capability 列为空 = 未探测（既有口径）→ 所有组件都 UNKNOWN，不是 NOT_INSTALLED
        ClusterComponentService svc = svc(List.of());

        assertThat(svc.status("c1")).allSatisfy(s -> assertThat(s.getState()).isEqualTo("UNKNOWN"));
    }

    @Test
    void flagsCarryNoVersionOrNamespace() {
        // 租户侧投影有意砍掉版本与命名空间 —— 租户不需要知道 istiod 装在哪儿
        ClusterComponentService svc = svc(List.of(probe("tekton-pipelines", true, 1, 1, "v1.15.3")));

        Map<String, ?> flags = svc.flags("c1");

        assertThat(flags).containsKey("ci");
        assertThat(flags.get("ci").toString()).doesNotContain("tekton-pipelines").doesNotContain("v1.15.3");
    }

    @Test
    void aFeatureIsAvailableOnlyWhenAllItsComponentsAreHealthy() {
        // ci 依赖 tekton 与 tekton-operator —— 两个都好才叫能跑
        ClusterComponentService svc = svc(List.of(
                probe("tekton-pipelines", true, 1, 1, "v1.15.3"),
                probe("tekton-operator", false, null, null, null)));

        assertThat(svc.flags("c1").get("ci").available()).isFalse();
        assertThat(svc.flags("c1").get("ci").missing()).contains("tekton-operator");
    }

    @Test
    void theSecondCallWithinTtlDoesNotProbeAgain() {
        // 门控收口后每个租户进 CI 页/网格页都会触发 —— 不挡就是几百次集群调用
        K8sComponentClient client = mock(K8sComponentClient.class);
        when(client.probe(anyString(), any())).thenReturn(List.of());
        ClusterComponentService svc = svcWith(client);

        svc.status("c1");
        svc.status("c1");

        verify(client, times(1)).probe(eq("c1"), any());
    }
}
```

夹具（`svc` / `svcWith` / `probe` / `find` / `svcCapabilityGroups`）写成本类私有静态方法。
`RedisJsonCache` 用**真实现**，照 `RedisJsonCacheTest` 的接法构造：

```java
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    ValueOperations<String, String> valueOps = mock(ValueOperations.class);
    when(redis.opsForValue()).thenReturn(valueOps);
    RedisJsonCache cache = new RedisJsonCache(redis, JsonMapper.builder().build());
```

⚠️ 缓存那条测试（`theSecondCallWithinTtlDoesNotProbeAgain`）依赖 mock 的 `opsForValue().get(key)`
返回第一次 `setMany` 写进去的值 —— 若 `StringRedisTemplate` 的 mock 不带存储语义，
用 `when(valueOps.get(anyString())).thenAnswer(...)` 自己挂一个 `Map` 当存储。
**别为了这条测试去改 `RedisJsonCache`**（那是共享缓存类，本批不该动它）。

- [ ] **Step 2: 跑测试确认它失败**

Run: `mvn -pl platform-api -am test -Dtest=ClusterComponentServiceTest`
Expected: 编译失败 —— `ClusterComponentService` 等不存在。

- [ ] **Step 3: 实现 client + DTO + service**

`K8sComponentClient`（照 `K8sNodeClient` 的形状）：

```java
@Component
@RequiredArgsConstructor
public class K8sComponentClient {

    private final K8sServerGateway gateway;

    /** POST /workload-probes（UPSTREAM 边界，上层收窄） */
    public List<WorkloadProbeResultDTO> probe(String clusterId, List<WorkloadProbeTargetDTO> targets) {
        WorkloadProbeRequestDTO body = new WorkloadProbeRequestDTO();
        body.setClusterId(clusterId);
        body.setTargets(targets);
        return gateway.exchange(HttpMethod.POST, "/workload-probes", null, body,
                gateway.listResponseType(WorkloadProbeResultDTO.class));
    }
}
```

`ComponentStatusDTO` 是普通 `@Data` 类（字段多、要 nullable）；
`ComponentFlagDTO` 是 **record**（三个字段，构造即用，Task 6 的测试按 `new ComponentFlagDTO(available, reason, missing)` 用它）：

```java
package com.coding.platformapi.models;

import java.util.List;

/**
 * 一个功能的可用性旗标（租户侧投影）。
 * <p>⚠️ **有意不含版本与命名空间** —— 租户不需要知道 istiod 装在哪个命名空间，
 * 而"能少给就少给"是这条边界的默认立场（spec §5.2）。
 *
 * @param available 该功能依赖的组件**全部**处于 INSTALLED_HEALTHY
 * @param reason    人读的一句话（缺什么 / 探测不到），展示用
 * @param missing   缺的组件展示名列表（前端可据此高亮）
 */
public record ComponentFlagDTO(boolean available, String reason, List<String> missing) {
}
```

`ClusterComponentService` 的核心（真正要实现的部分）：

```java
    /** 四态合成：**探测失败 = UNKNOWN，不是 NOT_INSTALLED**（集群断开时说"没装"是本仓修过的坑） */
    private ComponentStatusDTO compose(ComponentDef def, Map<String, WorkloadProbeResultDTO> byCode,
                                       Map<String, List<String>> groups) {
        ComponentStatusDTO s = new ComponentStatusDTO();
        s.setCode(def.code());
        s.setName(def.name());
        s.setFeatures(def.features());

        boolean groupHit = def.apiGroup() != null && groups.containsKey(def.apiGroup());
        WorkloadProbeResultDTO w = byCode.get(def.code());

        if (w != null && w.getError() != null) {
            s.setState("UNKNOWN");
            s.setDetail("探测失败：" + w.getError());
            return s;
        }
        if (def.apiGroup() != null && groups.isEmpty()) {
            // capability 列为空 = 未探测（既有口径）—— 不猜"没装"
            s.setState("UNKNOWN");
            s.setDetail("尚未探测集群 API 能力");
            return s;
        }
        boolean workloadHit = w != null && w.isFound();
        if (!groupHit && !workloadHit) {
            s.setState("NOT_INSTALLED");
            s.setDetail(w != null && w.getDetail() != null ? w.getDetail() : "在所有判据上都没命中");
            return s;
        }
        if (w != null) {
            s.setNamespace(w.getNamespace());
            s.setVersion(w.getVersion());
            s.setReady(w.getReady());
            s.setDesired(w.getDesired());
            s.setDetail(w.getDetail());
        }
        // ⚠️ desired == 0 判不健康：被 scale 到 0 的组件没在跑，不是"健康"
        boolean healthy = w == null || (w.getDesired() != null && w.getDesired() > 0
                && w.getReady() != null && w.getReady().equals(w.getDesired()));
        s.setState(healthy ? "INSTALLED_HEALTHY" : "INSTALLED_UNHEALTHY");
        return s;
    }
```

缓存与投影：

```java
    private static final String KEY_PREFIX = "cluster:components:";
    /** 45s —— 组件是分钟级变化的东西；装完想看最新状态走「重新探测」（清缓存） */
    private static final Duration TTL = Duration.ofSeconds(45);
    private static final TypeReference<List<ComponentStatusDTO>> LIST_TYPE = new TypeReference<>() {};

    public List<ComponentStatusDTO> status(String clusterId) {
        return cache.get(KEY_PREFIX + clusterId, TTL, LIST_TYPE, () -> probe(clusterId));
    }

    /** 功能旗标投影：**不含版本与命名空间**（租户不需要知道 istiod 装在哪儿） */
    public Map<String, ComponentFlagDTO> flags(String clusterId) {
        List<ComponentStatusDTO> all = status(clusterId);
        Map<String, ComponentFlagDTO> out = new LinkedHashMap<>();
        for (String feature : catalog.featureCodes()) {
            List<ComponentDef> deps = catalog.componentsOf(feature);
            List<String> missing = deps.stream()
                    .filter(d -> !"INSTALLED_HEALTHY".equals(stateOf(all, d.code())))
                    .map(ComponentDef::name).toList();
            out.put(feature, new ComponentFlagDTO(missing.isEmpty(), reasonOf(all, deps, missing), missing));
        }
        return out;
    }

    /**
     * 「重新探测」：探一次并**覆写**缓存。
     *
     * <p>⚠️ 不是"清缓存让下次重探" —— {@link RedisJsonCache} 的公开方法只有 get / getMany / setMany，
     * <b>没有 evict</b>，这是有意的（TTL-only，见 memory「别做手工失效」：失效点越铺越多时没人能说清
     * 缓存里到底还留着什么）。本类恰好吃得下这条约束：失效点只有这一个，写成"探完覆写"即可。
     */
    public void refresh(String clusterId) {
        List<ComponentStatusDTO> fresh = probe(clusterId);
        if (fresh != null) {
            cache.setMany(Map.of(KEY_PREFIX + clusterId, fresh), TTL);
        }
    }
```

- [ ] **Step 4: 跑测试确认它通过**

Run: `mvn -pl platform-api -am test -Dtest=ClusterComponentServiceTest`
Expected: PASS（9 个测试）。

- [ ] **Step 5: 变异验一下（两条）**

1. 把 `compose` 里 `w.getError() != null` 那一支删掉（让失败落到"没命中"）→
   `aProbeFailureIsUnknownNotMissing` 必须变红。改回。
2. 把 `desired > 0` 去掉 → `zeroDesiredReplicasIsNotHealthy` 必须变红。改回。

- [ ] **Step 6: 提交**

```bash
git add platform-api/src/main/java/com/coding/platformapi/k8s/K8sComponentClient.java \
        platform-api/src/main/java/com/coding/platformapi/models/Component*.java \
        platform-api/src/main/java/com/coding/platformapi/services/ClusterComponentService.java \
        platform-api/src/test/java/com/coding/platformapi/services/ClusterComponentServiceTest.java
git commit -m "feat(components): ClusterComponentService —— 四态合成 + 短 TTL 缓存 + 两投影"
```

---

### Task 5: platform-api · 三个端点 + 权限迁移

**Files:**
- Modify: `platform-api/src/main/java/com/coding/platformapi/controllers/ClusterController.java`
- Create: `platform-api/src/main/java/com/coding/platformapi/models/ClusterComponentFlagsView.java`（若需要包一层）
- Create: `platform-data/src/main/resources/db/migration/V2026_10_17_1__cluster_components.sql`
- Test: `platform-api/src/test/java/com/coding/platformapi/controllers/ClusterComponentEndpointsTest.java`

**Interfaces:**
- Consumes: `ClusterComponentService`（Task 4）。
- Produces（三条 platform-api 端点，前端 Task 7/8 按此调用）：
  - `POST /cluster/components` → `ResponseData<List<ComponentStatusDTO>>`
  - `POST /cluster/component-flags` → `ResponseData<Map<String, ComponentFlagDTO>>`
  - `POST /cluster/components/refresh` → `ResponseData<Void>`

- [ ] **Step 1: 写失败的测试**

```java
/** 端点很薄，测"委托对不对 + 路径对不对"。路径写错会有 PermissionCrossCheckRunner 在启动时兜住，
 *  但那条只在真启动时跑到，单测里先钉住。 */
class ClusterComponentEndpointsTest {

    @Test
    void theThreeEndpointsDelegate() {
        ClusterComponentService svc = mock(ClusterComponentService.class);
        ClusterComponentCatalog catalog = mock(ClusterComponentCatalog.class);
        ClusterController c = controllerWith(svc, catalog);
        ClusterKeyRequest key = new ClusterKeyRequest();
        key.setClusterId("c1");

        c.components(key);
        c.componentFlags(key);
        c.refreshComponents(key);

        verify(svc).status("c1");
        verify(svc).flags("c1");
        verify(svc).refresh("c1");
    }
}
```

- [ ] **Step 2: 跑测试确认它失败**

Run: `mvn -pl platform-api -am test -Dtest=ClusterComponentEndpointsTest`
Expected: 编译失败 —— 三个方法不存在。

- [ ] **Step 3: 加三个端点**

```java
    @PostMapping("/components")
    @Operation(summary = "集群组件清单（全量：状态 / 版本 / 健康 / 命名空间 / 影响的功能）",
            description = "平台侧展示用。租户侧门控走 /cluster/component-flags —— 那个投影不含版本与命名空间。")
    public ResponseData<List<ComponentStatusDTO>> components(@RequestBody ClusterKeyRequest request) {
        return new ResponseData<>(componentService.status(request.getClusterId()));
    }

    @PostMapping("/component-flags")
    @Operation(summary = "组件功能旗标（租户可读的窄投影：功能 code → 能不能用 + 原因）",
            description = "租户侧门控的单一出处：CI / 服务网格 / 网络 / HPA 都读它。code 与 /cluster/capability/get 同源。")
    public ResponseData<Map<String, ComponentFlagDTO>> componentFlags(@RequestBody ClusterKeyRequest request) {
        return new ResponseData<>(componentService.flags(request.getClusterId()));
    }

    @PostMapping("/components/refresh")
    @AuditAction(value = "刷新集群组件探测", type = AuditType.QUERY)
    @Operation(summary = "重新探测集群组件（清缓存 + 重探）")
    public ResponseData<Void> refreshComponents(@RequestBody ClusterKeyRequest request) {
        componentService.refresh(request.getClusterId());
        return new ResponseData<>(null);
    }
```

⚠️ `@AuditAction` 的实际注解形态与 `AuditType` 取值以既有用法为准（`grep -n "@AuditAction" ClusterController.java`）。
若既有写操作才挂审计、查询不挂，则**不挂**（refresh 是查询性动作，只是清了缓存）。

- [ ] **Step 4: 写迁移并验证（这条不能只靠单测）**

```sql
-- ============================================================================================
-- S1.2：集群组件展示的三条端点权限行
-- ============================================================================================
-- 端点：
--   POST /cluster/components          → 平台侧全量（版本/健康/命名空间/功能映射）
--   POST /cluster/component-flags     → 租户可读的功能旗标（CI / 网格 / 网络 / HPA 的门控来源）
--   POST /cluster/components/refresh  → 重新探测（清缓存 + 重探）
--
-- ⚠️ component-flags **两行同一路径**（platform:cluster:manage 与 tenant:cluster:capability:view）——
--    这不是笔误，是既有做法：/cluster/capability/get 就是这么写的（V2026_10_07_2）。
--    少写租户那一行，租户进 CI 页就吃 403，而"能不能跑 CI"正是他要的答案。
-- ⚠️ 新 tab **不需要新页面码** —— 它长在集群详情页里，那个页面已由 platform:page:cluster 门禁
--    （platform-web/src/router/index.ts 的 cluster-detail 路由）。
-- ⚠️ 部署顺序：先重放本文件再起新构建 —— 无行的新端点会被 PermissionCrossCheckRunner 拒启。
-- ⚠️ 本仓没有 Flyway，手工重放；启动日志里永远没有 Migrating schema。
-- 幂等：显式 id + ODKU；角色关联按显式 id（不用 LIKE，`_` 是通配符）。
-- 注意：MySQL 注释 `--` 后面必须跟空白字符。
-- ============================================================================================

INSERT INTO platform_permission (id, domain, resource, action, code, description) VALUES
 ('perm_res_cluster_components', 'API','/cluster/components', 'POST','platform:cluster:manage',
  '集群组件清单（全量：状态/版本/健康/命名空间/影响的功能）'),
 ('perm_res_cluster_component_flags_admin', 'API','/cluster/component-flags', 'POST','platform:cluster:manage',
  '组件功能旗标（管理员身份）'),
 ('perm_res_cluster_component_flags_tenant', 'API','/cluster/component-flags', 'POST','tenant:cluster:capability:view',
  '组件功能旗标（租户可读的集群只读快照，门控用）'),
 ('perm_res_cluster_components_refresh', 'API','/cluster/components/refresh', 'POST','platform:cluster:manage',
  '重新探测集群组件')
ON DUPLICATE KEY UPDATE
  domain=VALUES(domain), resource=VALUES(resource), action=VALUES(action),
  code=VALUES(code), description=VALUES(description);

-- ⚠️ 新权限行必须**显式绑角色**（不按 code 前缀继承）
INSERT INTO platform_role_permission (id, role_id, permission_id) VALUES
 ('rp_admin_cluster_components',        'builtin_role_admin', 'perm_res_cluster_components'),
 ('rp_admin_cluster_comp_flags',        'builtin_role_admin', 'perm_res_cluster_component_flags_admin'),
 ('rp_admin_cluster_comp_flags_tenant', 'builtin_role_admin', 'perm_res_cluster_component_flags_tenant'),
 ('rp_admin_cluster_comp_refresh',      'builtin_role_admin', 'perm_res_cluster_components_refresh')
ON DUPLICATE KEY UPDATE permission_id=VALUES(permission_id);
```

**验证（必须真跑，别只看 SQL）**：

1. 重放：`jshell` + JDBC 逐条执行（本仓没有 mysql CLI，配方见
   `~/.claude/.../memory/cluster-access-for-debugging.md`），或按项目既有方式重放。
2. 查回来确认 4 行都在：
   `select resource, action, code from platform_permission where resource like '/cluster/component%' or resource='/cluster/components';`
   Expected: 4 行，其中 `/cluster/component-flags` **两行**。
3. 起 platform-api（或跑它的启动测试），确认 `PermissionCrossCheckRunner` **没有**因缺行拒启
   —— 启动成功本身就是这条迁移的断言。

- [ ] **Step 5: 跑测试确认它通过**

Run: `mvn -pl platform-api -am test -Dtest=ClusterComponentEndpointsTest`
Expected: PASS。

- [ ] **Step 6: 提交**

```bash
git add platform-api/src/main/java/com/coding/platformapi/controllers/ClusterController.java \
        platform-api/src/test/java/com/coding/platformapi/controllers/ClusterComponentEndpointsTest.java \
        platform-data/src/main/resources/db/migration/V2026_10_17_1__cluster_components.sql
git commit -m "feat(components): 三条端点 + 权限迁移（component-flags 两行同路径）"
```

---

### Task 6: 门控收口（后端）· CI 与网格改读同一份派生

**Files:**
- Modify: `platform-api/src/main/java/com/coding/platformapi/services/PipelineCapabilityService.java`
- Modify: `platform-api/src/main/java/com/coding/platformapi/services/MeshService.java`
- Test: `platform-api/src/test/java/com/coding/platformapi/services/PipelineCapabilityServiceTest.java`（既有，改）
- Test: `platform-api/src/test/java/com/coding/platformapi/services/MeshServiceTest.java`（既有，改）

**Interfaces:**
- Consumes: `ClusterComponentService.flags(clusterId)`。
- Produces: `/mesh/status` 的**对外形状不变**（`MeshStatusDTO` 四个字段不动）—— 这是前端零改动的依据。

- [ ] **Step 1: 改既有测试，让它先红**

（两个测试类里的 `status(code, state)` / `flag(available, reason, missing)` 是**本类私有夹具**，
照 Task 4 的写法补：造 `ComponentStatusDTO` 并 set 三个字段即可。）

`MeshServiceTest`：把"ztunnel 探测结果驱动 istioAmbient"改成"组件旗标驱动"。

```java
    @Test
    void meshStatusDerivesFromTheComponentFlags() {
        // 收口前：MeshOperations.ambient() 活探 + ClusterCapabilityService 两组各判一次
        // 收口后：三条都从 ClusterComponentService 派生（同一次探测，同一份缓存）
        when(componentService.status("c1")).thenReturn(List.of(
                status("istio", "INSTALLED_HEALTHY"),
                status("gateway-api", "INSTALLED_HEALTHY"),
                status("istio-ambient", "INSTALLED_UNHEALTHY")));

        MeshStatusDTO s = meshService.status("c1");

        assertThat(s.isHasIstio()).isTrue();
        assertThat(s.isHasGatewayApi()).isTrue();
        assertThat(s.isIstioAmbient()).isFalse();   // 健康才叫 ambient 可用
        assertThat(s.getGatewayApiVersions()).isNotEmpty();  // 版本仍来自 capability 列
    }
```

`PipelineCapabilityServiceTest`：Tekton 那一半改读旗标；**StorageClass 那一半不动**。

```java
    @Test
    void ciUnavailableWhenTektonComponentIsMissing() {
        when(componentService.flags("c1")).thenReturn(Map.of("ci", new ComponentFlagDTO(false, "缺 Tekton Pipelines", List.of("Tekton Pipelines"))));

        assertThat(pipelineCapability.available("c1", "t1", "ns1").available()).isFalse();
        assertThat(pipelineCapability.available("c1", "t1", "ns1").reason()).contains("Tekton");
    }

    @Test
    void theStorageClassPreconditionStillUsesItsOwnNarrowProjection() {
        // ⚠️ 有意**不**收：StorageClass 不是组件（它是集群存储配置，粒度是 SC 不是组件）。
        //    把"有可动态供应的盘"塞进组件清单会把"组件"这个概念搞浑。
        verify(k8sPipelineClient, times(1)).storageClassProvisioners(any());
    }
```

- [ ] **Step 2: 跑测试确认它们失败**

Run: `mvn -pl platform-api -am test -Dtest='MeshServiceTest,PipelineCapabilityServiceTest'`
Expected: FAIL —— 两个服务还没接 `ClusterComponentService`。

- [ ] **Step 3: 改两个服务**

`MeshService.status(clusterId)`：三个布尔从 `componentService.status(clusterId)` 里按 code 取
（`istio` → `hasIstio`，`gateway-api` → `hasGatewayApi`，`istio-ambient` → `istioAmbient`，
**且要求 `INSTALLED_HEALTHY`**）；`gatewayApiVersions` 仍来自 `ClusterCapabilityService.versionsOf`。
`MeshOperations.ambient()` 这个活探测**从此不再被调用** —— 但**先别删**（见 Step 5）。

`PipelineCapabilityService.available(...)`：Tekton 那一半改成读 `flags("ci")`；
StorageClass 那一半原样保留；原因文案取 `flags` 里的 `reason`（缺哪个组件由 `missing` 给）。

- [ ] **Step 4: 跑测试确认它们通过 + 跑 platform-api 全量**

Run: `mvn -pl platform-api -am test`
Expected: 全绿。

- [ ] **Step 5: 留一条收尾说明（别静默留死代码）**

`MeshOperations.ambient()` 与 `/mesh/status` 那个 k8s-server 端点现在没有调用方了。
**本任务不删**（删了要连带改 `MeshController` + 它的测试 + `AdminMeshProbeResult`，
属于独立的一次清理）。在 `MeshOperations.ambient()` 的 javadoc 上补一句：

```java
 * @deprecated S1.2 收口后（2026-10-17）探测统一走 platform-api 的 {@code ClusterComponentService}
 *             （组件 {@code istio-ambient} 的 ztunnel 判据就是这件事），本方法与 {@code /mesh/status}
 *             已无调用方，保留待清理。**别在别处再新调它** —— 那会造出第二份 ztunnel 探测。
```

并在台账的收口块里记一条待清理项。

- [ ] **Step 6: 提交**

```bash
git add platform-api/src/main/java/com/coding/platformapi/services/MeshService.java \
        platform-api/src/main/java/com/coding/platformapi/services/PipelineCapabilityService.java \
        k8s-core/src/main/java/com/coding/k8score/operations/gateway/MeshOperations.java \
        platform-api/src/test/java/com/coding/platformapi/services/
git commit -m "refactor(components): CI 与网格门控改读组件清单（MeshOperations.ambient 标废弃待清理）"
```

---

### Task 7: 门控收口（前端）· `useClusterComponents` + 两处委派

**Files:**
- Create: `platform-web/src/composables/useClusterComponents.ts`
- Modify: `platform-web/src/api/index.ts`（`clusterApi` 加两个方法）
- Modify: `platform-web/src/types.ts`（`K8sComponentStatus` / `K8sComponentFlag`）
- Modify: `platform-web/src/composables/useMeshStatus.ts`
- Modify: `platform-web/src/composables/useClusterCapability.ts`
- Modify: `platform-web/src/apiCodes.ts`（若缺 code 常量）

**Interfaces:**
- Consumes: Task 5 的两条端点。
- Produces: `useClusterComponents(clusterId: Ref<string|null|undefined>)` 返回
  `{ flags, loading, probed, available(code), reasonOf(code), missingOf(code), canRead, load, refresh }`
  —— **返回普通对象（非 reactive）**，消费方必须显式写 `.value`（照 `useClusterCapability` 的约定）。

- [ ] **Step 1: 加 API 方法与类型**

```ts
// api/index.ts 的 clusterApi 里
  /** 集群组件清单（全量，平台侧；需 platform:cluster:manage） */
  components: (clusterId: string) =>
    http.post<never, K8sComponentStatus[]>('/cluster/components', { clusterId }),
  /**
   * 组件功能旗标（租户可读的窄投影：功能 code → 能不能用 + 原因）。
   * ⭐ 这是 CI / 服务网格 / 网络 / HPA 四处门控的**单一出处** —— 别再各自去读 capability 拼。
   */
  componentFlags: (clusterId: string) =>
    http.post<never, Record<string, K8sComponentFlag>>('/cluster/component-flags', { clusterId }),
  refreshComponents: (clusterId: string) =>
    http.post<never, void>('/cluster/components/refresh', { clusterId }),
```

```ts
// types.ts
/** 组件状态四态 —— ⚠️ UNKNOWN 不是 NOT_INSTALLED：探测失败时不能说"没装" */
export type K8sComponentState = 'INSTALLED_HEALTHY' | 'INSTALLED_UNHEALTHY' | 'NOT_INSTALLED' | 'UNKNOWN'

export interface K8sComponentStatus {
  code: string
  name: string
  state: K8sComponentState
  version?: string | null
  namespace?: string | null
  ready?: number | null
  desired?: number | null
  detail?: string | null
  features: string[]
}

export interface K8sComponentFlag {
  available: boolean
  reason?: string | null
  missing: string[]
}
```

- [ ] **Step 2: 写 composable**

```ts
/**
 * 集群组件功能旗标（门控的单一出处）。
 *
 * <p>权限位短路照 useClusterCapability：两个码都没有时不发注定被拒的请求，
 * 旗标保持空 = 「未探测」，各门禁按既有降级路径走，而不是报错。
 *
 * <p>返回普通对象（非 reactive）：嵌套 ref 不会被模板自动解包，消费方须显式写 `.value`
 * —— 漏掉会令门禁静默恒真/恒假（同 useClusterCapability 的坑）。
 */
export function useClusterComponents(clusterId: Ref<string | null | undefined>) {
  const perm = usePermission()
  const flags = ref<Record<string, K8sComponentFlag>>({})
  const loading = ref(false)
  const probed = computed(() => Object.keys(flags.value).length > 0)
  const canRead = () => perm.hasAny([apiCodes.clusterManage, apiCodes.clusterCapabilityView])

  async function load(): Promise<void> {
    const id = clusterId.value
    if (!id) { flags.value = {}; return }
    if (!canRead()) { flags.value = {}; return } // 无权限 → 按「未探测」降级，不发注定被拒的请求
    loading.value = true
    try {
      const next = await clusterApi.componentFlags(id)
      if (clusterId.value !== id) return // 加载期间切了集群 → 丢弃过期结果（对齐 useClusterCapability 先例）
      flags.value = next ?? {}
    } catch {
      if (clusterId.value !== id) return
      flags.value = {} // 拦截器已提示；按未探测降级，门禁走各自的既有降级路径
    } finally {
      if (clusterId.value === id) loading.value = false
    }
  }

  async function refresh(): Promise<void> {
    const id = clusterId.value
    if (!id) return
    await clusterApi.refreshComponents(id)
    await load()
  }

  function available(code: string): boolean { return flags.value[code]?.available ?? false }
  function reasonOf(code: string): string { return flags.value[code]?.reason ?? '' }
  function missingOf(code: string): string[] { return flags.value[code]?.missing ?? [] }

  watch(clusterId, () => void load(), { immediate: true })
  return {
    flags, loading, probed,
    available, reasonOf, missingOf,
    canRead: computed(canRead),
    /** 清缓存重探需要写权限（platform:cluster:manage），与 canRead 不是一回事 */
    canRefresh: computed(() => perm.has(apiCodes.clusterManage)),
    load, refresh,
  }
}
```

- [ ] **Step 3: 改 `useMeshStatus`（把"从 capability 派生"那层换成读旗标）**

```ts
  // 收口前：只有 capability 读权时，从这里派生 hasGatewayApi / hasIstio（自己拼 group 判断）
  // 收口后：租户侧读 /cluster/component-flags —— **同一份派生实现**，只是投影更窄。
  //         两层降级的结构保留（有权 → 全量；无权 → 未探测），变的是"有权那一层读什么"。
```

⚠️ **`useMeshStatus` 有一个消费者必须一起看**：`MeshStatusBanner.vue` 与
`ClusterDetailView`。改完跑 `npm run type-check` 看有没有漏改的解构。

- [ ] **Step 4: 改 `useClusterCapability` 的两个组件派生 flag（委派，不改它的公开 API）**

`hasMetricsServer` / `hasCalico` / `hasCalicoCrd` 改成读 `useClusterComponents` 的对应功能 code
（`hpa-resource-metrics` / `network`）；`hpaSupportsBehavior` 与 `hasCustomMetrics` /
`hasExternalMetrics` **保持读 capability 列**（autoscaling 版本与适配器 group 不是组件问题）。

⚠️ 这样改的**全部意义在于消费方零改动**：`HpaEditorView` / Calico 各页继续调 `cap.metricsAvailable`
这类入口，底下换了一个实现。若某个消费方读的是 `cap.hasMetricsServer`（原始 flag），
把它一起改成读组件旗标的派生 —— 别让两套并存。

- [ ] **Step 5: type-check**

Run: `cd platform-web && npm run type-check`
Expected: 通过。

- [ ] **Step 6: 浏览器冒烟（假 XHR/adapter 桩 + 真组件）**

冒烟要验的是**接线**，不是聚合逻辑：

```ts
// smoke-component-flags.ts（跑完即删）
setHandler((method, url, data) => {
  if (url === '/context') return { tenants: [/* 三级齐全 */] }
  if (url === '/cluster/component-flags') {
    return { ci: { available: false, reason: '缺 Tekton Pipelines', missing: ['Tekton Pipelines'] } }
  }
  return null
})
// 挂 HpaEditorView（或 ClusterDetailView），断言：
//   ① 页面确实打了 /cluster/component-flags（不是自己去拼 capability）
//   ② 旗标 available=false 时，CI 页/编辑器出现"缺 Tekton Pipelines"的原因文案
//   ③ 无 platform:cluster:manage 身份时 **不发**这个请求（权限位短路）
```

- [ ] **Step 7: 提交**

```bash
git add platform-web/src/composables/useClusterComponents.ts \
        platform-web/src/composables/useMeshStatus.ts \
        platform-web/src/composables/useClusterCapability.ts \
        platform-web/src/api/index.ts platform-web/src/types.ts platform-web/src/apiCodes.ts
git commit -m "refactor(components): 前端门控改读组件旗标（useMeshStatus / useClusterCapability 委派）"
```

---

### Task 8: 前端 · 新 tab「集群组件」+ 概览收敛

**Files:**
- Create: `platform-web/src/components/cluster/ClusterComponentsPanel.vue`
- Modify: `platform-web/src/views/ClusterDetailView.vue`
- Modify: `platform-web/src/types.ts`（如概览 DTO 需要新字段则加；否则不动）

- [ ] **Step 1: 写面板组件**

表格列：**组件 / 状态 / 版本 / 命名空间 / 健康 / 影响的功能**。

```vue
<script setup lang="ts">
/**
 * 集群组件清单（S1.2）。数据源 /cluster/components（平台侧全量）。
 *
 * ⚠️ 四态必须分开渲染 —— UNKNOWN（探测不到）与 NOT_INSTALLED（没装）是相反结论，
 *    都渲染成灰 tag 就等于把"不知道"说成了"没有"（本仓修过的坑）。
 * ⚠️ 本批**不给安装按钮**：安装/升级留给 S3.3 / S3.4，这里只给指引。
 */
</script>
<template>
  <div class="cc-panel">
    <div class="cc-head">
      <span class="cc-summary">{{ summary }}</span>
      <el-button size="small" :loading="loading" @click="refresh">重新探测</el-button>
    </div>
    <div v-if="anyUnknown" class="cc-warn">
      集群当前探测不到，以下状态可能不准确。
    </div>
    <el-table v-loading="loading" :data="rows" size="small">
      <el-table-column label="组件" min-width="180">
        <template #default="{ row }">
          <span class="cc-name">{{ row.name }}</span>
          <code class="cc-code">{{ row.code }}</code>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="130">
        <template #default="{ row }">
          <!-- ⚠️ 四态各一态：UNKNOWN 用虚线边（effect=plain）与 NOT_INSTALLED 区分开 -->
          <el-tag size="small" :type="STATE[row.state].type" :effect="STATE[row.state].effect">
            {{ STATE[row.state].label }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="版本" width="150">
        <template #default="{ row }"><code class="mono">{{ row.version ?? '—' }}</code></template>
      </el-table-column>
      <el-table-column label="命名空间" width="170">
        <template #default="{ row }">{{ row.namespace ?? '—' }}</template>
      </el-table-column>
      <el-table-column label="健康" width="110">
        <template #default="{ row }">
          {{ row.ready == null ? '—' : `${row.ready}/${row.desired}` }}
        </template>
      </el-table-column>
      <el-table-column label="影响的功能" min-width="220">
        <template #default="{ row }">
          <span v-for="f in row.features" :key="f" class="cc-feature">{{ featureLabel(f) }}</span>
          <!-- 本批不给安装按钮：安装/升级留给 S3.3 / S3.4，这里只给指引 -->
          <div v-if="row.state === 'NOT_INSTALLED' && row.features.length" class="cc-guide">
            缺它 → {{ row.features.map(featureLabel).join(' / ') }} 不可用（安装需 cluster-admin）
          </div>
          <div v-else-if="row.state === 'UNKNOWN'" class="cc-guide">{{ row.detail }}</div>
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>
```

配套的常量（同一个 `<script setup>` 里）：

```ts
/** 四态 → 徽标。⚠️ UNKNOWN 与 NOT_INSTALLED 必须视觉可分：前者是"不知道"，后者是"没有" */
const STATE: Record<K8sComponentState, { label: string; type: 'success' | 'warning' | 'info'; effect: 'light' | 'plain' }> = {
  INSTALLED_HEALTHY: { label: '已装', type: 'success', effect: 'light' },
  INSTALLED_UNHEALTHY: { label: '已装 · 不健康', type: 'warning', effect: 'light' },
  NOT_INSTALLED: { label: '未安装', type: 'info', effect: 'light' },
  UNKNOWN: { label: '未探测', type: 'info', effect: 'plain' },
}

/** 功能 code → 展示文案。code 是稳定 id（门控按它查），文案改这里不影响门控 */
const FEATURE_LABELS: Record<string, string> = {
  'hpa-resource-metrics': 'HPA 资源指标',
  'metrics-charts': '监控曲线',
  servicemonitor: 'ServiceMonitor / PodMonitor',
  'service-mesh': '服务网格',
  routes: '路由',
  'ambient-mesh': 'ambient 流量接管',
  ci: 'CI 流水线',
  network: '网络',
  ipam: 'IPAM',
  'gateway-tls': '网关 TLS 证书',
  'otel-collection': 'OTel 采集',
}
function featureLabel(code: string): string { return FEATURE_LABELS[code] ?? code }

状态映射（**四态各一色，UNKNOWN 用虚线边**）：

| state | 文案 | tag type |
|---|---|---|
| `INSTALLED_HEALTHY` | 已装 | `success` |
| `INSTALLED_UNHEALTHY` | 已装 · 不健康 | `warning` |
| `NOT_INSTALLED` | 未安装 | `info` |
| `UNKNOWN` | 未探测 | `info` + `effect="plain"` |

摘要一行：`11 个组件中 10 个已装，缺 1 个会影响 1 项平台功能`。

- [ ] **Step 2: 接到 `ClusterDetailView` 的第 5 个 tab**

```vue
        <el-tab-pane label="集群组件" name="components" lazy>
          <ClusterComponentsPanel :cluster-id="clusterId" />
        </el-tab-pane>
```

（`lazy` 是必须的 —— B4 那批踩过：不加 `lazy`，进页面就把所有 tab 的请求一起打出去。）

- [ ] **Step 3: 概览 tab 收敛（两处"同一事实两个来源"）**

1. 「组件版本与 API 能力」块 → 一行摘要 + 跳转：

```vue
              <section class="info-card">
                <h3 class="section-title">集群组件</h3>
                <div class="info-row">
                  <span class="k">组件</span>
                  <span class="v">
                    {{ componentSummary }}
                    <el-button link type="primary" @click="activeTab = 'components'">查看详情</el-button>
                  </span>
                </div>
              </section>
```

2. `Istio` / `Calico` 两行改显示**探测值**，人工登记值降为 tooltip：

```vue
                  <div class="info-row">
                    <span class="k">Istio</span>
                    <el-tooltip :content="istioVersionTip" placement="top">
                      <code class="mono v">{{ probedVersionOf('istio') ?? cluster.istioVersion ?? '—' }}</code>
                    </el-tooltip>
                  </div>
```

`istioVersionTip`：登记值与实测值不一致时写
`登记值 1.24 与实测 1.30.3 不一致（升级过集群？）`，一致或没有实测值时写 `登记时填写`。

- [ ] **Step 4: type-check + 浏览器冒烟**

```ts
// 断言：
// ① 第 5 个 tab 存在，切过去才发 /cluster/components（lazy 生效：切之前 requests 里没有它）
// ② 四态各渲染成不同的文案（造 4 条数据，断言四种文案都在）
// ③ 缺组件的行显示"影响的功能"，且**没有**安装按钮
// ④ 概览那一行的摘要与跳转可用；Istio 那行显示的是**实测**版本
// ⑤ 「重新探测」点了会打 /cluster/components/refresh 随后重取
```

- [ ] **Step 5: 提交**

```bash
git add platform-web/src/components/cluster/ClusterComponentsPanel.vue \
        platform-web/src/views/ClusterDetailView.vue
git commit -m "feat(components): 集群组件 tab + 概览收敛（组件块与 Istio/Calico 双来源）"
```

---

### Task 9: 收口 —— 端到端冒烟、台账、部署说明

**Files:**
- Modify: `docs/task-ledger-2026-10-12.md`

- [ ] **Step 1: 全量后端测试**

Run: `mvn test`（仓库根，全模块）
Expected: 全绿。**把结果记下来**（数量），别只说"绿了"。

- [ ] **Step 2: 前端 type-check + 真集群端到端**

`npm run type-check` 通过；然后**对着真集群**（`coding-cluster`，只读）走一遍：
集群详情页 → 集群组件 tab → 应显示 **10 装 1 缺**，缺的是 `metrics-server`，
"影响的功能"写着 HPA 资源指标。这一条是这批发出去之前唯一能证明它真的work的检查。

⚠️ 连真集群的配方见 memory `cluster-access-for-debugging`（kubeconfig 解密；**用完删明文**）。
页面本身需要走平台（`/api` 代理），所以这一步要么起完整后端，要么用 Task 7/8 的桩冒烟
+ 单独用 `kubectl` 核对探测结果一致。

- [ ] **Step 3: 台账收口**

在 `docs/task-ledger-2026-10-12.md` 的 §S1.2 下写收口块（照 §S2.1 的形态）：做了什么、
与 spec 的出入、有意不做的（指向 spec §10）、**待清理项**（Task 6 Step 5 那条
`MeshOperations.ambient()` + `/mesh/status` 已无调用方），以及进度表的 S1.2 行状态。
**"有意不做"必须显式写**，否则下一个读的人会当成漏做。

- [ ] **Step 4: 记部署面**

- 重建 **k8s-server + platform-api + platform-web**。
- 迁移 `V2026_10_17_1__cluster_components.sql` **先重放**再起构建。
- k8s-server 新增配置 `platform.k8s.probe-namespaces`（有默认值，不配也能跑）。
- 无新外部依赖、无集群侧前提。

- [ ] **Step 5: 提交**

```bash
git add docs/task-ledger-2026-10-12.md
git commit -m "docs(ledger): S1.2 集群组件展示收口"
```

---

## Self-Review 记录

**Spec 覆盖**：§1 交付边界 → Task 1–8 全覆盖；§2 目录 → Task 3；§3 探测/四态 → Task 1、4；
§4 边界 → Task 2（清单在适配器）与 Task 5（权限行）；§5 派生与门控 → Task 4、6、7；
§6 前端 → Task 7、8；§7 权限迁移 → Task 5；§8 测试 → 各任务内；§9 已实测前提 → Task 1 的测试注释里逐条对上；
§10 有意不做 → Global Constraints；§11 部署 → Task 9。

**剩下的一处不写死**（不是 TODO，是"按既有代码的口径落"）：
Task 5 的迁移里 `platform_permission` / `platform_role_permission` 的**列名与 id 命名**照
`V2026_10_16_3__pvc_resize_permission.sql` 一字不差地抄（那个文件是最近一次成功重放的模板，
连注释里的坑都写在里面）。**不要**照 `V2026_09_29_1` 抄 —— 那是 `resource` 列去前缀**之前**的写法。
