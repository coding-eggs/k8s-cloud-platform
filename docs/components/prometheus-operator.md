# prometheus-operator（kube-prometheus-stack）

## 1. 概览

| 项 | 值 |
|---|---|
| 组件 code | `prometheus-operator` |
| 显示名 | Prometheus Operator |
| 画像 | BUSINESS / MANAGEMENT（MANAGEMENT 会额外装 Thanos 底座） |
| chart | `kube-prometheus-stack` **92.10.1**（app v0.94.1） |
| release / namespace | `platform-kps` / `monitor`；MANAGEMENT 追加 `platform-thanos` / `monitor` |
| chart 目录 | `charts/components/prometheus-operator/92.10.1/chart/` |
| 上游 | <https://github.com/prometheus-community/helm-charts> |
| 探测判据 | `monitor` Deployment `prometheus-operator/prometheus-operator`（镜像仓库片段） |
| 平台功能 | `metrics-charts`（监控曲线）、`servicemonitor`（ServiceMonitor / PodMonitor 页）、告警规则、租户 `AlertmanagerConfig` |
| API group | `monitoring.coreos.com` |

> ⚠️ **只看 API group 会误判「已安装」**：Helm 卸载不删 CRD，group 会永久残留（2026-10-18 实测）。
> 组件状态只看工作负载判据。

## 2. 两种画像

### BUSINESS（业务集群）

- 只装 kube-prometheus-stack；
- Prometheus 的 `remoteWrite` 指向 **表单里的 Thanos Receive 地址**，不填直接拒绝安装
  （装完才失败的话，指标永远进不了中心存储）；
- 不装 Grafana（`grafana.enabled=false`，可编辑）。

### MANAGEMENT（管理集群）

- kube-prometheus-stack + **Thanos**（query / receive / storegateway / compactor，单副本）；
- `remoteWrite` 默认指向集群内：
  `http://platform-thanos-receive.monitor.svc:10908/api/v1/receive`
  （`10902` 是 HTTP/metrics 端口、`19291` 是旧默认值，都不对）；
- 安装表单必须提供 Thanos 对象存储参数（凭证 Secret 必须在 `monitor`，含 access key / secret key），
  平台会先把它转换成 `monitor/platform-thanos-objstore`（key `objstore.yml`）再执行 helm；
- Grafana 默认开启（可编辑关闭）。

## 3. 契约 values（🔒 不可改）

```yaml
prometheus.prometheusSpec:
  externalLabels: { cluster_name: "${cluster.name}" }
  ruleSelectorNilUsesHelmValues: false
  serviceMonitorSelectorNilUsesHelmValues: false
  podMonitorSelectorNilUsesHelmValues: false
  probeSelectorNilUsesHelmValues: false
  ruleNamespaceSelector: {}
  serviceMonitorNamespaceSelector: {}
  podMonitorNamespaceSelector: {}
  probeNamespaceSelector: {}
  remoteWrite: [{ url: "<Thanos Receive>" }]
alertmanager.alertmanagerSpec:
  alertmanagerConfigMatcherStrategy: { type: OnNamespaceExceptForAlertmanagerNamespace }
  alertmanagerConfigSelector: {}
  alertmanagerConfigNamespaceSelector: {}
kubeApiServer.enabled: false
kubeControllerManager.enabled: false
kubeEtcd.enabled: false
kubeScheduler.enabled: false
crds: { enabled: true, upgradeJob: { enabled: true } }
```

| 值 | 为什么是契约 |
|---|---|
| 四个 `*SelectorNilUsesHelmValues=false` + 四个空 NamespaceSelector | chart 默认只选带 release 标签的对象；平台在**租户命名空间**创建的 ServiceMonitor / PodMonitor / PrometheusRule 会被 Prometheus **静默忽略** |
| `OnNamespaceExceptForAlertmanagerNamespace` | Alertmanager 所在 ns（`monitor`）里的 AMC 处理全部告警（平台自己的联动）；租户 ns 的 AMC 只处理本 ns。用 `OnNamespace` 平台**收不到任何租户告警** |
| 控制面抓取四项关闭 | kubeadm 类集群的这些端点只监听 127.0.0.1，开了 target 恒 DOWN |
| `crds.upgradeJob.enabled=true` | Helm 不升级 `crds/` 目录；官方 chart 自带的 Job 会在 operator 前 apply CRD，是 CRD 升级的唯一正确姿势 |
| `externalLabels.cluster_name` | 中心 Thanos 按 `cluster_name` 区分集群；漏了会与别的集群混在一起 |

## 4. 可编辑 values

