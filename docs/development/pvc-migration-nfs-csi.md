# 把测试集群的 PVC 从 `nfs-client` 迁到 `nfs-csi`

> 2026-11-17 按 `coding-cluster` 实测写成。若目标是另一套集群（例如 `test-work-cluster`），
> 清单要重扫，步骤与坑不变。

## 0. 结论

1. **K8s 不允许改已绑定 PVC 的 `storageClassName`** —— 迁移只能是「删旧 PVC（先 Retain 保住数据）→ 建同名新 PVC → 把数据搬进新目录 → 消费方重挂」。
2. 好消息：两个 provisioner **指向同一台 NFS、同一个 export**
   （`nfs-client` 与 `nfs-csi` 都是 `192.168.85.180:/home/data/nfsdata`），
   所以「搬数据」可以在 NFS 主机上做目录 `mv`，秒级完成，不用走网络拷贝。
3. 现状 27 块 PVC 全在 `nfs-client`，约 521GiB 申请量；其中 **5 块没有任何 Pod 挂载**，直接删即可。
4. 建议分三批做：可丢弃 → 监控 → 存储/镜像/数据库。每块 PVC 一次停机窗口。

## 1. 现状（实测）

| 项 | 值 |
|---|---|
| `nfs-client` | `cluster.local/nfs-client-nfs-subdir-external-provisioner`，Delete，Immediate |
| `nfs-csi` | `nfs.csi.k8s.io`（默认 SC），Delete，Immediate，`server=192.168.85.180`、`share=/home/data/nfsdata` |
| PVC 总数 | 27，全部 `nfs-client`；`nfs-csi` 目前 **0 块** |
| 目录布局 | `nfs-client`：`/home/data/nfsdata/<ns>-<pvc>-<pv-id>`；`nfs-csi`：`/home/data/nfsdata/pvc-<pv-id>`（2026-11-17 用探针 PVC 实测，已清理） |
| 容量属性 | 两个 SC 都 `allowVolumeExpansion: true`、`volumeBindingMode: Immediate` |

> `nfs-csi` 的目录名是 **PV 名**（`pvc-<uid>`），不是 `<ns>/<pvc名>`。所以数据要搬进
> 「新 PVC 建好之后才拿得到的那个目录」，顺序不能反。

## 2. 为什么不能原地改

- `PersistentVolumeClaim.spec.storageClassName` 创建后不可变；能改的只有
  `resources.requests.storage`（扩容）和 `volumeAttributesClassName`。
- `StatefulSet.spec.volumeClaimTemplates` 同样不可变 —— 想改模板里的 SC 必须重建 STS。
  新版 STS 起来时会**按名字复用已存在的 PVC**（不会因为 SC 不同而重建），这是迁移能成立的前提。
- Operator 自管的 PVC（ECK 的 ES、CNPG 的 PostgreSQL）会被各自的 controller 立刻补建，
  删 PVC 前必须先让 controller 别再 reconcile（缩到 0 或暂停）。

## 3. 三条路线

### A. NFS 主机上搬目录（推荐，需要 NFS 主机的 shell）

同一 export 内 `mv` 是 rename，100GiB 也是秒级。停机窗口 = 改 PVC 的时间（分钟级）。

### B. 集群内 rsync 中转（没有主机权限时用）

旧 PVC 与新 PVC 不能同名共存，所以要走「中转 PVC + 两次 rsync」：
`旧 → 中转(nfs-csi) → 删旧、建同名新 PVC → 中转 → 新`。
全程不碰主机，但 **521GiB 要拷两遍**，且大盘（对象存储 100Gi、registry 50Gi）会很慢。

### C. 直接重建（数据本来就可丢）

`pipeline-cache`、无主 PVC 这类，删了让平台/组件自己建新 PVC 就行。

## 4. 路线 A 的通用步骤（每块 PVC 重复）

以 `movie/platform-pipeline-cache` 为例：

