# 工作负载级日志（S2.1）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让「一个工作负载的日志」能一次看完 —— 勾选若干 Pod，合成一条带 `[pod/<pod>/<容器>]` 前缀的流；并补上 `previous`（上一次容器实例）以便 CrashLoopBackOff 能自助排障。

**Architecture:** 全部是**客户端扇出**，照抄 `kubectl logs -l … --all-containers --prefix`：k8s 核心只有 Pod 的 `logs` 子资源，没有工作负载级的（spec §0）。后端只改 k8s-server 两个既有日志端点（各加一个 `previous` 查询参数），**不新增端点、不新增权限行、不建迁移**；聚合由前端对既有单 Pod 端点多发几次完成。

**Tech Stack:** Spring Boot 4.1.1 / Java 21 / fabric8 7.6.1 · Vue 3 + TypeScript + Element Plus

**Spec:** `docs/superpowers/specs/2026-10-16-workload-logs-design.md`

## Global Constraints

- **本仓前端没有测试框架**（没有 vitest / jest / @vue/test-utils）—— 前端的验证一律走**浏览器冒烟台**：在 `platform-web/` 下放一对 `smoke-*.html` + `smoke-*.ts`，用 Vite dev server 起来，断言写在页面上并把结论 `console.log` 出来。**跑完即删**，不进仓库（本仓没有提交冒烟文件的先例）。
- **假 XHR 必须是属性回调**：axios 装的是 `onreadystatechange` / `onloadend` **属性**，不是 `addEventListener`。写成后者请求永远不回。
- **`:hover` 不认合成事件**：要验悬停/跟随这类行为就量几何，别 `dispatchEvent(new MouseEvent('mouseover'))`。
- **几何断言先钉"面板有真实高度"**：预览面板里元素可能塌成 0~5px，塌着量"贴底"会**无条件成立**（假绿）。
- **yaml/字段说明等给人看的纯文本里不许出现 markdown 的 `**`** —— 面板按字面渲染，星号会露给用户。
- **k8s 时间戳前缀只认 UTC 的 `Z`**：认 `+08:00` 会把应用自己打在行首的时间戳剥掉。
- **fabric8 里 `previous` 叫 `terminated()`**，且它返回 `TimeTailPrettyLoggable` —— **链上从此没有 `limitBytes`**。
- 后端测试命令带 `-Dsurefire.failIfNoSpecifiedTests=false`（`-am` 会连带跑兄弟模块）。
- 前端 type-check：`cd platform-web && NODE_OPTIONS=--max-old-space-size=4096 npm run type-check`。
- **工作区里有别的 session 在做 S1.1**（`platform-auth/**`、`ProfileView.vue`、`MainLayout.vue`、`router/index.ts`）—— 本计划一个都不碰；提交时**只 add 自己动过的文件**，别用 `git add -A`。

## Review Focus

以下五类输入/情形是 spec 蕴含、但主路径的测试不会自然覆盖的，最可能伤到用的人。
每一类都在它归属的任务里有对应的测试步骤（已标出）。

1. **多容器 Pod** —— 一个 Pod 里两个容器时，勾选表勾的是 Pod，而流里必须是**两条来源**、前缀能分开它们。（Task 5 / Task 6）
2. **跟着跟着某个 Pod 被删了**（滚动更新、手动删）—— 不能把整条流停掉或清空，其余来源要照常追加。（Task 6）
3. **日志行内容里自带制表符**（`ls -l`、表格化的应用日志）—— 不能被当成 k8s 前缀从中间劈开；更不能让那个"假时间戳"把游标带跑，否则之后的增量全乱。（Task 3）
4. **`previous` 撞上没有重启过的容器** —— 用户手点了开关时，要看到 k8s 的原话（「没有上一个终止的容器」），不是白屏或空面板。（Task 4）
5. **一轮里两个来源的时间戳乱序** —— 批内要排好序；**没有时间戳的行不许插到中间**（保持它们在批次末尾的原相对顺序）。（Task 3 / Task 6）

---

### Task 1: k8s-server · `PodLogQuery`（把 `previous` 映射成 `terminated()`）

这是整批里唯一"看不出来对不对"的一行：fabric8 没有 `previous()`，它叫 `terminated()`。
把它单独关在一个小类里，是为了能被单测钉住，也是为了两处端点共用同一份说明。

**Files:**
- Create: `k8s-server/src/main/java/com/coding/k8sserver/components/PodLogQuery.java`
- Test: `k8s-server/src/test/java/com/coding/k8sserver/components/PodLogQueryTest.java`

**Interfaces:**
- Consumes: 无（只依赖 fabric8 的 `KubernetesClient`）
- Produces:
  - `public static BytesLimitTerminateTimeTailPrettyLoggable PodLogQuery.open(KubernetesClient client, String namespace, String pod, String container)`
  - `public static TimeTailPrettyLoggable PodLogQuery.withPrevious(BytesLimitTerminateTimeTailPrettyLoggable loggable, boolean previous)`

- [ ] **Step 1: 写失败的测试**

```java
package com.coding.k8sserver.components;

import io.fabric8.kubernetes.client.dsl.BytesLimitTerminateTimeTailPrettyLoggable;
import io.fabric8.kubernetes.client.dsl.TimeTailPrettyLoggable;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PodLogQueryTest {

    @Test
    void previousMapsToTerminatedOnTheFabric8Chain() {
        // ⭐ fabric8 里 previous **不叫** previous()，叫 terminated()。
        //    实测反编译：terminated() → withTerminatedStatus(true) → URL 上的 `&previous=true`。
        //    这一行是整批里唯一一眼看不出对错的地方，所以钉住它。
        BytesLimitTerminateTimeTailPrettyLoggable loggable =
                mock(BytesLimitTerminateTimeTailPrettyLoggable.class);
        TimeTailPrettyLoggable terminated = mock(TimeTailPrettyLoggable.class);
        when(loggable.terminated()).thenReturn(terminated);

        assertThat(PodLogQuery.withPrevious(loggable, true)).isSameAs(terminated);
        verify(loggable).terminated();
    }

    @Test
    void withoutPreviousTheChainIsLeftAlone() {
        // 防"顺手一直带着 previous" —— 那会让所有日志都变成上一代的
        BytesLimitTerminateTimeTailPrettyLoggable loggable =
                mock(BytesLimitTerminateTimeTailPrettyLoggable.class);

        assertThat(PodLogQuery.withPrevious(loggable, false)).isSameAs(loggable);
        verify(loggable, never()).terminated();
    }
}
```

- [ ] **Step 2: 跑测试确认它失败**

Run: `mvn -pl k8s-server -am test -Dtest=PodLogQueryTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 编译失败 —— `PodLogQuery` 还不存在（`找不到符号`）。

- [ ] **Step 3: 实现**

```java
package com.coding.k8sserver.components;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.BytesLimitTerminateTimeTailPrettyLoggable;
import io.fabric8.kubernetes.client.dsl.TimeTailPrettyLoggable;

