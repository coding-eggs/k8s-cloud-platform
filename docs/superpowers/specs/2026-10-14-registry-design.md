# 镜像仓库设计（platform-api + platform-web）

> 任务台账 **S3.1**（前置 S0.2 已完成）。需求依据与裁定理据见 `docs/gap-analysis-2026-10-12.md` §7.5。

**目标**：让平台里填镜像这件事不再靠背。两处镜像输入框有 **repository / tag 补全**；另有一个**只读浏览页**，能选一个已配的仓库、沿层级往下钻、看到 tag 的推送时间/大小/digest，并一键复制完整镜像地址。

**范围**：**Harbor 一档** + 一个为「后续加档」而做的方言扩展点。不含 imagePullSecret（用户裁定后续再说）。

**与 S0.2 的关系**：S0.2 建好了「连接」（`platform_credential`，`type` 目前 `GIT | REGISTRY`）与「怎么验证凭据」（`/credential/test`）。本批是它的**第一个真实消费者** —— `resolveSecret` 第一次有人调。

---

## 已定决策（brainstorming 结论，2026-10-14）

| 决策点 | 结论 | 理由 |
|---|---|---|
| **方言的表达** | **扩展 `type` 枚举**（不是加 `provider` 列，也不是运行时自动探测） | 三档差异很大（分析文档原话「不能一套打通」），平台必须先知道用哪一档。v1 里「连接是什么」与「用什么协议说话」一一对应，拆成两列的自由度暂时没人用。自动探测会让失败原因**不可诊断** |
| **本批实现几档** | **只 Harbor**，枚举里**不预放空值** | 留一个「能选中但没实现」的枚举值，管理员会建出一条**建了也没用的连接** —— 与 S0.2 拒掉那个恒为空的 `scope` 列是同一个判断 |
| **扩展位靠什么** | **接口 + 分派表 + 护栏测试**，不靠枚举占位 | 加一档 = 加枚举值 + 实现类 + UI 选项；候选端点/缓存/权限/浏览页/前端**不改**。「每个枚举值都有实现」由一条测试钉住，忘了实现**立刻红** |
| **补全粒度** | **单框、两段都补** —— 不含 `:` 补仓库、含 `:` 补 tag | 保住「从已配仓库里挑」这一半；拆两个输入框会改掉现有单框 UI |
| **补全怎么拼回去** | **后端返回要填进输入框的完整字符串**，前端只做整段替换 | 拼接规则是方言相关的（Harbor=`host/project/repo:tag`，Artifactory=`host/repo/path:tag`）。由前端拼 = 前端必须懂方言 |
| **浏览的层级** | **路径驱动**，面包屑由后端给；**不写死三层** | Harbor 是 `project→仓库→tag`，Artifactory 是 `仓库→路径→artifact`。写死层级 = 加档时前端要动。路径驱动**同时更简单** |
| **候选值权限** | 新增 **`tenant:` 前缀**的码 | 硬约束：`RoleService.assertScopeMatches` 规定 TENANT 角色只能持 `tenant:` 码，所以 S0.2 那个 `platform:credential:use` **帮不上忙** |
| **浏览页可见性** | **租户可达** | 与补全同一条授权线；租户本来就在拉私有镜像，看得到有什么才谈得上填。与 S0.2「全局可用」一致 |
| **imagePullSecret** | **不做**（用户裁定后续再说） | 见 §10 |
| **候选 / 浏览查询的审计** | **都不记** | 见 §8 |
| **ContainerEditor 是否一并接** | **接**（台账只点名了「更新镜像」） | 同一个能力、两处都是填镜像；只接一处会让人困惑「为什么这里行那里不行」，改动就是把同一个组件换掉 |

---

## 1. 交付边界

**做**：Harbor 一档（列 project / 列仓库 / 列 tag / 关键词补全）+ 方言扩展点与护栏 + 补全接进两处输入框 + 只读浏览页。

**不做**：imagePullSecret；`REGISTRY_V2` / `DOCKERHUB` 的实现；镜像扫描、签名、清理、删除。

---

## 2. 方言扩展点

### 2.1 接口

