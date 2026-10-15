
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
| **Docker Hub 要不要凭据** | ~~只允许匿名，封死 PAT~~ → **2026-10-15 改定：允许 BASIC（用户名 + PAT）** | 原裁定（用户，2026-10-14）：这一档的角色就是「公共镜像的来源」，私有镜像该放自建 Harbor。**用户于 2026-10-15 撤销**，见 §3.8 |
| **「哪档允许哪种认证」由谁声明** | **由各档自己声明**（`RegistryDialect.authTypes()`） | 否则就是又一份手写的 type 清单 —— S3.1 终审刚为此删掉过一份。见 §3.7 |
| **`authType` 的取值域** | **`BASIC` → `BASIC \| NONE`**（`NONE` = 匿名） | 匿名不是 Docker Hub 独有的（公共 Harbor project、公共 git 仓库都是）。⚠️ **这改的是 S0.2 已交付的东西** |
| **`probe` 怎么处理** | **挪进 `RegistryDialect` 接口** | S3.1 spec §11 早就预告过这件事，现在就是"届时" |
| **权限码 / 迁移** | **零新增** | 复用 `tenant:registry:catalog`；`TYPES` 是代码常量，不动表结构 |

---

## 1. 交付边界

**做**：Docker Hub 一档（补全 / 两层浏览 / 测试连接）+ 候选跨连接聚合 + `probe` 挪进接口
+ Harbor 档新增「无 host → 全局搜索」分支 + 前端下拉显示来源连接名
+ **`authType` 支持 `NONE`（匿名）并修掉 S0.2 那条「填了凭据就清不掉」的死路**。

**不做**：通用 registry v2 档；浏览**任意**公共命名空间（需要输入框，§10）；
镜像扫描 / 签名 / 清理 / 删除。
⚠️ **Docker Hub 的私有仓库**原本列在这里（封死 PAT 的直接后果），**2026-10-15 已做**，见 §3.8。

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

**用户名 / 密码这两个框直接不显示**（不是"可选"，是隐藏）—— 该档只允许匿名（§3.6）。
表单本来就有按类型变形的行为（endpoint 预填只读），这是同一条路上的一件事。

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
| 测试连接 | `GET https://hub.docker.com/v2/repositories/library/?page_size=1`（**匿名**） |

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
└──────────────────────────────────────┘

