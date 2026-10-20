# 任务台账（2026-10-12）

> 本轮「补全期」的工作台账：把已裁定的需求拆成 **spec 批**与**任务**，作为后续逐批 spec 的索引与进度表。
>
> - 需求依据、裁定理据、方案细节 → [gap-analysis-2026-10-12.md](gap-analysis-2026-10-12.md)（下称「分析文档」）
> - 各批的详细设计 → `docs/superpowers/specs/`（逐批产出）
> - 各批的实施计划 → `docs/superpowers/plans/`（spec 之后产出）
>
> **「不做」清单见分析文档 §5.2**（事件中心 / NetworkPolicy / Job·CronJob / PDB / K8s 原生 RBAC /
> CRD 查看器 / Ingress / 系统设置 / 列表增强 / 集群运维深度 等）—— **不在本台账中，勿重复上报**。

---

## 0. 拆分原则

**两级粒度**：

| 级别 | 判据 | 产物 |
|---|---|---|
| **Spec 批**（S 编号） | 一个**可独立上线**、有独立产品面的模块 —— 不需要等别的批一起才可用 | 一份 spec 文档 |
| **任务**（S#.n） | 一个**能独立出实施计划、能被单独评审**的单元；拆到「评审者可以只否掉它而放过相邻任务」为止 | spec 里的一个章节 |
| （更细）步骤 | TDD 步骤、具体文件、代码块 | 每份 spec 的 `plans/` 实施计划 |

**依赖用「→」表示**（`S0.2 → S3.1` 读作「S0.2 是 S3.1 的前置」）。
**规模用 S/M/L 表示**（相对量级，不是时间承诺）。

---

## 1. 全局先决决策（动手前必须定，会同时影响多批）

| # | 决策 | 影响 | 现状 |
|---|---|---|---|
| **D1** | **release 状态存哪** —— 平台自己存（DB 记 values + 渲染历史），还是真走 `helm install/upgrade`（Helm 成为第二写入方） | S3.2 / S3.3 / S3.4 | ⬜ 未定（分析文档 §7.1） |
| **D2** | **日志采集器形态** —— OTel webhook 注入（与模板编辑零冲突）vs 自建 filebeat sidecar（与模板编辑/回滚/重下发冲突） | S2.3 | ✅ **已定（2026-10-19）**：OTel operator 注入；文件日志用 per-Pod **sidecar + filelog**（卷/注解由平台加，与模板编辑同一套 SSA 写入） |
| **D3** | **「日志采集」与「OTel 采集」是同一件事还是两件事** —— OTel sidecar 采日志本身就是一种日志采集方式；若两者是一件事，S2.3 合并为一批 | S2.3 | ✅ **已合并（2026-10-19）**：应用日志（agent appender）与文件日志（sidecar）都归 OTel 一条链 |
| **D4** | **审计的埋点机制** —— 统一拦截（`PermissionAuthorizationManager` 之后）vs 各 Service 写路径埋点 | S0.1 | ⬜ 未定（漏埋点的审计不如没有） |
| **D5** | **平台总览的口径** —— 平台级统计 + 健康摘要分别取哪些数 | S1.3 | ⬜ 未定 |
| **D6** | **租户可见性**：Helm 商店 / Operator 管理对租户开放到什么程度 | S3.3 / S3.4 | ⬜ 未定 |

---

## 2. 台账正文

### S0 · 平台治理地基（无前置）

#### S0.1 审计日志 ｜ 规模 L
> 唯一「不做就补不回来」的一项 —— 越早埋点覆盖越全。分析文档 §5.1 #5 / §7.4.3。

- [ ] S0.1.1 **D4 决策** + 表设计（`platform_audit_log`：who / when / cluster / namespace / 资源 / 动作 / 结果 / 来源 IP），遵守「不加外键」约束
- [ ] S0.1.2 迁移脚本（日期序号接 `V2026_10_12_1` 之后）
- [ ] S0.1.3 埋点实现（按 D4 定的机制）+ 覆盖清点：资源写操作 / 权限与角色变更 / 命名空间分配 / 集群纳管 / **凭据使用**（S0.2）/ **CI 触发**（S3.5）/ **Operator 安装**（S3.4）
- [ ] S0.1.4 查询页（平台管理员）+ 权限点 `platform:page:audit` / `platform:audit:read` + **显式绑角色**（不按 code 继承 —— 踩过的坑）
- [ ] S0.1.5 保留策略（多久、谁清理、是否分区）

> **✅ 实施完成（2026-10-13）**：机制=注解 + AOP 管写操作、复用已有 `accessDeniedHandler` 管 403；
> 表 `platform_audit_log`（17 列 4 索引）+ 3 条权限行（只绑 admin）。
>
> **⚠️ 拦截点经用户评审后从 Service 上移到 Controller**（理由见 spec 顶部修订记录）：
> 审计单元是「一次用户请求」；Service 方法可能被多个入口复用/内部编排调用，会造成重复记录。
> 代价是**该层不再需要 `@Order` 排事务外侧** —— 原先最容易写错的一整块复杂度消失。
> 动作覆盖也一并补齐：**4 个 Controller 的 23 个写端点全标**（工作负载 7 · 命名空间 7 · 节点 4 · 集群 5），
> 不再有「后续批次」；其余族未动（不做全平台横扫，也不维护写端点清单）。
> **加注解不需要动迁移、不需要动权限表。**
>
> **执行期发现并订正的既有事实错误**（spec 与 plan 已同步）：
> 1. **本仓没有引入 Flyway** —— `db/migration/*.sql` 一直是**手工重放**的，应用不会自动执行它；
>    原「看 Migrating schema 日志」的验收判据不成立。
> 2. 计划里测试命令的 `-DfailIfNoTests=false` 无效（`-am` 连带跑兄弟模块时要用
>    `-Dsurefire.failIfNoSpecifiedTests=false`）。
> 3. 计划原稿的 `AuditAspect` 把成功路径的 `record` 放在业务 `try` 内，会让「审计失败不影响业务」
>    完全寄存在 `AuditService` 的 try/catch 里 —— TDD 已抓出并改为 `recordQuietly()` 本地兜底。
>
> **未验证（留给部署后）**：真集群端到端（删工作负载 / 触发 403 后查审计）、审计页的筛选与 purge、
> 以及「`REQUIRES_NEW` 抗业务回滚」—— 后者目前只由一条反射钉子守着（详见 spec §5.1 与 plan Task 9）。
>
> **既有失败（非本批引入）**：`k8s-core` 的 `WorkloadConverterStaticIpTest` 在 @HEAD 即 NPE，与本批无关。

#### S0.2 凭据与连接管理 ｜ 规模 M（原估 L，2026-10-12 复审下调）
> **实际消费方只有两个：CI（git 凭据）与镜像仓库 / Helm 商店（同一条 registry/chart-repo 连接）。**
> 应用级交付与告警**不需要**（kubeconfig 已加密存在 `k8s_cluster`；告警是入站 webhook，只要校验密钥）。
> 详见分析文档 §7.6 的复审修正。
>
> **为什么仍独立成批**：镜像仓库（S3.1）排在 CI（S3.5）**之前** —— 若把凭据模型写进 CI spec，
> S3.1 就要引用一份尚未产出的 spec，依赖方向与执行顺序打架。
> （它不是「抽公共基类」——那是否决过的形态；这是有表/页面/权限点/生命周期的实体模块。）
>
> **✅ 2026-10-12 用户裁定：保持独立成批**（不并入镜像仓库）。理由取「三条口径一次定清 +
> 将来加连接类型只动一处」这一面。**此决定不再复议。**

- [ ] S0.2.1 连接模型与表（`connection(type, endpoint, credential, scope)`）—— **先只落两类：`git`、`registry`**
      （chart-repo 与 registry 共用一条 Harbor 连接），模型留扩展位但**不预先实现**其余类型
- [ ] S0.2.2 凭据加密存储（复用 kubeconfig 的 `AESUtils` 先例；主密钥来自配置，**不进 DB、不上页面**）
- [ ] S0.2.3 管理页 + 权限点（**读 / 写 / 使用** 三段独立）+ 迁移
- [ ] S0.2.4 **永不回显**：列表与详情只返回「是否已配置 + 指纹」
- [ ] S0.2.5 **投递能力**：注入构建 Pod（CI，→ S3.5.13）/ 生成命名空间 Secret / imagePullSecret（→ S3.1.3）——
      并定清「谁能读明文、投递到哪」
- [ ] S0.2.6 使用审计（→ S0.1.3）

> **✅ 实施完成（2026-10-13）**：表 `platform_credential`（13 列，硬删除）+ 6 行权限（3 个码，
> 只绑 admin）。范围 = **凭据库内核 + 测试连接**；投递（命名空间 Secret / imagePullSecret /
> 构建 Pod 注入）留给 S3.1.3 / S3.5.13。设计见 `docs/superpowers/specs/2026-10-13-credential-management-design.md`，
> 实施计划见 `docs/superpowers/plans/2026-10-13-credential-management.md`（13 任务）。
>
> **两条口径在实现期被实测改掉**（spec §0 有完整实测表）：
> 1. 「公开仓库会忽略错凭据 → `info/refs` 探针假阳性」的假设**被推翻**：实测 gitee / gitlab
>    带错密码对公共仓库返回 **401**。所以不引入 `provider` 字段、不做按 host 分派。
> 2. 但发现两类**新的**假阳性/假阴性：`200 + HTML`（代理登录页）会骗过只看状态码的判定
>    → 改为**校验响应体以 git pkt-line 头开头**；GitLab 少 `.git` 是 301
>    → **不猜后缀、不跟随重定向**，3xx 时把 `Location` 给用户一键回填。
>
> **401 是二义的**（实测：不存在的仓库与错密码同码）→ 文案一律「凭据不通过或无权访问此仓库」，
> **不得写「密码错误」**。
>
> **有意不做**：SSH、按租户授权、匿名连接、主密钥轮换、删除保护、凭据版本历史、其它 `type`。
>
> **未验证（留给部署后）**：github.com 的 git 端点（实测机器连不上那个 IP）、
> 有效凭据 + 需认证仓库的成功路径、Harbor `/v2/` 的 401 行为。
> 「真实服务器出站可达性」见 spec §0.1 的环境提醒。
>
> **执行期发现的三处「计划本身有错」**（已在实施计划文件里就地订正，非代码问题）：
> ① 我误读一次命令输出，把前端常量文件路径写成 `src/constants/`（实际在 `src/`）；
> ② Task 2 的 javadoc 里 `secret*/description` 的 `*/` **提前闭合注释块**，直接编译失败；
> ③ Task 6 的反射钉子 banned 列表含 `"secret"`，而 `CredentialView.hasSecret` 正好含它 → 钉子会
> 把自己钉死（改为「`Boolean` 类型的 `hasSecret` 是唯一例外」）。
> 另有三处实现层面的缺陷靠 TDD/实测抓出：Task 3 的错密钥用例会 **1/256 概率假红**（flaky）、
> Task 11 的 `REGISTRY` 测试连接**结果无处显示**（点了没反应）、Task 12 让从 `@element-plus/icons-vue`
> 导入图标（实际图标是 `main.ts` 全局注册的）。
>
> **⚠️ 全量测试 583 绿的一个前提**：`k8s-core` 报 210 绿，是因为
> `WorkloadConverterStaticIpTest.java` **正被有意删在工作区**（@HEAD 它会 NPE，S0.1 时就记为既有失败）。
> 该删除**不在本批提交内**。
>
> ---
>
> ### ⚠️ 2026-10-14 补记：**一个会让人完全看不到页面的 bug，交付当天才发现**
>
> **症状**：凭据页的菜单项不显示；手输 `/credentials` 被路由守卫跳回总览。1 个用户可见的完全失效。
>
> **根因**：迁移 `V2026_10_13_3` 里 Page 权限行的 id 是 **`perm_page_credential`**，而角色关联那一行
> 写成了 **`perm_credential_page`**（词序颠倒）。本仓**不加外键**，所以这条 INSERT 照常成功、
> **不报任何错**，只留下一条**悬空关联**（指向不存在的权限行）→ `platform:page:credential` 实际
> 没绑给任何角色 → admin 没有这个码 → 菜单与路由守卫同时失效，且日志里一片安静。
>
> **为什么全套验证没拦住**：Task 1 的验收只数了 `id like 'rp_admin_credential%'` = **6 行** ——
> 数的是**行数**，不是**指向是否命中**。行数对、指向错，验收照样绿。
> 5 个 API 码恰好拼写正确，只有 Page 那个错了，所以它连"部分失效"都不算，是**静默全失效**。
>
> **修复**（提交 `2084d12`）：改正 id 后重放。ODKU 命中同一条主键，就地更新 `permission_id`，
> **不会留下孤儿行**。修复后验证：6 条关联全部 join 命中真实权限行、**全表悬空关联扫描 = 0**、
> admin 确实持有该码。
>
> **教训（已写进迁移文件本身当注释）**：`platform_role_permission.permission_id` 与
> `platform_permission.id` 必须逐字一致，而**没有外键托底**；因此**重放后的验收必须做 join 对账**，
> 不能只数行数。这条对后续所有批次都适用 —— 它不特定于 S0.2。
>
> **同时订正一个数字**：本批最终全量测试是 **586 绿**（终端评审后又补了 3 条测试），
> 上文正文里的 583 是终审修复之前的快照。

