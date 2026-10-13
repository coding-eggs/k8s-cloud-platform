# 凭据管理设计（platform-api + platform-web）

> 任务台账 **S0.2**。需求依据与裁定理据见 `docs/gap-analysis-2026-10-12.md` §7.6（含 2026-10-12 的复审修正）。

**目标**：给平台一个**凭据库** —— 平台管理员在这里录入 git 与镜像仓库的连接信息（地址 + 凭据），
凭据加密落库、**永不回显**；录入后能一键**测试连接**，确认真能用。

**范围**：**只做凭据库本身 + 测试连接**。投递（注入构建 Pod、生成命名空间 Secret / imagePullSecret）
留给消费方 S3.1 / S3.5。

**为什么这个范围就够**：S0.2 的价值在于**把三条口径一次定清**（加密与主密钥 / 三段权限与永不回显 /
使用留痕），而不是提前实现消费方。测试连接是**本批唯一的「使用」路径**，它同时承担三件事：
验证加密往返真的能解出明文、给 S0.2.6 的使用审计提供第一条真实记录、让管理员在租户跑 CI 之前
就发现凭据配错了。

---

## 已定决策（brainstorming 结论，2026-10-13）

| 决策点 | 结论 | 理由 |
|---|---|---|
| **凭据归属** | **纯平台级** —— 只有平台管理员能建/改，租户只「使用」 | 平台级凭据服务的就是公共基础设施（一套 Harbor、一个 GitLab），Harbor 侧靠 project 做隔离，不需要平台侧存 N 份。权限面最小：租户无法把凭据指向外部端点 |
| **可用范围** | **全局可用** —— 配好即对所有持 `platform:credential:read` 的调用方可用 | 「A 租户能拉、B 租户不能拉同一套 Harbor」在公共基础设施前提下不成立。不加按租户授权表、不加授权 UI、读取路径上没有「未授权降级」分支 |
| **认证方式** | **仅 HTTPS：用户名 + 密码/令牌**（basic auth） | SSH 的成本大头（known_hosts / host key 策略 / Tekton `ssh-directory` workspace 投递）属于**还不存在**的 S3.5；现在做只能定义字段、无法验证。认证字段存**加密 JSON blob** → 将来补 SSH 是「表单加一项 + blob 加两个键」，零迁移 |
| **本批边界** | **凭据库内核 + 测试连接** | 只做内核会交付一个**从未被解密过**的功能，加密正确性无法自证、使用审计无落点；做投递又会把 K8s 写入的边界问题提前（台账把它挂在 S3.1.3） |
| **权限码** | **3 个**：`platform:credential:read` / `:write` / `platform:page:credential` | 原计划的三段权限里「使用」独立成码，**用户裁定合并**：本批三个码全绑 admin，独立不产生任何角色差异，属 YAGNI。测试连接与列表**共用 `read`** |
| **可见性** | 只有平台管理员 | 纯平台级的直接后果。表结构与查询接口**不预留租户维度** |
| **删除语义** | **硬删除**（`DELETE FROM`，不是软删除） | 与库内其它表的软删除惯例**有意不同**：凭据的删除语义是「让它消失」，软删除会把密文留在库里 —— 如果删除的原因是「这个 token 泄露了」，软删除正好使它失效。审计里不依赖凭据行存活（审计存的是 id / name 字符串，不做 join） |
| **主密钥** | **独立新配** `platform.credential.aes-key` | 轮换边界独立；且**不扩散** `k8s.cloud.kubeconfig.aes-key` 那个把默认密钥硬编码在 `application.yaml` 里的既有坏味道（该处本批**不动**，也不复制第二次） |
| **主密钥轮换 / 密文重加密** | **不做** | 没有轮换工具、没有 re-encrypt 批处理。见 §12 |

---

## 0. 实测记录（2026-10-13 spike，用户提供 GitHub 凭据实测）

设计 §5 的探测方式**建立在实测之上**，不是推演。原始结论如下，**它们解释了本设计为什么长这样**：

