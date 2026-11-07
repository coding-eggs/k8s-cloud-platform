<script setup lang="ts">
/**
 * 服务网格说明：应用设置「服务网格」旁一个链接，点开是分章节的说明。
 * 内容与 docs/development/application-release.md §1.7 和 AppMeshForm 的行为对齐；行为改了三处一起改。
 */
import { ref } from 'vue'

const visible = ref(false)

const SECTIONS = [
  { id: 'choose', title: '1. 怎么选' },
  { id: 'l4', title: '2. L4：ztunnel' },
  { id: 'l7', title: '3. L7：waypoint' },
  { id: 'labels', title: '4. 平台写了哪些标签' },
  { id: 'waypoint', title: '5. 准备 waypoint' },
  { id: 'release', title: '6. 生效与发布' },
  { id: 'faq', title: '7. 常见问题' },
]
const active = ref('choose')
const content = ref<HTMLElement | null>(null)

/** 只滚内容区：scrollIntoView 会连带滚动弹窗外层 */
function jump(id: string) {
  active.value = id
  const box = content.value
  const target = box?.querySelector<HTMLElement>(`#app-mesh-guide-${id}`)
  if (box && target) box.scrollTop = target.offsetTop - box.offsetTop
}

/** 手动滚动时目录跟着高亮当前章节 */
function onScroll() {
  const box = content.value
  if (!box) return
  if (box.scrollTop + box.clientHeight >= box.scrollHeight - 2) {
    const el = box.querySelector<HTMLElement>(`#app-mesh-guide-${active.value}`)
    if (el && el.offsetTop - box.offsetTop >= box.scrollTop) return
  }
  let current = 'choose'
  for (const s of SECTIONS) {
    const el = box.querySelector<HTMLElement>(`#app-mesh-guide-${s.id}`)
    if (el && el.offsetTop - box.offsetTop <= box.scrollTop + 8) current = s.id
  }
  active.value = current
}
</script>

