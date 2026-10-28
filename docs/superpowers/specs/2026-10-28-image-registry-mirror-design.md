# 镜像来源纳管（平台 Harbor / 官方）设计方案

> 2026-10-28 立项。目标读者：平台后端/前端、集群运维。
> 关联：`charts/components/*`、`charts/middleware/*`、`ComponentInstallService`、`MiddlewareAdapter`。

## 1. 背景与目标

集群组件与中间件现在**全部从公网 registry 拉镜像**（docker.io、quay.io、ghcr.io、registry.k8s.io、
docker.elastic.co、gcr.io 六个上游，约 50 个仓库），内网/断网环境整条链路装不起来。

目标：镜像先进平台的 Harbor（`platform` 项目），组件与中间件的**镜像来源可选**：

- **平台 Harbor（默认）** —— 指向 `harbor.<域名>/platform/...`；
- **官方** —— 保持现在的行为，指向上游。

## 2. 现状事实（2026-10-28 实测）

| 事实 | 值 |
|---|---|
| 上游 registry | docker.io(11)、quay.io(18)、ghcr.io(8)、registry.k8s.io(2)、docker.elastic.co(2)、gcr.io(1)（按出现次数，去 tag） |
| 镜像来源（代码侧） | ① 组件/中间件 Operator chart 的 values（`image` / `repository` / `imageName`…）；② 中间件 CR 里由适配器显式写的镜像（ES、Redis、Kafka、MySQL、Milvus、RabbitMQ、PG）；③ 边车（exporter、kubectl、initContainer 复用主镜像） |
| 已装 Harbor | 组件 `harbor`（chart 1.19.4），默认 nodePort 暴露，租户镜像项目 `t-<sa>`；平台自身还没有 `platform` 项目 |
| 镜像清单 | 平台是唯一知道「装了哪些 chart、会拉哪些镜像」的一侧（chart 内置在 k8s-server 里） |

## 2.5 本批范围（2026-10-28 追加）

用户把三件事绑在一起做，缺一件都不成：

| # | 事项 | 落点 |
|---|---|---|
| 1 | **Harbor 安装支持可选 HTTPS**（现状是写死 HTTP）：HTTPS 时**域名固定**、证书由平台后端写进 Secret | 组件 `harbor` 的安装参数与 values |
| 2 | **镜像仓库功能调整**：浏览仓库/凭据/robot 那条链路要按实际 scheme 访问（现在 scheme 是写死的 `http://`） | `HarborRegistryService` / `HarborDialect` / `RegistryTargetResolver` |
| 3 | **镜像同步**：把组件与中间件用到的镜像搬进 `platform` 项目（Harbor 复制规则 + 首次全量同步 + 清单核对） | 运维 + 平台的镜像清单端点 |

### 2.5.1 Harbor 自身的 HTTPS（事项 1）

- 表单新增「传输方式」：`HTTP`（默认，保持现有部署不变）/ `HTTPS`。
- 选 HTTPS 时：
  - `host` **必须是域名**（证书只对域名有意义；IP 直接拒）；
  - 证书由**平台后端处理**：表单里粘贴证书链（PEM）与私钥（PEM），platform-api 交给 k8s-server 写成
    `harbor/harbor-tls`（键 `tls.crt` / `tls.key`），chart 用 `expose.tls.certSource=secret` 引用；
    **证书与私钥不进 values 快照、不回显**（与外部库口令同一条规矩：留空 = 沿用集群里已有的 Secret）；
  - `expose.tls.enabled=true` + `expose.nodePort.ports` 增加 `https`（默认 30443）；
  - `externalURL` 必须是 `https://<域名>` —— Harbor 的 token 服务用它回复 docker 客户端，写错就是
    「登录成功但 push/pull 失败」。
- 证书续期不在本批（见 [2026-10-28-harbor-tls-renewal-design.md](2026-10-28-harbor-tls-renewal-design.md)）。

### 2.5.2 镜像仓库功能的 scheme（事项 2）

现状：`HarborRegistryService` 拼 Harbor API 地址是写死的 `"http://" + host`，而 `HarborDialect` 对无
scheme 端点默认补 `https://` —— 两套口径。改成**一个来源**：Harbor 安装记录里的 `externalURL`（含 scheme），
`harborHost()` 直接返回它；`HarborDialect` 不再自己猜。

