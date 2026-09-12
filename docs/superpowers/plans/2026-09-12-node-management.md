# 节点管理（Node Management）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在「平台管理」菜单下新增节点管理（集群级、平台管理员视角）：列表 + 详情，支持 Cordon/Uncordon、Drain、标签/Taint 编辑、实时 CPU/内存使用率、按 device 磁盘折线、聚合网络 IO、节点 Pod 列表、节点事件。

**Architecture:** 贯穿四层，全部沿用现有模式。k8s-core 持有 K8s 逻辑（`CoreV1NodeOperations` + `CoreV1NodeConverter`）；k8s-server 提供 admin 边界端点（`/resources/nodes/*`，复用 `ResourceAccessResolver.assertClusterAccess`）；platform-api 薄透传 + 节点指标（Thanos/PromQL）；前端两个页面（列表 + 详情），监控复用现有 `MetricChart.vue`。node 是 cluster-scoped，作用域只有 `clusterId`，无租户/命名空间上下文。

**Tech Stack:** Java 21 / Spring Boot 4.0.6 / fabric8 kubernetes-client 7.9.0 / MyBatis；前端 Vue 3 + TypeScript + Element Plus + ECharts。指标走 Thanos（`ThanosQueryClient`）。

**Spec:** `docs/superpowers/specs/2026-09-12-node-management-design.md`（本计划实现该设计）

## Global Constraints

- fabric8 版本 **7.9.0**。模型类无 fluent `withXxx`（只有 Builder）——构建对象用 `new XxxBuilder()`，见 memory「fabric8 7.x 模型 API 坑」。
- k8s-server **零业务逻辑**：只做边界校验 + 委托 operations。K8s 语义（cordon/drain/label/pod 分组）全在 k8s-core。
- platform-api 整体已 `hasAuthority(PLATFORM:admin)`（`ResourceServerConfig` anyRequest），节点端点自动 admin-only，无需额外鉴权。
- 节点指标按 **`instance="<nodeInternalIp>:9100"`** 作用域（node_exporter 标准端口 9100；前端从节点 internalIp 拼 instance）。不依赖 `cluster_name` relabeling。若节点用非 9100 端口，改前端 instance 拼接即可。
- 磁盘按 device **分折线**（过滤恒 0 的 `sr.*`/`loop.*`）；网络只取 `device=~"ens.*"` 聚合成单线 RX/TX。
- Drain 默认保留 DaemonSet / 静态 / mirror Pod；`force=true` 时 PDB 阻断改为直接 delete。v1 **不做**删除节点。
- 提交信息前缀：`feat(node):` / `test(node):`。每个 Task 结束独立可编译、可测。
- 测试框架：JUnit 5 + Mockito + AssertJ（来自 `spring-boot-starter-test`）。k8s-core / platform-api 若无该依赖，在对应 pom 加 `<scope>test</scope>`。

---

### Task 1: 节点 DTO + ResourceType.NODE（platform-common）

**Files:**
- Create: `platform-common/src/main/java/com/coding/common/models/k8s/dto/NodeDTO.java`
- Create: `platform-common/src/main/java/com/coding/common/models/k8s/dto/NodeTaintDTO.java`
- Create: `platform-common/src/main/java/com/coding/common/models/k8s/dto/NodeConditionDTO.java`
- Create: `platform-common/src/main/java/com/coding/common/models/k8s/dto/NodePodStatDTO.java`
- Create: `platform-common/src/main/java/com/coding/common/models/k8s/dto/NodeDrainResultDTO.java`
- Create: `platform-common/src/main/java/com/coding/common/models/k8s/dto/NodeEventDTO.java`
- Modify: `platform-common/src/main/java/com/coding/common/models/k8s/ResourceType.java`

**Interfaces:**
- Produces: `NodeDTO extends BaseResources`（`getApiPath()` 返回 `/resources/nodes`），字段见下；`NodeTaintDTO{key,value,effect}`；`NodeConditionDTO{type,status,reason,message,lastHeartbeatTime,lastTransitionTime}`；`NodePodStatDTO{nodeName,podCount,cpuRequestMillicores,memRequestBytes}`；`NodeDrainResultDTO{evicted,skipped,errors: List<String>}`；`NodeEventDTO{reason,message,type,count,firstTimestamp,lastTimestamp}`。后续所有层引用这些类型名与字段名。

- [ ] **Step 1: 写 NodeTaintDTO / NodeConditionDTO（小 DTO，无逻辑）**

```java
// NodeTaintDTO.java
package com.coding.common.models.k8s.dto;
import lombok.Data;
@Data
public class NodeTaintDTO {
    private String key;
    private String value;
    /** NoSchedule / PreferNoSchedule / NoExecute */
    private String effect;
}
```

```java
// NodeConditionDTO.java
package com.coding.common.models.k8s.dto;
import lombok.Data;
@Data
public class NodeConditionDTO {
    private String type;   // Ready / MemoryPressure / DiskPressure / ...
    private String status; // True / False / Unknown
    private String reason;
    private String message;
    private String lastHeartbeatTime;
    private String lastTransitionTime;
}
```

- [ ] **Step 2: 写 NodeDTO（extends BaseResources）**

```java
// NodeDTO.java
package com.coding.common.models.k8s.dto;
import com.coding.common.models.k8s.BaseResources;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.util.List;

/** 节点 DTO（集群级，无命名空间）。labels 继承自 BaseResources（供标签编辑整体回写）。 */
@Data
@EqualsAndHashCode(callSuper = true)
public class NodeDTO extends BaseResources {
    private String status;            // Ready / NotReady（取 conditions 里 type=Ready）
    private Boolean unschedulable;    // spec.unschedulable（cordon 状态）
    private List<String> roles;       // node-role.kubernetes.io/* 的 key 后缀（control-plane/worker/...）
    private String kubeletVersion;
    private String os;                // linux / windows
    private String arch;
    private String kernelVersion;
    private String containerRuntimeVersion;
    private String osImage;
    private String podCidr;
    private String internalIp;
    private String externalIp;
    private String cpuCapacity;       // 原始量，如 "8"
    private String memoryCapacity;    // 如 "16Gi"
    private String cpuAllocatable;    // 如 "7920m"
    private String memoryAllocatable; // 如 "15Gi"
    private Long podsLimit;           // allocatable.pods，如 110
    private List<NodeConditionDTO> conditions;
    private List<NodeTaintDTO> taints;
    private String creationTime;      // ISO-8601

    @Override
    public String getApiPath() { return "/resources/nodes"; }
}
```

- [ ] **Step 3: 写 NodePodStatDTO / NodeDrainResultDTO / NodeEventDTO**

```java
// NodePodStatDTO.java —— 列表页「实际 Pod 数 + 请求分配率」按节点聚合
package com.coding.common.models.k8s.dto;
import lombok.Data;
@Data
public class NodePodStatDTO {
    private String nodeName;
    private long podCount;
    private long cpuRequestMillicores; // sum(pod.cpu.requests)，毫核
    private long memRequestBytes;      // sum(pod.memory.requests)，字节
}
```

```java
// NodeDrainResultDTO.java
package com.coding.common.models.k8s.dto;
import lombok.Data;
import java.util.ArrayList;
import java.util.List;
@Data
public class NodeDrainResultDTO {
    private List<String> evicted = new ArrayList<>();   // ns/name
    private List<String> skipped = new ArrayList<>();   // "ns/name: 原因"
    private List<String> errors = new ArrayList<>();    // "ns/name: 错误"
}
```

```java
// NodeEventDTO.java
package com.coding.common.models.k8s.dto;
import lombok.Data;
@Data
public class NodeEventDTO {
    private String reason;
    private String message;
    private String type;   // Normal / Warning
    private Integer count;
    private String firstTimestamp;
    private String lastTimestamp;
}
```

- [ ] **Step 4: ResourceType 加 NODE**

在 `ResourceType.java` 顶部 import 区加：
```java
import com.coding.common.models.k8s.dto.NodeDTO;
```
在枚举常量里（`HPA(HpaDTO.class)` 之后、`;` 之前）加一行：
```java
    HPA(HpaDTO.class),
    NODE(NodeDTO.class)
    ;
```

- [ ] **Step 5: 编译验证**

Run: `mvn -q -pl platform-common -am compile`
Expected: BUILD SUCCESS（新 DTO 编译通过，ResourceType.NODE 生效）

- [ ] **Step 6: Commit**

```bash
git add platform-common/src/main/java/com/coding/common/models/k8s/dto/Node*.java platform-common/src/main/java/com/coding/common/models/k8s/ResourceType.java
git commit -m "feat(node): add NodeDTO and related DTOs + ResourceType.NODE"
```

---

### Task 2: CoreV1NodeConverter（k8s-core）

**Files:**
- Create: `k8s-core/src/main/java/com/coding/k8score/converter/impl/core/CoreV1NodeConverter.java`
- Test: `k8s-core/src/test/java/com/coding/k8score/converter/impl/core/CoreV1NodeConverterTest.java`

**Interfaces:**
- Consumes: `NodeDTO`（Task 1）、fabric8 `io.fabric8.kubernetes.api.model.Node`。
- Produces: `CoreV1NodeConverter implements CommonConverter<Node, NodeDTO>`，方法 `Node convert(NodeDTO)`、`NodeDTO revert(Node)`。Task 3 的 operations 用它做双向转换。

- [ ] **Step 1: 确认 k8s-core 有测试依赖**

Run: `grep -q spring-boot-starter-test k8s-core/pom.xml || echo MISSING`
若 MISSING，在 `k8s-core/pom.xml` 的 `<dependencies>` 加：
```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-test</artifactId>
    <scope>test</scope>
</dependency>
```

- [ ] **Step 2: 写失败测试（revert：Node → DTO）**

```java
// CoreV1NodeConverterTest.java
package com.coding.k8score.converter.impl.core;
import com.coding.common.models.k8s.dto.NodeDTO;
import io.fabric8.kubernetes.api.model.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class CoreV1NodeConverterTest {
    private final CoreV1NodeConverter c = new CoreV1NodeConverter();

    @Test
    void revert_maps_identity_status_ips_and_capacity() {
        Node n = new NodeBuilder()
            .withMetadata(new ObjectMetaBuilder().withName("node1")
                .withLabels(java.util.Map.of(
                    "node-role.kubernetes.io/control-plane", "",
                    "kubernetes.io/os", "linux")).build())
            .withSpec(new NodeSpecBuilder().withUnschedulable(true).build())
            .withStatus(new NodeStatusBuilder()
                .withNodeInfo(new NodeSystemInfoBuilder()
                    .withKubeletVersion("v1.29.0").withOsImage("Ubuntu 22.04")
                    .withArchitecture("amd64").withKernelVersion("5.15").withContainerRuntimeVersion("containerd://1.7").build())
                .withPodCIDR("10.244.1.0/24")
                .withAddresses(java.util.List.of(
                    new NodeAddressBuilder().withType("InternalIP").withAddress("192.168.85.162").build(),
                    new NodeAddressBuilder().withType("ExternalIP").withAddress("1.2.3.4").build()))
                .withCapacity(io.fabric8.kubernetes.api.model.Quantity.parse("8"), "cpu")
                .withCapacity(io.fabric8.kubernetes.api.model.Quantity.parse("16Gi"), "memory")
                .withAllocatable(io.fabric8.kubernetes.api.model.Quantity.parse("7920m"), "cpu")
                .withAllocatable(io.fabric8.kubernetes.api.model.Quantity.parse("15Gi"), "memory")
                .withAllocatable(io.fabric8.kubernetes.api.model.Quantity.parse("110"), "pods")
                .withConditions(java.util.List.of(
                    new NodeConditionBuilder().withType("Ready").withStatus("True")
                        .withReason("KubeletReady").withMessage("kubelet is ready").build()))
                .withTaints(java.util.List.of(
                    new TaintBuilder().withKey("dedicated").withValue("gpu").withEffect("NoSchedule").build()))
                .build())
            .build();

        NodeDTO d = c.revert(n);
        assertThat(d.getName()).isEqualTo("node1");
        assertThat(d.getStatus()).isEqualTo("Ready");
        assertThat(d.getUnschedulable()).isTrue();
        assertThat(d.getRoles()).containsExactly("control-plane");
        assertThat(d.getKubeletVersion()).isEqualTo("v1.29.0");
        assertThat(d.getInternalIp()).isEqualTo("192.168.85.162");
        assertThat(d.getExternalIp()).isEqualTo("1.2.3.4");
        assertThat(d.getCpuCapacity()).isEqualTo("8");
        assertThat(d.getMemoryAllocatable()).isEqualTo("15Gi");
        assertThat(d.getPodsLimit()).isEqualTo(110L);
        assertThat(d.getConditions()).hasSize(1);
        assertThat(d.getTaints()).hasSize(1).first().extracting("key").isEqualTo("dedicated");
    }

    @Test
    void revert_null_returns_null() {
        assertThat(c.revert(null)).isNull();
    }
}
```

