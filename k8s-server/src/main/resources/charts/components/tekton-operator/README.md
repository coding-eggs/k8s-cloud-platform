# Tekton Operator（集群组件 `tekton`）

> **这是组件级 README（跨版本）**：平台怎么装、约定是什么、踩过哪些坑、加新版本要改哪里。
> 本次安装的 chart 版本与来源见版本目录：`<version>/README.md`（当前 `0.81.2/`）。

## 1. 概览

| 项 | 值 |
|---|---|
| 组件 code | `tekton` |
| 画像 | BUSINESS / MANAGEMENT |
| release / namespace | `platform-tekton-operator` / `tekton-operator`（**同一 release 跑两遍**） |
| 探测判据 | `tekton-pipelines` Deployment `tektoncd/pipeline/controller-` |
| 平台功能 | `ci`（流水线 / 运行控制 / 构建缓存） |
| 工作负载 ns | `tekton-pipelines` |

## 2. 实锤（来自历史对话）

### 2.1 单遍安装的真实报错（用户贴的）

> `Release "platform-tekton-operator" does not exist. Installing it now. Error: unable to build
> kubernetes objects from release manifest: resource mapping not found for name: "config"
> namespace: "" from "": no matches for kind "TektonConfig" in version "operator.tekton.dev/v1alpha1"
> ensure CRDs are installed first`

在真集群上复现确认：

```
# 单遍：installCRDs=true + extraObjects(TektonConfig)
Error: unable to build kubernetes objects from release manifest ...
no matches for kind "TektonConfig" ... ensure CRDs are installed first

# 第一遍：installCRDs=true，不带 extraObjects
（渲染成功，无错误）
```

原因：Helm 在 **Build 阶段**就把整份 manifest 映射成对象（早于 apply），同一份 manifest 里
CRD 和 CR 并存时，映射 CR 那一刻 CRD 还没进集群。

### 2.2 webhook 服务名对不上（dry-run 发现）

operator 自己注册的 webhook 配置**硬编码** service `tekton-operator-webhook`（官方默认 release 名），
而平台 release 叫 `platform-tekton-operator`，chart 生成的是 `platform-tekton-operator-webhook`。
名字对不上 TektonConfig 一定过不了 admission；证书 DNS 也只签了 `platform-` 前缀的名字
（临时建同名 service 试过，TLS 直接校验失败）。修法：`fullnameOverride: tekton-operator`。

### 2.3 TektonConfig 没有资源默认值字段

0.81.2 的 TektonConfig CRD **没有** `spec.pipeline.default-container-resource-requirements`；
写到直属会被 SSA 严格校验拒绝。默认资源走
`spec.pipeline.options.configMaps['config-defaults'].data`。

### 2.4 构建缓存的 coschedule 前提

集群侧实测（`docs/development/cluster-prerequisites.md` §1.1）：`coschedule: workspaces` 下
一个 TaskRun 不允许挂两个 PVC workspace，构建缓存会一秒内失败。**2026-10-19 起 recipe 固定
`pipelineruns`**（此前误写 workspaces，已由单测钉住）。

## 3. 为什么是「一个 release 跑两遍」

| 遍 | release | 内容 | values |
|---|---|---|---|
| 1 | `platform-tekton-operator` | CRD + operator | `installCRDs=true`，**不含** `extraObjects` |
| 2 | `platform-tekton-operator` | CRD + operator + `TektonConfig` | `installCRDs=true` + `extraObjects` |

k8s-server 多 release 执行顺序、失败即停，保证第一遍 CRD 先就位；第二遍是新的 helm 进程，
discovery 里已有 CRD。操作记录的 `release_names` 去重，不会出现两条同名。

## 4. 契约 values（🔒 不可改）

```yaml
installCRDs: true
fullnameOverride: tekton-operator
operator:
  autoInstallComponents: false
extraObjects: [ <TektonConfig> ]   # 仅第二遍
```

| 值 | 为什么 |
|---|---|
| `installCRDs: true` | 不开就没有 TektonConfig CRD |
| `fullnameOverride: tekton-operator` | 对齐 operator 硬编码的 webhook service 名（§2.2） |
| `operator.autoInstallComponents: false` | TektonConfig 由平台下发；不关会两边抢同一个 config |

`TektonConfig`：

```yaml
apiVersion: operator.tekton.dev/v1alpha1
kind: TektonConfig
metadata: { name: config }
spec:
  profile: "${tekton.profile}"          # 表单：lite / basic / all，默认 all
  targetNamespace: tekton-pipelines
  pipeline:
    coschedule: pipelineruns            # 构建缓存硬前提（2026-10-19 修正）
    options:
      configMaps:
        config-defaults:
          data:
            default-container-resource-requirements: |
              default: { requests: {cpu: 100m, memory: 128Mi}, limits: {cpu: 500m, memory: 512Mi} }
              prepare: ...
              place-scripts: ...
```

## 5. 安装表单

| 字段 | 可选值 | 默认 |
|---|---|---|
| profile | `lite` / `basic` / `all` | `all` |

空 = 上次成功值 → 再空 = `all`；其他值直接拒绝。`profile` 占位符不能解析为空，
否则装出的 Tekton 缺组件而 helm 显示成功。

## 6. 验证

```bash
helm -n tekton-operator list
kubectl -n tekton-operator get deploy
kubectl -n tekton-pipelines get deploy
kubectl get tektonconfig config -o yaml | grep -E 'profile|targetNamespace|coschedule'
kubectl -n tekton-pipelines get cm config-defaults -o jsonpath='{.data.default-container-resource-requirements}'
```

平台侧：组件健康 + 流水线页面可用（`ci` flag）。

## 7. 已知坑

1. **`no matches for kind TektonConfig`** —— 少了两阶段，或第一遍带了 `extraObjects`（§2.1）。
2. **webhook 拒绝 / service not found / TLS 校验失败** —— `fullnameOverride` 丢失（§2.2）。
3. **TektonConfig 反复被改回** —— `operator.autoInstallComponents=true`。
4. **TaskRun 秒失败、无 Pod** —— 命名空间有配额且缺 `default-container-resource-requirements`。
5. **SSA 拒绝未知字段** —— 资源默认值写到了 `spec.pipeline` 直属（§2.3）。
6. **带缓存的运行一秒内失败** —— `coschedule` 不是 `pipelineruns`（§2.4）。
7. **升级后 TektonConfig 不生效** —— 两遍版本不一致；两处 `ReleaseSpec` 必须同版本。

## 8. 新增版本

1. 从 Tekton Operator release 资产拉新 chart；
2. 入库 + `provenance.yaml` + 复制本 README；
3. `TEKTON_RELEASES` 里 **两条 ReleaseSpec 一起换版本**；
4. 检查新版本 TektonConfig CRD 的字段路径与 profile 取值；
5. 检查 `coschedule` 合法值（每个版本可能不同）；
6. 非生产集群装一遍，跑一次带缓存的流水线；
7. 重建 k8s-server + platform-api。

## 9. 卸载

平台不提供卸载。手工 `helm -n tekton-operator uninstall platform-tekton-operator`；
Tekton 的 CRD 与 `tekton-pipelines` 下的工作负载需单独确认清理。
