<script setup lang="ts">
/**
 * 新建集群向导：纯前端生成 kubespray 部署包，不执行、不落库、不发请求。
 * 左侧四步表单，右侧拓扑 + 校验 + 产物。
 *
 * 路由是**免登录**的（/clusters/new 挂在 PublicLayout 下），所以这里不能依赖权限、
 * 租户上下文或任何后端接口；只有已登录用户才给「返回集群列表」。
 */
import { computed, nextTick, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import PageHeader from '@/components/PageHeader.vue'
import NodeTable from '@/components/cluster/install/NodeTable.vue'
import TopologyPreview from '@/components/cluster/install/TopologyPreview.vue'
import ArtifactPanel from '@/components/cluster/install/ArtifactPanel.vue'
import { useClusterInstallPlan } from '@/composables/useClusterInstallPlan'
import { KUBE_VERSIONS, OS_OPTIONS } from '@/utils/clusterInstall/kubespray'
import { getAccessToken } from '@/auth/oauth'

const router = useRouter()
const { form, issues, errors, warnings, artifacts } = useClusterInstallPlan()
const loggedIn = Boolean(getAccessToken())

const STEPS = ['基础', '节点', '网络与入口', '介质'] as const
const step = ref(0)
const generated = ref(false)
const issuesRef = ref<HTMLElement | null>(null)
const artifactRef = ref<HTMLElement | null>(null)

const kubeVersionGroups = computed(() => {
  const groups = new Map<string, string[]>()
  for (const v of KUBE_VERSIONS) {
    const minor = v.split('.').slice(0, 2).join('.')
    const list = groups.get(minor) ?? []
    list.push(v)
    groups.set(minor, list)
  }
  return [...groups.entries()].map(([minor, versions]) => ({ minor, versions }))
})

/** 表单合法就主动展示产物；点「生成产物」也强制展示 */
const showArtifacts = computed(() => generated.value || errors.value.length === 0)

const CNI_OPTIONS = [
  { value: 'platform', label: '平台管理（安装后到组件页装 Calico）' },
  { value: 'calico', label: 'kubespray 安装 Calico' },
  { value: 'cilium', label: 'kubespray 安装 Cilium' },
  { value: 'flannel', label: 'kubespray 安装 flannel' },
] as const

function next(): void {
  if (step.value < STEPS.length - 1) step.value += 1
}

function prev(): void {
  if (step.value > 0) step.value -= 1
}

function generateArtifacts(): void {
  if (errors.value.length) {
    ElMessage.warning(`还有 ${errors.value.length} 个错误，先按右侧提示处理`)
    issuesRef.value?.scrollIntoView({ behavior: 'smooth', block: 'center' })
    return
  }
  generated.value = true
  void nextTick(() => artifactRef.value?.scrollIntoView({ behavior: 'smooth', block: 'start' }))
}
</script>

<template>
  <div class="install">
    <PageHeader title="新建集群" description="在浏览器本地生成 kubespray 部署包 —— 不登录、不上传、不发请求；执行由运维在节点侧完成。">
      <el-button v-if="loggedIn" @click="router.push('/clusters')">返回集群列表</el-button>
    </PageHeader>

    <el-steps :active="step" finish-status="success" align-center class="install__steps">
      <el-step v-for="s in STEPS" :key="s" :title="s" />
    </el-steps>

    <div class="install__grid">
      <section class="install__form">
        <el-form label-width="130px" label-position="right">
          <!-- 1. 基础 -->
          <template v-if="step === 0">
            <el-form-item label="集群名称" required>
              <el-input v-model="form.clusterName" placeholder="例如 prod-cluster-1" style="max-width: 320px" />
            </el-form-item>
            <el-form-item label="Kubernetes 版本">
              <el-select v-model="form.kubeVersion" style="width: 260px">
                <el-option-group v-for="g in kubeVersionGroups" :key="g.minor" :label="g.minor">
                  <el-option v-for="v in g.versions" :key="v" :label="`v${v}`" :value="v" />
                </el-option-group>
              </el-select>
            </el-form-item>
            <el-form-item label="操作系统">
              <el-select v-model="form.os" style="width: 260px">
                <el-option v-for="o in OS_OPTIONS" :key="o.value" :label="`${o.label}（${o.family}）`" :value="o.value" />
              </el-select>
            </el-form-item>
            <el-form-item label="架构">
              <el-radio-group v-model="form.arch">
                <el-radio value="amd64">amd64</el-radio>
                <el-radio value="arm64">arm64</el-radio>
              </el-radio-group>
            </el-form-item>
            <el-form-item label="容器运行时">
              <el-radio-group v-model="form.runtime">
                <el-radio value="containerd">containerd</el-radio>
                <el-radio value="crio">CRI-O</el-radio>
              </el-radio-group>
            </el-form-item>
          </template>

          <!-- 2. 节点 -->
          <template v-else-if="step === 1">
            <NodeTable v-model="form.nodes" />

            <el-divider content-position="left">etcd</el-divider>
            <el-form-item label="etcd 模式">
              <el-radio-group v-model="form.etcdMode">
                <el-radio value="stacked">同机（控制面即 etcd）</el-radio>
                <el-radio value="dedicated">专用 etcd</el-radio>
                <el-radio value="external">外部托管（实验）</el-radio>
              </el-radio-group>
            </el-form-item>
            <el-form-item v-if="form.etcdMode === 'dedicated'" label="提示">
              <span class="install__hint">把 etcd 角色的节点勾到 3 或 5 台；它们不能兼控制面或 worker。</span>
            </el-form-item>
            <el-form-item v-if="form.etcdMode === 'external'" label="endpoints">
              <el-input
                v-model="form.externalEtcd"
                placeholder="https://10.0.0.31:2379,https://10.0.0.32:2379"
                style="max-width: 520px"
              />
            </el-form-item>
          </template>

          <!-- 3. 网络与入口 -->
          <template v-else-if="step === 2">
            <el-form-item label="Pod CIDR">
              <el-input v-model="form.podCidr" style="width: 280px" />
            </el-form-item>
            <el-form-item label="Service CIDR">
              <el-input v-model="form.serviceCidr" style="width: 280px" />
            </el-form-item>
            <el-form-item label="clusterDomain">
              <el-input v-model="form.clusterDomain" style="width: 280px" />
            </el-form-item>
            <el-form-item label="kube-proxy 模式">
              <el-select v-model="form.kubeProxyMode" style="width: 200px">
                <el-option label="ipvs" value="ipvs" />
                <el-option label="iptables" value="iptables" />
                <el-option label="nftables" value="nftables" />
              </el-select>
            </el-form-item>
            <el-form-item label="节点网段">
              <el-input v-model="form.nodeSubnet" placeholder="可选，例如 10.0.0.0/24；填了才做重叠校验与 no_proxy 补全" style="max-width: 420px" />
            </el-form-item>

            <el-divider content-position="left">API 入口</el-divider>
            <el-form-item label="入口方式">
              <el-select v-model="form.apiEntry" style="width: 260px">
                <el-option label="kube-vip ARP" value="kubevip-arp" />
                <el-option label="kube-vip BGP" value="kubevip-bgp" />
                <el-option label="外部 LB" value="external-lb" />
                <el-option label="单 master 直连" value="direct" />
              </el-select>
            </el-form-item>

            <template v-if="form.apiEntry === 'kubevip-arp' || form.apiEntry === 'kubevip-bgp'">
              <el-form-item label="VIP">
                <el-input v-model="form.vip" placeholder="例如 10.0.0.200" style="width: 240px" />
              </el-form-item>
              <el-form-item v-if="form.apiEntry === 'kubevip-arp'" label="VIP 网卡">
                <el-input v-model="form.vipInterface" placeholder="可选，例如 ens160" style="width: 240px" />
              </el-form-item>
              <template v-else>
                <el-form-item label="本地 AS">
                  <el-input v-model="form.bgpAs" placeholder="例如 65000" style="width: 160px" />
                </el-form-item>
                <el-form-item label="BGP Router ID">
                  <el-input v-model="form.bgpRouterId" placeholder="可选，例如 192.168.0.2" style="width: 240px" />
                </el-form-item>
                <el-form-item label="BGP peers">
                  <el-input
                    v-model="form.bgpPeers"
                    type="textarea"
                    :rows="3"
                    placeholder="每行一个，格式 <ip>:<as>，例如 192.168.0.10:65000"
                    style="max-width: 360px"
                  />
                </el-form-item>
              </template>
            </template>

            <template v-if="form.apiEntry === 'external-lb'">
              <el-form-item label="LB 地址">
                <el-input v-model="form.lbAddress" placeholder="例如 10.0.0.100" style="width: 240px" />
              </el-form-item>
              <el-form-item label="LB 端口">
                <el-input-number v-model="form.lbPort" :min="1" :max="65535" :controls="false" style="width: 160px" />
              </el-form-item>
              <el-form-item label="LB 域名">
                <el-input v-model="form.lbDomain" placeholder="可选，apiserver 的域名" style="width: 280px" />
              </el-form-item>
            </template>

            <el-divider content-position="left">CNI</el-divider>
            <el-form-item label="CNI">
              <el-select v-model="form.cni" style="width: 340px">
                <el-option v-for="o in CNI_OPTIONS" :key="o.value" :label="o.label" :value="o.value" />
              </el-select>
            </el-form-item>
            <el-alert
              v-if="form.cni === 'platform'"
              type="info"
              :closable="false"
              show-icon
              title="安装后到组件页装 Calico 时，Pod CIDR 必须填下面这个值，写错会立即断网。"
            />
            <el-alert
              v-else
              type="warning"
              :closable="false"
              show-icon
              title="非平台管理的 CNI：平台的 Calico 地址池 / BGP 相关能力将不可用。"
            />
          </template>

          <!-- 4. 介质 -->
          <template v-else>
            <el-form-item label="部署模式">
              <el-radio-group v-model="form.online">
                <el-radio :value="true">在线</el-radio>
                <el-radio :value="false">离线</el-radio>
              </el-radio-group>
            </el-form-item>
            <el-form-item v-if="form.online" label="镜像源">
              <el-input v-model="form.imageRegistry" placeholder="留空 = 官方源；可填内网 registry / 加速地址" style="max-width: 420px" />
            </el-form-item>
            <template v-else>
              <el-form-item label="静态文件仓库">
                <el-input v-model="form.filesRepo" placeholder="例如 http://10.0.0.5/kubespray" style="max-width: 420px" />
              </el-form-item>
              <el-form-item label="内网 registry">
                <el-input v-model="form.registryHost" placeholder="例如 harbor.local/k8s" style="max-width: 420px" />
              </el-form-item>
            </template>

            <el-divider content-position="left">代理</el-divider>
            <el-form-item label="http_proxy">
              <el-input v-model="form.httpProxy" style="max-width: 420px" />
            </el-form-item>
            <el-form-item label="https_proxy">
              <el-input v-model="form.httpsProxy" style="max-width: 420px" />
            </el-form-item>
            <el-form-item label="no_proxy 追加">
              <el-input v-model="form.noProxyExtra" placeholder="逗号分隔；Pod/Service/节点网段会自动补" style="max-width: 420px" />
            </el-form-item>
          </template>
        </el-form>

        <div class="install__actions">
          <el-button :disabled="step === 0" @click="prev">上一步</el-button>
          <el-button v-if="step < STEPS.length - 1" type="primary" @click="next">下一步</el-button>
          <el-button type="primary" @click="generateArtifacts">生成产物</el-button>
        </div>
      </section>

      <aside class="install__aside">
        <div class="install__card">
          <div class="install__card-title">拓扑</div>
          <TopologyPreview :form="form" />
        </div>

        <div ref="issuesRef" class="install__card">
          <div class="install__card-title">
            校验
            <el-tag v-if="errors.length" type="danger" size="small">{{ errors.length }} 个错误</el-tag>
            <el-tag v-else-if="warnings.length" type="warning" size="small">{{ warnings.length }} 条提示</el-tag>
            <el-tag v-else type="success" size="small">通过</el-tag>
          </div>
          <ul v-if="issues.length" class="install__issues">
            <li v-for="(i, idx) in issues" :key="idx">
              <el-tag :type="i.level === 'error' ? 'danger' : 'warning'" size="small">
                {{ i.level === 'error' ? '错误' : '提示' }}
              </el-tag>
              <span>{{ i.message }}</span>
            </li>
          </ul>
          <div v-else class="install__ok">没有发现问题</div>
        </div>

        <div v-if="showArtifacts" ref="artifactRef" class="install__card">
          <div class="install__card-title">产物</div>
          <ArtifactPanel :files="artifacts" />
        </div>
      </aside>
    </div>
  </div>
</template>

<style scoped>
.install {
  padding: 0 2px 24px;
}
.install__steps {
  margin: 4px 0 18px;
}
.install__grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 520px;
  gap: 20px;
  align-items: start;
}
.install__form {
  min-width: 0;
}
.install__aside {
  position: sticky;
  top: 12px;
  display: flex;
  flex-direction: column;
  gap: 12px;
  max-height: calc(100vh - 120px);
  overflow: auto;
}
.install__card {
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  padding: 12px;
  background: var(--el-bg-color);
}
.install__card-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  font-weight: 600;
  color: var(--el-text-color-primary);
  margin-bottom: 10px;
}
.install__issues {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.install__issues li {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  font-size: 12.5px;
  color: var(--el-text-color-regular);
  line-height: 1.5;
}
.install__ok {
  font-size: 12.5px;
  color: var(--el-color-success);
}
.install__hint {
  font-size: 12.5px;
  color: var(--el-text-color-secondary);
}
.install__actions {
  display: flex;
  gap: 8px;
  margin-top: 18px;
  padding-top: 14px;
  border-top: 1px solid var(--el-border-color-lighter);
}
</style>