/**
 * 两处 Pod 日志端点（命名空间域 / 集群域）共用的一小块：把「取哪一份日志」拼成 fabric8 的查询链。
 *
 * <p>⚠️ 这里藏着两件只有这一处知道的事，所以宁可单独成类、也不在两边各抄一遍：
 *
 * <ol>
 *   <li><b>fabric8 里 {@code previous} 叫 {@code terminated()}</b>。实测反编译
 *       （kubernetes-client 7.6.1）：{@code terminated()} →
 *       {@code PodOperationContext.withTerminatedStatus(true)} → 拼进 URL 的是
 *       <b>{@code &previous=true}</b>。Java 侧没有 {@code previous()} 这个方法名可猜。</li>
 *   <li><b>{@code terminated()} 会让链"降级"</b>：它返回 {@link TimeTailPrettyLoggable}，
 *       而 {@code limitBytes} 与 {@code terminated} 都挂在
 *       {@link BytesLimitTerminateTimeTailPrettyLoggable} 上 —— 调了任一个就再也够不到另一个。
 *       两处端点本来都不用 {@code limitBytes}（它们靠 {@code transferTo} 直出流），所以无影响；
 *       <b>但别顺手加</b>，加了直接编译不过。</li>
 * </ol>
 */
public final class PodLogQuery {

    private PodLogQuery() {
    }

    /**
     * 开链：恒带 timestamps —— 每行加 RFC3339Nano 前缀，前端据此做增量游标、展示时剥掉还原原始日志。
     */
    public static BytesLimitTerminateTimeTailPrettyLoggable open(
            KubernetesClient client, String namespace, String pod, String container) {
        return client.pods()
                .inNamespace(namespace)
                .withName(pod)
                .inContainer(container)
                .usingTimestamps();
    }

    /**
     * 要不要「上一次终止的容器实例」。false 时**原样返回**，一个方法都不多调。
     */
    public static TimeTailPrettyLoggable withPrevious(BytesLimitTerminateTimeTailPrettyLoggable loggable,
                                                      boolean previous) {
        return previous ? loggable.terminated() : loggable;
    }
}
```

- [ ] **Step 4: 跑测试确认它通过**

Run: `mvn -pl k8s-server -am test -Dtest=PodLogQueryTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: `Tests run: 2, Failures: 0, Errors: 0`

- [ ] **Step 5: 变异验一下测试真的会咬**

把 `withPrevious` 改成永远返回 `loggable`（即 previous 不生效），重跑 ——
`previousMapsToTerminatedOnTheFabric8Chain` 必须**红**。改回来，再确认绿。

- [ ] **Step 6: 提交**

```bash
git add k8s-server/src/main/java/com/coding/k8sserver/components/PodLogQuery.java k8s-server/src/test/java/com/coding/k8sserver/components/PodLogQueryTest.java
git commit -m "feat(logs): previous 在 fabric8 里叫 terminated() —— 单独关起来并钉住"
```

---

### Task 2: k8s-server · 两个日志端点接上 `previous`

**Files:**
- Modify: `k8s-server/src/main/java/com/coding/k8sserver/controllers/namespace/PodController.java`（`logs` 方法，约 72-118 行）
- Modify: `k8s-server/src/main/java/com/coding/k8sserver/controllers/cluster/NodeController.java`（`podLogs` 方法，约 147-176 行）
- Test: `k8s-server/src/test/java/com/coding/k8sserver/controllers/PodLogEndpointsAcceptPreviousTest.java`

**Interfaces:**
- Consumes: Task 1 的 `PodLogQuery.open(...)` / `PodLogQuery.withPrevious(...)`
- Produces: 两个端点各多一个查询参数 `previous`（`Boolean`，可空，缺省 = 不带）

- [ ] **Step 1: 写失败的测试**

```java
package com.coding.k8sserver.controllers;

import com.coding.k8sserver.controllers.cluster.NodeController;
import com.coding.k8sserver.controllers.namespace.PodController;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RequestParam;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 声明级测试：两个端点各多了一个 {@code previous} 参数。
 *
 * <p>⚠️ 这里测的是**声明**而不是行为 —— 行为（映射成 {@code terminated()}）由
 * {@code PodLogQueryTest} 钉住。两边合起来才完整：这个防"参数被人删掉/改了类型"，
 * 那个防"映射写错"。路径与参数名 platform-api / 前端会照字面用，写错就是 404 或静默丢参。
 */
class PodLogEndpointsAcceptPreviousTest {

    @Test
    void namespaceScopedLogsDeclareAPreviousParam() throws Exception {
        Method m = PodController.class.getMethod("logs", String.class, String.class, String.class,
                String.class, String.class, Integer.class, String.class, Integer.class, Boolean.class);
        assertThat(m.getParameters()[8].getAnnotation(RequestParam.class)).isNotNull();
        assertThat(m.getParameters()[8].getType()).isEqualTo(Boolean.class);
    }

    @Test
    void clusterScopedPodLogsDeclareAPreviousParam() throws Exception {
        Method m = NodeController.class.getMethod("podLogs", String.class, String.class, String.class,
                String.class, String.class, Integer.class, String.class, Integer.class, Boolean.class);
        assertThat(m.getParameters()[8].getAnnotation(RequestParam.class)).isNotNull();
        assertThat(m.getParameters()[8].getType()).isEqualTo(Boolean.class);
    }
}
```

- [ ] **Step 2: 跑测试确认它失败**

Run: `mvn -pl k8s-server -am test -Dtest=PodLogEndpointsAcceptPreviousTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL —— `NoSuchMethodException`（签名还是 8 个参数）

- [ ] **Step 3: 改 `PodController.logs`**

签名末尾加一个参数、把建链换成 `PodLogQuery`、把 lambda 里用的变量类型换成 `TimeTailPrettyLoggable`：

```java
    @GetMapping(value = "/{name}/logs")
    @Operation(summary = "获取 Pod 日志（增量轮询 sinceTime；初始化回看 tailLines/sinceSeconds + timestamps；"
            + "previous=true 取上一次终止的容器实例）")
    public StreamingResponseBody logs(@PathVariable String name,
                                      @RequestParam(required = false) String tenantId,
                                      @RequestParam String clusterId,
                                      @RequestParam String namespace,
                                      @RequestParam(required = false) String container,
                                      @RequestParam(required = false) Integer sinceSeconds,
                                      @RequestParam(required = false) String sinceTime,
                                      @RequestParam(required = false) Integer tailLines,
                                      @RequestParam(required = false) Boolean previous) {
        AccessContext ctx = accessResolver.resolveNamespacedAccess(tenantId, clusterId, namespace);

        KubernetesClient client = getClient(ctx, clusterId);
        // 恒带 timestamps：每行加 RFC3339Nano 前缀，前端据此做增量游标、展示时剥掉前缀还原原始日志。
        // sinceTime=增量轮询（取上次之后所有新行，不丢行）；tailLines=初始化回看最近 N 行（首选，安静容器也不空）；
        // sinceSeconds=按时间窗回看（旧方式，保留兼容）；都不给=全量。
        // ⚠️ previous=true 时这条链会降级（丢掉 limitBytes）—— 见 PodLogQuery 的类注释。
        TimeTailPrettyLoggable loggable = PodLogQuery.withPrevious(
                PodLogQuery.open(client, namespace, name, container),
                Boolean.TRUE.equals(previous));

        return outputStream -> {

            if (StringUtils.hasText(sinceTime)) {
                loggable.sinceTime(sinceTime)
                        .withPrettyOutput()
                        .getLogInputStream()
                        .transferTo(outputStream);
            } else if (tailLines != null) {
                loggable.tailingLines(tailLines)
                        .withPrettyOutput()
                        .getLogInputStream()
                        .transferTo(outputStream);
            } else if (sinceSeconds != null) {
                loggable.sinceSeconds(sinceSeconds)
                        .withPrettyOutput()
                        .getLogInputStream()
                        .transferTo(outputStream);
            } else {
                loggable.withPrettyOutput()
                        .getLogInputStream()
                        .transferTo(outputStream);
            }
        };
    }
