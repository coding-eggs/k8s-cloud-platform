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
| 操作系统 | **逐节点**选择：Ubuntu 22.04 / 24.04、Debian 12 / 13、Rocky 9 / 10、Alma 9 / 10、CentOS Stream 9 / 10（可混合） | Rocky 9 | 只进 README 与离线仓库类型提示；kubespray 按主机探测 `ansible_os_family`，不写 group_vars |
| 架构 | 自动 / amd64 / arm64 | **自动** | 自动 = **不写** `image_arch`（由 kubespray 按主机 `host_architecture` 探测）；显式选择才写 —— 镜像清单是全局的，混架构集群跑不通 |
| K8s 版本 | 由 kubespray v2.32.0 的 checksums 生成，不手写范围 | 该版本默认（1.36） | `kube_version` |
| 容器运行时 | containerd / CRI-O | containerd | `container_manager` |
| 控制面 | 1 / 3 / 5 台，节点逐行勾选 | 3 | inventory `kube_control_plane` |
| etcd | 同机（控制面即 etcd）/ 专用 3 或 5 台 / 外部托管（填 endpoints，**自备证书**） | 同机 | `etcd` 组 + `etcd_deployment_type` + `etcd_access_addresses` + `cert_management: none`（仅外部） |
| API 入口 | kube-vip ARP / kube-vip BGP / 外部 LB / 单 master 直连 | kube-vip ARP | `kube_vip_*`、`loadbalancer_apiserver` |
| CNI | 平台管理（不装）/ kubespray 装 Calico / Cilium / flannel | 平台管理 | `kube_network_plugin` |
| 网络 | Pod CIDR、Service CIDR、clusterDomain、kube-proxy 模式、strictARP、节点网段（可选） | `10.233.64.0/18`、`10.233.0.0/18`、`cluster.local`、`ipvs` | `kube_pods_subnet`、`kube_service_addresses`、`cluster_name`、`kube_proxy_mode`、`kube_proxy_strict_arp` |
| 镜像源（在线） | 官方默认 / 自定义 registry / 镜像加速地址 / 内网 Harbor | 官方 | `kube_image_repo`、`gcr_image_repo`、`docker_image_repo`、`quay_image_repo` |
| 离线 | 静态文件仓库地址、内网 registry | — | `files_repo`、`registry_host`、`*_download_url`、`download_run_once` |
| 代理 | http_proxy / https_proxy / no_proxy | 空 | 节点环境变量配置 |
| 节点 | IP、SSH 端口、用户名、hostname、操作系统、角色（可多选） | — | inventory `hosts` |
| SSH 认证 / 跳转机 | 密钥（默认，要求节点已预置部署机公钥）/ 密码；跳转机 `[user@]host[:port]` 可选 | 密钥、直连 | `ansible_password`、`ansible_become_password`、`ansible_ssh_common_args: -o ProxyJump=…` |

说明：

- **操作系统是节点属性**：每台机器可以不同，kubespray 按主机探测；向导只把它写进 README 的 OS 统计，不进 group_vars。
- **架构是集群级约束**：`image_arch` 决定镜像清单与 download，混架构集群跑不通，所以按集群选一次；
  默认「自动」不写变量，让 kubespray 按主机探测（写死一个值反而会害了架构不同的节点）。
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
  - `components/cluster/install/NodeImportDialog.vue` 批量导入节点：粘贴表格 / 选 CSV，本地解析，不上传
  - `utils/clusterInstall/importNodes.ts` 纯函数解析导入文本（格式见 §4.2）
  - `data/kubesprayVars.ts` vendored 的变量名单与支持矩阵（见 §8）
- 草稿：`localStorage` 保存当前表单（可选，v1 可以不做）
- 所有产物用 `Blob` 下载，不引入新依赖

### 4.2 节点批量导入