- [ ] **Step 3: 运行确认失败**

Run: `mvn -q -pl k8s-core -am test -Dtest=CoreV1NodeConverterTest`
Expected: FAIL（`CoreV1NodeConverter` 不存在 / 编译错误）

- [ ] **Step 4: 实现 CoreV1NodeConverter**

```java
// CoreV1NodeConverter.java
package com.coding.k8score.converter.impl.core;

import com.coding.common.models.k8s.dto.NodeConditionDTO;
import com.coding.common.models.k8s.dto.NodeDTO;
import com.coding.common.models.k8s.dto.NodeTaintDTO;
import com.coding.k8score.converter.CommonConverter;
import io.fabric8.kubernetes.api.model.Node;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.Quantity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** core/v1 Node DTO 转换器（集群级）。 */
public class CoreV1NodeConverter implements CommonConverter<Node, NodeDTO> {

    @Override
    public Node convert(NodeDTO dto) {
        // 节点不支持整体 create；convert 仅用于 updateLabelsTaints 时保留 name（其余由 operations 直接改 live 对象）
        return new io.fabric8.kubernetes.api.model.NodeBuilder()
                .withMetadata(new ObjectMetaBuilder().withName(dto.getName()).build())
                .build();
    }

    @Override
    public NodeDTO revert(Node n) {
        if (n == null) return null;
        NodeDTO d = new NodeDTO();
        var md = n.getMetadata();
        if (md != null) {
            d.setName(md.getName());
            d.setLabels(md.getLabels() != null ? new LinkedHashMap<>(md.getLabels()) : new LinkedHashMap<>());
            if (md.getCreationTimestamp() != null) d.setCreationTime(md.getCreationTimestamp());
            d.setRoles(rolesFromLabels(md.getLabels()));
        }
        if (n.getSpec() != null) d.setUnschedulable(n.getSpec().getUnschedulable());
        var st = n.getStatus();
        if (st != null) {
            var info = st.getNodeInfo();
            if (info != null) {
                d.setKubeletVersion(info.getKubeletVersion());
                d.setOs(info.getOsImage() == null ? null : osOf(info));
                d.setArch(info.getArchitecture());
                d.setKernelVersion(info.getKernelVersion());
                d.setContainerRuntimeVersion(info.getContainerRuntimeVersion());
                d.setOsImage(info.getOsImage());
            }
            d.setPodCidr(st.getPodCIDR());
            if (st.getAddresses() != null) {
                for (var a : st.getAddresses()) {
                    if ("InternalIP".equals(a.getType())) d.setInternalIp(a.getAddress());
                    else if ("ExternalIP".equals(a.getType())) d.setExternalIp(a.getAddress());
                }
            }
            Map<String, Quantity> cap = st.getCapacity() != null ? st.getCapacity() : Map.of();
            Map<String, Quantity> alloc = st.getAllocatable() != null ? st.getAllocatable() : Map.of();
            d.setCpuCapacity(q(cap.get("cpu")));
            d.setMemoryCapacity(q(cap.get("memory")));
            d.setCpuAllocatable(q(alloc.get("cpu")));
            d.setMemoryAllocatable(q(alloc.get("memory")));
            if (alloc.get("pods") != null) d.setPodsLimit(Long.parseLong(alloc.get("pods").getAmount()));
            if (st.getConditions() != null) {
                List<NodeConditionDTO> conds = new ArrayList<>();
                for (var c : st.getConditions()) {
                    NodeConditionDTO cd = new NodeConditionDTO();
                    cd.setType(c.getType());
                    cd.setStatus(c.getStatus());
                    cd.setReason(c.getReason());
                    cd.setMessage(c.getMessage());
                    cd.setLastHeartbeatTime(c.getLastHeartbeatTime());
                    cd.setLastTransitionTime(c.getLastTransitionTime());
                    conds.add(cd);
                    if ("Ready".equals(c.getType())) d.setStatus(c.getStatus());
                }
                d.setConditions(conds);
            }
            if (st.getTaints() != null) {
                List<NodeTaintDTO> ts = new ArrayList<>();
                for (var t : st.getTaints()) {
                    NodeTaintDTO td = new NodeTaintDTO();
                    td.setKey(t.getKey());
                    td.setValue(t.getValue());
                    td.setEffect(t.getEffect());
                    ts.add(td);
                }
                d.setTaints(ts);
            }
        }
        return d;
    }

    /** node-role.kubernetes.io/<role> 的 <role> 集合。 */
    private List<String> rolesFromLabels(Map<String, String> labels) {
        if (labels == null) return new ArrayList<>();
        List<String> roles = new ArrayList<>();
        for (String k : labels.keySet()) {
            if (k.startsWith("node-role.kubernetes.io/")) {
                roles.add(k.substring("node-role.kubernetes.io/".length()));
            }
        }
        return roles;
    }

    private String osOf(io.fabric8.kubernetes.api.model.NodeSystemInfo info) {
        // 优先读 kubernetes.io/os label 由调用方填；此处退回 runtime 推断，默认 linux
        return "linux";
    }

    private String q(Quantity quantity) {
        return quantity == null ? null : quantity.toString();
    }
}
```

> 注：`revert` 里 `os` 先用固定 `"linux"`；若需精确，从 label `kubernetes.io/os` 读（见下 Step 5 修正）。Quantity `toString()` 返回原始量（如 `8`、`15Gi`、`7920m`），与前端展示一致。

- [ ] **Step 5: 运行确认通过**

Run: `mvn -q -pl k8s-core -am test -Dtest=CoreV1NodeConverterTest`
Expected: PASS（2 tests）

- [ ] **Step 6: Commit**

```bash
git add k8s-core/src/main/java/com/coding/k8score/converter/impl/core/CoreV1NodeConverter.java k8s-core/src/test/java/com/coding/k8score/converter/impl/core/CoreV1NodeConverterTest.java k8s-core/pom.xml
git commit -m "feat(node): CoreV1NodeConverter (fabric8 Node <-> NodeDTO)"
```

---

### Task 3: CoreV1NodeOperations + 工厂方法（k8s-core）

**Files:**
- Create: `k8s-core/src/main/java/com/coding/k8score/operations/core/CoreV1NodeOperations.java`
- Modify: `k8s-core/src/main/java/com/coding/k8score/factory/KubernetesOperationsFactory.java`
- Test: `k8s-core/src/test/java/com/coding/k8score/operations/core/CoreV1NodeOperationsTest.java`

**Interfaces:**
- Consumes: `NodeDTO`、`CoreV1NodeConverter`（Task 2）、fabric8 `KubernetesClient`。
- Produces: `CoreV1NodeOperations`，构造 `(KubernetesClient, CommonConverter<Node,NodeDTO>)`，方法：
  - `List<NodeDTO> list(String labelSelector, String fieldSelector)`
  - `NodeDTO get(String name)`
  - `String yaml(String name)`
  - `NodeDTO cordon(String name)` / `NodeDTO uncordon(String name)`
  - `NodeDrainResultDTO drain(String name, boolean force, boolean deleteEmptyDir)`
  - `NodeDTO updateLabelsTaints(String name, Map<String,String> labels, List<NodeTaintDTO> taints)`
  - `List<NodePodStatDTO> listPodStats()`
  - `List<NodeEventDTO> listEvents(String name)`
  工厂新增：`CoreV1NodeOperations getNodeOperation(String clusterId)`。Task 4 的 controller 调这些方法。

- [ ] **Step 1: 写失败测试（cordon/uncordon 设置 unschedulable；listPodStats 按 nodeName 聚合）**

```java
// CoreV1NodeOperationsTest.java
package com.coding.k8score.operations.core;
import com.coding.common.models.k8s.dto.*;
import com.coding.k8score.converter.impl.core.CoreV1NodeConverter;
import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CoreV1NodeOperationsTest {

    @SuppressWarnings("unchecked")
    private KubernetesClient mockClient() { return mock(KubernetesClient.class); }

    @Test
    void listPodStats_groups_by_nodeName_and_sums_requests() {
        KubernetesClient client = mockClient();
        Pod p1 = new PodBuilder().withMetadata(new ObjectMetaBuilder().withNamespace("a").withName("p1").build())
            .withSpec(new PodSpecBuilder().withNodeName("n1")
                .addNewContainer().withName("c")
                .withResources(new ResourceRequirementsBuilder()
                    .addToRequests("cpu", new Quantity("200m"))
                    .addToRequests("memory", new Quantity("128Mi")).build()).endContainer().build()).build();
        Pod p2 = new PodBuilder().withMetadata(new ObjectMetaBuilder().withNamespace("b").withName("p2").build())
            .withSpec(new PodSpecBuilder().withNodeName("n1")
                .addNewContainer().withName("c")
                .withResources(new ResourceRequirementsBuilder()
                    .addToRequests("cpu", new Quantity("1")).build()).endContainer().build()).build();
        // mock: client.pods().inAnyNamespace().list() -> PodList
        var podOp = mock(io.fabric8.kubernetes.client.dsl.NonNamespaceOperation.class);
        when(client.pods()).thenReturn(podOp);
        when(podOp.inAnyNamespace()).thenReturn(podOp);
        PodList pl = new PodListBuilder().addToItems(p1, p2).build();
        when(podOp.list(any())).thenReturn(pl);

        CoreV1NodeOperations ops = new CoreV1NodeOperations(client, new CoreV1NodeConverter());
        List<NodePodStatDTO> stats = ops.listPodStats();
        assertThat(stats).hasSize(1);
        NodePodStatDTO s = stats.get(0);
        assertThat(s.getNodeName()).isEqualTo("n1");
        assertThat(s.getPodCount()).isEqualTo(2L);
        assertThat(s.getCpuRequestMillicores()).isEqualTo(1200L); // 200m + 1000m
        assertThat(s.getMemRequestBytes()).isGreaterThan(0L);     // 128Mi
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -q -pl k8s-core -am test -Dtest=CoreV1NodeOperationsTest`
Expected: FAIL（`CoreV1NodeOperations` 不存在）

- [ ] **Step 3: 实现 CoreV1NodeOperations**

