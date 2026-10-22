# calico（tigera-operator chart v3.33.0 + projectcalico.org.v3 v3.33.0）· Calico 组件

> 来源：`https://docs.tigera.io/calico/charts`（projectcalico/calico），2026-10-17 原样入库，
> 平台定制只走 recipe values。组件 code = `calico`；
> **三个部分组成一个组件**：CRD（server-side apply）+ operator（Helm release）+ `Installation`（operator 接管）。
> `tigera-operator` 没有独立 recipe —— operator 单独安装没有产品意义。

## 1. 概览

| 项 | 值 |
|---|---|
| 组件 code | `calico` |
| 画像 | BUSINESS / MANAGEMENT（两边同一条 recipe） |
| ① CRD | chart `projectcalico-crds` v3.33.0 → **server-side apply 直接落盘，不建 Helm release** |
| ② 本体（primary） | release `platform-tigera-operator` / ns `tigera-operator` / chart `tigera-operator` v3.33.0 |
| 版本 | tigera-operator v3.33.0 = Calico v3.33.0（Chart.yaml `appVersion`，两套编号一致） |
| 部署内容 | operator + `Installation default`；operator 自建 `calico-system`（calico-node / typha / kube-controllers） |
| API group | `crd.projectcalico.org`（页面判据）+ `operator.tigera.io`（Installation 等） |
| 探测判据 | DaemonSet `calico-node`（镜像 `calico/node`）：`calico-system`（官方 operator 布局）**或** `kube-system`（老 `calico.yaml` / 早期 operator） |
| 平台功能 | `network`、`ipam`（Calico 页面直接读 `projectcalico.org/v3`） |
| 实测 | 2026-10-22 `test-work-cluster`（k8s **v1.37.1**，裸集群无 CNI）：`Installation` = `10.243.0.0/16` + `IPIP`，calico-node / typha / kube-controllers 全部 Running |

> 外部安装（探测命中但无平台操作记录）的集群显示「已装 · 只读」，平台不接管 ——
> 也不会给出会撞 ownership 冲突的「安装」按钮（2026-10-22 加的 `kube-system` 判据就是为了这个）。

## 2. 安装前置与安装顺序

**前置**

- 目标集群**没有 CNI**（本平台不支持 Calico 的 policy-only 模式）；已经有 Calico（或别的 CNI）的集群
  **不要走平台安装** —— 见 §7.3。
- 节点 Linux；安装时填的 Pod CIDR 必须与集群规划一致（Calico 用它建 IPPool，写错直接断网）。
- 平台侧：`k8s-server` 镜像需带 **helm 4.2.0 + kubectl**（Dockerfile 已装，`KUBECTL_VERSION`/`HELM_VERSION`
  可用 `--build-arg` 覆盖；内网构建机要能访问 get.helm.sh 与 dl.k8s.io）。

**顺序**（`ComponentInstallController` 顺序执行、失败即停）

1. 渲染 CRD chart（完整清单 + 按集群 capabilities，见 §2.1）→ `kubectl apply --server-side --force-conflicts
   --field-manager=platform-system`；
2. `kubectl wait --for=condition=Established`（清单里解析出的 31 个 CRD）；
3. `helm upgrade --install platform-tigera-operator`（operator + `Installation`）。

### 2.1 为什么第 ① 步是 SSA 而不是 Helm release

官方 tigera-operator chart **不含 CRD**，CRD 在独立 chart `projectcalico.org.v3` 里（官方 docs 也是
`helm template | kubectl apply --server-side`）。少这一步 → operator release 里的 `Installation` 在
manifest Build 阶段直接失败：

```
resource mapping not found ... no matches for kind "Installation" in version "operator.tigera.io/v1"
ensure CRDs are installed first
```

而这一步**不能做成 Helm release**：Helm 会把**整个 chart**（不只是渲染结果）存进 release Secret，
上限 1MiB；这套 CRD chart 渲染后 3.5MB、chart 本身同量级：

```
Secret "sh.helm.release.v1.platform-calico-crds.v1" is invalid: data: Too long: may not be more than 1048576 bytes
```