> ---
>
> ### ⚠️ 2026-10-14 补记：`authType` 扩出 `NONE`（匿名）
>
> 由 **S3.1 的 Docker Hub 增补**带出来的改动（用户裁定「Docker Hub 只允许匿名」）。
> 详设见 [dockerhub spec](superpowers/specs/2026-10-14-registry-dockerhub-design.md) §3.7。
>
> - **`authType` 从只有 `BASIC` 扩成 `BASIC | NONE`**。「该类型允许哪种认证方式」
>   **由各档自己声明**（`RegistryDialect.authTypes()`）；`GIT` 不是 dialect，它的集合是
>   `CredentialService` 里的常量 —— 全平台唯一一处例外，注释里写明
> - `NONE` 时**不写密文**（两列都 null），`hasSecret` 由「`secret_encrypted` 是否为空」得出
> - 出站时**不发 `Authorization` 头**（原来会发 `Basic Og==`）
> - **匿名档的 `/v2/` 401 判为通过** —— OCI 规范下未认证返 401 是 registry 的正常行为，
>   不这么判的话每一条匿名连接一建出来就是红的
> - **顺带修掉本批的一条死路**：「留空 = 保持不变」让**凭据一旦填了就再也清不掉**
>   （表单拿不到旧值，"空"只能读作"不改"）。现在 `authType` 改成 `NONE` 即清空
>
> **零迁移** —— `V2026_10_13_3` 的三列注释当初就写了「可空是为将来 NONE 型（匿名）留位」。
> 本批的「有意不做」清单里也原话写着「真出现『Docker Hub 公共镜像』场景时加一个枚举值即可」。
>
> **全量测试 474 绿**（platform-api；本批新增 68 条）。

---

### S1 · 平台侧小项（无前置，适合先跑通节奏）

#### S1.1 用户自助 ｜ 规模 S
- [ ] S1.1.1 改密码（`platform-auth` 端点 + 前端页面）
- [ ] S1.1.2 改资料（displayName / email）
- [ ] S1.1.3 清掉 `platform-auth` `ignore-urls` 里两个**不存在的静态页**（`/register.html`、`/forgot-password.html`）
- [ ] S1.1.4（可选，待定）登录失败锁定 / 在线会话与强制下线

#### S1.2 集群组件展示 ｜ 规模 M
> **是 S3.4 的地基**（先能看见，才谈装）。分析文档 §5.1 #7 / §7.1。

- [x] S1.2.1 组件清单模型（名称 / 是否部署 / 版本 / 健康 / 命名空间）
- [x] S1.2.2 探测实现：**收敛现有零散探测**（capability API group 探测、ambient 的 ztunnel 活探测）+ 新增首批内容（**OpenTelemetry Operator**、ES、Alertmanager、snapshot-controller 等）
- [x] S1.2.3 集群详情页展示（现为 4 tab，新增一个 tab 或并入概览）
- [x] S1.2.4 顺带成为各功能**门控的统一数据源**（capability 判据单一出处）

> **✅ 实施完成（2026-10-17）｜待部署｜spec [2026-10-17-cluster-components-design](superpowers/specs/2026-10-17-cluster-components-design.md) · plan [2026-10-17-cluster-components](superpowers/plans/2026-10-17-cluster-components.md)**
>
> **做了什么**
> - **目录（唯一出处）**：platform-api `ClusterComponentCatalog`，首批 **11 条** —— metrics-server / prometheus-operator /
>   gateway-api / istio / istio-ambient / tekton / tekton-operator / calico / tigera-operator / cert-manager / otel-operator。
>   每条组件的「支撑哪些平台功能」只写一遍，`feature → 组件` 的反查是算出来的。
> - **探测**：k8s-core `WorkloadProbeOperations` 按 `(namespace, kind)` 各一次 list（成本与命名空间数量无关），
>   **按镜像仓库片段匹配**（真集群上一半工作负载名带 Helm release 前缀）；DaemonSet 健康用
>   `numberReady/desiredNumberScheduled`（`readyReplicas` 实测为空）。k8s-server 新增 `POST /workload-probes`
>   （**UPSTREAM**，租户可达），候选命名空间必须落在 `platform.k8s.probe-namespaces` 清单内，越界**整请求 400**。
> - **纯 CRD 组件的版本**（2026-10-17 追加）：探测契约扩一种 `kind=crd`（集群级，按名字 get 一次），
>   版本读 CRD 的 `*bundle-version` 标签/注解 —— Gateway API 因此能显示实测 `v1.6.2`（真集群确认）。
>   CRD 不受命名空间清单约束，另配 `platform.k8s.crd-probes`（有默认值）。
> - **派生**：`ClusterComponentService` → 四态（HEALTHY / UNHEALTHY / NOT_INSTALLED / **UNKNOWN**）+ Redis 45s 缓存，
>   两个投影：`/cluster/components`（全量，管理员）与 `/cluster/component-flags`（功能旗标，租户可读）。
>   **UNKNOWN 与 NOT_INSTALLED 严格分开**：探测失败/未探测不得说成"没装"。
> - **门控收口**：CI（Tekton 那一半）/ 网格（三条布尔）/ HPA（metrics-server）改读同一份派生；
>   `/mesh/status` 对外形状不变（前端零改动）。
> - **前端**：集群详情页第 5 个 tab「集群组件」（四态徽标、影响的功能、缺组件给指引**不给安装按钮**）；
>   概览的 7 个能力 tag 收敛为一行摘要 + 跳转；Istio/Calico 改显示**实测版本**、人工登记值降为 tooltip
>   （不一致时 tooltip 明说）。
>
> **与 spec / plan 的出入（均已论证或实测）**
> - 迁移文件名用 `V2026_10_17_4`（`_1/_2/_3` 已分别被租户 RBAC CRD、网格拓扑、告警占用）。
> - `tenant:cluster:capability:view` 那行**绑三个内置角色**（admin / tenant_admin / tenant_member）——
>   只绑 admin 等于租户角色一条都没有，租户进 CI 页直接 403。
> - 网格只有 **ambient** 改读旗标：`service-mesh` 这个功能旗标同时覆盖 istio + gateway-api，拆不出两个布尔；
>   而这两个问的是"API group 在不在"，按决策 7 仍以 capability 列为唯一判据。
> - Calico 的两个 group 判据**有意保留** capability：`network` 旗标含 tigera-operator 健康，拿它当 Calico 页门禁
>   会在 operator 重启时把整页禁用，而 CRD 还在、页面要看的对象也读得到。
> - 组件全量投影在**页面加载时**拉一次（概览摘要要用），不是切 tab 才拉；`lazy` 仍保留（面板不额外请求）。
> - `capability` 列为空按**全局**"未探测"处理（对只有工作负载判据的 istio-ambient 同样成立），
>   否则同一集群会出现一半未探测、一半没装。
>
> **有意不做（别当漏做）**：安装 / 升级 / 卸载（留给 S3.3、S3.4）· 控制面组件
> （etcd / apiserver / scheduler / controller-manager）· `local-path-provisioner` /
> `nfs-subdir-external-provisioner` / `snapshot-controller` 不展示 ·
> **`eck-operator` 推迟到 S2.3**（日志采集开工时先决定它进不进这份清单）。
>
> **残留清理（2026-10-17 第二批完成）**：`POST /mesh/status` + `MeshOperations` + `AdminMeshProbeResult` +
> `K8sMeshClient.meshAmbient()` + `KubernetesOperationsFactory.getMeshOperation` 已删除（ztunnel 探测只剩组件清单一处）；
> `/cluster/overview` 的 `capabilitySummary` 七 flag 与 `CapabilitySummaryDTO` 已删（前端换摘要后无消费方）；
> `ClusterCapabilityService` 只剩 `groups` / `versionsOf` / `hasIstio`（group 存在性的唯一读取处），
> `summary` / `hasTekton` 与不再使用的 group 常量一并删除。
>
> **真集群实测（2026-10-17，只读 `coding-cluster`）**：10 条工作负载判据全部命中（含
> `ps-kube-prometheus-stack-operator` 这类 release 前缀名）；`metrics.k8s.io` 不在 apiservice 列表 →
> **10 装 1 缺**，缺的正是 metrics-server，与 spec §6.1 的例子一致。istio 实测版本 1.30.3（标签），
> 登记值 1.24 会作为 tooltip 显示不一致。
>
> **部署**：重建 **k8s-server + platform-api + platform-web**。迁移 `V2026_10_17_4__cluster_components.sql`
> （4 条权限行 + 6 条角色关联，**已在 dev 库重放并回查**）**先重放再起新构建**；k8s-server 新配置
> `platform.k8s.probe-namespaces` 与 `platform.k8s.crd-probes`（都有默认值，不配也能跑）。
> 无新外部依赖、无集群侧前提。
> ⚠️ **本仓没有 Flyway**，`db/migration/*.sql` 一律手工重放。
>
> **2026-10-19 目录再收敛**：`gateway-api` 并入 `istio`（同一安装单元 —— 装 Istio 必带 Gateway API CRD，
> 否则网关/路由页不可用）。当前组件目录 **7 条**，`service-mesh` / `ambient-mesh` / `routes` 都由 `istio` 回答；
> `hasGatewayApi` 改由 capability 的 group 版本回答（Istio 组件探测只认 istiod）。
> 存量集群升级 Istio 时 helm 会接管已有的 `platform-gateway-api` release。