```java
/** 一种镜像仓库的「方言」。加一档 = 实现它 + 加一个 type 枚举值 + 加一个 UI 选项，其余零改动。 */
public interface RegistryDialect {

    /** 对应的 platform_credential.type */
    String type();

    /**
     * 关键词补全。入参是用户在输入框里已有的**整串**（如 harbor.example.com/proj/ap）。
     * 返回的每一条都是**要填进输入框的完整字符串**，前端整段替换即可。
     * 取不到时返回空列表（静默降级），不抛。
     */
    List<String> suggest(CredentialRef cred, String imagePrefix);

    /**
     * 沿层级往下看。path 为空 = 第一层。
     * 面包屑由本方法给出（前端不认层级名），所以方言可以有自己的层数。
     */
    BrowseResult browse(CredentialRef cred, List<String> path);
}

public record CredentialRef(String id, String name, String type, String endpoint,
                            String username, String secret) { }
```

⚠️ **`CredentialRef` 携带明文凭据**（`username` / `secret`，由 S0.2 的 `CredentialService.resolveSecret` 解出）。它是**进程内**的传参记录，**不得出现在任何响应体里**，也不得被日志打印。S0.2 已经有一条反射钉子守着响应 DTO；本批新增的 DTO 各自也要守（§9）。

`BrowseResult`：

```java
public record BrowseResult(
        List<Crumb> breadcrumb,      // [{label, path}]，path 是累积路径，供点击回跳
        List<Node> children) { }     // 当前层的子节点

public record Crumb(String label, List<String> path) { }

public record Node(
        String name,
        boolean hasChildren,           // 前端据此决定这一行能不能点进去
        String copyText,               // 非空 = 这一行可「复制镜像地址」；叶子才有
        List<Detail> details) { }      // [{label, value}]，如 [推送时间, 大小, digest]

public record Detail(String label, String value) { }
```

**`details` 用「标签+值」而不是固定字段**，是为了让**前端表格的列由后端决定** —— 这是「加一档前端不用动」的关键。Harbor 给「推送时间 / 大小 / digest」，将来的方言给它自己的东西。

### 2.2 分派与护栏

```java
@Component
public class RegistryDialectRegistry {
    // 构造器把 List<RegistryDialect> 收成 Map<type, dialect>
    // 查不到 → CloudPlatformException("该仓库类型尚未实现: " + type)
}
```

**护栏测试**：遍历 `CredentialService.TYPES` 里每一个值，断言 `RegistryDialectRegistry` 都能解析出实现。将来有人加了枚举值忘了实现，**测试立刻红** —— 这才是「留扩展位」，空枚举值不是。

### 2.3 HarborDialect

| 动作 | Harbor API |
|---|---|
| 列 project | `GET /api/v2.0/projects?page_size=…` |
| 列仓库 | `GET /api/v2.0/projects/{project}/repositories` |
| 列 artifact（= tag） | `GET /api/v2.0/projects/{project}/repositories/{repo}/artifacts` |
| 关键词补全 | `GET /api/v2.0/search?q=` |

⚠️ **不走 Docker Registry V2 的 `_catalog`**（分析文档 §7.5：Harbor 默认关闭 catalog，且权限模型在它自己的 API 里）。

`type` 为 `HARBOR` 时，层级是 `project → 仓库 → artifact`；第三层的 `copyText` = `<endpoint>/<project>/<仓库>:<tag>`。

### 2.4 出站

与 S0.2 的 `CredentialProbeClient` **同一条路径**：`platform-api` 直接请求、显式超时、不跟随重定向。

⚠️ **S0.2 那条「不跟随重定向」的教训在这里同样适用**：`HttpURLConnection` 系工厂会默认跟随且没有关上它的 API，必须用 `JdkClientHttpRequestFactory` + 显式 `HttpClient.Redirect.NEVER`。**新写的客户端直接照抄那个工厂配置**，不要再踩一次。

---

## 3. `type` 枚举扩展

`GIT | REGISTRY` → **`GIT | HARBOR`**。

**要改的落点（实施时逐个走一遍，别漏）**：

| 文件 | 现状 |
|---|---|
| `platform-api/.../services/CredentialService.java:44` | `TYPES = Set.of("GIT", "REGISTRY")` |
| `platform-api/.../services/CredentialServiceTest.java:67,265,281` | 三处 `stored(…, "REGISTRY", …)` 的测试夹具 |
| `platform-web/src/types.ts:1588,1609,1622` | 三处 `type: 'GIT' \| 'REGISTRY'` |
| `platform-web/src/views/CredentialView.vue:19,33` | `as 'GIT' \| 'REGISTRY'` 与 `TYPE_OPTIONS` |
| `docs/superpowers/specs/2026-10-13-credential-management-design.md` §11 | 「复用 `REGISTRY` 类型的那条 Harbor 连接」→ 「`HARBOR` 那条」，**语义不变** |