```bash
# 0) 先把消费方停下来（STS scale 0 / Deployment scale 0 / operator 缩 CR），确认没有 Pod 挂载
kubectl -n <ns> get pods -o json | jq -r '.items[] | select(..|.claimName?=="<pvc>") | .metadata.name'
kubectl -n <ns> scale sts <sts> --replicas=0

# 1) 记下旧路径，并给旧 PV 改 Retain（否则删 PVC 时 provisioner 会把目录 archive/删掉）
OLD_PV=$(kubectl -n <ns> get pvc <pvc> -o jsonpath='{.spec.volumeName}')
kubectl get pv "$OLD_PV" -o jsonpath='{.spec.nfs.path}{"\n"}'      # 记下来
kubectl patch pv "$OLD_PV" -p '{"spec":{"persistentVolumeReclaimPolicy":"Retain"}}'

# 2) 删旧 PVC（PV 变 Released，数据还在）
kubectl -n <ns> delete pvc <pvc>

# 3) 建同名新 PVC，SC 用 nfs-csi，容量不变
#    （STS 的 PVC 名字由模板决定，必须同名）
cat <<EOF | kubectl apply -f -
apiVersion: v1
kind: PersistentVolumeClaim
metadata: {name: <pvc>, namespace: <ns>}
spec:
  accessModes: [ReadWriteOnce]
  storageClassName: nfs-csi
  resources: {requests: {storage: <原容量>}}
EOF

# 4) 拿新 PV 名 → 目标目录
NEW_PV=$(kubectl -n <ns> get pvc <pvc> -o jsonpath='{.spec.volumeName}')
# 目标目录 = /home/data/nfsdata/$NEW_PV

# 5) NFS 主机上把数据搬进去（同 export 内 mv 是 rename，秒级）
#    mv /home/data/nfsdata/<旧目录名>/* /home/data/nfsdata/$NEW_PV/   # 含隐藏文件用 rsync -a 更稳
#    rsync -aHAX --delete /home/data/nfsdata/<旧目录名>/ /home/data/nfsdata/$NEW_PV/

# 6) 权限对齐（见 §5，最容易踩）
# 7) 起消费方，按 §7 验证
# 8) 验证通过后清场
kubectl delete pv "$OLD_PV"
# 主机上删掉旧目录
```

### 回滚

任何一步验证不过：停消费方 → 删新 PVC → 把旧 PV 的 `claimRef` 清空、`reclaimPolicy` 改回 Delete →
建一个 `storageClassName: nfs-client`、`volumeName: <旧 PV>` 的 PVC → 起服务。
**旧目录在验证通过前不要删。**

## 5. 权限与属主（必读）

- `nfs-subdir-external-provisioner` 建的目录是 **0777**；`nfs-csi` 默认 `mountPermissions: "0755"`，
  且 driver 在建卷时会对目录 `chmod`。搬完数据后目录变成 root:root 0755，
  **以非 root 运行的服务（postgres 999、ES 1000、seaweedfs 等）会写不进去**。
- 两种处理：
  1. 给 `nfs-csi` SC 加 `parameters.mountPermissions: "0777"`（影响之后所有新卷）；
  2. 或搬完数据后按原属主 `chown -R`，并确认容器有 `securityContext.fsGroup`。
- 迁移前先用一个非 root 镜像的探针 Pod（挂 nfs-csi）实际写一次文件，确认导出端的 squash/权限行为，
  别等迁完业务起不来才发现。

## 6. PVC 清单与分类