| # | 探测 | 结果 | 对设计的影响 |
|---|---|---|---|
| A | `info/refs` 公共仓库 + **错密码** | **401**（gitee、gitlab 一致） | **推翻了「公开仓库会忽略错凭据 → 假阳性」的假设**。服务器只在**没带凭据**时才匿名放行；带了就校验。所以 `info/refs` 是可信的凭据探针，**不需要按 host 类型分派、不需要 `provider` 字段** |
| A2 | `info/refs` 公共仓库 + 无认证 | 200 | 匿名本来就能 clone 公开仓库 |
| B | `info/refs` 200 的**响应体** | `001e# service=git-upload-pack\n0000014e8a…` | **产出 §5.3 的「内容判定」**：只判状态码会被一类假阳性骗过（见下） |
| C | `info/refs` 401 的响应体 | `Incorrect username or password (access token)` | 文案是 host 特有的，**不可解析**（GitLab / GitHub 各不相同） |
| D | `info/refs` 不存在 / 无权的仓库 | **401 —— 与「凭据错」同一个码** | **产出 §5.4 的二义性**：401 分不清「凭据错」和「凭据对但无权访问该仓库」 |
| E | GitHub **API** 公开仓库 + 坏 token | **401** + `{"message":"Bad credentials"}` | 说明 GitHub 侧也是 fail-closed，与 A 一致 |
| F | 省略 `.git`（`gitlab.com/a/b`） | **301** → `/a/b.git/info/refs?…` | **产出 §5.2 的 URL 规范化**：不规范化会拿到 3xx |
| F2 | 省略 `.git`（`gitee.com/a/b`） | 200 | 各 host 行为不一致 → **不能靠重定向兜底，要在自己这边规范化** |
| F3 | 尾部多余斜杠 `…/repo.git//info/refs` | 301 | 同上 |
| G | `registry-1.docker.io/v2/` | 不可达，未测到 | Docker Hub 属 S3.1 的 autocomplete，不阻塞本批 |

### 0.1 未验证项（**留给部署后**，不要当已验证的事实）

1. **github.com 的 git 协议端点** —— 实测机器**连不上 `github.com` 那个 IP**（`api.github.com` 通、
   `github.com` 超时；`codeload` / `raw` 都通）。E 显示它的 API 是 fail-closed 的，git 端点**大概率一致，
   但这是推断不是实测**。
2. **有效凭据 + 需认证的仓库 → 200** —— 用户提供的仓库是**公开**仓库
   （`api.github.com/repos/coding-eggs/k8s-cloud-platform` 不带 token 返回 200），所以正常成功路径没测到。
   这条路径显然成立，但记录在此。
3. **Harbor `/v2/` 带错凭据是否返回 401** —— 未验证。A 的结论（presented-but-invalid ⇒ 401）是
   gitee / gitlab 的实测 + GitHub API 的旁证，**Harbor 待验**。

> **环境提醒（不是设计问题，但会决定能不能用）**：实测机器访问 `github.com` 超时。若**平台服务器**
> 处在同一网络后面，配 GitHub 的 git 连接前需先在**平台那台机器**上验一次出站可达性。

---

## 1. 术语与边界

| 词 | 指什么 |
|---|---|
| **凭据 / credential** | 本模块的实体：`type` + `endpoint` + 一组加密认证字段 |
| **`endpoint`** | **host 基址**，两类同义（见 §1.1） |
| **测试连接** | 解密凭据 → 真打一次对端 → 报通或不通。**本批唯一的「使用」路径** |
| **k8s Secret** | **不是本模块**。`views/resource/SecretView.vue` 那个是 K8s 原生 Secret 的资源页（租户侧、按命名空间）。两者不可混 |

**边界**：本模块不碰任何集群对象。落库只落 `platform_credential` 一张表。

### 1.1 `endpoint` 是 host 基址，不是具体仓库

| type | `endpoint` 形如 | 语义 |
|---|---|---|
| `GIT` | `https://gitlab.example.com` | 一个代码托管站点 |
| `REGISTRY` | `harbor.example.com` | 一个 registry |

**为什么 git 不存具体仓库 URL**：流水线是**租户级**的（跑在租户的命名空间里，台账 §7.4.3 的分配表边界），
租户要能 clone **自己的**任意仓库。若一条 git 凭据绑死一个仓库，租户就只能 clone 平台预配的那几个 ——
CI 就没用了。凭据（token）本来就是 host 级的，不是 repo 级的。

