# 流水线与中间件实例列表改租户级展示（设计）

> 2026-11-14。参考 2026-11-13 的「应用列表按租户跨集群展示」（commit 16f3012d）。
> 改动：platform-api / platform-data / platform-web；**无 SQL、无新端点、k8s-server 不改**。

## 1. 目标

1. **流水线列表**不再跟顶栏「集群/命名空间」走：按**租户**列出全部流水线（跨集群、跨命名空间），
   列表增加「集群」「命名空间」两列；顶栏不再显示上下文级联。
2. 顺带处理：流水线列表上三个**命名空间级**的东西（构建缓存、待重试工作区、CI 能力门控）
   挪到**流水线编辑器**（用户 2026-11-14 定）。
3. **中间件实例列表**同样改租户级：去掉顶栏级联、列表加「集群」列（命名空间列已有）。

## 2. 现状

| 项 | 现状 |
|---|---|
| 流水线表 `platform_pipeline` | 有 `cluster_id` / `namespace`，**没有 tenant_id**；租户边界靠分配表 `platform_tenant_namespace` |
| `/pipeline/list` | 必传 clusterId + namespace，`listByNamespace`；`NamespaceAccessGuard.assertAccessible` 校验 |
| 流水线列表页 | 顶栏级联 + 页面级能力横幅 + 构建缓存弹窗 + 待重试工作区清理，全部按当前 ns |
| 中间件实例表 | 有 `tenant_id` / `cluster_id` / `namespace` |
| `/middleware/instance/list` | 必传 clusterId（`requireCluster`），再按 tenant/namespace 过滤；状态按 `code|namespace` 分组回查集群 |
| 中间件实例列表页 | 跟顶栏集群/命名空间，列里只有命名空间 |

## 3. 方案

### 3.1 流水线：租户级列表（platform-api / platform-data）

- `PipelineService.list(PipelineDTO query)` 三态：
  1. 给了 `clusterId + namespace` → 保持原行为（`assertAccessible` + `listByNamespace`），兼容其它调用方；
  2. 没给位置、能确定租户（`auth.effectiveTenantId(query.getTenantId())` 非空）→
     取该租户的分配行（`allocationMapper.listByTenant`，给了 clusterId 就只在集群内），
     按 `(cluster_id, namespace)` 对去查 `platform_pipeline`；没有分配行 → 空列表；
  3. 没位置、也没有租户（平台侧、未戴帽）→ 全量（`listAll`），与管理面其它列表一致。
- mapper 新增 `listByPairs(@Param("pairs") List<PlatformTenantNamespace>)`：
  `where deleted_at is null and ((cluster_id=.. and namespace=..) or ...)`；空集合由 service 短路。
- **集群名**：`PipelineDTO.clusterName`（`BaseResources` 已有 `tenantId`，无需加请求字段），
  用 `ClusterService.options()` 一次查全表映射；集群已删除 → null，前端退回 clusterId。
- 不加 `tenant_id` 列：需要回填历史数据、且命名空间归属已经能表达边界（见 §4）。

### 3.2 中间件实例：租户级列表（platform-api / platform-data）

- `MiddlewareService.list(clusterId, code, tenantId, namespace)`：
  `clusterId` 为空时走新增的 `selectByTenant(tenantId, code)`；
  租户也为空（平台侧）→ `selectAll(code)`；给了 clusterId 保持原逻辑。
- **状态回查分组键改为 `clusterId|code|namespace`** —— 原先只有 `code|namespace`，
  跨集群后不同集群的同名命名空间会被并成一组，`refreshGroup` 用 `first.clusterId` 去查，
  会拿错集群的状态。
- `PlatformMiddlewareInstance` 加一个非表字段 `clusterName`（`insert` 列清单是显式的，不受影响），
  service 用 `ClusterService.options()` 回填。

### 3.3 前端：流水线

- **路由**：`pipelines`、`pipelines/editor`、`pipelines/runs`、`pipelines/run` 四条去掉 `context: 'full'`
  （同应用页）；运行页/详情页改用 `useApplicationContext().ensure()` 取租户，不再指望顶栏。
