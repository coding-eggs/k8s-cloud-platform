# Docker Hub 档 + 候选跨连接聚合（platform-api + platform-web）

> 任务台账 **S3.1 的增补**（2026-10-14）。S3.1 主体设计与交付见
> [2026-10-14-registry-design.md](2026-10-14-registry-design.md)（下称「S3.1 spec」）。
> 本批**改掉 S3.1 已交付的一部分**，订正清单见 §12。

**目标**：补上 Docker Hub 这一档；并让**候选跨连接聚合** —— 用户打 `ng` 时，
Harbor 里的 `nginx` 和 Docker Hub 的 `nginx` 同时出现在下拉里，右侧标出各自来自哪条连接，用户自己挑。

**为什么现在做**：S3.1 把 Docker Hub 记成了「用户裁定不做」，**那是错的**。
2026-10-12 的裁定原文是「⑥ **Docker Hub autocomplete 按本文建议接**（PAT + 缓存 + 静默降级）」
（[gap-analysis-2026-10-12.md](../../gap-analysis-2026-10-12.md) §7.5 末的裁定块），
台账 S3.1.1 原稿写的也是「**三档实现**（Harbor / 通用 registry v2 / **Docker Hub 走 PAT**）」，
而 S3.1 brainstorming 里用户批准的那个选项原文就是
`type 从 GIT|REGISTRY 扩为 GIT|HARBOR|REGISTRY_V2|DOCKERHUB`。
**唯一被当成"砍掉"依据的，是用户在回答「浏览页加不加」那道题时附的一句
「现在的平台只支持 harbor，后续可能还要支持其他类型的仓库，你考虑设计的问题」** ——
那是「现状如此、按可扩展来设计」，不是把已裁定要接的一档砍掉。
本批把它还原，并顺手修掉「把用户的一次澄清记成用户裁定」这个记账错误。

**范围**：Docker Hub 一档 + 候选跨连接聚合。**不含**通用 registry v2（`REGISTRY_V2`）——
它同样在台账三档里，但没有单独的裁定，且没有"按关键词搜仓库"的能力（§10），
本批不动它，在台账里记成欠账。

---

## 已定决策（brainstorming 结论，2026-10-14）

| 决策点 | 结论 | 理由 |
|---|---|---|
| **无 host 的写法归谁** | **不猜，全部连接都给** | `nginx` 该算 Docker Hub 还是 Harbor 里的同名仓库，是个**只能猜**的歧义。聚合把它消掉 |
| **带 host 时呢** | **仍然只问匹配上的那一条** | `test.harbor.com/ng` 归属明确，没必要也不应该再去问 Docker Hub 和其他连接 |
| **候选行右侧标什么** | **连接名** | 它是唯一**无法从候选串里推出来**的信息 —— Harbor 档的候选串自带 host，但"是哪个连接"推不出来 |
| **合并后怎么排序** | **按连接名；每条连接内部按对端返回顺序** | 完全可预测，无隐藏规则（不搞"私有优先""官方优先"这类魔法） |
| **Docker Hub 走哪个接口** | **`hub.docker.com/v2/…` 站点 API** | 见 §3.2：OCI 官方的 `registry-1.docker.io` 路径**没有搜索接口**，补全做不出来 |
| **Docker Hub 的浏览层级** | **两层**，第一层是 `library` + 你的用户名两个**固定入口** | Docker Hub 没有"列出所有命名空间"的接口，见 §3.3 |
| **Docker Hub 要不要凭据** | **必填用户名 + PAT** | 见 §3.6 —— 不给"某些类型可以不填凭据"的例外 |
| **`probe` 怎么处理** | **挪进 `RegistryDialect` 接口** | S3.1 spec §11 早就预告过这件事，现在就是"届时" |
| **权限码 / 迁移** | **零新增** | 复用 `tenant:registry:catalog`；`TYPES` 是代码常量，不动表结构 |

---

## 1. 交付边界

