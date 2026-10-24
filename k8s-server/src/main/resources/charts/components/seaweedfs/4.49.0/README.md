# 对象存储（SeaweedFS，chart 4.49.0）

> 来源：`https://seaweedfs.github.io/seaweedfs/helm`，chart 4.49.0 / app 4.49，
> 2026-10-24 经本地 7890 代理 `helm pull` 后**原样入库**（只删了 `ci/`）。见 `provenance.yaml`。
> 选型背景：MinIO 社区版已归档（无安全补丁），需求稿 §5.4 对比后选 SeaweedFS（Apache-2.0）。

## 1. 概览

| 项 | 值 |
|---|---|
| 组件 code | `seaweedfs` |
| 画像 | BUSINESS / MANAGEMENT（每个集群一套） |
| release / namespace | `platform-seaweedfs` / `platform-storage`（Helm `--create-namespace`） |
| 探测判据 | `platform-storage` StatefulSet `seaweedfs-master`（镜像含 `chrislusf/seaweedfs`） |
| 平台功能 | `object-storage`（对象存储；目前只有这一个功能 code，桶/凭据分配见 §6） |
| 集群内 S3 入口 | `http://seaweedfs-s3.platform-storage:8333` |
| 凭据 | Secret `seaweedfs-s3-secret`（`admin_access_key_id` / `admin_secret_access_key` / `read_*`） |

**模型**：集群级**一套服务 + 多个桶**，不是每租户一个实例。master / volume / filer / s3 四个组件：

| 组件 | 作用 | 持久化 |
|---|---|---|
| `seaweedfs-master`（StatefulSet） | 集群拓扑、文件 → 卷的映射；Raft 多数派 | PVC（Raft 日志/快照 = 集群身份） |
| `seaweedfs-volume`（StatefulSet） | 真正存数据；每个卷文件默认 30GiB | 每副本一块数据盘 PVC |
| `seaweedfs-filer`（StatefulSet） | 目录树语义，S3 建在它之上 | PVC（元数据） |
| `seaweedfs-s3`（Deployment） | S3 网关（`weed s3`） | 无（无状态） |

## 2. 契约 values（🔒 不可改）

```yaml
fullnameOverride: seaweedfs
global:
  seaweedfs:
    createClusterRole: false
    enableSecurity: false
s3:
  enabled: true
  enableAuth: true
admin:
  enabled: false
master: { resources: {...} }
volume: { resources: {...} }
filer:  { resources: {...} }
```

| 值 | 为什么是契约 / 来源 |
|---|---|
| `fullnameOverride: seaweedfs` | 探测认 StatefulSet `seaweedfs-master`；不钉名字，release 名一变面板就永远「未安装」（与 metrics-server 同款坑） |
| `s3.enabled: true` | 不开 S3 只是一个 filer，应用拿不到 endpoint —— 这个组件就没有意义 |
| `s3.enableAuth: true` | 关掉 = S3 匿名可写。凭据由 chart 生成进 `seaweedfs-s3-secret`（`helm.sh/hook` + `resource-policy: keep`，升级不重建） |
| `global.seaweedfs.createClusterRole: false` | chart 默认给 S3 网关 SA 一个**集群级**「任意 Pod 读写」角色（Cloud Drive 用）。平台只当 S3 用，不给存储组件这种权限 |
| `global.seaweedfs.enableSecurity: false` | 组件间 mTLS/JWT 暂不开（内网、平台自用）；**S3 的 AK/SK 校验是独立开关，已开** |
| `admin.enabled: false` | 平台不需要第二个管理面；admin UI 还会引入 worker 等额外工作负载 |
| 四个组件的 resources | 显式钉住，避免存储组件无 requests 把节点挤爆（也就不会撞命名空间配额） |

## 3. 安装参数（表单，⚙️）

| 字段 | 默认 | 说明 |
|---|---|---|
| `storageClass` | **空（必填）** | 三块 PVC 共用；空串在 K8s 里是「不用动态供给」，装完是一组永远 Pending 的卷，而后端会在下发前直接拒 |
| `volumeSize` | `30Gi` | 每个 volume server 的数据盘 |
| `filerSize` | `10Gi` | filer 元数据盘 |
| `masterSize` | `1Gi` | Raft 日志/快照（集群身份，必须持久化） |
| `masterReplicas` | `1` | Raft 要奇数（1 或 3）；HA 至少 3 |
| `volumeReplicas` | `1` | 数据节点数 |
| `s3Replicas` | `1` | S3 网关副本 |