```

导入调整（本文件顶部）：
- 加 `import com.coding.k8sserver.components.PodLogQuery;`
- 加 `import io.fabric8.kubernetes.client.dsl.TimeTailPrettyLoggable;`
- `BytesLimitTerminateTimeTailPrettyLoggable` 的 import 现在只被 `PodLogQuery` 用 —— 若编译器报未使用就删掉它（Java 不报未使用 import 的错，删掉更干净）

- [ ] **Step 4: 改 `NodeController.podLogs`**

同构改动（注意它的路径参数多一个 `name`，且用 `clientFactory.getAdminClient`）：

```java
    @GetMapping(value = "/{name}/pods/{namespace}/{podName}/logs")
    @Operation(summary = "节点上某 Pod 的日志（增量轮询 sinceTime；初始化回看 tailLines/sinceSeconds + timestamps；"
            + "previous=true 取上一次终止的容器实例）")
    public StreamingResponseBody podLogs(@PathVariable String name, @PathVariable String namespace,
                                         @PathVariable String podName, @RequestParam String clusterId,
                                         @RequestParam(required = false) String container,
                                         @RequestParam(required = false) Integer sinceSeconds,
                                         @RequestParam(required = false) String sinceTime,
                                         @RequestParam(required = false) Integer tailLines,
                                         @RequestParam(required = false) Boolean previous) {
        accessResolver.assertClusterAccess(clusterId);
        KubernetesClient client = clientFactory.getAdminClient(clusterId);
        // 恒带 timestamps：每行加 RFC3339Nano 前缀，前端据此做增量游标、展示时剥掉前缀还原原始日志。
        // ⚠️ previous=true 时这条链会降级（丢掉 limitBytes）—— 见 PodLogQuery 的类注释。
        TimeTailPrettyLoggable loggable = PodLogQuery.withPrevious(
                PodLogQuery.open(client, namespace, podName, container),
                Boolean.TRUE.equals(previous));

        return outputStream -> {
            if (StringUtils.hasText(sinceTime)) {
                loggable.sinceTime(sinceTime).withPrettyOutput().getLogInputStream().transferTo(outputStream);
            } else if (tailLines != null) {
                loggable.tailingLines(tailLines).withPrettyOutput().getLogInputStream().transferTo(outputStream);
            } else if (sinceSeconds != null) {
                loggable.sinceSeconds(sinceSeconds).withPrettyOutput().getLogInputStream().transferTo(outputStream);
            } else {
                loggable.withPrettyOutput().getLogInputStream().transferTo(outputStream);
            }
        };
    }
```

- [ ] **Step 5: 跑测试确认它通过**

Run: `mvn -pl k8s-server -am test -Dtest=PodLogEndpointsAcceptPreviousTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: `Tests run: 2, Failures: 0, Errors: 0`

- [ ] **Step 6: 跑 k8s-server 全量（别把既有测试弄红）**

Run: `mvn -pl k8s-server -am test`
Expected: `BUILD SUCCESS`

- [ ] **Step 7: 提交**

```bash
git add k8s-server/src/main/java/com/coding/k8sserver/controllers/namespace/PodController.java k8s-server/src/main/java/com/coding/k8sserver/controllers/cluster/NodeController.java k8s-server/src/test/java/com/coding/k8sserver/controllers/PodLogEndpointsAcceptPreviousTest.java
git commit -m "feat(logs): 两处 Pod 日志端点接上 previous（上一次容器实例）"
```

---

### Task 3: 前端 · `utils/logLines.ts`（两个视图共用的日志行口径）

**Files:**
- Create: `platform-web/src/utils/logLines.ts`
- Create（临时冒烟，跑完删）: `platform-web/smoke-log-lines.html`、`platform-web/smoke-log-lines.ts`

**Interfaces:**
- Consumes: 无
- Produces:
  - `splitLogLine(line: string): [string, string]`
  - `normTs(ts: string): string`
  - `interface LogLine { ts: string; content: string }`
  - `takeNewLines(text: string, startCursor: string): { lines: LogLine[]; nextCursor: string }`
  - `compareTs(a: string, b: string): number`
  - `sortByTs(lines: LogLine[]): LogLine[]`
  - `prefixOf(pod: string, container: string): string`

- [ ] **Step 1: 实现 `logLines.ts`**