> **📌 2026-10-14 新增需求：平台能部署 / 升级 / 卸载集群组件**（用户提出；未实施、未出 spec）
>
> 用户原话：*「集群的组件其实影响着集群的性能。如 istio 服务网格，calico 地址池，tekton ci 组件，
> metrics-server 影响 hpa，prometheus 影响监控告警。默认的 storageclass? opentelemetry 影响采集数据。
> 所以我希望在集群管理页面…可以部署他们。卸载，升级他们。」*
>
> **这个需求拆开是「三件已规划的事 + 一层新的东西」**：
>
> | 用户要的 | 其实是 |
> |---|---|
> | 看见「装没装 / 什么版本 / 健不健康」 | **本批 S1.2 本体** |
> | 装 / 升级 / 卸载的执行通道 | **S3.3 Helm 商店 + S3.4 Operator 管理**（istio / calico / tekton / prometheus 的官方装法都是 operator 或 helm，**逐个不同**） |
> | **「缺这个 → 平台的哪些功能不可用 → 点这里补上」** | ← **这才是新的那一层** |
>
> **决定（2026-10-14）**：**不新开「集群组件管理」模块**。那会造出三条平行的「往集群写东西」的通道
> （组件管理 / Helm 商店 / Operator 管理），各写一遍集群写入、各自一套权限与审计 —— 正是本仓一直在避免的形状。
> 改成：把上面那层**组件↔平台功能依赖映射并进 S1.2**，执行**复用** S3.3 / S3.4。
> 于是 S1.2 是**目录与门控的单一出处**，而执行只有一条路。
>
> **组件按「装错的后果」分两档，决定平台该管到多深**：
>
> | | 例子 | 装错的后果 | 平台该做到 |
> |---|---|---|---|
> | **只提供能力型** | metrics-server、Prometheus、Tekton、OTel Operator | **只是功能不可用**（HPA 灰 / 曲线空 / CI 不可用 / 没有采集数据） | 可以**很主动**：缺就标「缺」+ 一键补齐 |
> | **改变集群行为型** | **Calico 地址池**、Istio、默认 StorageClass | **会真出事**（网络断 / 断流 / 影响所有新建 PVC） | **最多只给入口，不该自动改** |
>
> **纳管策略：既不做纳管硬标准，也不完全不干涉 —— 分级门控**：
> 1. **关键档**（缺了大面积不可用）：metrics-server、默认 StorageClass、Tekton、Prometheus → 显眼标缺 + 补齐入口
> 2. **可选档**（istio / otel / calico 地址池）：装了才显示对应功能，不装不影响别的
> 3. **已经装的一律只探测、不接管**，除非用户明确点「交给平台管」（与 S3.4 的「已装 Operator 探测」是同一个问题）
>
> **三条必须写进设计的风险**：
> 1. **「卸载」比「安装」危险一个量级** —— 卸载 Calico 是集群网络全断；卸载 Istio 而数据面还有 sidecar 是断流。
>    倾向：**只允许卸载「平台自己装、且能证明没在被使用」的组件**，其余只给指引。
> 2. **装组件 = 平台凭据的权限推到 cluster-admin** —— `k8s_cluster` 里存着 kubeconfig，
>    「装组件」等于用它 apply 任意 manifest。**不是新增风险**（calico 页面已经在改地址池），
>    但暴露面用宽了，值得显式记一条而不是默认不说。
> 3. **版本与配置的长期账** —— 一旦平台宣称"能装"，用户就期望"能升级、出问题你能修"
>    （Istio 升级有严格顺序：控制面 → 数据面）。建议平台只做**「装 + 提示版本落后」**，不承担升级编排。
>
> **顺序（2026-10-14 用户裁定）**：**先做 S3.5（CI），S1.2 排后** ——
> 因此 S3.5 里只做一次**最小 capability 探测**，并在其 spec 里写明「将来 S1.2 接手这块门控」，
> 免得探测逻辑写死在两处。

#### S1.3 平台总览 ｜ 规模 M
> 租户概览已在做；本项是**平台视角**的总览（分析文档 T4-2 对侧）。

- [ ] S1.3.1 **D5 决策**（口径）
- [ ] S1.3.2 指标与健康摘要的数据来源（含聚类：集群 / 租户 / 分配）
- [ ] S1.3.3 前端改造（现 `OverviewView` 是管理员形状的静态统计 + 快捷入口）

---

### S2 · 排障与观测闭环

#### S2.1 工作负载级日志 ｜ 规模 M
- [x] S2.1.1 **`previous` 容器日志**（后端查询参数 + 前端切换）—— 解 CrashLoopBackOff 最高频场景，改动极小，建议先做
- [x] S2.1.2 工作负载详情按 labelSelector **聚合多 Pod 日志**
- [ ] S2.1.3（可选）多容器选择 / 时间范围下推 —— **两项都未做**，理由见下面「与台账原稿的三处出入」

> **✅ 实施完成（2026-10-16）｜2026-10-17 用户验收通过，需求完结**：S2.1.1 + S2.1.2 都做了。
> 之后又按用户意见迭代了两轮：入口挪到**工作负载列表页行操作列**（`7bfa824`）、
> 聚合视图补工具栏（`4beb7b1`）、加彩色并修好两个弹窗的全屏（`7328511`）。
> **详情页那个「全部 Pod 日志」按钮用户裁定保留**（同一层的动作，进了详情页还想看日志是常规诉求）——
> 于是入口有意有**两处**，不是漏删。
> 设计见 `docs/superpowers/specs/2026-10-16-workload-logs-design.md`，
> 实施计划见 `docs/superpowers/plans/2026-10-16-workload-logs.md`（8 任务）。
>
> - **S2.1.1 `previous`**：两个 Pod 日志端点各加一个 `previous` 参数，单 Pod 日志弹窗加「上一次」开关。
>   ⚠️ fabric8 里它**叫 `terminated()`**（反编译确认拼的是 `&previous=true`），不是 `previous()`。
>   **真集群实测**了两件事：kubelet 确实保留上一代日志（两代内容不同）；而
>   `--previous --since-time=<现在>` 输出长度为 0 —— 上一次实例是**已终止**的容器、不会再产出，
>   所以「上一次」模式**固定走 tailLines 看一次快照、不轮询**。
> - **S2.1.2 聚合**：入口两处 —— **工作负载列表页行操作列的「日志」**（用户指定：从列表直接看，
>   不必先点进详情）＋详情页操作栏的「全部 Pod 日志」。列表页那处要先 `get` 一次工作负载拿
>   `spec.selector`（列表 DTO 不带它），再按 selector 反查 Pod，**两次往返**；期间行上的「⋯」转圈。
>   之后先勾选 Pod（默认最近创建的 10 个、上限 20），确定后合成**一条带 `[pod/<pod>/<容器>]` 前缀的流**。
>   形态**照抄 `kubectl logs -l … --all-containers --prefix`**（k8s 核心只有 Pod 的 `logs` 子资源，
>   没有工作负载级的；kubectl 那套本来就是客户端扇出）。每个 (pod, 容器) 各自一个 `sinceTime` 游标，
>   每 2 秒收齐所有来源的新行、**在本轮内**按时间戳排好再追加；某个来源坏了只插一行说明、不影响其余。
>   工具栏与单 Pod 的 `PodLogDialog` 对齐（用户要求）：**行数窗口（100/200/500/1000，换即重开快照）、
>   搜索 + 匹配数、清除、重新加载、换一批 Pod 在左，彩色 + 全屏在右上角**。有意没有容器下拉
>   （天然跟全部容器）。缓冲也从"一整块字符串"改成**行数组**，否则搜索/裁剪每轮都要 split 全文；
>   而且行按 `{prefix, text}` **分开存** —— 着色用的 Spring Boot 正则要求行以时间戳开头，
>   前缀拼进去就一行都匹配不上（开关在、没一行变色）。
>
> **着色规则统一在 `utils/logColor.ts`**：一套分段定义、两个渲染器（单 Pod 走 xterm 要 ANSI，
> 聚合视图是 `<pre>` 要走 HTML 片段）。聚合视图用 `v-html`，所以每段都**先转义** ——
> 日志内容是集群里的进程写出来的，等于不可信输入。
>
> **⚠️ 全屏两个坑（都在 EP 的 dialog 盒模型上）**：
> ① **弹窗比屏幕高、最外层长出滑动条**：EP 的全屏规则只把 `--el-dialog-margin-top` 改成 0，
>    `margin` 简写仍由 `.el-dialog` 给（同特异性），而组件里那条 longhand `margin-top: 7rem`
>    排在 EP 样式之后 —— **全屏下也照样生效**，于是 `height:100%` 之外又叠了 7rem。
>    修法：`margin-top` 只写在 `:not(.is-fullscreen)` 里。真跑验证过：把旧的 `!important` 打回去，
>    最外层溢出**正好复现 112px**（=7rem）。
> ② **全屏后内容不跟着长**：日志框原来写死 `56vh`，弹窗长大了它不跟。改成 `flex:1` +
>    `:not(.is-fullscreen){height:min(720px,85vh)}`，全屏交给 EP 的 `height:100%`。
>
> 附：EP 里真正的滚动容器是**内层** `.el-overlay-dialog`（`position:fixed;inset:0;overflow:auto`），
> `.el-overlay` 自己没设 overflow —— 量"最外层有没有滑动条"时别量错层。
>
> **⚠️ 与台账原稿的三处出入**：
> ① **S2.1.3 的「多容器选择」不做** —— 由「勾 Pod 即跟它的全部容器」覆盖，前缀把容器分开就够了；
> ② **S2.1.3 的「时间范围下推」不做** —— 那要有日志后端，属 S2.3；
> ③ 聚合视图**不做** `previous`（用户裁定：失败原因走"点进那个 Pod"那条路，那条路已由 ① 补齐）。
>
> **⚠️ 与最初设想最大的一点不同：不新增端点、不新增权限行、无迁移。**
> 聚合是前端对**既有的单 Pod 日志端点多发几次** —— 那两个端点本来就是前端直连 k8s-server 的，
> 每一次抓取的授权与今天逐个点开完全一样。服务端聚合要设计「游标映射进 / 新行映射出」，
> 换来的只是省几个浏览器→k8s-server 的往返，而抓取量已被 20 个的上限压住。
>
> **顺带修掉一个既有隐患**：`PodLogDialog` 里原本是「找第一个制表符」来剥 k8s 时间戳前缀 ——
> 内容里自带制表符的行（`ls -l`、表格化的应用日志）会被从中间劈开，**而且那个假时间戳会被当成游标推上去**，
> 之后的增量全乱。现在两处视图共用 `platform-web/src/utils/logLines.ts` 这一份口径。
>
> **有意不做 / 已知缺口**（完整清单见 spec §8）：历史日志（Pod 删了就没有，要引日志后端 → S2.3）·
> 服务端检索 / 时间范围下推 · 跨 Pod 的**全局**时间排序（只在本轮内排）· 勾选表里的"异常优先"预选 ·
> 自动纳入滚动更新中新起的 Pod（勾选那一刻定下的就是那批）。
>
> **测试**：后端 +4 条（k8s-server 65 绿）；前端**本仓没有测试框架**，一律走浏览器冒烟台
> （logLines 14/14、单 Pod 弹窗 9/9、聚合勾选 14/14、聚合流式 13/13、端到端入口 9/9），
> 全仓 `mvn test` 1027 绿。关键行为都做了变异验证（确认测试真的会咬）。
>
> **部署**：重建 **k8s-server + platform-web**；platform-api 不动、数据库不动。

#### S2.2 告警 ｜ 规模 L
> 已定：PrometheusRule 做；**Alertmanager 已部署、平台不做其管理面、不对外暴露**；webhook 落库；
> **发送渠道由平台接管**（S2.2.4，2026-10-20：Alertmanager 只往平台推，发不发/发给谁由平台决定）。
>
> **已实施待部署**（主体 2026-10-17 的 19 任务；**S2.2.4 通知方式 2026-10-20 收口**，14 任务 + 一轮独立评审修复）。
> 全项目 `mvn test` **983 绿**；前端 type-check + build 绿；真集群 + 真库 + 本地 SMTP 收信器都验过（见下）。