> 为什么不用 `editableValues`：这些值落在嵌套结构里（`volume.dataDirs` 是 list、`*.data` 是 map），
> 而 editableValues 的模型是「路径 → 标量」。后端在 `mergeValues` 之后用
> `applySeaweedValues` 把它们拼进 values，并把整组参数记进操作快照的 `__platform.seaweed`（编辑表单回填）。

⚠️ **容量必须带单位**（`10Gi` / `200Mi` / `1Ti`）：后端用正则校验，裸数字会被拒（避免「30」被当成 30 字节）。

## 4. 装完之后

```bash
kubectl -n platform-storage get sts,deploy,svc
kubectl -n platform-storage get secret seaweedfs-s3-secret -o jsonpath='{.data}' | jq 'keys'
```

- 入口：`seaweedfs-s3.platform-storage:8333`（ClusterIP，不带 TLS）；
- 匿名请求应返回 **403**（证明 `enableAuth` 生效）；
- 桶的创建/删除目前走 S3 API（`aws s3 mb` / SDK），平台页面还没有封装（见 §6）。

## 5. 已知限制

- **升级不会轮换 AK/SK**：Secret 是 `helm.sh/hook` + `keep`，重复 upgrade 沿用已有凭据；
  要轮换得手工删 Secret 再 upgrade（会导致存量客户端短暂 403）。
- **没有配额/限速**：一个租户可以写满盘。平台侧目前只能靠监控告警，不做硬配额。
- **删桶 = 删数据**：没有回收站、没有内置快照；备份要外部做（卷 + filer 元数据）。
- **组件间无加密**（`enableSecurity: false`）：跨节点流量是明文，只在集群内网可信的前提下成立。

## 6. 后续（不在本次范围）

1. **桶与凭据分配**：平台按租户/应用发 `endpoint + bucket + AK/SK`（SeaweedFS 的 S3 IAM 支持 identity + actions；
   chart 也支持 `s3.createBuckets` 声明式建桶）。这需要新的二级资源模型（桶 + 凭据 + 归属），不是加个适配器；
2. **给 Thanos / Tempo 用**：它们现在指向外部 MinIO。改成本集群这套，只需把表单里的 endpoint/bucket 换掉
   （Tempo 用独立桶）；
3. **TLS**：要给集群外访问时再开 `enableSecurity` / `s3.httpsPort`，并考虑用 istio external-gateway 暴露；
4. **安全跟踪**：SeaweedFS 2026 年有过跨桶访问 / OIDC 绕过类 CVE（CVE-2026-54917、CVE-2026-77298）。
   升级前先看 advisory，桶隔离是**逻辑隔离**，强互不信任的租户之间不要共用一套。

## 7. 真集群验证

2026-10-24 对着 `coding-cluster`（k8s 1.35.7、默认 StorageClass `nfs-client`）：

| 检查 | 结果 |
|---|---|
| `helm lint`（带本平台的 values） | 0 failed（只有一条 icon 建议） |
| `helm template` | 12 个对象：SA / ConfigMap / 5 个 Service / `seaweedfs-s3` Deployment / master、volume、filer 三个 StatefulSet / `seaweedfs-s3-secret` |
| **`kubectl apply --dry-run=server`**（真集群准入校验） | 12 个对象全部通过（含 STS 的 `volumeClaimTemplates`、resources、Service 端口） |
| 卸载 chart 默认的 ClusterRole | ✅ 渲染结果里没有 `seaweedfs-rw-cr` / `crb`（`createClusterRole: false` 生效） |

**尚未做**：真正 `helm install` 落盘并跑通读写的验证。本次执行时创建集群资源的操作被权限策略拦下；
上线后由平台的组件 tab 安装一次即可完成这一步（装完按 §4 验证：Pods 全 Ready、匿名请求返回 403、Secret 有 key）。

