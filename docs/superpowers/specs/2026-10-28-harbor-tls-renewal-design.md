# Harbor TLS 证书续期方案（暂缓实施）

> 2026-10-28 立项。**本批不做**：镜像纳管（[2026-10-28-image-registry-mirror-design.md](2026-10-28-image-registry-mirror-design.md)）
> 先收口，续期作为独立的运维自动化任务后续实施；本文是那一步的设计依据。
> 目标读者：集群运维、平台后端。

## 1. 背景与目标

Harbor 已改为 **HTTPS + 域名 + 免费证书（3 个月有效）**，镜像来源纳管（组件/中间件从平台 Harbor 拉）依赖它。

**证书过期 = 所有镜像拉取失败**（`x509: certificate has expired or is not yet valid`）：
已在跑的 Pod 不受影响，但任何重启/新建/调度都会 `ImagePullBackOff`；平台后端调 Harbor API 也会被拒。

免费证书没有「续期」概念，只有「到期前重新签发」。本方案的目标：**到期前自动换证并让 Harbor 生效，零人工**。

## 2. 现状事实（2026-10-28）

| 项 | 值 |
|---|---|
| Harbor | 组件 `harbor`（chart 1.19.4），命名空间 `harbor`，`harbor-nginx` 对外 |
| 证书 | 手工申请并下载（3 个月）；DOMAIN / 证书链由运维保管 |
| chart 能力 | `expose.tls.certSource: secret` + `expose.tls.secret.secretName`（Secret 键 `tls.crt` / `tls.key`）—— **支持引用外部签发/续期的证书** |
| 集群已有 | `cert-manager` 组件（平台可装）、`prometheus`（kps） |
| 平台侧 | 公信证书 → JVM 默认信任，**不需要**任何 truststore 配置 |

## 3. 方案对比

| 方案 | 做法 | 前提 | 无人值守 | 评价 |
|---|---|---|---|---|
| **A. cert-manager + ACME(DNS-01)** | 集群内 ClusterIssuer 自动签发/续期到 Secret，Harbor 引用该 Secret | 集群有 cert-manager；DNS 提供商有 cert-manager **webhook**（Aliyun/Tencent/Cloudflare/Route53 均有社区 webhook） | 是 | **推荐**（证书、Secret、续期全在集群内，可观测） |
| **B. acme.sh + 主机 cron** | 在跑 Harbor 的机器（或任意能改 DNS 的机器）上用 acme.sh 续期，钩子里更新 Secret | 只需 DNS API（acme.sh 支持上百家，比 webhook 覆盖广） | 是 | 备选（DNS 商没有 webhook 时用它） |
| C. 手工续 | 每 3 个月手工换一次 | 无 | 否 | 仅作兜底，必须配到期提醒 |

选择规则：**DNS 商有 cert-manager webhook → A；否则 → B**。两条都要做 §6 的 reload，否则续了等于没续。

### 3.1 为什么必须有 reload 步骤

cert-manager / acme.sh 更新的是 **Secret**。Secret 挂进 Pod 后，文件内容会被 kubelet 异步刷新（约 1 分钟），
但 **Harbor 的 nginx 只在启动时读证书**，不会自动 reload —— 不 reload 的话，Secret 是新的、对外仍是旧证书，
到期照样失败。

## 4. 方案 A：cert-manager + DNS-01

### 4.1 资源

```yaml
# 1) ClusterIssuer（DNS-01；webhook 名称按 DNS 商选）
apiVersion: cert-manager.io/v1
kind: ClusterIssuer
metadata: { name: le-dns01 }
spec:
  acme:
    server: https://acme-v02.api.letsencrypt.org/directory
    email: ops@example.com
    privateKeySecretRef: { name: le-dns01-account }
    solvers:
      - dns01: { <对应商的 webhook solver 配置> }

# 2) Certificate（放进 Harbor 命名空间，Secret 名与 chart 对齐）
apiVersion: cert-manager.io/v1
kind: Certificate
metadata: { name: harbor-tls, namespace: harbor }
spec:
  secretName: harbor-tls
  dnsNames: ["<Harbor 域名>"]
  issuerRef: { name: le-dns01, kind: ClusterIssuer }
  duration: 2160h      # 90 天
  renewBefore: 720h    # 提前 30 天续（= 证书有效期 1/3 处）
```

### 4.2 Harbor 引用

组件 values 里（平台 Harbor 组件的 values 深合并）覆盖：

```yaml
expose:
  tls:
    enabled: true
    certSource: secret
    secret:
      secretName: harbor-tls
```