<template>
  <el-link type="primary" :underline="false" @click="visible = true">网格说明</el-link>

  <el-dialog v-model="visible" title="服务网格说明" width="920px" top="6vh" class="app-mesh-guide" append-to-body>
    <div class="app-mesh-guide__body">
      <nav class="app-mesh-guide__nav">
        <a v-for="s in SECTIONS" :key="s.id" :class="{ 'is-active': active === s.id }" @click="jump(s.id)">{{ s.title }}</a>
      </nav>

      <div ref="content" class="app-mesh-guide__content" @scroll="onScroll">
        <section id="app-mesh-guide-choose">
          <h3>1. 怎么选</h3>
          <p>服务网格是<b>应用级设置</b>，作用于应用的全部服务：mTLS、授权、L7 路由都要求调用双方都在网格里，一个应用里半进半出没有意义。</p>
          <table>
            <tr><th>档位</th><th>适合</th><th>得到什么</th></tr>
            <tr><td>跟随命名空间</td><td>命名空间已统一管理网格（命名空间页开了 ambient / 配了 waypoint）</td><td>由命名空间决定，平台不写任何标签</td></tr>
            <tr><td>不加入</td><td>明确不要网格，比如排查网格引起的问题</td><td>即使命名空间开了 ambient，本应用的 Pod 也不进网格</td></tr>
            <tr><td><b>L4</b></td><td>只要加密和身份，不需要按 HTTP 做治理</td><td>自动 mTLS、按身份 / 端口的授权、TCP 指标</td></tr>
            <tr><td><b>L7</b></td><td>要按路径 / header 路由、灰度、重试超时、HTTP 授权、请求级指标</td><td>L4 的全部 + 经 waypoint 的七层能力</td></tr>
          </table>
          <p class="app-mesh-guide__tip">拿不准就选 L4：开销小、对应用透明；确实需要 HTTP 层能力时再升 L7。</p>
        </section>

        <section id="app-mesh-guide-l4">
          <h3>2. L4：ztunnel</h3>
          <p>Istio ambient 模式下，每个节点上有一个 <code>ztunnel</code>。Pod 加入网格后，进出流量由它接管，<b>不注入 sidecar</b>，业务容器无需改动。</p>
          <ul>
            <li><b>mTLS</b>：服务之间自动加密、互相认证身份（SPIFFE 身份 = 命名空间 + ServiceAccount）。</li>
            <li><b>L4 授权</b>：AuthorizationPolicy 可以按来源身份、命名空间、目标端口放行或拒绝。</li>
            <li><b>TCP 指标</b>：连接数、收发字节；网格拓扑里能看到服务之间的 TCP 边。</li>
          </ul>
          <p><b>做不到</b>：ztunnel 不解析 HTTP，所以没有按路径 / header 的路由、重试、超时、HTTP 状态码与延迟指标，也不能按 HTTP 方法 / 路径授权。这些需要 L7。</p>
        </section>

        <section id="app-mesh-guide-l7">
          <h3>3. L7：waypoint</h3>
          <p>waypoint 是一个按需部署的 Envoy 代理（一个 Gateway 资源）。Service 打上 <code>istio.io/use-waypoint</code> 后，<b>发往这个 Service 的流量</b>先经 ztunnel 送到 waypoint，再由 waypoint 转给目标 Pod。</p>
          <ul>
            <li><b>HTTP 路由</b>：HTTPRoute / GRPCRoute 以 Service 为 parentRef，按路径、header、权重分流（灰度、A/B）。</li>
            <li><b>弹性</b>：重试、超时、故障注入。</li>
            <li><b>L7 授权</b>：按 HTTP 方法、路径、header 授权。</li>
            <li><b>请求级可观测</b>：请求数、成功率、延迟分位；网格拓扑出现 L7 边。</li>
          </ul>
          <h4>前提</h4>
          <ol>
            <li>Pod 已在 ambient 里（L7 一定包含 L4，平台选 L7 时会一并写好）。</li>
            <li>本命名空间有一个 waypoint，且它的 <code>istio.io/waypoint-for</code> 是 <code>service</code> 或 <code>all</code>。</li>
            <li>服务<b>填了端口</b>（有 K8s Service）。后台进程没有 Service，选 L7 时它们仍只有 L4。</li>
          </ol>
        </section>

        <section id="app-mesh-guide-labels">
          <h3>4. 平台写了哪些标签</h3>
          <p>只有两个 Istio 标签（是 label，不是 annotation），随发布写进清单：</p>
          <table>
            <tr><th>档位</th><th>Pod 模板 <code>istio.io/dataplane-mode</code></th><th>有端口服务的 Service <code>istio.io/use-waypoint</code></th></tr>
            <tr><td>跟随命名空间</td><td>不写</td><td>不写</td></tr>
            <tr><td>不加入</td><td><code>none</code></td><td>不写</td></tr>
            <tr><td>L4</td><td><code>ambient</code></td><td><code>none</code></td></tr>
            <tr><td>L7</td><td><code>ambient</code></td><td><code>&lt;所选 waypoint&gt;</code></td></tr>
          </table>
          <ul>
            <li><b>L4 为什么写 <code>use-waypoint=none</code></b>：命名空间如果配了 waypoint，Service 不写这个标签会继承它，实际变成 L7。写 <code>none</code> 保证选了 L4 就只走 ztunnel。</li>
            <li>这两个标签由平台管理，不要在资源页手工改应用对象（会被拒绝，下次发布也会被覆盖）。</li>
          </ul>
        </section>

        <section id="app-mesh-guide-waypoint">
          <h3>5. 准备 waypoint</h3>
          <ol>
            <li>到「网络 → Gateway」页，切到应用所在命名空间，点「创建 Waypoint」（网关类为 <code>istio-waypoint</code>）。</li>
            <li>「处理的流量类型」选 <b>service</b>（默认，或 all）。选 workload 的 waypoint 不处理发往 Service 的流量——Istio 会静默放行，L7 不生效，所以下拉里不列它。</li>
            <li>回到应用设置，L7 下拉里选它，保存后发布。</li>
          </ol>
          <p class="app-mesh-guide__tip">只支持同命名空间的 waypoint；一个 waypoint 可以被多个应用共用。</p>
        </section>

        <section id="app-mesh-guide-release">
          <h3>6. 生效与发布</h3>
          <ul>
            <li>保存只改期望值。发布时勾选「应用设置」才下发，<b>全部服务会滚动重启</b>（Pod 模板标签变了）；不勾则沿用线上值。</li>
            <li>集群没装 Istio ambient（组件「ambient 网格」不可用）时，L4 / L7 置灰；跟随、不加入随时可选。已开着的在组件卸载后照样能发布，预览给警告。</li>
            <li>保存与发布前会检查 L7 的 waypoint 是否存在、是否处理服务流量；不存在时发布预览报「应用设置：…」。</li>
            <li>回滚会连应用设置一起还原（前提是应用设置没有未发布的修改）。</li>
          </ul>
        </section>

        <section id="app-mesh-guide-faq">
          <h3>7. 常见问题</h3>
          <h4>选了 L7，拓扑里还是只有 TCP 边</h4>
          <ul>
            <li>确认已发布（勾了「应用设置」），且服务填了端口。</li>
            <li>到 Gateway 页看 waypoint 是否 Programmed、<code>waypoint-for</code> 是否为 service / all。</li>
            <li>调用方也要在网格里；只有服务端在网格时，流量没有经过 ztunnel 发往 waypoint。</li>
            <li>协议要是 HTTP / gRPC；纯 TCP 协议经过 waypoint 也只有 TCP 指标。</li>
          </ul>
          <h4>加入网格后服务连不上</h4>
          <ul>
            <li>目标端如果开了 STRICT mTLS 而调用方不在网格里，会被拒；把调用方也加入，或临时选「不加入」排查。</li>
            <li>节点上 ztunnel 证书过期也会导致连接被中断，表现为 connection termination，可联系平台管理员处理。</li>
          </ul>
          <h4>链路追踪和网格是什么关系</h4>
          <p>互相独立。链路追踪靠探针注入（应用设置里的「链路追踪」）；L7 的 waypoint 也会产生跨服务的请求数据，但要串成完整链路，业务仍需透传 <code>traceparent</code>。</p>
        </section>
      </div>
    </div>
  </el-dialog>