- 格式：`hostname  IP  [SSH端口]  [SSH用户]  [操作系统]  [角色]`，一行一个节点。
- 分隔符自动识别：制表符（Excel/WPS 直接粘贴）/ 逗号（CSV）/ 空白；首行若含 `hostname` 与 `ip` 视为表头跳过。
- 省略列取默认：`22` / `root` / Rocky 9 / `worker`；角色可用 `control-plane` / `worker` / `etcd`，
  多个用 `/ | 、 ; +` 分隔，认 `master`、`cp`、`控制面` 等同义词。
- OS 认下拉值与常见写法（`rocky9`、`Rocky 9`、`Rocky Linux 9`、`centos9`、`CentOS Stream 9`）。
- 严格模式：**任一行有问题就整批不允许导入**（页面上列出行号与原因），避免半份数据进表单；
  导入内部重复的 hostname / IP 也算问题。
- 两种落表方式：追加到现有行 / 替换现有行；替换会影响 `etcdMode` 的角色勾选，导入后校验照常提示。

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
- I2 `image_arch` 落到变量（2026-11-16 起改为：默认「自动」不写，显式选择才写）
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

### 11.3 操作系统归节点、架构默认自动（2026-11-16）

用户指出「基础里的操作系统、架构是节点属性」。核对后确认：

- **OS 名不副实**：原实现只把它写进 README 一行，不进任何 group_vars → 移到节点表每行一列，
  README 改为按 OS 归类计数（混合 OS 如实写出 `Ubuntu 22.04 × 1、Rocky Linux 9 × 3`）。
- **架构确实是集群级**（`image_arch` 驱动镜像清单与 download），但原实现强制写 `amd64`，
  会让 arm64 节点按错误架构拉镜像 → 改为「自动 / amd64 / arm64」，默认自动 = **不写** `image_arch`；
  离线 + 自动时给 warning（`download_run_once` 按下载机架构准备镜像）。
- 「基础」步只剩集群名 / K8s 版本 / 容器运行时；镜像架构挪到「介质」步。
- 节点表为放下 OS 列收窄列宽、角色标签改短；右侧汇总列 520 → 440px，1440 宽下表格不再横向滚动。
- 单测 **24/24**（新增：架构自动不写 `image_arch`、README OS 归类、缺 OS 非法、离线+自动 warning）。

### 11.4 节点批量导入（2026-11-16）

- 新增 `utils/clusterInstall/importNodes.ts`（纯函数）+ `NodeImportDialog.vue`；节点表加「导入节点」按钮。
- 解析规则与严格模式见 §4.2；单测 **35/35**（新增 11 条：TSV/CSV/表头、默认值、角色同义词、
  多角色、OS 别名、空文本、注释行、非法 IP / 端口 / 角色 / OS / 列数、重复 hostname 与 IP）。
- 预览实测：粘贴带一行坏数据的文本 → 显示 2 个有效节点 + 「第 4 行：IP 格式不正确」，
  导入按钮置灰；去掉坏行后「导入 3 个节点」把 4 行表变成 7 行；CSV 文件选择可用；
  替换模式把 7 行换成导入的 3 行。

### 11.5 SSH 密码认证与跳转机（2026-11-16）

用户问「SSH 只填了用户，密码是不用吗？跳转机免登录？」——原实现只生成 `ansible_user` / `ansible_port`，
隐含要求**密钥认证**（节点预置部署机公钥）且**直连**，这两条此前都没在页面里说清。现在都可选：

- 认证方式：密钥（默认）/ 密码。密码模式写 `ansible_password` + `ansible_become_password`（kubespray 用 become 提权），
  README 提示需要 `sshpass`、包内含明文密码需收紧权限。
- 跳转机：`ansible_ssh_common_args: "-o ProxyJump=[user@]host[:port]"`，留空即直连；
  格式与端口在页面上校验。
- 节点表「操作系统」列 120 → 165px（此前 `Rocky Linux 9` 被截断）；导入预览里的 OS 列同步加宽。
- 单测 **40/40**（新增：密码变量、密钥模式不落任何 SSH 变量、ProxyJump、README 三类文案、
  密码缺失报错、跳转机格式/端口校验）。


