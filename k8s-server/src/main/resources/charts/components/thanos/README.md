# Thanos（集群组件 `prometheus-operator` 的 MANAGEMENT 部分）

> **这是组件级 README（跨版本）**：平台怎么装、约定是什么、踩过哪些坑、加新版本要改哪里。
> 本 chart 不是一个独立组件，而是 **`prometheus-operator` 组件在 MANAGEMENT 画像下追加的底座**
> （recipe 里 `withThanos(...)`）。BUSINESS 不装。
> 本次安装的 chart 版本与来源见版本目录：`<version>/README.md`（当前 `0.47.2/`）。

## 1. 概览

| 项 | 值 |
|---|---|
| 所属组件 | `prometheus-operator`（MANAGEMENT only） |
| release / namespace | `platform-thanos` / `monitor` |
| 部署内容 | query / receive / storegateway / compactor（均单副本） |
| 对象存储 | `monitor/platform-thanos-objstore`（key `objstore.yml`） |
| 平台功能 | 中心指标存储：平台概览、监控曲线、网格拓扑、告警规则等按 `cluster_name` 查询 |

## 2. 契约 values（🔒 不可改）

```yaml
global:
  objstore:
    createSecret: false
    secretName: platform-thanos-objstore
    secretKey: objstore.yml
  serviceMonitor:
    enabled: true            # 让 Prometheus 抓到 thanos 组件自身的 up 序列
query: { replicaCount: 1 }
receive: { replicaCount: 1 }
storegateway: { replicaCount: 1 }
bucket: { enabled: false }
```

| 值 | 为什么 |
|---|---|
| `objstore.createSecret=false` | 凭证 Secret 由平台在集群内生成（读来源 Secret → 拼 `objstore.yml` → SSA），凭证不经过 platform-api、不进 values 快照 |
| `serviceMonitor.enabled=true` | chart **默认** `global.thanosRules.enabled=true`，会下发 `thanos-component-absent` 的 `absent(up{job=~".*thanos.*"})` 规则；不开 ServiceMonitor 就没有 `up` 序列，四条规则会恒 firing（2026-10-20 用户实报，见 §4） |
| `bucket.enabled=false` | 平台不用 Thanos 的 bucket web UI |
| 单副本 | 当前规模够用；副本数改动未进表单 |

### 2.1 保留期（MinIO 里存多久）

对象存储里的 block 由 **compactor** 按分辨率删除，chart 默认：

| 分辨率 | 保留 | 对应参数 |
|---|---|---|
| raw（原始采样） | **30d** | `--retention.resolution-raw` |
| 5m（降采样） | **90d** | `--retention.resolution-5m` |
| 1h（降采样） | **365d** | `--retention.resolution-1h` |

平台 recipe **没有覆盖** `compactor.retention`，所以上面就是当前生效值。实际含义不是"存 30 天"：

- 0–30 天：全采样可查；
- 30–90 天：按 5 分钟精度可查（raw block 已被删）；
- 90–365 天：按 1 小时精度可查；
- 超过 365 天：compactor 删除。

另外两个容易混淆的保留期：

- Prometheus 本地 `prometheusSpec.retention`（默认 15d，**组件表单里的「指标保留时长」**）——只影响本地 TSDB，
  到期删本地的，Thanos 桶里按上表保留；
- Receive 的 `receive.tsdb.retention=24h` ——只是 WAL 落盘到对象存储前的缓冲，与 MinIO 保留无关。

要改保留期：在 Prometheus 组件的 **MANAGEMENT 编辑表单 →「Thanos 对象存储」** 一节里改
（对应 `compactor.retention.resolutionRaw/5m/1h`，时长写法 `30d` / `12h`），再重放组件。
⚠️ **改小会立即触发删除且不可逆**；改大只是不再删。

#### 下限是硬约束（写小了 compactor 起不来）

Thanos v0.42.4 `cmd/thanos/compact.go`（降采样默认开启，本平台不暴露 `--disable-downsampling`）：

| 字段 | 下限 | 违反时 |
|---|---|---|
| `resolutionRaw` | **40h**（非 0 时） | 容器启动即退出：`raw resolution must be higher than the minimum block size after which 5m resolution downsampling will occur (40 hours)` |
| `resolution5m` | **10d**（非 0 时） | `5m resolution retention must be higher than the minimum block size after which 1h resolution downsampling will occur (10 days)` |
| `resolution1h` | 无 | — |