所以 recipe 给这条 release 标了 `applyMode=SERVER_SIDE_APPLY`（`ReleaseSpec.APPLY_SERVER_SIDE`），
走 `HelmRunner.applyServerSide` / `waitCrdsEstablished`，**不建 release**。

### 2.2 渲染必须按集群 capabilities（2026-10-22 修）

`helm template` 不连集群，`.Capabilities.APIVersions` 用的是 helm 自带默认值（4.2.0 ≈ k8s 1.35），会把
`MutatingAdmissionPolicy` 渲染成 `admissionregistration.k8s.io/v1beta1`；而集群 1.36+ 只认 `v1`：

```
no matches for kind "MutatingAdmissionPolicy" in version "admissionregistration.k8s.io/v1beta1"
```

现在 direct-apply 渲染前先探测集群：`major.minor` 走 `client.getKubernetesVersion()`，
`admissionregistration.k8s.io/{v1,v1beta1,v1alpha1}` 逐个看哪个真的提供 `mutatingadmissionpolicies`，
然后传 `--kube-version/--api-versions`。同一处还保证清单**不被 64KB 截断**（`renderFull`）。

## 3. 契约 values（🔒 不可改）

```yaml
installation:
  enabled: true
  calicoNetwork.ipPools: [{ cidr: "${calico.podCidr}", encapsulation: "${calico.encapsulation}" }]
apiServer: { enabled: false }   # 平台不用 Calico API Server
goldmane:  { enabled: false }   # 流量日志
whisker:   { enabled: false }   # 可视化 UI
```

| 值 | 为什么 |
|---|---|
| `calicoNetwork.ipPools` 两个占位符 | 来自安装表单（`platform.calico.*` 默认值）；**与集群现有网络不一致会直接断网** |
| 三个子组件关闭 | chart 默认全开，平台功能一个都不用（也省 Pod） |

## 4. 安装表单（可编辑）

| 字段 | 说明 |
|---|---|
| Pod CIDR | 默认取 `platform.calico.podCidr`；**必须与集群现有/规划的 Pod 网段一致**，写错立即断网 |
| 封装方式 | 默认取 `platform.calico.encapsulation`；五种取值见 §4.1，后端只接受这五个（2026-10-22 加校验） |

### 4.1 封装方式怎么选

| 值 | 做什么 | 什么时候选 | 代价 / 前提 |
|---|---|---|---|
| `IPIP` | 跨节点流量全部走 IP-in-IP 隧道（同节点直通） | 自建机房 / 裸金属；底层只要求节点间 IP 可达 | 20 字节头；节点网络要允许 IP 协议号 4 |
| `VXLAN` | 跨节点流量封进 UDP（4789） | 公有云、IPIP 被禁或丢包的环境 | 50 字节头，开销比 IPIP 略高 |
| `IPIPCrossSubnet` | 同子网节点走原生路由，**只跨子网**时 IPIP | 同网段集群，想省掉同子网封装开销 | 同子网节点必须二层可达 |
| `VXLANCrossSubnet` | 同上，但跨子网用 VXLAN | 同上且 IPIP 不可用 | 同上 |
| `None` | 不封装，纯路由（BGP 对等或静态路由通告 Pod CIDR） | 底层网络已能把每个节点的 Pod CIDR 路由过去 | 路由没打通 = 跨节点 Pod 完全不通 |

- 值写错时 Calico **不会报错退出**，而是装完网络不通（排查成本极高）——所以后端也校验这五个值
  （`ComponentInstallService.resolveCalicoInstall`），表单里选中的那一档会显示完整说明。
- 封装方式只能在**安装前**定：改它等于重建集群网络，已运行的 Pod 会断。

## 5. 实锤（来自历史对话）

- **2026-10-21 用户裁定**：管理集群的 Calico 是 `kubectl apply` 装的 tigera-operator
  （operator v1.38.6 / Calico 3.30.3，无 `meta.helm.sh` 注解、`helm list` 无记录），现有
  `Installation/default` = `10.244.0.0/16` + `VXLANCrossSubnet`。`helm template` + `kubectl diff`
  （server-side dry-run，零风险）证明 recipe 是**"空集群最小值"**：会洗掉 ipPool 的
  blockSize/name/natOutgoing/nodeSelector/allowedUses，并把 `kubeletVolumePluginPath` 改成 `None`
  → **对已装集群破坏性**。用户明确否决两项：① MANAGEMENT 画像取消 recipe；② 安装前检测其它 CNI 并拒绝。
  于是管理集群只做「已装 → 只读 + 探测 + Calico 页面」验收，安装路径等业务集群。