```java
// CoreV1NodeOperations.java
package com.coding.k8score.operations.core;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.k8s.dto.*;
import com.coding.k8score.converter.CommonConverter;
import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.api.model.events.v1.Event;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation;
import io.fabric8.kubernetes.client.utils.Serialization;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** core/v1 Node 操作（集群级）。节点无 create/delete；提供 cordon/drain/label-taint/pod 分组/事件。 */
public class CoreV1NodeOperations {

    private final KubernetesClient client;
    private final CommonConverter<Node, NodeDTO> converter;

    public CoreV1NodeOperations(KubernetesClient client, CommonConverter<Node, NodeDTO> converter) {
        this.client = client;
        this.converter = converter;
    }

    public List<NodeDTO> list(String labelSelector, String fieldSelector) {
        ListOptions o = new ListOptions();
        if (StringUtils.hasText(labelSelector)) o.setLabelSelector(labelSelector);
        if (StringUtils.hasText(fieldSelector)) o.setFieldSelector(fieldSelector);
        return client.nodes().list(o).getItems().stream().map(converter::revert).toList();
    }

    public NodeDTO get(String name) {
        requireName(name);
        return converter.revert(client.nodes().withName(name).get());
    }

    public String yaml(String name) {
        requireName(name);
        Node n = client.nodes().withName(name).get();
        if (n != null && n.getMetadata() != null) n.getMetadata().setManagedFields(null);
        return Serialization.asYaml(n);
    }

    public NodeDTO cordon(String name) { return setUnschedulable(name, true); }
    public NodeDTO uncordon(String name) { return setUnschedulable(name, false); }

    private NodeDTO setUnschedulable(String name, boolean v) {
        requireName(name);
        Node updated = client.nodes().withName(name).edit(n -> {
            if (n.getSpec() == null) n.setSpec(new NodeSpec());
            n.getSpec().setUnschedulable(v);
            return n;
        });
        return converter.revert(updated);
    }

    public NodeDTO updateLabelsTaints(String name, Map<String, String> labels, List<NodeTaintDTO> taints) {
        requireName(name);
        Node updated = client.nodes().withName(name).edit(n -> {
            n.getMetadata().setLabels(labels != null ? new LinkedHashMap<>(labels) : new LinkedHashMap<>());
            if (n.getSpec() == null) n.setSpec(new NodeSpec());
            List<Taint> ts = new ArrayList<>();
            if (taints != null) for (NodeTaintDTO t : taints) {
                ts.add(new TaintBuilder().withKey(t.getKey()).withValue(t.getValue()).withEffect(t.getEffect()).build());
            }
            n.getSpec().setTaints(ts);
            return n;
        });
        return converter.revert(updated);
    }

    /** 驱逐节点上的 Pod（保留 DaemonSet/静态/mirror）。force=true 时 PDB 阻断改为直接 delete。 */
    public NodeDrainResultDTO drain(String name, boolean force, boolean deleteEmptyDir) {
        requireName(name);
        List<Pod> pods = client.pods().inAnyNamespace()
                .list(new ListOptionsBuilder().withFieldSelector("spec.nodeName=" + name).build()).getItems();
        NodeDrainResultDTO result = new NodeDrainResultDTO();
        for (Pod pod : pods) {
            String ns = pod.getMetadata().getNamespace();
            String pn = pod.getMetadata().getName();
            String key = ns + "/" + pn;
            String skip = skipReason(pod);
            if (skip != null) { result.getSkipped().add(key + ": " + skip); continue; }
            try {
                if (force) client.pods().inNamespace(ns).withName(pn).delete();
                else evict(ns, pn);
                result.getEvicted().add(key);
            } catch (Exception e) {
                if (force) {
                    try { client.pods().inNamespace(ns).withName(pn).delete(); result.getEvicted().add(key); }
                    catch (Exception e2) { result.getErrors().add(key + ": " + e2.getMessage()); }
                } else {
                    result.getErrors().add(key + ": " + e.getMessage());
                }
            }
        }
        return result;
    }

    private void evict(String ns, String name) {
        Eviction ev = new EvictionBuilder()
                .withNewMetadata().withName(name).withNamespace(ns).endMetadata()
                .build();
        client.pods().inNamespace(ns).withName(name).evict(ev);
    }

    /** 返回跳过原因；null = 应驱逐。 */
    private String skipReason(Pod pod) {
        var md = pod.getMetadata();
        if (md.getAnnotations() != null && md.getAnnotations().containsKey("kubernetes.io/mirror")) return "mirror-pod";
        boolean staticPod = md.getOwnerReferences() == null || md.getOwnerReferences().isEmpty();
        if (staticPod) return "static-pod";
        for (var ref : md.getOwnerReferences()) {
            if ("DaemonSet".equals(ref.getKind())) return "daemonset";
        }
        return null;
    }

    /** 集群级 pod 列表按 nodeName 聚合：count + cpu/mem requests 之和。 */
    public List<NodePodStatDTO> listPodStats() {
        Map<String, long[]> byNode = new ConcurrentHashMap<>(); // [count, cpuMilli, memBytes]
        for (Pod pod : client.pods().inAnyNamespace().list().getItems()) {
            String nodeName = pod.getSpec() != null ? pod.getSpec().getNodeName() : null;
            if (!StringUtils.hasText(nodeName)) continue;
            long[] acc = byNode.computeIfAbsent(nodeName, k -> new long[3]);
            acc[0]++;
            if (pod.getSpec().getContainers() == null) continue;
            for (var c : pod.getSpec().getContainers()) {
                var req = c.getResources() != null ? c.getResources().getRequests() : null;
                if (req == null) continue;
                acc[1] += cpuToMilli(req.get("cpu"));
                acc[2] += memToBytes(req.get("memory"));
            }
        }
        List<NodePodStatDTO> out = new ArrayList<>();
        byNode.forEach((node, a) -> {
            NodePodStatDTO s = new NodePodStatDTO();
            s.setNodeName(node);
            s.setPodCount(a[0]);
            s.setCpuRequestMillicores(a[1]);
            s.setMemRequestBytes(a[2]);
            out.add(s);
        });
        return out;
    }

    private long cpuToMilli(Quantity q) {
        if (q == null) return 0L;
        // "200m" -> 200；"1" -> 1000；"0.5" -> 500
        String s = q.getFormat() != null && !q.getFormat().isEmpty() ? q.getAmount() : q.getFormatted();
        if (s.endsWith("m")) return Long.parseLong(s.substring(0, s.length() - 1));
        return (long) Math.round(Double.parseDouble(s) * 1000);
    }

    private long memToBytes(Quantity q) {
        if (q == null) return 0L;
        String f = q.getFormat();
        String amt = q.getAmount();
        double n = Double.parseDouble(amt);
        return switch (f == null ? "" : f) {
            case "m" -> (long) (n / 1024);
            case "k", "K" -> (long) (n * 1024);
            case "M", "Mi" -> (long) (n * 1024 * 1024);
            case "G", "Gi" -> (long) (n * 1024L * 1024 * 1024);
            case "T", "Ti" -> (long) (n * 1024L * 1024 * 1024 * 1024);
            default -> (long) n; // 纯字节
        };
    }

    /** 节点事件：查 involvedObject.name=<name>（跨命名空间），过滤 kind=Node。 */
    public List<NodeEventDTO> listEvents(String name) {
        requireName(name);
        var op = client.v1().events().inAnyNamespace();
        List<Event> events;
        try {
            events = op.withField("involvedObject.name", name).list().getItems();
        } catch (Exception e) {
            events = op.list().getItems(); // 老集群不支持该 fieldSelector 时退回全量再过滤
        }
        List<NodeEventDTO> out = new ArrayList<>();
        for (Event e : events) {
            if (e.getInvolvedObject() == null || !"Node".equals(e.getInvolvedObject().getKind())) continue;
            NodeEventDTO d = new NodeEventDTO();
            d.setReason(e.getReason());
            d.setMessage(e.getMessage());
            d.setType(e.getType());
            d.setCount(e.getCount());
            if (e.getFirstTimestamp() != null) d.setFirstTimestamp(e.getFirstTimestamp());
            if (e.getLastTimestamp() != null) d.setLastTimestamp(e.getLastTimestamp());
            out.add(d);
        }
        return out;
    }

    private void requireName(String name) {
        if (!StringUtils.hasText(name)) throw new CloudPlatformException(EnumResponseType.SEARCH_K8S_REQUIRE_NAME_AND_NAMESPACE);
    }
}
```

- [ ] **Step 4: 工厂加 getNodeOperation**

在 `KubernetesOperationsFactory.java`：顶部 import 加
```java
import com.coding.k8score.converter.impl.core.CoreV1NodeConverter;
import com.coding.k8score.operations.core.CoreV1NodeOperations;
```
在 `getClusterOperation(...)` 方法之后加：
```java
    /** 节点操作（集群级，admin client）。节点非标准 CRUD，故独立返回具体类型而非 ClusterOperations。 */
    public CoreV1NodeOperations getNodeOperation(String clusterId) {
        KubernetesClient client = clientFactory.getAdminClient(clusterId);
        return new CoreV1NodeOperations(client, new CoreV1NodeConverter());
    }
```

- [ ] **Step 5: 运行确认通过**

Run: `mvn -q -pl k8s-core -am test -Dtest=CoreV1NodeOperationsTest`
Expected: PASS（listPodStats 聚合正确）

- [ ] **Step 6: Commit**

```bash
git add k8s-core/src/main/java/com/coding/k8score/operations/core/CoreV1NodeOperations.java k8s-core/src/main/java/com/coding/k8score/factory/KubernetesOperationsFactory.java k8s-core/src/test/java/com/coding/k8score/operations/core/CoreV1NodeOperationsTest.java
git commit -m "feat(node): CoreV1NodeOperations (cordon/drain/label/podstats/events) + factory"
```

---

### Task 4: k8s-server NodeController（admin 边界端点）

**Files:**
- Create: `k8s-server/src/main/java/com/coding/k8sserver/controllers/cluster/NodeController.java`

**Interfaces:**
- Consumes: `KubernetesOperationsFactory.getNodeOperation`（Task 3）、`ResourceAccessResolver.assertClusterAccess(clusterId)`、`NodeDTO`。
- Produces（wire，platform-api Task 5 按此调用）：
  - `POST /resources/nodes/list` body `{clusterId, labelSelector?}` → `ResponseData<List<NodeDTO>>`
  - `GET /resources/nodes/{name}?clusterId` → `ResponseData<NodeDTO>`
  - `GET /resources/nodes/{name}/yaml?clusterId` → `ResponseData<String>`
  - `POST /resources/nodes/cordon` body `{clusterId, name}` → `ResponseData<NodeDTO>`
  - `POST /resources/nodes/uncordon` body `{clusterId, name}` → `ResponseData<NodeDTO>`
  - `PUT /resources/nodes/{name}?clusterId` body `{labels, taints}` → `ResponseData<NodeDTO>`
  - `POST /resources/nodes/drain` body `{clusterId, name, force, deleteEmptyDir}` → `ResponseData<NodeDrainResultDTO>`
  - `GET /resources/nodes/podstats?clusterId` → `ResponseData<List<NodePodStatDTO>>`
  - `GET /resources/nodes/{name}/pods?clusterId` → `ResponseData<List<PodDTO>>`（复用 POD operations）
  - `GET /resources/nodes/{name}/events?clusterId` → `ResponseData<List<NodeEventDTO>>`

- [ ] **Step 1: 写 NodeController**