- 2026-10-20 实锤：三档都填 `1d` → **Helm 装成功、`platform-thanos-compactor` 直接 CrashLoopBackOff**，
  而平台的操作记录是 SUCCESS。
- 现在两道闸：表单即时拒绝（提示 `Thanos 下限 40h / 10d`），
  `ComponentInstallService` 在下发 helm 之前也会拒（含「按上次快照重放」这条路径，坏值不会被一直沿用）。
- 留空 = 不改动（首次安装用默认 30d / 90d / 365d）。

## 3. 安装表单（挂载在 Prometheus 组件的 MANAGEMENT 表单里）

| 字段 | 说明 |
|---|---|
| 凭证 Secret 命名空间 | **必须**是 `monitor`（Thanos 的 release ns）；跨 ns 读 Secret 无产品必要 |
| 凭证 Secret 名 | 支持 key 组合：`access-key/secret-key`、`accessKey/secretKey`、`ACCESS_KEY/SECRET_KEY`、`MINIO_ACCESS_KEY/MINIO_SECRET_KEY`、`minio-access-key/minio-secret-key`、`AWS_ACCESS_KEY_ID/AWS_SECRET_ACCESS_KEY` |
| MinIO 地址 | `host:port`，可带 `http(s)://`；带 `http://` → `insecure: true`，否则按 HTTPS |
| 桶名 | 推荐独立桶（与 Tempo 的 `tempo` 桶分开） |
| 跳过 TLS 校验 | 自签证书时打开；同时写入 `http_config` 与 `tls_config` |

任一项缺失，组件安装前直接拒绝（不会装完才失败）。

**改完这些字段重放组件时，消费方会被自动滚动重启**（compactor / receive / storegateway）：
Thanos 只在启动时读 `--objstore.config-file`，而 chart 的 `checksum/objstore-configuration`
算的是 `global.objstore.config`（平台从不设置，恒为默认值）——**光改 Secret 不会让 helm 动 Pod 模板**。
平台的做法：写 Secret 后比对 Pod 模板上的 `platform.coding/config-hash` 注解，与本次内容哈希
（`<secretName>:<sha256(objstore.yml)>`）不一致才改写注解（改写即触发滚动），一致则跳过。
所以重放是幂等的；**没有这个注解的旧集群**（本次修复之前装的）会在部署新 k8s-server 后
第一次重放组件时被纠一次，不需要人工 `rollout restart`。见 §6 第 8 条。

## 4. 实锤（来自历史对话）

- **2026-10-18 用户指出端口写错**：
  > 「`remoteWrite: - url: http://platform-thanos-receive.monitor.svc:19291/api/v1/receive`，应该是 10908 端口」

  按 vendored chart 渲染核对：receive 容器参数 `--remote-write.address=0.0.0.0:10908`，
  Service `platform-thanos-receive` 的 `remote-write` 端口也是 **10908**；10902 是 HTTP/metrics，
  19291 是旧版默认值。
- 同一条评审里用户问「thanos 托管你指的是什么？」—— 结论：**不单独做 Thanos 组件**，
  由 Prometheus 组件在 MANAGEMENT 追加安装，避免出现两条往集群写东西的平行通道。
- 同集群同 ns 时 remoteWrite 可以用 Service 名；**跨集群（业务集群 → 管理集群）必须填外部可达地址**，
  所以地址必须进表单（用户评审第 1 条）。
- **2026-10-20 用户报四条告警恒 firing**：部署 Thanos 后出现
  `ThanosCompactIsDown` / `ThanosQueryIsDown` / `ThanosReceiveIsDown` / `ThanosStoreIsDown`，
  规则组 `thanos-component-absent`，表达式 `absent(up{job=~".*thanos.*compact.*"} == 1)` 等。
  根因：chart 默认 `global.thanosRules.enabled=true`（会下发这些规则），而
  `global.serviceMonitor.enabled` 默认 **false** —— Prometheus 里没有任何 thanos target，
  `up` 序列不存在，`absent()` 恒真。修法：recipe 打开 `global.serviceMonitor.enabled=true`
  （§2）。**存量集群**要重新 EDIT/升级一次 `prometheus-operator` 组件，让四个 ServiceMonitor 下发。