### 2.5.3 镜像同步（事项 3）

沿用已定方案（Harbor 侧复制规则），本批补三件具体东西：

1. 6 条复制规则（每个上游一条，destination namespace = 上游主机名），文档给出逐条配置字段；
2. 首次全量同步的触发方式与核对方法（按平台的镜像清单逐个确认 `manifest` 存在）；
3. 平台「镜像清单」页上，每条镜像给出一键复制的 `docker pull/push` 命令，便于手工补漏。

## 3. 已定决策

1. **命名**：保留上游路径 —— `harbor.x/platform/<上游主机>/<仓库路径>:<tag>`。
2. **谁搬**：由 **Harbor 侧同步**（replication rule），平台只负责改写指向 + 出清单。
3. **默认值**：组件/中间件表单默认「平台 Harbor」，仍可切「官方」。
4. **范围**：集群组件 + 中间件（含 Operator、工作负载、边车）+ **平台自身镜像**（platform-api /
   k8s-server / platform-web / platform-auth）；Harbor 自身作为例外（见 §10）。

## 4. 方案总览

```
                 ┌──────────────── 平台（platform-api） ────────────────┐
  镜像清单(只读) │ 渲染 values / 拼 CR 时改写镜像地址 + 传 imagePullSecret │
  /components/images └───────────────┬─────────────────────────────────┘
                                     │ helm values / CR spec
                    ┌────────────────▼────────────────┐
                    │  k8s-server（在集群内执行）       │
                    │  建 imagePullSecret、patch default SA │
                    └────────────────┬────────────────┘
                                     │ 拉镜像
                    ┌────────────────▼────────────────┐
                    │ Harbor  project=platform        │◀── 复制规则（6 条，Harbor 自己拉）
                    └────────────────┬────────────────┘
                                     │
         docker.io / quay.io / ghcr.io / registry.k8s.io / docker.elastic.co / gcr.io
```

平台不做推送：Harbor 配 6 条复制规则（每个上游一条，目标项目 `platform`），镜像是否已搬运由 Harbor 负责；
平台只在渲染时把地址换过去，并保证拉取凭据就位。

## 5. 命名与映射规则

```
上游：docker.elastic.co/elasticsearch/elasticsearch:9.5.0
镜像：harbor.x/platform/docker.elastic.co/elasticsearch/elasticsearch:9.5.0

上游：quay.io/opstree/redis:v7.0.15
镜像：harbor.x/platform/quay.io/opstree/redis:v7.0.15
```

规则（纯函数，单测覆盖）：

1. **归一化**：没有主机名的引用按 docker.io 补全（`redis:7` → `docker.io/library/redis:7`，
   `prometheus/prometheus` → `docker.io/prometheus/prometheus`）。
2. **替换主机**：`<上游主机>/<路径>` → `<harborHost>/<project>/<上游主机>/<路径>`，tag/digest 原样保留。
3. **不动**：已经是 `harborHost/project/...` 的地址（幂等）；本地回环/`oci:` 之类的非常规引用不改写。
4. **Harbor 侧**：每条复制规则把「Destination namespace」设成**上游主机名**（`docker.elastic.co`、
   `quay.io`…），这样拉下来的路径与规则 2 完全一致；不同上游的同名仓库（`redis`）不会互相覆盖。

> ⚠️ 待验证（实施第一步）：Harbor 复制规则是否允许 destination namespace 带点号。若不允许，
> 退化为主机名把 `.` 换成 `-`（`docker-elastic-co/...`），映射函数同步改——只影响一处常量和它的单测。

## 6. 配置模型

| 层 | 位置 | 说明 |
|---|---|---|
| 平台默认 | `application.yaml`：`platform.image-registry.*` / `platform.harbor.scheme` | `host`（域名[:端口]，**不带 scheme**）、`project`（默认 `platform`）、`enabled`；Harbor API 的 scheme 默认 `https` |
| 按集群覆盖 | 新列 `k8s_cluster.image_registry_host` / `image_registry_project`（迁移 `V2026_10_28_1`） | 业务集群连不到平台 Harbor 时各自指自己的；空 = 用平台默认 |
| 每次安装的来源 | 组件/中间件请求里的 `imageSource = PLATFORM \| OFFICIAL` | 默认 `PLATFORM`；落进操作快照，EDIT 重放可切 |