| 批次 | NS | PVC | 容量 | 消费方 | 处理 |
|---|---|---|---|---|---|
| 1 | movie | platform-pipeline-cache | 10Gi | 无（缓存） | C：删，按需重建 |
| 1 | platform-system | platform-pipeline-cache | 10Gi | 无（缓存） | C |
| 1 | default | data0-coding-minio-pool-0-0 | 50Gi | **无 Pod 挂载** | 确认后删 |
| 1 | default | redis-data-redis-single-master-0 | 8Gi | 无 | 确认后删 |
| 1 | monitor | data-th-thanos-compactor-0 / receive-0 / storegateway-0 | 10/10/8Gi | 无 | 确认后删（旧的一套 thanos） |
| 2 | monitor | data-platform-thanos-compactor-0 / receive-0 / storegateway-0 | 10Gi ×3 | STS `platform-thanos-*` | A（历史指标想留）/ C（可丢） |
| 2 | monitor | alertmanager-...-db-...-0 | 10Gi | STS alertmanager | A |
| 2 | monitor | prometheus-...-db-...-0 | 50Gi | STS prometheus | A |
| 2 | monitor | storage-platform-tempo-0 | 20Gi | STS platform-tempo | A |
| 3 | platform-storage | data-platform-storage-seaweedfs-master-0 | 1Gi | STS seaweedfs-master | A |
| 3 | platform-storage | data-filer-seaweedfs-filer-0 | 10Gi | STS seaweedfs-filer | A |
| 3 | platform-storage | data-seaweedfs-volume-0 | **100Gi** | STS seaweedfs-volume | A（对象存储真数据，最后做） |
| 3 | harbor | harbor-registry | **50Gi** | Deployment harbor-registry | A |
| 3 | harbor | harbor-jobservice | 1Gi | Deployment harbor-jobservice | A |
| 3 | platform-system | elasticsearch-data-...-es-default-0/1/2 | 30Gi ×3 | ECK，**operator 自管** | A + 先停 operator |
| 3 | platform-system | mw-postgresql-ba8ee962-1 | 5Gi | CNPG，**operator 自管** | A + 先缩 CR |
| 3 | platform-system | mw-redis-e1457545-...-0 / mw-redis-f591b8f9-...-0 | 10/5Gi | STS | A |
| 3 | platform-system | datadir-mw-mysql-46509832-mysql-0 | 20Gi | STS | A |
| 3 | default | data-mysql-0 | 8Gi | STS mysql | A |
| 3 | movie | data-mw-milvus-bc293ec1-etcd-0 | 5Gi | STS milvus-etcd | A |

> 兜底判断：迁之前先跑一遍
> `kubectl get pods -A -o json | jq -r '.items[] | [.metadata.namespace, (.spec.volumes[]?.persistentVolumeClaim.claimName // empty)] | @tsv'`
> 确认这块 PVC 现在真的没人挂 —— 清单是 2026-11-17 的快照。

## 7. 验证

每块 PVC 迁完：

```bash
kubectl -n <ns> get pvc <pvc> -o wide          # Bound，SC=nfs-csi
kubectl -n <ns> get pods -o wide               # 消费方 Ready
kubectl -n <ns> exec <pod> -- ls -la <mountPath>   # 数据在
```

- 有状态的做一次业务抽样（ES `_cluster/health`、PG `select count(*)`、milvus 集合数、registry `_catalog`）。
- 全部做完后 `kubectl get pvc -A` 应只剩 `nfs-csi`；`kubectl get pv` 里 `nfs-client` 的 PV 全部清掉，
  否则 `nfs-client` provisioner 不能卸载。

## 8. 别忘了改源头（否则重装/重建又回到 nfs-client）

| 位置 | 改什么 |
|---|---|
| 组件安装 values | 当时没显式写 SC，走默认值 —— 现在默认已是 `nfs-csi`，重装即正确；有显式写 `nfs-client` 的要改 |
| 中间件实例台账 `spec_json` | 旧实例里记着 `storageClass: nfs-client`；编辑/重建前改成 `nfs-csi`（PG / MySQL / ES / Redis / RabbitMQ / Milvus etcd 的适配器都读它） |
| `nfs-csi` SC | 如需 `mountPermissions: "0777"`，在 SC 上加参数（或改 csi-driver-nfs 组件 values 后重装） |
| StatefulSet 模板 | 平台 chart（`charts/app/workload`、`charts/components/*`）里 `persistence.storageClass` 留空即走默认，不要写死 |

## 9. 风险

- **停机窗口**：每块 PVC 都要停消费方；SeaweedFS（对象存储）停机要提前通知所有租户。
- **`reclaimPolicy: Delete`**：新 PVC 一旦建错，删掉就会把目录一起删。操作期间一律先 Retain 再删。
- **operator 自管对象**：ES / CNPG 删 PVC 会被 controller 秒补建（还是 nfs-client），必须先停 controller 或缩 CR。
- **权限差异**：见 §5，先做一次非 root 写入探针。
- **`test-work-cluster`（192.168.85.128）当前连不上**；本方案按 `coding-cluster` 写，换集群要重扫清单。
