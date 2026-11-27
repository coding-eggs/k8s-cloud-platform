# Kubernetes 云平台前端全量回归方案

**测试宗旨**　验证功能完整。执行前将现有需求逐条映射到角色、入口、操作、业务结果和用例编号。用户应能从入口完成整项任务，并在重新打开页面后核对结果。需求已有约定但入口或步骤缺失，即使没有对应路由，也登记缺陷。同一个编辑页的创建、修改模式分别记结果。

**一项一清**　每个用例独立准备数据。创建时登记对象、作用域及关联资源；完成最后一个断言并留存脱敏证据后，立即按依赖逆序删除本项测试数据，撤销本项临时授权，再刷新列表或重新查询确认删除生效。用例失败、中断或阻塞也执行清理。清理失败时记录具体残留和原因，保持该项未收尾，处理后复核。下项需要同类数据时重新创建。既有环境资源仅用于读取。

**本轮暂缓范围**　凡可能使集群失稳、使平台失去集群连接，或无法确认影响范围的操作，均在执行前标为“风险暂缓”。按具体操作判定，适用于准备数据、提交变更、自动化、复测和删除清理。仅当创建和清理均可安全完成时才创建测试数据。风险页面可用已有数据检查列表、详情及明确无副作用的界面交互。

典型暂缓项包括集群重置或卸载、变更集群连接地址或凭据、节点排空或重启、变更控制面或系统组件、修改 Calico 地址池及 BGP 配置，以及改动承载平台连接的网关、路由或权限。测试集群同样适用。

## 一、开测条件

| 准备项 | 执行时核对 |
|---|---|
| 版本与入口 | 记录提交号、前端 URL、后端版本、浏览器版本。首轮以桌面 Chromium 为基线。开发模式下，按 platform-web/vite.config.ts 确认 /auth 指向测试认证服务，/api 经本机 8090 网关访问测试后端。先走一次真实登录。 |
| 身份 | 准备平台管理员、租户 A 可写用户、租户 A 只读用户、租户 B 用户。记录各账号实际获授的页面和操作，使用独立浏览器会话。 |
| 隔离样本 | 在执行对应隔离用例时，为 A 的两个命名空间各建一个同名 ConfigMap，填不同标记值，按需为 B 建独立标记。集群切换另备第二测试集群。逐项记录并清理本项创建的全部样本。 |
| 可写资源 | 约定本轮资源前缀、测试镜像、配额、测试 Git 仓库和流水线。仅对作用域明确、不会影响集群稳定性或连接的专属测试资源执行写入。每次写操作登记对象和清理方式。 |
| 组件数据 | 为 Gateway、Calico、快照、监控、日志、追踪、中间件和流水线逐项登记已安装组件及已知样本。缺组件时可独立验证空态或错误态，正常路径记阻塞。 |

## 二、先跑六条业务链路

按表序执行。第 1 条失败时先恢复登录或网关。其他链路按依赖独立执行，每条保存所用身份、对象标记和最终页面证据。

| 编号 | 身份与操作 | 通过条件 |
|---|---|---|
| C01 登录与退出 | 从受保护入口进入，完成 OAuth 登录，访问总览和个人中心，刷新，退出，再通过后退和深链访问原页。 | 登录后身份和视图正确，刷新可继续使用，退出后受保护数据不可访问。 |
| C02 上下文切换 | A 用户在两个命名空间间快速切换，查看同名 ConfigMap，仅在第二个修改标记。刷新、后退，再分别重开两个对象。在第二测试集群重复。 | 列表和详情与当前选择一致；迟到的响应不会覆盖新选择；仅指定四元组发生变化。 |
| C03 租户和角色隔离 | 管理员、A 可写、A 只读及 B 用户分别使用菜单、命令面板和从正常界面复制的详情链接。核对平台代管视图与租户视图。 | 每个身份的入口、数据、写操作符合其实际授权。只读账号不能保存改动，B 看不到 A 的标记。 |
| C04 工作负载 | A 用户在测试命名空间新建单副本 Deployment，从工作负载进入 Pod 和容器，读出测试镜像的已知日志；将副本改为 2，刷新后删除测试对象。 | 详情中期望和就绪副本最终均为 2；Pod 属于本次工作负载；日志属于所选容器；删除后列表和关联 Pod 最终清空。 |
| C05 交付 | 用测试仓库与凭据保存流水线，执行一个成功任务和一个预设失败的任务，从运行列表进入 DAG、步骤和日志，再刷新详情。 | 两次运行有独立记录；成功产物或步骤输出可核对；失败定位到预设步骤；刷新后终态一致，日志中凭据保持隐蔽。 |
| C06 应用运维 | 从应用列表进入应用及服务详情，找到关联资源；在有数据的时间窗口查看该测试服务日志、告警或追踪，再切换到空窗口。 | 应用、服务和关联对象能按标记对应；已知样本可查，空窗口有明确反馈；切换服务后不残留上一个服务的数据。 |

## 三、逐页执行