**代价**：host 基址**没有通用的凭据探针**（GitLab 用 `/api/v4/user`、Gitea 用 `/api/v1/user`、
GitHub 用 `/user`、裸 git host 什么都没有）。**解法见 §5.1：测试连接对 `GIT` 类型额外要求一个
「探测用仓库 URL」**。它是**测试动作的入参，不是连接的属性**，所以不进表 ——
「这条 git 凭据能用吗」本身就是个不完整的问题，该问的是「能不能用它访问 X 仓库」
（token 本来就可能只对部分仓库有权限）。

---

## 2. 数据模型

### 2.1 表 `platform_credential`

```sql
CREATE TABLE IF NOT EXISTS `platform_credential` (
  `id`                 varchar(64)  NOT NULL COMMENT 'ULIDGenerator（16 位 hex），同库内其它表惯例',
  `name`               varchar(64)  NOT NULL COMMENT '显示名，全局唯一',
  `type`               varchar(32)  NOT NULL COMMENT 'GIT / REGISTRY（v1 仅此两类）',
  `endpoint`           varchar(512) NOT NULL COMMENT 'host 基址，见 §1.1',
  `auth_type`          varchar(32)  NOT NULL COMMENT 'BASIC（v1 唯一实现）；加 SSH 时在此扩展，不动表',
  `secret_encrypted`   text                  DEFAULT NULL COMMENT 'AES 密文（含 v1: 前缀），明文是认证字段 JSON。v1 恒非空（BASIC 必填）；可空是为将来 NONE 型（匿名）留位',
  `secret_fingerprint` varchar(64)           DEFAULT NULL COMMENT 'SHA-256(认证字段 JSON) 前 16 hex，见 §2.3',
  `description`        varchar(255)          DEFAULT NULL,
  `enabled`            tinyint      NOT NULL DEFAULT 1 COMMENT '0=停用：仍在列表里，但消费方不应选它（比删除温和）',
  `last_verified_at`   datetime              DEFAULT NULL COMMENT '上次**测试通过**的时刻；失败不改它',
  `last_verify_error`  varchar(255)          DEFAULT NULL COMMENT '上次测试的失败原因；成功则置 NULL',
  `created_at`         datetime              DEFAULT CURRENT_TIMESTAMP,
  `updated_at`         datetime              DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_name`(`name` ASC) USING BTREE,
  INDEX `idx_type`(`type` ASC) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC
  COMMENT='平台级凭据（git / registry 连接）';
```

**没有 `deleted_at`** —— 硬删除，理由见文首「已定决策」表的**删除语义**一行。因此 `name` 是**无条件唯一**，
不需要 `uk_name_deleted` 那种「唯一 + 软删」的复合索引。

**没有 `tenant_id`、没有 `scope`** —— 纯平台级 + 全局可用的直接后果。台账原稿写的
`connection(type, endpoint, credential, scope)` 里的 `scope` 是为「按租户授权」留的位，
用户已裁定不做，**留一个恒为空的列只会让人以为有个没实现的功能**。

### 2.2 一个实体，不是「连接 + 凭据」两张表

拆开唯一的好处是一组凭据能服务多条连接。本平台的两个用途都不需要：git 的 token 是 host 级的、
Harbor 的 robot account 是 registry 级的 —— 一条连接一份凭据就是全部场景。拆开只会多一张表、
多一次 join、多一套「删连接时凭据怎么办」的生命周期问题。

### 2.3 指纹

`secret_fingerprint = SHA-256(认证字段 JSON 的 UTF-8 字节) 前 16 位 hex`。

- **取明文而不是密文**：`AESUtils` 每次加密用随机 salt + 随机 IV，密文每次都不同，拿它做指纹毫无意义。
- **用途**：区分「改了凭据」与「只改了描述 / 名字」—— 列表页上两者都是「更新时间变了」。
- **已知的信息泄露面（接受）**：指纹相同 ⇒ 明文相同。对高熵 token 无风险；对弱口令构成一次离线比对的
  辅助，但比对的前提是攻击者**已经拿到了另一份密文和主密钥**，那时指纹不是主要问题。

---

## 3. 加密

### 3.1 复用算法，不复用主密钥

- **算法**：复用 `platform-common` 的 `AESUtils.encrypt/decrypt(String, String)`
  （AES-256/CBC/PKCS5Padding + PBKDF2 派生，65536 轮）。**不改这个类。**
- **主密钥**：**独立新配** `platform.credential.aes-key`（env `CREDENTIAL_AES_KEY`）。

### 3.2 密文带版本前缀

存储形态 = `"v1:" + AESUtils.encrypt(json, key)`。

`AESUtils` 自己**没有**版本概念，加上它是零成本，而将来换算法时能识别旧密文。
`decrypt` 遇到**未知前缀必须抛**（`CloudPlatformException`），**绝不静默当明文或按 v1 硬解** ——
静默降级会把「密钥配错了」伪装成「凭据错了」。

### 3.3 主密钥缺失 = 启动失败（fail-closed）

`CredentialCipher` 在 `@PostConstruct` 校验密钥非空，为空则**直接拒绝启动**，错误信息给出
用 `AESUtils.generateStrongKey()` 生成一把的命令。

**这是对 kubeconfig 先例的有意改进**：`k8s.cloud.kubeconfig.aes-key` 在 `application.yaml` 里
写死了一个默认值，于是「忘了配环境变量」会静默地用那把公开的默认密钥加密真实凭据。
本模块不复制这个坏味道。（那处本批不动 —— 改它属于另一个决定。）

### 3.4 认证字段的序列化

明文是 JSON：`{"username":"…","secret":"…"}`。

**用 Jackson 对象映射，不用字符串拼接**：拼接会在用户名含 `"` / `\` 时产出坏 JSON，
而这类字符在 robot account 名里完全可能出现。解析失败按 §3.2 抛出，不降级。