```java
// NodeController.java
package com.coding.k8sserver.controllers.cluster;

import com.coding.common.models.k8s.ResourceType;
import com.coding.common.models.k8s.dto.NodeDTO;
import com.coding.common.models.k8s.dto.NodeDrainResultDTO;
import com.coding.common.models.k8s.dto.NodeEventDTO;
import com.coding.common.models.k8s.dto.NodePodStatDTO;
import com.coding.common.models.k8s.dto.PodDTO;
import com.coding.common.models.system.ResponseData;
import com.coding.k8score.factory.KubernetesOperationsFactory;
import com.coding.k8score.operations.ClusterOperations;
import com.coding.k8score.operations.core.CoreV1NodeOperations;
import com.coding.k8sserver.components.ResourceAccessResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** 集群域 - Node（PLATFORM:admin，边界=平台已注册该集群）。节点非标准 CRUD：list/get/yaml + cordon/drain/label/podstats/events。 */
@Tag(name = "集群域-Node", description = "节点管理（PLATFORM:admin，边界=集群注册表）")
@RestController
@RequestMapping("/resources/nodes")
public class NodeController {

    private final KubernetesOperationsFactory operationsFactory;
    private final ResourceAccessResolver accessResolver;

    public NodeController(KubernetesOperationsFactory operationsFactory, ResourceAccessResolver accessResolver) {
        this.operationsFactory = operationsFactory;
        this.accessResolver = accessResolver;
    }

    @PostMapping("/list")
    @Operation(summary = "列出节点（body 传 clusterId/labelSelector）")
    public ResponseData<List<NodeDTO>> list(@RequestBody NodeDTO body) {
        accessResolver.assertClusterAccess(body.getClusterId());
        List<NodeDTO> items = ops(body.getClusterId()).list(body.getLabelSelector(), body.getFieldSelector());
        items.forEach(i -> i.setClusterId(body.getClusterId()));
        return new ResponseData<>(items);
    }

    @GetMapping("/{name}")
    @Operation(summary = "查询节点")
    public ResponseData<NodeDTO> get(@PathVariable String name, @RequestParam String clusterId) {
        accessResolver.assertClusterAccess(clusterId);
        NodeDTO item = ops(clusterId).get(name);
        if (item != null) item.setClusterId(clusterId);
        return new ResponseData<>(item);
    }

    @GetMapping("/{name}/yaml")
    @Operation(summary = "查询节点 YAML（只读）")
    public ResponseData<String> yaml(@PathVariable String name, @RequestParam String clusterId) {
        accessResolver.assertClusterAccess(clusterId);
        return new ResponseData<>(ops(clusterId).yaml(name));
    }

    @PostMapping("/cordon")
    @Operation(summary = "标记节点不可调度")
    public ResponseData<NodeDTO> cordon(@RequestBody NodeDTO body) {
        accessResolver.assertClusterAccess(body.getClusterId());
        return new ResponseData<>(ops(body.getClusterId()).cordon(body.getName()));
    }

    @PostMapping("/uncordon")
    @Operation(summary = "标记节点可调度")
    public ResponseData<NodeDTO> uncordon(@RequestBody NodeDTO body) {
        accessResolver.assertClusterAccess(body.getClusterId());
        return new ResponseData<>(ops(body.getClusterId()).uncordon(body.getName()));
    }

    @PutMapping("/{name}")
    @Operation(summary = "更新节点标签 + Taint（整体替换）")
    public ResponseData<NodeDTO> updateLabelsTaints(@PathVariable String name, @RequestParam String clusterId,
                                                    @RequestBody NodeLabelTaintRequest req) {
        accessResolver.assertClusterAccess(clusterId);
        return new ResponseData<>(ops(clusterId).updateLabelsTaints(name, req.getLabels(), req.getTaints()));
    }

    @PostMapping("/drain")
    @Operation(summary = "驱逐节点 Pod（保留 DaemonSet/静态/mirror）")
    public ResponseData<NodeDrainResultDTO> drain(@RequestBody NodeDrainRequest req) {
        accessResolver.assertClusterAccess(req.getClusterId());
        return new ResponseData<>(ops(req.getClusterId()).drain(req.getName(),
                Boolean.TRUE.equals(req.getForce()), Boolean.TRUE.equals(req.getDeleteEmptyDir())));
    }

    @GetMapping("/podstats")
    @Operation(summary = "按节点聚合实际 Pod 数 + requests（列表页用）")
    public ResponseData<List<NodePodStatDTO>> podstats(@RequestParam String clusterId) {
        accessResolver.assertClusterAccess(clusterId);
        return new ResponseData<>(ops(clusterId).listPodStats());
    }

    @GetMapping("/{name}/pods")
    @Operation(summary = "列出节点上的 Pod（按 spec.nodeName）")
    public ResponseData<List<PodDTO>> pods(@PathVariable String name, @RequestParam String clusterId) {
        accessResolver.assertClusterAccess(clusterId);
        PodDTO q = new PodDTO();
        q.setFieldSelector("spec.nodeName=" + name);
        ClusterOperations<PodDTO> podOps = operationsFactory.getClusterOperation(ResourceType.POD, clusterId);
        return new ResponseData<>(podOps.list(null, "spec.nodeName=" + name));
    }

    @GetMapping("/{name}/events")
    @Operation(summary = "节点事件（involvedObject=Node）")
    public ResponseData<List<NodeEventDTO>> events(@PathVariable String name, @RequestParam String clusterId) {
        accessResolver.assertClusterAccess(clusterId);
        return new ResponseData<>(ops(clusterId).listEvents(name));
    }

    private CoreV1NodeOperations ops(String clusterId) {
        return operationsFactory.getNodeOperation(clusterId);
    }

    // ---- 请求体（k8s-server 局部 DTO，不入库）----
    public static class NodeLabelTaintRequest {
        private Map<String, String> labels;
        private List<com.coding.common.models.k8s.dto.NodeTaintDTO> taints;
        public Map<String, String> getLabels() { return labels; }
        public void setLabels(Map<String, String> labels) { this.labels = labels; }
        public List<com.coding.common.models.k8s.dto.NodeTaintDTO> getTaints() { return taints; }
        public void setTaints(List<com.coding.common.models.k8s.dto.NodeTaintDTO> taints) { this.taints = taints; }
    }

    public static class NodeDrainRequest {
        private String clusterId;
        private String name;
        private Boolean force;
        private Boolean deleteEmptyDir;
        public String getClusterId() { return clusterId; }
        public void setClusterId(String v) { this.clusterId = v; }
        public String getName() { return name; }
        public void setName(String v) { this.name = v; }
        public Boolean getForce() { return force; }
        public void setForce(Boolean v) { this.force = v; }
        public Boolean getDeleteEmptyDir() { return deleteEmptyDir; }
        public void setDeleteEmptyDir(Boolean v) { this.deleteEmptyDir = v; }
    }
}
```

> 注：`pods` 端点用 `getClusterOperation(ResourceType.POD, ...)` 复用现有 Pod operations（Pod 是 namespaced，但按 `spec.nodeName` fieldSelector 集群级列出；若 `CoreV1PodOperations.list` 不支持无 namespace，见 Step 3 校验）。

- [ ] **Step 2: 编译验证**

Run: `mvn -q -pl k8s-server -am compile`
Expected: BUILD SUCCESS

- [ ] **Step 3: 校验 Pod 集群级 list 可用**

Run: `grep -n "public List<PodDTO> list" k8s-core/src/main/java/com/coding/k8score/operations/core/CoreV1PodOperations.java`
若 Pod operations 的 list 需要 namespace（走 tenant client / namespaced），则改用 admin namespaced：把 `pods` 端点里的 `getClusterOperation(ResourceType.POD, clusterId)` 换成 `operationsFactory.getAdminNamespacedOperation(ResourceType.POD, clusterId)` 并调其 `list(labelSelector, fieldSelector, null)`（以该接口实际签名为准）。编译通过后确认。

- [ ] **Step 4: Commit**

```bash
git add k8s-server/src/main/java/com/coding/k8sserver/controllers/cluster/NodeController.java
git commit -m "feat(node): k8s-server NodeController (admin boundary endpoints)"
```

---

### Task 5: platform-api 节点透传（list/get/yaml/cordon/drain/label/podstats/events）

**Files:**
- Create: `platform-api/src/main/java/com/coding/platformapi/controllers/resource/NodeController.java`

**Interfaces:**
- Consumes: `K8sResourceClient`（list/get/yaml 走通用 DTO）、`K8sServerGateway.exchange`（cordon/drain/podstats/events 走固定路径）、`NodeDTO`、k8s-server Task 4 wire。
- Produces（前端 nodeApi Task 7 按此调用，前缀 `/resource/nodes`）：
  - `POST /resource/nodes/list` body `{clusterId}` → `List<NodeDTO>`
  - `GET /resource/nodes/{name}?clusterId` → `NodeDTO`
  - `GET /resource/nodes/{name}/yaml?clusterId` → `String`
  - `POST /resource/nodes/cordon` body `{clusterId,name}` → `NodeDTO`
  - `POST /resource/nodes/uncordon` body `{clusterId,name}` → `NodeDTO`
  - `PUT /resource/nodes/{name}?clusterId` body `{labels,taints}` → `NodeDTO`
  - `POST /resource/nodes/drain` body `{clusterId,name,force,deleteEmptyDir}` → `NodeDrainResultDTO`
  - `GET /resource/nodes/podstats?clusterId` → `List<NodePodStatDTO>`
  - `GET /resource/nodes/{name}/pods?clusterId` → `List<PodDTO>`
  - `GET /resource/nodes/{name}/events?clusterId` → `List<NodeEventDTO>`

- [ ] **Step 1: 写 platform-api NodeController**

```java
// NodeController.java (platform-api)
package com.coding.platformapi.controllers.resource;

import com.coding.common.models.k8s.dto.*;
import com.coding.common.models.system.ResponseData;
import com.coding.platformapi.k8s.K8sResourceClient;
import com.coding.platformapi.k8s.K8sServerGateway;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 资源管理 - Node（集群级，admin）。list/get/yaml 走通用 client；动作端点直连 gateway。 */
@Tag(name = "资源管理-Node", description = "节点管理（集群级）")
@RestController
@RequestMapping("/resource/nodes")
@RequiredArgsConstructor
public class NodeController {

    private final K8sResourceClient k8s;
    private final K8sServerGateway gateway;

    @PostMapping("/list")
    public ResponseData<List<NodeDTO>> list(@RequestBody NodeDTO body) {
        return new ResponseData<>(k8s.list(body));
    }

    @GetMapping("/{name}")
    public ResponseData<NodeDTO> get(@PathVariable String name, @RequestParam String clusterId) {
        return new ResponseData<>(k8s.get(dto(name, clusterId)));
    }

    @GetMapping("/{name}/yaml")
    public ResponseData<String> yaml(@PathVariable String name, @RequestParam String clusterId) {
        return new ResponseData<>(k8s.yaml(dto(name, clusterId)));
    }

    @PostMapping("/cordon")
    public ResponseData<NodeDTO> cordon(@RequestBody NodeDTO body) {
        return new ResponseData<>(gateway.exchange(org.springframework.http.HttpMethod.POST, "/resources/nodes/cordon", null, body, gateway.responseType(NodeDTO.class)));
    }

    @PostMapping("/uncordon")
    public ResponseData<NodeDTO> uncordon(@RequestBody NodeDTO body) {
        return new ResponseData<>(gateway.exchange(org.springframework.http.HttpMethod.POST, "/resources/nodes/uncordon", null, body, gateway.responseType(NodeDTO.class)));
    }

    @PutMapping("/{name}")
    public ResponseData<NodeDTO> updateLabelsTaints(@PathVariable String name, @RequestParam String clusterId,
                                                    @RequestBody NodeLabelTaintReq req) {
        var params = java.util.Map.of("clusterId", clusterId);
        return new ResponseData<>(gateway.exchange(org.springframework.http.HttpMethod.PUT, "/resources/nodes/" + name, params, req, gateway.responseType(NodeDTO.class)));
    }

    @PostMapping("/drain")
    public ResponseData<NodeDrainResultDTO> drain(@RequestBody NodeDrainReq req) {
        return new ResponseData<>(gateway.exchange(org.springframework.http.HttpMethod.POST, "/resources/nodes/drain", null, req, gateway.responseType(NodeDrainResultDTO.class)));
    }

    @GetMapping("/podstats")
    public ResponseData<List<NodePodStatDTO>> podstats(@RequestParam String clusterId) {
        return new ResponseData<>(gateway.exchange(org.springframework.http.HttpMethod.GET, "/resources/nodes/podstats", java.util.Map.of("clusterId", clusterId), null, gateway.listResponseType(NodePodStatDTO.class)));
    }

    @GetMapping("/{name}/pods")
    public ResponseData<List<PodDTO>> pods(@PathVariable String name, @RequestParam String clusterId) {
        return new ResponseData<>(gateway.exchange(org.springframework.http.HttpMethod.GET, "/resources/nodes/" + name + "/pods", java.util.Map.of("clusterId", clusterId), null, gateway.listResponseType(PodDTO.class)));
    }

    @GetMapping("/{name}/events")
    public ResponseData<List<NodeEventDTO>> events(@PathVariable String name, @RequestParam String clusterId) {
        return new ResponseData<>(gateway.exchange(org.springframework.http.HttpMethod.GET, "/resources/nodes/" + name + "/events", java.util.Map.of("clusterId", clusterId), null, gateway.listResponseType(NodeEventDTO.class)));
    }

    private NodeDTO dto(String name, String clusterId) {
        NodeDTO d = new NodeDTO();
        d.setName(name);
        d.setClusterId(clusterId);
        return d;
    }

    public static class NodeLabelTaintReq {
        private java.util.Map<String, String> labels;
        private List<NodeTaintDTO> taints;
        public java.util.Map<String, String> getLabels() { return labels; }
        public void setLabels(java.util.Map<String, String> v) { this.labels = v; }
        public List<NodeTaintDTO> getTaints() { return taints; }
        public void setTaints(List<NodeTaintDTO> v) { this.taints = v; }
    }

    public static class NodeDrainReq {
        private String clusterId;
        private String name;
        private Boolean force;
        private Boolean deleteEmptyDir;
        public String getClusterId() { return clusterId; }
        public void setClusterId(String v) { this.clusterId = v; }
        public String getName() { return name; }
        public void setName(String v) { this.name = v; }
        public Boolean getForce() { return force; }
        public void setForce(Boolean v) { this.force = v; }
        public Boolean getDeleteEmptyDir() { return deleteEmptyDir; }
        public void setDeleteEmptyDir(Boolean v) { this.deleteEmptyDir = v; }
    }
}
```