path=["library"]        →  nginx / redis / postgres / …        ›
path=["library","nginx"] →  1.27 / 1.26 / latest …（带推送时间 / 大小 / digest）
```

⚠️ **只有一个入口，不是两个。** 初稿写的是 `library` + 「你的用户名」，那是假定这一档会带
username（用 PAT 登录时才有）。**封死 PAT 之后没有 username 可用了**，所以只剩 `library`
—— 这也正好符合这一档的定位：它是公共镜像的来源。

`library` 是**已知的、可枚举的**命名空间，一层 `browse` 返回 node 就能表达。
⚠️ **前端一行都不用改** —— 它只认 `hasChildren`（能不能点进去）和 `details`（表格列头），
这正是 S3.1 spec §11 那张「不要动的」清单能兑现的地方。

**叶子节点的 `copyText` 与 §3.4 同一条规则**：`library/nginx` 的 tag 复制出来是 `nginx:1.27`
（剥掉 `library/`）—— 与用户在输入框里手打的写法一致，复制进 YAML 直接能用。
不剥的话会变成 `library/nginx:1.27`，虽然也拉得动，但和平台其他地方的写法对不上。

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

### 3.5 tag 补全的解析（封死 PAT 之后简化了）

- 输入自带命名空间（`bitnami/nginx:1.`）→ 直接查 `bitnami/nginx` 的 tag
- 输入是单段（`nginx:1.`）→ 按 Docker 的字面语义查 **`library/nginx`**

⚠️ 初稿在这里写了个"取不到再回退到 `<username>/nginx`"的两跳规则，**那是给带 PAT 的场景设计的**。
封死 PAT 之后没有 username 可回退，这条规则连同它的第二次请求一起消失。
代价是**搜不到用户名下的私有镜像** —— 这正是 §10 记下的那条有意不做。

> **2026-10-15 回摆**：私有 Docker Hub 回来了（§3.8）。那条"两跳"**仍然不需要**显式实现 ——
> 因为内置档与凭据行是两个**独立来源**，各被问一次：内置档答 `library/…`（公共），
> 凭据行答 `<username>/…`（我名下）。聚合把它们并起来，正是当年那条 fallback 想要的结果。

### 3.6 Docker Hub 档只允许匿名（用户裁定）

**这一档不接受任何凭据**：表单里没有用户名/密码框，`authType` 只能是 `NONE`。
后端在 create / update 时校验这一点 —— 该类型带了凭据就**报错**，不静默丢弃
（静默丢弃会让管理员以为自己配上了 PAT，然后在发现搜不到私有仓库时找不到原因）。

**理由（用户的）**：Docker Hub 在这一平台里的角色就是**公共镜像的来源**；
私有镜像该放自建 Harbor（S3.1 已经接好了）。补全只是让这件事用起来方便，不该先要求一个账号。

⚠️ **我原来写的「必须填 PAT」的理由站不住，记在这里免得下次有人照抄**：
分析文档 §7.5 的前提 1 说的是「匿名按 IP 限流、极易 429」——
那个额度限制主要是**拉取（pull）**的，不是 API 搜索的；而且 §2.4 本来就有 2 分钟缓存。
一个平台实例的搜索量，匿名额度远远够。

**代价（写清楚，别当成 bug 上报）**：搜不到、也浏览不了自己账号下的私有 Docker Hub 仓库。

### 3.7 `authType` 支持 `NONE`（匿名）—— **这动的是 S0.2**

**匿名不是 Docker Hub 独有的**：公共 Harbor project、公共 git 仓库都是真实场景。
所以**不**引入「哪些类型可以不填凭据」的分支，而是把 `authType` 的取值域
从 `BASIC` 扩成 **`BASIC | NONE`**（`NONE` = 匿名）—— 与「加一档只动一处」是同一条判断。

**「哪档允许哪种认证」由各档自己声明**：

```java
/** 本档允许的认证方式。NONE = 匿名。 */
Set<String> authTypes();
```

| 档 | `authTypes()` |
|---|---|
| `HarborDialect` | `{BASIC, NONE}` |
| `DockerHubDialect` | `{NONE}` |
| **GIT** | `{BASIC, NONE}` —— 但 **GIT 不是 dialect**（它不是镜像仓库），这个集合是 `CredentialService` 里的一个常量 |

GIT 是**全平台唯一一处「类型的特性不在 dialect 里」的例外**，会写在注释里。
不这么放的话，`CredentialService` 里就会出现一份手写的「哪个类型能匿名」的清单 ——
**那正是 S3.1 终审删掉 `RegistryService.REGISTRY_TYPES` 时反对的东西**。

**要改的六处**（都在 S0.2 的交付面上）：

| 位置 | 现在 | 改成 |
|---|---|---|
| `CredentialService.create` | 用户名与 secret 必填 | `authType=NONE` 时两者**必须为空**；`BASIC` 时仍必填 |
| `CredentialService.update` | 「留空 = 保持不变」+「authType 变更必须重填」 | 同上；且 **`authType` 改成 `NONE` 即清空凭据** —— 修掉现在「填了就再也清不掉」的死路 |
| `CredentialProbeClient` | 无条件发 `Authorization: Basic …` | 无凭据时**不发这个头**（现在会发 `Basic Og==`，一个只有冒号的空 Basic） |
| `RegistryHttpClient` | 同上 | 同上 |
| `CredentialView.hasSecret` | 恒为 true | 匿名行自然为 `false`（字段本来就有，S0.2 建它时应该就是这个用途） |
| `CredentialService.test` | 无条件按 Basic 探测 | 见下面那条 401 规则 |

**⚠️ 匿名时 401 该怎么读**：OCI 规范下，任何 registry 的 `GET /v2/` **未认证时返回 401 是正常行为**
（Harbor 也一样 —— 它是「这是个 registry，且它要认证」，不是「你的凭据错了」）。
所以**匿名的 registry 探测里，401 判为通过**；`BASIC` 档的 401 仍然是「凭据不通过」。
这条若实测不符，按实测改（§11）。

**零迁移**：`platform_credential.auth_type` 列已经存在。

---

### 3.8 ⚠️ 2026-10-15 改定：**私有 Docker Hub 回来了**（撤销 §3.6）

**用户裁定**：Docker Hub 私有仓库要能**配**（用户名 + PAT），于是 §3.6「只允许匿名、封死 PAT」
被撤销，`DOCKERHUB` 这个 type 回到 `CredentialService.TYPES`。

**共存规则（用户明确：内置的永久保留）**：

| 来源 | 是谁 | 答什么 |
|---|---|---|
| **内置档**（`BuiltinRegistry`，合成 id `builtin:dockerhub`） | 没有行、没有凭据 | **公共镜像**：搜索 + 浏览 `library` |
| **凭据行**（`type=DOCKERHUB`，BASIC） | 管理员在凭据页建的一条连接 | **只答"我名下"**：`<username>/…` 的仓库与 tag（含私有）；浏览第一层多一个你自己命名空间的入口 |

两者在同一个下拉里**并存**（`RegistryService.isRegistry` 不再排掉内置档的 type）——
它们答的东西不同，所以不是重复而是各答一半。判据是**这条 ref 有没有凭据**，不新增开关。

**认证走哪条 —— 实测证据（2026-10-15）**：

```
POST https://hub.docker.com/v2/users/login/   {"username":…,"password":…}  → {"token":"…"}
GET  …/v2/repositories/<ns>/  + Basic  ← **被忽略**（一对错误凭据照样 200，等于匿名）
GET  …/v2/repositories/<ns>/  + Bearer bogus → **401**
```

→ 站点 API **只认登录换来的 JWT**。所以：方言先登录拿 token（**按用户名缓存 20 分钟**，
401 时丢掉缓存重换），之后每个请求带 `Authorization: Bearer …`。
⚠️ 这是一次**必须**的额外往返，站点接口仍无 SLA（同 §3.2 那条既有风险）。
`RegistryHttpClient` 因此多了 `getWithBearer` 与 `postJson`（后者**不带** Authorization —— 凭据在 body 里）。

**端点是恒定的**：三个别名（`docker.io` / `index.docker.io` / `registry-1.docker.io`）统一存成
`docker.io`，填别的**直接拒**（`RegistryDialect.normalizeEndpoint`，规则归方言声明）。
理由同 §3.1 那条：出站的两个站点由方言写死，与 endpoint 无关。

**「两跳」不需要**：内置档答 `library/…`、凭据行答 `<username>/…`，聚合并起来就是那个效果（见 §3.5 的回摆注）。

**作为凭据行只接受 `BASIC`**（`DockerHubDialect.authTypes()`）：匿名那一份是内置档，
凭据行要是也匿名，下拉里就会多出一个一模一样、候选完全重复的来源。

**代价如实记着**：管理员为私有仓库配了一条 PAT，那么**平台出站**就用它去问 Docker Hub ——
这条 PAT 能看到的私有仓库，任何能用镜像补全的租户都能看到名字（不是内容）。
这正是 §5.3「凭证授权」那条边界的形状（同 git 连接），也是为什么这一档要挂在**平台级**的凭据页上。

**前端**：类型下拉多一项「Docker Hub（私有仓库）」，选中后地址锁定 `docker.io`、认证方式只剩 BASIC
（用户名填 Docker ID，令牌填 PAT）。

## 4. `probe` 挪进 `RegistryDialect` 接口

S3.1 spec §11 预告过「将来若某一档的测试连接方式与 `/v2/` 不同，届时要把 probe 也挪进接口 ——
现在有意不预加」。**Docker Hub 就是那个"届时"**：它的 `endpoint` 是 `docker.io`，
`https://docker.io/v2/` 根本不是它的 API。

