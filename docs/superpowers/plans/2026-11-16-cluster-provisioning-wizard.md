# 集群安装向导（kubespray 配置生成器）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在平台前端提供「新建集群」向导：填参数 → 校验 → 生成可直接放进 kubespray v2.32.0 执行的 inventory / group_vars / 离线配置 / 操作手册。

**Architecture:** 纯前端。纯函数层（类型、支持矩阵、校验、生成）放在 `src/utils/clusterInstall/`，用 Node 内置 test runner 做零依赖单测；展示层是 `src/views/ClusterInstallView.vue` + `src/components/cluster/install/` 三个组件；不调用任何后端接口。

**Tech Stack:** Vue 3 + TypeScript + Element Plus + Vite；Node 22 内置 `--experimental-strip-types --test`。

**Spec:** `docs/superpowers/specs/2026-11-16-cluster-provisioning-wizard-design.md`

## Global Constraints

- 只改 `platform-web`：不新增后端端点、不加 SQL 迁移、不加权限码；路由权限复用 `pageCodes.cluster`。
- 不新增依赖（运行时或开发期）。测试用 Node 内置 test runner：`node --experimental-strip-types --test <file>`（Node 24 可去掉该 flag）。
- 引擎固定 kubespray **v2.32.0**；K8s 版本范围 **v1.34.0–v1.36.4**，默认 **v1.36.4**（取自该版本 `kubelet_checksums`）。
- 生成 YAML 的每个键必须属于 `KubesprayVars` 联合类型；禁止在模板字符串里手写键名。
- 在线/离线共用同一份 inventory；离线只多一个 `group_vars/all/offline.yml`（Ansible 自动加载，无需 `-e`）。
- YAML 展示统一走 `YamlBlock`；文件下载用 `Blob` + `<a download>`。
- 文案中文，技术名词保留英文。
- 提交步骤只在用户明确同意后执行；未授权时跳过 commit 并说明。

## Review Focus

- **变量名拼错**：生成的 `group_vars` 键不在 kubespray 里 → Ansible 静默忽略、落到默认值。由 `KubesprayVars` 类型 + Task 2 的测试兜住。
- **Pod CIDR 与 Calico 安装不一致**：CNI=平台管理时，Pod CIDR 必须与之后平台装 Calico 时填的值一致，否则断网。Task 1 校验 + Task 2 README 断言兜住。
- **角色组合产生空组**：没有控制面、或没有任何 `kube_node` 成员 → kubespray 启动失败。Task 1 测试兜住。
- **外部 etcd 被当成同机 etcd**：会装出两套 etcd。Task 2 断言「外部模式不生成 etcd 组」+ Task 1 给 warning。
- **单 master 生成 VIP / 多控制面配置**：无效配置。Task 1 校验 + Task 2 断言「单 master 不出现 `kube_vip_*`」。

---

### Task 1: 表单模型、支持矩阵与校验

**Files:**
- Create: `platform-web/src/utils/clusterInstall/types.ts`
- Create: `platform-web/src/utils/clusterInstall/kubespray.ts`
- Create: `platform-web/src/utils/clusterInstall/validate.ts`
- Test: `platform-web/src/utils/clusterInstall/__tests__/validate.test.ts`
- Modify: `platform-web/package.json`（加 `test:install-plan` 脚本）

**Interfaces:**
- Consumes: 无
- Produces:
  - `types.ts` 导出 `Arch`、`Runtime`、`EtcdMode`、`ApiEntryMode`、`CniMode`、`KubeProxyMode`、`NodeRole`、`InstallNode`、`InstallForm`、`Issue`、`InstallFile`
  - `kubespray.ts` 导出 `KUBESPRAY_VERSION = 'v2.32.0'`、`KUBE_VERSIONS: readonly string[]`、`DEFAULT_KUBE_VERSION = 'v1.36.4'`、`OS_OPTIONS: readonly {value,label,family}[]`、`KubesprayVars`
  - `validate.ts` 导出 `validate(form: InstallForm): Issue[]`

- [ ] **Step 1: 写失败的测试**

`platform-web/src/utils/clusterInstall/__tests__/validate.test.ts`：

