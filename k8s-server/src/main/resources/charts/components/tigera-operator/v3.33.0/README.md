# calico（tigera-operator chart v3.33.0 + projectcalico.org.v3 chart v3.33.0）

> 来源：`https://docs.tigera.io/calico/charts`（projectcalico/calico），原样入库，平台定制只走 recipe values。
> 组件 code = `calico`；**两个 release 组成一个组件**，`tigera-operator` 没有独立 recipe
>（operator 单独安装没有产品意义）。

## 1. 概览

| 项 | 值 |
|---|---|
| 画像 | BUSINESS / MANAGEMENT |
| ①CRD | chart `projectcalico-crds` v3.33.0 → **server-side apply 直接落盘，不建 Helm release** |
| ②本体（primary） | release `platform-tigera-operator` / ns `tigera-operator` / chart `tigera-operator` v3.33.0 |
| 部署内容 | tigera-operator + `Installation default`（`calico-system` 由 operator 自己建） |
| 平台功能 | `network`、`ipam`（Calico 页面读 `projectcalico.org/v3`） |

## 2. 为什么是「CRD 直接落盘 + 一个 Helm release」

**官方 tigera-operator chart 里没有 CRD**，CRD 在独立 chart `projectcalico.org.v3` 里，官方 docs 的安装顺序是：

```bash
helm template calico-crds projectcalico/projectcalico.org.v3 --version v3.33.0 \
  --api-versions admissionregistration.k8s.io/v1/MutatingAdmissionPolicy | kubectl apply --server-side -f -
helm install calico projectcalico/tigera-operator --version v3.33.0 -n tigera-operator
```

少这一步时 operator release 里的 `Installation` 会在 manifest Build 阶段直接失败：

```
resource mapping not found ... no matches for kind "Installation" in version "operator.tigera.io/v1"
ensure CRDs are installed first
```

**为什么第一步不能做成 Helm release**：Helm 会把**整个 chart**（不只是渲染结果）存进 release Secret，
上限 1MiB。这套 CRD chart 渲染出来 3.5MB、chart 本身同量级，`helm install` 直接报：

```
Secret "sh.helm.release.v1.platform-calico-crds.v1" is invalid: data: Too long: may not be more than 1048576 bytes
```

（2026-10-22 实测）。所以 recipe 给这条 release 标了 `applyMode=SERVER_SIDE_APPLY`：
`ComponentInstallController` 渲染后执行 `kubectl apply --server-side --force-conflicts
--field-manager=platform-system`，**不建 Helm release**；随后 `kubectl wait --for=condition=Established`
等 CRD 真的可被发现，再装 operator release（`ComponentInstallController` 的顺序执行 + 失败即停）。

`helm template` 的 `.Capabilities.APIVersions` 来自 helm 自带默认值（当前 helm 4.2.0 ≈ k8s 1.35），
所以 `MutatingAdmissionPolicy` 会渲染成 `admissionregistration.k8s.io/v1beta1`。这意味着：
目标集群**比 helm 默认版本旧**（不认 v1beta1）会 apply 失败；**新到把 v1beta1 移除**（k8s 1.39 前后）
同样失败。两种情况都要给渲染补 `--kube-version/--api-versions`（尚未实现，2026-10-22 记录）。

## 3. 契约 values（🔒）

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

### 3.1 封装方式（安装表单的「封装方式」）怎么选

| 值 | 做什么 | 什么时候选 | 代价 / 前提 |
|---|---|---|---|
| `IPIP` | 跨节点流量全部走 IP-in-IP 隧道（同节点直通） | 自建机房 / 裸金属；底层只要求节点间 IP 可达 | 20 字节头；节点网络要允许 IP 协议号 4 |
| `VXLAN` | 跨节点流量封进 UDP（4789） | 公有云、IPIP 被禁或丢包的环境 | 50 字节头，开销比 IPIP 略高 |
| `IPIPCrossSubnet` | 同子网节点走原生路由，**只跨子网**时 IPIP | 同网段集群，想省掉同子网封装开销 | 同子网节点必须二层可达 |
| `VXLANCrossSubnet` | 同上，但跨子网用 VXLAN | 同上且 IPIP 不可用 | 同上 |
| `None` | 不封装，纯路由（BGP 对等或静态路由通告 Pod CIDR） | 底层网络已能把每个节点的 Pod CIDR 路由过去 | 路由没打通 = 跨节点 Pod 完全不通 |

- 值写错时 Calico **不会报错退出**，而是装完网络不通（排查成本极高）——所以后端也校验这五个值
  （2026-10-22 加入 `ComponentInstallService`），表单里选中的那一档会显示完整说明。
- 封装方式只能在**安装前**定：改它等于重建集群网络，已运行的 Pod 会断。

## 4. 已知坑

1. **`Installation` 建不出来 / `ensure CRDs are installed first`** —— CRD 那步没跑或没生效（§2）。
2. **手工删 CRD = 断网** —— CRD 由平台以 `field-manager=platform-system` 写入，没有 Helm release 可回滚；
   `kubectl delete crd` 会级联删除全部 Calico 自定义资源（含 IPPool）。平台不提供卸载。
3. **集群已经用 kubectl 装过 Calico** —— CRD 已存在但归属别的 manager：
   SSA 带 `--force-conflicts` 能接管字段，但 operator release 里的 Deployment/SA/Installation 仍是
   Helm 归属冲突，**这种集群不要走平台安装**（继续手工管理）。
   探测判据已覆盖两种真实布局（`calico-system/calico-node` 与老的 `kube-system/calico-node`），
   这类集群在组件页显示「已装 · 只读」，不会给出「安装」按钮（2026-10-22）。
4. **Pod CIDR / encapsulation 填错** —— 装完立即断网，且修复需要回到节点上处理。
5. **`MutatingAdmissionPolicy` 的 API 版本** —— 由 helm 默认 capabilities 决定（当前 v1beta1）；
   k8s 1.39 前后该版本移除后，渲染要改成带 `--kube-version/--api-versions`。

## 5. 验证

```bash
helm -n tigera-operator list | grep platform-tigera-operator    # 只有 operator 是 release
kubectl get crd | grep -E 'projectcalico.org|operator.tigera.io' | wc -l   # 31（SSA 落盘）
kubectl get installation default -o jsonpath='{.status.phase}'              # Ready 之前是 Progressing
kubectl -n calico-system get pods
kubectl get pods -A -o wide | grep -v Running    # 节点网络正常后全集群 Pod 才恢复
```

## 6. 新增版本

1. 同步拉两个 chart 的新版本（同一版本号），入库到 `projectcalico-crds/<ver>/chart` 与
   `tigera-operator/<ver>/chart`，各带 `provenance.yaml`；
2. recipe 的 `CALICO_RELEASES` **两条都换版本**（CRD chart 与 operator chart 必须同版本）；
3. 非生产集群验证 §5 的四条；
4. 重建 **k8s-server + platform-api**（chart 在 k8s-server 侧）。