---

## 4. 认证字段建模：加密 JSON blob，不建强类型列

**不建 `username` / `password` 列。**

- 强类型列的唯一好处是「DB 里能直接读到用户名」—— 而那本来就是**不该可读**的敏感数据
- 代价是**加认证方式要改表**，与台账「模型留扩展位」的意图相反
- 加了 SSH 之后是「表单多一个选项 + blob 里多两个键」，**零迁移**

`auth_type` 是**明文列**（渲染表单与脱敏要用它），但不参与拼接 —— 它是 blob 的解释方式。

---

## 5. 测试连接

`POST /credential/test`，入参 `{id, probeUrl?}`。**只测已保存的凭据。**

**为什么不支持「表单里先测再存」**：明文会因此多一条传输路径（保存本来就要传），
且未保存的凭据没有指纹、没有审计目标 —— 测出来的结果无处安放。流程是「保存 → 列表里点测试」。

**结果语义**：探测**不改变任何业务状态**，它本身是一个查询动作。它只写两处：
`last_verified_at` / `last_verify_error`（成功清空 error），以及一条审计记录。

### 5.1 按类型分派

| type | 探测 | 额外入参 |
|---|---|---|
| `GIT` | `GET <probeUrl>/info/refs?service=git-upload-pack` + basic auth | **`probeUrl` 必填**（完整仓库 URL），理由见 §1.1 |
| `REGISTRY` | `GET <endpoint>/v2/` + basic auth | 无 |

`REGISTRY` 不需要额外入参：`/v2/` 是 OCI Distribution 规范的一部分，所有 registry 都实现。

### 5.2 git 探测 URL 的规范化（实测驱动，见 §0 的 F / F2 / F3）

规范化**只做一件事**，然后拼接：

1. 去尾部所有 `/`
2. 追加 `/info/refs?service=git-upload-pack`

**不猜 `.git` 后缀，也不跟随重定向。** 重定向策略显式设为 `NEVER`。

**为什么不做这两件事**：

- **不猜后缀**：F 显示 GitLab 省略 `.git` 会 301、F2 显示 gitee 直接 200 ——
  各 host 不一致。补 `.git` 是一种猜测，猜错在别的 host 上就是假阴性，且用户看不出为什么。
  正确做法是把完整仓库地址交给用户填（UI 给示例），**我们不替他对地址**。
- **不跟随重定向**：跟随会让「通不通」变成「对端愿不愿意带你跳」。更麻烦的是
  **`Authorization` 头在重定向中的传递规则是 host / 跳转类型相关的** —— 一旦被丢弃就是
  **假阴性**，而且现象是「地址明明对、凭据明明对，就是不通」，极难排查。
  把 `NEVER` 写死，这个变量就整块消失。