```ts
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { validate } from '../validate.ts'
import type { InstallForm, InstallNode } from '../types.ts'

function node(hostname: string, ip: string, roles: InstallNode['roles']): InstallNode {
  return { hostname, ip, sshPort: 22, sshUser: 'root', roles }
}

function base(overrides: Partial<InstallForm> = {}): InstallForm {
  return {
    clusterName: 'demo', os: 'rocky9', arch: 'amd64', kubeVersion: 'v1.36.4',
    runtime: 'containerd',
    nodes: [node('cp-1', '10.0.0.11', ['control-plane', 'worker'])],
    etcdMode: 'stacked', externalEtcd: '',
    apiEntry: 'direct', vip: '', vipInterface: '', bgpAs: '', bgpPeers: '', bgpRouterId: '',
    lbAddress: '', lbPort: 6443, lbDomain: '',
    cni: 'platform', podCidr: '10.233.64.0/18', serviceCidr: '10.233.0.0/18',
    clusterDomain: 'cluster.local', kubeProxyMode: 'ipvs', nodeSubnet: '',
    online: true, imageRegistry: '', filesRepo: '', registryHost: '',
    httpProxy: '', httpsProxy: '', noProxyExtra: '',
    ...overrides,
  }
}

const errors = (f: InstallForm) => validate(f).filter((i) => i.level === 'error')

test('单 master 兼 worker 合法', () => {
  assert.equal(errors(base()).length, 0)
})

test('两台控制面非法', () => {
  const f = base({ nodes: [node('cp-1', '10.0.0.11', ['control-plane']), node('cp-2', '10.0.0.12', ['control-plane', 'worker'])] })
  assert.ok(errors(f).some((i) => i.field === 'nodes'))
})

test('多控制面没有 API 入口非法', () => {
  const f = base({ nodes: [node('cp-1', '10.0.0.11', ['control-plane', 'worker']), node('cp-2', '10.0.0.12', ['control-plane']), node('cp-3', '10.0.0.13', ['control-plane'])], apiEntry: 'direct' })
  assert.ok(errors(f).some((i) => i.field === 'apiEntry'))
})

test('专用 etcd 与控制面重叠非法', () => {
  const f = base({ etcdMode: 'dedicated', nodes: [node('cp-1', '10.0.0.11', ['control-plane', 'worker', 'etcd']), node('etcd-1', '10.0.0.21', ['etcd'])] })
  assert.ok(errors(f).some((i) => i.field === 'etcdMode' || i.field === 'nodes'))
})

test('节点 IP 重复非法', () => {
  const f = base({ nodes: [node('cp-1', '10.0.0.11', ['control-plane', 'worker']), node('w-1', '10.0.0.11', ['worker'])] })
  assert.ok(errors(f).some((i) => i.field === 'nodes'))
})

test('平台管 CNI 但 Pod CIDR 为空非法', () => {
  assert.ok(errors(base({ podCidr: '' })).some((i) => i.field === 'podCidr'))
})

test('没有任何 worker 角色非法', () => {
  assert.ok(errors(base({ nodes: [node('cp-1', '10.0.0.11', ['control-plane'])] })).some((i) => i.field === 'nodes'))
})

test('没有控制面非法', () => {
  assert.ok(errors(base({ nodes: [node('w-1', '10.0.0.21', ['worker'])] })).some((i) => i.field === 'nodes'))
})

test('单 master 选 kube-vip 非法', () => {
  assert.ok(errors(base({ apiEntry: 'kubevip-arp', vip: '10.0.0.200' })).some((i) => i.field === 'apiEntry'))
})

test('外部 etcd 给出 warning', () => {
  assert.ok(validate(base({ etcdMode: 'external', externalEtcd: 'https://10.0.0.31:2379' })).some((i) => i.level === 'warning'))
})
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd platform-web && node --experimental-strip-types --test src/utils/clusterInstall/__tests__/validate.test.ts`
Expected: FAIL — `Cannot find module '../validate.ts'`

- [ ] **Step 3: 实现类型、支持矩阵与校验**

`types.ts` 按 Interfaces 定义；`InstallForm` 字段与上面测试里的对象完全一致。

`kubespray.ts`：