```java
/** 测试连接的规格。**404 的含义各档不同**，所以文案也由各档给。 */
public record ProbeSpec(String url, String notFoundMessage) { }

ProbeSpec probeSpec(CredentialRef cred);

/** 本档允许的认证方式。NONE = 匿名。见 §3.7。 */
Set<String> authTypes();
```

配套三处：

1. **`CredentialProbe.registryProbeUrl` 从 `credential` 包移走** —— `/v2/` 的拼法进 `HarborDialect`
   （它本来就是 Harbor 形状的假设）。per-包依赖方向仍是单向的：`credential` → `registry`
2. **`CredentialProbeClient.probeRegistry(endpoint, …)` 签名改成 `probeRegistry(url, …)`** ——
   出站那四条安全要求（`Redirect.NEVER`、连接/读超时、`URI.create` 格式错归因、
   网络不可达文案）**一条不动**
3. **`classifyRegistry(status)` 保持共用，但两处判读要改由 dialect / authType 给**：
   - **404 的文案**：对 Harbor 是「地址不对：该地址不是 registry（`/v2/` 不存在）」，
     对 Docker Hub 是「Docker Hub 公共 API 返回 404（请求可能被网络中间件改写了）」。
     同一句写死会把管理员**引去查一个没问题的地址**
   - **401 的读法按该连接的 `authType` 分（不是按档）**：`BASIC` 档仍是「凭据不通过」；
     **`NONE` 档的 `/v2/` 返 401 是 OCI 规范的正常行为，判为通过**（§3.7）

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
| `types.ts` | `RegistrySuggestItem { value: string; connectionName: string }`；`RegistrySuggestResponse.items` 类型跟着改；凭据的 `type` 加 `'DOCKERHUB'`、`authType` 从 `'BASIC'` 扩成 `'BASIC' \| 'NONE'` |
| `composables/useImageSuggest.ts` | 从 `List<String>` 改成传对象数组；三条规矩（无权限不发请求 / 失败静默 / 选中整段替换）**不变** |
| `WorkloadView.vue`「更新镜像」、`ContainerEditor.vue` | `el-autocomplete` 用 `#default` scoped slot 渲染右侧连接名；`v-for` 的 key 用 `value + "\|" + connectionName` |
| `CredentialView.vue` | ① `TYPE_OPTIONS` 加「Docker Hub 镜像仓库」；② 加一个「认证方式」下拉（`用户名密码` / `匿名`）—— **`authType` 这个字段本来就在 DTO 里**，前端此前只是从没发过非 BASIC 的值；③ 切到 Docker Hub 时 **endpoint 预填 `docker.io` 只读、认证方式与凭据框整组隐藏**（该档只有一个选项，给控件是噪音） |
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
| **`DockerHubDialect` 纯函数** | 四个 URL 的拼法；候选串剥 `library/` 的规则（带 host 不剥、无 host 剥）；browse 第一层**只有 `library` 一个**入口；`authTypes()` 恰好是 `{NONE}` |
| **`authType = NONE`（动 S0.2）** | create：`NONE` + 带凭据 → **报错**（不静默丢弃）；`BASIC` + 缺凭据 → 报错；update：`authType` 改成 `NONE` 能**清空**（钉住那条死路已修）；Docker Hub 类型带凭据 → 报错 |
| **匿名的出站** | `MockRestServiceServer` 断言**请求里没有 `Authorization` 头**（现在会发 `Basic Og==`）；`BASIC` 档仍然有 |
| **匿名的 401 判读** | `NONE` 档 `/v2/` 返 401 → **通过**；`BASIC` 档返 401 → **不通过**（两条各钉一次，§3.7 / §4） |
| **出站层** | `MockRestServiceServer` 钉 URL；重定向 NEVER 沿用 S3.1 已有的本地 `HttpServer` 用例 |
| **`probe` 挪接口** | Harbor 档的 `probeSpec.url` 仍是 `<endpoint>/v2/`；Docker Hub 的是 `hub.docker.com/v2/repositories/library/`；**两档的 404 文案不同**；`CredentialService.test()` 对 DOCKERHUB 类型不再走 `/v2/` 分支 |
| **护栏** | ① `CredentialService.TYPES` 里每个值都有 `RegistryDialect` 实现（S3.1 已有，**会自动覆盖 DOCKERHUB**）—— "加档忘实现"的兜底；② 每个 `RegistryDialect.authTypes()` **非空** |
| **窄投影** | `/registry/connections` 的响应仍**不含**凭据字段（S3.1 的反射钉子，不受本批影响） |