### 11.6 列宽与 etcd 角色可见性（2026-11-16）

- 节点表「操作系统」列 165 → 205px：`CentOS Stream 10` 实测文本 118px、可选区 181px，不再截断；
  导入预览的 OS 列同步到 170px；右侧汇总列 440 → 420px 让出空间。
- **同机 / 外部 etcd 模式不再显示 etcd 勾选框**（`etcd` 角色只在「专用 etcd」下有意义）；
  从专用切走时自动清掉节点上的 etcd 角色（只挂 etcd 的节点回落成 worker）并提示一次，
  避免留下看不见、只能靠校验错误提示的残留状态。导入时若带 etcd 角色而当前不是专用模式，同样按 worker 导入并提示。
- 窄屏（≤1100px）由两列改为单列上下叠放，右侧汇总不再把表单挤扁。

### 11.7 复核修正（2026-11-17）

用户指出右侧拓扑/产物被挤扁，要求整体复核。逐项核对 kubespray v2.32.0 源码后改了这些：

**布局**

- 产物面板从「左侧 200px 竖排列表 + 右侧预览」改成**横向文件 chip + 全宽预览**：右栏只有 400~560px，
  竖排列表会把 YAML 预览挤到 170px。实测 2048 宽下预览区从 170px 变成 481px。
- 右栏宽度 `420px` → `clamp(380px, 30%, 560px)`；页面 `max-width: 1720px`，超宽屏不再把表单拉到 1800px 贴边。
- 换步时滚回表单顶部（原来点「下一步」人还停在上一屏，以为没反应）。

**外部 etcd（原实现会跑不起来）**

对照 `roles/validate_inventory/tasks/main.yml`：`etcd_deployment_type` 只接受 `host / docker / kubeadm`，
所以「外部 etcd」不能靠这个变量；实际做法是三件事：

1. `hosts.yaml` 里给一个**空的 etcd 组**（组键必须存在，多处代码直接读 `groups['etcd']`；空组表示不装 etcd）；
2. `etcd_access_addresses` 指向外部端点（kubeadm 模板在 `etcd_deployment_type != kubeadm` 时一律走 external 分支）；
3. `cert_management: none` + README 说明「每台控制面自备 `/etc/ssl/etcd/ssl/{ca.pem,node-<host>.pem,node-<host>-key.pem}`」——
   否则 etcd 角色里的「把 etcd CA 加进系统信任库」会因找不到 ca.pem 直接失败。

**角色清理（原来会把 etcd 节点静默变成 worker）**

切走「专用 etcd」时，只做 etcd 的节点此前会回落成 worker，等于凭空多出几台工作节点。
现在抽成纯函数 `utils/clusterInstall/roles.ts`：兼角色的只去掉 etcd；**只做 etcd 的整行移除**并提示数量；
导入时同理跳过并提示。

**其它**

- 新增校验「节点至少需要一个角色」：无角色的节点会进 `all.hosts` 却不进任何组，成为孤儿主机。
- 核对过无问题的：`kube_version` 裸版本、`kube_proxy_mode`（ipvs/iptables/nftables）、`kube_network_plugin`
  （cni/calico/cilium/flannel）、kube-vip 全套变量与 `loadbalancer_apiserver`（官方文档同样要求）、
  BGP peers 格式 `ip:as::false`、`cluster_name` 取 DNS 域名（与 kubespray 样例 `dns_domain: {{ cluster_name }}` 一致）。
- 已知缺口（未做）：内网 registry 若是 HTTP 或自签证书，需要 `containerd_insecure_registries` /
  预置 CA，向导目前不生成。
- 单测 **44/44**（新增：外部 etcd 的空组/cert_management/README、角色清理 3 条、无角色报错）。

### 11.8 大集群拓扑、README 预览与离线物料清单（2026-11-17）

用户提了四个问题：100 个 worker 怎么画、README 为什么是原文、产物是什么意思、离线模式的
文件清单与镜像清单去哪了。