**数据迁移：不需要**。已确认 `platform_credential` 里没有 `REGISTRY` 行（只有 1 条 `GIT`）。迁移文件里会写一行注释说明这件事，以及「**若你的环境已有人建过 `REGISTRY` 凭据，它会被判成非法类型，需删掉重建**」。

---

## 4. 候选值（补全用）

```
POST /registry/suggest
  { "imagePrefix": "harbor.example.com/proj/ap" }
→ { "items": ["harbor.example.com/proj/app", ...] }
```

**一个端点，后端解析整串** —— 判定「这是哪个连接、该补哪一段、拼出来是什么」是**方言知识，只属于后端**；前端不需要知道 Harbor 与将来任何方言的区别。

### 4.1 解析与分派

1. 从 `imagePrefix` 里取出 **host 部分**（第一个 `/` 之前；没有 `/` 则整串去掉 `:tag` 之后的部分）
2. **host 必须在已配置且 `enabled` 的 registry 类连接里匹配到**（取 `endpoint` 的最长前缀匹配）
3. 匹配不到 → 返回 `{"items": []}`，**不发任何请求**
4. 匹配到 → 交给该连接的 dialect，由它决定补哪一段

**本批的一条明确行为**：不带 host 的写法（`nginx:1.27`、`myteam/app` —— 隐式 Docker Hub）**一律返回空候选**。因为 Docker Hub 那一档没实现，平台也无法确定它该归哪条连接。**这是有意的，不是漏了** —— 用户仍可自由输入（§7）。

### 4.2 两条硬边界

1. **出站 URL 只由凭据的 `endpoint` 拼**，绝不拿用户输入里的 host 去发请求。用户输入只用于**匹配**连接和生成候选串，**不参与构造目标地址**。
2. 匹配不到就**不发任何请求**（上面的第 3 步）。这条会有测试断言「零交互」。

### 4.3 缓存 + 静默降级

- 走已有的 `RedisJsonCache`（键带 `platform:cache:` 前缀，与既有约定一致），短 TTL（分钟级）
- 取不到 / 对端报错 / 超时 → **返回空列表 + `WARN` 日志**，**不抛、不弹错、不阻塞输入**（§7.5 已定：autocomplete 不能成为「能不能填镜像」的硬依赖）

---

## 5. 浏览页的两个端点

### 5.1 `POST /registry/connections`

```
→ { "items": [ { "id": "...", "name": "harbor-prod", "type": "HARBOR",
                 "endpoint": "harbor.example.com" } ] }
```

**只回四个字段的窄投影**。**为什么不复用 S0.2 的 `/credential/list`**：那个要 `platform:credential:read`（只绑 admin），租户一进页面就 403 —— 这正是 `frontend-permission-conventions.md` §5「页面需要什么数据，就为那份数据开一个权限码匹配的端点」的教科书情形。

**只列 `enabled=1` 的 registry 类连接**（停用的不该出现在浏览入口里）。

### 5.2 `POST /registry/browse`

```
  { "credentialId": "...", "path": ["myproject", "myapp"] }
→ { "breadcrumb": [ { "label": "myproject", "path": ["myproject"] }, ... ],
    "children":   [ { "name": "1.2.3", "hasChildren": false,
                      "copyText": "harbor.example.com/myproject/myapp:1.2.3",
                      "details": [ {"label":"推送时间","value":"2026-10-13 21:04"},
                                   {"label":"大小","value":"82.4 MB"},
                                   {"label":"digest","value":"sha256:9f2c…"} ] } ] }
```

`path` 为空数组 = 第一层。**层级深度由方言决定**，前端只按 `hasChildren` 决定行能不能点、按 `breadcrumb` 渲染面包屑、按 `details` 渲染表格列。

### 5.3 与 `/registry/suggest` 的**姿态差异**（重要）

| | `/registry/suggest` | `/registry/browse` |
|---|---|---|
| 使用者 | 正在打字的用户，**每按键一次一次请求** | 在页面上主动点了一层 |
| 失败姿态 | **静默降级**（空列表 + WARN） | **报错**（走统一异常出口） |

理由：补全是锦上添花，弹错只会掩盖真正的问题；而浏览页是用户主动发起的导航，**什么都不说等于页面坏了**。两者用同一套错误文案规则（凭据类失败一律二义文案）。

### 5.4 缓存

**只缓存 `suggest`**。`browse` 不缓存 —— 它是低频的主动导航，用户点进去期望看到的是当下状态，而「必须缓存」那条要求（§7.5）本来针对的就是高频的 autocomplete。

