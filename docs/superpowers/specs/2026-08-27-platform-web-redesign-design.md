# platform-web 全面重设计 + K8s 资源管理域 — 设计文档

日期：2026-08-27 · 状态：已获用户批准（聊天内五节设计，2026-08-27）

## 背景与目标

现有 platform-web 只有四个平台管理页（集群/租户/分配/模板），视觉为 Element Plus 默认样式。
本次重设计：① 视觉全面重制（美观优先，深色默认 + 浅色可切）；② 新增 **K8s 资源管理域**
（管理员代操作租户被分配命名空间内的 K8s 资源）。

## 已定决策（brainstorming 结论）

| 决策点 | 结论 |
|---|---|
| 身份模式 | **管理员代操作**：platform-web → platform-api → k8-server `/admin/**`（admin client），显式传 tenantId/clusterId/ns；不新增租户登录体系 |
| 地址池 | = **Calico IPPool**（集群级 CRD，`crd.projectcalico.org/v1`） |
| 资源清单（本期 8 类） | ConfigMap、Secret、工作负载（Deployment/StatefulSet/DaemonSet 合一页）、ServiceMonitor、Pod、Service、PVC、Calico IPPool。Ingress / Job / CronJob 不做 |
| Pod exec | **完整终端**：xterm.js + k8s-server WebSocket 中继（stdin/stdout/tty/resize） |
| 主题 | 双主题，**深色默认**，顶栏一键切换，localStorage 持久化 |
| 信息架构 | 分组侧边栏（总览 / 平台管理 / 资源管理 / 集群运维）+ **顶栏全局上下文 chip**（租户→集群→命名空间级联，跨页共享、记住上次选择） |
| 创建/编辑方式 | **基础表单**（每类资源常用字段）；详情内 YAML **只读**高亮查看；后续按需把高级字段转表单 |

## 1. 架构与调用链

```
platform-web ──(admin JWT)──▶ platform-api :8081 ──(JWT 透传)──▶ k8s-server /admin/** ──▶ K8s API
                                 业务编排 + DB                      唯一 K8s 适配器(admin client)
```

- **上下文解析全在 platform-api 的 DB**：租户 → 有分配的集群（`platform_tenant_namespace`）→ 该租户在该集群的命名空间。
  零新表、零 DDL、零外键（遵守既有约束）。Calico IPPool / ServiceMonitor 只存在于集群侧。
- **边界校验**：k8s-server 新增 `@AdminTenantValidate` 切面 —— 从 DTO 显式取 tenantId/clusterId/namespace，
  调现有 `PlatformTenantMapper.hasNamespaceAccess()`（与租户域 `@TenantValidate` 同一张分配表）。
  管理员代操作严格限定在租户被分配的命名空间内。
- **exec / logs 通道**：日志 = HTTP 流式透传；exec = WebSocket，**platform-api 做 WS 中继**
  （保持 platform-web 单网关纪律）；若中继实现有坑，退化为前端直连 k8s-server（JWT 在 k8s-server 同样有效）。

## 2. 前端信息架构与页面清单

侧边栏四组：总览 / 平台管理（集群纳管、租户管理、命名空间分配、RBAC 模板）/
资源管理（工作负载、Pod、ConfigMap、Secret、Service、PVC、ServiceMonitor）/ 集群运维（地址池）。

顶栏上下文 chip：`租户 ▾ → 集群 ▾ → 命名空间 ▾`，级联过滤 + localStorage；
资源管理页未选齐时页面内空态引导（不跳转）；地址池页只显示集群选择器。

