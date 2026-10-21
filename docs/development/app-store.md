# 应用商店（计划①：引擎 + 管理员安装）

> 设计：[2026-10-21-app-store-and-operator-design.md](../superpowers/specs/2026-10-21-app-store-and-operator-design.md)
> 计划：[2026-10-21-app-store-engine-and-admin-install.md](../superpowers/plans/2026-10-21-app-store-engine-and-admin-install.md)
> 本阶段只开管理员路径（`strict=false`）；目录治理、来源搜索、租户自助在计划②，Operator 视图在计划③。

## 1. 执行链

```
platform-api（权限、应用记录、异步操作）
   └─ POST /chart/preview|apply|uninstall ──▶ k8s-server
                                              拉 chart（helm pull，校验 sha256 digest）
                                              → helm template
                                              → ManifestInspector 静态检查
                                              → helm upgrade --install
                                              → helm get manifest 复核
```

- chart 一律以 **tgz** 传递，不展开：`helm template` / `upgrade` 直接接受打包路径。
- digest 是 tgz 文件的 sha256（`sha256:...`）：上架时记录，安装时逐字节比对。
  管理员直装可以不填 digest（不校验），填了就必须一致。

## 2. 三道闸（`ManifestInspector`）

| 检查 | 结果 |
|---|---|
| 同一对象在清单里出现多次 | BLOCK |
| 显式 `metadata.namespace` ≠ 目标命名空间 | **BLOCK（含管理员）** |
| 集群级 kind（内置表 + CRD `spec.scope`） | 租户 BLOCK；管理员 WARN |
| `kind: Namespace` | BLOCK |
| `helm.sh/hook` 资源 | 租户 BLOCK；管理员 WARN |
| 未知 kind（集群里没有对应 CRD） | 租户 BLOCK（fail-closed）；管理员 WARN |

- 租户路径额外加 `--skip-crds --no-hooks`（严格参数见 `HelmRunner.InstallOptions`）。
- 装后复核用 `helm get manifest` 再跑一遍检查：越界不自动删除，应用详情标「需复核」。

## 3. 状态与操作

- `platform_app.status`：`INSTALLING → RUNNING / FAILED`，卸载后 `UNINSTALLED`。
- 每次安装/升级/卸载写 `platform_app_operation`（`PENDING/RUNNING/SUCCESS/FAILED`），
  前端每 2 秒轮询一次，最多 5 分钟。
- **同一个应用不能有两个进行中的操作**：`running_lock = cluster_id|app_id` 的唯一键。

## 4. 卸载语义

`helm uninstall` 只删 release 里的对象：**CRD 与 PVC 保留**。UI 的确认框写明这一点。
平台不允许卸载"不是平台安装"的应用（没有 `platform_app` 记录 → 拒绝）。

## 5. 常见失败

| 现象 | 原因 |
|---|---|
| 拉取 chart 失败 | 源不可达 / 版本不存在 / Harbor 需要凭据 |
| digest 不一致 | 源内容变了（或填错了）；**不会**继续安装 |
| 渲染失败 | values 写错 / chart 模板错误 |
| `CROSS_NAMESPACE：...` | chart 里显式写了别的命名空间 |
| `CLUSTER_SCOPED：...` | 租户路径装了带集群级对象的 chart |
| `UNKNOWN_KIND：...` | chart 引用了集群里没装的 CRD |

## 6. 部署

1. 先重放 `V2026_10_21_1__app_engine.sql`（无权限行则服务拒启）；
2. 重建 **platform-data + platform-common + k8s-server + platform-api + platform-web**；
3. k8s-server 需要访问 Harbor / chart repo 的网络；配置项：
   - `platform.chart.max-size-bytes`（默认 50MB）
   - `platform.chart.discovery-ttl`（CRD scope 缓存，默认 5m）