**做**：Docker Hub 一档（补全 / 两层浏览 / 测试连接）+ 候选跨连接聚合 + `probe` 挪进接口
+ Harbor 档新增「无 host → 全局搜索」分支 + 前端下拉显示来源连接名。

**不做**：通用 registry v2 档；浏览**任意**公共命名空间（需要输入框，§10）；
Docker Hub 匿名访问；镜像扫描 / 签名 / 清理 / 删除。

---

## 2. 候选跨连接聚合（**改 S3.1 spec §4.1 的核心契约**）

### 2.1 匹配模型

```
1. 从输入里取 host（规则不变，仍是 Docker 自己的规则：首段含 . 或 :，或恰为 localhost）

2. host 非空（全限定写法）
   a. 在 enabled 的镜像仓库连接里做 endpoint 最长前缀匹配（现有逻辑）
      + Docker Hub 的三个别名都认（§3.1）
   b. 匹配不到        → 零请求，返回空
   c. 匹配到一条      → **只问它**

3. host 为空（隐式 Docker Hub 写法：`nginx`、`nginx:1.27`、`myteam/app`）
   a. 一条 enabled 的镜像仓库连接都没有 → 零请求，返回空
   b. 否则 → **全部参与**，并发查询，结果合并
```

第 3b 步里，**某一档如果做不出关键词搜索，就由它自己的 `suggest` 返回空** ——
分派层不做「哪些档支持搜索」的判断（那会是第二个真相源，S3.1 终审刚为此删掉过一份手写的 type 清单）。

⚠️ **第 3 条是 S3.1 spec §4.1「不带 host 的写法一律返回空候选」的反转**。
S3.1 那么写是因为当时只有 Harbor 一档、无法确定该归哪条连接 —— 结论没错，但代价是
**Docker Hub 档只能在用户手打 `docker.io/` 时生效**，等于白做。聚合把这个问题从
「猜哪条」变成「都给」，歧义消失。

### 2.2 发几个请求

| 输入 | 发几个请求 |
|---|---|
| `test.harbor.com/proj/ap` | **1**（只问匹配上的那条） |
| `test.harbor.com/xx`（匹配不上任何连接） | **0** |
| `ng`（无 host） | **N**（所有 enabled 的镜像仓库连接） |
| `ng` 但一条连接都没配 | **0** |
| 空串 | **0** |

### 2.3 响应结构（**改**）

现在 `RegistrySuggestResponse` 是 `List<String>` —— 没有地方放来源。改成：

```json
{"items": [
   {"value": "nginx",                     "connectionName": "dockerhub"},
   {"value": "test.harbor.com/proj/nginx","connectionName": "harbor-prod"}
]}
```

```java
public record RegistrySuggestResponse(List<Item> items) {
    /** value = 要填进输入框的完整串；connectionName **只用于展示**。 */
    public record Item(String value, String connectionName) { }
}
```

- `value` 的语义**一个字没变**：仍是「要填进输入框的完整字符串」，前端仍然整段替换、不做任何拼接
- ⚠️ 前端 `v-for` 的 key **不能只用 `value`** —— 两条不同连接返回同一个串是可能的
  （比如两条指向同一 endpoint 的连接），会造成 Vue 的重复 key 警告。
  用 `value + "|" + connectionName`
- ⚠️ **`connectionName` 不是唯一键**：`platform_credential.name` 上没有唯一约束。
  本批只把它当展示用，不做任何按它的后续查询

### 2.4 缓存：**一个字都不用改**

key 现在就是 `registry:suggest:<连接id>:<输入前缀>` —— 天然是「每条连接一份」，
聚合并行时正好复用，而且**连接的增删不会互相失效**。TTL 仍是 2 分钟。

⚠️ 与 S3.1 一样，传的是**逻辑键**：`RedisJsonCache` 自己会加 `platform:cache:` 前缀，
这里再带一次就会变成双前缀、缓存永不命中（S3.1 踩过，见 S3.1 spec §5.4 与台账）。

### 2.5 并发与超时（**新**）

N 条连接**必须并发**。串行的话一条对端黑洞（读超时 10s）会把整条时间线拖到 10s×N。