将现有需求清单和文末路由底表双向核对。先从侧栏、总览、命令面板进入，再通过列表行、抽屉、标签页进入隐藏功能。动态地址从真实测试记录打开。平台和租户共用的页面分别在两种视图执行。每个实际存在的标签、对象类型、编辑模式及用户可执行操作均须有用例记录。

| 功能组 | 必测操作与判据 |
|---|---|
| 总览、导航与安装向导 | 从总览快捷入口和命令面板到同一页，核对选中菜单；安装向导输入无效节点再修正、返回上一步、导出方案，核对导出内容与页面节点角色。 |
| 集群、节点、命名空间 | 从列表进详情，用已知节点及命名空间核对归属；创建测试命名空间，编辑配额后重开，核对数值和单位。 |
| 组织与平台运维 | 用临时角色和用户验证授权变更生效；按本轮动作核对审计主体和对象。模板、OAuth 客户端、通知渠道用测试配置验证保存、重开、取消和清理。 |
| 应用与交付 | 运行 C05、C06；核对应用服务详情、商店与实例列表之间的关联；跨集群实例须显示正确归属。凭据、仓库分别验证有效和失效的测试配置。 |
| 计算与配置 | 工作负载按页面提供的类型分别走列表、编辑和详情；HPA 核对目标及阈值；ConfigMap、Secret 核对键值编辑和刷新；Pod、容器核对日志切换和终端连接、关闭。 |
| 网络 | Service 核对选择器和端口；GatewayClass、Gateway、ListenerSet 核对归属；逐一打开 HTTP、gRPC、TCP、TLS、UDP 路由编辑器，仅在网关、路由匹配范围和后端均与平台连接隔离时验证保存，否则登记风险暂缓。拓扑核对已知服务关系。Calico 地址池、保留 IP、BGP 各配置仅核对列表和详情，变更项登记风险暂缓。 |
| 存储 | 从测试 PVC 核对绑定的 PV 和 StorageClass；对专用测试卷创建快照，核对就绪状态、来源及集群侧快照内容。对象存储以测试桶上传、下载同一小文件，核对内容，再清理。 |
| 可观测性 | ServiceMonitor、PodMonitor、PrometheusRule 保存后重开核对目标、表达式；用已知测试事件核对告警、日志和追踪的范围、时间筛选、详情与无数据状态。 |

**逐页通用检查**　列表先查已知对象，再查无匹配项，清空条件并换页。表单依次验证必填或格式错误、取消、有效提交和刷新回读。对异步页面观察加载、成功、失败与重试。用浏览器离线模式验证一次读取失败后的恢复；在测试对象上检查重复点击保存是否产生重复记录。缩窄桌面窗口并用键盘完成一次弹窗提交与取消，确认主操作和错误提示可达。

## 四、记录、复测与收尾

每项执行前后均更新[持久化进度台账](./2026-11-20-frontend-regression-progress.md)。按操作编号即时保存结果、清理情况和续测位置。上下文中断后以台账为准，先处理进行中项和残留数据。

每个“路由 × 操作”记录一行。状态使用待测、通过、失败、阻塞、不适用、风险暂缓。风险暂缓项记录功能、具体操作和可能影响，单列为未执行。不适用写明产品或角色依据，阻塞填写缺失条件。截图与日志遮盖令牌、凭据和 Secret 值。

| 需求 / 用例 / 操作 | 版本 / 身份 / 上下文 | 输入与预期 | 实际结果 / 证据 | 功能状态 / 关联编号 | 本项数据 / 清理结果与证据 |
|---|---|---|---|---|---|
| 上下文隔离 / C02 / 切换后保存 | 填本轮版本、A 可写、两个命名空间 | 仅第二个同名对象更新 | 填两个对象刷新后的标记 | 待测 | 执行时登记两个对象，删除后分别重查并记录结果 |

**新需求登记**　执行中发现可完善的功能，当场在本轮报告的“新需求”表追加一行，分配 REQ 编号并关联原用例。写明谁在什么场景遇到什么不便、期望怎样改善，以及如何验证改善有效。已有需求未满足时另建缺陷；同一场景兼有改进建议时关联两个编号。

| 新需求编号 | 来源用例 / 页面 | 使用角色、场景与现状 | 建议能力及用户收益 | 可验证的验收条件 | 状态 |
|---|---|---|---|---|---|
| REQ-序号 | 发现时填写 | 填具体操作与影响 | 填期望行为 | 填用户可观察的结果 | 待评估 |

**完成条件**　逐页核对路由和页内操作，统计通过、失败、阻塞、不适用及风险暂缓数量。逐条报告六条核心链路的实际结果；含暂缓步骤的链路标明未验证部分；越权、写错目标、数据损坏和核心流程中断须修复并复测。其他遗留问题逐条写明影响和发布决定。阻塞项单列，解除后补跑，报告中保留实际覆盖范围。

**收尾复核**　核对每条现有需求的用例和结果、每项测试数据的即时清理记录，以及所有改进建议的新需求编号。纯读取项在清理栏填写“未创建数据”。有残留的用例单列追踪至清理完成。回归报告附缺陷和新需求两份清单。