| key | 默认 | 说明 |
|---|---|---|
| `prometheus.prometheusSpec.retention` | `15d` | 指标保留 |
| `prometheus.prometheusSpec.replicas` | `1` | Prometheus 副本数（>1 需避免重复 remote-write） |
| `prometheus.prometheusSpec.storageSpec.volumeClaimTemplate.spec.storageClassName` | 空 | **空串不写进 values**：K8s 里 `storageClassName: ""` 表示「禁用动态供给」，会装出 Pending PVC |
| `...resources.requests.storage` | `50Gi` | Prometheus 容量 |
| `alertmanager.alertmanagerSpec.storage...storageClassName` | 空 | 同上 |
| `alertmanager.alertmanagerSpec.storage...requests.storage` | `10Gi` | Alertmanager 容量 |
| `grafana.enabled` | MANAGEMENT `true` / BUSINESS `false` | 是否装 Grafana |

## 5. 平台告警联动

安装/升级成功后、写 `SUCCESS` 之前：

1. 读取 platform-api 配置 `platform.alert.webhook.token`（**未配置直接失败**）；
2. 在 `monitor` 下发固定名字的两个对象（幂等 SSA）：
   - Secret `platform-alert-token`（key `token`）；
   - `AlertmanagerConfig platform-webhook`（按 `namespace + alertname` 分组，resolved 也发；回调地址由表单提供，
     后端自动追加 `clusterId`）；
3. 任一步失败 → 整条操作记 `FAILED`，消息注明「组件已安装，但平台告警联动配置失败」——
   修好 token/地址后**重试升级**即可，不用重装。

注意：

- 回调地址必须是**集群网络可达**的地址，`127.0.0.1` 在平台与集群不同机时不可达；
- token 只在这一跳出现，**不进 helm values / 操作快照**。

## 6. 装完怎么验证

```bash
helm -n monitor list | grep -E 'platform-kps|platform-thanos'
kubectl -n monitor get deploy prometheus-operator
kubectl get crd | grep monitoring.coreos.com
kubectl -n monitor get alertmanagerconfig platform-webhook
kubectl -n monitor get secret platform-alert-token
```

平台侧：

- 集群详情 → 组件 → Prometheus Operator 健康；
- 监控曲线有数据（Thanos 按 `cluster_name` 过滤）；
- ServiceMonitor / PodMonitor 页可创建，Prometheus targets 里能看到；
- 告警页能收到测试告警（`alertApi.selfTest`）。

## 7. 已知坑

1. **租户的 SM/PM 创建成功但 prometheus 不抓** —— 四个 `*NilUsesHelmValues` / NamespaceSelector 契约值丢失。
2. **平台收不到租户告警** —— AMC matcher strategy 用了 `OnNamespace`。
3. **控制面 target 全部 DOWN** —— 没关 `kubeApiServer/kubeControllerManager/kubeEtcd/kubeScheduler`。
4. **CRD 升级失败 / 新 CRD 未安装** —— `crds.upgradeJob.enabled=false`，或 Job 被跳过。
5. **`conflicts with "helm"`** —— 集群里已有手工 `helm install` 的 CRD；平台的 helm 已带 `--force-conflicts`，
   不要手工删 CRD。
6. **PVC Pending** —— 存储类为空时写入了 `storageClassName: ""`（平台已避免）；或集群没有默认 SC。
7. **升级后 Grafana/Alertmanager 数据丢失** —— 存储类/容量被改小或 SC 变更；升级前确认 PVC 未重建。
8. **安装卡在 RUNNING** —— 看操作记录的 `log_tail`；进程重启留下的 RUNNING 可「中止操作」后重试。
9. **告警联动失败但组件已装好** —— `platform.alert.webhook.token` 或回调地址问题，修好后重试升级。

## 8. 新增版本

1. `helm pull kube-prometheus-stack --repo https://prometheus-community.github.io/helm-charts --version <x.y.z> --untar`；
2. 入库 + `provenance.yaml`；
3. `ComponentRecipeCatalog.prometheusReleases(...)` 的 `ReleaseSpec` 最前面加新版本；
   **契约 values 原样保留**，只按新 chart 的实际 key 调整（上游经常重命名 values key，务必 `helm template` 对比）；
4. 检查 `crds.upgradeJob` 在新版本是否还存在（没有的话要换 CRD 升级办法，否则 CRD 永远不升级）；
5. 检查 Thanos 追加逻辑（MANAGEMENT）是否仍需随版本变化；
6. 非生产集群验证：安装 → 指标进 Thanos → 租户 SM/PM 被抓 → 告警回调；
7. 重建 k8s-server + platform-api。

> 升级 kube-prometheus-stack 是**高风险**操作：CRD 变更不可回滚（Helm 不删 CRD 也不降级 schema），
> 先在非生产集群走一遍，再动生产。