解析顺序（与对象存储 `object_storage_url` 同款思路）：请求里的来源 → 集群列 → 平台默认。
**没配 host 或关了开关时，「平台 Harbor」选项置灰，默认回退官方**，不让用户选一个注定拉不到的来源。

## 7. 改写点

### 7.1 chart values（组件 + 中间件 Operator，覆盖绝大多数镜像）

在 `ComponentInstallService` 解析完 values、落库/下发之前，对 values 做一次**受控递归改写**：

- 只认键名：`image`、`repository`、`imageName`、`imageRepository`、`registry`（大小写不敏感）；
- 只改**像镜像引用**的值：含 `/` 或 `:`，不含空格/URL scheme/文件路径，且不命中白名单（`imagePullPolicy`、
  `tag`、`digest` 这类键本来就不在键名列表里）；
- `registry` 键单独处理：值是主机名时换成 `harborHost/project/<主机>`（charts 里 `global.imageRegistry`
  的那套写法）；
- 命中写一条 debug 日志，便于核对「这次到底换了哪些」。

**护栏测试**（关键）：把内置的 10 个组件 recipe 与 7 个中间件 Operator chart 的默认 values 全跑一遍，
断言「改写后不再出现任何上游主机名」，同时断言「识别出的镜像数 ≥ 每个 chart 的已知下限」——
防止递归漏掉某个嵌套结构（charts 的 image 字段位置并不统一）。

### 7.2 中间件 CR（适配器显式写的镜像）

适配器里出现的镜像（ES 主镜像、Redis、Kafka、MySQL、Milvus、RabbitMQ、PG 的 CR 镜像，以及 exporter 边车）
逐处改成经 `ImageRewriter.rewrite(...)`；来源通过请求 DTO 的新字段 `imageRegistry`（host/project/pullSecret）
传进 k8s-server（platform-api 已经持有集群配置，不在 k8s-server 里再读库）。

Redis 的 `quay.io/opstree/*`、ES 的 `docker.elastic.co/*`、exporter 的 `quay.io/prometheuscommunity/*`
都在这一步覆盖。

### 7.3 不动的东西

- Helm chart 自带的 CRD/Job 里的镜像（calico CRD chart 不含镜像）；
- `helm --wait` 依赖的钩子镜像（同属 chart values，走 7.1 一起改）；
- initContainer 复用主镜像的情况（ES 的 IK init 用 ES 主镜像）—— 7.1/7.2 改主镜像时一并生效。

## 8. 拉取凭据

Harbor 的 `platform` 项目默认私有。做法：

1. k8s-server 新端点 `POST /components/image-pull-secret`：按平台配置的 robot 账号生成
   `<ns>/platform-harbor-pull`（`kubernetes.io/dockerconfigjson`），幂等 SSA；
2. 同时 **patch 该命名空间的 `default` ServiceAccount**，把 secret 加进 `imagePullSecrets` ——
   这样所有 chart 渲出来的 Pod 自动继承，不用逐个 chart 改 values（10+ 个 chart 各自写法不同，逐改会漏）；
3. 调用时机：组件安装/升级前、中间件实例创建/重放前，对涉及的命名空间各调一次；
4. 目标命名空间集合：组件 release 的 namespace（含 `platform-middleware-system`、`monitor`、
   `platform-storage`、`istio-system`…）+ 中间件实例所在租户 namespace。
5. 平台自身镜像：由部署清单自带 `imagePullSecrets`（见 §9）。

## 9. 平台自身镜像

platform-api / k8s-server / platform-web / platform-auth 的 Deployment 不在本仓库里（用户自己的部署清单），
平台能做的是：

- 在「镜像清单」里把这 4 个镜像一并列出（含默认 tag）；
- 文档给出把镜像推到 `platform/<上游主机>/<路径>:<tag>` 的命令（与 §5 规则一致）；
- 部署清单的 `imagePullSecrets` 由运维在发布时设置 —— 平台不接管自身的滚动。

> 顺序提醒：k8s-server 是「写 imagePullSecret」的执行者。若它自己也从 Harbor 拉，**必须先把这 4 个镜像推上去**
> 再切清单，否则新 Pod 起不来、连补 secret 的机会都没有。

## 10. 例外与风险