- [x] S2.2.1 `PrometheusRule` CRUD（通用 CRD 范式，同 Calico/Gateway）
- [x] S2.2.2 平台侧接收端点 —— ⚠️ **不是 `/api/v2/alerts` 契约**：Alertmanager 的 webhook receiver 推的是
      `[]Alert` 数组，形状与 `GET /api/v2/alerts` **返回**一致，但与那个端点的**查询参数**无关。
      端点 `POST /alert/webhook?clusterId=<id>`，**回真实 HTTP 状态码**（401/400/200）
- [x] S2.2.3 告警落库 + 列表页 + 权限点
- [x] S2.2.4 通知方式（**2026-10-20 已实施待部署**）—— 平台接管「发给谁 / 怎么发」：通知方式实体（类型 + JSON 连接配置 +
      租户属性 + 字段级加密）、规则 ↔ 通知方式/收件人 绑定（CR 级，存平台表）、告警到达后异步发邮件 + 投递记录 + 失败重试 3 次。
      [spec](superpowers/specs/2026-10-19-notification-design.md) · [plan](superpowers/plans/2026-10-19-notification.md)
- [x] S2.2.5 通知内容与规则字段收口（**2026-10-20**，S2.2.4 之后的连续打磨）
      - **通知正文写全**：集群 / 租户 / 命名空间 / 规则 / 严重度 / 状态 / 开始（含已持续时长）/ PromQL 表达式 /
        摘要 / 详情 / Runbook / 全部标签。表达式投递时查一次 `/api/v1/rules`（webhook payload 里没有）；
        集群名与租户名与告警详情页共用 `AlertNotifyContextService`。
      - **默认收件人三级回落**：绑定 → 租户管理员 → **平台管理员**（集群级告警的命名空间不属于任何租户，靠这一级）。
      - **规则字段专用入口**：严重度（下拉）/ 摘要 / 详情 / 排障链接 做成独立输入，后端拼进
        `labels.severity` 与 `annotations.summary|description|runbook_url`、读详情时摘回；
        这几个键在通用「标签 / 注解」编辑器里**不可写**（前端标红提示 + 后端丢弃并 warn）。
      - **时长字段前后端校验**：`interval / for / keep_firing_for / query_offset`，纯数字会被 apiserver 422。
      - **评审修复 7 条**：投递只带本次领取成功的事件（groupBy 重推不再重复通知）、收件人必须是本租户成员、
        清空通知方式要真删绑定、默认渠道查询不再过滤 enabled、`/options` 改窄投影（不再下发 SMTP 配置）、
        SMTP 加连接/读写超时、启动时把中断的 PENDING 标 FAILED。
      - **Watchdog 不投递**：kube-prometheus-stack 的心跳规则（`vector(1)` 恒 firing）只落库、不发通知，
        连投递记录都不写；列表里本来也默认隐藏（`showWatchdog=true` 才看）。
      - **未做（有意）**：告警抑制（下一轮，`AlertDispatchService.beforeSend()` 钩子已留）；
        失败过的投递不占「补发」通道（补好收件人后不会自动重发，靠下一次重推）。

> **三块**：① PrometheusRule CRUD（逐层照抄 ServiceMonitor，`groups`/`rules` 都是 atomic list →
> 按组名/规则名对齐的 fetch-overlay）；② 运行状态回读（`k8s_cluster.prometheus_url` + `/api/v1/rules`，
> **K8s 对象是唯一真源**，Prometheus 只补状态，拉不到就是「未知」且 CRUD 照常）；③ Alertmanager webhook 落库 + 列表。
>
> **真实测**：① 对齐算法拿真集群数据跑 —— 36 条 CR 全部 loaded、280 条规则 0 条 unknown、
> `AlertmanagerClusterCrashlooping=ok/firing`；② webhook 真机连投两次 → `accepted:1` 且库里**只有 1 行**
> （幂等键 `(cluster_id, fingerprint, starts_at)` 生效）、`.123456` 截成 `.123`；③ 五种状态码逐个 curl 验过。
>
> **⚠️ 计划里的两处错误已在实施中纠正**（都会让功能看起来能用、实际不可用）：
> 1. 「进 `ExemptPaths` 就跳过 JWT 鉴权」**不对** —— 那只跳权限表；认证层还要 `spring.security.ignore-urls`。
> 2. 即便放行，带 `Authorization: Bearer <共享密钥>` 的请求会被 **JWT 解码器**先 401 ——
>    `ResourceServerConfig.bearerTokenResolver` 必须对 `/alert/webhook` 返回 null。
>
> **⚠️ 部署前提**：`ALERT_WEBHOOK_TOKEN` 必须配置，否则 **platform-api 拒启**（fail-closed，有意为之）。
>
> **部署**：先重放 **`V2026_10_17_3__alerting.sql`**（注意不是计划里写的 10_17_1 —— 那个号被
> 「租户 RBAC 模板 CRD 组」占了，10_17_2 被网格拓扑占了），**再**起新构建；重建
> **platform-data + platform-common + k8s-core + k8s-server + platform-api + platform-web**；
> **重新登录**（权限行变了）。集群侧四步见 `docs/development/cluster-prerequisites.md` §1.8 ——
> 其中 `alertmanagerConfigMatcherStrategy` 与 `sendResolved` **配错会静默半工作**，必须逐条验。


#### S2.3 日志采集 + OTel 采集 ｜ 规模 L ｜ ✅ **已完成（2026-10-19）**
> 依赖：端点与凭据走 S0.2；存储为 ES（用户在 `platform-system` 自建）。

- [x] S2.3.1 **D2 / D3 决策** —— 2026-10-19：D2 = OTel 注入（应用日志 agent appender / 文件日志 per-Pod sidecar），D3 = 合并为一批
- [x] S2.3.2 采集配置（ES 端点、索引策略）—— 表单化：ES 地址 + `monitor` 内的凭证 Secret（key 固定 `username` / `password`）+ 固定按天索引 `otel-logs-YYYY.MM.DD`
- [x] S2.3.3 注入/下发实现 —— Instrumentation（traces / logs / all）+ gateway collector（traces→Tempo、logs→ES）+ sidecar（filelog，具体文件）
- [x] S2.3.4 日志查询 UI + 权限点 —— `POST /logs/search` + 日志查询中心（顶栏租户/集群/命名空间、工作负载/Pod 下拉+模糊、关键词高亮、级别、时间、分页、日志↔链路）；权限 `tenant:log:query` / `tenant:page:observability.logs`（`V2026_10_19_6`）
- [x] S2.3.5 filebeat 交互设计 —— **不适用**：最终未选 filebeat；文件日志走 OTel sidecar，卷/注解与模板编辑同一套 SSA 写入

> **交付与部署（2026-10-19）**：设计见 `superpowers/specs/2026-10-18-otel-observability-design.md`；
> 采集侧 = `otel-operator` 组件（operator + Tempo + collector gateway + sidecar），查询侧 = 日志查询中心。
> 需重放 `V2026_10_19_6`（日志查询权限）与 `V2026_10_19_7`（租户链路权限），
> 重建 platform-api + k8s-server + platform-web；集群侧需配置 ES 连接
> （`PLATFORM_ES_URL` / `PLATFORM_ES_USERNAME` / `PLATFORM_ES_PASSWORD`）与 `monitor` 内的 ES 凭证
> Secret（key 固定 `username` / `password`）。
> **隔离口径**：日志按 `platform_tenant_namespace` 的 (集群, 命名空间) 收窄；链路按 span 的
> `resource.cluster.name` + `resource.k8s.namespace.name` 过滤（列表在 Tempo 侧、详情在平台侧）。

#### S2.4 服务网格可视化 ｜ 规模 M-L
> 已定：**自研**（不引 Kiali、不引 Loki）。分析文档 §7.7。

- [x] S2.4.1 拓扑数据查询（Thanos 的 `istio_requests_total` / `istio_request_duration_milliseconds_bucket` → 节点与边） —— 2026-10-17 已实施
- [x] S2.4.2 拓扑图前端（节点 = workload，边 = 速率 / 错误率 / 延迟） —— 2026-10-17 已实施（ECharts `graph` + 自算静态分层布局，左→右 + 层内上下）
- [ ] S2.4.3 链路追踪接入（Jaeger 或 Grafana Tempo；嵌入或跳转） —— **用户 2026-10-17 明确：后期会做**（不是砍掉）。现在做不了是因为集群里**没有任何追踪后端**（jaeger/tempo/zipkin 都没有，otel collector 也没配 traces 管道）；开工时要先部署后端 + 让业务透传 `traceparent`
- [x] S2.4.4 确认 ambient 模式下指标是否齐备（ztunnel / waypoint 的指标产出与命名） —— **已实测回答**（见 spec §7）
- [x] S2.4.5 **Service 列表 / 详情补网格信息**（用户 2026-10-17 提出）—— **2026-10-17 已实施，纯前端**
      **做了什么**：
      ① 列表加「网格」列：`waypoint: xxx` / `L7 未启用`（显式 none）/ `跟随命名空间`；
      ② 详情**抽屉**加「服务网格」区块：本服务 waypoint、继承优先级、集群 ambient，以及「在拓扑里看」；
      ③ 拓扑页支持 `?focus=<ns>/<workload>[,<id>…]`，只显示聚焦节点 + 直接邻居，带「看全图」；
      找不到时给明确提示（多半是这段时间没有 L7 流量）。
      **⚠️ 命名空间级的值租户读不到** —— `/namespace/get` 要 `platform:allocation:list`（只有 admin 有），
      `/namespaces` 在 k8s-server 是 **PLATFORM 边界**（token 透传，租户调用必 403）。
      所以「没设」只如实显示"跟随命名空间"，**不替它编一个值**；要显示真值得新增一条租户可达的窄端点
      （k8s-server 加 TENANT 边界读 + platform-api 投影 + 权限行），记作后续可选增强。
      **「在拓扑里看」的映射**：拓扑节点是工作负载，Service 侧先用 `selector` 反查工作负载再带过去；
      没有 selector（headless / ExternalName / 端点外部管理）就直接去全图。

---

### S3 · 交付能力（本阶段主体，量大）

#### S3.1 镜像仓库 ｜ 规模 M ｜ 前置：S0.2
- [ ] S3.1.1 registry connection 接入 + **三档实现**（Harbor 自己的 REST API v2.0 / 通用 registry v2 `/v2/<name>/tags/list` / Docker Hub 走 PAT）
- [ ] S3.1.2 tags autocomplete（**缓存 + 静默降级**）接到工作负载「更新镜像」
- [ ] S3.1.3 imagePullSecret 自动生成（→ S0.2.5）