- `KUBE_VERSIONS` 写入从 kubespray v2.32.0 `kubelet_checksums` 取出的全部 26 个版本（`v1.36.4`…`v1.34.0`，每组 minor 降序），`DEFAULT_KUBE_VERSION = 'v1.36.4'`。
- `OS_OPTIONS`：Ubuntu 22.04/24.04、Debian 12/13、Rocky 9/10、Alma 9/10、CentOS Stream 9/10，标注 rpm/deb；实施时对照 `roles/bootstrap_os` 删掉 v2.32 不支持的项。
- `KubesprayVars`：可选键的精确集合——`kube_version`、`container_manager`、`kube_network_plugin`、`kube_pods_subnet`、`kube_service_addresses`、`cluster_name`、`kube_proxy_mode`、`kube_proxy_strict_arp`、`kube_vip_enabled`、`kube_vip_controlplane_enabled`、`kube_vip_address`、`kube_vip_interface`、`kube_vip_arp_enabled`、`kube_vip_bgp_enabled`、`kube_vip_local_as`、`kube_vip_bgp_routerid`、`kube_vip_bgppeers`、`loadbalancer_apiserver`、`apiserver_loadbalancer_domain_name`、`etcd_deployment_type`、`etcd_access_addresses`、`http_proxy`、`https_proxy`、`no_proxy`、`files_repo`、`registry_host`、`kube_image_repo`、`gcr_image_repo`、`docker_image_repo`、`quay_image_repo`、`download_run_once`。`loadbalancer_apiserver` 类型为 `{ address: string; port: number }`，`kube_vip_bgppeers` 为 `string[]`。

`validate.ts` 实现 Spec §6 的规则，返回 `{ field, level, message }`；至少覆盖上面 8 个测试。多控制面缺入口、数量非法、角色空组、etcd 重叠、IP 重复、CIDR 为空、外部 etcd warning。

- [ ] **Step 4: 运行测试确认通过**

Run: `cd platform-web && node --experimental-strip-types --test src/utils/clusterInstall/__tests__/validate.test.ts`
Expected: PASS（10 tests）

- [ ] **Step 5: 加 npm 脚本并跑一次**

`package.json` 增加：

```json
"test:install-plan": "node --experimental-strip-types --test src/utils/clusterInstall/__tests__/validate.test.ts"
```

Run: `cd platform-web && npm run test:install-plan`
Expected: PASS

- [ ] **Step 6: 提交（经用户同意后）**

```bash
git add platform-web/src/utils/clusterInstall platform-web/package.json
git commit -m "feat(cluster-install): 表单模型、支持矩阵与校验"
```

---

### Task 2: 产物生成

**Files:**
- Create: `platform-web/src/utils/clusterInstall/yaml.ts`
- Create: `platform-web/src/utils/clusterInstall/generate.ts`
- Test: `platform-web/src/utils/clusterInstall/__tests__/generate.test.ts`
- Modify: `platform-web/package.json`（`test:install-plan` 追加第二个测试文件）

**Interfaces:**
- Consumes: Task 1 的 `InstallForm`、`KubesprayVars`、`InstallFile`
- Produces: `generate(form: InstallForm): InstallFile[]`、`yamlDoc(obj: Partial<Record<string, YamlValue>>): string`

- [ ] **Step 1: 写失败的测试**

`generate.test.ts`（复用 Task 1 的 `base()`/`node()` 辅助函数，复制到本文件）：