```ts
/**
 * 日志行的**纯函数**口径 —— 单 Pod 的日志弹窗与工作负载级的聚合视图共用同一份。
 *
 * ⚠️ 为什么必须共用：同一份日志在两个视图里要显示出**一样的行**。各写一份的话，
 *    前缀正则或小数秒的比法迟早分叉，而那种分叉极难发现（症状只是"两处看着有点不一样"）。
 *
 * ⚠️ 本仓前端**没有测试框架**（没有 vitest/jest），所以这一层的验证走浏览器冒烟台：
 *    `smoke-log-lines.ts` 直接 import 本模块断言 —— 那是本仓既有的做法。
 */

/** k8s 注入的时间戳前缀：RFC3339Nano（UTC 'Z'）+ 制表符或空格。只认 UTC-Z，见 splitLogLine。 */
export const K8S_TS_PREFIX = /^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?Z)[ \t]+/

/**
 * 一行 `<RFC3339Nano>\t<内容>` → `[时间戳, 内容]`；没有前缀则时间戳为空串。
 *
 * ⚠️ 刻意**不**用「找第一个制表符」那种写法：内容里本来就带制表符的行（`ls -l` 的输出、
 *    表格化的应用日志）会被从中间劈开，前半个当成时间戳、后半个当成内容 ——
 *    更糟的是那个"假时间戳"还会被当成游标推上去，**之后的增量全乱**。
 *    分隔符可能是制表符（kubelet 原样）也可能是空格（透传链路规整过），两种都认。
 * ⚠️ 只认 UTC 的 `Z`：认 `+08:00` 会把应用自己打在行首的时间戳剥掉。
 */
export function splitLogLine(line: string): [string, string] {
  const m = K8S_TS_PREFIX.exec(line)
  return m ? [m[1] ?? '', line.slice(m[0].length)] : ['', line]
}

/**
 * 小数秒补到固定 9 位，让同一来源的时间戳能**按字典序**比较。
 * ⚠️ 直接比字符串是不对的：`…:00.5Z` 与 `…:00.45Z` 按字符串比会得出前者更小，而按时间它更大。
 */
export function normTs(ts: string): string {
  const m = /^(.*?)(\.\d+)?(Z|[+-]\d{2}:?\d{2})?$/.exec(ts)
  if (!m) return ts
  const frac = ((m[2] ?? '').slice(1) + '000000000').slice(0, 9)
  return m[1] + '.' + frac + (m[3] ?? '')
}

/** 一行日志：k8s 时间戳（没有前缀时是空串）+ **已剥掉前缀**的内容。 */
export interface LogLine {
  ts: string
  content: string
}

/**
 * 拆一批文本 → 只要**严格晚于** `startCursor` 的新行。
 *
 * ⚠️ 为什么是"严格晚于"：k8s 的 `sinceTime` 是「该时刻之后」**且边界那行会重复来一次** ——
 *    不滤的话每轮都会多出一行"上一轮的尾巴"。
 * ⚠️ 没有 k8s 前缀的行**无法比大小也无法去重**，原样收下且**不推进游标**
 *    （调用方据此知道"这批没法增量"，见 PodLogDialog 的降级处理）。
 */
export function takeNewLines(
  text: string,
  startCursor: string,
): { lines: LogLine[]; nextCursor: string } {
  const lines: LogLine[] = []
  let nextCursor = startCursor
  for (const raw of text.split('\n')) {
    if (!raw) continue
    const [ts, content] = splitLogLine(raw)
    if (!ts) {
      lines.push({ ts: '', content })
      continue
    }
    if (normTs(ts) > normTs(nextCursor)) {
      lines.push({ ts, content })
      nextCursor = ts
    }
  }
  return { lines, nextCursor }
}

/**
 * 两个时间戳的先后（-1 / 0 / 1）。**两个视图都调它** —— 各写一份比较器迟早分叉，
 * 而那种分叉的症状只是"两处的顺序偶尔不一样"，极难发现。
 * ⚠️ 约定：**没有时间戳的一律算"更晚"**（于是排在后面），两个都没有则相等。
 */
export function compareTs(a: string, b: string): number {
  if (!a && !b) return 0
  if (!a) return 1
  if (!b) return -1
  const x = normTs(a)
  const y = normTs(b)
  return x < y ? -1 : x > y ? 1 : 0
}

/**
 * 批内排序：有时间戳的按时间戳，**没有时间戳的一律排在后面**（保持它们原来那点相对顺序）。
 *
 * ⚠️ 只排"这一批"。跨批全局有序要缓存"还没到齐的更早行"，延迟与内存都不好控 ——
 *    而排障时多等几秒就是副作用（spec §3.3）。
 */
export function sortByTs(lines: LogLine[]): LogLine[] {
  return lines
    .map((l, i) => ({ l, i }))
    .sort((a, b) => compareTs(a.l.ts, b.l.ts) || a.i - b.i) // 相等时保持原顺序（Array.sort 现代实现是稳定的）
    .map((x) => x.l)
}

/**
 * 聚合视图的行前缀 —— **照抄 `kubectl logs -l … --all-containers --prefix`** 的形态：
 * `[pod/<pod名>/<容器名>]`。照抄的理由：排障的人多半先想到 kubectl 那条命令，
 * 两边长得一样就不用重新认（真跑出来的样子见 spec §0）。
 */
export function prefixOf(pod: string, container: string): string {
  return `[pod/${pod}/${container}] `
}
```

- [ ] **Step 2: 写冒烟台接断言**

`platform-web/smoke-log-lines.html`：

```html
<!doctype html>
<html lang="zh-CN">
  <head><meta charset="utf-8" /><title>smoke: logLines</title></head>
  <body style="margin: 0">
    <div id="smoke-out" style="font: 12px/1.5 monospace; padding: 8px; white-space: pre-wrap"></div>
    <script type="module" src="/smoke-log-lines.ts"></script>
  </body>
</html>
```

`platform-web/smoke-log-lines.ts`：

```ts
/**
 * 冒烟：utils/logLines.ts 的纯函数。
 * ⚠️ 本仓前端没有测试框架，所以纯函数也走这条既有的路子（直接 import 真模块断言）。
 */
import { compareTs, normTs, prefixOf, sortByTs, splitLogLine, takeNewLines } from '@/utils/logLines'

const out = document.getElementById('smoke-out')!
const lines: string[] = []
const results: Array<[string, boolean, string]> = []
function log(s: string): void {
  lines.push(s)
  out.textContent = lines.join('\n')
  console.log('[smoke]', s)
}
function check(name: string, ok: boolean, detail = ''): void {
  results.push([name, ok, detail])
  log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? '  — ' + detail : ''}`)
}

// ---- 前缀拆解 ----
check('制表符分隔', JSON.stringify(splitLogLine('2026-10-16T07:16:32Z\thi')) === JSON.stringify(['2026-10-16T07:16:32Z', 'hi']))
check('空格分隔也认（中间转发可能把制表符规整掉）',
  JSON.stringify(splitLogLine('2026-10-16T07:16:32Z hi')) === JSON.stringify(['2026-10-16T07:16:32Z', 'hi']))
// ⭐ Review Focus #3：内容里自带制表符的行，不许从中间劈开
check('内容里的制表符不会被当成前缀分隔符',
  JSON.stringify(splitLogLine('a\tb')) === JSON.stringify(['', 'a\tb']),
  JSON.stringify(splitLogLine('a\tb')))
// 应用自己打在行首的 +08:00 时间戳不许被剥
check('行首 +08:00 的自家时间戳不剥',
  JSON.stringify(splitLogLine('2026-10-16T15:16:32+08:00 hi')) === JSON.stringify(['', '2026-10-16T15:16:32+08:00 hi']))

// ---- 小数秒补零 ----
check('小数秒补零后字典序=时间序', normTs('2026-10-16T07:16:00.5Z') > normTs('2026-10-16T07:16:00.45Z'))
check('整数秒也补零', normTs('2026-10-16T07:16:00Z') < normTs('2026-10-16T07:16:01Z'))

// ---- 增量：严格晚于游标 ----
const a = takeNewLines(
  '2026-10-16T07:16:35.000000000Z 边界\n2026-10-16T07:16:40.123456789Z 新行\n',
  '2026-10-16T07:16:35.000000000Z',
)
check('边界那行被滤掉（k8s 的 sinceTime 会把它重复送来）',
  a.lines.length === 1 && a.lines[0]!.content === '新行', JSON.stringify(a.lines))
check('游标推进到最新的那行', a.nextCursor === '2026-10-16T07:16:40.123456789Z', a.nextCursor)

const noTs = takeNewLines('没有时间戳的一行\n', '')
check('没有前缀的行照样收下（但游标不动）',
  noTs.lines.length === 1 && noTs.nextCursor === '', JSON.stringify(noTs))

// ---- 批内排序 ----
const sorted = sortByTs([
  { ts: '2026-10-16T07:16:40.000000000Z', content: '晚' },
  { ts: '', content: '无戳' },
  { ts: '2026-10-16T07:16:35.000000000Z', content: '早' },
])
// ⭐ Review Focus #5：没有时间戳的不许插到中间
check('批内按时间排、无时间戳的排最后',
  sorted.map((l) => l.content).join(',') === '早,晚,无戳', sorted.map((l) => l.content).join(','))

// ---- 比较器（两个视图共用，别各写一份）----
check('compareTs：空串算"更晚"', compareTs('', '2026-10-16T07:16:00Z') === 1 && compareTs('2026-10-16T07:16:00Z', '') === -1)
check('compareTs：两个都空算相等', compareTs('', '') === 0)
check('compareTs：正常先后', compareTs('2026-10-16T07:16:35Z', '2026-10-16T07:16:40Z') === -1)

// ---- 前缀 ----
check('前缀照抄 kubectl 的形态', prefixOf('web-1', 'app') === '[pod/web-1/app] ', prefixOf('web-1', 'app'))