---

## 10. 有意不做 / 已知缺口（**显式记录，勿当新问题上报**）

| 项 | 为什么不做 |
|---|---|
| **浏览任意公共命名空间**（`bitnami/`、任意 `xxx/`） | 需要浏览页有输入框；加它就让前端知道「某些档需要输入」，打破「层级由方言定」。用补全搜替代（§3.3） |
| **通用 registry v2 档（`REGISTRY_V2`）** | 台账三档里的第三个，但**没有单独裁定**，且它同样没有搜索接口 —— 补全做不出来。**记为欠账，本批不动** |
| ~~**Docker Hub 的私有仓库（PAT）**~~ | ⚠️ **2026-10-15 撤销**：用户改主意了，私有 Docker Hub 已实现（§3.8）。这一行保留是为了让后来者看到"它曾经是个有意不做的缺口"，别再当成新缺口的理由 |
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

- [ ] **匿名下 `hub.docker.com/v2/…` 的实际可达性与响应形状** ——
      现在**全是匿名请求**，初稿那条「Basic 还是 JWT」随 §3.6 封死 PAT 一起作废
- [ ] **匿名 429 的实际触发阈值** —— 决定 §2.4 的 TTL 是否够（现在是 2 分钟）
- [ ] search 返回里 **`repo_name` 的确切形状**（带不带 `library/` 前缀）——
      §3.4 的剥前缀规则依赖它