```ts
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { generate } from '../generate.ts'
import type { InstallForm, InstallNode } from '../types.ts'

// base() / node() 同 validate.test.ts

function file(form: InstallForm, path: string): string {
  const hit = generate(form).find((f) => f.path === `inventory/${form.clusterName}/${path}`)
  assert.ok(hit, `missing ${path}`)
  return hit.content
}

test('单 master 兼 worker：两个组都进，且没有 etcd 组', () => {
  const hosts = file(base(), 'hosts.yaml')
  assert.match(hosts, /kube_control_plane:/)
  assert.match(hosts, /kube_node:/)
  assert.match(hosts, /cp-1:/)
  assert.doesNotMatch(hosts, /^    etcd:/m)
  assert.equal(generate(base()).some((f) => f.path.endsWith('/group_vars/all/etcd.yml')), false)
})

test('3 master + ARP：VIP、LB、strictARP 都在', () => {
  const f = base({
    nodes: [node('cp-1', '10.0.0.11', ['control-plane']), node('cp-2', '10.0.0.12', ['control-plane']), node('cp-3', '10.0.0.13', ['control-plane']), node('w-1', '10.0.0.21', ['worker'])],
    apiEntry: 'kubevip-arp', vip: '10.0.0.200',
  })
  const all = file(f, 'group_vars/all/all.yml')
  assert.match(all, /kube_vip_enabled: true/)
  assert.match(all, /kube_vip_address: "10\.0\.0\.200"/)
  assert.match(all, /loadbalancer_apiserver:/)
  const net = file(f, 'group_vars/k8s_cluster/k8s-cluster.yml')
  assert.match(net, /kube_proxy_strict_arp: true/)
})

test('专用 etcd：独立组、且不进 k8s_cluster', () => {
  const f = base({
    etcdMode: 'dedicated',
    nodes: [node('cp-1', '10.0.0.11', ['control-plane']), node('cp-2', '10.0.0.12', ['control-plane']), node('cp-3', '10.0.0.13', ['control-plane']), node('etcd-1', '10.0.0.31', ['etcd']), node('etcd-2', '10.0.0.32', ['etcd']), node('etcd-3', '10.0.0.33', ['etcd']), node('w-1', '10.0.0.21', ['worker'])],
    apiEntry: 'kubevip-arp', vip: '10.0.0.200',
  })
  const hosts = file(f, 'hosts.yaml')
  assert.match(hosts, /etcd-1:/)
  const k8sClusterBlock = hosts.slice(hosts.indexOf('k8s_cluster:'))
  assert.doesNotMatch(k8sClusterBlock, /etcd-1:/)
  assert.match(file(f, 'group_vars/all/etcd.yml'), /etcd_deployment_type: "host"/)
})

test('外部 etcd：不生成 etcd 组，写 endpoints', () => {
  const f = base({ etcdMode: 'external', externalEtcd: 'https://10.0.0.31:2379,https://10.0.0.32:2379' })
  assert.doesNotMatch(file(f, 'hosts.yaml'), /^    etcd:/m)
  assert.match(file(f, 'group_vars/all/etcd.yml'), /etcd_access_addresses: "https:\/\/10\.0\.0\.31:2379,https:\/\/10\.0\.0\.32:2379"/)
})

test('离线：生成 offline.yml；在线不生成', () => {
  const off = base({ online: false, filesRepo: 'http://10.0.0.5/kubespray', registryHost: 'harbor.local/k8s' })
  assert.match(file(off, 'group_vars/all/offline.yml'), /files_repo: "http:\/\/10\.0\.0\.5\/kubespray"/)
  assert.match(file(off, 'group_vars/all/offline.yml'), /registry_host: "harbor\.local\/k8s"/)
  assert.equal(generate(base({ online: true })).some((x) => x.path.endsWith('/offline.yml')), false)
})

test('平台管 CNI 生成 cni；Calico 生成 calico', () => {
  assert.match(file(base(), 'group_vars/k8s_cluster/k8s-cluster.yml'), /kube_network_plugin: "cni"/)
  assert.match(file(base({ cni: 'calico' }), 'group_vars/k8s_cluster/k8s-cluster.yml'), /kube_network_plugin: "calico"/)
})

test('README 写明 Pod CIDR 与 Calico 一致', () => {
  const readme = file(base(), 'README.md')
  assert.match(readme, /10\.233\.64\.0\/18/)
  assert.match(readme, /Calico/)
})
```

- [ ] **Step 2: 运行确认失败**

Run: `cd platform-web && node --experimental-strip-types --test src/utils/clusterInstall/__tests__/generate.test.ts`
Expected: FAIL — `Cannot find module '../generate.ts'`

- [ ] **Step 3: 实现 YAML 序列化与生成器**

`yaml.ts`：`yamlDoc` 支持标量、数组、嵌套对象；字符串一律 `JSON.stringify`（YAML 合法），数字/布尔原样；忽略 `undefined`；每个顶级键一行，嵌套缩进 2 空格。

`generate.ts` 产出（路径前缀 `inventory/<clusterName>/`）：

