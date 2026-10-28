# 对象存储（SeaweedFS S3）HTTPS 化方案（待实施）

> 2026-10-28 记录。**本批不做**：先把镜像来源纳管（[2026-10-28-image-registry-mirror-design.md](2026-10-28-image-registry-mirror-design.md)）
> 收口，再做这条；原因见 §7（它要重放 Thanos / Milvus / Tempo，和镜像那批混在一起不好定位问题）。

## 1. 目标

对象存储的对外入口从明文 HTTP 换成 HTTPS，让桶/凭据/数据不再裸奔。

## 2. 现状（2026-10-28 实测）

| 项 | 值 |
|---|---|
| SeaweedFS | 组件 chart 4.48.0，`platform-storage` 命名空间，`seaweedfs-s3` ClusterIP `:8333`（HTTP） |
| 对外入口 | istio `external-gateway` 的 listener `seaweedfs-s3`，端口 **38333 / HTTP** |
| 平台「S3 连接地址」 | 每集群一条（`k8s_cluster.object_storage_url`），当前 `192.168.31.80:38333`（明文） |
| 消费方 | Thanos objstore、Milvus（`config.minio`）、Tempo（平台对象存储模式）、平台后端（桶/凭据/对象读写，SigV4）、ES 的 IK 插件下载 |

**关键利好**：所有消费方都只吃「地址 + 是否明文」两个信息，且明文开关已经全部按地址 scheme 推断：

| 消费方 | 判断点 |
|---|---|
| Thanos objstore | `ThanosObjstoreObjects`：`http://` → `insecure: true`；`https://` → false + `insecure_skip_verify` |
| Milvus CR | `MiddlewareService`：`raw.startsWith("https://")` → `useSSL` |
| Tempo（平台模式） | `plaintext = !endpoint.startsWith("https://")` |
| 平台后端 | `SeaweedSigV4` 取 endpoint 的 scheme；公信证书走 JVM 默认信任 |

## 3. 方案：TLS 在网关终止，SeaweedFS 内部保持 HTTP

```
Pod/外部客户端 --https--> external-gateway (38443, 证书 s3.ackcp.cloud) --http--> seaweedfs-s3.platform-storage:8333
```

- SeaweedFS 本身**不改**（不碰 `weed s3` 的证书参数、不给每个 Pod 挂证书）；
- 只需把「S3 连接地址」改成 `https://s3.ackcp.cloud:38443`，四家消费方自动跟随；
- **先新增 38443 与 38333 并存**，验证通过后再下线 38333（直接改会中断现有写入）。

## 4. 需要的东西

1. **证书**：`s3.ackcp.cloud`（或通配符 `*.ackcp.cloud`，一次覆盖 harbor / s3 / 以后的服务）；
2. **Gateway listener**：`s3-https` / 38443 / HTTPS / Terminate / `certificateRefs` → Secret（放 `istio-system`）；
3. **平台配置**：`k8s_cluster.object_storage_url = https://s3.ackcp.cloud:38443`；
4. **解析**：`s3.ackcp.cloud` 必须能被 **Pod 内**客户端解析（CoreDNS → 节点 DNS），不是只在节点上；
5. **重放**：Thanos（objstore Secret 由平台重写）、Milvus（`useSSL` 变化 → CR 滚动）、Tempo（重装）。

## 5. 连锁后果

- **HTTPS 后必须用域名访问**：证书是给 `s3.ackcp.cloud` 的，`https://192.168.31.80:38443` 会校验失败。
  Milvus/Tempo 现在用的正是 IP，必须一起换成域名。
- **IK 插件下载保持集群内 HTTP**（`http://seaweedfs-s3.platform-storage:8333/...`）：只读、匿名、不过网关，没必要绕一圈。
- SeaweedFS 的 filer / 管理台 / `weed shell` 不受影响。

## 6. 不推荐：SeaweedFS 自己开 TLS

`weed s3 -cert.file -key.file` 看似更彻底，实际：

- 每个集群内客户端（Thanos / Tempo / Milvus / 平台后端 / IK init）都要改地址 + 信任证书；
- 公信证书要求 SAN 对上 `seaweedfs-s3.platform-storage` 这个 Service 名 —— 对不上；自签又回到「人人装 CA」。

收益与网关终止相同，改动面大得多。

## 7. 验收与工作量

| 步骤 | 验收 |
|---|---|
| 证书 + listener | `curl -I https://s3.ackcp.cloud:38443/`（未签名 → 403，证明 TLS 通、S3 在应答） |
| 平台地址切换 | 平台「对象存储」页能列桶（走 https + SigV4） |
| Thanos | compactor 日志无凭证/连接错误；`objstore.yml` 里 `insecure: false` |
| Milvus | 实例 CR `useSSL: true`，Pod 正常、能读旧数据 |
| Tempo | 平台模式下 `insecure: false`，启动 `ListObjects` 无 500 |

工作量：证书与 listener 0.5 天、平台配置与三家重放验证 0.5 天。

## 8. 触发条件

镜像来源纳管（11 个任务）收口后再做；若那批里已经做过「网关 443 终止 TLS」的通用化改造，本例只剩证书 + listener
+ 配置三件事。