const bad = results.filter(([, ok]) => !ok)
log(`\n== ${results.length - bad.length}/${results.length} 通过 ==`)
document.title = bad.length === 0 ? 'SMOKE-OK' : `SMOKE-FAIL(${bad.length})`
```

- [ ] **Step 3: 跑冒烟，确认全绿**

```bash
# 起 dev server（launch.json 里已有 main-dev-check，端口 5199）
# 然后浏览器打开 /smoke-log-lines.html，读 document.title 与 #smoke-out
```
Expected: `SMOKE-OK`，`== 14/14 通过 ==`

- [ ] **Step 4: 变异验一下（至少两条）**

- 把 `sortByTs` 的 `if (!a.l.ts) return 1` 改成 `return -1` → `批内按时间排…` 必须红
- 把 `splitLogLine` 改回「找第一个制表符」→ `内容里的制表符…` 必须红
改回来，确认绿。

- [ ] **Step 5: 提交（不提交冒烟文件）**

```bash
git add platform-web/src/utils/logLines.ts
git commit -m "feat(logs): 抽出日志行的纯函数口径（两视图共用）并修掉制表符被当前缀的隐患"
```

---

### Task 4: 前端 · `PodLogDialog` 的「上一次」开关

**Files:**
- Modify: `platform-web/src/components/PodLogDialog.vue`

**Interfaces:**
- Consumes: Task 2 的 `previous` 查询参数；Task 3 的 `takeNewLines` / `splitLogLine` / `normTs`
- Produces: 无（组件内部）

**背景（spec §9 已实测，不是推断）**：上一次实例是**已终止**的容器，不会再产出任何行 ——
`--previous --since-time=<现在>` 的实测输出长度是 0。所以**「上一次」模式固定走 `tailLines` 看一次快照、不轮询**。

- [ ] **Step 1: 换掉本地的时间戳实现，改调 `logLines`**

删掉本文件里的 `K8S_TS_PREFIX`、`splitLine`、`normTs` 三个本地定义，改成导入；`processLog` 改成这个形状（**行为不变**，只是解析来源换成共用模块）：

```ts
import { splitLogLine, takeNewLines } from '@/utils/logLines'

/** 把一批文本拆成「要显示的新行」，并推进游标。行为与重构前一致，解析改由 utils/logLines 提供。 */
function processLog(text: string, startCursor: string): { newContents: string[]; nextCursor: string } {
  const { lines, nextCursor } = takeNewLines(text, startCursor)
  const newContents = lines.map((l) => l.content)
  const hadTs = lines.some((l) => !!l.ts)
  if (!hadTs && newContents.length) {
    // 无前缀：无法精确去重 —— 保持既有降级（整批追加 + 每次会话只提示一次）
    if (!warnedNoTs) {
      warnedNoTs = true
      console.warn('[PodLog] 响应缺少 k8s 时间戳前缀（<ts>\\t），请确认 k8s-server 已重建并启用 usingTimestamps；当前退化为按批次追加。')
    }
    return { newContents, nextCursor: new Date().toISOString() }
  }
  return { newContents, nextCursor }
}
```

⚠️ 若 `splitLogLine` 这个名字在本文件里已被别处引用，一并改掉；`grep -n splitLine platform-web/src/components/PodLogDialog.vue` 确认没有漏网的。

- [ ] **Step 2: 加「上一次」状态与开关**

在 props 之后加：

```ts
/** 看**上一次容器实例**（CrashLoopBackOff 的死因在那一代里）。只在 Pod 真重启过时给。 */
const showPreviousToggle = computed(() => (props.pod?.restarts ?? 0) > 0)
const previous = ref(false)

/** ⚠️ 上一次实例是**已终止**的容器，不会再产出 —— 实测 `--previous --since-time=<现在>` 输出长度为 0。
 *  所以这个模式下只做一次 tailLines 快照，**不轮询**（见 spec §9.2）。 */
function setPrevious(v: boolean): void {
  previous.value = v
  beginSession()
}
```

`fetchLogs` 里加参数（放在 `container` 之后）：

```ts
  if (previous.value) q.set('previous', 'true')
```
并把「增量游标」那条限制住 —— 该函数开头加一行：

```ts
  // ⚠️ previous 模式没有增量可言（死掉的容器不产出新行），游标一律不发
  if (previous.value) sinceTime = undefined
```

`beginSession()` 里把轮询条件收紧：

```ts
  void bootstrap()
  // ⚠️ previous 模式是一次性快照，不起轮询（死掉的容器不会再产出）
  if (!previous.value) {
    pollTimer = window.setInterval(() => void pollOnce(), POLL_MS)
  }
```

`pollOnce()` 开头加一行保险：`if (previous.value) return`。

`beginSession()` 开头（`cursor = ''` 附近）**不要**重置 `previous` —— 用户切容器/回看行数时应当保持在同一个模式；但**弹窗每次打开要重置**：在 `onOpened()` 里加 `previous.value = false`。

- [ ] **Step 3: 工具栏加按钮**

在「彩色」按钮**之前**插入（`size="small"`，与相邻按钮一致）：

```html
      <el-button
        v-if="showPreviousToggle"
        size="small"
        :type="previous ? 'warning' : 'default'"
        :title="previous ? '正在看上一次容器实例（已终止，不会再刷新）' : '看看上一次容器实例为什么死的'"
        @click="setPrevious(!previous)"
      >
        上一次
      </el-button>
```

- [ ] **Step 4: 冒烟验证（Review Focus #4）**

写一对临时冒烟文件 `platform-web/smoke-previous.html` + `.ts`，挂真 `PodLogDialog`、用假 XHR 记录请求：

1. `pod.restarts = 0` → 工具栏里**没有**「上一次」按钮
2. `pod.restarts = 3` → 有；点一下之后发出的请求 URL 里带 `previous=true`
3. 该请求**不带** `sinceTime`，且点完之后**不再有新请求**（等 3 秒，请求数不涨）—— 这条正是"不轮询"
4. `previous` 模式下服务端返回 k8s 的原话（模拟 `previous terminated container not found`）→ 面板上显示这句话而不是空白（把 `pollOnce`/`bootstrap` 的 catch 分支确认成会往终端写 `[错误] …`；若现在是静默吞掉，就在 `bootstrap` 的 catch 里补一次 `term?.write()`）

⚠️ 第 4 条如果发现 `bootstrap` 的 catch 现在就是写终端的，那这条断言直接过；若不是，**补上**再提交。

- [ ] **Step 5: type-check**

Run: `cd platform-web && NODE_OPTIONS=--max-old-space-size=4096 npm run type-check`
Expected: 无输出（干净通过）

- [ ] **Step 6: 提交**

```bash
git add platform-web/src/components/PodLogDialog.vue
git commit -m "feat(logs): 单 Pod 日志弹窗加「上一次」开关（快照，不轮询）"
```

---

### Task 5: 前端 · `WorkloadLogsDialog` —— 勾选 + 快照

先交付"勾完给一份快照"，Task 6 再把它变成流。

**Files:**
- Create: `platform-web/src/components/WorkloadLogsDialog.vue`

**Interfaces:**
- Consumes: Task 3 的 `takeNewLines` / `sortByTs` / `prefixOf`
- Produces: 组件 `WorkloadLogsDialog`，props `{ modelValue: boolean; pods: K8sPod[]; tenantId?: string; clusterId: string; namespace: string }`，emit `update:modelValue`

- [ ] **Step 1: 写组件（含勾选表 + 一次性快照）**

```vue
<script setup lang="ts">
/**
 * **工作负载级日志**（S2.1）：勾选若干 Pod → 一条带 `[pod/<pod>/<容器>]` 前缀的流。
 *
 * 形态照抄 `kubectl logs -l … --all-containers --prefix`（spec §0）——
 * k8s 核心只有 Pod 的 `logs` 子资源，没有工作负载级的；kubectl 那套是**客户端扇出**，
 * 本组件就是那个扇出：每个 (pod, 容器) 各自一个 sinceTime 游标，每 2 秒收一次新行，
 * **在本轮内**按时间戳排好再追加（不跨轮缓存排序 —— spec §3.3）。
 *
 * ⚠️ 每一步抓取都走**既有**的单 Pod 日志端点，授权与今天逐个点开完全一样 ——
 *    所以本批不新增端点、不新增权限行（spec §5）。
 */
