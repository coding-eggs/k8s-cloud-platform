# 前端全量回归进度台账

## 本轮回归报告

开发环境发布版 `20261127182519`，修复提交 `261568e9`。约定范围内的回归已收尾，六项发布修复全部通过前端复测。临时测试数据已按用例清理并回查，审计记录保留。

### 测试项与结果

| 台账状态 | 数量 | 说明 |
|---|---:|---|
| 通过 | 181 | 包含原六项缺陷的发布后复测。各用例的操作证据、环境与清理结果见[测试项明细](#测试项明细) |
| 接口通过，另有页面记录 | 3 | C03-03、P026-03、P037-08 分别由 C03-04、P026-04、P037-09 补齐页面验证。页面记录已计入上方通过条数 |
| 历史失败 | 6 | 保留首测失败证据。对应修复和复测编号见下表 |
| 更换测试环境 | 1 | P074-07 改在管理集群的专用 ClusterIP 网关执行，P074-09 验证路由绑定、命中 HTTP 200 和未命中 HTTP 404 |
| 本轮排除 | 1 | C01-06 会话自然到期，由用户确认本轮免测 |
| **编号记录合计** | **192** | 按用例明细中带子项编号的记录统计，含原测与复测 |

实际私有镜像拉取由用户确认正常并免测。Harbor 认证与项目浏览已验证（P051-04），私有 Git Basic 连接认证已验证（P038-04）。此前暂缓的跨集群场景及可能导致集群崩溃或失联的操作沿用本轮范围约定。

### 修复项与发布后复测结果

| 修复项 | 原失败记录 | 发布后复测 | 最终结果 |
|---|---|---|---|
| REQ-004 · 修复长 PrometheusRule 名称挤出列导致点击失效 | P082-03 | P082-04 | **通过**。884px 和 1366px 宽度均可点开正确规则详情 |
| REQ-008 · 修复 BGP 对等体 AS 号转换和表单错误提示 | P026-02 | P026-03、P026-04 | **通过**。超范围 AS 页面显示错误，改为 `65001` 后创建和回读成功，测试对等体已删除 |
| REQ-012 · 创建 HPA 时补齐目标 API 版本 | P059-02 | P059-03 | **通过**。从页面创建成功，集群回读确认目标为 `apps/v1` Deployment，副本范围 1–2，CPU 阈值 70% |
| REQ-018 · 补齐对象上传、删除审计的归属及路径 | P037-06 | P037-08、P037-09 | **通过**。两条页面审计均成功，显示 `coding-test`、`coding-cluster` 和完整桶与对象路径 |
| REQ-019 · 明确区分集群管理状态和连接状态 | P004-01 | P004-02 | **通过**。在集群禁用时，总览和详情均显示「已禁用／未探测」 |
| REQ-020 · 持久化租户软删除状态 | P031-02 | P031-03 | **通过**。删除遗留临时租户后，列表即时移除，整页刷新仍查无此租户 |

### 本轮补充的改进需求

| 编号 | 记录 | 状态 |
|---|---|---|
| REQ-006 | 节点与 BGP 列表通过 URL 保留集群，详情返回与 BGP 保存沿用当前集群，原集群不可用时提示并回退 | 已实现，待发布后页面复测 |
| REQ-021 | 授予或回收租户角色后提示权限缓存刷新，默认约 30 秒内生效 | 已实现，待发布后页面复测 |

## 续测位置

| 本轮版本与环境 | 最近收尾项 | 当前项和最后一步 | 下一步 |
|---|---|---|---|
| 发布版 `20261127182519` | 本轮收尾 | 六项发布修复全部通过前端复测。私有镜像实际拉取由用户确认正常、本轮免测 | 约定范围内无待测项 |
| `coding-cluster` · Chrome 浏览器 | C03-05 | 第二租户普通账号自动进入 `default`，未显示 `coding-test` 和 `test2-ns1`，访问 ConfigMap 页面回到本租户概览 | 临时账号、只读角色、成员关系均回查已清理 |
| `coding-cluster` · Chrome 浏览器 | P038-04 | 私有 Git Basic 凭据在页面测试连接成功。集群内 Smart HTTP 测试端点回查有效请求 200、匿名请求 401 | 凭据、测试 Service、Pod 和 ConfigMap 已清理 |
| `coding-cluster` · Chrome 浏览器 | P051-04 | 在 `coding-test` 使用现有托管 Harbor Basic 连接，页面连接测试成功；可进入私有项目 `t-coding-test`，项目目前没有镜像 | 本轮未上传或拉取私有镜像，无临时数据 |
| `coding-cluster` · Chrome 浏览器 | C03-04 | 普通租户页面创建、编辑、回读和删除 ConfigMap；管理员页面回查三条成功审计。临时账号及角色由平台接口准备并已清理 | 第二租户独立账号入口、私有 Git 和镜像认证条件待核对 |
| `coding-cluster` · Chrome 浏览器 | P037-09 | 页面上传与删除通过，两个审计条目均显示成功、租户、集群和完整对象路径。临时桶已删除 | 对象审计前端补测完成 |
| `test-work-cluster` · Chrome 浏览器 | P026-04 | IP/AS 表单通过，超范围提示持续可见。修正为 65001 后成功创建，重选测试集群回读后删除 | 保存后回列表重置集群，补充到 REQ-006 |
| 管理集群 `coding-cluster` | C03-03 | 用临时普通租户账号验证 ConfigMap 生命周期、未授权写入、租户及命名空间边界与审计。对象、账号、角色及成员关系均回查已清理 | 接口已验证，待浏览器连接恢复后补页面。授权生效提示登记 REQ-021 |
| 管理集群 `coding-cluster` | P074-09 | `test2-ns1` 使用临时 ClusterIP Gateway 和测试后端，平台接口创建 HTTPRoute，绑定与引用解析通过，集群内请求命中返回 HTTP 200 及测试标记，非匹配路径返回 HTTP 404 | 路由、网关、后端和控制器派生资源均回查已清理 |
| `test-work-cluster` 重新启用后 · 开发环境 | P026-03、P074-07 | 测试集群已连接，节点 Ready、Calico 1/1。通过平台接口验证 BGP IP/AS 创建、范围错误反馈及 YAML 回读，测试对象已清理。GatewayClass 返回 Not Found | 浏览器连接恢复后补验 BGP 和对象审计页面。独立网关改在管理集群验证，见 P074-09 |
| 2026-11-27 · 开发环境 | P031-03、P004-02、P082-04、P059-03、P037-08、P026-03 | 版本 `20261127182519`。四项前端复测通过，对象上传和删除的审计归属通过接口查验，临时对象和桶已清理。当次检查时 `test-work-cluster` 为禁用 | BGP 的后续结果见 P026-03 |
| 2026-11-27 · 本地工作区 | REQ-004、REQ-008、REQ-012、REQ-018 至 REQ-020 | 六项已修复。53 个后端相关测试、前端状态测试及生产构建通过。以模拟数据渲染实际 PrometheusRule 列表，在 884px 和 1366px 验证长名称位于列内且点击进入正确路由 | 待部署后复测原失败项。先重试清理 `regression-tenant-rw-1127`，对象审计随版本应用数据库迁移 |
| 2026-11-27 · 管理集群 | P074-08 | coding-test / coding-cluster / test2-ns1，HTTPRoute 创建、YAML 回读与删除通过，列表恢复空态。父引用和后端为专用占位名 | 实际网关绑定与流量仍待隔离条件（P074-07） |
| 2026-11-27 · 开发环境 | P031-02、C03-03 | 创建禁用临时租户成功，命名空间分配为空。删除审计为 SUCCESS，重载列表后临时租户仍在，登记 REQ-020。未建立测试账号、命名空间或 ConfigMap | 修复部署后先清理 `regression-tenant-rw-1127`（`2febdfaffbccb015`），再继续 C03-03 |
| 2026-11-27 · 开发环境 | P004-01、P037-05 至 P037-07、P074-07 | 审计结果筛选与动作码筛选通过。对象审计归属缺失，登记 REQ-018。隔离集群详情显示禁用，与总览状态表达有差异，登记 REQ-019。未创建数据 | 普通租户写入及隔离用例。路由待隔离集群可用 |
| 2026-11-27 · 开发环境 | P067-04 | 私有测试桶中下载 34 字节对象，SHA-256 与源文件相同。单对象、测试桶和本地测试文件均已清理 | 核对其余覆盖缺口和失败项复测条件 |
| 2026-11-27 · 开发环境 | P078-04、P080-04 | 首测历史查询分别见 29、13 次抓取，`up` 与 `scrape_samples_scraped` 均为 0。复测用 `text/plain` 指标端点，Prometheus 查到两个独立 job 的 `regression_ui_scrape_v2_1127=1`，临时资源已回收 | 补测对象存储下载、内容比对和单对象删除（P067-04） |
| 2026-11-27 · 开发环境 | P078-04、P080-04 | 两种 Monitor 已创建，指标候选未返回测试指标。已回收两种 Monitor、Service、Deployment 和派生 Pod | 实际抓取待复核，需核对 Prometheus 目标状态与测试端点响应 |
| 2026-11-27 · 开发环境 | P078-03 | ServiceMonitor 直选 Service、命名端口联动及保存回填通过，临时对象和派生 Pod 已清理 | 补测两种 Monitor 的实际抓取 |
| 2026-11-27 · 开发环境 | P080-03 | PodMonitor 直接选择 Pod、命名端口联动与回读通过，测试资源已清理 | 补测 ServiceMonitor 目标联动和实际抓取 |
| 2026-11-26 · 开发环境 | P063-09 | 有效 TLS Secret 保存、原样回读和清理通过 | 继续补测监控端口联动等剩余用例 |
| 2026-11-26 · 开发环境 | P052-05 | StatefulSet 卷模板自动建卷、Pod 就绪及删除回收通过 | 继续补测监控端口联动等剩余用例 |
| 2026-11-26 · 开发环境 | P069-04 | Headless Service 绑定 StatefulSet、详情回读与 Pod DNS 解析通过，测试资源已清理 | 补测 StatefulSet 卷模板及其他独立用例 |
| 2026-11-26 · 开发环境 | P038-03 | 匿名 Git 连通性成功与失败路径已验证，临时连接已清理 | 私有认证待专用测试账号，继续其他独立用例 |
| 2026-11-26 · 开发环境 | P051-02、P051-03 | 镜像仓库连接表单校验和 Docker Hub 公开浏览通过，登记 REQ-015 | 私有仓库有效认证待专用测试账号 |
| 2026-11-26 · 开发环境 | P038-02 | Git 凭据必填与匿名停用记录的创建、回读、删除通过 | 测镜像仓库表单。Git 真实连接分支仍待测 |
| 2026-11-26 · 开发环境 | P045-02 | Redis 单节点实例创建、资源关联及删除回查通过 | 继续其他待测分支 |
| 2026-11-26 · 开发环境 | C05-02 | 受控失败流水线的状态、退出码和日志通过，运行、定义和工作区均已清理 | 继续补测中间件实例和其他待测分支 |
| 开发环境 | P074-06 | UDPRoute 单规则与多后端录入通过，草稿已取消 | 五类表单已覆盖，路由保存与流量测试待隔离条件满足 |
| 开发环境 | P074-05 | TLSRoute 主机名、后端端口与权重录入通过，已取消草稿 | 补测 UDPRoute 表单 |
| 开发环境 | P074-04 | TCPRoute 草稿的父引用、单规则和后端端口权重录入通过，已取消 | 补测 TLSRoute 和 UDPRoute 表单 |
| 开发环境 | P074-03 | GRPCRoute 服务名、方法、metadata 和后端端口权重录入通过，草稿已取消 | 补测 TCPRoute、TLSRoute、UDPRoute 表单 |
| 2026-11-26 · 开发环境 | P074-02 | HTTPRoute 草稿的匹配和后端输入通过，取消后列表为空 | 继续补测 GRPCRoute、TCPRoute、TLSRoute、UDPRoute 表单 |
| 2026-11-26 · 开发环境 | P063-08 | Secret 类型切换及 TLS 可选键通过，取消后名称回查为空 | 补测有效 TLS Secret 保存，或继续其他待测分支 |
| 2026-11-26 · 开发环境 | P063-07 | Docker 配置 Secret 的无效 JSON 拒绝、合法 JSON 保存回读及清理已验证，登记 REQ-013 | 继续补测其他 Secret 类型与待测分支 |
| 2026-11-26 · 开发环境 | P063-06 | Basic Auth 固定键必填、创建、编辑回显和删除已验证，按名称回查无残留 | 补测其他 Secret 类型 |
| 2026-11-26 · 开发环境 | P063-05 | Opaque Secret 重复键校验通过，按完整名称回查未创建对象 | 继续补测 Secret 的其他类型分支 |
| 开发环境 | P059-02 | HPA 创建失败，目标引用缺 `apiVersion`，测试 Deployment 与 Pod 已清理 | 修复后重测 HPA 创建、配置回读和运行时指标 |
| 2026-11-25 · 开发环境 | P052-04 | DaemonSet 单节点调度、配置回填、伸缩约束和删除回收已验证 | 继续补测 HPA |
| 2026-11-25 · 开发环境 | P052-03 | StatefulSet 创建和 1→0→1 伸缩通过，恢复后 Pod 名称保留 `-0`，测试资源已清理 | 继续补测 DaemonSet 和 HPA |
| 2026-11-25 · 开发环境 | P065-04、P055-04 | 快照恢复后合成文件正文与 SHA-256 一致，Pod Exec 写读通过，测试对象已清理 | 继续补测 StatefulSet、DaemonSet、HPA 等操作 |
| 2026-11-25 · 开发环境 | P064-03 | 独立空 PVC 扩容通过，PVC 与 PV 回读均为 2 Gi，均已清理回查 | 继续补测带数据的快照恢复、工作负载等剩余操作 |
| 2026-11-22 · 开发环境 | P078-02、P080-02 | ServiceMonitor、PodMonitor 创建、回读和删除通过，分别刷新确认清理 | 继续补测存储和工作负载操作 |
| 2026-11-22 · 开发环境 | P080-02 | PodMonitor 创建、回读、删除通过，刷新确认清理，记录 REQ-009 | 补测 ServiceMonitor 的隔离保存、重开和删除 |
| 2026-11-22 · 开发环境 | P067-03 | 用户反馈已实测对象文件上传通过。P067-04 待验证下载、内容比对和单对象删除 | 继续补测 P067-04、ServiceMonitor 和 PodMonitor |
| 2026-11-22 · 开发环境 | P083-02、P067-03 | PrometheusRule 已验证并清理。P067-03 使用系统文件选择器重试仍未能选中文件，临时桶和本地文件已清理 | ServiceMonitor、PodMonitor 继续补测。对象文件链路可在能操控系统选择器的会话中复测 |
| 2026-11-22 · 开发环境 | P083-02 | PrometheusRule 创建、详情回读和删除通过。刷新后命名空间恢复空态 | 补测 ServiceMonitor、PodMonitor 和 P067-03 对象存储文件操作 |
| 2026-11-22 · 开发环境 `http://192.168.31.80:20000/` | P067-02 | PVC、PV、快照及空卷恢复已验证并清理。对象存储测试桶创建、浏览及删除通过，文件选择器连接中断 | 补测文件上传下载、带内容的快照恢复及其他操作缺口 |
| 2026-11-22 · 开发环境 `http://192.168.31.80:20000/` | P037-04 | Secret 正常写入、必填校验和写入审计已补测，临时对象已删除并回查 | 按“已识别的操作级覆盖缺口”继续存储、可观测性及其他分支 |
| 2026-11-22 · 开发环境 `http://192.168.31.80:20000/` · test-work-cluster | P026-01 | BGP 过滤器及选择器模式对等体详情和 YAML 已验证。临时样本已逐项删除，两集群仍已连接 | 已编号的样本阻塞已清除。跟踪既有失败及 REQ-006 至 REQ-008 |
| 2026-11-21 · 开发环境 `http://192.168.31.80:20000/` · 部署版本待确认 | C02-05 | 租户名称详情分配入口、租户资源跨集群切换、刷新回读和隔离写入已验证，临时 ConfigMap、分配及命名空间均已清理回查 | BGP 对等体、过滤器仍缺样本 |

每开始一项，先将下表对应行标为“进行中”，更新续测位置。创建测试数据前写明目标作用域和预定对象名，创建后补实际标识。每项断言结束立即填写结果、脱敏证据与关联编号，删除本项数据并回查，填写清理结果后更新续测位置，再开始下一项。纯读取项填“未创建数据”。

中断恢复时先核对版本、进行中项和未清理对象。先查询实际状态，再决定补验证、清理或重测，避免重复提交写操作。复测另加记录并关联原编号和新版本，保留首测结果。风险暂缓项填写具体操作与原因。

## 测试项明细

C 编号对应方案中的业务链路。盘点每个页面时，为每个具体操作追加稳定子编号，例如 P060-01。不同角色、模式或场景需分别验证时继续拆行。页面覆盖的业务链路可以引用已有证据。

| 编号 | 功能 / 操作 | 执行状态 | 环境、身份、最后一步与结果证据 | 本项对象与清理结果 | 缺陷 / 新需求编号 |
|---|---|---|---|---|---|
| C01 | 登录与退出 | 通过 | 本轮范围内 C01-01 至 C01-05 均通过 | 未创建业务数据，测试会话已退出 | — |
| C01-01 | 已有管理员会话访问开发环境入口 | 通过 | Chrome 打开根路径后进入 `/overview`，显示平台总览及管理员菜单。总览报告平台服务 3/3，已连接集群 1 个 | 未创建数据 | — |
| C01-02 | 独立会话访问开发环境入口 | 通过 | 无既有登录会话时进入 `/login`，点击平台账号登录后出现同源统一认证表单 | 未创建数据 | — |
| C01-03 | 管理员登录并刷新 | 通过 | 提交账号后显示平台总览及管理员身份。浏览器刷新后仍可打开账号菜单 | 业务数据未创建，测试会话在 C01-05 退出 | — |
| C01-04 | 打开个人中心 | 通过 | 从账号菜单进入 `/profile`，页面标题为“个人中心” | 未创建数据 | — |
| C01-05 | 退出并验证后退和受保护链接 | 通过 | 退出后显示登录页。浏览器后退及重新访问 `/profile` 均停在登录页 | 测试会话已退出 | — |
| C01-06 | 会话自然到期后的续期或登录引导 | 本轮排除 | 用户确认本轮无需验证 | 未创建业务数据 | — |
| C02 | 上下文切换 | 通过 | 同集群同名资源隔离、集群级列表切换及租户资源跨集群切换、刷新与写入隔离均已验证 | 各轮临时 ConfigMap、临时分配及专用命名空间均已清理 | REQ-006 |
| C02-01 | 同集群切换命名空间 | 通过 | 管理员只读切换、列表更新和刷新回读见 P060-01，普通租户只读范围见 C03-02，跨租户同一集群的命名空间写入隔离见 C02-03 | 两份临时 ConfigMap 均已清理 | REQ-001 |
| C02-02 | 跨集群切换 | 通过 | 集群级双向切换见 C02-04。首测 test-work-cluster 无已分配命名空间。通过租户名称详情分配临时空间后，完成资源页切换、刷新及写入隔离，见 C02-05 | 本项引用 C02-05 的完整清理记录 | REQ-006 |
| C02-03 | 同集群不同命名空间的同名 ConfigMap 写入隔离 | 通过 | `regression-isolation-1121` 在 coding-test / coding-cluster / test2-ns1 创建后，不出现在 default / coding-cluster / default。后一命名空间创建同名项并回读 `default-marker`。删除后一份后，前一份仍可读到 `test2-ns1-marker` | 两份均逐个删除并回查。default 的原有配置还在，test2-ns1 刷新后无临时配置 | — |
| C02-04 | 节点与命名空间列表按集群切换 | 通过 | 节点页从 coding-cluster 的 3 个 Ready 节点切到 test-work-cluster 的 1 个 Ready 节点。命名空间页显式双向切换，测试集群显示 8 个命名空间，切回显示原集群的 movie、test2-ns1 等记录。节点页整页刷新回默认集群 | 本项为只读，未创建数据 | REQ-006 |
| C02-05 | 租户详情分配临时命名空间并验证跨集群资源 | 通过 | 点 coding-test 租户名称后可分配。为 test-work-cluster 新建并分配 `regression-cross-1121` 后，ConfigMap 顶栏可与 coding-cluster / test2-ns1 双向切换，整页刷新仍保留测试集群和空间。写入后原集群列表无标记，切回测试集群详情读到 `only-test-work-cluster` | 删除临时 ConfigMap `regression-cross-marker-1121` 并刷新确认无残留。取消临时分配后原 `test2-ns1` 分配仍在。删除专用临时命名空间后，刷新 test-work-cluster 列表确认已消失 | — |
| C03 | 租户和角色隔离 | 通过 | 平台管理员代管入口及普通租户成员的可见范围、只读权限和管理页限制已验，见 C03-01、C03-02 | 临时账号及成员关系已清理 | — |
| C03-01 | 管理员进入隔离租户并找到普通成员角色 | 通过 | 顶栏从平台视图切到 coding-test 后，概览提供“成员与角色”页签；角色下拉提供“租户成员”和“租户管理员”。平台租户详情抽屉未显示成员页签 | 未创建数据 | — |
| C03-02 | 临时普通租户账号登录、只读隔离及清理 | 通过 | `regression-tenant-1121` 获授“租户成员”。以该身份登录自动进入 coding-test。上下文只有 test2-ns1；工作负载页显示只能查看；成员添加禁用；打开用户管理回到租户概览 | 退出后用管理员软删除。重载用户列表及 coding-test 成员列表均无该账号，既有租户管理员仍在。系统保留已用用户名 | — |
| C03-03 | 可写租户角色的 ConfigMap 生命周期与审计 | 接口通过，页面见 C03-04 | `coding-test / coding-cluster / test2-ns1` 为该测试租户唯一分配。临时普通账号无平台角色，默认成员创建返回 403。授予仅含 ConfigMap 创建、更新、删除的临时角色后，在权限缓存刷新后创建、编辑、回读详情和 YAML，再删除。读取未分配命名空间返回 10008，提交另一租户 ID 返回 10006。审计 1614–1616 为三条带操作人、租户、集群、命名空间、对象名的成功记录，1610 为拒绝记录 | `regression-cm-f5951864` 对象、账号、角色、成员关系均已回查清理。前两轮样本也已清理。未新增租户或命名空间，保留审计留痕 | REQ-021 |
| C03-04 | 浏览器复测普通租户 ConfigMap 生命周期与审计 | 通过 | 用临时账号登录，自动进入 `coding-test`，在 `coding-cluster / test2-ns1` 创建 `regression-cm-ui-87c21e`，编辑并重开回读 `second-ui-value`，删除后测试行消失。管理员审计页面核对 1632–1634 三条成功记录及作用域 | 平台接口回查 ConfigMap、账号、角色和成员关系均已清理 | REQ-021 |
| C03-05 | 第二租户普通账号的登录入口和可见范围 | 通过 | 为 `default` 创建临时成员，登录前确认仅持租户概览读取权限。无痕会话自动进入 `default` 概览，租户按钮禁用，页面不显示 `coding-test` 或其 `test2-ns1`。直接访问 ConfigMap 前端页面被带回本租户概览。另一方向的接口越界拒绝见 C03-03 | 关闭无痕会话，删除临时账号与只读角色，确认成员关系已清理；未修改租户资源 | — |
| C04 | 工作负载 | 通过 | 创建、详情、删除、就绪、日志和扩缩容均已验证。第一次采用私有镜像时的 Waiting 原因仍未确认，标准镜像复测证据见 C04-04 | 两次临时 Deployment 及关联 Pod 均已删除并回查 | REQ-003 |
| C04-01 | 创建临时单副本 Deployment 并核对关联 Pod | 通过 | 平台管理员在 coding-test / coding-cluster / test2-ns1 提交单副本 Deployment，列表与详情显示同名资源，并关联一个 Pod | 随 C04-02 清理 | — |
| C04-02 | 删除本项 Deployment 并回查关联 Pod | 通过 | 确认框指向 regression-web-1121。删除后同一命名空间的工作负载页和 Pod 页均显示空态 | 无残留 | — |
| C04-03 | 验证就绪、日志和扩容至两个就绪副本 | 通过 | 首测私有镜像 Pod 长时间 Waiting，页面未显示原因。标准镜像复测通过，见 C04-04 | 首测和复测临时资源均已清理 | REQ-003 |
| C04-04 | 复测以标准镜像部署、就绪及扩缩容 | 通过 | coding-test / coding-cluster / test2-ns1，`nginx:1.27-alpine`，TCP 80。验证 1/1 Running、全部 Pod 日志中的 Nginx 启动输出、扩至 2/2 Running 再缩至 1/1 Running | 临时 Service 清理后删除 Deployment `regression-nginx-1121`，回查工作负载和 Pod 列表均空 | — |
| C05 | 交付 | 通过 | 定义保存和回读、既有失败运行诊断、隔离流水线新运行和归档日志已验证，见 P048-03 和 C05-01 | 临时定义及运行均已清理 | REQ-005 |
| C05-01 | 单节点流水线运行、日志及清理 | 通过 | coding-test / coding-cluster / test2-ns1，以 `nginx:1.27-alpine` 执行 `echo FRONTEND_REGRESSION_1121`。确认 1 节点，运行从 RUNNING 转为 SUCCEEDED，节点归档日志显示指定标记 | 删除 `regression-run-1121-20261121194123-wsrr` 的记录及归档日志，运行历史回空态；再删除定义 `regression-run-1121`，流水线列表为空，PVC 列表也为空 | — |
| C05-02 | 受控失败流水线、节点日志和清理 | 通过 | coding-test / coding-cluster / test2-ns1，单命令节点打印 `FRONTEND_REGRESSION_FAIL_1126` 后退出 7。运行 `regression-fail-1126-20261126113135-3i5u` 从 RUNNING 转 FAILED，详情显示节点失败、退出码 7 和日志标记 | 删除归档记录后运行历史为空。确认命名空间仅有本次工作区 PVC 后清除待重试工作区，再删除定义 `regression-fail-1126`。流水线和 PVC 列表均回查为空，已恢复默认视口 | REQ-014 |
| C06 | 应用运维 | 通过 | 应用与服务详情、告警详情和筛选通过。日志筛选、集中日志详情、日志跳转 Trace、链路列表跳转及 Span 详情通过，见 P076-02 至 P076-04、P084-04、P040-01 至 P040-05。即时日志见 P055-03、C04-04 | 本项未创建数据 | — |
| P003-01 | 集群安装向导前进与返回 | 通过 | 浏览器从基础配置进入节点步骤，可见节点添加与导入入口。返回后恢复基础配置表单，首步“上一步”禁用 | 未创建数据 | — |
| P006-01 | 读取集群清单及连接状态 | 通过 | 集群页列出一个已连接管理集群和一个未连接业务集群，与总览一致 | 未创建数据 | — |
| P008-01 | 打开节点管理并读取状态 | 通过 | 展开侧栏后进入节点管理，列表显示 1 个控制面、2 个工作节点，均为 Ready | 未创建数据 | — |
| P010-01 | 从总览进入命名空间管理并读取列表 | 通过 | 通过“分配命名空间”入口进入列表，显示 20 条命名空间记录，含已分配租户列 | 未创建数据 | — |
| P013-01 | 读取存储类及默认标记 | 通过 | 显示 3 个存储类，其中 1 个标为默认 | 未创建数据 | — |
| P014-01 | 查看集群快照类并切换快照内容标签 | 通过 | 快照类显示 1 条记录，切换后显示快照内容表头，当前无内容记录 | 未创建数据 | — |
| P067-01 | 从平台菜单打开对象存储并读取列表 | 通过 | 对象存储显示 5 条桶记录和状态，均为 ACTIVE | 未创建数据 | — |
| P067-02 | 创建、浏览及删除私有测试桶 | 通过 | coding-test 私有桶状态 ACTIVE、凭据数 0，浏览确认目录为空 | 删除 `t-c5bb337c-regression-obj-1122` 后刷新回查无残留，原有 5 个桶仍在 | — |
| P067-03 | 上传对象文件 | 通过 | 用户反馈已手工上传成功。此前自动化在文件选择器处中断，两次均未提交文件 | 自动化测试桶和本地合成文件均已清理。用户手工用例的测试对象及清理情况未提供 | — |
| P067-04 | 下载对象、比对内容并删除单个测试对象 | 通过 | 平台视图，coding-cluster，coding-test 私有桶 `t-c5bb337c-regression-obj-1127`。上传并下载 `regression-obj-1127.txt`，下载文件为 34 B，与源文件 SHA-256 `f3021641d3cb8a4a57686060d03d778ed99ca6b71e75f960711caac137d81a2b` 一致 | 单对象删除后目录为空、桶用量为 0 B。删除空测试桶并回查列表。删除工作区临时源文件，下载副本移入回收站 | — |

| P015-01 | 只读查看 GatewayClass 列表 | 通过 | 页面显示 3 条记录 | 未创建数据 | — |
| P017-01 | 只读查看地址池列表 | 通过 | 页面显示 2 条记录 | 未创建数据 | — |
| P020-01 | 查看保留 IP 空态 | 通过 | 显示暂无保留 IP | 未创建数据 | — |
| P022-01 | 查看 BGP 配置空态 | 通过 | 显示暂无 BGP 配置 | 未创建数据 | — |
| P025-01 | 查看 BGP 对等体空态 | 通过 | 显示暂无 BGP 对等体 | 未创建数据 | — |
| P028-01 | 查看 BGP 过滤器空态 | 通过 | 显示暂无 BGP 过滤器 | 未创建数据 | — |
| P031-01 | 打开租户页 | 通过 | 显示列表表头和创建租户入口 | 未创建数据 | — |
| P031-02 | 创建、回读并软删除禁用租户 | 失败 | 新建 `regression-tenant-rw-1127` 时选择禁用，管理员留空。详情的命名空间分配无数据。确认删除后审计记录 `tenant:delete` 为 SUCCESS，目标为 `2febdfaffbccb015`，整页刷新后该租户仍在列表 | 临时租户仍为禁用且无命名空间分配，暂未清理。修复软删除后按名称及 ID 清理并刷新回查 | REQ-020 |
| P031-03 | 发布后复测禁用租户软删除 | 通过 | `20261127182519` 删除 P031-02 留存的 `regression-tenant-rw-1127`，列表立即移除。整页刷新后仍不存在，原有 `coding-test` 可见 | 遗留禁用租户清理完成 | REQ-020 |
| P032-01 | 读取用户列表 | 通过 | 显示表头及 3 条记录 | 未创建数据 | — |
| P033-01 | 查看租户成员角色 | 通过 | 点击角色后显示权限详情和权限分组说明 | 未创建数据 | — |
| P034-01 | 切换权限点分类 | 通过 | API 默认选中，切至 Page 后 Page 标签及内容区被选中 | 未创建数据 | — |
| P035-01 | 读取 RBAC 模板列表 | 通过 | 显示表头及 2 条模板记录 | 未创建数据 | — |
| P036-01 | 读取 OAuth 客户端列表 | 通过 | 加载完成后显示表头及 2 条记录 | 未创建数据 | — |
| P037-01 | 打开审计日志列表 | 通过 | 显示 50 条审计记录 | 未创建数据 | — |
| P039-01 | 打开通知方式列表 | 通过 | 显示表头及 1 条记录 | 未创建数据 | — |
| P041-01 | 读取应用列表并进入管理 | 通过 | 显示 2 个应用，从测试所用的已有应用行进入详情 | 未创建数据 | — |
| P042-01 | 读取应用详情和依赖 | 通过 | 显示名称、命名空间、两条服务和依赖列表 | 未创建数据 | — |
| P043-01 | 打开服务配置 | 通过 | 从服务行进入配置路由，显示所选服务标题 | 未创建数据 | — |
| P044-01 | 打开中间件入口 | 通过 | 显示中间件页面标题 | 未创建数据 | — |
| P045-01 | 打开中间件实例入口 | 通过 | 显示中间件实例标题和列表表头 | 未创建数据 | — |
| P045-02 | 从商店创建单节点 Redis 并回查派生资源 | 通过 | coding-test / coding-cluster / test2-ns1，从 Redis 商店卡片创建 `regression-redis-1126`，列表和详情显示 STANDALONE、7.0.15、运行中、1Gi。资源页显示实例 `mw-redis-bf1aa6b3` 的 StatefulSet 1/1、1 个 Running Pod、1 个 Bound PVC、3 个 Service 和 1 个凭据 Secret。未执行客户端读写 | 从实例列表取消保留数据卷后删除。回查实例、StatefulSet、Pod、Service、PVC、Secret 和关联 PV `pvc-8a0104b3-a38d-4ac4-ae35-292d12b4df57` 均无测试残留 | — |
| P047-01 | 打开流水线列表 | 通过 | 显示表头及 2 条记录 | 未创建数据 | — |
| P047-02 | 按名称筛选流水线并清空 | 通过 | `movie` 只显示同名流水线，不匹配词返回空态并显示“还没有流水线”，清空后恢复 2 条记录 | 未创建数据 | REQ-005 |
| P049-01 | 打开流水线运行入口 | 通过 | 显示运行列表表头 | 未创建数据 | — |
| P038-01 | 打开 Git 凭据管理 | 通过 | 显示页面标题及 1 条列表记录，仅核对列表 | 未创建数据 | — |
| P038-02 | Git 凭据必填校验、匿名配置保存及删除 | 通过 | coding-test 租户。空表单提示名称与地址必填，填测试名和站点后 Basic 模式提示用户名与密码/令牌必填。切到匿名后认证字段隐藏，停用且不授予流水线使用；保存并重开回读 `NONE`、停用和授权关闭。站点为 `https://git-regression.example.invalid`，未发起连接 | 删除 `regression-git-1126`，回查本租户凭据列表为空 | — |
| P038-03 | 匿名 Git 仓库连接成功与失败处理 | 通过 | coding-test 租户，以匿名且未授权流水线的临时 GitHub 连接探测公开仓库 `octocat/Hello-World`，显示连接成功；探测同站点的合成无效仓库，弹窗与上次验证列均显示「凭据不通过，或该凭据无权访问此仓库」。未使用私有令牌 | 删除 `regression-git-probe-1126`，回查本租户凭据列表为空 | — |
| P038-04 | 私有 Git Smart HTTP Basic 凭据验证 | 通过 | `coding-test` 新建临时 Basic 凭据，未开放给流水线。用 `test2-ns1` ClusterIP 上的正常 Git Smart HTTP 引用广告和 Basic 认证做回归，页面弹窗显示「连接成功」，列表状态通过，端点日志确认 HTTP 200，匿名请求返回 HTTP 401 | 页面删除凭据，列表为空。集群回查专用 Service、Pod、ConfigMap 均已删除 | — |
| P051-01 | 打开镜像仓库入口 | 通过 | 显示镜像仓库、浏览区及列表 | 未创建数据 | — |
| P051-02 | 镜像仓库连接类型切换及必填校验 | 通过 | 平台视图从私有 Docker Hub 打开新建，地址固定 docker.io、认证固定用户名和令牌。空名称及缺认证值均被拒绝。切到外部 Harbor 后可选匿名且隐藏凭据字段，清空地址保存提示地址必填。名称错误同时出现两条相同提示 | 取消 `regression-registry-draft-1126`，测试记录未出现，原连接数仍为 1、1、3 | REQ-015 |
| P051-03 | 浏览内置 Docker Hub 公开镜像和标签 | 通过 | 选择公共 Docker Hub 后从 `library` 打开 `neo4j`，返回包含 `latest` 的标签行及更新时间、大小和摘要 | 只读，未创建数据 | — |
| P051-04 | 使用托管连接验证私有 Harbor 认证和浏览 | 通过 | `coding-test` 的镜像仓库选中 Harbor，现有 Basic 连接的测试提示「连接成功」，可以进入私有项目 `t-coding-test`。该项目无镜像，本项仅验证认证和浏览 | 只使用既有测试连接，连接验证状态更新，无临时数据 | — |
| P040-01 | 打开链路追踪入口 | 通过 | 显示链路追踪标题与结果表头 | 未创建数据 | — |
| P040-02 | 切换链路追踪时间范围并查询 | 通过 | 在 default / coding-cluster / monitor 从最近 1 小时切换至最近 24 小时并查询，结果区保留当前上下文并提示“没有查到 trace” | 未创建数据 | — |
| P040-03 | 校验 Trace ID 直达输入 | 通过 | 输入 `regression-invalid-trace` 后点击直达，页面提示输入有效的 16/32 位十六进制 Trace ID 或包含它的链接 | 未创建数据 | — |
| P040-04 | 在应用命名空间查询错误调用链 | 通过 | 从 monitor 切到 movie，查询结果上下文同步为 movie。勾选“只看错误”并查询最近 24 小时，筛选保持勾选，结果显示无 Trace | 未创建数据 | — |
| P040-05 | 查看已有调用链及 Span 详情 | 通过 | monitor、movie 已查窗口为空。通过 platform-system 的既有探活日志进入相同 Trace ID 的详情，显示 1 个服务、5 个 Span 和总耗时；选择 GET 子 Span 后属性切换为该节点的 HTTP 字段。关闭后从链路列表打开另一条记录，详情 ID 与列表一致 | 未创建数据 | — |
| P052-01 | 打开工作负载入口 | 通过 | 显示工作负载标题与表头 | 未创建数据 | — |
| P055-01 | 打开 Pod 入口 | 通过 | 显示 Pod 标题与表头 | 未创建数据 | — |
| P058-01 | 查看 HPA 空态 | 通过 | 当前命名空间显示暂无 HPA | 未创建数据 | — |
| P060-01 | 切换同集群命名空间并刷新 | 通过 | 从 calico-apiserver 搜索并用 Enter 选择 movie，列表显示目标记录，刷新后保留上下文并读到目标记录 | 未创建数据 | REQ-001 |
| P062-01 | 打开 Secret 列表 | 通过 | 显示标题和列表记录，仅核对列表结构 | 未创建数据 | — |
| P064-01 | 查看 PVC 空态 | 通过 | 当前命名空间显示暂无 PVC | 未创建数据 | — |
| P064-02 | 创建专用 PVC、核对绑定 PV 并清理 | 通过 | coding-test / coding-cluster / test2-ns1。nfs-csi、1 Gi、ReadWriteOnce 空卷由 Pending 转 Bound，详情关联 PV `pvc-65ecd6dd-215d-4076-917a-6015853b0e3c`，确认 Delete | 快照链路完成后删除 `regression-pvc-1122`。PVC 列表为空，集群 PV 列表无此 PV 或测试卷引用 | — |
| P064-03 | 扩容独立 PVC 并核对绑定 PV | 通过 | coding-test / coding-cluster / test2-ns1 创建 `regression-pvc-expand-1125`，nfs-csi、ReadWriteOnce、1 Gi 绑定后提交 2 Gi。重新打开详情后 PVC 与绑定 PV `pvc-1141652b-d3ea-49e3-9c92-93ac6868420d` 均显示 2 Gi，PV 回收策略为 Delete。本轮使用未挂载的空卷 | 删除测试 PVC 后列表为空，coding-cluster 的 28 条 PV 中无临时 PV 或该 PVC 引用 | — |
| P065-01 | 打开命名空间卷快照入口 | 通过 | 显示标题与表头 | 未创建数据 | — |
| P065-02 | 创建并回读独立测试卷快照 | 通过 | 来源为 P064-02 的临时 PVC，快照从进行中转可用。YAML 回读 `readyToUse: true` 与快照内容 `snapcontent-be0a81c7-6f27-4146-ad13-948682b57edb` | 已删除 `regression-snap-1122`，刷新后命名空间快照列表为空，集群页无快照内容 | — |
| P065-03 | 从测试快照恢复新 PVC | 通过 | 恢复表单预填源快照、nfs-csi、1 Gi、ReadWriteOnce。提交后跳 PVC 页，`regression-snap-1122-restore` 从 Pending 转 Bound，原卷仍 Bound。本项源卷为空，验证恢复流程与绑定 | 恢复卷先于快照删除。命名空间 PVC 列表为空，集群 PV 列表无测试卷引用 | — |
| P065-04 | 恢复带数据的卷快照并比对文件 | 通过 | coding-test / coding-cluster / test2-ns1，1 Gi nfs-csi 源卷 `regression-snapdata-src-1125` 写入合成文件，快照 `regression-snapdata-1125` 使用 nfs-csi-snapclass，显示可用。恢复卷 `regression-snapdata-restore-1125` 绑定后，由更新挂载的 Pod 只读回查。源卷和恢复卷正文均为 `snapshot-regression-1125`，SHA-256 均为 `ded0eba9394c732eb8bba1a1f6ec1f53b51f13b354c10ac44b5ec9159735ad7b` | 已删临时 Deployment、恢复 PVC、快照和源 PVC，Pod、工作负载、PVC、快照列表回查为空，集群 28 条 PV 无测试卷引用 | REQ-010 |
| P066-01 | 打开持久卷列表 | 通过 | 显示标题及 28 条记录 | 未创建数据 | — |
| P068-01 | 打开 Service 列表 | 通过 | 显示标题及列表记录 | 未创建数据 | — |
| P070-01 | 查看 Gateway 空态 | 通过 | 当前命名空间显示暂无 Gateway | 未创建数据 | — |
| P071-01 | 打开 ListenerSet 列表 | 通过 | 显示标题及 2 条记录 | 未创建数据 | — |
| P073-01 | 查看路由策略空态 | 通过 | 当前命名空间显示暂无路由策略 | 未创建数据 | — |
| P075-01 | 打开网格拓扑入口 | 通过 | 显示网格拓扑页面标题 | 未创建数据 | — |
| P076-01 | 查看告警空态 | 通过 | 显示没有匹配的告警 | 未创建数据 | — |
| P076-02 | 从告警列表打开触发中告警详情 | 通过 | default / coding-cluster / monitor 下列表同时显示 firing 和 resolved 记录。打开一条 firing 记录，详情可见命名空间、严重度、告警名标签和 summary、description 注解 | 未创建数据 | — |
| P076-03 | 按告警名称模糊查询 | 通过 | 在 monitor 输入 `NodeClockNot` 后提交，页面显示 17 条 `NodeClockNotSynchronising`，无其他名称的记录 | 未创建数据 | — |
| P076-04 | 按恢复状态查询应用告警 | 通过 | 在 movie 查询已恢复，当前页 30 条均为 movie 的 resolved 记录。切到未恢复并查询后显示“没有匹配的告警” | 未创建数据 | — |
| P077-01 | 查看 ServiceMonitor 空态 | 通过 | 当前命名空间显示暂无 ServiceMonitor | 未创建数据 | — |
| P079-01 | 查看 PodMonitor 空态 | 通过 | 当前命名空间显示暂无 PodMonitor | 未创建数据 | — |
| P081-01 | 查看 PrometheusRule 空态 | 通过 | 当前命名空间显示暂无 PrometheusRule | 未创建数据 | — |
| P084-01 | 打开日志查询入口 | 通过 | 显示日志查询页面标题和结果表头 | 未创建数据 | — |

| P052-02 | 工作负载名称筛选与清空 | 通过 | 搜索 Web Deployment 只匹配目标，搜索不存在名称后无结果，清空后列表恢复。空态文案见 REQ-002 | 未创建数据 | REQ-002 |
| P052-03 | StatefulSet 创建、缩容、恢复和清理 | 通过 | coding-test / coding-cluster / test2-ns1，`regression-sts-1125` 使用 nginx:1.27-alpine、TCP 80、单副本。`regression-sts-1125-0` 首次为 1/1 Running，缩容后列表为 0/0 Suspended 且 Pod 列表为空，恢复后同名新 Pod 为 1/1 Running，创建时间不同。未使用卷模板或配置 Headless Service | 删除 StatefulSet 后回查工作负载、Pod 和 Service 列表为空 | — |
| P053-01 | 点击 Deployment 名称打开详情 | 通过 | 进入工作负载详情，显示 Pod 副本、监控指标、Service、更新和调度策略 | 未创建数据 | — |
| P052-04 | DaemonSet 单节点调度、回读和清理 | 通过 | coding-test / coding-cluster / test2-ns1，`regression-ds-1125` 使用 nginx:1.27-alpine、TCP 80、`kubernetes.io/hostname=node2`。唯一 Pod 在 node2 为 1/1 Running，工作负载显示 1 就绪，重开编辑正确回填标签。点伸缩后提示 DaemonSet 每个节点一个 Pod，未打开伸缩表单 | 删除 DaemonSet，工作负载及 Pod 列表均恢复空态 | REQ-011 |
| P052-05 | StatefulSet 卷模板自动建卷、绑定与删除回收 | 通过 | coding-test / coding-cluster / test2-ns1，单副本 `regression-sts-pvc` 使用卷模板 `regression-data`，nfs-csi、ReadWriteOnce、1 Gi，挂载到 `/regression-data`，`whenDeleted=Delete`。派生 PVC `regression-data-regression-sts-pvc-0` 为 Bound，Pod 为 1/1 Running，绑定 PV 回收策略 Delete | 仅删除 StatefulSet 后回查工作负载、Pod、PVC 列表与集群全量 PV，测试 PVC 和 `pvc-feb56783-c660-4a27-ba75-85e1965f6cbd` 均无残留 | — |
| P055-02 | 按名称筛选 Pod | 通过 | 筛选应用 Web Pod 后保留一个匹配项，另一服务的 Pod 被排除 | 未创建数据 | — |
| P056-01 | 从 Pod 列表进入详情 | 通过 | 进入对应 Pod 详情，显示匹配名称、Pod 信息、主容器和监控指标 | 未创建数据 | — |
| P057-01 | 从 Pod 打开容器详情 | 通过 | 续测确认上次点击已进入容器详情，对应原 Pod 的 app 容器，显示基础信息、资源配置、探针和挂载区域 | 未创建数据 | — |
| P049-02 | 从流水线列表打开运行历史 | 通过 | 从 movie 行的运行历史入口进入，列表同时显示既有 SUCCEEDED 和 FAILED 记录 | 未创建数据 | — |
| P050-01 | 打开失败运行并读取 DAG 状态 | 通过 | 从失败行进入详情，显示失败原因和被上游失败跳过的两个构建节点 | 未创建数据 | — |
| P050-02 | 从失败 DAG 节点打开归档日志面板 | 通过 | 选择 Git 检出节点后，日志区退出“点上面的节点看它的日志”占位状态，页面显示检出失败及退出码 128 | 未创建数据 | — |
| P050-03 | 打开成功运行详情并核对 DAG | 通过 | 续测确认进入同一成功记录的详情，Git 检出、Java 和 Node 等节点显示成功 | 未创建数据 | — |
| P055-03 | 从 Pod 列表打开并关闭日志 | 通过 | 对应 Pod 的日志弹窗显示实际容器日志。关闭后复核弹窗消失且原 Pod 行可见 | 未创建数据 | — |
| P055-04 | 通过 Pod 列表 Exec 操作专用容器 | 通过 | 同一临时 Deployment 的源卷 Pod 和恢复卷 Pod 均显示连接到 `reader`，分别执行受控文件写入、读取和 SHA-256 计算，输出可见 | 与 P065-04 一并清理，Pod 列表回查为空 | — |
| P061-01 | 打开 ConfigMap 创建表单并取消草稿 | 通过 | 从列表打开创建页，表单显示名称和键值对字段。输入草稿名称后点击取消，续测确认已回列表路径 | 仅输入草稿，未提交 | — |
| P061-02 | ConfigMap 名称必填 | 通过 | 复测捕捉到“请输入名称”提示 | 未创建数据 | — |
| P061-03 | ConfigMap 名称格式校验 | 通过 | 输入含大写和下划线的名称，看到 RFC1123 提示 | 未创建数据 | — |
| P061-04 | ConfigMap 重复键校验 | 通过 | 两个同名键触发重复提示，取消后回到 ConfigMap 列表 | 未创建数据 | — |
| P061-05 | 创建、回读、编辑与删除临时 ConfigMap | 通过 | 平台管理员在 coding-test / coding-cluster / test2-ns1 创建后重开确认初始值，保存并重开确认更新值 | 已删除 regression-check-1121。刷新后目标作用域和原有列表数据可见，临时对象无记录 | — |

| P003-02 | 安装向导缺项预检 | 通过 | 仅填写虚拟集群名称，浏览至最终预检，显示 4 条节点 IP 必填及 1 条 VIP 校验错误 | 未创建数据 | — |
| P007-01 | 从集群列表打开概览 | 通过 | 点击已连接集群名称，详情显示正确标题、健康与异常及基本信息 | 未创建数据 | — |
| P009-01 | 从节点列表打开详情 | 通过 | 点击已连接集群的控制面节点，进入节点详情页 | 未创建数据 | — |
| P011-01 | 打开命名空间创建表单 | 通过 | 创建模式显示名称、配额和限制范围等表单控件 | 未创建数据 | — |
| P012-01 | 从命名空间列表打开详情 | 通过 | 打开 test2-ns1，概览显示基本信息、用量摘要和监控指标 | 未创建数据 | — |
| P016-01 | 查看 GatewayClass 创建表单并取消 | 通过 | 表单加载，取消回到 GatewayClass 列表。变更共享网关类按风险暂缓 | 未创建数据 | — |
| P018-01 | 从地址池列表打开详情 | 通过 | 点击地址池行名称，概览显示基本信息和 IPAM 汇总 | 未创建数据 | — |
| P019-01 | 查看地址池创建表单并取消 | 通过 | 表单加载，取消回到地址池列表。配置提交按风险暂缓 | 未创建数据 | — |
| P021-01 | 查看保留 IP 创建表单并取消 | 通过 | 表单加载，取消回到保留 IP 列表。配置提交按风险暂缓 | 未创建数据 | — |
| P023-01 | 从列表查看 BGP 配置详情 | 通过 | 首测 coding-cluster 无样本。切到 test-work-cluster 后列表有既有 default 配置，点击名称进入只读详情，核对集群、配置名、日志级别 Info 及 YAML 类型。点击返回后列表恢复为默认集群 | 未创建数据 | REQ-006 |
| P024-01 | 查看 BGP 配置创建表单并取消 | 通过 | 表单加载，取消回到 BGP 配置列表。配置提交按风险暂缓 | 未创建数据 | — |
| P026-01 | 从列表查看 BGP 对等体详情 | 通过 | test-work-cluster 上以零匹配节点和对端选择器成功建样本。列表进入详情、详情与 YAML 两项选择器一致，整页刷新仍保留集群和对象。详情返回会重置为 coding-cluster | 临时 `regression-bgp-peer-1122` 已删除，复核测试集群对等体列表为空，平台总览两集群仍显示已连接 | REQ-006 |
| P026-02 | 用对端 IP 和 AS 创建限定节点范围的对等体 | 失败 | test-work-cluster 中，零匹配节点选择器加示例 IP/AS，多次提交停留在表单且回查无对象，未捕获到具体提示。选择器模式可保存，见 P026-01 | 未创建对象 | REQ-008 |
| P026-03 | 发布后复测对端 IP 和 AS 模式 | 接口通过，页面见 P026-04 | `test-work-cluster` 已启用并连接，节点 Ready，Calico v3.33.0 1/1。用对端 `192.0.2.23`、AS `65001` 和恒为假的节点选择器创建，详情与 YAML 回读一致且 YAML 中 AS 为整数。超范围 AS 返回 `0–4294967295` 校验错误，未创建对象。浏览器连接超时，表单和错误显示待补 | 已删除 `regression-bgp-as-5c8dd806`，对等体列表恢复，节点及 Calico 状态正常 | REQ-008 |
| P026-04 | 浏览器复测 BGP IP/AS 表单 | 通过 | 在 `test-work-cluster` 使用零匹配选择器。AS4294967296 提交停留在创建页，滚回表单顶部仍展示合法范围。修正为 65001 后创建成功，重选测试集群可回读 `192.0.2.23` 和 `65001` | 已在测试集群删除 `regression-bgp-ui-87c21e`，回查列表为空 | REQ-006、REQ-008 |
| P027-01 | 查看 BGP 对等体创建表单并取消 | 通过 | 表单加载，取消回到 BGP 对等体列表。配置提交按风险暂缓 | 未创建数据 | — |
| P029-01 | 从列表查看 BGP 过滤器详情 | 通过 | test-work-cluster 下仅填 CIDR 被后端拒绝，补匹配方式 Equal 后成功。详情及 YAML 一致显示 1 条 IPv4 导出规则，其余 3 个方向为 0；刷新保持同一集群和过滤器。保存后列表仍回默认集群 | 未引用的临时 `regression-bgp-filter-1122` 已删除并回查测试集群列表为空 | REQ-006、REQ-007 |
| P030-01 | 查看 BGP 过滤器创建表单并取消 | 通过 | 表单加载，取消回到 BGP 过滤器列表。配置提交按风险暂缓 | 未创建数据 | — |
| P046-01 | 打开中间件详情并切换标签 | 通过 | 实例详情在概览、资源对象和操作记录三个标签间切换 | 未创建数据 | — |
| P048-01 | 打开流水线编辑器 | 通过 | 展示画布、节点区、保存和保存并运行入口 | 未创建数据 | — |
| P048-02 | 新建流水线时校验名称必填 | 通过 | 从列表新建，保持名称为空并点击保存，页面提示“流水线名必填”。返回列表后仍只有原有 2 条流水线 | 未创建数据 | — |
| P048-03 | 隔离命名空间保存、回读及删除流水线定义 | 通过 | coding-test / coding-cluster / test2-ns1。保存后返回列表确认名称、命名空间、描述，再进编辑页确认命令节点已回读 | 仅创建定义 `regression-pipeline-1121`，无运行记录和日志。删除并刷新后确认隔离租户流水线列表为空 | — |
| P048-04 | 保存前校验空画布 | 通过 | 已填名称并选择 test2-ns1，未添加节点时点击保存，页面提示“流水线至少要有一个节点” | 未创建数据 | — |
| P048-05 | 添加命令节点并预览 Tekton YAML | 通过 | 从画布右键菜单添加命令节点，预览显示同一节点名、测试镜像、脚本与 workspace 定义 | 未创建数据 | — |
| P059-01 | 打开 HPA 创建表单并取消 | 通过 | 表单加载，取消回到 HPA 列表 | 未创建数据 | — |
| P059-02 | 配置 HPA 目标、CPU 阈值并回读 | 失败 | coding-test / coding-cluster / test2-ns1，选择测试 Deployment `regression-hpa-target`，副本 1–2、CPU 70%。提交 `regression-hpa` 时 Kubernetes 拒绝，`spec.scaleTargetRef.apiVersion` 为空，提示 `apiVersion must specify API group`。编辑器只传 kind、name，HpaV2Converter 透传 apiVersion。未能验证详情回读或运行时指标 | HPA 列表为空；删除临时 Deployment 后，工作负载和 Pod 列表为空 | REQ-012 |
| P059-03 | 发布后复测 HPA 目标 API 版本 | 通过 | coding-test / coding-cluster / test2-ns1，从临时 Deployment `regression-hpa-target-fix` 创建 `regression-hpa-fix`，列表回读 1–2 副本、CPU 70%。Kubernetes 回读确认 `scaleTargetRef.apiVersion=apps/v1`、`kind=Deployment` 及同一目标 | 删除测试 HPA 后删除临时 Deployment，列表回到空态 | REQ-012 |
| P063-01 | 打开 Secret 创建表单并取消 | 通过 | 表单加载，取消回到 Secret 列表 | 未创建数据 | — |
| P063-02 | Opaque Secret 创建、回读、编辑和删除 | 通过 | coding-test / coding-cluster / test2-ns1。创建后列表显示 Opaque 和 1 项数据。打开编辑回读初始合成值，修改后重新打开回读新值 | `regression-opaque-1122` 已删除，刷新后在同一命名空间确认无残留 | — |
| P063-03 | Secret 名称缺失时禁止创建 | 通过 | 空表单点击创建，页面提示“请输入名称” | 未创建数据 | — |
| P063-04 | TLS Secret 缺证书和私钥时禁止创建 | 通过 | 填有效草稿名称并选 `kubernetes.io/tls`，点击创建提示 `tls.crt` 和 `tls.key` 必填。取消后列表无草稿名称 | 未创建数据 | — |
| P063-05 | Opaque Secret 重复键校验 | 通过 | coding-test / coding-cluster / test2-ns1，用 `regression-secret-dup-1126` 提交两个 `regression-key` 和不同合成值。前端提示 `Key「regression-key」重复` 并停留在编辑器 | 取消后在同一命名空间按完整名称搜索无结果，清空筛选。未创建测试对象 | — |
| P063-06 | Basic Auth Secret 固定键、必填校验和回读 | 通过 | coding-test / coding-cluster / test2-ns1，固定键输入均禁用。空值提交提示 username、password 必填，填写合成值后创建成功，列表显示类型正确、2 项数据。重开确认两条合成值均回显。浏览器回读时固定键名被掩码，未逐键比对 | 删除 `regression-basic-1126` 后按完整名称搜索无结果，已清空筛选 | — |
| P063-07 | Docker 配置 Secret JSON 校验、格式化与回读 | 通过 | coding-test / coding-cluster / test2-ns1，无效 JSON 无法格式化且提交被 Kubernetes 拒绝。合法合成配置 `{"auths":{}}` 可格式化并创建，列表显示 dockerconfigjson 和 1 项数据，重开回读与格式化输入完全一致。未配置仓库账号或执行拉取 | 删除 `regression-docker-1126` 后按完整名称搜索无结果，已清空筛选 | REQ-013 |
| P063-08 | Basic Auth 切换 TLS 及可选 CA 键增删 | 通过 | coding-test / coding-cluster / test2-ns1，从 Basic Auth 切换到 TLS 后只剩两条空值必填行。菜单可添加 `ca.crt`，两条必填行不可删，可选行可删，删除后添加入口重新出现 | 取消 `regression-tls-switch-1126` 草稿，按完整名称搜索无结果，已清空筛选。未创建对象 | — |
| P063-09 | TLS Secret 有效 PEM 保存与原样回读 | 通过 | coding-test / coding-cluster / test2-ns1，使用 `regression-tls.example.invalid` 一次性自签证书与配套私钥创建 `regression-tls-1126`。列表显示 `kubernetes.io/tls` 和两项数据，重开编辑，两份 PEM 均与输入完全一致。未关联工作负载或网关 | 删除测试 Secret 后按完整名称搜索无结果，已清空筛选，本地 PEM 文件已清理 | — |
| P069-01 | 打开 Service 创建表单并取消 | 通过 | 表单加载，取消回到 Service 列表 | 未创建数据 | — |
| P069-02 | ClusterIP Service 创建与回读 | 通过 | 首测缺可绑定对象，取消后列表为空。用隔离 Deployment 复测成功，证据见 P069-03 | 首测未创建数据，复测 Service 已清理 | — |
| P069-03 | 复测 ClusterIP Service 工作负载绑定和清理 | 通过 | coding-test / coding-cluster / test2-ns1，列表和详情均显示自动分配 ClusterIP 及 80→80 TCP，详情 selector 为 `app=regression-nginx-1121` | 已删除唯一临时 Service `regression-nginx-svc-1121`，回查同一命名空间列表为空 | — |
| P069-04 | Headless Service 与 StatefulSet 的绑定和 DNS | 通过 | coding-test / coding-cluster / test2-ns1，单副本 `regression-headless-sts` 指向 `regression-headless-svc`。表单以 ClusterIP 类型填 `None`，绑定 StatefulSet、80→80 TCP。详情显示 headless、selector `app=regression-headless-sts`，Pod 终端查询 `regression-headless-sts-0.regression-headless-svc.test2-ns1.svc.cluster.local` 返回与 Pod 列表一致的 IP | 删除测试 Service 和 StatefulSet，回查 Service、工作负载和 Pod 列表为空 | — |
| P072-01 | 打开 Gateway 创建表单并取消 | 通过 | 表单加载，取消回到 Gateway 列表 | 未创建数据 | — |
| P074-01 | 打开路由策略创建表单并取消 | 通过 | 表单加载，取消回到路由策略列表 | 未创建数据 | — |
| P074-02 | HTTPRoute 匹配与后端字段录入 | 通过 | coding-test / coding-cluster / test2-ns1，从 HTTPRoute 入口打开锁定类型的表单。草稿填写 PathPrefix、GET、请求头及查询参数；新增第二个后端后，端口 8080 和权重 2 在失焦后同时保留。未验证提交、绑定或流量 | 取消 `regression-httproute-1126`，同一命名空间列表为空，未创建资源 | — |
| P074-03 | GRPCRoute 匹配与后端字段录入 | 通过 | coding-test / coding-cluster / test2-ns1，选择 GRPCRoute，录入服务 `regression.v1.Probe`、方法 `Check`、metadata `x-regression=draft`、端口 50051 和权重 2，失焦后仍保留。未验证提交、绑定或流量 | 取消 `regression-grpc-1126`，同一命名空间列表为空，未创建资源 | — |
| P074-04 | TCPRoute 父引用与后端字段录入 | 通过 | coding-test / coding-cluster / test2-ns1，创建菜单进入 TCPRoute，草稿可选入合成父 Gateway，显示监听器且规则限定一条。端口 3306 和权重 3 失焦后保留。未验证提交、绑定或流量 | 取消 `regression-tcp-draft`，同一命名空间列表为空，未创建资源 | — |
| P074-05 | TLSRoute 主机名与后端字段录入 | 通过 | coding-test / coding-cluster / test2-ns1，创建菜单进入 TLSRoute，添加主机名 `tls-regression.example.invalid`，端口 8443 和权重 1 失焦后保留。未验证提交、绑定或流量 | 取消 `regression-tlsroute-draft`，同一命名空间列表为空，未创建资源 | — |
| P074-06 | UDPRoute 单规则与多后端字段录入 | 通过 | coding-test / coding-cluster / test2-ns1，UDPRoute 表单限定一条规则，可添加第二个后端，端口 5353 与权重 2 失焦后保留且第一行未被修改。未验证提交、绑定或流量 | 取消 `regression-udproute-draft`，同一命名空间列表为空，未创建资源 | — |
| P074-07 | 在隔离网关验证路由保存、绑定和流量 | 改在管理集群验证 | `test-work-cluster` 已重新启用并连接。GatewayClass 列表查询返回“集群拒绝了该操作：Not Found”，平台组件状态显示 Istio 未安装。用户授权改在 `coding-cluster` 的专用 ClusterIP 网关验证，结果见 P074-09 | 在 `test-work-cluster` 前置检查只读，未创建数据 | REQ-019 |
| P074-08 | 管理集群 HTTPRoute 保存、YAML 回读与删除 | 通过 | coding-test / coding-cluster / test2-ns1 原网关列表为空。创建 `regression-route-1127`，YAML 回读同命名空间占位 Gateway `regression-no-gw-1127`、Service `regression-no-svc-1127:80`、`PathPrefix /regression-route-1127` 和 `status.parents: []` | 删除唯一临时 HTTPRoute 后列表恢复空态。未创建 Gateway 或 Service | — |
| P074-09 | 管理集群专用网关绑定与 HTTP 实际流量 | 通过 | `coding-cluster / test2-ns1`，用 `kubectl` 创建临时 nginx 后端及 Istio Gateway，用平台接口创建 `regression-gw-18299f09` HTTPRoute 并回读 YAML。派生 Service 为 ClusterIP，Gateway `Programmed=True`，同命名空间路由的 `http` 监听器绑定为 `Accepted=True`、`ResolvedRefs=True`。从测试 Pod 访问网关 DNS，有效 Host 和路径返回 HTTP 200 及专属后端标记，非匹配路径返回 HTTP 404 | 删除后平台列表无测试路由。Kubernetes 回查测试路由、Gateway、后端 Pod 和 Service，以及网关派生的 Service、Deployment、ServiceAccount 和 Pod 均已清理 | — |
| P078-01 | 打开 ServiceMonitor 创建表单并取消 | 通过 | 表单加载，取消回到 ServiceMonitor 列表 | 未创建数据 | — |
| P078-02 | 创建、重开并删除 ServiceMonitor | 通过 | coding-test / coding-cluster / test2-ns1 创建 `regression-sm-1122`，列表显示 1 个端点，重开回填 `regression-monitor-1122 In (service-target)` 和命名端口 `metrics` | 删除测试对象，手动刷新后列表恢复空态 | REQ-009 |
| P078-03 | ServiceMonitor 直接选择 Service 与命名端口联动 | 通过 | coding-test / coding-cluster / test2-ns1，选择 `regression-sm-svc-1127` 后端口候选出现 `metrics（80/TCP）`。保存后列表显示 1 个端点，重新编辑回填 `regression-monitor=service-target-1127`、`metrics` 和 `/metrics` | 依次删除 `regression-sm-direct-1127`、`regression-sm-svc-1127`、`regression-sm-target-1127`，回查列表并刷新确认派生 Pod 已回收 | — |
| P078-04 | ServiceMonitor 实际抓取测试指标 | 通过 | coding-test / coding-cluster / test2-ns1。首测 29 次抓取且 `up=0`。复测使用返回 `text/plain` 的 `/metrics`，从 Prometheus 查询 `last_over_time(regression_ui_scrape_v2_1127[15m])`，`job=regression-scrape-v2-svc-1127` 的样本值为 `1`，带有 `service=regression-scrape-v2-svc-1127` 和 `endpoint=metrics` | 各轮对应 ServiceMonitor、Service、Deployment 已删除并回查，派生 Pod 已回收 | REQ-017 |
| P080-01 | 打开 PodMonitor 创建表单并取消 | 通过 | 表单加载，取消回到 PodMonitor 列表 | 未创建数据 | — |
| P080-02 | 创建、重开并删除 PodMonitor | 通过 | coding-test / coding-cluster / test2-ns1 创建 `regression-pm-1122`，列表显示 1 个端点，重开回填 `regression-monitor-1122 In (pod-target)` 和命名端口 `metrics` | 删除测试对象，手动刷新后列表恢复空态 | REQ-009 |
| P080-03 | PodMonitor 直接选择 Pod 与命名端口联动 | 通过 | coding-test / coding-cluster / test2-ns1，选中 `regression-pm-target-1127` 派生 Pod 后端口候选出现 `metrics（80/TCP）`。保存后列表显示 1 个端点，重开回填 `metrics`、`app=regression-pm-target-1127` 与 `pod-template-hash=5bdd7695d8`。未验证实际抓取 | 先删除 `regression-pm-direct-1127`，后删除临时 Deployment。PodMonitor、工作负载、Pod 列表均回查为空 | REQ-016 |
| P080-04 | PodMonitor 实际抓取测试指标 | 通过 | 同 P078-04 作用域。首测 13 次抓取且 `up=0`。复测直选临时 Pod 的 `metrics` 端口，Prometheus 查到 `job=test2-ns1/regression-scrape-v2-pm-1127` 的 `regression_ui_scrape_v2_1127` 样本值为 `1`，带有对应的 `pod` 标签 | 两轮 PodMonitor 已删除并回查，最后确认 Pod 列表为空 | REQ-017 |
| P082-01 | 打开 PrometheusRule 详情并筛选问题规则 | 通过 | 以 monitor 命名空间已知规则的详情地址打开，显示 9 条规则和 1 条 firing。点击筛选标签后勾选且表格行减少 | 未创建数据 | — |
| P082-02 | 从 PrometheusRule 列表点击名称进入详情 | 通过 | 在 1366px 宽视口点击既有长名称，进入相应规则详情 | 未创建数据，已恢复原视口 | — |
| P082-03 | 窄窗口下点击长规则名称 | 失败 | 884px 宽时点击名称中央仍停留列表，命中的是相邻表格列。1366px 宽时同一点位可点击并成功跳转 | 未创建数据，已恢复原视口 | REQ-004 |
| P082-04 | 发布后复测长规则名称点击 | 通过 | 在 `default / coding-cluster / monitor` 的 884px 和 1366px 视口中，现有规则 `platform-kps-kube-promethe-kube-prometheus-node-recording.rules` 链接位于名称列内，中心命中链接，点击进入对应详情 | 只读，已恢复原视口 | REQ-004 |
| P083-01 | 打开 PrometheusRule 创建表单并取消 | 通过 | 表单加载，取消回到 PrometheusRule 列表 | 未创建数据 | — |
| P083-02 | 创建、回读并删除 PrometheusRule | 通过 | coding-test / coding-cluster / test2-ns1 创建 `regression-rule-1122`。列表显示 1 条、已加载，详情回读 `regression-group`、`RegressionNeverFires`、`vector(0) > 1`，活跃数为 0 | 删除测试规则，点击刷新后列表恢复空态 | — |
| P037-02 | 审计日志按操作者筛选和清空 | 通过 | 不存在账号返回空态且加载更多禁用。清空并查询后恢复 50 条记录 | 未创建数据 | — |
| P037-03 | 审计日志游标分页 | 通过 | 点击加载更多后从 50 条追加至 100 条 | 未创建数据 | — |
| P037-04 | 将测试写操作与审计主体及对象对应 | 通过 | 临时 Secret 的创建、更新、删除均产生 SUCCESS 审计，操作者、对象、租户、集群和命名空间与测试一致。展开删除行显示请求方法和目标、耗时，无业务值 | 本项只读。临时 Secret 已随 P063-02 清理，正常审计记录保留 | — |
| P037-05 | 分别筛选成功、失败、被拒结果 | 通过 | 提交三个结果筛选条件后，当前结果分别为 50 条 SUCCESS、50 条 FAILED 和 39 条 DENIED，每组均无其他状态。FAILED 内含 3 条既有临时仓库表单写入失败记录 | 只读，未创建数据 | — |
| P037-06 | 回查对象上传、删除的审计归属 | 失败 | P067-04 的 `object:upload`、`object:delete` 均记录 SUCCESS、操作者 `coding`、文件名 `regression-obj-1127.txt` 和请求路径，但列表及详情无租户和集群 ID，详情也未显示桶。同期 `object-bucket:create` 可显示 `coding-test`、`coding-cluster` | 只读，测试对象和桶已随 P067-04 清理，保留审计记录 | REQ-018 |
| P037-07 | 搜索并筛选对象删除动作码 | 通过 | 输入 `object:delete` 可选对应建议，查询返回 12 条记录，动作码全部匹配，其中包含本轮 `regression-obj-1127.txt` | 只读，未创建数据 | — |
| P037-08 | 发布后回查对象上传和删除审计 | 接口通过，页面见 P037-09 | 从前端创建 `coding-test` 私有桶，文件选择自动化超时。改用平台接口上传并回读对象，随后删除。以集群、动作码、操作者及 SUCCESS 筛选审计，记录 1568 和 1569 均包含 `coding-cluster`、`coding-test` 及完整桶名和对象键 | 对象删除后列表为空，删除桶后桶列表查无样本，审计记录保留 | REQ-018 |
| P037-09 | 浏览器复测对象上传、删除及审计归属 | 通过 | 在平台视图的 `coding-cluster` 为 `coding-test` 创建私有桶，浏览器文件选择上传 `regression-audit-ui-87c21e.txt`。删除后目录空。审计列表两条 `object:upload`、`object:delete` 均为 SUCCESS，显示 `coding-test`、`coding-cluster` 和完整桶与对象路径 | 测试对象、桶、临时本地文件清理，回查桶列表无测试项 | REQ-018 |
| P004-01 | 对照平台总览与详情的集群可用状态 | 失败 | 总览中 `test-work-cluster` 显示“已连接”。从同一行进入详情显示“集群已禁用，实时数据不可用”，组件摘要显示 9 个未探测。总览未展示禁用信息 | 只读，未创建数据 | REQ-019 |
| P004-02 | 发布后复测禁用集群状态 | 通过 | 新前端入口 `index-53y23esD.js` 加载后，总览及 `test-work-cluster` 详情均显示连接状态“未探测”及管理状态“已禁用”。刷新时曾命中旧入口 `index-BywKhOeh.js`，带版本查询参数重新加载后验证新版 | 只读，未创建数据 | REQ-019 |
| P084-02 | 隔离命名空间日志关键词查询空态 | 通过 | 在 test2-ns1 以一个不存在的关键词查询，页面显示“没有查到日志” | 未创建数据 | — |
| P084-03 | 应用日志查询切换时间预设 | 通过 | 切换至 movie 并清空关键词，最近 24 小时和最近 7 天分别执行查询，显示对应时间及空态，页面无报错 | 未创建数据 | — |
| P084-04 | 读取有数据的集中日志及详情 | 通过 | movie 和 test2-ns1 已查窗口为空。在 default / coding-cluster / platform-system 查询最近一小时，显示 80 条既有日志；打开一条探活日志，核对时间、级别、正文、集群/命名空间、工作负载、Pod 和 Trace/Span 字段 | 本项未创建数据。临时 Nginx 已随 C04-04 清理 | — |

## 已识别的操作级覆盖缺口

以下是依据方案和已有证据识别的下一批缺口。单个页面可能还有其他未拆项操作。

| 模块 | 待补操作 |
|---|---|
| 计算与配置 | HPA 目标和阈值复测。TLS Secret 的有效证书与私钥保存回读已覆盖（P063-09），Headless Service 联动及 StatefulSet 卷模板已覆盖（P069-04、P052-05），Secret 重复键、StatefulSet 稳定 Pod 名及伸缩、DaemonSet 限定节点调度、专用 Pod Exec 已覆盖（P063-05、P052-03、P052-04、P055-04） |
| 存储 | 对象上传、下载内容比对和单对象删除已覆盖（P067-03、P067-04）。独立空 PVC 扩容和带数据快照恢复已覆盖（P064-03、P065-04） |
| 可观测性 | 两种 Monitor 的实际抓取已通过 Prometheus 确认（P078-04、P080-04）。直选目标与命名端口联动已覆盖（P078-03、P080-03），选择器表达式分支已覆盖（P078-02、P080-02），PrometheusRule 创建、详情回读与删除已覆盖（P083-02） |
| 组织与审计 | 普通租户 ConfigMap 与审计、命名空间与租户越界拒绝、第二租户独立入口与可见范围均已覆盖（C03-03 至 C03-05）。管理员加入成员和授予角色审计均为 SUCCESS（1609、1613） |
| 应用与交付 | 私有 Harbor 认证和项目浏览通过（P051-04）。实际私有镜像拉取由用户确认正常、本轮免测。私有 Git 连接认证已用集群内专用样本验证（P038-04），未执行流水线克隆。Git 必填、匿名配置保存、公开仓库成功及无效仓库失败见 P038-02、P038-03 |
| 网络 | 五类路由草稿录入已覆盖（P074-02 至 P074-06），管理集群 HTTPRoute 保存与删除已覆盖（P074-08）。专用网关绑定与实际 HTTP 转发已改在管理集群验证通过（P074-09）。测试集群前置条件见 P074-07 |
| 缺陷复测 | 六项修复发布后均完成前端复测（P082-04、P059-03、P004-02、P031-03、P037-09、P026-04），历史失败仍保留 |

## 页面盘点索引

盘点后将页面状态更新为“已拆项”，并列出对应的操作编号。全部子项有结果且清理记录齐全后标为“已汇总”，在关联栏注明失败、阻塞或暂缓项。

| 页面编号 | 页面与路由 | 盘点状态 | 关联测试项 |
|---|---|---|---|
| P001 | login · `/login` | 已开始盘点 | C01-02、C01-05 |
| P002 | callback · `/callback` | 已开始盘点 | C01-03 验证了正常登录回跳 |
| P003 | cluster-install · `/clusters/new` | 已开始盘点 | P003-01、P003-02 |
| P004 | 总览 · `/overview` | 已开始盘点 | C01-01、P004-01、P004-02、P010-01 |
| P005 | 个人中心 · `/profile` | 已开始盘点 | C01-04、C01-05 |
| P006 | 集群管理 · `/clusters` | 已开始盘点 | P006-01 |
| P007 | 集群概览 · `/clusters/detail` | 已开始盘点 | P007-01 |
| P008 | 节点管理 · `/nodes` | 已开始盘点 | P008-01 |
| P009 | 节点详情 · `/nodes/detail` | 已开始盘点 | P009-01 |
| P010 | 命名空间管理 · `/namespaces` | 已开始盘点 | P010-01 |
| P011 | 命名空间编辑 · `/namespaces/editor` | 已开始盘点 | P011-01 |
| P012 | 命名空间概览 · `/namespaces/detail` | 已开始盘点 | P012-01 |
| P013 | 存储类 · `/resources/storageclasses` | 已开始盘点 | P013-01 |
| P014 | 卷快照 · `/volume-snapshot-cluster` | 已开始盘点 | P014-01 |
| P015 | GatewayClass · `/mesh/gatewayclasses` | 已开始盘点 | P015-01 |
| P016 | GatewayClass 编辑 · `/mesh/gatewayclasses/editor` | 已开始盘点 | P016-01 |
| P017 | 地址池 · `/ops/ippools` | 已开始盘点 | P017-01 |
| P018 | 地址池详情 · `/ops/ippools/detail` | 已开始盘点 | P018-01 |
| P019 | 地址池编辑 · `/ops/ippools/editor` | 已开始盘点 | P019-01 |
| P020 | 保留 IP · `/ops/ipreservations` | 已开始盘点 | P020-01 |
| P021 | 保留 IP 编辑 · `/ops/ipreservations/editor` | 已开始盘点 | P021-01 |
| P022 | BGP 配置 · `/ops/bgpconfigurations` | 已开始盘点 | P022-01 |
| P023 | BGP 配置详情 · `/ops/bgpconfigurations/detail` | 已开始盘点 | P023-01 |
| P024 | BGP 配置编辑 · `/ops/bgpconfigurations/editor` | 已开始盘点 | P024-01 |
| P025 | BGP 对等体 · `/ops/bgppeers` | 已开始盘点 | P025-01 |
| P026 | BGP 对等体详情 · `/ops/bgppeers/detail` | 已开始盘点 | P026-01 至 P026-04 |
| P027 | BGP 对等体编辑 · `/ops/bgppeers/editor` | 已开始盘点 | P027-01 |
| P028 | BGP 过滤器 · `/ops/bgpfilters` | 已开始盘点 | P028-01 |
| P029 | BGP 过滤器详情 · `/ops/bgpfilters/detail` | 已开始盘点 | P029-01 |
| P030 | BGP 过滤器编辑 · `/ops/bgpfilters/editor` | 已开始盘点 | P030-01 |
| P031 | 租户管理 · `/tenants` | 已开始盘点 | P031-01 至 P031-03 |
| P032 | 用户管理 · `/users` | 已开始盘点 | P032-01 |
| P033 | 角色与权限 · `/roles` | 已开始盘点 | P033-01 |
| P034 | 权限点 · `/permissions` | 已开始盘点 | P034-01 |
| P035 | RBAC 模板 · `/templates` | 已开始盘点 | P035-01 |
| P036 | OAuth 客户端 · `/oauth-clients` | 已开始盘点 | P036-01 |
| P037 | 审计日志 · `/audit` | 已开始盘点 | P037-01 至 P037-09 |
| P038 | Git 凭据 · `/git-credentials` | 已开始盘点 | P038-01 至 P038-04 |
| P039 | 通知方式 · `/notification-channels` | 已开始盘点 | P039-01 |
| P040 | 链路追踪 · `/mesh/traces` | 已开始盘点 | P040-01 至 P040-05 |
| P041 | 应用 · `/applications` | 已开始盘点 | P041-01 |
| P042 | 应用 · `/applications/:id` | 已开始盘点 | P042-01 |
| P043 | 服务配置 · `/applications/:id/services/:key` | 已开始盘点 | P043-01 |
| P044 | 中间件 · `/apps` | 已开始盘点 | P044-01 |
| P045 | 中间件实例 · `/apps/instances` | 已开始盘点 | P045-01、P045-02 |
| P046 | 中间件详情 · `/apps/:id` | 已开始盘点 | P046-01 |
| P047 | 流水线 · `/pipelines` | 已开始盘点 | P047-01、P047-02 |
| P048 | 流水线编辑 · `/pipelines/editor` | 已开始盘点 | P048-01 至 P048-05 |
| P049 | 流水线运行 · `/pipelines/runs` | 已开始盘点 | P049-01、P049-02 |
| P050 | 运行详情 · `/pipelines/run` | 已开始盘点 | P050-01、P050-02、P050-03 |
| P051 | 镜像仓库 · `/registries` | 已开始盘点 | P051-01 至 P051-04 |
| P052 | 工作负载 · `/resources/workloads` | 已开始盘点 | P052-01 至 P052-05 |
| P053 | 工作负载详情 · `/resources/workloads/detail` | 已开始盘点 | P053-01 |
| P054 | 工作负载编辑 · `/resources/workloads/editor` | 已开始盘点 | C04-01 |
| P055 | Pod · `/resources/pods` | 已开始盘点 | P055-01 至 P055-04 |
| P056 | Pod 详情 · `/resources/pods/detail` | 已开始盘点 | P056-01 |
| P057 | 容器详情 · `/resources/pods/container` | 已开始盘点 | P057-01 |
| P058 | HPA · `/resources/hpas` | 已开始盘点 | P058-01 |
| P059 | HPA 编辑 · `/resources/hpas/editor` | 已开始盘点 | P059-01 至 P059-03 |
| P060 | ConfigMap · `/resources/configmaps` | 已开始盘点 | P060-01 |
| P061 | ConfigMap 编辑 · `/resources/configmaps/editor` | 已开始盘点 | P061-01 至 P061-05 |
| P062 | Secret · `/resources/secrets` | 已开始盘点 | P062-01 |
| P063 | Secret 编辑 · `/resources/secrets/editor` | 已开始盘点 | P063-01 至 P063-09 |
| P064 | PVC · `/resources/pvcs` | 已开始盘点 | P064-01 至 P064-03 |
| P065 | 卷快照 · `/resources/volumesnapshots` | 已开始盘点 | P065-01 至 P065-04 |
| P066 | 持久卷 · `/resources/persistentvolumes` | 已开始盘点 | P066-01 |
| P067 | 对象存储 · `/object-storage` | 已开始盘点 | P067-01 至 P067-04 |
| P068 | Service · `/resources/services` | 已开始盘点 | P068-01 |
| P069 | Service 编辑 · `/resources/services/editor` | 已开始盘点 | P069-01 至 P069-04 |
| P070 | Gateway · `/resources/gateways` | 已开始盘点 | P070-01 |
| P071 | ListenerSet · `/resources/listenersets` | 已开始盘点 | P071-01 |
| P072 | Gateway 编辑 · `/resources/gateways/editor` | 已开始盘点 | P072-01 |
| P073 | 路由策略 · `/routes` | 已开始盘点 | P073-01 |
| P074 | 路由策略编辑 · `/routes/editor` | 已开始盘点 | P074-01 至 P074-09 |
| P075 | 网格拓扑 · `/mesh/topology` | 已开始盘点 | P075-01 |
| P076 | 告警 · `/alerts` | 已开始盘点 | P076-01 至 P076-04 |
| P077 | ServiceMonitor · `/resources/servicemonitors` | 已开始盘点 | P077-01 |
| P078 | ServiceMonitor 编辑 · `/resources/servicemonitors/editor` | 已开始盘点 | P078-01 至 P078-04 |
| P079 | PodMonitor · `/resources/podmonitors` | 已开始盘点 | P079-01 |
| P080 | PodMonitor 编辑 · `/resources/podmonitors/editor` | 已开始盘点 | P080-01 至 P080-04 |
| P081 | PrometheusRule · `/resources/prometheusrules` | 已开始盘点 | P081-01 |
| P082 | PrometheusRule 详情 · `/resources/prometheusrules/detail` | 已开始盘点 | P082-01 至 P082-04 |
| P083 | PrometheusRule 编辑 · `/resources/prometheusrules/editor` | 已开始盘点 | P083-01、P083-02 |
| P084 | 日志查询 · `/observability/logs` | 已开始盘点 | P084-01 至 P084-04 |

## 缺陷记录

| 编号 | 测试项 | 复现步骤、预期与实际 | 证据及复测结果 |
|---|---|---|---|

## 新需求记录

| 编号 | 测试项 | 场景与现状 | 建议及收益 | 验收条件 | 状态 |
|---|---|---|---|---|---|
| REQ-001 | P060-01 | 461px 宽窗口中，级联选项在右侧超出可点击区域。已用键盘完成同一选择 | 窄窗口内自动调整级联面板位置或改用适配交互 | 在 461px 窗口能用鼠标点选目标命名空间，ConfigMap 列表切换到目标数据 | 待评估 |
| REQ-002 | P052-02 | 有工作负载的命名空间内，名称筛选无匹配时显示“该命名空间下暂无工作负载” | 筛选无匹配时说明当前筛选条件无结果，并提供清空入口 | 有工作负载但关键词无匹配时显示筛选空态，清空后恢复列表 | 待评估 |
| REQ-003 | C04-03 | 运维排查 Pending Pod 时，主容器只显示 Waiting 和未就绪 | 在 Pod 详情展示 Kubernetes 已提供的等待原因和近期相关事件 | 用持续未就绪的样本打开详情，页面能在同一处读到已有等待原因及时间 | 待评估 |
| REQ-004 | P082-03 | 884px 宽窗口下，过长的规则名称延伸到相邻列，名称中央点按后仍停留列表 | 在名称列约束长文本宽度并保留可点区域 | 同一规则名称在 884px 与 1366px 宽时均能点开正确详情 | 发布后前端复测通过 |
| REQ-005 | P047-02 | 已有流水线时输入不匹配关键词，页面提示“还没有流水线” | 筛选无结果时提示“没有匹配的流水线”并提供清空筛选入口 | 有流水线但筛选无结果时显示匹配空态，清空筛选后恢复列表；确实没有流水线时显示创建引导 | 待评估 |
| REQ-006 | C02-04、P023-01、P026-04 | 在 test-work-cluster 节点页整页刷新，从 BGP 配置详情点击返回，或成功创建 BGP 对等体后回列表，都会回到 coding-cluster | 在刷新、详情返回及保存后回列表时保留仍可访问的当前集群 | 上述操作后仍显示原测试集群及其数据；原集群不可用时提示并回退 | 已实现，集群选择单元测试通过，待发布后页面复测 |
| REQ-007 | P029-01 | 过滤器规则只填 CIDR 时表单允许提交，后端返回 CIDR 与匹配方式必须同时填写或同时为空 | 保存前对每条规则联动校验这两个字段，在对应规则行下提示 | 只填其一时定位规则行，补齐二者后可保存 | 待评估 |
| REQ-008 | P026-02 | 对等体表单填示例对端 IP、纯数字 AS 和零匹配节点选择器，提交后停留在表单且回查未创建，测试未捕获到提示；相同作用范围下对端选择器模式可创建 | 排查 IP/AS 模式的提交结果，失败时在页面持续显示具体原因 | 支持的 IP/AS 配置可保存，被拒绝的配置有可读反馈 | 发布后前端复测通过（P026-04） |
| REQ-009 | P078-02、P080-02 | ServiceMonitor 和 PodMonitor 仅填写选择器表达式也可保存，表单仍标注目标必填，端口仍提示先选择目标 | 在目标和选择器处说明二者任选其一，使用表达式时端口提示可输入命名端口 | 两种表单使用有效表达式时，界面提示与可保存条件一致 | 待评估 |
| REQ-010 | P065-04 | 创建 Deployment 时，基础信息留有空端口，仍可提交并收到 Kubernetes `containerPort: Required value`。填写 80 后成功 | 提交前定位无效端口并给出提示，或在允许省略端口时忽略整行空记录 | 默认空端口不会发成无效端口记录，填写合法端口可正常提交 | 待评估 |
| REQ-011 | P052-04 | DaemonSet 的行菜单仍显示可点击的「伸缩」，点击后才提示该类型不支持手动调整副本数 | 隐藏该类型的伸缩动作，或将其禁用并在菜单中说明原因 | 打开 DaemonSet 菜单即可识别伸缩不可用 | 待评估 |
| REQ-012 | P059-02 | 从候选选中有效 Deployment 后，HPA 创建仍因 `spec.scaleTargetRef.apiVersion` 为空被集群拒绝 | 提交时为已选目标附带其 API 版本，Deployment 使用 `apps/v1`，编辑时保留既有目标的 API 版本 | 从下拉选择新建 Deployment 后可以创建 HPA，重开显示相同目标、1–2 副本和 CPU 70% | 发布后前端复测通过 |
| REQ-013 | P063-07 | Docker 配置 Secret 的格式化可识别不合法 JSON，点击创建仍将其提交给集群，收到 Kubernetes 错误才知道不能保存 | 保存前复用 JSON 语法校验，并在 `.dockerconfigjson` 输入处提示 | 不合法 JSON 在表单内被拒绝，合法配置仍可保存和回读 | 待评估 |
| REQ-014 | C05-02 | 流水线编辑器在 567px 宽视口中画布实际宽度为 0，无法从画布右键添加节点。调整至 1366px 后画布宽 612px，添加操作正常 | 窄屏时折叠侧栏或提供可用的画布与节点添加入口 | 567px 和 1366px 视口下均可从空白画布添加命令节点 | 待评估 |
| REQ-015 | P051-02 | 镜像仓库新建连接缺名称时，一次保存同时显示两个「名称必填」提示 | 同一次失败只显示一条提示，或在名称字段旁显示校验错误 | 单次提交空名称时仅显示一处明确提示且不创建连接 | 待评估 |
| REQ-016 | P080-03 | 直接选择 Deployment 的 Pod 后，生成的 PodMonitor 选择器含 `pod-template-hash`。新版本 Pod 使用不同 hash 时，原条件将不再匹配 | 保存前明确展示即将采用的标签，并说明包含版本标签时的匹配范围 | 选择 Deployment 的 Pod 时，用户在保存前能看见版本标签与滚动更新后的影响 | 待评估 |
| REQ-017 | P078-04、P080-04 | Monitor 编辑器的指标候选为空时，页面没有说明是目标未发现、抓取异常还是没有匹配指标 | 在 Monitor 页面展示目标发现状态、最近抓取结果及可用的错误信息 | 用户能区分未发现、抓取失败和查询无匹配三种状态，并定位到对应端点 | 待评估 |
| REQ-018 | P037-06 | 平台视图在租户私有桶上传和删除文件时，审计详情只凭文件名与请求路径无法定位所属桶 | 从已授权的桶记录写入租户、集群与桶标识，保留对象键 | 管理员通过集群过滤能找到对象操作，展开可区分不同桶中的同名对象 | 发布后前端复测通过（P037-09） |
| REQ-019 | P004-01、P074-07 | 详情提示集群禁用时，总览仍仅显示“已连接”，无法在列表判断实时功能是否可用 | 在总览显示禁用状态，并明确区分管理启用状态与连接状态 | 禁用集群在总览可见禁用标记，进入详情时提示一致 | 发布后前端复测通过 |
| REQ-020 | P031-02、C03-03 | 删除临时租户审计为 SUCCESS，但刷新后租户仍在列表。代码中 `TenantService.delete` 设置 `deletedAt`，其调用的 `PlatformTenantMapper.updateByPrimaryKeySelective` 未写入 `deleted_at` | 持久化租户软删除时间，并核对删除回读与审计结果一致 | 删除禁用租户后列表刷新不再显示该条记录，按原 ID 回读返回不存在 | 发布后前端复测通过 |
| REQ-021 | C03-03 | 角色授予已保存，但被授予者此前已访问过权限接口时，短时间内仍读到旧权限。缓存刷新后写权限生效。租户详情的授予提示仅显示已授予 | 在授予或回收角色的反馈中说明权限生效时机 | 有缓存的账号变更角色后，管理员能看到预计生效时间，避免将短暂 403 误判为授权失败 | 已实现，待发布后页面复测 |

## 本地自动化补充结果

2026-11-21 在 platform-web 运行 `npm.cmd run test:install-plan`，64 项通过，0 项失败。覆盖安装方案校验、节点导入与角色、清单和 ZIP 生成。此记录独立于开发环境浏览器用例。