> **✅ 实施完成（2026-10-14）**：Harbor 一档 + 方言扩展点 + 两处输入框补全 + 只读浏览页。
> 设计见 `docs/superpowers/specs/2026-10-14-registry-design.md`，实施计划见
> `docs/superpowers/plans/2026-10-14-registry.md`（15 任务）。
>
> **⚠️ 与台账原稿的两处范围出入（均为用户裁定）**：
> ① **S3.1.3 imagePullSecret 不做** —— 用户裁定「先不做，后续再说」。它把平台级凭据的可用范围
> 落到租户命名空间里（租户对自己命名空间的 Secret 有读权限），是一次真实的数据面扩张，值得单独决策；
> ② ~~**S3.1.1 只落 Harbor 一档**，`REGISTRY_V2` / `DOCKERHUB` 不做（用户：「现在只支持 harbor，
> 后续可能还要支持其他类型的仓库」）。~~
> **⚠️ 2026-10-14 订正：这一条是记账错误。** 2026-10-12 的裁定 ⑥ 原文是「**Docker Hub autocomplete 按本文建议接**
> （PAT + 缓存 + 静默降级）」（分析文档 §7.5），本条目原稿写的也是「**三档实现**」，
> 而 S3.1 brainstorming 里用户批准的那个选项原文就是 `GIT|HARBOR|REGISTRY_V2|DOCKERHUB`。
> 唯一被当成「砍掉」依据的，是用户回答**「浏览页加不加」**那道题时附的一句「现在只支持 harbor…你考虑设计的问题」
> —— 那是「现状如此、按可扩展来设计」，**不是砍掉已裁定要接的一档**。
> 现已在 [dockerhub spec](superpowers/specs/2026-10-14-registry-dockerhub-design.md) 里补做：
> Docker Hub 一档（**只允许匿名**，用户 2026-10-14 裁定）+ **候选跨连接聚合**。
> **`REGISTRY_V2` 记为欠账** —— 它同样没有「按关键词搜仓库」的能力，补全做不出来。
>
> **`type` 枚举由 `REGISTRY` 收敛为 `HARBOR`**（只放已实现的方言值，**有意不预放空值** ——
> 留一个「能选中但没实现」的类型，管理员会建出一条建了也没用的连接）；
> 扩展位由 `RegistryDialect` 接口 + `RegistryDialectRegistry` + **一条护栏测试**提供 ——
> 加一档 = 加枚举值 + 实现类 + UI 选项，候选端点 / 缓存 / 权限 / 浏览页 / 前端**全不动**。
> 护栏不只是写着：**实测过一次故意红**（给 `TYPES` 塞一个没人实现的 `GITEA`，测试立刻报
> 「该镜像仓库类型尚未实现: GITEA」）。
> **加档时要一并做的**：S0.2 的 `registryProbeUrl` 假定「所有 registry 都响应 `/v2/`」，
> 对 Artifactory 不成立 —— 届时要把 probe 挪进 dialect（spec §11 的清单）。
>
> **权限**：`tenant:registry:catalog`（API，覆盖 suggest / browse / connections 三个只读端点）
> + `tenant:page:registry`（Page）。必须是 `tenant:` 前缀 —— `assertScopeMatches` 规定
> TENANT 角色只能持 `tenant:` 码。拆 API/Page 两码是为了让「能补全但进不了浏览页」可表达。
> 三个端点**共用**一个 API 码（同一件事的三个入口、同一页面同一批人）。
>
> **三条设计取舍**（都是为了让「加档时前端不用改」，且**本身就比写死的版本更简单**）：
> 路径驱动浏览（面包屑由后端给、层级深度由方言定，不写死三层）、候选返回**要填进输入框的完整串**
> （拼接规则是方言相关的）、表格列由 `details` 的标签决定。
>
> **两种失败姿态有意不同**：`/registry/suggest` 静默降级（每按键一次的高频动作，弹错会掩盖真问题，
> 也会让用户以为「填不了镜像」）；`/registry/browse` 报错（用户主动点的导航，什么都不说等于页面坏了）。
>
> **有意不做**：imagePullSecret、**`REGISTRY_V2` 档（Docker Hub 已于 2026-10-14 补做，见上）**、
> ~~无 host 的镜像写法（隐式 Docker Hub，一律空候选）~~（**2026-10-14 契约已反转**：无 host 时问全部连接）、
> 镜像扫描/签名/清理/删除、**候选与浏览的审计**（高频只读，逐次记会淹掉审计表）。
>
> **已知风险（接受）**：租户能看到平台所有已配 registry 的目录（仓库名 + tag 名，**不含凭据**）——
> 这是 S0.2「纯平台级 + 全局可用」那条决定的直接延伸，现在落到了具体数据上。
>
> **执行期发现并订正的「计划本身有错」共 9 处**（已在实施计划文件里就地订正，非代码问题）：
> `hostOf` 用「第一个 `/` 之前」会把 Docker Hub 的 `myteam/app` 判成 host（改用 Docker 自己的规则）；
> `HarborDialect` 少了两个接口方法（照抄编译不过）；`parseRepositoryNames` 缺 project 参数却要做剥离；
> 两个 `onStatus` 的注册顺序（我一度改反，被测试抓回）；`RedisJsonCache` 的 import 缺两处；
> **缓存键带了前缀而该类自己也会加前缀 → 双前缀、永不命中**（而原断言恰好把它钉成了期望行为）；
> `connections.value[0].id` 在 `noUncheckedIndexedAccess` 下报 TS2532；
> 以及测试数/行数的几处算术。
> **其中一条最值得记**：`npx vue-tsc --noEmit -p tsconfig.json` **通过**，而 `npm run build`
> 打出 `error TS2532` —— **两个检查不等价，构建期更严**；而计划里那条 build 断言
> （`grep -E "built in|error"`）在两者同时打印时**仍会 echo BUILD_OK**。已在计划里注明「build 那步要人眼看」。
>
> ---
>
> **⚠️ 终审（独立评审者）抓到一个 Critical，已修**：**tag 补全恒不工作**。
> `segmentsOf` 只按 `/` 切，`:tag` 原样留在最后一段 → 拼出的仓库名是 `app:1.2` →
> 请求打到 `/repositories/app:1.2/artifacts` → 真实 Harbor 回 404 → 补全的静默降级把它吞掉 →
> **在任何输入下都返回空候选，且无声**。也就是本批两件交付物之一（tag 补全）实际没工作。
>
> **它能一路绿灯的原因**：`HarborDialectTest` 的 21 条测试**全部**打在 static 纯函数上，
> `suggest` / `browse` **零覆盖** —— 而 bug 恰恰在「拼出来的 URL 长什么样」这一步。
> 计划 Review Focus 第 4 条声称「每条都有测试钉住」，这一条当时不成立。
>
> **同批修掉的另外 7 处**（都先写失败测试）：spec §9 要求的两条「姿态差异」测试缺失；
> `RegistryService` 里手写的 `REGISTRY_TYPES` 是**第二个真相源**（加一档忘了改它会「全测绿但新档静默失效」，
> 已删掉改问分派表）；`endpoint` 的四种合法写法（带 scheme / 尾斜杠 / 大写）会让补全静默死掉而 browse 正常；
> 浏览页缺 spec 明确要求的 **digest 列**；前端「失败静默」被 axios 拦截器击穿（每按键一条红条，
> 已加 `silent403`）；**每次按键解密全部凭据**（一条坏密文让所有人的补全全挂，已改成只解密命中的那条）；
> 类型收敛后遗留的「仅支持 GIT / REGISTRY」错误文案（改成从 `TYPES` 动态取，不会再过时）。
>
> **修复后全量 656/656 绿**（platform-common 19 · k8s-core 210 · k8s-server 20 · platform-api 407）。

> ---
>
> **✅ 2026-10-14 增补：Docker Hub 档 + 候选跨连接聚合**（设计见
> [dockerhub spec](superpowers/specs/2026-10-14-registry-dockerhub-design.md)，
> 实施计划见 [plan](superpowers/plans/2026-10-14-registry-dockerhub.md)，15 任务）。
>
> 起因是**订正一条记账错误**（见上文 ② 那行的 2026-10-14 订正）：Docker Hub 是 2026-10-12
> 就裁定要接的，被误记成「用户裁定不做」。补做的同时带出两件事：
>
> **1. 候选跨连接聚合**（反转了本批 §4.1 的「无 host 一律空候选」契约）。
> 原来那么写是因为当时只有 Harbor 一档、只能猜该归哪条连接；代价是 Docker Hub 档
> 只能在用户手打 `docker.io/` 时生效，等于白做。现在：**带 host 只问匹配上的那一条**
> （匹配不上仍零请求）、**不带 host 问所有启用的镜像仓库连接**，结果按连接名排序后合并，
> 每条候选带上来源连接名（前端下拉右侧显示）。那个「只能猜」的歧义由此消失。
> 并发走虚拟线程 + **3 秒总预算** —— 刻意小于单条连接最坏耗时（5s+10s），
> 一条黑洞连接被取消、按空算，**其余连接的候选照常返回**。
>
> **2. `authType` 扩出 `NONE`（匿名）** —— 见上文 **S0.2 的 2026-10-14 补记**。
>
> **Docker Hub 档的两条特性**：**只允许匿名**（用户裁定 —— 这一档的角色是「公共镜像的来源」，
> 私有镜像该放自建 Harbor）；**出站地址写死 `hub.docker.com`**，不是它的 endpoint `docker.io`
> （endpoint 只用于匹配）。走站点 API 而非 OCI 官方的 `registry-1.docker.io`，是因为后者
> **没有搜索接口**，补全根本做不出来。
>
> **顺带修掉一个现存 bug**：`HarborDialect.segmentsOf` 拿**原始 endpoint** 做 `startsWith`，
> 而终审那次只给 `RegistryTargetResolver` 加了归一化。于是 endpoint 填成
> `https://harbor.example.com`（GIT 的 placeholder 正是 `https://gitlab.example.com`，切类型时极易残留）时，
> 连接匹配得上、段切分取不到段 → **补全静默返回空，浏览页却一切正常**。与终审那条 Critical 同一形状。
>
> **有意不做**：`REGISTRY_V2` 档（记为欠账）、Docker Hub 的私有仓库（封死 PAT 的直接后果）、
> 浏览任意公共命名空间（需要浏览页有输入框）。
>
> **全量测试 474 绿**（platform-api，本批新增 68 条）+ 前端 `npm run build` 零 TS error。

> ---
>
> **✅ 2026-10-14 再补：Docker Hub 改成平台内置档**（上线前的一次改动，推翻了上面那条增补的一部分）
>
> 用户裁定「内置隐式、不要记录」。它现在是 `BuiltinRegistry.DOCKER_HUB` 常量，
> **不进 `platform_credential`** —— 没有行、没有 authType、没有密文、没有启停状态，
> 重放迁移后开箱即用，不用任何人手动建。
>
> **起因是一次真实排查**：用户为 Docker Hub 配好了代理却仍然没有候选。查库发现
> `platform_credential` 里**一条镜像仓库连接都没有**，于是 `suggest` 在第一个 guard
> 就返回了、**一个出站请求都没发**，代理白配；而那个 guard 当时**是静默的**，日志里一片安静。
>
> **落地方式**（避免"内置"这个特例传染）：新增 `RegistryService.allSources()` 作为
> **「有哪些来源」的唯一真相源**，内置与凭据的分叉只在那里发生一次，
> 下游（聚合 / 并发 / 排序 / 候选带来源名 / 浏览）只认新引入的 `Source` 形状。
> 浏览页下拉里会出现内置档（合成 id `builtin:dockerhub`），`browse` 认这个 id。
> 开关 `platform.registry.dockerhub.enabled`（默认 true）。
>
> **连带回退**：`CredentialService.TYPES` 去掉 `DOCKERHUB`，凭据页不再提供这个类型 ——
> 上面那批刚交付的两处前端（类型选项、endpoint 预填只读联动）随之回退。
>
> **同批做的第二件事：出站代理按目标白名单分流**（`platform.outbound.proxy.{server,port,targets}` —— **刻意不叫 host/hosts**，那对只差一个字母、一个指代理服务器一个指要走它的目标，最容易看反），
> `RegistryHttpClient` 与 `CredentialProbeClient` 共用一个 `ProxySelector`。
> 只有白名单里的目标走代理，内网（自建 Harbor / gitlab、k8s-server、Thanos、Prometheus）
> 一律直连；白名单为空 = 全直连（默认，fail-safe）。这补上了原先「补全通、测试连接不通」
> 的不一致。用户原先写死在 `RegistryHttpClient` 里那段已撤掉。
>
> **踩到一个只有启动校验看得见的坑**：两个 client 各有第二个（包级私有、给测试用的）构造器，
> Spring 在多个构造器且无无参构造器时**不会自己挑** —— 必须给生产的那个标 `@Autowired`，
> 否则启动直接 `No default constructor found`。**全量测试全绿也照样过**，只有真启动拦得住。
>
> **全量测试 744/744 绿**（19 · 215 · 20 · 490）+ 前端 0 TS error + 启动校验 276 个 endpoint 不变。
>
> ⚠️ **两条改动尚未在真实环境验证**：内置档在**浏览器**里打出候选（本轮只到「单元测试 +
> 启动校验」），以及代理白名单配上之后 `hub.docker.com` / `github.com` 的实际连通。