**代价与补偿**：用户填了不带 `.git` 的地址会拿到 3xx。**所以 3xx 不是「对端异常」，
而是一条可操作的提示** —— 把 `Location` 原样显示给用户，告诉他「对端要求地址为 …」，
复制即可（见 §5.4 表格）。

### 5.3 判定要看响应体，不只看状态码

`GIT` 类型：**200 且响应体以 `001e# service=git-upload-pack` 开头**才算通过。

**为什么**：状态码之外还有一整类假阳性 —— 反向代理的登录页、SSO 跳转、captive portal
都可能对 `info/refs` 返回 **200 + HTML**。只判状态码会把它们判成「通过」。B 那条实测确认了
正常响应的头部就是这串 pkt-line 头，判定成本是一次字符串前缀比较。

`REGISTRY` 类型：200 即通过（`/v2/` 的响应体是空 JSON `{}`，没有可判定的前缀）。

### 5.4 ⚠️ 401 是二义的 —— 文案不能写「密码错误」

实测 D：**不存在的仓库与「凭据错」返回同一个 401**。所以 401 的正确解读是
**「凭据不通过，或该凭据无权访问此仓库」**。

**写错文案的代价是把管理员引到错的方向** —— 他会去改密码，而真正的问题是那个 robot account
没有这个仓库的读权限。

| 观察到 | 含义 | 给用户的文案 |
|---|---|---|
| 200 + 合法 pkt-line 头 | 通过 | 「连接成功」 |
| 200 + 非 git 响应体 | 对端返回了非 git 内容（多为代理/登录页） | 「对端返回了非 git 协议内容，地址可能指向了网关或登录页」 |
| 401 / 403 | **凭据不通过，或凭据无权访问该仓库** | 见上，**不要提「密码」** |
| 3xx | 对端要求跳转（多半是地址少了 `.git`） | **把 `Location` 显示出来**：「对端要求地址为 `<Location 去掉 /info/refs… 后缀>`，请改用该地址」 |
| 404 | 地址不对 / 仓库不存在 | 按地址错报 |
| 其他 4xx / 5xx | 对端异常 | 带状态码 |
| 连不上 / 超时 | 网络不可达 | **并提示：平台服务器需能出站访问该地址**（见 §0.1 的环境提醒） |

### 5.5 超时

探测是**同步**的（一个 HTTP 请求，一个按钮）。连接超时与读超时都要显式设置（秒级），
**绝不用默认的无限等待** —— 否则对端黑洞会把请求线程挂住。

---

## 6. API 与权限

### 6.1 端点

| 端点 | 作用 | 权限码 | 审计动作 |
|---|---|---|---|
| `POST /credential/list` | 列表（脱敏） | `platform:credential:read` | 无（读操作） |
| `POST /credential/test` | 测试连接 | `platform:credential:read` | `credential:test` |
| `POST /credential/create` | 新建 | `platform:credential:write` | `credential:create` |
| `POST /credential/update` | 更新 | `platform:credential:write` | `credential:update` |
| `POST /credential/delete` | 删除 | `platform:credential:write` | `credential:delete` |

**没有单条详情端点**：列表返回的字段已足够，编辑表单的数据从列表行来。
（与 `/audit/list` 不做单条详情同一条偏离理由。）

### 6.2 测试连接与列表共用 `read`

用户裁定。本批三个码全绑 admin，独立成码不产生任何角色差异。
**后果说明白：`/credential/test` 和 `/credential/list` 共用一个码，即「能看列表 = 能点测试」。**

将来若出现「能看列表但不能探测」的角色（如纯审计角色），拆开的成本是：
加一行权限行 + 改一个注解 + 给角色补授权。这是显式接受的。

### 6.3 权限行（迁移）

6 行 —— 5 行 API + 1 行 Page：