import { computed, nextTick, onUnmounted, ref, watch } from 'vue'
import type { K8sPod } from '@/types'
import { getAccessToken } from '@/auth/oauth'
import EmptyState from '@/components/EmptyState.vue'
import { prefixOf, sortByTs, takeNewLines, type LogLine } from '@/utils/logLines'

const props = defineProps<{
  modelValue: boolean
  pods: K8sPod[]
  tenantId?: string
  clusterId: string
  namespace: string
}>()
const emit = defineEmits<{ (e: 'update:modelValue', v: boolean): void }>()

const visible = computed({
  get: () => props.modelValue,
  set: (v) => emit('update:modelValue', v),
})

/** 默认勾选数与硬上限：抓取量 = Pod 数 × 容器数，不封顶会直接撞到集群（spec §3.2） */
const DEFAULT_PICK = 10
const HARD_MAX = 20
/** 首次回看最近多少行（每个来源各自一份） */
const TAIL_LINES = 200

/** 一条来源 = 一个 (pod, 容器) */
interface Source {
  pod: string
  container: string
  cursor: string
  /** 已经提示过取不到，别每轮刷屏 */
  warned: boolean
}

const phase = ref<'pick' | 'stream'>('pick')
const picked = ref<string[]>([])
const sources = ref<Source[]>([])
const logText = ref('')
const textEl = ref<HTMLElement | null>(null)
const atBottom = ref(true)
const busy = ref(false)

/** 最近创建的排前面 —— 纯按时间，不做"异常优先"那套（用户裁定，spec §3.2） */
const podsByNewest = computed(() =>
  [...props.pods].sort((a, b) => (b.creationTime ?? '').localeCompare(a.creationTime ?? '')),
)

const overLimit = computed(() => picked.value.length >= HARD_MAX)
const allPicked = computed(
  () => props.pods.length > 0 && picked.value.length === Math.min(props.pods.length, HARD_MAX),
)

function podLabel(p: K8sPod): string {
  const reason = p.statusReason ? `（${p.statusReason}）` : ''
  return `${p.phase ?? '—'}${reason}`
}

function toggleAll(v: boolean): void {
  picked.value = v ? podsByNewest.value.slice(0, HARD_MAX).map((p) => p.name) : []
}

function togglePick(name: string): void {
  picked.value = picked.value.includes(name)
    ? picked.value.filter((n) => n !== name)
    : [...picked.value, name]
}

/** 弹窗每次打开都回到勾选这一步，并按"最近创建的 10 个"预选 */
function resetPick(): void {
  phase.value = 'pick'
  picked.value = podsByNewest.value.slice(0, DEFAULT_PICK).map((p) => p.name)
  sources.value = []
  logText.value = ''
  atBottom.value = true
}

watch(visible, (v) => {
  if (v) resetPick()
})

function podOf(name: string): K8sPod | undefined {
  return props.pods.find((p) => p.name === name)
}

/** 一个 Pod 有几个容器就跟几个 —— 前缀把它们分开（Review Focus #1） */
function sourcesOf(pod: K8sPod): Source[] {
  const cs = pod.containers?.length ? pod.containers : ['']
  return cs.map((c) => ({ pod: pod.name, container: c, cursor: '', warned: false }))
}

function requestUrl(s: Source, sinceTime?: string): string {
  const q = new URLSearchParams()
  if (props.tenantId) q.set('tenantId', props.tenantId)
  q.set('clusterId', props.clusterId)
  q.set('namespace', props.namespace)
  if (s.container) q.set('container', s.container)
  if (sinceTime) q.set('sinceTime', sinceTime)
  else q.set('tailLines', String(TAIL_LINES))
  return `/api/pods/${encodeURIComponent(s.pod)}/logs?${q.toString()}`
}

async function fetchSource(s: Source, sinceTime?: string): Promise<string> {
  const resp = await fetch(requestUrl(s, sinceTime), {
    headers: { Authorization: `Bearer ${getAccessToken() ?? ''}` },
  })
  const ctype = resp.headers.get('content-type') ?? ''
  if (!resp.ok || ctype.includes('application/json')) {
    let msg = `HTTP ${resp.status}`
    try {
      const body = (await resp.json()) as { msg?: string }
      if (body.msg) msg = body.msg
    } catch {
      /* 保留 HTTP 状态 */
    }
    throw new Error(msg)
  }
  return await resp.text()
}

/** 把 (来源, 文本) 收成带前缀、排好序的整块文本 */
function render(s: Source, lines: LogLine[]): string {
  return lines.map((l) => prefixOf(s.pod, s.container) + l.content).join('\n') + (lines.length ? '\n' : '')
}

/** 某个来源取不到 —— 只插一行说明，**不影响其余**（Review Focus #2 / spec §3.4） */
function warnOnce(s: Source, why: string): string {
  if (s.warned) return ''
  s.warned = true
  return `${prefixOf(s.pod, s.container)}暂时取不到日志（${why}）\n`
}

/** 首次：每个来源各拉一次 tailLines 快照 */
async function snapshot(): Promise<void> {
  busy.value = true
  try {
    const chunks: string[] = []
    for (const s of sources.value) {
      try {
        const text = await fetchSource(s)
        const { lines, nextCursor } = takeNewLines(text, '')
        s.cursor = nextCursor
        chunks.push(render(s, sortByTs(lines)))
      } catch (e) {
        chunks.push(warnOnce(s, (e as Error).message))
      }
    }
    logText.value = chunks.join('')
    scrollToBottom()
  } finally {
    busy.value = false
  }
}

function start(): void {
  sources.value = picked.value.flatMap((name) => {
    const p = podOf(name)
    return p ? sourcesOf(p) : []
  })
  phase.value = 'stream'
  logText.value = ''
}

function scrollToBottom(): void {
  if (!atBottom.value) return
  void nextTick(() => {
    const el = textEl.value
    if (el) el.scrollTop = el.scrollHeight
  })
}

function onScroll(): void {
  const el = textEl.value
  if (!el) return
  atBottom.value = el.scrollHeight - el.scrollTop - el.clientHeight < 8
}

function jumpToBottom(): void {
  atBottom.value = true
  scrollToBottom()
}

onUnmounted(() => {
  /* Task 6 会在这里停轮询 */
})
</script>