## 5. 验证

```bash
kubectl -n monitor get sts,svc | grep thanos
kubectl -n monitor get secret platform-thanos-objstore
kubectl -n monitor logs statefulset/platform-thanos-receive --tail=50
# 改了 S3 地址后：先确认 Pod 是新的（消费方应带 config-hash 注解），再确认连的是新地址
kubectl -n monitor get pods -l app.kubernetes.io/part-of=thanos
kubectl -n monitor get sts platform-thanos-compactor \
  -o jsonpath='{.spec.template.metadata.annotations.platform\.coding/config-hash}{"\n"}'
```

平台侧：管理集群的监控曲线有数据；`count(up{cluster_name="<业务集群>"}) > 0`。

## 6. 已知坑

1. **`19291` / `10902` 端口** —— 都不是 remote-write；正确是 **10908**（用户指出并核对过）。
2. **接收端 404 / 连接拒绝** —— remoteWrite 用的 Service 名只在同集群可解析；跨集群要用外部入口。
3. **对象存储 403** —— 来源 Secret 的 key 组合不在支持列表里，或桶不存在（Thanos 不会建桶）。
4. **TLS 握手失败** —— endpoint 带 `https://` 但 MinIO 是明文，或反之；scheme 决定 `insecure`。
5. **数据在中心查不到** —— Prometheus 侧的 `externalLabels.cluster_name` 丢失（属于 Prometheus 契约）。
6. **四条 `thanos-component-absent` 恒 firing** —— 没开 `global.serviceMonitor.enabled`
   （chart 默认规则开着、抓取关着）；见 §4 最后一条。
7. **compactor CrashLoopBackOff，日志 `raw resolution must be higher than ... (40 hours)`** ——
   保留期写到了下限以下（raw < 40h 或 5m < 10d）；`helm` 是成功的，只有 Pod 起不来。
   改成 `>= 40h` / `>= 10d`（或留空）后重放组件，见 §2.1。
8. **改了 S3 地址但不生效，compactor / receive 还在往旧桶写** —— 2026-10-26 用户实报。
   根因：对象存储配置在 Secret 里而不在 values 里，chart 的 checksum 注解看不到它的内容变化，
   `helm upgrade` 因此不改 Pod 模板、不滚动 Pod；进程又只在启动时读一次配置文件。
   **实测证据**（管理集群，改地址那次操作 `UPGRADE SUCCESS`）：Secret 与 Pod 内挂载的
   `/etc/thanos/objstore.yml` 都已是新地址，但 `cat /proc/net/tcp` 里 compactor / receive
   的 S3 连接仍指向旧的 `192.168.31.80:9002`，Pod 启动时间停在改地址之前。
   现在由 k8s-server 写 Secret 后自动滚动消费方（§3 末尾）。**修复不能只看"helm 成功"** ——
   装完要核对 Pod 是新的：`kubectl -n monitor get pods -l app.kubernetes.io/part-of=thanos`。
   平台上还没升级到这版 k8s-server 的集群，先手工纠一次：
   ```bash
   kubectl -n monitor rollout restart statefulset/platform-thanos-compactor statefulset/platform-thanos-receive statefulset/platform-thanos-storegateway
   ```

## 7. 新增版本

1. `helm pull thanos --repo https://thanos-community.github.io/helm-charts --version <x.y.z> --untar`；
2. 入库到 `thanos/<x.y.z>/chart/` + `provenance.yaml`；
3. `ComponentRecipeCatalog.THANOS_RELEASES` 换版本（release 名/namespace/契约 values 不变）；
4. 核对 receive 的 remote-write 端口是否仍是 10908（写进了
   `THANOS_RECEIVE_IN_CLUSTER` 与文档，改了要同步）；
5. 非生产集群验证：Prometheus remoteWrite → Thanos 查询；
6. 重建 k8s-server + platform-api。

## 8. 卸载

随 Prometheus 组件生命周期；平台不提供卸载。手工 `helm -n monitor uninstall platform-thanos`
不会删除 `platform-thanos-objstore` Secret 与已有对象存储数据。
