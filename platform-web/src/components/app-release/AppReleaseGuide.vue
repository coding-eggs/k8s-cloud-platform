<script setup lang="ts">
/**
 * 应用发布说明：页头一个按钮，点开是一份分章节的使用说明。
 * 内容与 docs/development/application-release.md 对齐；行为改了两边一起改。
 */
import { ref } from 'vue'

const visible = ref(false)

const SECTIONS = [
  { id: 'quick', title: '1. 快速上手' },
  { id: 'concept', title: '2. 基本概念' },
  { id: 'service', title: '3. 服务配置' },
  { id: 'ref', title: '4. 服务间与依赖的连接' },
  { id: 'dep', title: '5. 依赖' },
  { id: 'release', title: '6. 发布' },
  { id: 'rollback', title: '7. 回滚与中断恢复' },
  { id: 'delete', title: '8. 移除与删除' },
  { id: 'notes', title: '9. 注意事项' },
]
const active = ref('quick')
const content = ref<HTMLElement | null>(null)

/** 只滚内容区：scrollIntoView 会连带滚动弹窗外层 */
function jump(id: string) {
  active.value = id
  const box = content.value
  const target = box?.querySelector<HTMLElement>(`#app-guide-${id}`)
  if (box && target) box.scrollTop = target.offsetTop - box.offsetTop
}

/** 手动滚动时目录跟着高亮当前章节 */
function onScroll() {
  const box = content.value
  if (!box) return
  // 滚到底时最后几节的标题到不了顶部：保留点选的那节
  if (box.scrollTop + box.clientHeight >= box.scrollHeight - 2) {
    const el = box.querySelector<HTMLElement>(`#app-guide-${active.value}`)
    if (el && el.offsetTop - box.offsetTop >= box.scrollTop) return
  }
  let current = 'quick'
  for (const s of SECTIONS) {
    const el = box.querySelector<HTMLElement>(`#app-guide-${s.id}`)
    if (el && el.offsetTop - box.offsetTop <= box.scrollTop + 8) current = s.id
  }
  active.value = current
}
</script>