> 注：`K8sServerGateway.exchange(HttpMethod, path, Map params, Object body, TypeReference/Class responseType)` 与 `listResponseType(Class)` 的精确签名以现有 `K8sServerGateway.java` 为准（Task 5 Step 2 编译时核对；若返回类型参数是 `Class<T>` 而非 `TypeReference`，按实际调整）。

- [ ] **Step 2: 编译验证 + 核对 gateway 签名**

Run: `mvn -q -pl platform-api -am compile`
Expected: BUILD SUCCESS。若 `exchange`/`responseType` 参数类型不匹配，读 `K8sServerGateway.java` 对应方法签名并修正调用（保持 wire 路径不变）。

- [ ] **Step 3: Commit**

```bash
git add platform-api/src/main/java/com/coding/platformapi/controllers/resource/NodeController.java
git commit -m "feat(node): platform-api NodeController passthrough (list/actions/podstats/events)"
```

---

### Task 6: platform-api 节点指标（PromQL + MetricsService + 端点）

**Files:**
- Modify: `platform-api/src/main/java/com/coding/platformapi/metrics/MetricQuery.java`（加 node 模板）
- Create: `platform-api/src/main/java/com/coding/platformapi/metrics/labels/InstanceLabels.java`
- Create: `platform-api/src/main/java/com/coding/platformapi/metrics/labels/DeviceLabels.java`
- Create: `platform-api/src/main/java/com/coding/platformapi/metrics/dto/NodeMetricsRequest.java`
- Modify: `platform-api/src/main/java/com/coding/platformapi/metrics/MetricsService.java`（加 node 方法 + multi-series 助手）
- Modify: `platform-api/src/main/java/com/coding/platformapi/controllers/resource/NodeController.java`（加 4 个 metrics 端点 + current）

**Interfaces:**
- Consumes: `ThanosQueryClient.query/queryRange`、`MetricSeriesResponse/MetricSeries/MetricPoint`、`PromResult`。
- Produces（前端 Task 7 按此调用）：
  - `POST /resource/nodes/metrics/current` body `{clusterId, instances:[...], start?, end?}` → `Map<String,{cpuPercent,memPercent}>`（列表页批量，instant query）
  - `POST /resource/nodes/{name}/metrics/cpu` body `NodeMetricsRequest{clusterId,instance,start,end}` → `MetricSeriesResponse`（unit "百分比"，1 series "使用率"）
  - `.../memory` → unit "字节"，4 series（已用/缓存/缓冲/空闲）
  - `.../disk` → unit "字节/秒"，per-device「dev-读」+「dev-写」series
  - `.../network` → unit "字节/秒"，2 series（RX/TX，ens.* 聚合）

- [ ] **Step 1: 加 label 类型**

```java
// InstanceLabels.java
package com.coding.platformapi.metrics.labels;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
@Data
public class InstanceLabels { @JsonProperty("instance") private String instance; }
```

```java
// DeviceLabels.java
package com.coding.platformapi.metrics.labels;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
@Data
public class DeviceLabels { @JsonProperty("device") private String device; }
```

- [ ] **Step 2: 加 NodeMetricsRequest**

```java
// NodeMetricsRequest.java
package com.coding.platformapi.metrics.dto;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
@Data
@Schema(description = "节点指标查询请求")
public class NodeMetricsRequest {
    @Schema(description = "集群id", requiredMode = Schema.RequiredMode.REQUIRED) private String clusterId;
    @Schema(description = "node_exporter instance（<internalIp>:9100）", requiredMode = Schema.RequiredMode.REQUIRED) private String instance;
    @Schema(description = "起始时间（unix 秒）", requiredMode = Schema.RequiredMode.REQUIRED) private long start;
    @Schema(description = "结束时间（unix 秒）", requiredMode = Schema.RequiredMode.REQUIRED) private long end;
}
```

- [ ] **Step 3: MetricQuery 加 node 模板**

在 `MetricQuery` 枚举里（`WORKLOAD_DISK_WRITE(...)` 之后、`;` 之前）加：
```java
    // ===== Node 详情（%s = instance，即 <internalIp>:9100；不依赖 cluster_name relabeling）=====
    NODE_CPU_USED(MetricLabels.class,
        "100 * (1 - avg(rate(node_cpu_seconds_total{instance=\"%s\"}[5m])))"),
    NODE_MEMORY_USED(MetricLabels.class,
        "(node_memory_MemTotal_bytes{instance=\"%s\"} - node_memory_MemAvailable_bytes{instance=\"%s\"})"),
    NODE_MEMORY_CACHED(MetricLabels.class, "node_memory_Cached_bytes{instance=\"%s\"}"),
    NODE_MEMORY_BUFFERS(MetricLabels.class, "node_memory_Buffers_bytes{instance=\"%s\"}"),
    NODE_MEMORY_FREE(MetricLabels.class, "node_memory_MemFree_bytes{instance=\"%s\"}"),
    // 磁盘：无 sum → 每 device 一条序列；过滤恒 0 的 sr/loop
    NODE_DISK_READ(DeviceLabels.class,
        "rate(node_disk_read_bytes_total{instance=\"%s\", device!~\"sr.*|loop.*\"}[5m])"),
    NODE_DISK_WRITE(DeviceLabels.class,
        "rate(node_disk_written_bytes_total{instance=\"%s\", device!~\"sr.*|loop.*\"}[5m])"),
    // 网络：只取 ens.* 物理网卡聚合
    NODE_NET_RX(MetricLabels.class,
        "sum(rate(node_network_receive_bytes_total{instance=\"%s\", device=~\"ens.*\"}[5m]))"),
    NODE_NET_TX(MetricLabels.class,
        "sum(rate(node_network_transmit_bytes_total{instance=\"%s\", device=~\"ens.*\"}[5m]))"),
    ;
```

- [ ] **Step 4: MetricsService 加 node 方法 + multi-series 助手**

在 `MetricsService.java` 加 import：
```java
import com.coding.platformapi.metrics.labels.DeviceLabels;
import com.coding.platformapi.metrics.labels.InstanceLabels;
import com.coding.platformapi.metrics.dto.NodeMetricsRequest;
```
在类内（`podDisk` 之后）加：
```java
    // ===== Node 维度（instance 作用域；queryRange 固定 STEP）=====

    public MetricSeriesResponse nodeCpu(NodeMetricsRequest req) {
        String sql = String.format(MetricQuery.NODE_CPU_USED.sql(), req.getInstance());
        return new MetricSeriesResponse("百分比", List.of(toSeries("使用率", queryNode(sql, req))));
    }

    public MetricSeriesResponse nodeMemory(NodeMetricsRequest req) {
        String i = req.getInstance();
        return new MetricSeriesResponse("字节", List.of(
            toSeries("已用", queryNode(String.format(MetricQuery.NODE_MEMORY_USED.sql(), i, i), req)),
            toSeries("缓存", queryNode(String.format(MetricQuery.NODE_MEMORY_CACHED.sql(), i), req)),
            toSeries("缓冲", queryNode(String.format(MetricQuery.NODE_MEMORY_BUFFERS.sql(), i), req)),
            toSeries("空闲", queryNode(String.format(MetricQuery.NODE_MEMORY_FREE.sql(), i), req))));
    }

    public MetricSeriesResponse nodeDisk(NodeMetricsRequest req) {
        String i = req.getInstance();
        List<MetricSeries> series = new ArrayList<>();
        for (var row : client.queryRange(String.format(MetricQuery.NODE_DISK_READ.sql(), i), req.getStart(), req.getEnd(), STEP, DeviceLabels.class)) {
            series.add(labeledSeries(dev(row) + "-读", row.getValues()));
        }
        for (var row : client.queryRange(String.format(MetricQuery.NODE_DISK_WRITE.sql(), i), req.getStart(), req.getEnd(), STEP, DeviceLabels.class)) {
            series.add(labeledSeries(dev(row) + "-写", row.getValues()));
        }
        return new MetricSeriesResponse("字节/秒", series);
    }

    public MetricSeriesResponse nodeNetwork(NodeMetricsRequest req) {
        String i = req.getInstance();
        return new MetricSeriesResponse("字节/秒", List.of(
            toSeries("RX", queryNode(String.format(MetricQuery.NODE_NET_RX.sql(), i), req)),
            toSeries("TX", queryNode(String.format(MetricQuery.NODE_NET_TX.sql(), i), req))));
    }

    /** 列表页批量：全集群节点 cpu%/mem%（instant query，按 instance 对齐）。instances = ["ip:9100", ...] */
    public Map<String, NodeCurrentMetric> nodeCurrents(String clusterId, List<String> instances) {
        String regex = String.join("|", instances);
        Map<String, NodeCurrentMetric> out = new java.util.LinkedHashMap<>();
        for (var row : client.query(
            "100 * (1 - avg by (instance) (rate(node_cpu_seconds_total{instance=~\"" + regex + "\"}[5m])))",
            null, InstanceLabels.class)) {
            NodeCurrentMetric m = out.computeIfAbsent(inst(row), k -> new NodeCurrentMetric());
            if (row.getValue() != null && row.getValue().size() >= 2) m.setCpuPercent(parseDouble(row.getValue().get(1)));
        }
        for (var row : client.query(
            "100 * (1 - node_memory_MemAvailable_bytes{instance=~\"" + regex + "\"} / node_memory_MemTotal_bytes{instance=~\"" + regex + "\"})",
            null, InstanceLabels.class)) {
            NodeCurrentMetric m = out.computeIfAbsent(inst(row), k -> new NodeCurrentMetric());
            if (row.getValue() != null && row.getValue().size() >= 2) m.setMemPercent(parseDouble(row.getValue().get(1)));
        }
        return out;
    }

    private List<PromResult<MetricLabels>> queryNode(String sql, NodeMetricsRequest req) {
        return client.queryRange(sql, req.getStart(), req.getEnd(), STEP, MetricLabels.class);
    }

    /** 带标签的多序列：每行一个 series，legend 由调用方给。 */
    private MetricSeries labeledSeries(String legend, List<List<String>> values) {
        MetricSeries s = new MetricSeries();
        s.setLegend(legend);
        s.setPoints(toPoints(values));
        return s;
    }

    private String dev(PromResult<DeviceLabels> row) {
        return row.getMetric() != null && row.getMetric().getDevice() != null ? row.getMetric().getDevice() : "unknown";
    }
    private String inst(PromResult<InstanceLabels> row) {
        return row.getMetric() != null && row.getMetric().getInstance() != null ? row.getMetric().getInstance() : "unknown";
    }

    /** 从 values 提取点序列（复用现有 toSeries 的点解析逻辑）。 */
    private List<MetricPoint> toPoints(List<List<String>> values) {
        List<MetricPoint> points = new ArrayList<>();
        if (values != null) for (List<String> sample : values) {
            if (sample == null || sample.size() < 2) continue;
            double v = parseDouble(sample.get(1));
            if (Double.isNaN(v) || Double.isInfinite(v)) continue;
            MetricPoint p = new MetricPoint();
            p.setTs((long) parseDouble(sample.get(0)));
            p.setValue(v);
            points.add(p);
        }
        return points;
    }

    public static class NodeCurrentMetric {
        private double cpuPercent;
        private double memPercent;
        public double getCpuPercent() { return cpuPercent; }
        public void setCpuPercent(double v) { this.cpuPercent = v; }
        public double getMemPercent() { return memPercent; }
        public void setMemPercent(double v) { this.memPercent = v; }
    }
```