- **拓扑折叠**：一行最多 5 格，超出折成「+N 台」一格（虚线框），卡片下方给一行
  `共 103 台 · 控制面 3 · worker 100`。原来 100 个 worker 会把 viewBox 拉到 15840px，
  缩到 400px 宽后每台机器只剩 4px，等于什么都看不见。
- **README 用 Markdown 预览**：新增 `utils/clusterInstall/markdown.ts`（~110 行，无依赖），
  支持标题 / 列表 / 围栏代码块 / 引用 / 行内 code 与加粗；**先转义 HTML 再做行内替换**，
  `v-html` 里不会执行任何原始 HTML。之前 `.md` 走 `<pre>`，用户看到的是带 `##` 的原文。
- **「产物」改名「部署包文件」**：卡片标题 + 一行说明（这是要拷进 kubespray 检出 `inventory/`
  的文件，点文件名预览、右上「下载」存单个文件）；按钮 `生成产物` → `生成部署包`。
- **离线物料清单**：kubespray 的文件/镜像清单不能手写（随版本、CNI、架构变化），
  官方链路是 `contrib/offline/generate_list.sh -i <inventory>` → `temp/files.list` + `temp/images.list`，
  再由 `manage-offline-files.sh`（wget -x 保留 `github.com` / `dl.k8s.io` / `storage.googleapis.com` /
  `get.helm.sh` 目录结构，起 nginx :8080）与 `manage-offline-container-images.sh create|register` 落地。
  离线模式现在多生成一个 `offline-prepare.sh`，把这条链路按**本集群的 inventory** 串好；
  README 的离线段落也改成「清单从哪来、`files_repo` 该指向哪」。
- 单测 **51/51**（新增 markdown 6 条、离线脚本 1 条），构建通过。

### 11.9 部署包一键打包下载（2026-11-17）

用户问「部署包文件，我怎么还得一个一个下载？」——之前只有单文件下载。

- 面板顶部加「打包下载 .zip」：把当前全部产物打成一个 zip，**保留 `inventory/<集群名>/` 目录结构**，
  解压即得可拷进 kubespray 的目录；文件名 `<集群名>.zip`。单文件下载保留。
- 实现是自带的 `utils/clusterInstall/zip.ts`（约 110 行、零依赖）：store 方式（不压缩，产物才几十 KB）、
  自算 CRC32、固定时间戳（同输入同字节），UTF-8 文件名标记。
- 验证：`unzip -t` 实测生成的包「No errors detected」、中文内容解出正常；页面里点按钮截获到的
  blob 以 `PK\x03\x04` 开头、EOCD 计数 6、文件名 `demo-cluster.zip`，6 个条目路径全部正确。
- 单测 **53/53**（新增 CRC32 已知向量、ZIP 头/尾与内容 2 条）。

### 11.10 右栏可读性与全宽布局（2026-11-17）

用户反馈「拓扑图和下面的文档预览弄大一点，看着太费劲」，随后要求「整体宽度全屏」。

- **拓扑从 SVG 改成 HTML/CSS 格子**：根因是 SVG 等比缩放——右栏 420~680px，一行 3 台机器的
  viewBox 就有 570+，整体缩到 0.7 倍后 13px 的标题在屏幕上只剩 9px（5 列时更低到 7px）。
  HTML 格子用 `grid-template-columns: repeat(auto-fit, minmax(110px, 1fr))`，字号恒等于 CSS 的 px，
  窄栏只少放一格、不缩小字。行间用居中的竖线连接，外层入口仍是居中药丸。
  折叠规则改为一行最多 **3** 台（原 5 台），超出折成「+N 台」虚线格，底部仍给
  `共 N 台 · 控制面 x · worker y`。
- **页面全宽**：`.install` 去掉 `max-width: 1720px`，右栏从 `clamp(420px, 34%, 680px)` 改成
  `minmax(480px, 40%)`——窗口多宽就用多宽，右栏跟着长到 40%。