- 走 **Java 21 虚拟线程**，每连接一个任务，一个 app 级 `ExecutorService` bean（`@PreDestroy` 关闭）
- **总等待上限 3 秒**（`invokeAll(tasks, 3, SECONDS)`）；未完成的按**空结果**算，
  记一条 WARN —— 这仍然是 S3.1 §5.3 那条「补全静默降级」的姿态，只是**逐连接**适用：
  **任何一条连接失败或被取消，都不影响其他连接的候选**
- 命中缓存时是即时的（不进出站）

⚠️ **3 秒是刻意小于单条连接的最长耗时的**：`RegistryHttpClient` 是连接 5s + 读 10s，
最坏 15s。一条黑洞连接不该让用户的补全等 15 秒 —— 它被取消、按空算，
而**其余连接的结果照常返回**。这正是"逐连接静默"要买的东西。S3.1 时只有一条连接参与，
不存在这个问题；聚合之后它是必须的。

### 2.6 硬边界（**保留**）

**出站 URL 只由凭据的 endpoint（或 dialect 自己写死的官方地址）拼。**
用户输入只用于**匹配**和**生成候选串**，绝不参与构造目标地址 ——
这条是防「给调用方一个打任意地址的口子」，聚合之后更不能松
（现在一次输入会打出去 N 个请求，地址若可控，放大效应也一并放大）。

---

## 3. `DockerHubDialect`

### 3.1 枚举值与 endpoint 的语义

`CredentialService.TYPES`：`GIT | HARBOR` → **`GIT | HARBOR | DOCKERHUB`**。

**endpoint 这一格**：切到该类型时表单**预填 `docker.io` 并置为只读**。

- 不这么做，用户会填出 `https://registry-1.docker.io/` 之类的变体，解析层就要处理一堆别名
- 解析层仍然**把 `docker.io` / `index.docker.io` / `registry-1.docker.io` 三个写法认作同一个** ——
  用户手打这三种带 host 的引用都能匹配上这条连接

⚠️ **对 DOCKERHUB 档，`endpoint` 只用于匹配，不用于出站。** 出站地址由 dialect 写死
`hub.docker.com`（Docker Hub 的 API 站点根本不是 `docker.io`）。
这不是绕过 §2.6 那条边界，**正是它的用法** —— 用户连填都填不了。

### 3.2 走哪四个接口

| 动作 | 接口 |
|---|---|
| 补全（按关键词搜仓库） | `GET https://hub.docker.com/v2/search/repositories/?query=<q>&page_size=25` |
| 浏览第一层 | `GET https://hub.docker.com/v2/repositories/<ns>/?page_size=100` |
| 浏览第二层（列 tag） | `GET https://hub.docker.com/v2/repositories/<ns>/<repo>/tags?page_size=100` |
| 测试连接 | `GET https://hub.docker.com/v2/repositories/<username>/?page_size=1` 带 Basic |

**为什么不是 OCI 官方的 `registry-1.docker.io/v2/<name>/tags/list`**（那条路更"正统"）：

1. 它**没有搜索接口** → 补全根本做不出来，而补全是这一档的主要用途
2. `tags/list` **只给 tag 名**，浏览页要的推送时间 / 大小 / digest 得逐 tag 再取一次 manifest（N+1）

分析文档 §7.5 记的那个代价（「registry 站点自身用的接口，**无官方 SLA 承诺**」）
换来的就是上面两件事。这也是 S3.1 spec §4.3 那套「必须缓存 + 静默降级」在这里**尤其**要守住的原因。

### 3.3 浏览页是**两层**，第一层是两个固定入口

Docker Hub 没有「列出所有命名空间」的接口 —— Harbor 有三层的头（列 project），Docker Hub 没有对应物。

```
path=[]  →
┌──────────────────────────────────────┐
│ library       Docker 官方镜像库   ›  │   ← 固定常量入口
│ <username>    你的命名空间        ›  │   ← 从凭据的 username 来
└──────────────────────────────────────┘

path=["library"]        →  nginx / redis / postgres / …        ›
path=["library","nginx"] →  1.27 / 1.26 / latest …（带推送时间 / 大小 / digest）
```

