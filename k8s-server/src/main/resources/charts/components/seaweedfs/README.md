# 对象存储（SeaweedFS，集群组件）

> **这是组件级 README（跨版本）**：平台怎么装、约定是什么、踩过哪些坑、加新版本要改哪里。
> 具体 chart 版本的来源/版本号在版本目录：`<version>/README.md`（当前 `4.48.0/`）。
> 选型背景：MinIO 社区版已归档（无安全补丁），需求稿 §5.4 对比后选 SeaweedFS（Apache-2.0）。

## 1. 概览

| 项 | 值 |
|---|---|
| 组件 code | `seaweedfs` |
| 画像 | BUSINESS / MANAGEMENT（每个集群一套） |
| release / namespace | `platform-seaweedfs` / `platform-storage`（Helm `--create-namespace`） |
| 探测判据 | `platform-storage` StatefulSet `seaweedfs-master`（镜像含 `chrislusf/seaweedfs`） |
| 平台功能 | `object-storage`（桶/凭据分配见 §6） |
| 集群内 S3 入口 | `http://seaweedfs-s3.platform-storage:8333` |
| 凭据 | Secret `seaweedfs-s3-secret`（`admin_access_key_id` / `admin_secret_access_key` / `read_*`） |

**模型**：集群级**一套服务 + 多个桶**，不是每租户一个实例。四个组件：

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

### 2.1 镜像版本 = chart 的 appVersion（升级前必须验证）

chart 的 `seaweedfs.image` helper 是 `default .Chart.AppVersion .Values.image.tag` —— **不写 `image.tag` 时，
镜像 tag 就是 Chart.yaml 的 appVersion**。所以：

- chart 版本与镜像版本**不是一回事**，且发布节奏会脱节：chart `4.49.0` 的 appVersion 是 `4.49`，
  但 Docker Hub 上 `chrislusf/seaweedfs:4.49` **没有发布**（只有 `4.49_large_disk_foundationdb`），
  装上去就是 `manifest unknown`（2026-10-24 用户实装报错，因此当前钉 4.48.0 / 镜像 4.48）；
- 升级 chart 前的必检项：`curl -o /dev/null -w '%{http_code}' https://hub.docker.com/v2/repositories/chrislusf/seaweedfs/tags/<appVersion>`
  必须 200；不是 200 时要么换一个 chart 版本，要么在契约里显式钉 `image.tag`（并在版本 README 写明偏差）。

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
  要轮换得手工删 Secret 再 upgrade（会导致存量客户端短暂 403）；
- **没有配额/限速**：一个租户可以写满盘。平台侧目前只能靠监控告警，不做硬配额；
- **删桶 = 删数据**：没有回收站、没有内置快照；备份要外部做（卷 + filer 元数据）；
- **组件间无加密**（`enableSecurity: false`）：跨节点流量是明文，只在集群内网可信的前提下成立。

## 6. 后续（不在当前范围）

1. **桶与凭据分配**：平台按租户/应用发 `endpoint + bucket + AK/SK`（S3 IAM 支持 identity + actions；
   chart 也支持 `s3.createBuckets` 声明式建桶）。需要新的二级资源模型（桶 + 凭据 + 归属）；
2. **给 Thanos / Tempo 用**：它们现在指向外部 MinIO；改成本集群这套只需换表单里的 endpoint/bucket
   （Tempo 用独立桶）；
3. **TLS**：要给集群外访问时再开 `enableSecurity` / `s3.httpsPort`，并考虑用 istio external-gateway 暴露；
4. **安全跟踪**：SeaweedFS 2026 年有过跨桶访问 / OIDC 绕过类 CVE（CVE-2026-54917、CVE-2026-77298）。
   升级前先看 advisory；桶隔离是**逻辑隔离**，强互不信任的租户之间不要共用一套。

## 7. 加一个新版本怎么做

1. `helm pull seaweedfs --repo https://seaweedfs.github.io/seaweedfs/helm --version <x.y.z> --untar`
   → 入库 `seaweedfs/<x.y.z>/chart/` + `provenance.yaml` + `<x.y.z>/README.md`；
2. **先验证镜像存在**（§2.1）：chart 的 appVersion 必须能在 Docker Hub 拉到；
3. 改 `ComponentRecipeCatalog.SEAWEEDFS_RELEASES` 的 `chartVersion` / `appVersion`（**顺序新→旧**）；
4. 核对 values 字段是否改名（本次用到：`fullnameOverride`、`global.seaweedfs.*`、`s3.enabled/enableAuth/port`、
   `master/volume/filer.data(.size/.storageClass)`、`volume.dataDirs`）；改名要同步
   `ComponentInstallService.applySeaweedValues`；
5. `helm template` + `kubectl apply --dry-run=server` 过一遍（会校验 PVC 模板与资源字段）；
6. 真集群装一次，按 §4 验证（Pods Ready、匿名 403、Secret 有 key、建一个桶删一个桶）；
7. 重建 k8s-server + platform-api + platform-web（chart 在 k8s-server 的 resources 里）。