- `hosts.yaml`：`all.hosts` 每个节点写 `ansible_host`/`ip`/`access_ip`（值加引号）、`ansible_port`、`ansible_user`；组 `kube_control_plane`（全部控制面）、`kube_node`（所有含 worker 角色的节点，含兼 worker 的控制面）、`etcd`（仅 dedicated 模式）、`k8s_cluster.children`（`kube_control_plane` + `kube_node`，**不含 etcd**）。
- `group_vars/k8s_cluster/k8s-cluster.yml`：`kube_version`、`container_manager`、`kube_network_plugin`、`kube_pods_subnet`、`kube_service_addresses`、`cluster_name`、`kube_proxy_mode`、`kube_proxy_strict_arp`。
- `group_vars/all/all.yml`：VIP/外部 LB/代理。`kubevip-arp` → `kube_vip_enabled: true`、`kube_vip_controlplane_enabled: true`、`kube_vip_address`、`kube_vip_arp_enabled: true`、`loadbalancer_apiserver: {address: VIP, port: 6443}`；`kubevip-bgp` → `kube_vip_bgp_enabled: true`、`kube_vip_local_as`、`kube_vip_bgp_routerid`、`kube_vip_bgppeers`（每个 peer 生成 `<ip>:<as>::false`）；`external-lb` → `loadbalancer_apiserver: {address, port}` + 可选 `apiserver_loadbalancer_domain_name`；`direct` → 不写 VIP/LB 键。`ipvs + kubevip-arp` 时 `kube_proxy_strict_arp: true`。代理三项非空才写。
- `group_vars/all/etcd.yml`：`dedicated` → `etcd_deployment_type: "host"`；`external` → `etcd_deployment_type: "host"` + `etcd_access_addresses: "<原样>"`；`stacked` → 不生成该文件。
- `group_vars/all/offline.yml`：`files_repo`、`registry_host`、四个 `*_image_repo` 指向 `registry_host`、`download_run_once: true`。
- `README.md`：kubespray 版本、把目录拷进 checkout 的命令、在线/离线差异、Pod CIDR 的值与「装 Calico 时填同一个值」的提示、链到 `docs/development/cluster-prerequisites.md`。

- [ ] **Step 4: 运行确认通过**

Run: `cd platform-web && node --experimental-strip-types --test src/utils/clusterInstall/__tests__/generate.test.ts`
Expected: PASS（7 tests）

- [ ] **Step 5: 更新 npm 脚本并跑全部**

```json
"test:install-plan": "node --experimental-strip-types --test src/utils/clusterInstall/__tests__/validate.test.ts src/utils/clusterInstall/__tests__/generate.test.ts"
```

Run: `cd platform-web && npm run test:install-plan`
Expected: PASS（17 tests）

- [ ] **Step 6: 提交（经用户同意后）**

```bash
git add platform-web/src/utils/clusterInstall platform-web/package.json
git commit -m "feat(cluster-install): 生成 kubespray inventory 与 group_vars"
```

---

### Task 3: 表单状态与节点表

**Files:**
- Create: `platform-web/src/composables/useClusterInstallPlan.ts`
- Create: `platform-web/src/components/cluster/install/NodeTable.vue`

**Interfaces:**
- Consumes: Task 1/2 的 `InstallForm`、`validate`、`generate`、`KUBE_VERSIONS`、`OS_OPTIONS`
- Produces:
  - `useClusterInstallPlan(): { form: InstallForm; issues: ComputedRef<Issue[]>; errors: ComputedRef<Issue[]>; warnings: ComputedRef<Issue[]>; artifacts: ComputedRef<InstallFile[]> }`
  - `NodeTable` props `{ modelValue: InstallNode[] }`，emits `update:modelValue`

- [ ] **Step 1: 实现 composable**

`createDefaultForm()`：`clusterName: ''`、`os: 'rocky9'`、`arch: 'amd64'`、`kubeVersion: DEFAULT_KUBE_VERSION`、`runtime: 'containerd'`、3 行 `cp-1..3`（角色 `control-plane`）+ 1 行 `worker-1`（角色 `worker`）、`etcdMode: 'stacked'`、`apiEntry: 'kubevip-arp'`、`cni: 'platform'`、Pod/Service CIDR 用 Spec 默认值、其余空/默认。
`useClusterInstallPlan` 用 `reactive` 持有表单，`computed` 出 `issues`/`artifacts`，零请求。

