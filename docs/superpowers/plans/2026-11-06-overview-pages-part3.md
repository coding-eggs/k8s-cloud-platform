# 平台总览与各维度概览 · 第 3 册（前端与收口）

> 全局约束、Review Focus 见 [第 1 册](2026-11-06-overview-pages.md)。本册消费第 2 册的接口。
> 前端验证用 memory「UI preview 验证法」：dev server `main-dev-check`（5199）+ 假 JWT（`data.permissions` 放所需码）+ XHR stub + 应用自身 router 跳转。

### Task 10: 类型、API 与三个展示组件

**Files:**
- Modify: `platform-web/src/types.ts`（`CountBreakdown`、`PlatformOverview`、`ScopeSummary`，字段与第 2 册 Task 7/8 的 record 同名同形）、`platform-web/src/api/index.ts`、`platform-web/src/apiCodes.ts`（`overviewView: 'platform:overview:view'`）
- Create: `platform-web/src/components/overview/CountCard.vue`、`StatusPie.vue`、`WaterBar.vue`

**Interfaces — Produces:**
- `overviewApi.platform(): Promise<PlatformOverview>`；`clusterApi.summary(clusterId)`、`namespaceApi.summary(clusterId, namespace)`、`tenantApi.summary(tenantId?)` → `ScopeSummary`；`nodeApi.podStat(name, clusterId)` → `NodePodStat`（含 Task 2 新字段）。
- `CountCard` props：`title: string; total: number | null; segments: { label: string; value: number; tone: 'success'|'warning'|'danger'|'info' }[]`；`total == null` 显示「—」；`value == 0` 的段不显示（「已完成」等只在 >0 时出现）。
- `StatusPie` props：`title: string; data: { label: string; value: number; tone }[]; note?: string`。echarts 按需引入（`PieChart` + `TooltipComponent` + `LegendComponent` + `CanvasRenderer`，同 `MetricChart.vue` 的写法），颜色取 theme.css 的 `--success/--warning/--danger/--info`。
- `WaterBar` props：`label: string; used: number | null; total: number | null; unit: 'core' | 'byte'`；任一为 null 显示「—」/「未配置」；≥90% 红、≥80% 橙。数值格式化用 `formatMetricValue`。
- 状态键 → 中文：应用 运行/发布中/未发布/失败/下线；中间件 正常/异常/处理中；CI 成功/失败/运行中/已取消/未运行；告警 严重/警告/其它。放在 `components/overview/labels.ts`。

- [ ] Step 1: 写组件 + labels。
- [ ] Step 2: `npm --prefix platform-web run build` → 成功；提交。

### Task 11: 平台总览页

**Files:** Modify `platform-web/src/views/overview/PlatformOverview.vue`

- 布局（自上而下）：健康条（平台服务 x/y、集群 已连接/不可用（tooltip 列名字）、告警触发中）→ 五张 `CountCard`（应用、中间件、工作负载、Pod、租户：总数 + 启用/停用 + 成员数 + 已分配命名空间）→ 两条 `WaterBar`（集群整体 CPU / 内存 requests ÷ 可分配）→ 两个 `StatusPie`（节点 Ready/NotReady，note=整体 CPU、内存百分比；命名空间 已分配/未分配/平台系统，note=配额整体用量）→ 保留原「集群状态表 / 快捷操作 / 最近访问」。
- 一次请求 `overviewApi.platform()`；无 `apiCodes.overviewView` 时不请求，只显示原有区块。
- 不做「需要关注」清单。

- [ ] Step 1: 改页面。
- [ ] Step 2: 浏览器验证（stub 返回一个集群不可用 + 各状态有值的数据）：截图确认五卡、两条水位、两个饼图、健康条「不可用 1」。
- [ ] Step 3: build 通过；提交。

### Task 12: 集群 / 命名空间 / 租户概览页签

**Files:** Modify `platform-web/src/views/ClusterDetailView.vue`（「概览」tab）、`platform-web/src/views/NamespaceDetailView.vue`（「概览」tab）、`platform-web/src/components/tenant/TenantOverviewPanel.vue`

- 集群：现有「健康与异常」上方插入四张 `CountCard`（应用、中间件、CI、告警）+ 两个 `StatusPie`（节点、命名空间）。数据 `clusterApi.summary`。
- 命名空间：基本信息下方加 `CountCard` × 6（工作负载、Pod、应用、中间件、CI、告警）+ `WaterBar` × 2（配额 CPU / 内存，无配额「未配置」）。
- 租户（平台侧详情）：「资源与配额」上方加 `CountCard` × 4（应用、中间件、CI、告警）+ 命名空间数 / 成员数；「需要关注」保留。
- 摘要里某块为 null（无权限）→ 不渲染该卡。

- [ ] Step 1: 三处改动。
- [ ] Step 2: 浏览器验证三页各一张截图；其中一页 stub 让 `alerts=null`，确认告警卡不出现。
- [ ] Step 3: build；提交。

### Task 13: 节点概览页签

**Files:** Modify `platform-web/src/views/NodeDetailView.vue`

- 基本信息下方：`WaterBar` × 4（CPU requests、CPU limits、内存 requests、内存 limits，分母 allocatable）+ Pod `CountCard`（Running/Pending/已完成/异常，副标题「Pod 数 / podsLimit」）。数据 `nodeApi.podStat`。

- [ ] Step 1: 改页面 → Step 2: 浏览器截图验证 → Step 3: build；提交。

### Task 14: 租户工作台 + 收口

**Files:** Modify `platform-web/src/views/overview/TenantOverview.vue`；Modify `docs/task-ledger-2026-10-12.md`（S1.3 状态 + 部署说明）

- 工作台：原四张卡改为 `CountCard` × 6（应用、中间件、工作负载、Pod、CI、告警）+ `WaterBar` × 2（配额 CPU / 内存）；保留「需要关注」「常用操作」「最近访问」。数据：`tenantApi.overview`（已有，工作负载 / Pod / 配额）+ `tenantApi.summary`（四类计数）。
- 台账 S1.3：勾 S1.3.1–3，D5 标「已定（见 spec）」，进度表写部署：**重放 `V2026_11_06_4`** → 重建 k8s-core 相关的 k8s-server、platform-api、platform-web（以及 auth / k8s-server 若 Task 6 改了 health 放行）→ 重新登录；可选配置 `PLATFORM_GATEWAY_HEALTH_URL`。

- [ ] Step 1: 改工作台 → 浏览器截图（租户 token）。
- [ ] Step 2: 全量 `mvn -q -pl platform-api,k8s-server -am test` + `npm --prefix platform-web run build` → 全绿。
- [ ] Step 3: 更新台账与 memory；提交。