---

## 6. 权限与迁移

### 6.1 权限码（**2 个**）

```
tenant:registry:catalog   API  · /registry/suggest + /registry/browse
tenant:page:registry      Page · 浏览页可见性（/registries）
```

**三个端点只用一个 API 码**（connections 也走 `catalog`）：三者是同一件事（看镜像目录）的三个入口，都只读、都归同一个页面与同一批人 —— 与 S0.2 把「测试连接」并进 `read` 是同一条判断。

**拆成 API 码 + Page 码的意义**：这样「能补全、但进不了浏览页」是**可表达**的（给 catalog 不给 page），反之亦然。这正是 `V2026_10_07_1` 把那两套码拆开所要保住的自由度。

⚠️ **必须是 `tenant:` 前缀**：`RoleService.assertScopeMatches` 规定 TENANT 角色只能持 `tenant:` 码；而补全的使用者正是租户。

### 6.2 迁移

新增 `V2026_10_14_1__registry_permissions.sql`：

- 权限行 2 行（1 API + 1 Page），显式 id + `ON DUPLICATE KEY UPDATE`
- 角色关联：**显式 id 列表**绑 `builtin_role_tenant_admin` + `builtin_role_tenant_member` + `builtin_role_admin`

⚠️ **S0.2 交付当天踩过的坑，这里必须避开**：`platform_role_permission.permission_id` 与 `platform_permission.id` 必须**逐字一致**，而本仓**不加外键** —— 写错了不报任何错，只留一条**悬空关联**，症状是「码在库里、角色却没有它」，菜单不显示且日志安静。**重放后的验收必须做 join 对账**（断言每条关联都能 join 到真实权限行），不能只数行数。

⚠️ 迁移文件里 `--` 后必须跟空白字符（S0.1/S0.2 都踩过）；本仓**没有 Flyway**，手工重放。

---

## 7. 前端

### 7.1 两处补全

`WorkloadView` 的「更新镜像」弹窗、`ContainerEditor` 的容器镜像框：`el-input` → `el-autocomplete`。

- `fetchSuggestions` 一律**防抖**
- **失败静默**（空候选），保持可自由输入 —— 候选为空**不是**错误态
- 选中候选 = **整段替换输入框内容**（后端给的就是完整串，前端不做拼接）

### 7.2 浏览页 `/registries`

租户可达，菜单挂在资源管理组，`meta.requiresPerm: [pageCodes.registry]`。

形态：顶部一个连接下拉（数据来自 `/registry/connections`）→ 面包屑 → 表格。

- 表格**列由数据决定**：`name` + `details` 里的标签作为列头 + 每行「复制镜像地址」（`copyText` 非空时才有）
- 没有 `hasChildren` 的行不可点；有 `hasChildren` 的行点击 = 进入下一层（`path` 追加 `name`）
- 面包屑点击 = 回到该层（用后端给的 `path`）
- 空列表要区分两种态：**「这一层是空的」vs「出错了」** —— 后者必须让用户看见（§5.3）

### 7.3 约定

`pageCodes.ts` / `apiCodes.ts` 加常量，路由与菜单引用常量不写字面量（`frontend-permission-conventions.md` §1.2）。复制用既有 `YamlBlock` 那套带 `execCommand` 兜底的写法 —— **内网 http 部署下 `navigator.clipboard` 不存在**（本仓已踩过）。

---

## 8. 审计：候选与浏览查询都**不记**

补全是**每次按键一次请求**，逐次写审计等于拿一个高频只读动作淹掉审计表；浏览页同理（只读、不改状态）。

S0.2 的「使用凭据要留痕」在这里**有意不执行**。「使用」的留痕应该记在**真正产生副作用的动作**上 —— S3.5 把凭据注入构建 Pod、将来真做 imagePullSecret 下发时，各记一条。

这条写在这里是为了避免下次评审把它当缺口。

---

## 9. 测试

| 层 | 测什么 |
|---|---|
| **解析器** | `imagePrefix` → 连接 + 补哪一段。覆盖：全限定带 tag / 全限定不带 tag / 带端口 / **无 host（→ 空，且零请求）** / host 匹配不上 / 多条连接时取最长匹配 |
| **`HarborDialect`** | URL 拼接与响应映射做成**纯函数**（照 `CredentialProbe` 的样子），不掺 IO |
| **出站层** | `MockRestServiceServer`：URL、Basic 头、状态码分类；**重定向 NEVER** 要有一条打本地 `com.sun.net.httpserver` 的用例（S0.2 那条 Critical 的教训） |
| **降级** | `suggest` 对端 500 / 超时 → 返回空列表且**不抛**；`browse` 对端 500 → **抛**（两者姿态不同，各钉一条） |
| **护栏** | `CredentialService.TYPES` 里每个值都有 `RegistryDialect` 实现 |
| **窄投影** | `/registry/connections` 的响应**不含**凭据字段（照 S0.2 的反射钉子写法） |
| **迁移** | 手工重放；**join 对账**断言每条角色关联都能命中真实权限行（不是数行数） |