- [ ] **Step 2: 实现 NodeTable**

`el-table` + 可编辑单元格：hostname（input）、IP（input）、SSH 端口（input-number）、SSH 用户（input）、角色（`el-checkbox-group`：control-plane / worker / etcd）。底部「添加节点 / 删除」。`defineModel<InstallNode[]>()` 或 `modelValue` + emit（用项目里现有的写法，参考 `src/components/namespace/QuotaSection.vue`）。

- [ ] **Step 3: 类型检查**

Run: `cd platform-web && npm run type-check`
Expected: 0 errors

- [ ] **Step 4: 隔离挂载验证（Approach B）**

`preview_start` 用 `main-dev-check`（端口 5199）；在预览里按 Approach B 直接挂 `NodeTable.vue`：`import('/node_modules/.vite/deps/vue.js')` + `import('/src/components/cluster/install/NodeTable.vue')`，`createApp` 挂到 `document.body`（Element Plus CSS 已全局加载）。断言：4 行渲染、勾选角色后 emit 的数组变化、添加/删除行生效。不启动整页、不装 XHR stub。预览面板可能在两次 eval 之间重载，断言前先确认组件实例还在。

- [ ] **Step 5: 提交（经用户同意后）**

```bash
git add platform-web/src/composables/useClusterInstallPlan.ts platform-web/src/components/cluster/install/NodeTable.vue
git commit -m "feat(cluster-install): 表单状态与节点表"
```

---

### Task 4: 拓扑预览与产物面板

**Files:**
- Create: `platform-web/src/components/cluster/install/TopologyPreview.vue`
- Create: `platform-web/src/components/cluster/install/ArtifactPanel.vue`

**Interfaces:**
- Consumes: `InstallForm`、`InstallFile`
- Produces: `TopologyPreview` props `{ form: InstallForm }`；`ArtifactPanel` props `{ files: InstallFile[] }`

- [ ] **Step 1: 实现 TopologyPreview**

纯 SVG（不引图库）：VIP 节点（有入口时）+ 控制面列 + worker 列 + etcd 列（专用模式）；节点框显示 hostname/IP；etcd 与控制面同机时在控制面框内标 `etcd`。颜色用 Element Plus 语义变量，亮/暗主题都成立。

- [ ] **Step 2: 实现 ArtifactPanel**

文件列表（`el-tabs` 或左侧列表 + 右侧内容）；`.yaml/.yml` 用 `YamlBlock` 渲染，`.md` 用只读 `<pre>`；每个文件一个「下载」按钮（`Blob` + `URL.createObjectURL` + `<a download>`，下载后 `revokeObjectURL`）；复制沿用 `YamlBlock` 自带按钮。

- [ ] **Step 3: 类型检查**

Run: `cd platform-web && npm run type-check`
Expected: 0 errors

- [ ] **Step 4: 隔离挂载验证**

挂 `TopologyPreview`：单 master、3 master + 专用 etcd 两种形态，断言节点框数量与 etcd 标注。挂 `ArtifactPanel`：断言文件数、选中 `hosts.yaml` 时出现 `hljs-attr`、下载按钮存在。

- [ ] **Step 5: 提交（经用户同意后）**

```bash
git add platform-web/src/components/cluster/install/TopologyPreview.vue platform-web/src/components/cluster/install/ArtifactPanel.vue
git commit -m "feat(cluster-install): 拓扑预览与产物面板"
```

---

### Task 5: 页面、路由与入口

**Files:**
- Create: `platform-web/src/views/ClusterInstallView.vue`
- Modify: `platform-web/src/router/index.ts`（`clusters` 路由之后加一条 hidden 路由）
- Modify: `platform-web/src/views/ClusterView.vue`（工具条按钮；对话框标题）

**Interfaces:**
- Consumes: Task 3/4 的 composable 与三个组件
- Produces: 路由 `name: 'cluster-install'`、`path: 'clusters/new'`

- [ ] **Step 1: 加路由**