<template>
  <el-dialog v-model="visible" title="全部 Pod 日志" width="min(1280px, 94vw)" class="wl-logs">
    <template v-if="phase === 'pick'">
      <div class="pick-note">
        这个负载共 {{ pods.length }} 个 Pod，已勾 {{ picked.length }} 个（最多 {{ HARD_MAX }} 个）。
        勾的是 Pod —— 一个 Pod 里的每个容器都会跟，靠前缀分开。
      </div>
      <EmptyState v-if="!pods.length" title="这个工作负载当前没有 Pod"
                  description="没有 Pod 就没有日志可跟。" />
      <el-table v-else :data="podsByNewest" size="small" max-height="52vh">
        <el-table-column width="46">
          <template #header>
            <el-checkbox :model-value="allPicked" :disabled="!pods.length" @change="toggleAll($event as boolean)" />
          </template>
          <template #default="{ row }">
            <!-- ⚠️ 不用「数组 v-model」那种写法（`v-model="picked" :value=…`）—— 那要靠 el-checkbox
                 内部认数组，行为不稳。显式读写 picked 最清楚，也好断言。 -->
            <el-checkbox :model-value="picked.includes(row.name)"
                         :disabled="overLimit && !picked.includes(row.name)"
                         @change="togglePick(row.name)" />
          </template>
        </el-table-column>
        <el-table-column label="名称" min-width="300">
          <template #default="{ row }"><code class="mono">{{ row.name }}</code></template>
        </el-table-column>
        <el-table-column label="状态" width="180">
          <template #default="{ row }">{{ podLabel(row) }}</template>
        </el-table-column>
        <el-table-column label="重启" width="70">
          <template #default="{ row }">{{ row.restarts ?? 0 }}</template>
        </el-table-column>
        <el-table-column label="调度节点" min-width="120">
          <template #default="{ row }">{{ row.nodeName ?? '—' }}</template>
        </el-table-column>
        <el-table-column label="创建时间" min-width="170">
          <template #default="{ row }">{{ row.creationTime ?? '—' }}</template>
        </el-table-column>
      </el-table>
    </template>

    <template v-else>
      <div class="stream-note">
        正在跟 {{ sources.length }} 条来源（{{ picked.length }} 个 Pod）。
        <el-button link type="primary" @click="resetPick">换一批 Pod</el-button>
      </div>
      <pre ref="textEl" class="wl-log" @scroll="onScroll">{{ logText || '（还没有日志）' }}</pre>
      <div class="wl-foot">
        <el-button v-if="!atBottom" link type="primary" @click="jumpToBottom">↓ 回到底部</el-button>
      </div>
    </template>

    <template #footer>
      <el-button v-if="phase === 'pick'" @click="visible = false">取消</el-button>
      <el-button v-if="phase === 'pick'" type="primary" :disabled="!picked.length" @click="start">确定</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.pick-note,
