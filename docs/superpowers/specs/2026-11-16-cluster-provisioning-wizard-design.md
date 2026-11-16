# 集群安装向导（kubespray 配置生成器）设计

> 2026-11-16 讨论定稿。平台里点「新建集群」→ 选参数 → 生成一套可直接执行的 kubespray 部署包（在线/离线）。
> 纯前端：不落库、不执行、不新增后端端点。

## 1. 为什么是"生成器"而不是"执行器"

- 新集群在纳管之前，`k8s-server` 摸不到它；要执行只能 SSH / agent，引入新的凭据管理与安全边界。
- kubespray 已经覆盖安装逻辑（HA、etcd、离线、多 OS、多运行时），平台的价值是**把参数收敛成一份正确的配置**，
  而不是重写安装器。
- 同类产品的做法一致：Rancher 生成节点注册命令、KubeKey 生成 `config-sample.yaml`、Sealos 生成集群镜像——
  管理面都不直接装节点。

## 2. 范围

**做**

- 一个向导页（`platform-web`），四步：基础 / 节点 / 网络与入口 / 介质
- 生成 `inventory/<集群名>/`（`hosts.yaml` + `group_vars/**` + `README.md`），离线时含 `offline.yml`
- 组合校验：非法组合禁用并给原因；拓扑预览
- 在线 / 离线两种介质模式

**不做**

- 不执行、不 SSH、不落库、不加后端端点、不加权限行、不加 SQL 迁移
- 不做集群生命周期（扩缩容 / 升级 / 删除）
- 不管理外部 etcd 本身（只填连接信息）
- v1 不做 IPv6 / 双栈、不做 zip 打包（逐文件下载）、不做节点免密检测

**反目标**

- 生成一份"看起来对但跑不起来"的配置——每个选项必须落到 kubespray 的真实变量（由类型约束，见 §8）
- 把任何现网测试集群的形态写成默认值；默认值只取通用最佳实践

## 3. 参数矩阵

引擎固定 **kubespray v2.32.0**（2026-09 最新稳定；实现选型，不作为用户选项）。其余全部可选：

| 维度 | 可选值 | 默认 | 落到 |
|---|---|---|---|
| 操作系统 | Ubuntu 22.04 / 24.04、Debian 12 / 13、Rocky 9 / 10、Alma 9 / 10、CentOS Stream 9 / 10 | Rocky 9 | 支持矩阵 + 离线仓库类型（rpm/deb） |
| 架构 | amd64 / arm64（**全集群统一**） | amd64 | `image_arch`（下载产物架构，不是逐节点开关） |
| K8s 版本 | 由 kubespray v2.32.0 的 checksums 生成，不手写范围 | 该版本默认（1.36） | `kube_version` |
| 容器运行时 | containerd / CRI-O | containerd | `container_manager` |
| 控制面 | 1 / 3 / 5 台，节点逐行勾选 | 3 | inventory `kube_control_plane` |
| etcd | 同机（控制面即 etcd）/ 专用 3 或 5 台 / 外部托管（填 endpoints） | 同机 | `etcd` 组 + `etcd_deployment_type` |
| API 入口 | kube-vip ARP / kube-vip BGP / 外部 LB / 单 master 直连 | kube-vip ARP | `kube_vip_*`、`loadbalancer_apiserver` |
| CNI | 平台管理（不装）/ kubespray 装 Calico / Cilium / flannel | 平台管理 | `kube_network_plugin` |
| 网络 | Pod CIDR、Service CIDR、clusterDomain、kube-proxy 模式、strictARP、节点网段（可选） | `10.233.64.0/18`、`10.233.0.0/18`、`cluster.local`、`ipvs` | `kube_pods_subnet`、`kube_service_addresses`、`cluster_name`、`kube_proxy_mode`、`kube_proxy_strict_arp` |
| 镜像源（在线） | 官方默认 / 自定义 registry / 镜像加速地址 / 内网 Harbor | 官方 | `kube_image_repo`、`gcr_image_repo`、`docker_image_repo`、`quay_image_repo` |
| 离线 | 静态文件仓库地址、内网 registry | — | `files_repo`、`registry_host`、`*_download_url`、`download_run_once` |
| 代理 | http_proxy / https_proxy / no_proxy | 空 | 节点环境变量配置 |
| 节点 | IP、SSH 端口、用户名、hostname、角色（可多选） | — | inventory `hosts` |