- **预览区放大**：README 预览 12.5px → 14px（行高 1.8、内边距 14/18、标题 15.5px），
  YAML 预览 12.5px → 13.5px（`ArtifactPanel` 内 `:deep(.yaml-view)` 覆盖，不动全局 YamlBlock），
  预览高度 46vh → 58vh，文件 chip 12px → 13px。
- 单测 **53/53**、`npm run build` 通过；1139 / 1920 两档宽度实测无横向溢出，
  1139 下右栏 480px、格子 142px、标题 13px（改造前同等宽度下约 7px）。

### 11.11 文档改为全屏查看（2026-11-17）

用户要求「把文档单独做个按钮，点击就全屏打开，别整右边了」——右栏再宽也是辅助栏，读 YAML/README 不合适。

- **右栏不再内嵌预览**：`部署包文件` 卡片只剩「全屏查看 / 打包下载 .zip」两个按钮、文件 chip、当前路径 +「下载」，
  高度从 500+ 降到 ~240px；aside 因此不再需要内部滚动。
- **全屏查看器**：`el-dialog fullscreen`，头部是完整路径 +「下载」，下面一排文件 chip 可直接切换，
  正文按类型渲染（YAML 走 YamlBlock 14px、README 走 Markdown 15px/行高 1.9、其余 pre）。
- **样式位置**：dialog 内容挂在 body 下拿不到 scoped 的 `data-v`，查看器样式写在非 scoped 的
  `<style>` 里并统一用 `.artifact-viewer` 前缀隔离（`artifact-viewer__md` 等）。
- 实测：1139px 窗口下查看器铺满（1139×890），YAML/README 均按预期字号渲染，README 行宽封顶 1080px；
  单测 53/53，构建通过。

### 11.12 删掉「生成部署包」按钮（2026-11-17）

用户问「这个生成部署包按钮干嘛的」——它是死按钮，属于实现遗留：

- 部署包是 `computed(generate(form))`，表单合法就实时存在，**没有「点一下才开始算」这回事**；
- 原实现 `showArtifacts = generated || errors.length === 0`，而按钮在有错误时直接 return，
  所以合法时卡片早已显示、非法时按钮也不会让卡片出现——它唯一的效果是 `scrollIntoView`；
- 已删除按钮、`generated` 状态与两个滚动锚点；`showArtifacts` 只看 `errors.length === 0`。
  实测：填完表单（集群名 + 4 台 IP + VIP）后「部署包文件」卡片自动出现，无需任何点击。
- 单测 53/53、构建通过。

### 11.13 README 第 1 步改成与实际产物一致（2026-11-17）

用户问「把本目录拷进 kubespray 检出（从本目录所在位置执行）这句话什么意思」——这句话本身就是错的：

- 它的本意是「把 `inventory/<集群名>/` 这一层放进 kubespray 的 `inventory/`」，`cp -r` 用相对路径所以要求
  「在它所在位置执行」；但面板并**不会**给出一个 `demo-cluster/` 目录——整包下载拿到的是
  `demo-cluster.zip`，包里已经是 `inventory/demo-cluster/...`，正确动作是解压到 kubespray 根目录。
- 现在 README §1 写两种真实路径：
  - 整包：`unzip <集群名>.zip -d <kubespray>`（结果 `<kubespray>/inventory/<集群名>/`），没有 unzip 就用系统解压工具；
  - 逐个下载：按面板里的完整路径 `inventory/<集群名>/...` 建目录再放文件。
- 实测：查看器里渲染正常；单测 53/53、构建通过。

### 11.14 README 里补 `<kubespray>` 的定义（2026-11-17）

用户接着问「kubespray 是什么」——指的是 README 命令里的 `<kubespray>` 占位符。§1 开头现在先说明：
它指 kubespray 仓库的检出目录（例 `/opt/kubespray`），`inventory/`、`cluster.yml` 都在其下；
没有的话给出 `git clone --depth 1 --branch v2.32.0 …` 一行。查看器实测渲染正常（占位符不会被当 HTML），
单测 53/53、构建通过。

