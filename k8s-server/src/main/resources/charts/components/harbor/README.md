# Harbor（镜像仓库，集群组件）

> **组件级 README（跨版本）**：平台怎么装、约定是什么、踩过哪些坑、加新版本要改哪里。
> 当前 chart 版本的事实见 `1.19.4/README.md`。

## 1. 概览

| 项 | 值 |
|---|---|
| 组件 code | `harbor` |
| 画像 | BUSINESS / MANAGEMENT |
| release / namespace | `platform-harbor` / `harbor` |
| 探测判据 | `harbor` Deployment `harbor-core`（镜像含 `goharbor/harbor-core`） |
| 平台功能 | `container-registry` |
| 暴露 | `istio-system/external-gateway` 的 HTTPRoute（HTTP） |
| 管理口令 | Secret `platform-harbor-secrets`（`HARBOR_ADMIN_PASSWORD` / `secretKey`） |
| 前置组件 | `istio`（route 必须有 Gateway） |

## 2. 装之前必须确认（DNS）

`host` 必须**同时**能被以下三边解析：

1. 集群内 Pod（流水线的 push/pull）；
2. 平台所在机器（仓库浏览与租户项目同步）；
3. 外部 docker/podman 客户端。

Harbor 的 token 服务按 `externalURL` 返回地址；三边不一致的典型症状是
「登录成功但 push 到错误地址 / 解析失败」。

## 3. 契约 values（🔒）

```yaml
fullnameOverride: harbor
expose:
  type: route
  tls: { enabled: false }
  route:
    parentRefs: [{ name: external-gateway, namespace: istio-system }]
    hosts: ["${harbor.host}"]
externalURL: "http://${harbor.host}"
existingSecretAdminPassword: platform-harbor-secrets
existingSecretSecretKey: platform-harbor-secrets
persistence: { enabled: true, resourcePolicy: keep }
database: { type: internal }
redis: { type: internal }
```

- `fullnameOverride`：探测认 `harbor-core`，不钉名字 release 名一变就永远「未安装」。
- `resourcePolicy: keep`：卸载 release 不删镜像与数据库 PVC。
- `database/redis: internal`：内网单集群，本期不接外部中间件。

## 4. 安装参数（表单，⚙️）

| 字段 | 默认 | 说明 |
|---|---|---|
| `host` | 无（必填） | 不带 scheme/path；写进 `externalURL` 与 route hosts |
| 5 块 PVC 的 StorageClass / 容量 | 空 / 20Gi、1Gi、5Gi、1Gi、5Gi | StorageClass 空 = 跟随集群默认 |
| `trivy.enabled` | `false` | 内网拉不到漏洞库，默认不装 |

## 5. 装完之后

```bash
kubectl -n harbor get deploy,sts,svc
kubectl -n harbor get httproute
kubectl -n harbor get secret platform-harbor-secrets -o jsonpath='{.data}' | jq 'keys'
```

- HTTPRoute `Accepted=True` 且 `AttachedRoutes=1`；
- 平台「集群组件」页 Harbor 行 →「镜像项目」能看到每租户的 project 与凭据状态，
  装完 Harbor 与新建租户时平台会自动同步（失败可在该面板重试/重建）。

## 6. 管理口令（不可重建自愈）

口令由 k8s-server 生成，只在首次初始化时写进 Harbor 库；`secretKey` 还用于加密库里的字段。
**Secret 丢失且 `harbor-core` 已存在时，平台会拒绝安装/升级** —— 重新生成的口令与库里的哈希
不一致，同步必然 401。此时需人工按 Harbor 官方方式重置管理员口令 / secretKey 后再操作。

## 7. 已知限制

- 资源占用较大（core/registry/db/redis/jobservice 合计约 1~2GB 内存起）；
- 镜像较大，慢网络下 `helm --timeout 10m` 可能不够，失败后重试（upgrade）；
- 本期不做：租户登录 Harbor、项目配额/保留策略/复制/签名、外部 DB、TLS 终止。

## 8. 加一个新版本怎么做

1. `helm pull harbor --repo https://helm.goharbor.io --version <x.y.z> --untar` → 入库
   `harbor/<x.y.z>/chart/` + `provenance.yaml` + `<x.y.z>/README.md`；
2. 核对 values 字段名（本期用到：`expose.type/route/tls`、`externalURL`、
   `existingSecretAdminPassword`、`existingSecretSecretKey`、
   `persistence.persistentVolumeClaim.<registry|jobservice.jobLog|database|redis|trivy>.{storageClass,size}`、
   `trivy.enabled`、各组件 `resources`）；
3. 改 `ComponentRecipeCatalog.HARBOR_RELEASES` 的 `chartVersion` 与 `withAppVersion`（新→旧）；
4. 跑 `ComponentRecipeCatalogTest`（会与 `Chart.yaml` 交叉校验）；
5. 真集群装一次，按 §5 验证；
6. 重建 k8s-server（chart 入镜像）+ platform-api。