<template>
  <el-button @click="visible = true">发布说明</el-button>

  <el-dialog v-model="visible" title="应用发布说明" width="920px" top="6vh" class="app-guide" append-to-body>
    <div class="app-guide__body">
      <nav class="app-guide__nav">
        <a v-for="s in SECTIONS" :key="s.id" :class="{ 'is-active': active === s.id }" @click="jump(s.id)">{{ s.title }}</a>
      </nav>

      <div ref="content" class="app-guide__content" @scroll="onScroll">
        <section id="app-guide-quick">
          <h3>1. 快速上手</h3>
          <ol>
            <li><b>添加服务</b>：应用页「+ 添加服务」，填服务名、镜像仓库、tag；对外提供接口的服务填<b>端口</b>。</li>
            <li><b>添加依赖</b>（可选）：数据库、缓存等在「+ 添加依赖」里新建实例、绑定已有实例或登记外部地址。</li>
            <li><b>写连接配置</b>：在服务的环境变量里用 <code>${名称.字段}</code> 引用其他服务或依赖，例如 <code>DB_HOST=${db.host}</code>。</li>
            <li><b>发布</b>：点页头「发布」，勾选要上线的服务，看一眼改动预览，确认。</li>
            <li><b>看进度</b>：页面顶部显示执行阶段；完成后服务状态变为「运行中」。</li>
          </ol>
          <p class="app-guide__tip">之后的日常更新：改服务配置（或直接换 tag）→ 应用页出现「有改动」→ 勾选该服务发布。</p>
        </section>

        <section id="app-guide-concept">
          <h3>2. 基本概念</h3>
          <table>
            <tr><th>应用</th><td>一个命名空间里的一组服务 + 一组依赖，以及一串发布记录。</td></tr>
            <tr><th>服务</th><td>你自己的程序（一个镜像）。<b>填了端口</b> = 对外提供接口的服务（建 K8s Service）；<b>没填端口</b> = 后台进程（只跑 Pod，别人不能引用它）。</td></tr>
            <tr><th>依赖</th><td>服务要用的中间件或外部系统。依赖<b>不参与发布</b>，增删立即生效。</td></tr>
            <tr><th>待发布改动</th><td>在服务页保存的配置只是「期望配置」，不会立刻影响线上；应用页把与线上不同的服务标成「新增 / 有改动 / 待删除」，发布后才生效。</td></tr>
            <tr><th>发布</th><td>一次不可变的快照（所选服务的配置 + 连接信息）。发布号递增，可回滚到任意一次。</td></tr>
            <tr><th>执行</th><td>把一次发布落到集群的过程，按阶段推进。每个应用同一时间只能有一个进行中的执行。</td></tr>
          </table>
        </section>

        <section id="app-guide-service">
          <h3>3. 服务配置</h3>
          <h4>3.1 服务名</h4>
          <ul>
            <li>至多 20 位，小写字母、数字、连字符，字母开头、不以连字符结尾；<b>创建后不能改</b>。</li>
            <li>不能与依赖同名（引用时共用一个名字空间）；不能以 <code>-env</code> / <code>-secret</code> / <code>-headless</code> / <code>-files</code> 结尾（平台保留）。</li>
            <li>集群里的对象名是 <code>应用slug-服务名</code>，例如应用 <code>shop</code> 的服务 <code>api</code> → <code>shop-api</code>。</li>
          </ul>
          <h4>3.2 基本</h4>
          <ul>
            <li><b>镜像仓库 / tag</b>：仓库不含 tag。平台 Harbor 里的镜像可以直接下拉选 tag；仓库里有更晚推送的 tag 时，应用页会提示「有新版本」。</li>
            <li><b>端口</b>：决定它是服务还是后台进程（见概念）。别的服务引用的就是这个端口。</li>
            <li><b>副本数</b>：开启自动扩缩容后以扩缩容为准。</li>
          </ul>
          <h4>3.3 环境变量</h4>
          <ul>
            <li><b>值 / 引用</b>：字面值，或含 <code>${名称.字段}</code> 的引用（可与文字拼接，如 <code>${api.url}/v1</code>）。</li>
            <li><b>Secret</b>：引用命名空间里已有的 Secret 的某个键。</li>
            <li>名字以 <code>PASSWORD</code> / <code>PWD</code> / <code>SECRET</code> / <code>TOKEN</code> / <code>API_KEY</code> 等结尾却写了明文时，发布预览会警告：请改用依赖引用或 Secret。</li>
          </ul>
          <h4>3.4 配置文件</h4>
          <ul>
            <li>每条一个绝对路径 + 内容，<b>只读</b>挂到容器里，同目录的其他文件不受影响。</li>
            <li>每服务至多 20 个，内容合计至多 512KiB；路径不能重复、不能互相嵌套、不能放在持久化数据目录（默认 <code>/data</code>）里。</li>
            <li>内容里只能引用 <code>host</code> / <code>port</code> / <code>address</code> / <code>url</code>；<b>账号口令不能写进文件</b>，请走环境变量。</li>
            <li>改了文件内容，发布时会滚动重启该服务（容器里的文件不会自己更新）。</li>
          </ul>
          <h4>3.5 资源与命令、高级</h4>
          <ul>
            <li><b>CPU / 内存</b>：请求与上限，如 <code>100m</code>、<code>512Mi</code>。命名空间有配额时务必填写。</li>
            <li><b>启动命令 / 参数</b>：一行一个参数，留空用镜像默认。</li>
            <li><b>持久化</b>（仅有端口的服务）：开启后以 StatefulSet 运行并挂数据卷（大小、存储类、挂载路径）。</li>
            <li><b>健康探针</b>：<b>默认开启</b>，按 HTTP 访问探针路径（默认 <code>/</code>）。非 HTTP 服务请关掉，否则会一直不就绪、发布卡在健康检查。</li>
            <li><b>暴露指标</b>：开启后按指标路径（默认 <code>/metrics</code>）采集，在「监控」页签可见。</li>
          </ul>
        </section>

        <section id="app-guide-ref">
          <h3>4. 服务间与依赖的连接</h3>
          <p>不需要连线：在环境变量或配置文件里写 <code>${名称.字段}</code>，发布时替换成真实值。名称是服务名或依赖名。</p>
          <table>
            <tr><th>字段</th><th>服务</th><th>依赖</th><th>可用位置</th></tr>
            <tr><td><code>host</code></td><td>集群内服务名，如 <code>shop-api</code></td><td>实例 / 外部主机</td><td>环境变量、配置文件</td></tr>
            <tr><td><code>port</code></td><td>服务填的端口</td><td>连接端口</td><td>环境变量、配置文件</td></tr>
            <tr><td><code>url</code></td><td><code>http://host:port</code></td><td>按协议拼出的地址</td><td>环境变量、配置文件</td></tr>
            <tr><td><code>address</code></td><td>—</td><td><code>host:port</code></td><td>环境变量、配置文件</td></tr>
            <tr><td><code>username</code> / <code>password</code></td><td>—</td><td>从 Secret 读取，明文不进快照</td><td><b>仅环境变量</b></td></tr>
          </table>
          <ul>
            <li>示例：<code>API_URL=${api.url}</code>、<code>SPRING_DATASOURCE_URL=jdbc:mysql://${db.address}/shop</code>、<code>DB_PASSWORD=${db.password}</code>。</li>
            <li>只有<b>填了端口</b>的服务能被引用；引用不存在的名称或字段，发布预览会报错并阻止发布。</li>
            <li><code>url</code> 固定是 <code>http://</code>，对方不是 HTTP 时自己用 <code>${x.host}:${x.port}</code> 拼。</li>
            <li>引用了还没发布过的服务时，发布对话框会提示「需要一并勾选」。</li>
            <li>「拓扑」页签按这些引用自动画出关系图（只读）。</li>
          </ul>
        </section>

        <section id="app-guide-dep">
          <h3>5. 依赖</h3>
          <table>
            <tr><th>新建实例</th><td>由应用创建一个中间件实例（选类型、形态、版本、规格）。需要该中间件的组件在集群里已就绪。</td></tr>
            <tr><th>绑定已有实例</th><td>使用中间件商店里已有的实例，应用不管理它的生命周期。</td></tr>
            <tr><th>外部连接</th><td>登记集群外的地址（协议、主机、端口），账号口令放在应用命名空间的 Secret 里，下拉选 Secret 名和键名（没有就点「新建 Secret」）。</td></tr>
          </table>
          <ul>
            <li><b>修改依赖</b>：外部连接可在依赖抽屉里「修改连接信息」（地址、端口、Secret 登记；依赖名和协议不能改）。保存后线上不变，引用它的服务会列为待发布，下次发布生效；这时发布任何服务都要把它们一并勾选。中间件依赖的规格到中间件实例页改。</li>
            <li>依赖<b>不进发布</b>：添加后立即创建 / 绑定；状态变为「运行正常」后才能被服务正常使用。</li>
            <li><b>初始化脚本</b>（MySQL / PostgreSQL / Redis / Elasticsearch / Kafka）：在依赖抽屉里填脚本并执行，实例就绪后才能运行。初始化与发布无关，重复发布不会重跑；输出日志可能包含执行的语句，<b>脚本里不要写明文口令</b>。</li>
            <li>依赖的高级参数（如 Milvus、ES Kibana/TLS）在这里建不了：先去中间件商店建好，再「绑定已有实例」。</li>
          </ul>
        </section>

        <section id="app-guide-release">
          <h3>6. 发布</h3>
          <h4>6.1 操作步骤</h4>
          <ol>
            <li>点页头「发布 · N 项改动」，勾选本次要上线的服务（可以只发其中几个）。</li>
            <li>只想换版本：在「发布 tag」列直接改 tag，不必先去服务页。</li>
            <li>检查预览：本次改动、要新建 / 删除的对象、报错与警告。有报错不能发布。</li>
            <li>填写发布说明（可选），确认。</li>
          </ol>
          <h4>6.2 执行阶段</h4>
          <p>预检 → 渲染 → 冲突检查 → 下发 → 健康检查 → 清理旧对象 → 完成。</p>
          <ul>
            <li><b>冲突检查</b>：命名空间里已有同名、但不是本应用创建的对象时拒绝发布，不会接管别人的资源。</li>
            <li><b>健康检查</b>：等待应用内<b>所有</b>服务就绪，最长 30 分钟；超时失败时消息里会点名没就绪的对象。</li>
            <li><b>清理旧对象</b>：删掉这次发布不再包含的对象（如待删除的服务）；数据卷 PVC 保留。</li>
          </ul>
          <h4>6.3 哪些服务会重启</h4>
          <ul>
            <li>勾选的服务：按新配置滚动更新。</li>
            <li><b>没勾选的服务不会带上自己的待发布改动</b>，一般也不重启；例外：它引用的服务端口或依赖连接信息变了，会随之滚动以拿到新值。</li>
            <li>只改副本数不会重启已有 Pod。</li>
          </ul>
        </section>

        <section id="app-guide-rollback">
          <h3>7. 回滚与中断恢复</h3>
          <ul>
            <li><b>回滚</b>：在「发布记录」里选一次历史发布回滚，等于用那次的快照再发一次（生成新的发布号）。回滚前会显示两份快照的差异。</li>
            <li>回滚用的是那次快照里的外部连接信息：之后改过的地址、Secret 不会带上；回滚后引用它的服务会重新列为待发布，再发布一次就用上当前登记的连接信息。中间件依赖的连接信息发布时现取，不受影响。</li>
            <li><b>已中断</b>：平台重启时进行中的执行会变成「已中断」，点「继续」从断点接着跑；也可以直接发新版本取代它。</li>
            <li><b>失败</b>：修正配置后直接重新发布即可。</li>
            <li>回滚与发布需要应用写权限；只读成员只能查看。</li>
          </ul>
        </section>

        <section id="app-guide-delete">
          <h3>8. 移除与删除</h3>
          <ul>
            <li><b>移除服务</b>：已发布过的服务被标为「待删除」，下次发布时勾选它才真正从集群移除；没发布过的直接删除配置。</li>
            <li><b>移除依赖</b>：还有服务引用它时不能移除，先去掉引用并发布。应用新建的实例可选择保留（转交中间件模块，默认）或连同数据一起删除；绑定的实例和外部连接只解除登记。</li>
            <li><b>删除应用</b>：清理它在集群里建过的对象（PVC 保留），应用新建的中间件实例保留并转交中间件模块。有进行中的发布时不能删除。</li>
          </ul>
        </section>

        <section id="app-guide-notes">
          <h3>9. 注意事项</h3>
          <ul>
            <li><b>口令只放 Secret 或依赖引用</b>：只读成员也能看到环境变量的字面值。</li>
            <li><b>非 HTTP 服务记得关健康探针</b>，否则发布会一直卡在健康检查直到超时。</li>
            <li><b>一个服务不健康会挡住整个应用的发布</b>：健康检查等的是所有服务，包括没勾选的。</li>
            <li><b>发布失败后尽快修复或回滚</b>：下发阶段已经更新了环境变量和配置文件，失败期间如果旧 Pod 重启，会读到新配置配旧镜像。</li>
            <li>使用平台 Harbor 镜像的服务，命名空间需要已下发镜像拉取凭据（由管理员「重下发全部模板」），否则会卡在拉镜像。</li>
            <li>对外访问：本页暂不提供对外暴露设置，集群内用服务名访问即可；对外入口请联系管理员配置。</li>
            <li>发布记录里的运行状态取自发布台账，实时状态以服务列表为准。</li>
          </ul>
        </section>
      </div>
    </div>
  </el-dialog>