**复测**　缺陷记录最短复现步骤、预期与实际、影响身份及环境。修复后用新建数据重跑原用例和相邻场景，例如上下文错误须复测同名对象、快速切换和刷新。复测结束同样逐项清理。首批浏览器自动化优先固定 C01–C04 的稳定断言。

## 附录　逐页路由底表

基线取自 platform-web/src/router/index.ts。执行前与待测版本比对，补入新增入口。根路径、旧租户详情入口和未知路径另验重定向后落到总览或进入认证流程。

| 页面 | 路径 |
|---|---|
| login | `/login` |
| callback | `/callback` |
| cluster-install | `/clusters/new` |
| 总览 | `/overview` |
| 个人中心 | `/profile` |
| 集群管理 | `/clusters` |
| 集群概览 | `/clusters/detail` |
| 节点管理 | `/nodes` |
| 节点详情 | `/nodes/detail` |
| 命名空间管理 | `/namespaces` |
| 命名空间编辑 | `/namespaces/editor` |
| 命名空间概览 | `/namespaces/detail` |
| 存储类 | `/resources/storageclasses` |
| 卷快照 | `/volume-snapshot-cluster` |
| GatewayClass | `/mesh/gatewayclasses` |
| GatewayClass 编辑 | `/mesh/gatewayclasses/editor` |
| 地址池 | `/ops/ippools` |
| 地址池详情 | `/ops/ippools/detail` |
| 地址池编辑 | `/ops/ippools/editor` |
| 保留 IP | `/ops/ipreservations` |
| 保留 IP 编辑 | `/ops/ipreservations/editor` |
| BGP 配置 | `/ops/bgpconfigurations` |
| BGP 配置详情 | `/ops/bgpconfigurations/detail` |
| BGP 配置编辑 | `/ops/bgpconfigurations/editor` |
| BGP 对等体 | `/ops/bgppeers` |
| BGP 对等体详情 | `/ops/bgppeers/detail` |
| BGP 对等体编辑 | `/ops/bgppeers/editor` |
| BGP 过滤器 | `/ops/bgpfilters` |
| BGP 过滤器详情 | `/ops/bgpfilters/detail` |
| BGP 过滤器编辑 | `/ops/bgpfilters/editor` |
| 租户管理 | `/tenants` |
| 用户管理 | `/users` |
| 角色与权限 | `/roles` |
| 权限点 | `/permissions` |
| RBAC 模板 | `/templates` |
| OAuth 客户端 | `/oauth-clients` |
| 审计日志 | `/audit` |
| Git 凭据 | `/git-credentials` |
| 通知方式 | `/notification-channels` |
| 链路追踪 | `/mesh/traces` |
| 应用 | `/applications` |
| 应用 | `/applications/:id` |
| 服务配置 | `/applications/:id/services/:key` |
| 中间件 | `/apps` |
| 中间件实例 | `/apps/instances` |
| 中间件详情 | `/apps/:id` |
| 流水线 | `/pipelines` |
| 流水线编辑 | `/pipelines/editor` |
| 流水线运行 | `/pipelines/runs` |
| 运行详情 | `/pipelines/run` |
| 镜像仓库 | `/registries` |
| 工作负载 | `/resources/workloads` |
| 工作负载详情 | `/resources/workloads/detail` |
| 工作负载编辑 | `/resources/workloads/editor` |
| Pod | `/resources/pods` |
| Pod 详情 | `/resources/pods/detail` |
| 容器详情 | `/resources/pods/container` |
| HPA | `/resources/hpas` |
| HPA 编辑 | `/resources/hpas/editor` |
| ConfigMap | `/resources/configmaps` |
| ConfigMap 编辑 | `/resources/configmaps/editor` |
| Secret | `/resources/secrets` |
| Secret 编辑 | `/resources/secrets/editor` |
| PVC | `/resources/pvcs` |
| 卷快照 | `/resources/volumesnapshots` |
| 持久卷 | `/resources/persistentvolumes` |
| 对象存储 | `/object-storage` |
| Service | `/resources/services` |
| Service 编辑 | `/resources/services/editor` |
| Gateway | `/resources/gateways` |
| ListenerSet | `/resources/listenersets` |
| Gateway 编辑 | `/resources/gateways/editor` |
| 路由策略 | `/routes` |
| 路由策略编辑 | `/routes/editor` |
| 网格拓扑 | `/mesh/topology` |
| 告警 | `/alerts` |
| ServiceMonitor | `/resources/servicemonitors` |
| ServiceMonitor 编辑 | `/resources/servicemonitors/editor` |
| PodMonitor | `/resources/podmonitors` |
| PodMonitor 编辑 | `/resources/podmonitors/editor` |
| PrometheusRule | `/resources/prometheusrules` |
| PrometheusRule 详情 | `/resources/prometheusrules/detail` |
| PrometheusRule 编辑 | `/resources/prometheusrules/editor` |
| 日志查询 | `/observability/logs` |