两个都是**已知的、可枚举的**命名空间，一层 `browse` 返回 node 就能表达。
⚠️ **前端一行都不用改** —— 它只认 `hasChildren`（能不能点进去）和 `details`（表格列头），
这正是 S3.1 spec §11 那张「不要动的」清单能兑现的地方。

**叶子节点的 `copyText` 与 §3.4 同一条规则**：`library/nginx` 的 tag 复制出来是 `nginx:1.27`
（剥掉 `library/`），`<username>/nginx` 的则是 `<username>/nginx:1.27`。
不剥的话，同一层里 `library` 和用户名两个入口下的镜像会有两套写法，用户复制到 YAML 里就要想一下哪个能用。

**做不到的**：浏览 `bitnami/` 底下有什么、或任意一个你知道名字的公共命名空间 ——
那需要一个「输入命名空间」的框，而浏览页现在没有输入框；加它就得让前端知道
「某些档需要输入」，打破 §2 那条「层级由方言定、前端不改」。
**那种需求用补全搜就够**：打 `bitnami/ng` 直接出 `bitnami/nginx`。

### 3.4 候选串的拼法：保持用户已经打的深度

| 用户打 | 候选 |
|---|---|
| `ng` | `nginx`、`myteam/nginx-agent` |
| `docker.io/ng` | `docker.io/nginx`、`docker.io/myteam/nginx-agent` |
| `docker.io/library/ng` | `docker.io/library/nginx` |
| `nginx:1.` | `nginx:1.27` |

规则两条：

1. **用户打了 host 前缀就带上**（`docker.io/`），没打就不带 —— 不把用户的隐式写法改写成显式
2. **无 host 输入时剥掉 `library/`** —— `nginx` 与 `library/nginx` 是同一个镜像，
  填短的更常见；用户显式打了 `library/` 就保留

### 3.5 tag 补全的单段歧义

`nginx:1.` 里的 `nginx` 该算 `library/nginx`（Docker 的字面语义）还是 `<username>/nginx`？

**定**：**先按字面语义查 `library/nginx`，取不到再查 `<username>/nginx`。**
第二次请求只在第一次真取不到时才发。输入自带命名空间时（`myteam/app:1.`）直接用那一段，不猜。

理由：`nginx:1.27` 的字面语义就是官方库；但用户自己私有仓库里同名的镜像也确实常见，
直接放弃会让这一档在"私有镜像打 tag"这个场景下失效。

### 3.6 我定的两条（本批不做例外）

1. **Docker Hub 档仍然必填用户名 + PAT**，不引入「某些类型可以不填凭据」的例外。
   分析文档 §7.5 的前提 1 就是「匿名按 IP 限流、极易 429」，这一档的价值正来自配 PAT。
   不给例外意味着**表单 / 校验 / `resolveSecret` / 出站层的 Basic 头，一处分支都不用加**。
2. **零新增权限码、零新增迁移**。复用 `tenant:registry:catalog`（三个只读端点共用）；
   `TYPES` 是代码常量，`platform_credential.type` 不动表结构
   （S3.1 的迁移文件注释里已写过这件事）。

---

## 4. `probe` 挪进 `RegistryDialect` 接口

S3.1 spec §11 预告过「将来若某一档的测试连接方式与 `/v2/` 不同，届时要把 probe 也挪进接口 ——
现在有意不预加」。**Docker Hub 就是那个"届时"**：它的 `endpoint` 是 `docker.io`，
`https://docker.io/v2/` 根本不是它的 API。

```java
/** 测试连接的规格。**404 的含义各档不同**，所以文案也由各档给。 */
public record ProbeSpec(String url, String notFoundMessage) { }

ProbeSpec probeSpec(CredentialRef cred);
```

配套三处：

1. **`CredentialProbe.registryProbeUrl` 从 `credential` 包移走** —— `/v2/` 的拼法进 `HarborDialect`
   （它本来就是 Harbor 形状的假设）。per-包依赖方向仍是单向的：`credential` → `registry`