.stream-note {
  font-size: 12px;
  color: var(--text-3);
  padding-bottom: 8px;
}
.wl-log {
  height: 56vh;
  margin: 0;
  padding: 10px;
  overflow: auto;
  border: 1px solid var(--border, #ebeef5);
  border-radius: 4px;
  background: var(--bg);
  color: var(--text-1);
  font-size: 12px;
  line-height: 1.5;
  white-space: pre-wrap;
  word-break: break-all;
}
.wl-foot {
  height: 22px;
}
</style>
```

⚠️ Task 6 会把 `onUnmounted` 与 `snapshot()` 接到轮询上，并把 `compareTs` 加进上面那行 import。

- [ ] **Step 2: 临时接到详情页，冒烟验勾选（Review Focus #1）**

在 `WorkloadDetailView.vue` 里临时挂上（Task 7 会把它变成正式入口）：

```vue
<WorkloadLogsDialog v-model="workloadLogsVisible" :pods="pods"
                    :tenant-id="ctxParams.tenantId" :cluster-id="ctxParams.clusterId"
                    :namespace="ctxParams.namespace" />
```
配 `const workloadLogsVisible = ref(true)`（临时默认打开，方便冒烟）。

冒烟断言（假 XHR；`pods` 里放 12 个 Pod，其中一个有 **2 个容器**）：

1. 默认勾了 **10** 个，且是**创建时间最新的**那 10 个
2. 再勾第 11 个 → 第 12 个的复选框**被禁用**（到上限）；到 20 个时全部未勾选的行禁用
3. 「换一批」/ 重开弹窗 → 回到勾选态
4. 确认后发出的请求数 = 勾选 Pod 数 × 各自容器数 —— **多容器那个 Pod 要发两次**（Review Focus #1）

- [ ] **Step 3: type-check**

Run: `cd platform-web && NODE_OPTIONS=--max-old-space-size=4096 npm run type-check`
Expected: 干净通过

- [ ] **Step 4: 提交**

```bash
git add platform-web/src/components/WorkloadLogsDialog.vue platform-web/src/views/resource/WorkloadDetailView.vue
git commit -m "feat(logs): 工作负载级日志弹窗 —— 勾选 Pod + 一次性快照"
```

---

### Task 6: 前端 · `WorkloadLogsDialog` —— 变成流

**Files:**
- Modify: `platform-web/src/components/WorkloadLogsDialog.vue`

**Interfaces:**
- Consumes: Task 5 的组件内部状态（`sources` / `logText` / `scrollToBottom`）
- Produces: 无（组件内部）

- [ ] **Step 1: 加轮询**

在 `<script setup>` 里补：

```ts
const POLL_MS = 2000
let timer: number | undefined

function stopPolling(): void {
  if (timer !== undefined) {
    window.clearInterval(timer)
    timer = undefined
  }
}

/**
 * 一轮：把**所有来源**的新行收上来，**在本轮内**按时间戳排好，再 Append 到末尾。
 *
 * ⚠️ 为什么可以并发收再一起排：并排各收各的会让"两个 Pod 的行交错成什么顺序"取决于网络快慢，
 *    同一份日志每次看都不一样。收齐再排，至少本轮的顺序是稳定的。
 * ⚠️ 只排本轮：跨轮全局有序要缓存"还没到齐的更早行"，延迟与内存都不好控（spec §3.3）。
 */
async function pollOnce(): Promise<void> {
  const collected: Array<{ s: Source; l: LogLine }> = []
  let extra = ''
  const results = await Promise.all(
    sources.value.map(async (s) => {
      try {
        const text = await fetchSource(s, s.cursor || undefined)
        const { lines, nextCursor } = takeNewLines(text, s.cursor)
        return { s, lines, nextCursor, err: null as Error | null }
      } catch (e) {
        return { s, lines: [] as LogLine[], nextCursor: s.cursor, err: e as Error }
      }
    }),
  )
  for (const r of results) {
    // ⭐ Review Focus #2：某个来源没了（Pod 被滚动更新删掉）不能让整条流停掉或清空
    if (r.err) extra += warnOnce(r.s, r.err.message)
    if (r.nextCursor) r.s.cursor = r.nextCursor
    for (const l of r.lines) collected.push({ s: r.s, l })
  }
  // ⚠️ 收齐再排，用的是**与单 Pod 视图同一份** compareTs —— 别再写第二个比较器
  collected.sort((a, b) => compareTs(a.l.ts, b.l.ts)) // 相等时保持收集顺序（现代 Array.sort 稳定）
  const body = collected.map((x) => prefixOf(x.s.pod, x.s.container) + x.l.content).join('\n')
  if (body || extra) {
    logText.value += extra + (body ? body + '\n' : '')
    scrollToBottom()
  }
}
```

（`compareTs` / `takeNewLines` / `prefixOf` / `sortByTs` 都从 `@/utils/logLines` 导入；
`sortByTs` 在 Task 5 的 `snapshot()` 里已经用了，本步再加 `compareTs`。）

- [ ] **Step 2: 接上启停**

`start()` 末尾：

```ts
  phase.value = 'stream'
  logText.value = ''
  stopPolling()
  void snapshot().then(() => {
    timer = window.setInterval(() => void pollOnce(), POLL_MS)
  })
```

`onUnmounted` 里：`stopPolling()`；`resetPick()` 里也加 `stopPolling()`。
`watch(visible, …)` 关闭时（`v === false`）也 `stopPolling()`。

- [ ] **Step 3: 冒烟验证「真的在跟」（Review Focus #2 / #5）**

同一对冒烟文件里加断言（假 XHR 记录每次请求的 URL 与 `sinceTime`）：

1. 首轮之后等 **2.5 秒**：每个来源**又发了一次**请求，且带上了各自的 `sinceTime`（两个来源的游标**互不相同**）
2. 新行**追加在末尾**（原来的内容还在，不是整份替换）
3. 前缀正确：`[pod/<pod>/<容器>]`，两个来源各自的行走各自的前缀
4. ⭐ **把其中一个 Pod 的响应改成 404** 之后等一轮：面板上出现一行「暂时取不到日志（…）」，
   而**另一个来源的新行照常追加**（流没停）
5. 贴底自动跟随；把 `scrollTop` 设成 0 触发 `scroll` 后再等一轮 → 位置**不被拽走**
   （⚠️ 先等面板有真实高度，`clientHeight >= 200` 再做这两条 —— 塌着量是假绿）

- [ ] **Step 4: type-check**

Run: `cd platform-web && NODE_OPTIONS=--max-old-space-size=4096 npm run type-check`
Expected: 干净通过

- [ ] **Step 5: 提交**

```bash
git add platform-web/src/components/WorkloadLogsDialog.vue platform-web/src/utils/logLines.ts
git commit -m "feat(logs): 聚合视图变成流 —— 每来源一个游标、每 2 秒批内排序追加"
```

---

### Task 7: 前端 · 正式入口 + 端到端冒烟 + 收口

**Files:**
- Modify: `platform-web/src/views/resource/WorkloadDetailView.vue`

**Interfaces:**
- Consumes: Task 5/6 的 `WorkloadLogsDialog`
- Produces: 无

- [ ] **Step 1: 入口按钮**

在 Pod 列表的 `.list-head` 里、`<span class="muted count">{{ pods.length }} 个</span>` **之后**加：

```html
            <el-button size="small" style="margin-left: 8px" :disabled="!pods.length"
                       @click="workloadLogsVisible = true">全部 Pod 日志</el-button>
```

并且把 Task 5 临时挂的那份 `<WorkloadLogsDialog …>` 的 `v-model` 换成正式的 `workloadLogsVisible`（初始 `ref(false)`，不再是 `true`）。

- [ ] **Step 2: 端到端冒烟**

跑一遍完整链路（真组件 + 假 XHR）：

1. 打开工作负载详情 → 点「全部 Pod 日志」→ 出现勾选表（没有 Pod 时按钮是禁用的）
2. 勾选 → 确定 → 日志流出现，前缀正确
3. 等 2.5 秒 → 新行自己接上来了
4. 打开单 Pod 的「日志」下拉项 → `PodLogDialog` 照旧工作（**回归**：Task 4 改了它的时间戳实现）
5. 控制台**没有** `[PodLog] 响应缺少 k8s 时间戳前缀` 这条警告（说明 `takeNewLines` 的重构没把前缀识别弄坏）

- [ ] **Step 3: 删掉临时冒烟文件**

```bash
rm -f platform-web/smoke-*.html platform-web/smoke-*.ts
```
（**只删本批新建的那几个** —— 工作区里可能还有别的 session 的冒烟文件，先 `git status` 看一眼。）

- [ ] **Step 4: 全量后端测试**

Run: `mvn test`
Expected: `BUILD SUCCESS`

- [ ] **Step 5: 提交**

```bash
git add platform-web/src/views/resource/WorkloadDetailView.vue
git commit -m "feat(logs): 工作负载详情页接入「全部 Pod 日志」"
```

---

### Task 8: 收口 —— 台账与部署说明

**Files:**
- Modify: `docs/task-ledger-2026-10-12.md`（S2.1 段与进度表）

**Interfaces:** 无

- [ ] **Step 1: 台账里记收口**

在 S2.1 段落下补一段引用块，写清**实际交付了什么、和原稿哪里不一样**（例如：S2.1.3「多容器选择」
其实由"勾 Pod 即跟它的全部容器"覆盖了；「时间范围下推」本批没做、留给 S2.3），
并把进度表的 S2.1 行从 `⬜ 未开始` 改成 `✅ 已实施待部署` + spec/plan 链接。

- [ ] **Step 2: 记下部署面**

在收口块里写明：**重建 `k8s-server` + `platform-web`**；`platform-api` 不动、数据库不动。

- [ ] **Step 3: 提交**

```bash
git add docs/task-ledger-2026-10-12.md
git commit -m "docs(ledger): S2.1 工作负载级日志收口"
```

---

## 自查记录（写完计划后回看 spec）

- **spec 覆盖**：§0（原生有没有）→ 计划头「Architecture」+ Task 3 的前缀注释；§1 交付边界 → Task 2/4/5/6；
  §2 previous → Task 1/2/4；§3 聚合视图 → Task 5/6；§4 前端结构 → Task 3/4/5（`logLines.ts` 抽取 + 新组件 + PodLogDialog 只换实现来源）；
  §5 后端分层 → Task 2（只改两个端点，不新增）；§6 权限 → 无任务（**本批不动权限表**，故无需任务）；
  §7 测试 → 各任务的 Step；§8 不做 → 无任务（正确）；§9 已实测前提 → 已并入 Task 4 的注释与 Task 6 的设计；§10 部署 → Task 8。
- **无占位符**：每个代码步骤都给了可直接粘贴的代码；没有 TBD / "类似 Task N"。
- **类型一致**：`Source{cursor}`、`LogLine{ts,content}`、`takeNewLines(text,cursor)`、`sortByTs`、`prefixOf` 在 Task 3 定义、
  Task 4/5/6 按同名同参使用；`PodLogQuery.open/withPrevious` 在 Task 1 定义、Task 2 使用。
- **Review Focus 五条都落到了测试**：#1 → Task 5 Step 2 断言 4；#2 → Task 6 Step 3 断言 4；#3 → Task 3 Step 2 第 3 条；
  #4 → Task 4 Step 4 第 4 条；#5 → Task 3 Step 2 最后一条 + Task 6 Step 1 的排序口径。
- **`Produces` 的一致性**：`compareTs` 是自查时才补进 Task 3 的 —— 初稿让 Task 6 自己写了一个比较器，
  那是**同一份口径的第二份实现**（这个项目里已经因为"两处各写一份"吃过亏）。现在 `sortByTs` 与聚合视图的
  批内排序都调 `compareTs`，Task 3 的冒烟里也钉了它三条。
