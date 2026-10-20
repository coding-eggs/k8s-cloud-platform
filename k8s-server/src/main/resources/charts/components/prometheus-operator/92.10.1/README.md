# prometheus-operator / kube-prometheus-stack（chart 92.10.1）

> 来源：`https://github.com/prometheus-community/helm-charts`（kube-prometheus-stack 92.10.1 / app v0.94.1），
> 2026-10-17 原样入库，平台定制只走 recipe values。管理集群追加的 Thanos 见
> [thanos/0.47.2](../../thanos/0.47.2/README.md)。

## 1. 概览

| 项 | 值 |
|---|---|
| 组件 code | `prometheus-operator` |
| 画像 | BUSINESS / MANAGEMENT（MANAGEMENT 额外装 Thanos） |
| release / namespace | `platform-kps` / `monitor` |
| 探测判据 | `monitor` Deployment `prometheus-operator/prometheus-operator`（镜像仓库片段） |
| 平台功能 | `metrics-charts`、`servicemonitor`、告警规则、租户 `AlertmanagerConfig` |
| API group | `monitoring.coreos.com` |

> ⚠️ **只看 API group 会误判「已安装」**：Helm 卸载不删 CRD，group 会永久残留（2026-10-18
> `helm uninstall` 旧 Prometheus 后页面仍显示「已安装」）。组件状态只看工作负载判据。

## 2. 实锤（来自历史对话）

**2026-10-18 用户对批② recipe 的七条评审**（随后落地 `dadae3b`、`a02601f`）：

| # | 用户原话/意见 | 结果 |
|---|---|---|
| 1 | 「remoteWrite 地址你暴漏出表单了吗，部署的时候填写？」 | 新增「Thanos Receive 地址」表单字段；未填直接拒装 |
| 2 | 「matcherStrategy 要 `OnNamespaceExceptForAlertmanagerNamespace` 而不是只有当前命名空间」 | 确认此前 `OnNamespace` 是**真 bug**：平台 AMC 放在 `monitor`，用 `OnNamespace` 永远收不到租户告警 |
| 3 | 「istio 部署要是 ambient 模式…不用暴漏表单，部署默认的」 | 与本文无关，但同一批落地 |
| 4 | 「grafana 默认不开，只有管理集群要开启」 | BUSINESS 默认 `false`，MANAGEMENT 默认 `true` |
| 5 | 「kps 的 crd 更新要处理下方案」 | 启用官方 `crds.upgradeJob.enabled=true` |
| 7 | 「`--kubelet-preferred-address-types=InternalIP` 可以显示钉住」 | `defaultArgs` 全量显式写死 |

**2026-10-18 用户指出 Thanos Receive 端口写错**：

> 「你端口写错了 `remoteWrite: - url: http://platform-thanos-receive.monitor.svc:19291/...`，应该是 10908」

按 vendored chart 渲染核对：容器参数 `--remote-write.address=0.0.0.0:10908`，Service 的
`remote-write` 端口也是 10908；10902 是 HTTP/metrics 端口，19291 是旧版 Thanos 默认值。
同集群同命名空间可以用 Service 名；**跨集群必须填外部可达地址**，所以地址必须进表单。

**「装上了但功能不生效」的典型**：`ruleSelectorNilUsesHelmValues` 默认 `true`，不关掉时
helm/Pod 全绿，但平台在租户 ns 创建的 PrometheusRule 会被 Prometheus **静默忽略**，告警永不触发。

**2026-10-20 用户要求采集 `node_cpu_info`**：node_exporter 1.12.1 里
`--collector.cpu.info` 是 `Bool()` 且**没有 Default**（默认 false），chart 默认 extraArgs 也没带它 ——
所以指标根本不存在。修法：recipe 的 `prometheus-node-exporter.extraArgs` 显式加
`--collector.cpu.info`；同时保留 chart 默认的两条 filesystem exclude（extraArgs 是 list，
Helm 合并整体替换）。存量集群重放一次本组件后，`node_cpu_info{cpu="0",...}` 才会出现在 Prometheus/Thanos。

## 3. 两种画像

### BUSINESS

- 只装 kube-prometheus-stack；`remoteWrite` 指向表单里的 Thanos Receive，不填拒绝安装；
- 不装 Grafana（可编辑）。

### MANAGEMENT

- kube-prometheus-stack + Thanos（query/receive/storegateway/compactor，单副本）；
- `remoteWrite` 默认 `http://platform-thanos-receive.monitor.svc:10908/api/v1/receive`（可改）；
- 安装表单必须提供 Thanos 对象存储参数（凭证 Secret 必须在 `monitor`），平台先生成
  `monitor/platform-thanos-objstore`（key `objstore.yml`）再执行 helm；
- Grafana 默认开启（可编辑关闭）。

## 4. 契约 values（🔒 不可改）

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
prometheus-node-exporter:
  extraArgs:
    - --collector.filesystem.mount-points-exclude=^/(dev|proc|sys|run/containerd/.+|var/lib/docker/.+|var/lib/kubelet/.+)($|/)
    - --collector.filesystem.fs-types-exclude=^(autofs|binfmt_misc|bpf|cgroup2?|configfs|debugfs|devpts|devtmpfs|fusectl|hugetlbfs|iso9660|mqueue|nsfs|overlay|proc|procfs|pstore|rpc_pipefs|securityfs|selinuxfs|squashfs|sysfs|tracefs|erofs)$
    - --collector.cpu.info