2. **`CredentialProbeClient.probeRegistry(endpoint, …)` 签名改成 `probeRegistry(url, …)`** ——
   出站那四条安全要求（`Redirect.NEVER`、连接/读超时、`URI.create` 格式错归因、
   网络不可达文案）**一条不动**
3. **`classifyRegistry(status)` 保持共用，但 404 那句文案改由 dialect 给** ——
   对 Harbor 是「地址不对：该地址不是 registry（`/v2/` 不存在）」，
   对 Docker Hub 是「该命名空间不存在或不可见」。同一句写死会把管理员**引去查一个没问题的地址**

`CredentialService.test()` 的分支从 `if ("GIT") … else registry` 改成
「GIT 走 git；否则**问分派表**要 `probeSpec`」—— 与 §3.1 一致，不再有第二份 type 清单。

---

## 5. Harbor 档新增分支：无 host → 全局搜索

`GET /api/v2.0/search?q=<关键词>` → 命中的 `repository[].repository_name` 形如 `proj/nginx`
→ 候选串 = `test.harbor.com/proj/nginx`（**必须带 host** —— Harbor 引用不带 host 没有意义）。

**触发条件**：输入**不以任何 endpoint 开头**。带 host 的输入走原来的路径补 repo / 补 tag，**一行不改**。

⚠️ 这一条不新增接口，但它是**一整条新分支**，不是白捡的 —— 这是 §2 那条聚合的代价之一。
它在 `segmentsOf` 的 `startsWith` 判断之外，需要一个新的关键词提取规则
（输入 `ng` → `q=ng`；输入 `myteam/app` → `q=app`，取最后一段）。

---

## 6. 前端

| 位置 | 改动 |
|---|---|
| `types.ts` | `RegistrySuggestItem { value: string; connectionName: string }`；`RegistrySuggestResponse.items` 类型跟着改 |
| `composables/useImageSuggest.ts` | 从 `List<String>` 改成传对象数组；三条规矩（无权限不发请求 / 失败静默 / 选中整段替换）**不变** |
| `WorkloadView.vue`「更新镜像」、`ContainerEditor.vue` | `el-autocomplete` 用 `#default` scoped slot 渲染右侧连接名；`v-for` 的 key 用 `value + "\|" + connectionName` |
| `CredentialView.vue` | `TYPE_OPTIONS` 加「Docker Hub 镜像仓库」；切到该类型时 **endpoint 预填 `docker.io` 并置为只读** |
| 浏览页 `RegistryView.vue` | **零改动** |

`usePermission` 短路、`silent403`、防抖、复制兜底（`writeClipboard` 的 `execCommand` 分支）全部沿用。

---

## 7. 权限与迁移

**两个都是零**：

- 权限码：复用 `tenant:registry:catalog`（API，覆盖 suggest / browse / connections）+ `tenant:page:registry`（Page）
- 迁移：**本批没有任何 SQL**。`TYPES` 是代码常量，`platform_credential` 表结构不变

⚠️ 但**部署顺序有依赖**：新构建起来之前，库里若已有 `type='REGISTRY'` 的行（S3.1 之前建的），
它会被判成非法类型 —— S3.1 的迁移注释里已记过这件事，本批不重复处理。

---

## 8. 审计：**仍然都不记**

S3.1 spec §8 的决定原样有效：补全（现在每按键 N 个出站请求）与浏览都是**高频只读**，
逐次写审计等于拿一个高频动作淹掉审计表。

「使用凭据要留痕」的落点仍然是**真正产生副作用的动作**（S3.5 注入构建 Pod、将来的 imagePullSecret），
不是这里的候选查询。这条写在这里是为了避免下次评审把它当缺口。

---

## 9. 测试