| 风险/例外 | 处理 |
|---|---|
| **Harbor 自身 11 个镜像**（harbor-core、registry-photon、trivy…） | 不能从 Harbor 拉（先有鸡还是先有蛋）。安装 Harbor 组件时**强制官方**（即使表单选了平台 Harbor），UI 置灰并显示原因；Harbor 的「外部 PG/Redis」同理 |
| 镜像还没同步到 Harbor | 拉取失败发生在 Pod 启动（`ImagePullBackOff`）。平台在安装前**不探测**（探测 = 每装一次打一轮 registry，代价大），改为：错误信息里明确提示「检查 Harbor 是否已同步该镜像」+ 镜像清单页提供逐个复制地址 |
| digest 引用（`@sha256:`） | 保留 digest：`<harbor>/platform/<host>/<path>@sha256:...`；同步时也必须按 digest 复制（Harbor 复制规则支持 tag+digest） |
| 上游 chart 里写死了非标准镜像（如 `alpine/k8s` 这类 docker.io 简写） | 归一化到 `docker.io/library/...` 或 `docker.io/alpine/...`；护栏测试兜底 |
| Harbor 的 replication 只拉「用过一次」的镜像 | 平台提供**镜像清单**（§11）供运维一次性触发同步/核对，避免装的时候才发现缺 |
| **TLS 证书到期**（免费证书 3 个月） | 公信证书下节点无需任何配置；但证书一过期，**所有拉取**都会 `x509: certificate has expired`。续期属运维自动化，见 [2026-10-28-harbor-tls-renewal-design.md](2026-10-28-harbor-tls-renewal-design.md)，不随本批实施 |
| 多集群 | 按集群配置（§6）；镜像清单按集群 + 组件维度给出 |

## 11. 镜像清单（只读端点 + 页面）

- `GET /components/images?clusterId=...` → `[{component, release, chartVersion, upstream, mirrored, usedBy}]`
- 数据来源：内置 chart 的默认 values 经 §7.1 的扫描器（同一份代码，避免清单与实现漂移）+ 中间件适配器的镜像常量；
- 「usedBy」= 已安装的组件/中间件实例（从组件状态与实例表反查），让运维知道「哪些是实际在用的」；
- 前端放在「集群组件」页的一个抽屉/页签，支持一键复制「镜像 → Harbor 地址」。

## 12. 回退与切换

- 每次安装/创建的来源写进操作快照（`imageSource`），EDIT 时回填；切回「官方」= 不改写，重放一次即生效；
- 不改写时行为与今天完全一致，**方案可随时整体关闭**（`platform.image-registry.enabled=false`）。

## 13. 实施拆分（供写计划时参考）

| # | 内容 | 验收 |
|---|---|---|
| 1 | `V2026_10_28_1`：`k8s_cluster` 两列 + 配置项 | 重放后列存在；配置为空时行为不变 |
| 2 | `ImageReference`（归一化/改写）+ `ImageRewriter`（values 递归）+ 单测 | 50 个已知引用全部按规则改写；幂等；白名单不误伤 |
| 3 | 护栏测试：10 个组件 + 7 个中间件 Operator 的默认 values 全量扫描 | 改写后无上游主机名残留 |
| 4 | 中间件适配器镜像点位改造（7 家 + exporter）+ DTO 传 `imageRegistry` | 创建出的 CR 里镜像全在 Harbor |
| 5 | k8s-server `image-pull-secret` 端点 + default SA patch | 新命名空间的 Pod 无需额外配置即可拉取 |
| 6 | 前端：组件/中间件表单「镜像来源」+ 集群设置里的 Harbor 地址 | 默认平台、可切官方；未配置时置灰 |
| 7 | 镜像清单端点 + 页面 + 文档 | 清单与实际渲染一致（抽查 3 个组件） |
| 8 | Harbor 侧 6 条复制规则 + 首次全量同步（运维执行） | 断网模拟验证：关掉出网后重新装一个组件成功 |

预估：后端 1.5 天、前端 0.5 天、Harbor 配置与首同步 0.5 天、真集群验证 0.5 天。

## 14. 待确认

1. Harbor 复制规则的 destination namespace 能否带点号（不能就把主机名转成 `-` 形式）。
2. 平台 Harbor 的地址与 project 名（默认 `platform`）——需要你给我实际域名。
3. `platform` 项目是否走 robot 账号拉取（还是先开成公开项目，省掉 imagePullSecret 一条链路）。