- [ ] **Harbor 的 `GET /v2/` 匿名时是否返 401** —— §3.7 与 §4 那条「匿名时 401 判为通过」
      依赖它。若实测发现 Harbor 对匿名返 200，这条规则要改成「401 与 200 都算通过」

⚠️ **实测前不要改 §3.4 的规则** —— 它按"`repo_name` 是完整的 `ns/repo`" 设计，
若实测发现不是，改的是解析函数，不是契约。

---

## 12. 对 S3.1 spec 的订正清单（**与本批同批提交**）

不订正的话**三份 spec 会互相矛盾**（本份 / S3.1 / S0.2）。逐处：

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
| **S0.2 spec** 的认证方式（`authType`、v1 只有 BASIC） | 取值域扩成 `BASIC \| NONE`；补两段：「哪种认证由各类型的 `authTypes()` 声明」、「匿名时 `/v2/` 的 401 判为通过」 |
| **S0.2 spec** 的测试连接（探测 URL 与结果判定） | 补匿名分支：**不发 `Authorization` 头**；匿名 401 判通过 |
| **S0.2 spec** 的「永不回显」 | 补一句：匿名行的 `hasSecret = false`（字段本来就有，本批才真正用上） |
| 台账 `task-ledger-2026-10-12.md` **S0.2 条目** | 补一句「2026-10-14 扩 `authType` 支持匿名；顺带修掉『填了凭据就再也清不掉』的死路」 |
| 台账 `task-ledger-2026-10-12.md` **S3.1 条目** | 「② S3.1.1 只落 Harbor 一档…（用户：…）」这行**是记账错误**，改为「Docker Hub 于 2026-10-14 补做（见 dockerhub spec）；`REGISTRY_V2` 记为欠账」 |

---

## 13. 部署

**本批没有 SQL、没有新增配置项。**