| 层 | 测什么 |
|---|---|
| **聚合解析** | 带 host 命中一条 → 只问一条；带 host 匹配不上 → **零请求**（断言 mock 未被调用）；无 host → 问全部；无 host 且无连接 → 零请求；Docker Hub 三个别名都能匹配上同一条连接 |
| **合并与排序** | 多条结果合并；按连接名排序；**某一条抛异常/超时时其余候选照样返回**；总等待上限生效（一条挂住不拖垮整体） |
| **`DockerHubDialect` 纯函数** | 四个 URL 的拼法；候选串剥 `library/` 的规则（带 host 不剥、无 host 剥）；browse 第一层的两个固定入口；tag 歧义的两次查询顺序 |
| **出站层** | `MockRestServiceServer` 钉 URL + `Authorization` 头；重定向 NEVER 沿用 S3.1 已有的本地 `HttpServer` 用例 |
| **`probe` 挪接口** | Harbor 档的 `probeSpec.url` 仍是 `<endpoint>/v2/`；Docker Hub 的是 `hub.docker.com/.../<username>/`；**两档的 404 文案不同**；`CredentialService.test()` 对 DOCKERHUB 类型不再走 `/v2/` 分支 |
| **护栏** | `CredentialService.TYPES` 里每个值都有 `RegistryDialect` 实现（S3.1 已有，**会自动覆盖 DOCKERHUB**）—— 这条是"加档忘实现"的兜底，本批应当看到它从绿变绿 |
| **窄投影** | `/registry/connections` 的响应仍**不含**凭据字段（S3.1 的反射钉子，不受本批影响） |

---

## 10. 有意不做 / 已知缺口（**显式记录，勿当新问题上报**）

| 项 | 为什么不做 |
|---|---|
| **浏览任意公共命名空间**（`bitnami/`、任意 `xxx/`） | 需要浏览页有输入框；加它就让前端知道「某些档需要输入」，打破「层级由方言定」。用补全搜替代（§3.3） |
| **通用 registry v2 档（`REGISTRY_V2`）** | 台账三档里的第三个，但**没有单独裁定**，且它同样没有搜索接口 —— 补全做不出来。**记为欠账，本批不动** |
| **Docker Hub 匿名访问** | §3.6：匿名按 IP 限流、极易 429 |
| **官方 OCI 路径（`registry-1.docker.io`）** | 无搜索接口 → 补全做不出来（§3.2） |
| **镜像扫描 / 签名 / 清理 / 删除** | 不在台账 S3.1 范围 |
| **候选结果的条数上限与分页** | 沿用 `page_size=100`（S3.1 的既有缺口，本批不扩大也不修） |

### 10.1 已知风险（接受）

**一次输入会向 N 条连接各发一次请求。** N = 已启用的镜像仓库连接数。
S3.1 的「匹配不到就不发请求」正是用来防这个放大的，改成聚合后无 host 时必然全发。
靠 2 分钟缓存兜大头（同一前缀在 TTL 内不重复出站），但**每个新前缀的第一击都是 N 个并发请求**。
配 10 条连接时，用户每打一个新前缀就会打出 10 个请求。

这条风险与 §10.1 在 S3.1 里记的那条（租户能看到所有已配 registry 的目录）叠加：
**新增的是请求放大，不是新的数据暴露** —— 候选里仍不含任何凭据内容。

---

## 11. 待实测（**部署后把结论补进本表**，与 S0.2 spec §0 同样的做法）

按分析文档 §7.5 的结论设计，**以下三条没有实测过**：

- [ ] `hub.docker.com/v2/…` 是否**直接接受 Basic 认证**，还是必须先
      `POST /v2/users/login` 换 JWT、再用 `Authorization: JWT <token>`。
      （只影响 `RegistryHttpClient` 的认证头构造，不影响任何 URL 与解析规则）
- [ ] **匿名 429 的实际触发阈值** —— 决定 §2.4 的 TTL 是否够（现在是 2 分钟）
- [ ] search 返回里 **`repo_name` 的确切形状**（带不带 `library/` 前缀）——
      §3.4 的剥前缀规则依赖它

⚠️ **实测前不要改 §3.4 的规则** —— 它按"`repo_name` 是完整的 `ns/repo`" 设计，
若实测发现不是，改的是解析函数，不是契约。