```sql
INSERT INTO platform_permission (id, domain, resource, action, code, description) VALUES
 ('perm_credential_list',  'API','/credential/list',  'POST','platform:credential:read', '凭据列表（脱敏，永不回显凭据内容）'),
 ('perm_credential_test',  'API','/credential/test',  'POST','platform:credential:read', '测试连接（与列表同码：能看即能测）'),
 ('perm_credential_create','API','/credential/create','POST','platform:credential:write','新建凭据'),
 ('perm_credential_update','API','/credential/update','POST','platform:credential:write','更新凭据（可轮换密钥）'),
 ('perm_credential_delete','API','/credential/delete','POST','platform:credential:write','删除凭据（硬删除）'),
 ('perm_page_credential',  'Page','/credentials',     'view','platform:page:credential','凭据管理页可见性')
ON DUPLICATE KEY UPDATE description=VALUES(description), resource=VALUES(resource), action=VALUES(action);
```

⚠️ `platform_permission` 上有 `UNIQUE(domain, resource, action)` —— 上面 6 行的
`(domain, resource)` 组合互不相同，这条约束不会被踩。`resource` 上限 64 字符，最长的一行 18 字符。

**角色关联**：6 行全部显式绑 `builtin_role_admin`（按**显式 id 列表**，不用 LIKE ——
id 里的 `_` 是 LIKE 的单字符通配符，`V2026_10_08_5` 已踩过）。租户族角色**有意不给**。

⚠️ 每个端点**必须有权限行**，否则 `PermissionCrossCheckRunner` 在启动期拒启（fail-closed）。

### 6.4 `platform:page:credential` 与 API 码是两回事

页面可见性由 **Page 域**的 `platform:page:credential` 决定（`domain='Page'`、resource=前端路由 path、
action=`VIEW`），与任何 API 码无关。这是 `V2026_10_07_1` 起的既有约定，
理由见 `docs/development/frontend-permission-conventions.md` §1。

**不能拿 Page 码去覆盖 `/credential/*`**：Page 域的行**不进 URL 规则表**
（`PermissionRegistryFactory` 只取 `domain='API'`），那样 `POST /credential/test` 就没有 API 行，
启动期交叉校验直接拒启。

---

## 7. 审计

复用已有的 `@Auditable` + `AuditAspect`，**标在 Controller 端点**上，零新机制。

新增 4 个 `AuditAction` 常量（现有 23 → 27）：

```java
CREDENTIAL_CREATE = "credential:create";
CREDENTIAL_UPDATE = "credential:update";
CREDENTIAL_DELETE = "credential:delete";
CREDENTIAL_TEST   = "credential:test";
```

`AuditAction.all()` 是反射来的，**加常量会自动进动作码候选**，不用改别处。

**`target` 取什么**：

| 端点 | `target` | 结果 |
|---|---|---|
| `create` | `#request.name` | 名字 |
| `update` / `delete` / `test` | `#request.id` | **id** |

后三个的请求里只有 id，所以 `target_name` 记的是 id。**这与 `ClusterController` 完全一致**
（cluster 的 update / delete / provision 同样是 `#request.clusterId`）——
**是有意对齐，不是漏改，勿当 bug 上报。**

### 7.1 审计里绝不记凭据内容与 endpoint

审计记录**不写** endpoint、不写用户名、不写密码/令牌，**只写 id / name**。

理由：审计表是长期保留的，把凭据抄进去等于把敏感信息搬到一个「按时间范围删除」更宽松的表里，
且审计本身的可见范围（平台管理员）比凭据的可见范围（同样是平台管理员）**并不更窄** ——
一旦将来放宽任一侧，泄露面立刻扩大。

这条会写成测试断言（§9）。

---

## 8. 前端（platform-web）

### 8.1 页面

新页 `/credentials`，**平台视图**，菜单挂在平台管理组（与审计日志同级），
`meta.requiresPerm: [pageCodes.credential]`。

列表列：名称 / 类型 / endpoint / 认证方式 / **指纹** / 状态（上次验证结果）/ 启用 / 操作。

- **状态列**：`last_verify_error` 非空 → 红字显示原因；`last_verified_at` 非空 → 绿字显示时间；
  两者都空 → 灰字「未验证」
- **操作列**：编辑 / 测试连接 / 启用停用 / 删除

### 8.2 表单

新建与编辑走抽屉（`el-drawer`）。⚠️ **样式必须写在非 scoped 块并用独有的 class 前缀** ——
`el-dialog` / `el-drawer` 的内部节点不带 `data-v-*`，scoped `:deep()` 匹配不到。
这在本仓已踩过一次，不是本模块的新问题。