1. 先起新构建（若 S3.1 尚未部署，**两批一起上**：先重放 `V2026_10_14_1__registry_permissions.sql`，再起构建）
2. 凭据页建一条 `Docker Hub 镜像仓库` 类型的连接 —— **一个字段都不用填**
   （名称必填；endpoint 自动预填 `docker.io` 且只读；认证方式与凭据框整组不出现）
3. 点「测试连接」—— 这一步同时验证 §11 的头两条待实测项（匿名可达性）
4. 重放权限相关迁移后重新登录，或等权限闭包缓存的 30s TTL

### 13.1 部署后待验清单

- [ ] 凭据页能建出 `Docker Hub 镜像仓库` 类型的连接，**除名称外一个字段都不用填** ——
      endpoint 框预填 `docker.io` 且**改不动**，认证方式与用户名/密码**整组不出现**
- [ ] 「更新镜像」输入框里打 `ng` → 下拉里**同时**出现 Docker Hub 的候选和已配 Harbor 的候选，
      每行右侧是**连接名**，整体**按连接名排序**
- [ ] 打 `docker.io/ng` → **只有** Docker Hub 那条的候选（Harbor 不参与，因为 host 已明确）
- [ ] 打 `test.harbor.com/proj/ap` → 只有 Harbor 那条，行为与 S3.1 交付时**完全一致**（回归）
- [ ] 输入一个**没配过的** host（`other.example.com/ng`）→ 无候选、无报错，且能自由输入
- [ ] 浏览页选中 Docker Hub 连接 → 第一层**只有 `library` 一个入口** → 点进去能列仓库 →
      再点进去能列 tag（带推送时间/大小/digest）
- [ ] 把 Docker Hub 连接**停用** → 它的候选和浏览入口都消失，Harbor 的候选不受影响
- [ ] 一条连接不可达时，**其余连接的候选照样出现**（不是整个下拉空掉）
- [ ] 审计页确认：补全与浏览**没有**产生记录（§8 的有意行为）

**匿名的回归项**（这批动了 S0.2，所以单独列）：

- [ ] 新建一条 **HARBOR** 连接、认证方式选「匿名」→ 保存后列表里 `hasSecret` 显示为「未配置」
- [ ] 对它点「测试连接」→ `/v2/` 返 401 时**判为通过**（不是「凭据不通过」，§3.7）
- [ ] 把一条**已有凭据**的连接改用「匿名」→ 凭据被清空（**钉住那条已修的死路**），
      再改回「用户名密码」→ 提示必须重填
- [ ] 建一条 Docker Hub 连接时**带上**用户名/密码（绕过前端直接打接口）→ **报错**，不静默丢弃

---

## 14. 2026-10-14 修订：Docker Hub 改成**内置档**（不再是一条连接）

> 本节记录一次**上线前**的改动，它推翻了本文件 §3.1 / §3.6 与 §12 的一部分。

**改动**：Docker Hub 不再需要管理员建连接。它是一个常量
（`BuiltinRegistry.DOCKER_HUB`：`id=builtin:dockerhub` / `name=Docker Hub` /
`type=DOCKERHUB` / `endpoint=docker.io`），**不进 `platform_credential`** ——
没有行、没有 authType、没有密文、没有启停状态。

**为什么**：用户裁定「内置隐式，不要记录」。原设计（§3.6）要求管理员建一条匿名连接、
只填名称 —— 这个摩擦在一次真实排查里立刻显形：用户为 Docker Hub 配好了代理，
却仍然没有候选，因为**一条连接记录都没有**，`RegistryService.suggest` 在第一个 guard
就返回了、**一个出站请求都没发**；而那个 guard 当时是**静默**的，日志里一片安静，
只能靠查库才定得了性。

**怎么落地（零特例传染）**：新增 `RegistryService.allSources()` ——
**「有哪些来源」的唯一真相源**（内置档 + 凭据行）。内置与凭据的分叉只在那里发生一次，
下游（聚合 / 并发 / 排序 / 候选带来源名 / 浏览）只认新引入的 `Source` 形状，不需要知道
有「内置」这回事。散成两处就是第二个真相源（S3.1 终审删掉 `REGISTRY_TYPES` 时反对的形状）。