</template>

<style>
/* el-dialog 内部节点没有 data-v-*，scoped 打不到；用唯一前缀 app-mesh-guide */
.app-mesh-guide .el-dialog__body { padding-top: 4px; }
.app-mesh-guide .app-mesh-guide__body { display: flex; gap: 20px; height: 72vh; }
.app-mesh-guide .app-mesh-guide__nav {
  flex: 0 0 168px;
  display: flex;
  flex-direction: column;
  gap: 2px;
  border-right: 1px solid var(--el-border-color-lighter);
  padding-right: 12px;
}
.app-mesh-guide .app-mesh-guide__nav a {
  cursor: pointer;
  padding: 6px 10px;
  border-radius: 6px;
  font-size: 13px;
  color: var(--el-text-color-regular);
}
.app-mesh-guide .app-mesh-guide__nav a:hover { background: var(--el-fill-color-light); }
.app-mesh-guide .app-mesh-guide__nav a.is-active { background: var(--el-color-primary-light-9); color: var(--el-color-primary); font-weight: 600; }
.app-mesh-guide .app-mesh-guide__content { flex: 1; overflow-y: auto; padding-right: 8px; font-size: 13.5px; line-height: 1.75; color: var(--el-text-color-primary); }
.app-mesh-guide .app-mesh-guide__content section { padding-bottom: 12px; margin-bottom: 12px; border-bottom: 1px dashed var(--el-border-color-lighter); }
.app-mesh-guide .app-mesh-guide__content section:last-child { border-bottom: none; }
.app-mesh-guide .app-mesh-guide__content h3 { margin: 4px 0 8px; font-size: 16px; }
.app-mesh-guide .app-mesh-guide__content h4 { margin: 12px 0 4px; font-size: 14px; }
.app-mesh-guide .app-mesh-guide__content p { margin: 4px 0; }
.app-mesh-guide .app-mesh-guide__content ul,
.app-mesh-guide .app-mesh-guide__content ol { margin: 4px 0; padding-left: 22px; }
/* 全局 preflight 把列表符号清掉了，这里补回来 */
.app-mesh-guide .app-mesh-guide__content ul { list-style: disc; }
.app-mesh-guide .app-mesh-guide__content ol { list-style: decimal; }
.app-mesh-guide .app-mesh-guide__content code {
  padding: 1px 5px;
  border-radius: 4px;
  background: var(--el-fill-color-light);
  font-size: 12.5px;
}
.app-mesh-guide .app-mesh-guide__content table { width: 100%; border-collapse: collapse; margin: 6px 0; }
.app-mesh-guide .app-mesh-guide__content th,
.app-mesh-guide .app-mesh-guide__content td { border: 1px solid var(--el-border-color-lighter); padding: 6px 10px; text-align: left; vertical-align: top; }
.app-mesh-guide .app-mesh-guide__content th { background: var(--el-fill-color-lighter); white-space: nowrap; font-weight: 600; }
.app-mesh-guide .app-mesh-guide__tip { color: var(--el-text-color-secondary); }
</style>