- **敏感字段永远是空的 `password` 输入框**，`placeholder="留空 = 保持不变"`（编辑态）
- **`authType` 变更时必须重填凭据** —— 密文是绑 `authType` 解释的 blob，
  跨类型沿用会让旧 JSON 的语义对不上。表单要在切换时清空敏感字段并给出提示
- 新建态敏感字段必填（`BASIC` 类型）

### 8.3 测试连接

`GIT` 类型点「测试连接」时**弹出输入探测用仓库 URL**（§5.1 的额外入参），`REGISTRY` 类型直接打。

输入框的 `placeholder` 必须给**完整示例**：`https://gitlab.example.com/group/repo.git`
（**带上 `.git`**）—— 因为 §5.2 决定不猜后缀，用户填错就要靠这条提示避免踩 3xx。
测试结果里若返回了「对端要求地址为 …」的提示，要提供**一键填入**（把 `Location` 规范化后回填输入框），
不要让用户手工抄一个长 URL。

**凭据内容永远不出现在这个交互里**：测试用的是库里的密文，探测 URL 是唯一的额外输入。

### 8.4 约定遵守

- **`pageCodes.ts` 加常量**，路由 `meta.requiresPerm` 与菜单 `v-if` 引用它，**禁止写字面量**
- **`apiCodes.ts` 只登记前端真的用来做判断的码**（本批：写权限用于按钮置灰）
- 该页的数据拉取**不存在「可选拉取短路」问题**：页面本身就需要列表，且能进页面就有 `read`

---

## 9. 测试

| 层 | 测什么 |
|---|---|
| `CredentialCipher` | 加密往返（含 `v1:` 前缀）；**同一明文两次加密密文不同**（salt/IV 随机）；**未知前缀必须抛**；密钥缺失启动失败 |
| 指纹 | 同明文同指纹、异明文异指纹；AES 的随机性**不影响**指纹 |
| **脱敏 DTO** | **反射断言**：响应 DTO 上不存在名字含 `secret` / `password` / `username` 的字段 —— 防止将来有人加字段时静默泄露。**这是结构性钉子，不是行为测试** |
| 探测分派 | `RestClient` mock：200 + 合法 pkt-line 头 → 通过；200 + HTML → 非 git 协议；401 → 二义文案（断言**不含「密码」字样**）；3xx → 带 Location；超时 → 网络不可达 |
| URL 规范化 | 去尾斜杠 / 补 `.git` / 已带 `.git` 不重复补 |
| 审计 | `credential:test` 落一条；**审计记录里不含 endpoint 与凭据内容**（抓 captor 断言） |
| 权限 | 启动期交叉校验通过（5 个 API 端点都有行） |
| 迁移 | 按本仓惯例**手工重放**；`platform_permission` 的 `UNIQUE(domain,resource,action)` 不冲突 |

**不做**：真对端集成测试（留给部署后，见 §0.1）。

---

## 10. 部署与回滚

### 10.1 顺序（**有硬顺序，不能颠倒**）

1. **先重放迁移** `V2026_10_13_3__credential.sql`（建表 + 6 行权限 + admin 角色关联）
2. **再配主密钥** `CREDENTIAL_AES_KEY`（不配则**应用起不来**，见 §3.3）
3. **最后起新构建**

⚠️ **本仓没有 Flyway** —— `db/migration/*.sql` 一直是**手工重放**的，应用不会自动执行它；
启动日志里**永远不会有** `Migrating schema`。（这条在 S0.1 已确认过一次。）

⚠️ 顺序反了会被 `PermissionCrossCheckRunner` 拒启（端点无权限行，fail-closed）。

### 10.2 迁移可重放性

- 建表 `CREATE TABLE IF NOT EXISTS`
- 权限行显式 id + `ON DUPLICATE KEY UPDATE`
- 角色关联显式 id + `ON DUPLICATE KEY UPDATE`
- ⚠️ **MySQL 注释 `--` 后面必须跟空白字符**，否则整个文件重放失败。
  `V2026_10_13_1` / `V2026_10_13_2` / `V2026_10_09_1` 都踩过这个坑。

### 10.3 回滚