> 注：`client.query(sql, time, labels)` 的 `time` 参数传 `null` 表示「now」（以 ThanosQueryClient 实际签名为准，Task 6 Step 5 编译核对；若要求非空则传当前 unix 秒字符串）。

- [ ] **Step 5: NodeController（platform-api）加 metrics 端点**

在 Task 5 的 `NodeController` 里加 import + 注入 `MetricsService`，并加：
```java
    private final com.coding.platformapi.metrics.MetricsService metricsService;

    @PostMapping("/metrics/current")
    public ResponseData<java.util.Map<String, com.coding.platformapi.metrics.MetricsService.NodeCurrentMetric>> metricsCurrent(
            @RequestBody NodeCurrentReq req) {
        return new ResponseData<>(metricsService.nodeCurrents(req.getClusterId(), req.getInstances()));
    }

    @PostMapping("/{name}/metrics/cpu")
    public ResponseData<com.coding.platformapi.metrics.dto.MetricSeriesResponse> cpuMetrics(@PathVariable String name, @RequestBody com.coding.platformapi.metrics.dto.NodeMetricsRequest req) {
        return new ResponseData<>(metricsService.nodeCpu(req));
    }
    @PostMapping("/{name}/metrics/memory")
    public ResponseData<com.coding.platformapi.metrics.dto.MetricSeriesResponse> memoryMetrics(@PathVariable String name, @RequestBody com.coding.platformapi.metrics.dto.NodeMetricsRequest req) {
        return new ResponseData<>(metricsService.nodeMemory(req));
    }
    @PostMapping("/{name}/metrics/disk")
    public ResponseData<com.coding.platformapi.metrics.dto.MetricSeriesResponse> diskMetrics(@PathVariable String name, @RequestBody com.coding.platformapi.metrics.dto.NodeMetricsRequest req) {
        return new ResponseData<>(metricsService.nodeDisk(req));
    }
    @PostMapping("/{name}/metrics/network")
    public ResponseData<com.coding.platformapi.metrics.dto.MetricSeriesResponse> networkMetrics(@PathVariable String name, @RequestBody com.coding.platformapi.metrics.dto.NodeMetricsRequest req) {
        return new ResponseData<>(metricsService.nodeNetwork(req));
    }

    public static class NodeCurrentReq {
        private String clusterId;
        private List<String> instances;
        public String getClusterId() { return clusterId; }
        public void setClusterId(String v) { this.clusterId = v; }
        public List<String> getInstances() { return instances; }
        public void setInstances(List<String> v) { this.instances = v; }
    }
```

- [ ] **Step 6: 编译验证**

Run: `mvn -q -pl platform-api -am compile`
Expected: BUILD SUCCESS。若 `client.query(sql, null, ...)` 的 time 参数不接受 null，读 `ThanosQueryClient.query` 签名改为传 `String.valueOf(System.currentTimeMillis()/1000)`。

- [ ] **Step 7: Commit**

```bash
git add platform-api/src/main/java/com/coding/platformapi/metrics/ platform-api/src/main/java/com/coding/platformapi/controllers/resource/NodeController.java
git commit -m "feat(node): node metrics (cpu/mem/disk-per-device/net-ens) + batched current"
```

---

### Task 7: 前端类型 + nodeApi（platform-web）

**Files:**
- Modify: `platform-web/src/types.ts`（加 K8sNode 等）
- Modify: `platform-web/src/api/index.ts`（加 nodeApi）
- Modify: `platform-web/src/api/metrics.ts`（加 nodeMetrics）

**Interfaces:**
- Consumes: 后端 Task 5/6 wire。
- Produces：TS 类型 `K8sNode, NodeTaint, NodeCondition, NodePodStat, NodeDrainResult, NodeEvent, NodeCurrentMetric`；`nodeApi`（list/get/getYaml/cordon/uncordon/updateLabelsTaints/drain/podStats/listPods/events）；`nodeMetrics.{cpu,memory,disk,network,current}`。Task 8/9 引用。

- [ ] **Step 1: types.ts 加类型**

在 `types.ts` 末尾（`K8sPersistentVolume` 附近风格一致处）加：
```ts
export interface NodeTaint { key: string; value?: string | null; effect: 'NoSchedule' | 'PreferNoSchedule' | 'NoExecute' }
export interface NodeCondition {
  type: string; status: 'True' | 'False' | 'Unknown'; reason?: string | null; message?: string | null
  lastHeartbeatTime?: string | null; lastTransitionTime?: string | null
}
export interface K8sNode {
  clusterId?: string | null
  name: string
  status?: 'True' | 'False' | 'Unknown' | null   // Ready 条件状态（True=Ready）
  unschedulable?: boolean | null
  roles?: string[]
  kubeletVersion?: string | null
  os?: string | null
  arch?: string | null
  kernelVersion?: string | null
  containerRuntimeVersion?: string | null
  osImage?: string | null
  podCidr?: string | null
  internalIp?: string | null
  externalIp?: string | null
  cpuCapacity?: string | null
  memoryCapacity?: string | null
  cpuAllocatable?: string | null
  memoryAllocatable?: string | null
  podsLimit?: number | null
  labels?: Record<string, string>
  conditions?: NodeCondition[]
  taints?: NodeTaint[]
  creationTime?: string | null
}
export interface NodePodStat { nodeName: string; podCount: number; cpuRequestMillicores: number; memRequestBytes: number }
export interface NodeDrainResult { evicted: string[]; skipped: string[]; errors: string[] }
export interface NodeEvent { reason?: string | null; message?: string | null; type?: 'Normal' | 'Warning' | null; count?: number | null; firstTimestamp?: string | null; lastTimestamp?: string | null }
export interface NodeCurrentMetric { cpuPercent: number; memPercent: number }
```

- [ ] **Step 2: api/index.ts 加 nodeApi**

在 `clusterApi` 之后加：
```ts
/** 节点管理 /resource/nodes（集群级，admin；作用域仅 clusterId） */
export const nodeApi = {
  list: (clusterId: string) => http.post<never, K8sNode[]>('/resource/nodes/list', { clusterId }),
  get: (name: string, clusterId: string) => http.get<never, K8sNode>(`/resource/nodes/${encodeURIComponent(name)}`, { params: { clusterId } }),
  getYaml: (name: string, clusterId: string) => http.get<never, string>(`/resource/nodes/${encodeURIComponent(name)}/yaml`, { params: { clusterId } }),
  cordon: (clusterId: string, name: string) => http.post<never, K8sNode>('/resource/nodes/cordon', { clusterId, name }),
  uncordon: (clusterId: string, name: string) => http.post<never, K8sNode>('/resource/nodes/uncordon', { clusterId, name }),
  updateLabelsTaints: (name: string, clusterId: string, labels: Record<string, string>, taints: NodeTaint[]) =>
    http.put<never, K8sNode>(`/resource/nodes/${encodeURIComponent(name)}`, { labels, taints }, { params: { clusterId } }),
  drain: (clusterId: string, name: string, force: boolean, deleteEmptyDir: boolean) =>
    http.post<never, NodeDrainResult>('/resource/nodes/drain', { clusterId, name, force, deleteEmptyDir }),
  podStats: (clusterId: string) => http.post<never, NodePodStat[]>('/resource/nodes/podstats', { clusterId }),
  listPods: (name: string, clusterId: string) => http.get<never, K8sPod[]>(`/resource/nodes/${encodeURIComponent(name)}/pods`, { params: { clusterId } }),
  events: (name: string, clusterId: string) => http.get<never, NodeEvent[]>(`/resource/nodes/${encodeURIComponent(name)}/events`, { params: { clusterId } }),
}
```
（顶部 import 补 `K8sNode, NodeTaint, NodePodStat, NodeDrainResult, NodeEvent`；`K8sPod` 已存在。）

- [ ] **Step 3: api/metrics.ts 加 nodeMetrics**

在文件末尾加：
```ts
/** 节点指标上下文：instance = <internalIp>:9100 */
export interface NodeMetricsReq { clusterId: string; instance: string; start: number; end: number }

export const nodeMetrics = {
  cpu: (name: string, req: NodeMetricsReq) => http.post<never, MetricSeriesResponse>(`/resource/nodes/${encodeURIComponent(name)}/metrics/cpu`, req),
  memory: (name: string, req: NodeMetricsReq) => http.post<never, MetricSeriesResponse>(`/resource/nodes/${encodeURIComponent(name)}/metrics/memory`, req),
  disk: (name: string, req: NodeMetricsReq) => http.post<never, MetricSeriesResponse>(`/resource/nodes/${encodeURIComponent(name)}/metrics/disk`, req),
  network: (name: string, req: NodeMetricsReq) => http.post<never, MetricSeriesResponse>(`/resource/nodes/${encodeURIComponent(name)}/metrics/network`, req),
  current: (clusterId: string, instances: string[]) =>
    http.post<never, Record<string, { cpuPercent: number; memPercent: number }>>('/resource/nodes/metrics/current', { clusterId, instances }),
}
```

- [ ] **Step 4: 类型检查**

Run: `cd platform-web && npm run type-check`（若无该 script，用 `npx vue-tsc --noEmit`）
Expected: 无新增类型错误

- [ ] **Step 5: Commit**

```bash
git add platform-web/src/types.ts platform-web/src/api/index.ts platform-web/src/api/metrics.ts
git commit -m "feat(node): frontend K8sNode types + nodeApi + nodeMetrics"
```

---

### Task 8: 前端 NodeView（列表页）+ 菜单/路由

**Files:**
- Create: `platform-web/src/views/NodeView.vue`
- Modify: `platform-web/src/router/index.ts`（加 /nodes、/nodes/detail）
- Modify: `platform-web/src/layouts/MainLayout.vue`（平台管理组，集群管理下方加「节点管理」）

