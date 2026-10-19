# tekton（Tekton Pipelines）

## 1. 概览

| 项 | 值 |
|---|---|
| 组件 code | `tekton` |
| 显示名 | Tekton Pipelines |
| 画像 | BUSINESS / MANAGEMENT 都可装 |
| chart | `tekton-operator` **0.81.2**（app v0.81.2，Tekton Pipelines 1.15.x） |
| release / namespace | `platform-tekton-operator` / `tekton-operator`（**同一个 release 执行两遍**） |
| chart 目录 | `charts/components/tekton-operator/0.81.2/chart/` |
| 上游 | Tekton Operator 0.81.2 release 资产里的 Helm chart |
| 探测判据 | `tekton-pipelines` Deployment `tektoncd/pipeline/controller-`（tag 含 `tekton-pipelines-controller`） |
| 平台功能 | `ci`：流水线页面 / 运行控制 / 构建缓存 |
| 工作负载命名空间 | `tekton-pipelines`（由 operator 按 `TektonConfig.spec.targetNamespace` 创建） |

## 2. 为什么是「一个 release 跑两遍」

chart 的 CRD 在 `templates/kubernetes-crds.yaml`（由 `installCRDs` 控制，默认 false），
而平台要下发的 `TektonConfig` 是 CR。Helm 在 **Build 阶段**就要把整份 manifest 映射成对象，
此时 CRD 还没进集群，会报：

```
no matches for kind "TektonConfig" ... ensure CRDs are installed first
```

所以：

| 遍 | release | 内容 | values |
|---|---|---|---|
| 1 | `platform-tekton-operator` | CRD + operator | `installCRDs=true`，**不含** `extraObjects` |
| 2 | `platform-tekton-operator` | CRD + operator + `TektonConfig` | `installCRDs=true` + `extraObjects: [TektonConfig]` |

k8s-server 的多 release 执行是**顺序、失败即停**，正好保证第一遍的 CRD 在第二遍前已就位。

## 3. 契约 values（🔒 不可改）

```yaml
installCRDs: true
fullnameOverride: tekton-operator
operator:
  autoInstallComponents: false
extraObjects: [ <TektonConfig> ]   # 仅第二遍
```

| 值 | 为什么是契约 |
|---|---|
| `installCRDs: true` | 不开就没有 TektonConfig CRD |
| `fullnameOverride: tekton-operator` | operator 自己注册的 webhook 配置**硬编码** service `tekton-operator-webhook`；平台 release 名带 `platform-` 前缀，不覆盖会导致资源名对不上，TektonConfig 被 webhook 拒（service not found）。2026-10-18 实装踩到 |
| `operator.autoInstallComponents: false` | TektonConfig 由平台下发；不关掉 operator 会自动装一份，两边抢同一个 config |

`TektonConfig`：

```yaml
apiVersion: operator.tekton.dev/v1alpha1
kind: TektonConfig
metadata: { name: config }
spec:
  profile: "${tekton.profile}"          # 表单：lite / basic / all，默认 all
  targetNamespace: tekton-pipelines     # 探测判据就在这里
  pipeline:
    coschedule: workspaces              # ⚠️ 见 §6.1
    options:
      configMaps:
        config-defaults:
          data:
            default-container-resource-requirements: |
              default: { requests: {cpu: 100m, memory: 128Mi}, limits: {cpu: 500m, memory: 512Mi} }
              prepare: ...
              place-scripts: ...
```

- `default-container-resource-requirements` 必须写在 `spec.pipeline.options.configMaps['config-defaults'].data`；
  **不要**写到 `spec.pipeline` 直属 —— 0.81.2 的 CRD 没有这个字段，SSA 严格校验会拒；
- 不写这组默认资源，**有配额的命名空间里 TaskRun 会被直接拒绝**（init 容器平台侧设不了资源）；
- `profile` 占位符不能解析为空，否则装出的 Tekton 缺组件而 helm 显示成功。

## 4. 安装表单

| 字段 | 可选值 | 默认 |
|---|---|---|
| profile | `lite` / `basic` / `all` | `all`（`lite` 仅 Pipelines；`basic` 加 Triggers/Dashboard；`all` 全量，以官方 operator 文档为准） |

空 = 上次成功值 → 再空 = `all`；其他值直接拒绝。

## 5. 装完怎么验证

```bash
helm -n tekton-operator list
kubectl -n tekton-operator get deploy
kubectl -n tekton-pipelines get deploy
kubectl get tektonconfig config -o yaml | grep -E 'profile|targetNamespace'
kubectl -n tekton-pipelines get cm config-defaults -o jsonpath='{.data.default-container-resource-requirements}'
```

平台侧：集群详情 → 组件 → Tekton 健康；流水线页面可用（`PipelineCapabilityService` 读 `ci` flag）。

## 6. 已知坑

### 6.1 `coschedule` 与构建缓存冲突（⚠️ 待修）

当前 recipe 写的是 `coschedule: workspaces`（Tekton 默认值，也是 2026-10-17 集群实测值），
但平台的构建缓存要给**同一个 TaskRun 再挂一块 PVC workspace**。`workspaces` 模式下
`ValidateOnlyOnePVCIsUsed` 会直接 `MarkResourceFailed` —— 症状是运行一秒内失败、Pod 都没起来。

构建缓存的硬前提是 `pipelineruns`（见 `docs/development/cluster-prerequisites.md` §1.1）。
**新集群用平台安装 Tekton 后必须补一刀**，或后续把 recipe 改成 `pipelineruns`：

```bash
kubectl -n tekton-pipelines patch configmap feature-flags --type merge \
  -p '{"data":{"coschedule":"pipelineruns"}}'
```

> 1.15.3 的合法值：`workspaces` / `pipelineruns` / `isolate-pipelinerun` / `disabled`。

### 6.2 其他

1. **`no matches for kind TektonConfig`** —— 少了两阶段，或在第一遍就带上了 `extraObjects`。
2. **TektonConfig 被 webhook 拒：service not found** —— `fullnameOverride` 丢失。
3. **TektonConfig 反复被改回** —— `operator.autoInstallComponents=true`。
4. **TaskRun 秒失败、无 Pod** —— 命名空间有配额且没写 `default-container-resource-requirements`。
5. **SSA 拒绝未知字段** —— 把资源默认值写到了 `spec.pipeline` 直属；按 §3 的路径写。
6. **profile 为空** —— 占位符未解析成功时安装前就应拒绝；不要允许空 profile 进 helm。

## 7. 新增版本

1. 从 Tekton Operator 的 release 资产拉新 chart（0.81.2 时的来源见 `provenance.yaml`）；
2. 入库 + `provenance.yaml`；
3. `ComponentRecipeCatalog.TEKTON_RELEASES` 里 **两条 ReleaseSpec 一起换版本**
   （两遍必须同版本，否则第二遍的 operator 会把 CRD 改回旧 schema）；
4. 检查新版本 `TektonConfig` CRD 的字段路径（`spec.pipeline.options.configMaps`）与 profile 取值是否变化；
5. 检查 `coschedule` 合法值（每个版本可能不同）；
6. 非生产集群装一遍，跑一次带缓存的流水线；
7. 重建 k8s-server + platform-api。

> 升级时 CRD 由 chart 的 `installCRDs` 模板更新（不是 `crds/` 目录），所以两遍都要跑。
> Tekton 的 CRD 变更通常向后兼容，但仍建议先非生产验证。