### 11.15 README 补成可照做的完整文档（2026-11-17）

用户要求「文档补全，需要什么都一一说明」。原 README 只有 3 节、默认读者认识 kubespray。

- 新结构：0 三个名词（kubespray / inventory / 控制机）→ 1 包里有什么（逐文件）→ 2 控制机准备
  （Python 3.11~3.13、clone、venv + `pip install -r requirements.txt`、sshpass、SSH 连通性自测）→
  3 节点准备（OS、SSH 用户与 sudo、规格、时间同步、swap、防火墙 + 端口清单）→ 4 放入 inventory →
  5 安装前确认（入口/CNI/网段/kube-proxy/kubeconfig_localhost）→ 6 执行安装 → 7 验证 → 8 接入平台 →
  9 离线模式（仅离线，含 9.1~9.4）→ 10 日常操作（加节点 `scale.yml`、删节点 `remove-node.yml`、
  升级 `upgrade-cluster.yml`、清空 `reset.yml`）→ 注意事项（按表单条件出：明文密码 / 跳转机 / 代理 /
  固定架构 / 外部 etcd）。
- 章节号随离线节动态编号（无离线时 8 → 9），「注意事项」无内容时整节不出现，避免空标题。
- **对照 kubespray v2.32.0 官方文档核过的事实**：`requirements.txt` 钉 ansible 12.3.0（Python 3.11~3.13）；
  安装命令是 `ansible-playbook … -b cluster.yml`；`kubeconfig_localhost` / `kubectl_localhost` 产物在
  `inventory/<名>/artifacts/`；端口清单见 `docs/operations/port-requirements.md`（修正了凭记忆写错的
  Calico VXLAN 端口：**4789**，并补 Typha 5473；Cilium 才是 8472）；扩缩容用 `scale.yml`、删节点用
  `remove-node.yml`（不支持删第一台控制面/etcd）。
- 渲染器顺带修了一个真 bug：列表项的缩进续行原来被拆成独立段落（`markdown.ts` 增加续行归并），
  新增单测后从 53 → **54/54** 通过；构建通过。

### 11.16 离线改成「全离线包」（参照用户的 k8s-install 方案，2026-11-17）

用户指出「既然是离线环境肯定是全离线」，并让参考 `D:\coding\k8s-install` 里他之前的两段脚本。

- 参照该方案：离线环境拿不到任何外网资源，所以 **一次收进一个 tar 包**：kubespray 源码、inventory、
  Python wheel、静态文件、容器镜像、`registry:2` 本体镜像、节点系统 rpm/deb。
- `offline-prepare.sh` 重写为 7 步：clone → 并入 inventory → venv + `pip download` →
  `generate_list.sh` + `NO_HTTP_SERVER=true manage-offline-files.sh`（只下载不起服务）→
  `manage-offline-container-images.sh create` → `pull/save registry:2` → 按节点 OS 家族
  （`osFamily`，rpm 用 `yum --downloadonly`、deb 用 `apt-get --download-only`）→ 统一 `tar czf`。
- `offline.yml`：`registry_host` 去掉 `http://` 前缀；地址是 `http://` 时追加
  `containerd_registries_mirrors`（http + `skip_verify: true`），这正是之前记录的「HTTP registry 缺口」。
- `yaml.ts` 补上**对象数组**的序列化（mirrors 这种结构原来写不出来）。
- README §9 重写为 9.1~9.6：生成离线包 → 解压 → 起 registry 并灌镜像 → 起文件服务器
  （`python3 -m http.server`，并注明官方 `manage-offline-files.sh` 的 nginx 是 `/download` 前缀，
  `files_repo` 要跟着带）→ 节点装 rpm/deb → 安装。删掉「OS 包仓库仍需另行配置」。
- 单测 **57/57**（新增 3 条：insecure mirror、全离线脚本步骤、README §9 内容），构建通过。