```

| 值 | 为什么是契约 |
|---|---|
| 四个 `*SelectorNilUsesHelmValues=false` + 四个空 NamespaceSelector | 默认只选带 release 标签的对象；租户 ns 的 SM/PM/PrometheusRule 会被**静默忽略** |
| `OnNamespaceExceptForAlertmanagerNamespace` | 租户 AMC 只处理本 ns；`monitor` 里的平台 AMC 处理全部告警。用 `OnNamespace` 平台收不到任何租户告警（用户评审第 2 条确认的真 bug） |
| 控制面抓取四项关闭 | kubeadm 类集群这些端点只在 127.0.0.1，target 恒 DOWN（用户评审第 3' 条） |
| `crds.upgradeJob.enabled=true` | Helm 不升级 `crds/`；官方 Job 在 operator 前 apply CRD（用户评审第 5 条） |
| `externalLabels.cluster_name` | 中心 Thanos 按 `cluster_name` 区分集群 |
| `prometheus-node-exporter.extraArgs` | node_exporter 1.12 的 `--collector.cpu.info` 是 Bool 且**没有默认值**（默认 false），不开就没有 `node_cpu_info`；`extraArgs` 是 list，Helm 合并会**整体替换**，所以必须把 chart 默认的两条 filesystem exclude 一起写回 |

## 5. 可编辑 values

| key | 默认 | 说明 |
|---|---|---|
| `prometheus.prometheusSpec.retention` | `15d` | 指标保留 |
| `prometheus.prometheusSpec.replicas` | `1` | 副本数 |
| `...storageClassName` | 空 | **空串不写进 values**（`storageClassName: ""` 会禁用动态供给 → Pending PVC） |
| `...requests.storage` | `50Gi` | Prometheus 容量 |
| `alertmanager...storageClassName` / `storage` | 空 / `10Gi` | Alertmanager 持久化 |
| `grafana.enabled` | MANAGEMENT true / BUSINESS false | 用户评审第 4 条 |
| `compactor.retention.resolutionRaw` / `5m` / `1h` | `30d` / `90d` / `365d` | **Thanos 对象存储保留期**（仅 MANAGEMENT）。表单与凭据/桶同属「Thanos 对象存储」一节；时长写法 `30d` / `12h`，改小立即删历史且不可逆（见 [thanos 文档](../../thanos/0.47.2/README.md) §2.1） |

> 表单按组渲染：**基础配置 / 指标保留 / 存储 / Thanos 对象存储（管理集群）/ 平台联动**，
> 不再把所有输入框平铺在一起（2026-10-20 用户反馈后整理）。

## 6. 平台告警联动

安装/升级成功后、写 `SUCCESS` 之前：

1. 读 `platform.alert.webhook.token`（**未配置直接失败**）；
2. 在 `monitor` 下发固定名字对象（幂等 SSA）：
   - Secret `platform-alert-token`（key `token`）；
   - `AlertmanagerConfig platform-webhook`（按 `namespace + alertname` 分组，resolved 也发；
     回调地址表单提供，后端追加 `clusterId`）；
3. 失败 → 操作记 `FAILED`，消息注明「组件已安装，但平台告警联动配置失败」；修好后重试升级即可。

回调地址必须**集群网络可达**（`127.0.0.1` 在平台与集群不同机时不可达）；token 不进 helm values/快照。

## 7. 验证

```bash
helm -n monitor list | grep -E 'platform-kps|platform-thanos'
kubectl -n monitor get deploy prometheus-operator
kubectl get crd | grep monitoring.coreos.com
kubectl -n monitor get alertmanagerconfig platform-webhook
kubectl -n monitor get secret platform-alert-token
```

平台侧：监控曲线有数据、SM/PM 页可创建且 targets 可见、告警自测能回调。

## 8. 已知坑

1. **租户 SM/PM 创建成功但 prometheus 不抓** —— 四个 selector 契约值丢失（静默，无报错）。
2. **平台收不到租户告警** —— matcher strategy 用了 `OnNamespace`（用户评审确认过的真 bug）。
3. **控制面 target 全 DOWN** —— 没关四项控制面抓取。
4. **CRD 不升级** —— `crds.upgradeJob.enabled=false` 或被跳过。
5. **`conflicts with "helm"`** —— 手工 helm 残留 CRD；平台 helm 已带 `--force-conflicts`。
6. **PVC Pending** —— 写了 `storageClassName: ""`（平台已避免）或集群没有默认 SC。
7. **告警联动失败但组件已装好** —— token/回调地址问题，修好重试升级。
8. **安装卡 RUNNING** —— 看操作记录 `log_tail`；进程重启留下的记录可中止后重试。
9. **查询不到 `node_cpu_info`** —— `--collector.cpu.info` 默认 false；确认 node-exporter DaemonSet 的
   args 里有它（§2 最后一条），且没有把默认的 filesystem exclude 冲掉。

## 9. 新增版本

1. `helm pull kube-prometheus-stack --repo https://prometheus-community.github.io/helm-charts --version <x.y.z> --untar`；
2. 入库 + `provenance.yaml` + 复制本 README；
3. `prometheusReleases(...)` 的 `ReleaseSpec` 最前面加新版本；契约 values 原样保留，
   按新 chart 实际 key 调整（上游常改名，必须 `helm template` 对比）；
4. 检查 `crds.upgradeJob` 是否仍存在（没有就要另找 CRD 升级方案）；
5. 非生产集群验证：装 → 指标进 Thanos → 租户 SM/PM 被抓 → 告警回调；
6. 重建 k8s-server + platform-api。

> CRD 变更不可回滚（Helm 不删 CRD、不降 schema），升级前务必非生产演练。