---

## 12. 对 S3.1 spec 的订正清单（**与本批同批提交**）

不订正的话两份 spec 会互相矛盾。逐处：

| S3.1 spec 位置 | 订正 |
|---|---|
| 顶部「**范围**：Harbor 一档」 | 「Harbor + Docker Hub 两档」，并指向本文件 |
| §2.1 接口 | 新增 `probeSpec`；说明 `suggest` 的**语义变了**（从"整串归一条连接"变成"可能被多条连接并发调用"） |
| §2.2 护栏测试 | 补一句：Docker Hub 加入后该测试自动覆盖新值 |
| §4.1 解析与分派 | **整节重写**为本文件 §2.1 —— 这条契约被反转了 |
| §4.2 两条硬边界 | 第一条（出站 URL 只由 endpoint 拼）**保留并加强**；第二条从「匹配不到不发请求」改成 §2.2 那张表 |
| §4.3 缓存 + 静默降级 | 补 §2.5 的并发与总等待上限；静默降级改为**逐连接**适用 |
| §5.3 姿态差异 | 不变（suggest 静默 / browse 报错），补一句"逐连接静默" |
| §10 有意不做 | **删掉两行**：「`REGISTRY_V2` / `DOCKERHUB` 两档」与「无 host 的镜像写法」；`REGISTRY_V2` 单列成欠账 |
| §11 加档清单 | `probe` 那条从「将来要挪」改成「**已挪进接口**（见本文件 §4）」；Docker Hub 作为已落档的实例 |
| §12.1 部署后待验清单 | 补 Docker Hub 项：建连接 / 打 `ng` 出两类候选 / 右侧连接名 / 浏览页两个固定入口 / 未配 host 的写法仍可自由输入 |
| 台账 `task-ledger-2026-10-12.md` S3.1 条目 | 「② S3.1.1 只落 Harbor 一档…（用户：…）」这行**是记账错误**，改为「Docker Hub 于 2026-10-14 补做（见 dockerhub spec）；`REGISTRY_V2` 记为欠账」 |

---

## 13. 部署

**本批没有 SQL、没有新增配置项。**

1. 先起新构建（若 S3.1 尚未部署，**两批一起上**：先重放 `V2026_10_14_1__registry_permissions.sql`，再起构建）
2. 凭据页建一条 `Docker Hub 镜像仓库` 类型的连接（endpoint 会自动预填 `docker.io` 且只读），
   用户名填 Docker Hub 账号名，密码填 **PAT**
3. 点「测试连接」—— 这一步同时验证 §11 的第一条待实测项
4. 重放权限相关迁移后重新登录，或等权限闭包缓存的 30s TTL

### 13.1 部署后待验清单

- [ ] 凭据页能建出 `Docker Hub 镜像仓库` 类型的连接，endpoint 框预填 `docker.io` 且**改不动**
- [ ] 「更新镜像」输入框里打 `ng` → 下拉里**同时**出现 Docker Hub 的候选和已配 Harbor 的候选，
      每行右侧是**连接名**，整体**按连接名排序**
- [ ] 打 `docker.io/ng` → **只有** Docker Hub 那条的候选（Harbor 不参与，因为 host 已明确）
- [ ] 打 `test.harbor.com/proj/ap` → 只有 Harbor 那条，行为与 S3.1 交付时**完全一致**（回归）
- [ ] 输入一个**没配过的** host（`other.example.com/ng`）→ 无候选、无报错，且能自由输入
- [ ] 浏览页选中 Docker Hub 连接 → 第一层是 `library` 和你的用户名两个入口 → 点进去能列仓库 → 再点进去能列 tag（带推送时间/大小/digest）
- [ ] 把 Docker Hub 连接**停用** → 它的候选和浏览入口都消失，Harbor 的候选不受影响
- [ ] 一条连接不可达时，**其余连接的候选照样出现**（不是整个下拉空掉）
- [ ] 审计页确认：补全与浏览**没有**产生记录（§8 的有意行为）