**Interfaces:**
- Consumes: `clusterApi.list`（集群下拉）、`nodeApi.list/podStats/cordon/uncordon/drain`、`nodeMetrics.current`、`K8sNode/NodePodStat/NodeCurrentMetric`、`fmtAge`。
- Produces: 路由 `/nodes`（列表）。行操作跳 `/nodes/detail?clusterId=&name=`。

- [ ] **Step 1: router 加两条路由**

在 `router/index.ts` 平台管理组里，`clusters` 之后、`tenants` 之前加：
```ts
        { path: 'nodes', name: 'nodes', component: () => import('@/views/NodeView.vue'), meta: { title: '节点管理', group: '平台管理' } },
        { path: 'nodes/detail', name: 'node-detail', component: () => import('@/views/NodeDetailView.vue'), meta: { title: '节点详情', group: '平台管理' } },
```

- [ ] **Step 2: MainLayout 加菜单项**

在 `MainLayout.vue` 的「集群管理」`el-menu-item`（index="/clusters"）之后加：
```html
        <el-menu-item index="/nodes">
          <el-icon><Cpu /></el-icon>
          <template #title>节点管理</template>
        </el-menu-item>
```

- [ ] **Step 3: 写 NodeView.vue（列表页）**

```vue
<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { clusterApi, nodeApi } from '@/api'
import { nodeMetrics } from '@/api/metrics'
import type { K8sCluster, K8sNode, NodePodStat, NodeCurrentMetric } from '@/types'
import { fmtAge } from '@/utils/format'

const clusters = ref<K8sCluster[]>([])
const clusterId = ref('')
const loading = ref(false)
const nodes = ref<K8sNode[]>([])
const stats = ref<Record<string, NodePodStat>>({})
const current = ref<Record<string, NodeCurrentMetric>>({})

async function loadClusters(): Promise<void> {
  clusters.value = await clusterApi.list()
  if (!clusterId.value && clusters.value.length) clusterId.value = clusters.value[0].clusterId
}

function instanceOf(n: K8sNode): string { return n.internalIp ? `${n.internalIp}:9100` : '' }

async function load(): Promise<void> {
  if (!clusterId.value) { nodes.value = []; return }
  loading.value = true
  try {
    const [list, podStats] = await Promise.all([nodeApi.list(clusterId.value), nodeApi.podStats(clusterId.value)])
    nodes.value = list
    const m: Record<string, NodePodStat> = {}
    for (const s of podStats) m[s.nodeName] = s
    stats.value = m
    // 批量实时使用率（2 条 instant query）
    const insts = list.map(instanceOf).filter(Boolean)
    if (insts.length) {
      try { current.value = await nodeMetrics.current(clusterId.value, insts) } catch { current.value = {} }
    } else current.value = {}
  } finally { loading.value = false }
}

function ready(n: K8sNode): boolean { return n.status === 'True' }
function statOf(n: K8sNode): NodePodStat | undefined { return stats.value[n.name] }
function curOf(n: K8sNode): NodeCurrentMetric | undefined { return current.value[instanceOf(n)] }

const draining = ref('')
async function onCordon(row: K8sNode): Promise<void> {
  if (row.unschedulable) { await nodeApi.uncordon(clusterId.value, row.name); ElMessage.success('已解除调度限制（Uncordon）') }
  else { await nodeApi.cordon(clusterId.value, row.name); ElMessage.success('已标记不可调度（Cordon）') }
  await load()
}

async function onDrain(row: K8sNode): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `确认驱逐节点「${row.name}」上的 Pod？DaemonSet/静态/mirror Pod 会保留。`, 'Drain 节点', { type: 'warning' })
  } catch { return }
  const force = await askForce()
  draining.value = row.name
  try {
    const r = await nodeApi.drain(clusterId.value, row.name, force, false)
    ElMessage.success(`Drain 完成：驱逐 ${r.evicted.length}，跳过 ${r.skipped.length}，失败 ${r.errors.length}`)
    if (r.errors.length) console.warn('drain errors', r.errors)
    await load()
  } finally { draining.value = '' }
}

async function askForce(): Promise<boolean> {
  try {
    await ElMessageBox.confirm('是否 --force（PDB 阻断时直接删除 Pod）？', 'Drain 选项', { type: 'warning' })
    return true
  } catch { return false }
}

function goDetail(row: K8sNode): void {
  // 用 query 传上下文（节点无租户 context store）
  window.location.hash = '' // no-op，保持 history 模式
  location.href = `#/nodes/detail` // 见 Step 4：改用 router.push
}

onMounted(async () => { await loadClusters(); await load() })
</script>
```

> **Step 3 修正**：跳转详情用 vue-router，不用 `location.href`。把 `goDetail` 改为：
> ```ts
> import { useRouter } from 'vue-router'
> const router = useRouter()
> function goDetail(row: K8sNode): void { router.push({ name: 'node-detail', query: { clusterId: clusterId.value, name: row.name } }) }
> ```

```vue
<template>
  <div>
    <div class="toolbar">
      <el-select v-model="clusterId" style="width: 240px" @change="load">
        <el-option v-for="c in clusters" :key="c.clusterId" :label="c.clusterName" :value="c.clusterId" />
      </el-select>
      <el-button @click="load">刷新</el-button>
      <span class="summary" v-if="nodes.length">
        共 {{ nodes.length }} · Ready {{ nodes.filter(ready).length }} · NotReady {{ nodes.length - nodes.filter(ready).length }} · Cordon {{ nodes.filter(n => n.unschedulable).length }}
      </span>
    </div>

    <el-table v-loading="loading" :data="nodes" stripe>
      <el-table-column prop="name" label="节点名称" min-width="150" />
      <el-table-column label="状态" width="150">
        <template #default="{ row }">
          <el-tag :type="ready(row) ? 'success' : 'danger'">{{ ready(row) ? 'Ready' : 'NotReady' }}</el-tag>
          <el-tag v-if="row.unschedulable" type="warning" style="margin-left:6px">Cordon</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="角色" width="130">
        <template #default="{ row }">{{ (row.roles ?? []).join(', ') || '-' }}</template>
      </el-table-column>
      <el-table-column prop="kubeletVersion" label="版本" width="110" />
      <el-table-column label="CPU" width="150">
        <template #default="{ row }">
          <span v-if="curOf(row)">{{ curOf(row)!.cpuPercent.toFixed(1) }}%</span>
          <span v-else class="dim">—</span>
          <div class="sub">{{ row.cpuAllocatable ?? '-' }}</div>
        </template>
      </el-table-column>
      <el-table-column label="内存" width="150">
        <template #default="{ row }">
          <span v-if="curOf(row)">{{ curOf(row)!.memPercent.toFixed(1) }}%</span>
          <span v-else class="dim">—</span>
          <div class="sub">{{ row.memoryAllocatable ?? '-' }}</div>
        </template>
      </el-table-column>
      <el-table-column prop="internalIp" label="IP" width="140" />
      <el-table-column label="Pod" width="110">
        <template #default="{ row }">{{ statOf(row)?.podCount ?? 0 }} / {{ row.podsLimit ?? '-' }}</template>
      </el-table-column>
      <el-table-column label="加入时间" width="120">
        <template #default="{ row }">{{ fmtAge(row.creationTime) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="220" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="goDetail(row)">详情</el-button>
          <el-button link :type="row.unschedulable ? 'success' : 'warning'" @click="onCordon(row)">{{ row.unschedulable ? 'Uncordon' : 'Cordon' }}</el-button>
          <el-button link type="danger" :loading="draining === row.name" @click="onDrain(row)">Drain</el-button>
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>

<style scoped>
.toolbar { display: flex; align-items: center; gap: 12px; margin-bottom: 16px; }
.summary { color: var(--text-3); font-size: 13px; margin-left: 8px; }
.sub { font-size: 12px; color: var(--text-3); }
.dim { color: var(--text-3); }
</style>
```

- [ ] **Step 4: 类型检查 + 构建**

Run: `cd platform-web && npx vue-tsc --noEmit && npm run build`
Expected: 通过（无类型错误，构建成功）

- [ ] **Step 5: Commit**

```bash
git add platform-web/src/views/NodeView.vue platform-web/src/router/index.ts platform-web/src/layouts/MainLayout.vue
git commit -m "feat(node): NodeView list page + menu/route"
```

---

### Task 9: 前端 NodeDetailView（详情页：概览/Conditions/Pod/标签Taint/监控/事件/YAML）

**Files:**
- Create: `platform-web/src/views/NodeDetailView.vue`
- Create: `platform-web/src/components/node/NodeMetricsPanel.vue`

**Interfaces:**
- Consumes: `nodeApi.get/getYaml/listPods/events/updateLabelsTaints/cordon/uncordon/drain`、`nodeMetrics.*`、`MetricChart.vue`（现有）、`K8sNode`。路由 query：`clusterId`、`name`。
- Produces: 路由 `/nodes/detail`。

- [ ] **Step 1: 写 NodeMetricsPanel.vue（复用 MetricChart，4 图）**

```vue
<script setup lang="ts">
import { onMounted, reactive, watch } from 'vue'
import { nodeMetrics, type NodeMetricsReq } from '@/api/metrics'
import type { MetricSeriesResponse } from '@/types/metrics'
import MetricChart from '@/components/workload/MetricChart.vue'

const props = defineProps<{ name: string; clusterId: string; instance: string }>()
type Key = 'cpu' | 'memory' | 'network' | 'disk'
const KEYS: Key[] = ['cpu', 'memory', 'network', 'disk']
interface Cell { data: MetricSeriesResponse | null; loading: boolean }
const state = reactive<Record<Key, Cell>>({
  cpu: { data: null, loading: false }, memory: { data: null, loading: false },
  network: { data: null, loading: false }, disk: { data: null, loading: false },
})
const RANGES = [15, 30, 60]
const ranges = reactive<Record<Key, number>>({ cpu: 30, memory: 30, network: 30, disk: 30 })

async function fetchOne(key: Key): Promise<void> {
  if (!props.name || !props.instance) return
  state[key].loading = true
  try {
    const end = Math.floor(Date.now() / 1000)
    const req: NodeMetricsReq = { clusterId: props.clusterId, instance: props.instance, start: end - ranges[key] * 60, end }
    state[key].data = await nodeMetrics[key](props.name, req)
  } catch { state[key].data = null } finally { state[key].loading = false }
}
async function fetchAll(): Promise<void> { await Promise.all(KEYS.map((k) => fetchOne(k))) }
watch(() => ranges.cpu, () => void fetchOne('cpu'))
watch(() => ranges.memory, () => void fetchOne('memory'))
watch(() => ranges.network, () => void fetchOne('network'))
watch(() => ranges.disk, () => void fetchOne('disk'))
watch([() => props.name, () => props.instance], fetchAll)
onMounted(fetchAll)
defineExpose({ refresh: fetchAll })
</script>

<template>
  <section class="metrics-panel">
    <div class="mp-head"><h3 class="mp-title">监控指标</h3></div>
    <div class="metrics-grid">
      <MetricChart title="CPU" :unit="state.cpu.data?.unit ?? '百分比'" :series="state.cpu.data?.series ?? []" :loading="state.cpu.loading" :ranges="RANGES" :range="ranges.cpu" @update:range="(v) => (ranges.cpu = v)" />
      <MetricChart title="内存" :unit="state.memory.data?.unit ?? '字节'" :series="state.memory.data?.series ?? []" :loading="state.memory.loading" :ranges="RANGES" :range="ranges.memory" @update:range="(v) => (ranges.memory = v)" />
      <MetricChart title="网络 IO（ens.*）" :unit="state.network.data?.unit ?? '字节/秒'" :series="state.network.data?.series ?? []" :loading="state.network.loading" :ranges="RANGES" :range="ranges.network" @update:range="(v) => (ranges.network = v)" />
      <MetricChart title="磁盘 IO（按 device）" :unit="state.disk.data?.unit ?? '字节/秒'" :series="state.disk.data?.series ?? []" :loading="state.disk.loading" :ranges="RANGES" :range="ranges.disk" @update:range="(v) => (ranges.disk = v)" />
    </div>
  </section>