```ts
{ path: 'clusters/new', name: 'cluster-install', component: () => import('@/views/ClusterInstallView.vue'), meta: { title: '新建集群', domain: 'cluster', nav: 'hidden', requiresPerm: [pageCodes.cluster], keywords: ['install', 'kubespray', 'bootstrap', '部署集群'] } },
```

- [ ] **Step 2: 实现页面**

`PageHeader`（标题「新建集群」，描述一句：生成 kubespray 部署包，执行由运维在节点侧完成）+ `el-steps` 四步（基础 / 节点 / 网络与入口 / 介质）；左侧按步渲染表单分区，右侧 sticky：`TopologyPreview` + 错误/警告列表 + `ArtifactPanel`。底部「上一步 / 下一步 / 生成产物」；有 error 时禁用生成并滚动到第一条。生成后在当前页展示产物（不跳转、不落库）。

四步的字段（全部来自 `InstallForm`，一个不能少）：

- **基础**：`clusterName`、`kubeVersion`（`KUBE_VERSIONS`，按 minor 分组）、`os`（`OS_OPTIONS`）、`arch`、`runtime`
- **节点**：`NodeTable`
- **网络与入口**：`podCidr`、`serviceCidr`、`clusterDomain`、`kubeProxyMode`、`nodeSubnet`（可选）；`apiEntry` 下拉 + 对应字段（ARP：`vip`/`vipInterface`；BGP：`vip`/`bgpAs`/`bgpPeers`/`bgpRouterId`；外部 LB：`lbAddress`/`lbPort`/`lbDomain`）；`cni` 下拉
- **介质**：`online` 开关；在线 `imageRegistry`；离线 `filesRepo`/`registryHost`；代理 `httpProxy`/`httpsProxy`/`noProxyExtra`

- [ ] **Step 3: 改集群列表入口**

`ClusterView.vue` 工具条：

```vue
<el-button type="primary" @click="openCreate">接入集群</el-button>
<el-button type="primary" plain @click="router.push('/clusters/new')">新建集群</el-button>
<el-button @click="load">刷新</el-button>
```

弹窗标题改为 `:title="isEdit ? '编辑集群' : '接入集群'"`。命令面板的 `create` 仍指向接入弹窗，不改。

- [ ] **Step 4: 全量构建**

Run: `cd platform-web && npm run build`
Expected: 成功；无新增依赖（`package.json` 只有脚本变化）

- [ ] **Step 5: 整页验证（真实路由 + 假 token）**

`preview_start` → `preview_resize` 给 1440×900 → `location.reload()` → 写 `localStorage.platform_access_token` → `usePermission().load()` → 真实 `$router.push('/clusters/new')`。断言：
1. 四步表单与右侧拓扑渲染；
2. 填 3 个控制面 IP + VIP 后错误清空、产物面板出现 4 个文件（`hosts.yaml`、`k8s-cluster.yml`、`all.yml`、`README.md`）；
3. 把控制面改成 2 台，错误列表出现「控制面」；
4. 切到离线，产物多出 `offline.yml`；
5. 切 `html.dark` 再截图，对比色与可读性；
6. 页面未发出任何 `/api/**` 请求（stub 记录为空）。

- [ ] **Step 6: 提交（经用户同意后）**

```bash
git add platform-web/src/views/ClusterInstallView.vue platform-web/src/router/index.ts platform-web/src/views/ClusterView.vue
git commit -m "feat(cluster-install): 新建集群向导页面与入口"
```

---

### Task 6: 最终验收

**Files:** 无新增

- [ ] **Step 1: 跑单测与构建**

Run: `cd platform-web && npm run test:install-plan && npm run build`
Expected: 17 tests PASS；build 成功

- [ ] **Step 2: 四个样例的产物人工核对**

用页面或 Node 直接调 `generate`，分别生成 Spec §9 的四种组合；把输出目录拷进 kubespray v2.32.0 checkout，跑：

```bash
ansible-playbook -i inventory/<集群名>/hosts.yaml --syntax-check cluster.yml
```

并抽查 `group_vars` 键名与本计划 `KubesprayVars` 一致（syntax-check 不校验键名）。

- [ ] **Step 3: 记录结果**

把四组校验结论与任何偏差写回 Spec §9；偏差属于实现问题就修，属于 kubespray 支持范围就改 `kubespray.ts` 矩阵。