> 首次切换时 `harbor-tls` 必须已存在（cert-manager 签发需要几分钟），否则 Harbor 起不来；
> 顺序：先建 Certificate → 等 Secret 就绪 → 再重放 Harbor 组件。

### 4.3 观测

- `kubectl -n harbor get certificate harbor-tls` 的 `RENEWAL TIME` / `NotAfter`；
- Prometheus 规则（kps 有 blackbox 时）：`probe_ssl_earliest_cert_expiry - time() < 14*24*3600` → 告警；
- cert-manager 自身事件（`kubectl -n harbor describe certificate`）失败原因通常就写在 `Events` 里。

## 5. 方案 B：acme.sh + 主机 cron

```bash
# 一次安装
acme.sh --register-account -m ops@example.com
acme.sh --issue --dns dns_xxx -d <Harbor 域名> --keylength ec-256

# 续期钩子：把证书写回 Secret，并触发 reload（见 §6）
acme.sh --install-cert -d <Harbor 域名> --ecc \
  --cert-file /etc/harbor/tls.crt --key-file /etc/harbor/tls.key \
  --reloadcmd "/opt/harbor/sync-cert.sh"
```

`sync-cert.sh`：`kubectl -n harbor create secret tls harbor-tls --cert=... --key=... --dry-run=client -o yaml | kubectl apply -f -`

acme.sh 自带 cron，`renewBefore` 默认到期前 30 天（`--days 30` 可调）。

## 6. reload 机制（A/B 共用）

一个 CronJob（`harbor` 命名空间，每 6 小时）：

1. 读 Secret 里 `tls.crt` 的指纹（`openssl x509 -noout -fingerprint -sha256`）；
2. 连 `https://<域名>:443` 取线上证书指纹（`openssl s_client -connect ... </dev/null | openssl x509 -noout -fingerprint -sha256`，
   或 `kubectl exec deploy/harbor-nginx -- cat /etc/cert/tls.crt` 读挂载文件）；
3. **两者不同才** `kubectl -n harbor rollout restart deploy/harbor-nginx`（滚动重启，Harbor 页面与推送短暂抖动，拉取不受影响）。

只在实际变化时重启，避免每 6 小时无谓滚动。

## 7. 失败模式与演练

| 失败 | 表现 | 处理 |
|---|---|---|
| 续期失败（DNS API 失效 / webhook 挂） | `Certificate` 事件报错；证书仍有效直到到期 | 30 天续期窗口内会反复重试；告警剩余 14 天时人工介入 |
| 续了但没 reload | 线上仍是旧证书，到期当天拉取突然全挂 | §6 的 CronJob 是必需项；验收里必须覆盖「续期后线上证书变了」 |
| 证书 SAN 与镜像地址主机名不一致 | `x509: certificate is valid for X, not Y` | 镜像地址与 `dnsNames` 必须用同一个域名 |
| 节点时钟偏差 | `not yet valid` | 节点 NTP（kubelet 侧，非本方案范围） |

**演练方法**（上线前做一次）：用 staging ACME 或手工签一张 **1 天有效期**的证书，完成
「签发 → 挂进 Harbor → 拉一次镜像 → 改 Secret 模拟续期 → 确认 CronJob reload 后线上证书指纹变化 → 再拉一次成功」。
把结果写进 `docs/development/image-registry.md`。

## 8. 验收清单

- [ ] `Certificate` 的 `READY=True`，`SECRET=harbor-tls`
- [ ] `openssl s_client` 看到的线上证书与 Secret 指纹一致
- [ ] 节点 `crictl pull <域名>/library/hello-world:latest` 成功（公信证书，节点无配置）
- [ ] 按 §7 演练：换证 → CronJob reload → 线上指纹变化 → 拉取仍成功
- [ ] 告警：证书剩余 < 14 天触发（或在组件页显示剩余天数）

## 9. 工作量

| 项 | 估时 |
|---|---|
| A：ClusterIssuer + Certificate + webhook 凭据 | 0.5 天（取决于 DNS 商） |
| B：acme.sh + 钩子脚本 | 0.5 天 |
| reload CronJob + 演练 | 0.5 天 |
| 告警规则 / 文档 | 0.5 天 |

## 10. 不做 / 边界

- **不**把续期做进平台代码：证书生命周期属于集群运维，平台只在「组件状态」里展示证书剩余天数（可选增强，不在本方案内）。
- **不**用 HostPath 把证书塞进节点：那是把运维负担换了个位置，且节点是 cattle 不是 pet。
- 平台自身镜像若也走 Harbor：k8s-server 重启时若证书恰好过期会起不来 —— 所以告警阈值（14 天）必须早于任何人的反应时间。