| 页面 | 核心功能 |
|---|---|
| 总览（新） | 统计卡（集群/租户/分配数、启用占比）+ 快捷入口 |
| 集群纳管（增强） | 搜索、状态徽章、重新开通按钮 |
| 租户管理（增强） | 搜索 + 状态筛选 |
| 命名空间分配（增强） | 展示引用模板名 + RoleBinding 名 |
| RBAC 模板（增强） | 引用明细：被哪些租户的哪些分配引用（后端补接口暴露 countByRoleTemplate） |
| 工作负载（新） | 三类同表（类型列 + tab 筛选）；详情抽屉 tabs：概览 / Pod / Events / YAML(只读) |
| Pod（新） | 上下文 ns 内全部 Pod；日志（流式 follow 抽屉）、exec（xterm.js 终端，多容器可选）、删除 |
| ConfigMap（新） | key-value 行编辑器；详情抽屉 |
| Secret（新） | 类型选择（Opaque/TLS/DockerConfigJson…）；值默认打码、可"显示" |
| Service（新） | 类型 + 端口映射行编辑；展示 ClusterIP/NodePort |
| PVC（新） | 列表 + 创建（容量/StorageClass/访问模式）+ 绑定状态 |
| ServiceMonitor（新） | 基础表单（selector/endpoints port/path/interval）+ YAML 只读 |
| 地址池（新） | Calico IPPool：CIDR/blockSize/使用节点数，增删改；集群级 |
| 登录页（重设计） | 品牌渐变 + K8s 七边形元素 + 居中卡片；PKCE 流程不变 |

## 3. 后端改动

- **k8s-core**：按现有 `ResourceType` + operations/converter/factory 模式扩展：
  StatefulSet、DaemonSet、ConfigMap、Secret、Service、PVC、Pod（list/get/delete/logs/exec）；
  ServiceMonitor 与 Calico IPPool 走 fabric8 **通用 CRD API**（APIGroupVersion + kind，不引代码生成依赖）。
- **k8s-server**：`/admin/resources/{clusterId}/{ns}/...` 资源端点组（带 `@AdminTenantValidate`）；
  Pod 日志流式端点（fabric8 log stream → OutputStream）；Pod exec WebSocket 中继端点
  （Spring WebSocketHandler ↔ fabric8 ExecHandle 双向桥接，握手时复用 JWT 校验）。
- **platform-api**：`/resource/**` Controller+Service 组（上下文解析 + RestClient 透传 + 日志流转发 + exec WS 中继）；
  模板引用明细接口。

## 4. 主题与设计系统

- Element Plus 主题变量整体重制，两套语义 token：**深色默认** / 浅色可切（顶栏切换，localStorage）。
  深色：底 `#0a0f1c`、面板 `#111a2e`、边框 `#1e293b`、强调青→紫（`#22d3ee`/`#8b5cf6`）；
  浅色：白底 + `#2563eb` 主色。通过映射 `--el-*` 变量让全部 EP 组件自动换肤。
- 统一组件规范：状态徽章（一套颜色语义）、空状态（插图+引导文案）、表格规范（hover、操作列固定、相对时间戳）、
  页面级工具栏（标题+描述左、主操作右）。
- 新依赖仅 **xterm.js**；图标沿用 @element-plus/icons-vue。

## 5. 实施顺序（每阶段可独立验收）

| 阶段 | 内容 |
|---|---|
| 0 主题基建 | token 体系 + MainLayout 重制 + 登录页 + 徽章/空状态/PageHeader 规范 → 现有 4 页自动换肤 |
| 1 上下文+资源框架 | 顶栏 chip、context store（localStorage）、路由重组、**ConfigMap 端到端打通**（k8s-core→k8s-server→platform-api→前端），作为全链路参考实现 |
| 2 资源页全家 | Secret / Service / PVC / 工作负载 / ServiceMonitor / Pod 列表页 |
| 3 Pod 深度操作 | 日志流 + exec WS 中继 + xterm.js 终端 |
| 4 收尾 | 地址池（Calico IPPool）+ 总览仪表盘完善 + 存量页功能补全（模板引用明细、搜索筛选） |

## 未决 / 实现期验证项

- `@AdminTenantValidate` 对集群级端点（IPPool）只校验 clusterId 启用状态，不查命名空间
- 日志流式经 platform-api 透传的背压/超时处理（RestClient streaming → ServletOutputStream）
- exec WS 中继的 resize/close 传播；fabric8 ExecHandle 生命周期与 WS session 对齐
- ServiceMonitor CRD 的 group/version（`monitoring.coreos.com/v1`）以集群实际为准