> ⚠️ 本批再次复现 S3.1 记过的那条：**`npx vue-tsc --noEmit` 会零报错放过真错误，
> `npm run build` 才报** —— 前端收口一律用后者。

#### S3.2 应用级交付（自研薄应用层）｜ 规模 L ｜ 前置：**D1**（**不需要** S0.2 —— kubeconfig 已在 `k8s_cluster` 表）
> 已定：自研（分析文档 §6.4）。产品形态自研、渲染引擎内部用 Helm。

- [ ] S3.2.1 **D1 决策** + 应用模型与表（应用 = 模板 + values + 渲染历史）
- [ ] S3.2.2 渲染引擎（`helm template`）
- [ ] S3.2.3 复用现有 SSA apply 通道（不新建写入路径）
- [ ] S3.2.4 应用列表 / 详情 / 升级 / 回滚页 + 权限点 + 迁移
- [ ] S3.2.5 漂移处理（真相源在平台侧；检测到被外部改动时的策略）

#### S3.3 Helm Chart 商店 ｜ 规模 M ｜ 前置：S3.2
- [ ] S3.3.1 来源接入：**本地 Harbor（OCI chart）** + **Artifact Hub 公共索引**（发现/搜索）
- [ ] S3.3.2 chart 搜索 / 浏览页
- [ ] S3.3.3 values 表单（schema → 表单）或 YAML
- [ ] S3.3.4 安装流程（复用 S3.2 渲染 + apply）
- [ ] S3.3.5 **D6 决策**（租户可见性：能否装任意 chart、配额怎么算、危险 chart 怎么限）

#### S3.4 Operator 管理 ｜ 规模 L ｜ 前置：S1.2、**D1**
> 用户明确：**独立模块，不能用 Helm 商店替代**；Helm 只是它的部署方式之一。支持安装与升级。

- [ ] S3.4.1 已装 Operator 探测（CRD group + controller Deployment）
- [ ] S3.4.2 Operator 详情：**它提供了哪些 CRD、各有多少实例**（Operator 的价值所在）
- [ ] S3.4.3 安装（来源：Helm chart 或 manifest bundle）
- [ ] S3.4.4 升级（当前版本 vs 目标版本）
- [ ] S3.4.5 **D6 决策**（租户可见性）

#### S3.5 CI（Tekton）｜ 规模 XL ｜ 前置：S0.2
> 已定全部关键需求：自由 DAG、内置/自定义节点、条件分支 + finally、**只手动触发**。
> 详见分析文档 §7.4.1–§7.4.3。

> **📌 2026-10-16 收口（用户裁定"这个大需求先做到这里"）**
>
> **片① + 片② 已交付**，spec = `docs/superpowers/specs/2026-10-15-ci-tekton-design.md`。
> 下面这些勾**已成立**：S3.5.1 / S3.5.3 / S3.5.4（画布 + 并发语义提示）/ S3.5.6 / S3.5.7 /
> S3.5.9 / S3.5.10 / S3.5.12（5 个权限码，定性记在 spec §10.1）/ S3.5.13。
> S3.5.5 的「跳过传播可视化」也做了（画布上把没跑的节点画出来并说清为什么）。
>
> **变形的两条**（不是没做，是改成了另一个东西）：
> - **S3.5.5 条件分支 → 有意不做**：Tekton 的 `tasks[].when` **不能引用执行状态**
>   （dry-run 验过，webhook 直接拒），按参数分支又要先有 S3.5.8 的运行时参数。
>   画布上只给 `onError`（失败也继续）+ `finally`（收尾执行）两个开关。
> - **S3.5.2 自定义节点 / S3.5.8 运行时参数 / S3.5.11 并发策略 → 片③，未做。**
>
> ⚠️ 另外：**spec 的做/不做以它 §10 那张表为准**（§1 那份是片①时的边界，已经标了）。

- [ ] S3.5.1 节点模型：**内置节点模板 + 字段 schema**（类型 / 校验 / 默认值 / 中文说明 / 是否可引用上游）+ 版本化
- [ ] S3.5.2 **自定义节点**（隶属流水线、级联删除）
- [ ] S3.5.3 渲染器：画布 JSON → Tekton `Pipeline`，**`tasks[].taskSpec` 全部内联**（消掉跨命名空间引用与 ClusterTask 废弃问题）
- [ ] S3.5.4 画布前端（LogicFlow 或 Vue Flow）：自由连线 + 环检测 + **并行语义提示**（没连线 = 并发）
- [ ] S3.5.5 **条件分支**（`when` 三段式：字段 + in/notin + 值）+ **跳过会传播到下游**的可视化
- [ ] S3.5.6 `finally` 收尾节点
- [ ] S3.5.7 **隐式共享 workspace**（平台注入到所有节点，用户无感 —— Tekton 特有的坑）
- [ ] S3.5.8 运行表单（**运行时参数 vs 固定配置**分离）
- [ ] S3.5.9 触发（**仅手动**）：平台创建 `PipelineRun`；**不引入 Tekton Triggers**
- [ ] S3.5.10 运行历史 / 日志页 + 权限点
- [ ] S3.5.11 并发与重复触发策略（禁止 / 排队 / 允许并行）
- [ ] S3.5.12 ⚠️ **权限与安全**：构建 Pod 能跑任意代码 —— 定义/触发/看日志分开授权、能跑在哪个命名空间、构建步骤镜像来源是否限制
- [ ] S3.5.13 git 节点接 S0.2 的 git 连接（地址 + 凭据）

> **📌 2026-10-14 调研结论**（用户指示先调研后再动；**未写 spec**）
>
> **Tekton 客户端**：`io.fabric8:tekton-client` 与 `kubernetes-client` **完全同步发版**
> （本仓 fabric8 7.9.0 → tekton-client 7.9.0，另有 8.0.0）。`tekton-model` 建模了 **GA 的
> `io.fabric8.tekton.v1.{Pipeline, PipelineRun, PipelineSpec, Task, TaskRun, TaskSpec}`**，
> `tekton-client` 本体只有 15 KB（薄适配器，给 `TektonClient` DSL）。
> **推荐用它（typed model）**，不走本仓的通用 CRD 范式 —— 决定性的是 `pipelineSpec` 的字段量
> （`tasks[].taskSpec{steps[],workspaces,results,volumes}` + `finally[]` + `workspaces[]` + `when` 三段式）
> 远大于本仓现有任何 CRD（HTTPRoute 的 `rules[].matches/filters/backendRefs`）。
> ⚠️ 唯一没验的：**集群的 Tekton 版本** —— 若有只有 `v1beta1` 的老集群，
> 通用路径的「按 capability 分派版本」更省事（本仓 Gateway 就是这么干的）。
>
> **部署方案**：本批**只需要 Tekton Pipelines**（台账已定「仅手动触发」+「不引入 Tekton Triggers」）。
> 官方给的 profile：`lite` = 只有 Pipeline；`basic` = +Trigger/Chains；`all`(K8s) = +Dashboard。
> 所以：**Operator + `profile: lite`**，或**不用 operator** 直接
> `kubectl apply -f https://infra.tekton.dev/tekton-releases/pipeline/latest/release.yaml`。
> ⚠️ **顺序反直觉**：官方 install 文档明说 operator 那一步**会连带装上 pipelines/triggers/chains/dashboard**，
> `lite` 是之后再用 `TektonConfig` 收敛（「收敛时会不会把已装的多余组件卸干净」文档没写死，**要实测**）。
> ⚠️ 官方对 vanilla 那条路的原话是 "not meant for the production use"，生产推荐用 operator。
>
> **一个顺带的好发现**：**PipelineRun 的日志就是它创建的 Pod 的日志**（每个 TaskRun 起一个 Pod）
> → **S3.5.10 的运行历史 / 日志页可以直接复用现有的 Pod 日志通道**（含 WS 流式与 8KB 预览），不用另写一套。
> 若先做 S2.1（工作负载日志），那批的成果在这里直接兑现。
>
> ⚠️ **待定（开工前必须定）**：S3.1 明确「**不做 imagePullSecret**」，而构建 Pod 要拉基础镜像
> （`golang:1.22` / `maven:3.9` …）—— 走私有 Harbor 那条路现在是断的。三个选择：
> **(a)** 这批顺带把 imagePullSecret 做了；**(b)** 只用公开基础镜像，私有镜像的用户自己建 Secret；
> **(c)** 用 S0.2 的连接给构建 Pod 注入 docker config（等价于 a，但落在 Pod 层而非命名空间 Secret）。
> **倾向 (a)** —— 它是「代码 → 构建 → 镜像 → 部署」这条链上唯一还断着的一环，CI 会把它从"可选"变成"必需"。
>
> ⚠️ **集群侧前提**（平台不负责装，但要有 capability 门控 —— 缺了整块隐藏，不是报错）：
> 装 Tekton 要 `cluster-admin`、集群 ≥ 1.28；
> **租户 SA 的 K8s RBAC 模板要覆盖 `tekton.dev` 组**（与当年 Gateway 那个坑同形）；
> PipelineRun 在**租户命名空间**里起 Pod → 配额、PSA、拉取凭据都要通。

#### S3.6 组件生命周期（安装 / 升级）｜ 规模 L ｜ 前置：S1.2（已完成）

> **2026-10-17 用户新建需求**：「对集群概览中的组件，如未探活的可以进行安装部署、升级」。
> 设计见 [component-lifecycle spec](superpowers/specs/2026-10-17-component-lifecycle-design.md)。
> **本批部分推翻了 2026-10-14 的「执行复用 S3.3/S3.4」裁定**：执行能力提前到本批，
> 但仍只有一条写入实现；通用 Helm 商店（S3.3）与 Operator 管理（S3.4）将来是另一件事，与本批无关。

- [x] S3.6.1 `cluster_role`（MANAGEMENT / BUSINESS）+ 画像化 recipe 目录 —— 批①（2026-10-17）
- [x] S3.6.2 内置 chart（上游 + thin chart）+ sha256 校验 —— 批①（2026-10-17）
- [x] S3.6.3 helm 执行链（预检 / 执行 / 收尾）与异步操作记录 —— 批①（2026-10-17）
- [x] S3.6.4 组件 tab 的安装 / 升级与进度视图 —— 批①（2026-10-17）
- [ ] S3.6.5 逐个组件的配置契约与功能级验收（Prometheus 的 selector / externalLabels / remoteWrite 是重点）
  —— **已验收 6/7**：metrics-server、prometheus-operator（含 Thanos）、tekton、otel-operator（含 Tempo）、
  cert-manager、istio（含 Gateway API）；**剩 calico**

**brainstorming 已定**：只做目录内 11 条；安装 + 升级（不做卸载/回滚按钮）；统一 Helm 执行器
（含 thin chart）；chart 内置进 k8s-server；集群画像区分 management / business（Thanos 挂
Prometheus 的 management recipe）；异步操作记录 + 轮询；release 名与命名空间由 recipe 固定；
只升级平台自己装的 release；成功以 S1.2 探测为准。