- **2026-10-22 业务集群实测（`test-work-cluster`，k8s v1.37.1，裸集群）**，连续撞三次，逐条修掉：
  1. `ensure CRDs are installed first` → 用官方独立 CRD chart（§2.1）；
  2. `Secret ... data: Too long` → CRD 改走 server-side apply（§2.1）；
  3. `no matches for kind "MutatingAdmissionPolicy" in version "admissionregistration.k8s.io/v1beta1"`
     → 渲染按集群 capabilities（§2.2）。
  最终 operator release `deployed`、`Installation` Ready、`calico-node` / `typha` / `kube-controllers` 全 Running。

## 6. 验证

```bash
helm -n tigera-operator list | grep platform-tigera-operator   # 只有 operator 是 release（CRD 不是）
kubectl get crd | grep -E 'projectcalico.org|operator.tigera.io' | wc -l   # 31（SSA 落盘）
kubectl get installation default -o jsonpath='{.status.phase}'             # Ready 之前是 Progressing
kubectl -n calico-system get pods                                         # node / typha / controllers
kubectl get pods -A -o wide | grep -v Running                             # CNI 正常后全集群 Pod 才恢复
```

平台侧：组件页 Calico 显示「已装」，`network` / `ipam` 功能可用（Calico 页能读 IPPool/BGP 等）。

## 7. 已知坑

1. **`Installation` 建不出来 / `ensure CRDs are installed first`** —— CRD 那步没跑或没生效（§2）。
2. **手工删 CRD = 断网** —— CRD 由平台以 `field-manager=platform-system` 写入，**没有 Helm release 可回滚**；
   `kubectl delete crd` 会级联删除全部 Calico 自定义资源（含 IPPool）。平台不提供卸载（§9）。
3. **集群已经用 kubectl 装过 Calico** —— CRD 已存在但归属别的 manager：SSA 带 `--force-conflicts`
   能接管字段，但 operator release 里的 Deployment/SA/Installation 仍是 Helm 归属冲突，
   **这种集群不要走平台安装**（继续手工管理）。探测已覆盖两种布局，这类集群显示「已装 · 只读」。
4. **Pod CIDR / encapsulation 填错** —— 装完立即断网，且修复要回到节点上处理。
5. **`MutatingAdmissionPolicy` 的 API 版本** —— 已改为按集群探测（§2.2）；今后升级 helm 或 k8s
   大版本时，先确认探测结果与集群实际一致（`kubectl api-resources --api-group=admissionregistration.k8s.io`）。

## 8. 新增版本

1. 同步拉两个 chart 的新版本（**同一版本号**），入库到 `projectcalico-crds/<ver>/chart` 与
   `tigera-operator/<ver>/chart`，各带 `provenance.yaml`；
2. recipe 的 `CALICO_RELEASES` **两条都换版本**（CRD chart 与 operator chart 必须同版本）；
3. 确认 `projectcalico-crds` 仍然只有模板、没有 `crds/` 目录（否则 CRD 会被 Helm 以另一种方式安装，行为不同）；
4. 非生产集群按 §6 验证；
5. 重建 **k8s-server + platform-api**（chart 在 k8s-server 侧）。

## 9. 卸载

平台**不提供**组件卸载。手工顺序与后果：

```bash
helm -n tigera-operator uninstall platform-tigera-operator
```

- 这条会删掉 operator 与 `Installation` → operator 清理 `calico-system` → **集群网络立即中断**；
- CRD 不是 Helm release 的一部分，**不会被删**；要彻底清理必须手工 `kubectl delete crd`，
  而那会级联删除所有 Calico 对象（含 IPPool）；
- 卸载前必须已有替代 CNI，或在维护窗口内操作。