---

## 10. 有意不做 / 已知缺口（**显式记录，勿当新问题上报**）

| 项 | 为什么不做 |
|---|---|
| **imagePullSecret 自动生成** | 用户裁定「先不做，后续再说」。§7.5 的原意是「自然的延伸」，但它把平台凭据的可用范围落到租户命名空间里（租户对自己命名空间的 Secret 有读权限），那是一次真实的数据面扩张，值得单独决策 |
| **`REGISTRY_V2` / `DOCKERHUB` 两档** | 用户裁定现在只 Harbor。扩展点已就位（§2.2 的护栏保证不会「加了枚举忘了实现」） |
| **无 host 的镜像写法（隐式 Docker Hub）** | 本批一律返回空候选（§4.1）。用户仍可自由输入 |
| **镜像扫描 / 签名 / 清理 / 删除** | 不在台账 S3.1 范围 |
| **候选与浏览的审计** | §8 |
| **`/registry/browse` 的缓存** | §5.4 |

### 10.1 已知风险（接受）

**租户能看到平台所有已配 registry 的目录。** 这是 S0.2「纯平台级 + 全局可用」那条决定的直接延伸 —— 现在落到了具体数据上（仓库名与 tag 名，**不含凭据**）。若某个 registry 里的 project / 仓库名本身含敏感信息（如按客户命名），这是一次真实的数据面扩张。要收窄的话得回到 S0.2 里被否掉的「按租户授权」，本批不做。

---

## 11. 将来加一档（如 JFrog Artifactory）要动哪里

这张清单是「扩展位」这个承诺的**兑现方式** —— 写清楚，免得下一个人以为它会自动生效。

**要动的**：
1. `platform_credential.type` 迁移里加一个枚举值
2. 新增一个 `RegistryDialect` 实现类
3. 凭据表单的 `TYPE_OPTIONS` 加一项
4. **S0.2 的测试连接** —— `CredentialProbe.registryProbeUrl` 现在假定「所有 registry 都响应 `/v2/`」，对 Artifactory **不成立**（它的端点在 `/artifactory/api/docker/<repo>/v2/`）。届时要把 `probe` 也挪进 dialect 接口。
   ⚠️ 本批**有意不预先加** `probe` 到接口里 —— 那会是直到那天都没人调的方法（YAGNI）。

**不要动的**（这就是扩展位的价值）：
`/registry/suggest`、`/registry/browse`、`/registry/connections`、缓存、权限码、浏览页与前端**全部**。

> **为什么「路径驱动 + 后端给面包屑 + 候选返回完整串 + 列由 `details` 决定」值这个价**：如果写死三层（Harbor 形状）、由前端拼镜像串、由前端固定列，上面那份「不要动」的清单会有四项进「要动的」。这三条设计**本身就比写死的版本更简单**，扩展性不是额外代价。

---

## 12. 部署

1. **先重放** `V2026_10_14_1__registry_permissions.sql`（手工，本仓没有 Flyway）
2. **再起新构建**（本批无新增配置项，不需要新环境变量）
3. 重放后重新登录或等权限缓存 TTL（30s）

### 12.1 部署后待验清单

- [ ] 凭据页能建出 `Harbor 镜像仓库` 类型的连接（旧库里的 `GIT` 行不受影响）
- [ ] 「更新镜像」输入框里打 `harbor.example.com/` → 出仓库候选；打 `…:1` → 出 tag 候选
- [ ] 输入一个**没配过的** host → **无候选、无报错**，且能自由输入
- [ ] 浏览页：选连接 → 点进 project → 点进仓库 → 看到 tag 的推送时间/大小/digest → 复制镜像地址
- [ ] 浏览页在 registry 不可达时**要报错**（不是空列表）
- [ ] 用非 admin 的租户成员账号验证：补全与浏览页**都可用**（`tenant:registry:catalog` 与 page 码已绑）
- [ ] 审计页确认：补全与浏览**没有**产生记录（§8 的有意行为）