**醒目风险**：`kube-prometheus-stack` 的 `*SelectorNilUsesHelmValues` 默认 `true` —— 不改成 `false`，
平台在租户命名空间创建的 PrometheusRule / ServiceMonitor / PodMonitor 会被**静默忽略**（装上了但告警不生效）。


> **✅ 批① 实施完成（2026-10-17）｜待部署｜plan [2026-10-17-component-lifecycle-batch1](superpowers/plans/2026-10-17-component-lifecycle-batch1.md)**
>
> **做了什么（引擎 + metrics-server 试点）**
> - `cluster_role`（MANAGEMENT / BUSINESS）+ 全局唯一校验；`platform_component_operation` 操作表。
> - k8s-server 内置 chart 目录（文本形态，非 tgz）+ `ChartBundle`（版本漂移明确报错）+ `HelmRunner`
>   （Helm 4.2.0、`--rollback-on-failure`、临时 kubeconfig 0600 + finally 删除）。
> - `POST /components/apply`（PLATFORM 边界）：`helm template` 硬预检 → `--dry-run=server` 软警告 → `helm upgrade --install`。
> - platform-api `ComponentRecipeCatalog`（metrics-server 一条，含契约值 `--kubelet-insecure-tls`）+
>   `ComponentInstallService`（异步 + 并发拒绝 + 中断标 UNKNOWN + 装完刷新探测）。
> - 三条端点 + 审计；组件 tab 的安装/升级按钮与轮询；集群列表的画像展示。
>
> **与计划的出入（均已 ledger 记录）**
> - MySQL 不支持 `ADD COLUMN IF NOT EXISTS` → 迁移改用 information_schema + PREPARE 幂等写法。
> - 假 helm 用同 JVM 小程序而非 shell 脚本（Windows 的 PATH 里 bash 可能是 WSL，退出码 127）。
> - 多 release 失败即停（不继续装依赖它的后续 release）。
> - 测试里手工构造的 `ClusterService` 需补 `AES_KEY`（@Value 注入在单测里为 null）。
>
> **未执行**
> - **真集群试点（在非生产集群装 metrics-server）未做** —— 需要用户明确批准；当前没有非生产集群。
>   上线前应补这一步，或在部署冒烟时完成。
>
> **待办**
> - **批②：其余 10 条组件的 recipe 与配置契约**（Prometheus 的 selector/externalLabels/remoteWrite、
>   gateway-api 的 experimental channel、Tekton 的 coschedule、Calico 的 CIDR 等）。批② 开工前先决定
>   嵌套 values 的合并方式（批① 只支持顶层 key）。
> - 组件 tab 的"哪些组件可安装"目前在前端硬编码（`recipeCodes = ['metrics-server']`）；
>   批② 改为由后端下发。
>
> **部署**
> - 重建 **k8s-server + platform-api + platform-web**。
> - **先重放** `V2026_10_18_1__component_lifecycle.sql`（cluster_role 列 + 操作表 + 三条权限行），
>   重放后把平台自身所在集群改为 `MANAGEMENT`（见迁移注释里的 UPDATE）。
> - k8s-server 镜像构建需要 helm 4.2.0 二进制（构建机下载或内网镜像），`charts/` 目录随镜像复制。
> - 无需迁移之外的外部依赖；运行时不需要外网。
>
> **验证**：修复后全模块 `mvn test` **1239 绿**（common 21 / core 365 / server 97 / auth 8 / api 748）；
> 前端 `type-check` + `build` 通过；浏览器冒烟覆盖三种按钮态与 apply→轮询→refresh 全链路；
> 迁移在 dev 库重放并回查（含两个生成列 + 唯一键）。
>
> **独立评审后的修复（`099b5ca`）**：helm 读输出阻塞导致超时不触发 · 请求漏传 componentCode
> （release 标签会变成 null）· 并发插入 TOCTOU（生成列 + 唯一键）· message 超长丢失败原因 ·
> 后端不校验"只升级平台装的"· 升级不复用上次 values · 陈旧操作只在启动扫一次 · UI 升级传旧版本号。
> **顺带发现（未处理）**：platform-api 没有 `@EnableScheduling`，`PipelineRetentionService` 的
> `@Scheduled` 很可能从未执行 —— 已记录，未擅自开启。

> **✅ 批② 实施完成 + 功能级验收（2026-10-18 → 2026-10-20）**
>
> - 11 条组件的 recipe 与配置契约全部落地；12 个 chart 原样入库
>   （`charts/components/<chart>/<version>/`，每个 chart 目录带组件说明：安装契约 / 踩坑 / 新增版本）。
> - 可编辑项表单：存储（StorageClass + 容量）、资源（四格）、Service 类型与端口表（监听协议 / TLS 模式 /
>   证书 Secret）、Tekton profile、Calico CIDR、Thanos 凭证、OTel 采样/Tempo/ES。
> - 平台联动：Prometheus 告警回调（Secret + AlertmanagerConfig）、Thanos 托管安装、ingress `Telemetry`、
>   Istio `external-gateway` Gateway CR（`addresses` 绑定 Service + listeners 由端口表派生）。
> - **真集群功能级验收**：metrics-server、prometheus-operator（含 Thanos）、tekton、otel-operator（含 Tempo）、
>   cert-manager、istio（含 Gateway API）**全部通过**；**剩 calico 未验收**。
> - 验收中修掉的真问题：ambient 三件套缺 `PILOT_ENABLE_AMBIENT`；external-gateway `Programmed=False`
>   （chart 不建 CR、CR 又没写 addresses）；组件版本取第一个 release 导致 Istio 显示成 1.6.2；
>   多 release 的可编辑值不回显；端口表「仅 Service」被默认值补回；Thanos 四条 absent 告警
>   （ServiceMonitor 未开）；node_exporter 缺 `--collector.cpu.info`。
>
> **部署**：先重放 `V2026_10_18_1`（同批①），重建 **k8s-server + platform-api + platform-web**。
> `istio` 安装/重放会额外创建 `istio-system/external-gateway` Gateway CR（不再需要手工维护）。

---

---

### S4 · 收尾

#### S4.1 PVC 扩容 ｜ 规模 S ｜ ✅ 2026-10-16 完成（已实施待部署）
> 扩容做成了**独立端点** `POST /resource/pvcs/{name}/resize`（不是塞进 PUT）——
> 与工作负载的 `POST /{name}/image`、`POST /{name}/pause` 同形：**端点与权限行按「一次有意义的操作」分，
> 权限码按「资源 + write 家族」共享**（这里沿用 `tenant:pvc:update`）。迁移 `V2026_10_16_3`（已重放）。
> ⚠️ 最初我把它塞进了 `PUT /resource/pvcs/{name}`，理由是"端点与权限行本来就有、零迁移" ——
> 那让 PUT 的语义从「同步标签」悄悄变成「也能扩容」（动存储后端、异步、不可逆），
> 权限与审计都说不清。**这个仓的惯例是分开的，台账原文写的「+ 权限点 + 迁移」本来就是对的那条。**
- [x] S4.1.1 `StorageClass.allowVolumeExpansion` 门控 —— 数据本来就有（`StorageClassDTO` 里带了），
      判断落在 `PvcService.resize`；读不到存储类时 **fail-closed** 拒掉
- [x] S4.1.2 只增不减的前后端双重校验 + 权限点 + 迁移 —— 权限码 `tenant:pvc:update` **复用**
      （早就有，绑对了：tenant_admin 持有、tenant_member 只拿读动词），但**权限行与迁移是新加的**
      （新端点在 PermissionCrossCheckRunner 下必须有自己的行，否则拒启）。
      前端那道拦住了**不发请求**，后端才是权威（它读集群上那个对象）
- [x] S4.1.3 中间态展示（`Resizing` / `FileSystemResizePending` **不是异常**）——
      ⚠️ 它们**不在 `phase` 上**（扩容期间 `phase` 一直是 `Bound`），所以 DTO 补了 `conditions`、converter 补了映射
- [x] S4.1.4 STS `volumeClaimTemplate` 的特例处理 —— ⚠️ **原方案里的「改 template」是错的**：
      K8s 不允许改 StatefulSet 的 `spec.volumeClaimTemplates`，改了会被 API server 拒。
      所以维持**只读**，补的是"为什么只读"的说明（另见 commit `dc6b0df`）

> ⚠️ **收口时真撞到的那个坑（2026-10-16，`2e84329`）**：门控只看 `StorageClass.allowVolumeExpansion`
> 是**假绿灯** —— 本集群两个 SC 都写着 `true`，两个都做不到（provisioner 不是 CSI，没有
> `external-resizer` 去执行）。「允许扩」≠「有人执行扩」。判据改成
> `allowVolumeExpansion && provisioner ∈ 集群已注册的 CSIDriver 名单`，三态
> `unsupported` / `csi` / `unbacked`（唯一算法在 `CoreV1StorageClassOperations.fillExpansion`）。
> 顺带修掉两个真 bug：扩容走 `storageClasses.get()` 是 **PLATFORM 边界、租户一律 403**
> （改走窄投影 `expansionOf()`）；PVC 的 `update()` 把读回来的对象当 SSA apply config 发，
> 被 `metadata.managedFields must be nil` 拒（**这个 op 的老 bug，不是扩容引入的；未补回归测试** ——
> fabric8 自引用泛型挡掉了 MockStubs）。
- [x] S4.1.5（补）列表页「可扩容」列 + 不能扩就把「调整容量」禁掉（`31666be`）；
      存储类页加「在线扩容」列，两种非绿状态分开（`unsupported` 灰 / `unbacked` 橙 + 悬停讲机制）
- [x] S4.1.6（补）文案定稿：`unbacked` 那一态叫 **「非 CSI 驱动」**（`4b9eda0`）——
      原来的「没人执行」太口语，用户点名换掉

#### S4.2 Istio 表单补偿 ｜ 规模 M ｜ ⏸ **2026-10-16 用户裁定：待定**（不是取消 —— 用户要先想清「对外暴露」这块的产品规划）