### 11.17 离线声明上移到顶部、去掉名词介绍（2026-11-17）

- README 摘要区新增「部署模式：在线 / 离线」；离线包紧跟一个引用块声明：**这是全离线部署包**、
  包里有 kubespray 源码 / Python 依赖 / 镜像与 registry / 静态文件 / 系统依赖、完整流程见第 9 节。
- 删除「0. 三个名词」整节；`<kubespray>` 的含义仍写在第 2、4 节。
- 顺带修渲染器：连续 `>` 行原来每行一个 `<blockquote>`（页面上是三个方框），现在合并成一个；
  新增单测，**59/59** 通过，构建通过。

### 11.18 离线地址不让用户填、部署步骤落成脚本（2026-11-17）

用户：文档里已经写了文件服务器和 registry，那就直接写成脚本，地址也别让填。

- 表单「介质」步删掉「静态文件仓库 / 内网 registry」两个输入；`InstallForm` 去掉 `filesRepo` / `registryHost`，
  校验里的两条必填一并删除（离线的结构校验只剩架构提示）。
- `offline.yml` 的地址改成占位符：`http://__CONTROL_HOST__:8080`（文件服务）、`__CONTROL_HOST__:5000`（registry），
  `containerd_registries_mirrors` 固定指向这个 HTTP registry。
- 新增 `deploy-offline.sh`（与 inventory 同目录）：
  探测控制机 IP（`ip route get 1.1.1.1`，回退 `hostname -I`）→ `sed` 替换 offline.yml 里的占位符 →
  加载 `registry.tar` 起本地 registry → `manage-offline-container-images.sh register` 灌镜像 →
  后台 `python3 -m http.server 8080` 服务 `offline-files/` → 用离线 wheel 装 ansible →
  `ansible all` 按 inventory 分发并安装 rpm/deb → `ansible-playbook cluster.yml`。
- README §9 改成：9.1 生成离线包 / 9.2 带进离线环境 / 9.3 一键部署 / 9.4 等价手工步骤（排错用）。
- 单测 **60/60**（离线断言改占位符、新增 deploy 脚本 1 条），构建通过；页面实测：介质步无地址输入，
  产物 7 个文件（含 `deploy-offline.sh`）。

### 11.19 拓扑与部署包文件挪进最后一步（2026-11-17）

用户：四步里第 4 步「啥也没有」，把拓扑和文档等都挪进去。

- 删除右侧 sticky 汇总栏（`.install__aside`）；「拓扑 / 校验 / 部署包文件」三张卡片放到第 4 步表单下方，
  第 4 步改名「介质与部署」。
- 布局：`.install__grid` 改为单列；新增 `.install__review = grid auto-fit minmax(420px,1fr)`，
  拓扑与校验并排、部署包文件用 `.install__card--wide` 占满整行。
- 实测 1024 宽窗口：review 两列各 478px、部署包卡片 972px、无横向滚动；前三步不再有右栏。
- 单测 60/60、构建通过。

### 11.20 拆成五步：介质归第四步，第五步专门看结果（2026-11-17）

用户：不是把结果塞进第四步，而是**增加第五步**，第五步里给拓扑、部署包文件和校验结果。

- `STEPS = ['基础', '节点', '网络与入口', '介质', '部署包']`（原第 4 步「介质与部署」拆开）。
- 第 4 步只剩介质表单（镜像架构 / 部署模式 / 镜像源或离线提示 / 代理）；表单容器加 `v-if="step < STEPS.length - 1"`，
  第 5 步不渲染空的 `el-form`。
- 第 5 步「部署包」承载 `.install__review`：拓扑与校验并排、部署包文件整行。校验通过时文件卡片才出现，
  有错误时只显示错误列表（提示回上一步改）。
- 实测 1024 宽：五步标题正确、第 4 步只有表单、第 5 步卡片为「拓扑 / 校验 通过 / 部署包文件」，
  review 两列 478px、部署包 972px、无横向滚动。单测 60/60、构建通过。