说明：

- **OS / 架构是集群级**：不按节点混搭（kubespray 虽能混 OS，但离线仓库与支持矩阵会翻倍，v1 不做）。
  节点行只填 IP / SSH / hostname / 角色。
- **角色可重叠**：control-plane 可以同时是 worker（单机小集群常见）；专用 etcd **不能**与控制面或 worker 重叠
  （kubespray 文档要求 `kube_node` 与 `etcd` 组不相交）。至少要有一个控制面、至少有一个 `kube_node` 成员
  （控制面兼 worker 时控制面节点同时进 `kube_node`）。
- **同机 etcd** 用 `etcd_deployment_type: host` + 不单列 `etcd` 组（kubespray 默认把 etcd 放在控制面上），
  不选 `kubeadm`（kubespray 标 experimental）。
- **专用 etcd** 单列 `etcd` 组、`etcd_deployment_type: host`。
- **外部托管** 只收 endpoints + 可选证书路径，标「实验」：kubespray 目前没有干净的"跳过 etcd 安装"开关
  （[issue #9587](https://github.com/kubernetes-sigs/kubespray/issues/9587)），生成的是变量覆盖，需现场自验。
- **单 master** 时 API 入口只允许"直连"，不允许 VIP。
- **CNI = 平台管理** 时 `kube_network_plugin: cni`（不装 CNI）。此时 **Pod CIDR 必填**，且必须与之后在平台
  组件页安装 Calico 时填的 `calicoNetwork.ipPools[].cidr` **一字不差**（tigera-operator README：写错立即断网）。
  生成的 README 里会写明"装 Calico 时 Pod CIDR 填这个值"。选非 Calico 的 CNI 时提示平台的 Calico 地址池 / BGP
  相关能力不可用。

## 4. 页面

- 路由：`/clusters/new`，**`meta.public = true`，免登录**。向导只做本地生成（不执行、不落库、不发请求），
  放在平台外壳之外（`layouts/PublicLayout.vue`）—— MainLayout 依赖登录态与权限引导（`perm.bootstrap()`），
  公共路由放行时它不会跑，挂上去只会得到空壳侧栏和一堆 401。不占用 `pageCodes`，也不新增权限码。
- 入口：集群列表页现有「新增集群」按钮（打开 kubeconfig 登记弹窗）改名为「接入集群」，
  旁边新增「新建集群」按钮 → 向导页
- 布局：左侧四步表单，右侧 sticky 汇总（拓扑预览 + 校验结果 + 产物下载）
- 组件：
  - `views/ClusterProvisionView.vue` 页面壳 / 步骤编排
  - `components/cluster/provision/NodeTable.vue` 节点表与角色
  - `components/cluster/provision/TopologyPreview.vue` 纯 SVG 拓扑（不用 vue-flow，图很小）
  - `components/cluster/provision/ArtifactPanel.vue` 产物预览、复制、逐文件下载
  - `composables/useProvisionPlan.ts` 纯函数：表单 → 校验结果 + 文件内容
  - `data/kubesprayVars.ts` vendored 的变量名单与支持矩阵（见 §8）
- 草稿：`localStorage` 保存当前表单（可选，v1 可以不做）
- 所有产物用 `Blob` 下载，不引入新依赖

### 4.1 API 入口字段

| 入口 | 字段 | 变量 |
|---|---|---|
| kube-vip ARP | VIP 地址（必填）、网卡（可选） | `kube_vip_enabled`、`kube_vip_address`、`kube_vip_interface` |
| kube-vip BGP | VIP、本地 AS、peer 地址、router-id（可选） | `kube_vip_bgp_enabled`、`kube_vip_local_as`、`kube_vip_bgppeers` |
| 外部 LB | 地址、端口（默认 6443）、域名（可选） | `loadbalancer_apiserver`、`apiserver_loadbalancer_domain_name` |
| 单 master 直连 | 无 | — |

`kube_proxy_mode: ipvs` + ARP 时自动 `kube_proxy_strict_arp: true`。

## 5. 产物

```
inventory/<集群名>/
├── hosts.yaml
├── group_vars/
│   ├── k8s_cluster/k8s-cluster.yml   # 版本 / 运行时 / CNI / 网络 / kube-proxy
│   ├── all/all.yml                   # VIP / 外部 LB / 代理
│   ├── all/etcd.yml                  # etcd 模式（同机时可省略）
│   └── all/offline.yml               # 仅离线
└── README.md                         # 步骤、命令、前置检查清单
```

下载后在 kubespray v2.32.0 checkout 根目录执行（`group_vars` 按 Ansible 规则随 inventory 目录加载，
在线 / 离线命令相同；离线只多"先准备 files_repo / registry"这一步）：

```bash
cp -r <下载目录>/<集群名> inventory/
ansible-playbook -i inventory/<集群名>/hosts.yaml cluster.yml
```

README 同时链到 `docs/development/cluster-prerequisites.md`，提醒纳管后要做的集群侧前置。

## 6. 校验规则（前端硬校验）

| 规则 | 处理 |
|---|---|
| 控制面数量 ∈ {1,3,5}；专用 etcd 数量 ∈ {3,5} | 其他值禁用提交 |
| 至少 1 个控制面；至少 1 个 `kube_node` 成员（控制面兼 worker 也算） | 未满足禁用提交 |
| 专用 etcd 节点不能与控制面或 worker 重叠 | 冲突行标红 |
| 控制面 > 1 时必须有 API 入口（VIP / 外部 LB） | 未填禁用提交 |
| 同机 etcd 时 etcd 数量跟随控制面 | 只读展示 |
| 节点 IP 不得重复；VIP 不得与节点 IP 相同；Pod / Service / 节点网段互不重叠 | 标红 |
| OS / 架构 / K8s 版本组合必须在支持矩阵内 | 不支持的组合不出现在下拉 |
| CNI = 平台管理且未填 Pod CIDR | 禁用提交（见 §3 的一致性要求） |
| 选外部 etcd | 顶部黄色提示"实验，需自验 kubespray 是否跳过 etcd 任务" |
| kube-vip ARP + ipvs | 自动置 `kube_proxy_strict_arp: true` |
| no_proxy | 自动补 Pod / Service CIDR、节点网段（若填）、`127.0.0.1`、`localhost` |

节点网段是**可选**输入：填了才做重叠校验和 no_proxy 补全；不填则跳过这两项，并在 README 里提示手工检查。

## 7. 边界与已知问题

- **外部 etcd** 不是一等支持（见 §3），页面标实验，不承诺"开箱即用"。
- **支持矩阵是静态数据**：kubespray 升级（v2.32 → 后续）时，OS / 版本 / 变量名要同步维护。
- **生成物不替代现场检查**：磁盘、内核模块、网络（VIP 是否可用 ARP、BGP peer 是否可达）仍需按 README 核对。
- **离线包不在页面里生成**：页面只生成 `offline.yml` 和打包命令，实际下载/拷贝由现场执行。
- **syntax-check 不校验变量名**：`ansible-playbook --syntax-check` 只解析 playbook，拼错的 `group_vars` 键会被
  静默忽略、落到 kubespray 默认值。所以正确性靠 §8 的类型约束保证，syntax-check 只是补充。

## 8. 正确性保障（关键）

不引入测试框架、不新增依赖。做法：

1. `data/kubesprayVars.ts` vendored 一份 **kubespray v2.32.0 的变量名单**（来自该版本
   `roles/kubespray_defaults/defaults/main/main.yml` 与 `inventory/sample/group_vars/**`），
   以及 OS / K8s 版本支持矩阵。
2. 生成器返回一个**键名被该名单约束的对象**（TS 类型 + `satisfies`），不动态拼键名——
   拼错或用了不存在的变量，`vue-tsc` 直接报错。
3. 生成的文件内容由这个对象序列化，不在模板字符串里手写键名。
4. 人工验收时把生成目录拷进 v2.32.0 checkout 跑一次 `syntax-check`，并抽查键名与 sample 一致。

## 9. 验收

- `npm run build` / `vue-tsc` 通过；无新增依赖
- `data/kubesprayVars.ts` 覆盖生成器用到的全部键；类型检查通过
- 四种典型组合各生成一份样例，人工在 kubespray v2.32.0 checkout 上跑 `ansible-playbook --syntax-check`：
  1. 单 master（兼 worker）+ 同机 etcd + 直连
  2. 3 master + 同机 etcd + kube-vip ARP
  3. 3 master + 专用 etcd + 外部 LB
  4. 3 master + 外部 etcd（实验）+ 离线
- 非法组合全部被禁用且给原因；页面无任何后端请求

## 10. 留给实施

- 支持矩阵与预置镜像源的具体数据内容（以 v2.32.0 为准）
- 是否保留 localStorage 草稿
- 产物是否需要一次性下载（zip）——v1 不做
- 拓扑图的节点分组与连线细节

## 11. 实施验收记录（2026-11-16）

- 单测：**21/21**（`validate` 12 + `generate` 9）
- 构建：`npm run build` 通过；无新增依赖
- 整页验证（假 token + 真实路由）：四步表单、拓扑、产物面板渲染；填参后 4 个文件；
  改成 2 台控制面立即报错；切离线多出 `offline.yml`；暗/亮主题都检查；切页期间零 `/api/**` 请求
- 四组样例已生成并人工核对（工作区 scratch，不提交）：
  1. 单 master 兼 worker + 同机 etcd + 直连 → 3 个文件（无 all.yml）
  2. 3 master + 同机 etcd + kube-vip ARP → 4 个文件
  3. 3 master + 专用 etcd + 外部 LB → 5 个文件
  4. 3 master + kube-vip ARP + 外部 etcd（实验）+ 离线 → 6 个文件（含 warning）
- **样例 4 修正**：3 master 必须有 API 入口（§6），原 §9 的写法缺这一项，补了 kube-vip ARP。
- **未执行 `ansible-playbook --syntax-check`**：本机没有 kubespray 检出，也没有 ansible；
  且 syntax-check 不校验 `group_vars` 键名。键名正确性由 `KubesprayVars` 类型约束 + 单测保证，
  仍需在有 ansible 的控制机上补跑一次真校验。

### 11.1 复审修复（2026-11-16）

独立复审对照 kubespray v2.32.0 源码发现 3 个必须修复项，已在本分支修复并补测试：

- **C1 `kube_version` 不能带 `v`**：v2.32 的 `kube_version` 直接取 checksums 键（`1.36.4`），
  kubeadm 模板自己拼 `v`。版本表改为裸版本，UI 展示时加 `v`；新增值级测试。
- **C2 离线：`files_repo` 在 v2.32 没有消费方**：改成生成 `github_url` / `dl_k8s_io_url` /
  `storage_googleapis_url` / `get_helm_url` 四个覆盖，并补 `github_image_repo`（kube-vip 镜像）；
  `files_repo` 仅保留为可读根地址。OS 包仓库仍需现场配置，README 已写明。
- **C3 etcd 三种模式没有页面入口**：在「节点」步补 etcd 模式单选（同机 / 专用 / 外部）与外部 endpoints。
- I1 在线镜像源落到四个 `*_image_repo` + `github_image_repo`
- I2 `image_arch` 落到变量
- I3 hostname 格式校验；inventory 的 host 键加引号
- I4 补「单 master 不出现 `kube_vip_*`」「`kube_version` 值」测试
- I6 `KubesprayVars` 白名单补齐四个 URL 覆盖与 `github_image_repo` / `image_arch`；
  `etcd_deployment_type` 只保留实际使用的 `host`

### 11.2 改为免登录（2026-11-16）

用户要求「这个不用登录吧，直接就能访问」。向导本来就是纯前端生成器，登录只会挡住想把部署包拿去用的人。

- 路由从 MainLayout 子路由提为顶级：`/clusters/new` → `layouts/PublicLayout.vue`（品牌条 + 主题切换 +
  `登录平台 / 进入平台`），`meta.public = true`，去掉 `requiresPerm` / `domain` / `nav`。
- 页面只按 `getAccessToken()` 决定是否显示「返回集群列表」，不碰权限、租户上下文与任何接口。
- 验证（无 token 直开 `/clusters/new`）：渲染正常、无侧栏、零 `/api/**` 请求、无控制台告警；
  填参后校验通过并自动出 4 个产物文件；带 token 时多出「进入平台」「返回集群列表」；
  主题切换有效。

