# thanos（chart 0.47.2）

> 来源：`https://github.com/thanos-community/helm-charts`，chart 0.47.2 / app v0.42.4。
> 上游 Thanos 项目不发布官方 Helm chart，使用社区 chart（2026-10-17 用户裁定），原样入库。
>
> 本 chart 不是一个独立组件，而是 **`prometheus-operator` 组件在 MANAGEMENT 画像下追加的底座**
> （recipe 里 `withThanos(...)`）。BUSINESS 不装。

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
query: { replicaCount: 1 }
receive: { replicaCount: 1 }
storegateway: { replicaCount: 1 }
bucket: { enabled: false }
```

| 值 | 为什么 |
|---|---|
| `objstore.createSecret=false` | 凭证 Secret 由平台在集群内生成（读来源 Secret → 拼 `objstore.yml` → SSA），凭证不经过 platform-api、不进 values 快照 |
| `bucket.enabled=false` | 平台不用 Thanos 的 bucket web UI |
| 单副本 | 当前规模够用；副本数改动未进表单 |

## 3. 安装表单（挂载在 Prometheus 组件的 MANAGEMENT 表单里）

| 字段 | 说明 |
|---|---|
| 凭证 Secret 命名空间 | **必须**是 `monitor`（Thanos 的 release ns）；跨 ns 读 Secret 无产品必要 |
| 凭证 Secret 名 | 支持 key 组合：`access-key/secret-key`、`accessKey/secretKey`、`ACCESS_KEY/SECRET_KEY`、`MINIO_ACCESS_KEY/MINIO_SECRET_KEY`、`minio-access-key/minio-secret-key`、`AWS_ACCESS_KEY_ID/AWS_SECRET_ACCESS_KEY` |
| MinIO 地址 | `host:port`，可带 `http(s)://`；带 `http://` → `insecure: true`，否则按 HTTPS |
| 桶名 | 推荐独立桶（与 Tempo 的 `tempo` 桶分开） |
| 跳过 TLS 校验 | 自签证书时打开；同时写入 `http_config` 与 `tls_config` |

任一项缺失，组件安装前直接拒绝（不会装完才失败）。

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

## 5. 验证

```bash
kubectl -n monitor get sts,svc | grep thanos
kubectl -n monitor get secret platform-thanos-objstore
kubectl -n monitor logs statefulset/platform-thanos-receive --tail=50
```

平台侧：管理集群的监控曲线有数据；`count(up{cluster_name="<业务集群>"}) > 0`。

## 6. 已知坑

1. **`19291` / `10902` 端口** —— 都不是 remote-write；正确是 **10908**（用户指出并核对过）。
2. **接收端 404 / 连接拒绝** —— remoteWrite 用的 Service 名只在同集群可解析；跨集群要用外部入口。
3. **对象存储 403** —— 来源 Secret 的 key 组合不在支持列表里，或桶不存在（Thanos 不会建桶）。
4. **TLS 握手失败** —— endpoint 带 `https://` 但 MinIO 是明文，或反之；scheme 决定 `insecure`。
5. **数据在中心查不到** —— Prometheus 侧的 `externalLabels.cluster_name` 丢失（属于 Prometheus 契约）。

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