> **为什么推翻了 §6.2 的方案**：动了设计之前先去真集群看了一遍（只读），
> **集群的实际用法与 §6.2 的假设不同**，方案里最重的两块在本地根本用不上。
>
> **现场事实**（`coding-cluster`，2026-10-16 核对）：
> - 唯一的对外入口是 **`istio-system/external-gateway`**（class `istio`，
>   `networking.istio.io/service-type: NodePort`），31 天前由用户手工建好。
> - 它是**一个端口一个 listener**，共 15 个：`http-80` / `http-9527` / `http-13000` /
>   `http-30909` / `http-39091` / `http-19090` / `http-9000` / `http-9001` / `http-9200` /
>   `http-5601` / `https-9002`（带证书 Secret `minio-gw-tls`）/ `tcp-1234` / `tcp-6379` /
>   `http-19530` / `http-3000`。**每个 listener 都 `allowedRoutes.namespaces.from: All`**。
> - **不按域名分流，按端口分流**：既有的 11 条 HTTPRoute 全在业务命名空间
>   （`default` / `monitor` / `milvus`），跨命名空间 `parentRefs` 指向上面那些 listener，
>   **`spec.hostnames` 全是空的**。11 条全名带 `k8s-` 前缀、全是 `kubectl apply` 建的，不经平台。
>
> **由此推翻的两条**：
> 1. **「否则建一个 Gateway」不成立** —— 入口一个就够，且属集群基础设施（用户手工部署，
>    与「平台不装中间件/独立组件」那条一致）。平台不该建、也不该让租户建入口 Gateway。
> 2. **「编排式创建 + 失败回滚」不成立** —— 每条路由**只建一个 HTTPRoute**。
>    原方案里最重的那块（跨对象事务、回滚）在这个形状下没有对象可编排。
>
> **真正的落差是另一件事**（S4.2 若重启，从这里开始）：
> **platform 看不见那个 Gateway。** 路由编辑器的父资源候选走 `useParentCandidates` →
> `gatewayApi.list(ctx)` → **分配表三元组边界**，只能列出租户自己命名空间里的 Gateway；
> `istio-system` 不在任何租户的分配表里。后果：
> - 新建路由时**父资源下拉恒空** → 只能手填 `name / namespace / sectionName` 三个框
>   （[RouteEditorView.vue](../../../platform-web/src/views/resource/RouteEditorView.vue) 的父资源块）；
> - 填错一个字符的后果是 **Gateway API 静默不挂载** —— 不报错、不产生 Condition，
>   路由就是不生效（现有校验只够验格式，验不了"这个 listener 存不存在"）；
> - 反向也缺：11 条存量路由在平台里只显示一串 `external-gateway/http-80`，
>   **没有任何地方说明 9527 是干嘛的、还有哪些端口空着**。
>
> **2026-10-20 用户改判（推翻本条「平台不该建入口 Gateway」）**：入口 Gateway CR 由
> **Istio 组件创建并维护**（安装/重放成功后 SSA 下发 `istio-system/external-gateway`，
> `spec.addresses` 绑定承载它的 Service，`spec.listeners` 来自安装表单的端口表）。
> 详见 `charts/components/istio-base/1.30.6/README.md` §5。「对外暴露」整体规划仍未定；
> 旧的 15 个 listener 如需恢复，在组件编辑表单的端口表里按端口补回即可。
>
> **重启时的待决问题（用户卡的就是这个）**：「对外暴露」整体怎么规划 —— 尤其是
> **「哪些 Gateway 算可供租户挂的入口」由谁定**。三个候选各自的代价：
> ①管理员在 Gateway 上打标注（要新开「平台侧改集群 Gateway」的口子）；
> ②自动发现、排除 waypoint（零配置，但把「哪些是入口」交给启发式猜）；
> ③平台 yaml 里写白名单（不碰集群对象，但改配置要重启 —— §6.3 自己也说这毛病该治）。
>
> **原方案里与本地形状无关、但仍成立的两条**（重启时保留）：
> ③意图化展示（路由详情写「挂在哪个入口的哪个端口 + 那个端口是干嘛的」；入口侧反过来列「这个端口挂了谁」）；
> ④冲突检测（真正会撞的是**同一个入口端口**上两条路由抢同 hostname+path ——
> Gateway API 静默按优先级挑一个，正是「改了不生效」的现场）。
>
> - [ ] S4.2.1 「快速路由」表单 —— 形态改为「选入口 + 路径 + 后端 Service」，**不建 Gateway**
> - [ ] S4.2.2 ~~后端编排式创建端点~~ **撤销**（没有第二个对象可回滚）
> - [ ] S4.2.3 意图化展示（双向：路由 → 入口，入口 → 路由）
> - [ ] S4.2.4 冲突检测（同入口端口内 hostname+path 重叠）
> - [ ] S4.2.5（新）**入口可见性**：跨命名空间的入口 Gateway 候选从哪来 —— 见上面三个候选

#### S4.3 VolumeSnapshot ｜ 规模 M ｜ 用户要求排最后
- [ ] S4.3.1 capability 门控（`snapshot.storage.k8s.io` + 存在可用的 `VolumeSnapshotClass`；缺一整块隐藏）
- [ ] S4.3.2 三 CRD 操作层（通用 CRD 范式；只写 `VolumeSnapshot`，另两个只读）
- [ ] S4.3.3 快照列表 / 创建 / 删除页 + 权限点 + 迁移
- [ ] S4.3.4 从快照恢复 PVC（`dataSourceRef` 已支持，补 UI 入口）
- [ ] S4.3.5 三条语义写进 UI：快照 ≠ 备份 / `deletionPolicy` 的后果 / `restoreSize` 下限

---

## 3. 进度表

| 批 | 模块 | 前置 | 规模 | 状态 | Spec |
|---|---|---|---|---|---|
| **S0.1** | 审计日志 | — | L | ✅ **已实施待部署** | [spec](superpowers/specs/2026-10-13-audit-log-design.md) · [plan](superpowers/plans/2026-10-13-audit-log.md) |
| **S0.2** | 凭据与连接管理 | — | M | ✅ **已实施待部署** | [spec](superpowers/specs/2026-10-13-credential-management-design.md) · [plan](superpowers/plans/2026-10-13-credential-management.md) |
| **S1.1** | 用户自助 | — | S | ✅ **已实施待部署**（2026-10-16；`5d8fd8e`+`a2ac99d`+`f427ae6`+`bb955a6`。改密码 / 改资料 + 清掉两个死 URL。**要重建 platform-auth + platform-web**；无迁移。⚠️ 顺手修了个既有配置错：`@EnableRedisHttpSession` 建的是**无索引版**仓库，"按用户名踢会话"要 `@EnableRedisIndexedHttpSession` —— 见 `RedisSessionConfig`。S1.1.4（登录失败锁定 / 在线会话管理）**按用户决定未做**） | — |
| **S1.2** | 集群组件展示 | — | M | ✅ **已实施待部署**（2026-10-17；9 任务 / 全模块测试绿；真集群实测 10 装 1 缺）。部署：**k8s-server + platform-api + platform-web**，先重放 `V2026_10_17_4` | [spec](superpowers/specs/2026-10-17-cluster-components-design.md) · [plan](superpowers/plans/2026-10-17-cluster-components.md) |
| **S1.3** | 平台总览 | — | M | ⬜ 未开始 | — |
| **S2.1** | 工作负载级日志 | — | M | ✅ **已实施待部署**（2026-10-16 收口；**2026-10-17 用户验收通过，需求完结**） | [spec](superpowers/specs/2026-10-16-workload-logs-design.md) · [plan](superpowers/plans/2026-10-16-workload-logs.md) |
| **S2.2** | 告警 | — | L | ✅ **已实施待部署**（2026-10-17 主体 19 任务；**2026-10-20 补 S2.2.4 通知方式 + S2.2.5 收口**，全项目 983 绿）。部署：**先重放 `V2026_10_17_3` 再重放 `V2026_10_19_8`**，再起构建；重建 platform-data + platform-common + k8s-core + k8s-server + platform-api + platform-web，**重新登录**；⚠️ `ALERT_WEBHOOK_TOKEN` 不配则 platform-api **拒启**；集群侧四步见 cluster-prerequisites §1.8（matcherStrategy 与 sendResolved 会静默半工作）；**部署后到「通知方式」页建一条邮件渠道并设默认**，否则投递记 `FAILED("未配置通知渠道")` | [spec](superpowers/specs/2026-10-17-alerting-design.md) · [plan](superpowers/plans/2026-10-17-alerting.md) · S2.2.4 [spec](superpowers/specs/2026-10-19-notification-design.md) [plan](superpowers/plans/2026-10-19-notification.md) |
| **S2.3** | 日志采集 + OTel | S0.2、D2/D3 | L | ✅ **已完成（2026-10-19）**。部署：**先重放 `V2026_10_19_6` + `V2026_10_19_7`**，重建 platform-api + k8s-server + platform-web；集群侧配 ES 连接与 `monitor` 内的 ES 凭证 Secret。采集 = otel-operator 组件（operator + Tempo + collector + sidecar），查询 = 日志查询中心 + 链路追踪（租户按分配表收窄） | [spec](superpowers/specs/2026-10-18-otel-observability-design.md) · 组件文档在 `k8s-server/src/main/resources/charts/components/<chart>/<version>/README.md` |
| **S2.4** | 服务网格可视化 | — | M-L | ✅ **已实施待部署**（2026-10-17，含 S2.4.5 Service 页补网格信息）。部署：**platform-api + platform-web**，先重放 `V2026_10_17_2`（S2.4.5 本身只动前端，随 platform-web 一起发）。S2.4.4 **已实测回答**；**S2.4.3 链路追踪后期会做**（集群里现在没有任何追踪后端） | [spec](superpowers/specs/2026-10-17-mesh-topology-design.md) · [plan](superpowers/plans/2026-10-17-mesh-topology.md) |
| **S3.1** | 镜像仓库 | S0.2 | M | ✅ **已实施待部署**（含 2026-10-14 Docker Hub 增补） | [spec](superpowers/specs/2026-10-14-registry-design.md) · [plan](superpowers/plans/2026-10-14-registry.md)<br>增补：[spec](superpowers/specs/2026-10-14-registry-dockerhub-design.md) · [plan](superpowers/plans/2026-10-14-registry-dockerhub.md) |
| **S3.2** | 应用级交付 | D1 | L | ⬜ 未开始 | — |
| **S3.3** | Helm Chart 商店 | S3.2、S0.2 | M | ⬜ 未开始 | — |
| **S3.4** | Operator 管理 | S1.2、D1 | L | ⬜ 未开始 | — |
| **S3.5** | CI（Tekton） | S0.2 | XL | ✅ **已实施待部署**（2026-10-16 收口：片① + 片②） | [spec](superpowers/specs/2026-10-15-ci-tekton-design.md) |
| **S3.6** | 组件生命周期（安装/升级） | S1.2 | L | 🟡 **批①+批② 已实施**（2026-10-18/20；引擎 + 11 条组件 recipe + 组件文档）；**功能级验收 6/7，剩 calico**。部署：**先重放 `V2026_10_18_1`**，重建 k8s-server + platform-api + platform-web；istio 安装/重放会创建 `istio-system/external-gateway` Gateway CR | [spec](superpowers/specs/2026-10-17-component-lifecycle-design.md) · [plan①](superpowers/plans/2026-10-17-component-lifecycle-batch1.md) |
| **S4.1** | PVC 扩容 | — | S | ✅ **已实施待部署**（2026-10-16 收口：门控·中间态·独立端点·列表列·文案，`0de9533`→`4b9eda0`） | — |
| **S4.2** | Istio 表单补偿 | — | M | ⏸ **待定**（2026-10-16 用户裁定：先想清「对外暴露」的产品规划；现场勘查推翻原方案的两条，见 §S4.2） | — |
| **S4.3** | VolumeSnapshot | — | M | ⬜ 未开始 | — |

### 依赖图

```
S0.1 审计 ──────────────┐（凭据的「使用」要落审计）
                        ▼
S0.2 凭据/连接（git + registry 两类）
        ├──▶ S3.1 镜像仓库（同一条 registry 连接）──▶ (S3.5 CI 的产物去向)
        ├──▶ S3.3 Helm 商店（复用同一个 Harbor 连接）
        └──▶ S3.5 CI（git 凭据 + 注入构建 Pod）
S3.2 应用级交付 ──▶ S3.3 Helm 商店
S1.2 集群组件展示 ──▶ S3.4 Operator 管理（另需 D1）
（其余各批互不依赖）
```

### 无依赖、可立即开工的批

**S1.1 用户自助 · S1.2 集群组件展示 · S1.3 平台总览 ·
S2.2 告警 · S4.3 VolumeSnapshot**

> 已完成的从这份名单里划掉：S0.1 审计、S0.2 凭据、S2.1 工作负载级日志、S4.1 PVC 扩容、S2.4 服务网格可视化。
> S4.2 从「可立即开工」改为**待定**（用户要先想清「对外暴露」的产品规划）。

> **S0.1（审计）建议最优先** —— 它是唯一「不做就补不回来」的，而且凭据使用、CI 触发、Operator 安装
> 这些高危动作都指望它留痕。
> **S0.2（凭据）排在 S3.1/S3.5 之前即可**，不必抢在最前 —— 复审后它的消费方只有两个
> （git 连接 → CI；registry 连接 → 镜像仓库/商店），单独先做会有一部分闲着。