### 14.1 被推翻 / 订正的地方

| 本文件原来的写法 | 现在 |
|---|---|
| §3.1「切到该类型时表单预填 `docker.io` 并置为只读、认证方式与凭据框整组隐藏」 | **凭据页没有这个类型了**（`CredentialService.TYPES` 去掉 `DOCKERHUB`）。刚交付的那两处前端已回退 |
| §3.6「该类型带了凭据就报错」 | 同上 —— 现在连这个类型都建不出来，报的是「凭据类型仅支持 GIT / HARBOR」 |
| §5.1「`connections()` 只列 enabled 的凭据行」 | 改成列 `allSources()` —— 下拉里会出现内置档 |
| §5.2 `browse` 用 `credentialId` 查 `mapper.selectById` | 改成在 `allSources()` 里找 —— 内置档用的是**合成 id** `builtin:dockerhub` |
| §2.4 缓存键 `registry:suggest:<连接id>:<前缀>` | 不变，只是 `<连接id>` 对内置档是那个合成 id |
| §12 那份「对 S3.1 spec 的订正清单」 | 仍然有效，另外 S3.1 spec §4.1 里「所有启用的镜像仓库连接」现在应读作「所有**来源**」 |

**没变的**：Docker Hub 仍然**只允许匿名**（现在连"允许"都不需要表达了）；仍走
`hub.docker.com/v2/…` 站点 API；`notFoundMessage`、两层浏览、`library` 单入口、
剥 `library/`、tag 的单段归 `library` —— 全部照旧。

### 14.2 新增的两项配置

```yaml
platform:
  registry:
    dockerhub:
      enabled: true      # 关掉就不内置这一档（隔离内网里免得每次按键都朝一个不可达的地址打一发）
  outbound:
    proxy:
      server: 192.168.85.1  # 代理服务器地址
      port: 7890
      targets: hub.docker.com,github.com   # **只有列在这里的目标走代理**，其余一律直连
```

⚠️ **键名刻意不叫 `host` / `hosts`** —— 那两个只差一个字母，一个指**代理服务器**、
一个指**要走它的目标**，最容易看反。所以是 `server` / `targets`。
（第一版就叫 `host` / `hosts`，用户当场指出这个歧义。）

**出站代理为什么是白名单而不是「一个全局代理」**：平台只有两类出站是朝公网的
（Docker Hub、外网 git host），其余全是内网（k8s-server `127.0.0.1:8080`、Thanos、
Prometheus、自建 Harbor / gitlab）。配一个「替所有出站」的代理意味着**代理填错时内网流量
也被导出去** —— 那是最难查的一类故障。白名单为空 = 全直连（默认值，fail-safe：
一个写错的内网代理地址会让外部拉取全线失败）。精确匹配 host，不做后缀。

**这条同时补上了本批的一个缺口**：原先「测试连接」走的是 `CredentialProbeClient`，
而代理只加在 `RegistryHttpClient` 上 —— 会出现「补全通、测试连接不通」的不一致。

### 14.3 另一个修掉的静默路径

`suggest` 在「一个来源都没有」时的早退，现在记一条 `log.info` 并写明原因
（内置档被关掉 + 没有启用的镜像仓库凭据）。以前它与「有来源但对端没回应」在日志里
长得一模一样，而后者至少还有一条 WARN。

### 14.4 上线前发现的一个坑（**只有启动校验看得见**）

两个出站 client 各有第二个（包级私有、给测试注入用的）构造器。Spring 在**多个构造器
且没有无参构造器**时**不会自己挑**一个 —— 生产的那个必须标 `@Autowired`，
否则启动直接 `No default constructor found`。**全量测试全绿照样过**（测试都是直接 `new`），
只有真启动才拦得住。这条又一次说明：启动校验不是可选项。