- **列表页**（PipelineListView.vue）：
  - 租户取自 `useApplicationContext()`，`pipelineApi.list(undefined, undefined, tenantId)`；
  - 列：名称 / **集群** / **命名空间** / 描述 / 创建人 / 更新时间 / 操作；
  - 保留关键字过滤、刷新、「运行」弹窗（`runCanvas` 来自行数据）；
  - **点「运行」时**按该行的 `clusterId + namespace` 现探 `/pipeline/capability`，不可用就提示原因、
    不弹运行对话框（列表不再有页面级能力横幅）；`available === null`（不知道）照常放行；
  - 去掉构建缓存弹窗、待重试工作区、集群/命名空间依赖。
- **编辑器**（PipelineEditorView.vue）：
  - 顶部「集群 / 命名空间」：**新建时可编辑**（选项来自 `/context` 中本租户的「集群 + 命名空间」组合），
    已有流水线只读展示；清掉顶栏后它是唯一的位置来源；
  - 接收列表挪过来的「构建缓存」弹窗与「待重试工作区」清理（按 `form.clusterId + form.namespace`）；
  - `usePipelineCapability` 改为传 `(clusterId, namespace, tenantId)`，缓存状态才有值；
    CI 不可用时仍禁用「保存并运行」并显示原因；
  - 直接打开 `/pipelines/editor`（无 query）时先 `ensure()` 上下文，位置为空就不能保存。
- **新建入口**：列表的「新建流水线」（含命令面板）直接进编辑器，位置在编辑器里选，不再弹位置对话框。

### 3.4 前端：中间件实例列表

- 路由 `apps/instances` 去掉 `context: 'full'`（详情页 `apps/:id` 保留顶栏，仍是命名空间级页面）。
- 页面：
  - 租户取 `useMiddlewareTenant()`（`ensure()`），不再读顶栏集群/命名空间；
  - `middlewareApi.instanceList(undefined, code, tenantId)`；去掉集群下拉与级联 watch；
  - 新增「集群」列（`clusterName || clusterId`）；
  - 类型筛选的选项取**当前实例里出现过的 code**，显示名沿用一次 catalog 查询（第一个可用集群）；
  - 状态筛选保持前端计算；「去商店创建」「刷新」「连接」「删除」不变。

## 4. 租户边界与不变量

- **流水线按分配表收窄**：租户级查询只查该租户分配过的 `(集群, 命名空间)` 对；
  平台侧身份不受限（`NamespaceAccessGuard` 同一条判断）。
- 同名命名空间在不同集群**不合并**（流水线按对查询；中间件状态分组带 clusterId）。
- 集群名只做展示，集群已删除时退回 clusterId，不伪造。
- 权限点不变：仍走 `tenant:pipeline:read`、中间件 `pageCodes.app` 等既有码，无新端点。

## 5. 不做什么

- 不新增 `/pipeline/options` 之类端点（位置选项用现有 `/context`，避免动权限行）。
- 不给 `platform_pipeline` 加 tenant_id 列，不写迁移。
- 不改流水线运行历史/详情的查询口径（仍按 pipelineId）。
- 中间件商店（`/apps`）与中间件详情（`/apps/:id`）的顶栏行为不动。
- k8s-server 不改。

## 6. 验证

- 后端：`mvn -pl platform-api -am test`（PipelineServiceTest 增租户级用例；MiddlewareServiceTest 增跨集群用例）。
- 前端：`npm run build`（vue-tsc + vite）。
- 浏览器 preview（假 token + XHR stub）：
  - 流水线列表显示两列、按租户返回、无顶栏级联；点运行按行探测能力；
  - 编辑器新建时可选位置，缓存/待重试可用；
  - 中间件实例列表无顶栏、有集群列，类型筛选正常。

## 7. 部署

无 SQL、无新端点；先起 platform-api，再发 platform-web（前端新字段 `clusterName` 由新后端返回）。