本批**无数据迁移**（新表），回滚 = 停新构建，表可留（不影响旧代码）。
**没有「撤销加密」这一步** —— 密文与主密钥都不被旧代码触碰。

### 10.4 部署后待验清单

- [ ] 建一条真 GitLab / GitHub 凭据 → 测试连接 → 通
- [ ] 故意填错密码 → 测试连接 → **报的是二义文案，不是「密码错误」**
- [ ] 建一条 Harbor 凭据 → 测试连接（**§0.1 未验证项 3**）
- [ ] 列表页确认**看不到**任何凭据内容，只有指纹
- [ ] 审计页确认 4 类动作都有记录，且记录里**没有 endpoint 与凭据内容**
- [ ] 平台服务器对目标地址的出站可达性（**§0.1 的环境提醒**）

---

## 11. 与下游批次的接口（**本批只定义，不实现**）

消费方按这个约定取用，S0.2 不实现投递：

| 消费方 | 取什么 | 台账条目 |
|---|---|---|
| S3.5（CI） | git 节点的 clone 地址与凭据；**按 host 基址 + 仓库路径拼接** | S3.5.13 |
| S3.1（镜像仓库） | registry 的连接与凭据；生成 `imagePullSecret` | S3.1.3 |
| S3.3（Helm 商店） | **复用 `REGISTRY` 类型的那条 Harbor 连接**，不另存一份 | S3.3 |
| S2.3（日志采集） | ES 端点与凭据 —— 需要**新增一个 `type`**，不动表结构 | S2.3.2 |

**取用一律经一个内部服务方法**（`CredentialService.resolveSecret(id)`），**不暴露成 HTTP 端点** ——
明文只在进程内传递。该方法**不记审计**，审计由调用方那一层负责（否则一次 CI 运行会记 N 条）。

> 台账 S0.2.6「使用审计」在本批的落点**只有测试连接**。CI 触发、镜像拉取等使用动作的审计
> 归各自的批次 —— 本条是为了避免「S0.2 没做使用审计」被当成缺口。

---

## 12. 有意不做 / 已知缺口（**显式记录，勿当新问题上报**）

| 项 | 为什么不做 |
|---|---|
| **SSH / 私钥认证** | 成本大头（known_hosts、host key 策略、Tekton `ssh-directory` workspace 投递）属于还不存在的 S3.5。认证字段是 blob → 将来补是零迁移 |
| **投递**（命名空间 Secret / imagePullSecret / 构建 Pod 注入） | 台账挂在 S3.1.3 / S3.5.13 |
| **按租户授权** | 用户裁定「全局可用」。表里连 `scope` 列都没有 |
| **匿名 / 无凭据连接**（`authType=NONE`） | 用户裁定 v1 只做 HTTPS basic auth。真出现「Docker Hub 公共镜像」场景时加一个枚举值即可 |
| **主密钥轮换 / 密文重加密工具** | 没有轮换流程就没有需求；主密钥独立配置已经让将来轮换成为可能 |
| **凭据版本历史 / 审计「变更前后值」** | 指纹能回答「变没变」，回答不了「变成什么」—— 后者不该存 |
| **删除保护**（被流水线引用时禁止删） | 不加外键是既定的架构原则；`enabled=0` 提供了比删除温和的选项 |
| **单条详情端点** | 列表已返回全部字段 |
| **`type` 的其它取值**（ES 等） | 台账「模型留扩展位但**不预先实现**其余类型」。加类型是加一个枚举值 + 一个表单分支 |
| **真对端集成测试** | 见 §0.1 未验证项 |
| **对 kubeconfig 主密钥硬编码默认值的修复** | 属另一个决定，本批**不动也不复制** |

### 12.1 已知风险（接受，不假装平台能解决）

**平台级凭据 = 平台的凭据。** 用它做的操作，在 GitLab / Harbor 侧看来都是「平台」做的，
而不是「租户 A」做的。若那条 token 权限很宽，租户 A 就能借它碰到租户 B 的仓库。

**正解在基础设施侧**：每个租户一个 deploy token / Harbor robot account + project 隔离。
平台侧只能靠「别把宽权限凭据发下去」来兜。这条**会在 UI 上以说明文案呈现**，
不假装平台侧的权限模型能替代它。