</template>

<style>
/* el-dialog 内部节点没有 data-v-*，scoped 打不到；用唯一前缀 app-guide */
.app-guide .el-dialog__body { padding-top: 4px; }
.app-guide .app-guide__body { display: flex; gap: 20px; height: 72vh; }
.app-guide .app-guide__nav {
  flex: 0 0 168px;
  display: flex;
  flex-direction: column;
  gap: 2px;
  border-right: 1px solid var(--el-border-color-lighter);
  padding-right: 12px;
}
.app-guide .app-guide__nav a {
  cursor: pointer;
  padding: 6px 10px;
  border-radius: 6px;
  font-size: 13px;
  color: var(--el-text-color-regular);
}
.app-guide .app-guide__nav a:hover { background: var(--el-fill-color-light); }
.app-guide .app-guide__nav a.is-active { background: var(--el-color-primary-light-9); color: var(--el-color-primary); font-weight: 600; }
.app-guide .app-guide__content { flex: 1; overflow-y: auto; padding-right: 8px; font-size: 13.5px; line-height: 1.75; color: var(--el-text-color-primary); }
.app-guide .app-guide__content section { padding-bottom: 12px; margin-bottom: 12px; border-bottom: 1px dashed var(--el-border-color-lighter); }
.app-guide .app-guide__content section:last-child { border-bottom: none; }
.app-guide .app-guide__content h3 { margin: 4px 0 8px; font-size: 16px; }
.app-guide .app-guide__content h4 { margin: 12px 0 4px; font-size: 14px; }
.app-guide .app-guide__content p { margin: 4px 0; }
.app-guide .app-guide__content ul,
.app-guide .app-guide__content ol { margin: 4px 0; padding-left: 22px; }
/* 全局 preflight 把列表符号清掉了，这里补回来 */
.app-guide .app-guide__content ul { list-style: disc; }
.app-guide .app-guide__content ol { list-style: decimal; }
.app-guide .app-guide__content code {
  padding: 1px 5px;
  border-radius: 4px;
  background: var(--el-fill-color-light);
  font-size: 12.5px;
}
.app-guide .app-guide__content table { width: 100%; border-collapse: collapse; margin: 6px 0; }
.app-guide .app-guide__content th,
.app-guide .app-guide__content td { border: 1px solid var(--el-border-color-lighter); padding: 6px 10px; text-align: left; vertical-align: top; }
.app-guide .app-guide__content th { background: var(--el-fill-color-lighter); white-space: nowrap; font-weight: 600; }
.app-guide .app-guide__tip { color: var(--el-text-color-secondary); }
</style>