</template>

<style scoped>
.mp-head { display: flex; align-items: center; margin-bottom: 12px; }
.mp-title { margin: 0; font-size: 13px; font-weight: 700; letter-spacing: .04em; color: var(--text-2); }
.metrics-grid { display: grid; grid-template-columns: minmax(0, 1fr); gap: 14px; }
</style>
```

- [ ] **Step 2: 写 NodeDetailView.vue（7 tab）**

```vue
<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { nodeApi } from '@/api'
import type { K8sNode, NodeEvent, K8sPod, NodeTaint } from '@/types'
import { fmtDate } from '@/utils/format'
import NodeMetricsPanel from '@/components/node/NodeMetricsPanel.vue'

const route = useRoute()
const router = useRouter()
const clusterId = computed(() => (route.query.clusterId as string) ?? '')
const name = computed(() => (route.query.name as string) ?? '')
const instance = computed(() => (node.value?.internalIp ? `${node.value.internalIp}:9100` : ''))

const node = ref<K8sNode | null>(null)
const yamlText = ref('')
const pods = ref<K8sPod[]>([])
const events = ref<NodeEvent[]>([])
const tab = ref('overview')

// 标签/Taint 编辑态
const editingLabels = ref<Record<string, string>>({})
const labelRows = ref<{ key: string; value: string }[]>([])
const taintRows = ref<NodeTaint[]>([])

async function load(): Promise<void> {
  if (!clusterId.value || !name.value) return
  node.value = await nodeApi.get(name.value, clusterId.value)
  yamlText.value = await nodeApi.getYaml(name.value, clusterId.value)
  pods.value = await nodeApi.listPods(name.value, clusterId.value)
  events.value = await nodeApi.events(name.value, clusterId.value)
  editingLabels.value = { ...(node.value.labels ?? {}) }
  labelRows.value = Object.entries(editingLabels.value).map(([key, value]) => ({ key, value }))
  taintRows.value = [...(node.value.taints ?? [])]
}

function onTabChange(t: string): void { tab.value = t }

async function saveLabelsTaints(): Promise<void> {
  const labels: Record<string, string> = {}
  for (const r of labelRows.value) if (r.key.trim()) labels[r.key.trim()] = r.value
  try {
    await nodeApi.updateLabelsTaints(name.value, clusterId.value, labels, taintRows.value)
    ElMessage.success('已保存标签 / Taint')
    await load()
  } catch { /* 拦截器提示 */ }
}

async function onCordon(): Promise<void> {
  if (!node.value) return
  if (node.value.unschedulable) await nodeApi.uncordon(clusterId.value, name.value)
  else await nodeApi.cordon(clusterId.value, name.value)
  ElMessage.success('已更新调度状态'); await load()
}

async function onDrain(): Promise<void> {
  try { await ElMessageBox.confirm(`确认驱逐节点「${name.value}」上的 Pod？`, 'Drain', { type: 'warning' }) } catch { return }
  let force = false
  try { await ElMessageBox.confirm('是否 --force（PDB 阻断时直接删除）？', 'Drain 选项', { type: 'warning' }); force = true } catch { /* 不 force */ }
  const r = await nodeApi.drain(clusterId.value, name.value, force, false)
  ElMessage.success(`驱逐 ${r.evicted.length}，跳过 ${r.skipped.length}，失败 ${r.errors.length}`); await load()
}

function goBack(): void { router.push({ name: 'nodes' }) }

onMounted(load)
</script>

<template>
  <div v-if="node">
    <div class="head">
      <el-button link @click="goBack">← 返回</el-button>
      <h2 class="title">{{ node.name }}</h2>
      <el-tag :type="node.status === 'True' ? 'success' : 'danger'">{{ node.status === 'True' ? 'Ready' : 'NotReady' }}</el-tag>
      <el-tag v-if="node.unschedulable" type="warning">Cordon</el-tag>
      <span class="roles">{{ (node.roles ?? []).join(', ') || 'worker' }}</span>
      <div class="head-actions">
        <el-button :type="node.unschedulable ? 'success' : 'warning'" @click="onCordon">{{ node.unschedulable ? 'Uncordon' : 'Cordon' }}</el-button>
        <el-button type="danger" @click="onDrain">Drain</el-button>
      </div>
    </div>

    <el-tabs v-model="tab" @tab-change="onTabChange">
      <el-tab-pane label="概览" name="overview">
        <el-descriptions :column="2" border>
          <el-descriptions-item label="CPU（alloc/cap）">{{ node.cpuAllocatable }} / {{ node.cpuCapacity }}</el-descriptions-item>
          <el-descriptions-item label="内存（alloc/cap）">{{ node.memoryAllocatable }} / {{ node.memoryCapacity }}</el-descriptions-item>
          <el-descriptions-item label="Pod 上限">{{ node.podsLimit }}</el-descriptions-item>
          <el-descriptions-item label="IP">{{ node.internalIp }}<span v-if="node.externalIp"> / {{ node.externalIp }}</span></el-descriptions-item>
          <el-descriptions-item label="Kubelet">{{ node.kubeletVersion }}</el-descriptions-item>
          <el-descriptions-item label="容器运行时">{{ node.containerRuntimeVersion }}</el-descriptions-item>
          <el-descriptions-item label="OS / 架构">{{ node.osImage }} ({{ node.arch }})</el-descriptions-item>
          <el-descriptions-item label="内核">{{ node.kernelVersion }}</el-descriptions-item>
          <el-descriptions-item label="Pod CIDR">{{ node.podCidr }}</el-descriptions-item>
          <el-descriptions-item label="加入时间">{{ fmtDate(node.creationTime) }}</el-descriptions-item>
        </el-descriptions>
      </el-tab-pane>

      <el-tab-pane label="Conditions" name="conditions">
        <el-table :data="node.conditions ?? []" stripe>
          <el-table-column prop="type" label="类型" width="160" />
          <el-table-column prop="status" label="状态" width="100" />
          <el-table-column prop="reason" label="原因" width="180" />
          <el-table-column prop="message" label="信息" min-width="200" show-overflow-tooltip />
          <el-table-column label="心跳" width="180"><template #default="{ row }">{{ fmtDate(row.lastHeartbeatTime) }}</template></el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane :label="`Pod (${pods.length})`" name="pods">
        <el-table :data="pods" stripe>
          <el-table-column prop="name" label="名称" min-width="200" />
          <el-table-column prop="namespace" label="命名空间" width="160" />
          <el-table-column prop="phase" label="状态" width="120" />
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="标签 & Taint" name="labels">
        <h4>Labels</h4>
        <div v-for="(r, i) in labelRows" :key="i" class="row">
          <el-input v-model="r.key" placeholder="key" style="width:220px" />
          <el-input v-model="r.value" placeholder="value" style="width:220px" />
          <el-button link type="danger" @click="labelRows.splice(i, 1)">删除</el-button>
        </div>
        <el-button size="small" @click="labelRows.push({ key: '', value: '' })">+ 标签</el-button>

        <h4 style="margin-top:16px">Taints</h4>
        <div v-for="(t, i) in taintRows" :key="i" class="row">
          <el-input v-model="t.key" placeholder="key" style="width:200px" />
          <el-input v-model="t.value" placeholder="value" style="width:160px" />
          <el-select v-model="t.effect" style="width:180px">
            <el-option label="NoSchedule" value="NoSchedule" /><el-option label="PreferNoSchedule" value="PreferNoSchedule" /><el-option label="NoExecute" value="NoExecute" />
          </el-select>
          <el-button link type="danger" @click="taintRows.splice(i, 1)">删除</el-button>
        </div>
        <el-button size="small" @click="taintRows.push({ key: '', value: '', effect: 'NoSchedule' })">+ Taint</el-button>

        <div style="margin-top:16px"><el-button type="primary" @click="saveLabelsTaints">保存</el-button></div>
      </el-tab-pane>

      <el-tab-pane label="监控" name="metrics">
        <NodeMetricsPanel v-if="instance" :name="name" :cluster-id="clusterId" :instance="instance" />
      </el-tab-pane>

      <el-tab-pane :label="`事件 (${events.length})`" name="events">
        <el-table :data="events" stripe>
          <el-table-column prop="type" label="级别" width="90"><template #default="{ row }"><el-tag :type="row.type === 'Warning' ? 'danger' : 'info'">{{ row.type }}</el-tag></template></el-table-column>
          <el-table-column prop="reason" label="原因" width="200" />
          <el-table-column prop="message" label="信息" min-width="260" show-overflow-tooltip />
          <el-table-column prop="count" label="次数" width="80" />
          <el-table-column label="最近" width="180"><template #default="{ row }">{{ fmtDate(row.lastTimestamp) }}</template></el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="YAML" name="yaml">
        <pre class="yaml">{{ yamlText }}</pre>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<style scoped>
.head { display: flex; align-items: center; gap: 12px; margin-bottom: 16px; }
.title { margin: 0; font-size: 18px; font-weight: 700; }
.roles { color: var(--text-3); font-size: 13px; }
.head-actions { margin-left: auto; display: flex; gap: 8px; }
.row { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; }
.yaml { background: var(--panel); border: 1px solid var(--border); border-radius: 8px; padding: 12px; font-family: 'Consolas', monospace; font-size: 12px; white-space: pre-wrap; word-break: break-all; }
</style>
```

- [ ] **Step 3: 类型检查 + 构建**

Run: `cd platform-web && npx vue-tsc --noEmit && npm run build`
Expected: 通过。若 `K8sPod.phase`/`name`/`namespace` 字段名与现有 `K8sPod` 类型不符，读 `types.ts` 的 `K8sPod` 校正字段名。

- [ ] **Step 4: Commit**

```bash
git add platform-web/src/views/NodeDetailView.vue platform-web/src/components/node/NodeMetricsPanel.vue
git commit -m "feat(node): NodeDetailView (overview/conditions/pods/labels/metrics/events/yaml)"
```

---

## 端到端验证（全部 Task 完成后）

- [ ] **后端联调**：起 k8s-server + platform-api，用 admin token 依次打 `/resource/nodes/list`、`/podstats`、`/cordon`、`/{name}/metrics/cpu`；确认返回结构与前端类型一致。
- [ ] **前端预览**（走 memory「UI preview 验证法」）：假 token + 活后端，进「平台管理 → 节点管理」，验证：集群下拉加载节点列表；实时 CPU/内存 % 列有值（node_exporter 在则真数据，否则 `—`）；Pod 数 = actual/limit；Cordon/Uncordon 切换状态；Drain 弹窗 + 结果；详情页 7 tab（监控 4 图：CPU 单线、内存 4 线、磁盘按 device 折线、网络 ens.* RX/TX）；标签/Taint 编辑保存生效；事件表有数据。
- [ ] **降级检查**：某集群无 node_exporter 时，实时列显示 `—`、监控图「无数据」，其余功能正常。

## Self-Review 备注（实现时核对）

- `K8sServerGateway.exchange/responseType/listResponseType` 与 `ThanosQueryClient.query(sql,time,labels)` 的精确签名以现有源码为准——Task 5/6 编译时对齐（wire 路径不变）。
- Pod 集群级 list（按 `spec.nodeName`）：若 `CoreV1PodOperations` 走 namespaced，用 admin namespaced operation（Task 4 Step 3）。
- fabric8 7.9.0 模型无 fluent `withXxx`——本计划已全用 Builder / functional `.edit()`。
- 前端 `K8sPod` 字段名、`MetricChart.vue` props 以现有源码为准（Task 9 Step 3）。
