package com.coding.platformapi.k8s;

import com.coding.common.models.k8s.dto.PipelineRunDTO;
import com.coding.common.models.k8s.dto.StorageClassDTO;
import com.coding.common.models.k8s.dto.StorageClassProvisionerDTO;
import com.coding.common.models.pipeline.PipelineCanvasRequest;
import com.coding.common.models.pipeline.PipelineCacheStatusDTO;
import com.coding.common.models.pipeline.PipelineStepRefDTO;
import com.coding.common.models.pipeline.TektonSchedulingDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * k8s-server 的**流水线专用**端点 client。
 *
 * <p>为什么不并入 {@link K8sClient}：这里面既有非六端点形态的（渲染 / 节点元数据 / 节点落点 / 取消），
 * 也有「日志是流式、不整体缓冲」的那一条。按「公共行为一个 client，专用端点各自独立」的约定单列
 * （与 {@link K8sPodClient} 同一条判断）。
 *
 * <p>⚠️ <b>本 client 的形状就是那条边界的形状</b>（spec §7 的改定块）：platform-api
 * 不依赖 k8s-core/fabric8，所以「画布 → PipelineSpec/YAML」「run → TaskRun → Pod → 容器」
 * 全在 k8s-server 侧完成。这里只发画布/名字，收回**已经解析好的**结果。
 */
@Component
@RequiredArgsConstructor
public class K8sPipelineClient {

    private final K8sServerGateway gateway;
    private final K8sClient k8s;

    /**
     * 内置节点的元数据（表单 schema + 语言预设）。
     *
     * <p>⚠️ **原样透传，不解析成类型**：解析就得把 k8s-core 的 {@code NodeField} / {@code BuildPreset}
     * 拖进 platform-api 的 classpath —— 那正是这条边界要避免的。前端本来就照 schema 渲染，
     * 中间这一跳没有理由理解它。
     */
    public JsonNode nodeTypes() {
        return gateway.exchange(HttpMethod.POST, "/pipelineruns/node-types", null, Map.of(),
                gateway.responseType(JsonNode.class));
    }

    /**
     * 画布 → Tekton YAML。
     *
     * <p>⚠️ 它**同时是保存流水线时的画布校验入口**：带环、未知节点类型都会由 k8s-server
     * 抛业务异常，platform-api 原样冒给用户。规则只有一份，不在这里重写。
     */
    public String render(String canvasJson) {
        return render(canvasJson, null);
    }

    /**
     * 画布 → YAML，**带上本次可用的连接**（预览 / 保存校验走这条）。
     *
     * <p>⚠️ 带镜像节点的画布离开连接就渲染不出来（目标镜像地址 = 连接 endpoint + 仓库路径 + tag），
     * 所以这两条路径也得把连接交上去。⚠️ 交的是 **endpoint-only** 的 ref
     * （{@code CredentialService.endpointOnlyRef}，不解密）—— 这条路径的产物会给用户看。
     */
    public String render(String canvasJson,
                         List<com.coding.common.models.pipeline.PipelineConnectionRef> connections) {
        return gateway.exchange(HttpMethod.POST, "/pipelineruns/render", null,
                new PipelineCanvasRequest(canvasJson, connections), gateway.responseType(String.class));
    }

    /**
     * 创建一次运行。**带的是画布** —— 渲染在 k8s-server 侧（spec §7 的改定块）。
     *
     * <p>⚠️ caller 必须**先落库再调它**（spec §4.5①）：run 名由平台先生成，
     * 幂等键从源头就有，也不会出现「集群里有对象、库里没记录」的孤儿。
     */
    public PipelineRunDTO createRun(String clusterId, String tenantId, String namespace,
                                   String runName, String canvasJson,
                                   java.util.List<com.coding.common.models.pipeline.PipelineConnectionRef> connections,
                                   String workspacePvc, boolean reuseWorkspace) {
        PipelineRunDTO dto = new PipelineRunDTO();
        dto.setClusterId(clusterId);
        dto.setTenantId(tenantId);
        dto.setNamespace(namespace);
        dto.setName(runName);
        dto.setPipelineCanvasJson(canvasJson);
        dto.setConnections(connections);
        dto.setWorkspacePvc(workspacePvc);
        dto.setReuseWorkspace(reuseWorkspace);
        return k8s.create(dto);
    }

    /** 待重试工作区：本命名空间里带平台工作区标签的 PVC（窄投影）。 */
    public List<com.coding.common.models.pipeline.PipelineRetryWorkspaceDTO> listRetryWorkspaces(
            String clusterId, String tenantId, String namespace) {
        com.coding.common.models.k8s.dto.PersistentVolumeClaimDTO query =
                new com.coding.common.models.k8s.dto.PersistentVolumeClaimDTO();
        query.setClusterId(clusterId);
        query.setTenantId(tenantId);
        query.setNamespace(namespace);
        query.setLabelSelector("platform.pipeline/role=workspace");
        return k8s.list(query).stream().map(K8sPipelineClient::retryWorkspaceOf).toList();
    }

    /** PVC → 窄投影。⚠️ 注解缺失时 `retainUntil` 必须是 null，不能是字符串 `"null"`。 */
    private static com.coding.common.models.pipeline.PipelineRetryWorkspaceDTO retryWorkspaceOf(
            com.coding.common.models.k8s.dto.PersistentVolumeClaimDTO p) {
        Object raw = p.getAnnotations() == null ? null
                : p.getAnnotations().get("platform.pipeline/retain-until");
        return new com.coding.common.models.pipeline.PipelineRetryWorkspaceDTO(
                p.getName(),
                p.getLabels() == null ? null : p.getLabels().get("platform.pipeline/run"),
                p.getCreationTime(),
                raw == null ? null : String.valueOf(raw),
                p.getStorage() == null ? 0L : p.getStorage().longValue());
    }

    /** 删一块待重试工作区（按 PVC 名）。 */
    public void deleteRetryWorkspace(String clusterId, String tenantId, String namespace, String name) {
        com.coding.common.models.k8s.dto.PersistentVolumeClaimDTO dto =
                new com.coding.common.models.k8s.dto.PersistentVolumeClaimDTO();
        dto.setClusterId(clusterId);
        dto.setTenantId(tenantId);
        dto.setNamespace(namespace);
        dto.setName(name);
        k8s.delete(dto);
    }

    /** 读一次运行的当前状态（归档时抓快照用）。不存在返回 null。 */
    public PipelineRunDTO getRun(String clusterId, String tenantId, String namespace, String name) {
        PipelineRunDTO dto = new PipelineRunDTO();
        dto.setClusterId(clusterId);
        dto.setTenantId(tenantId);
        dto.setNamespace(namespace);
        dto.setName(name);
        return k8s.get(dto);
    }

    /**
     * 一次运行渲染出来的 YAML —— **集群里那个真实对象**。
     *
     * <p>⚠️ 只有**还活着**的运行才给得出来：归档那一刻对象就被删了（spec §4.1）。
     * 归档之后要看 YAML 就读库里的 {@code run_yaml}（那是归档那一刻抓下来的同一份东西）。
     */
    public String runYaml(String clusterId, String tenantId, String namespace, String name) {
        PipelineRunDTO dto = new PipelineRunDTO();
        dto.setClusterId(clusterId);
        dto.setTenantId(tenantId);
        dto.setNamespace(namespace);
        dto.setName(name);
        return k8s.yaml(dto);
    }

    /**
     * 集群里 StorageClass 的**窄投影**（name + provisioner）—— CI 能力探针要的那次集群读。
     *
     * <p>⚠️ 为什么不能走通用的 {@code /storageclasses/list}：那个 controller 的边界是
     * {@code PLATFORM}（V2026_10_07_3 有意收成平台管理员专属），**租户调用方一律 403**；
     * 而列集群级 StorageClass 又必须用 admin client（租户 SA 的 K8s RBAC 里没有它）。
     * 窄投影这条与 {@code /nodes/addresses} 同形：UPSTREAM 边界 + 只回两个字段，
     * 收窄在上游（{@code /pipeline/capability} 挂 {@code tenant:pipeline:read}）。
     *
     * <p>放在这个 client 里而不是通用 {@link K8sClient}：它是**非六端点形态**的窄投影，
     * 且只服务于 CI 这条链。
     */
    public List<StorageClassProvisionerDTO> storageClassProvisioners(String clusterId) {
        StorageClassDTO body = new StorageClassDTO();
        body.setClusterId(clusterId);
        return gateway.exchange(HttpMethod.POST, "/storageclasses/provisioners", null, body,
                gateway.listResponseType(StorageClassProvisionerDTO.class));
    }

    /** 删除一次运行（**级联删 TaskRun 与 Pod** —— 这正是「k8s 不留 Pod」的那一步）。 */
    public void deleteRun(String clusterId, String tenantId, String namespace, String name) {
        PipelineRunDTO dto = new PipelineRunDTO();
        dto.setClusterId(clusterId);
        dto.setTenantId(tenantId);
        dto.setNamespace(namespace);
        dto.setName(name);
        k8s.delete(dto);
    }

    /** 取消一次运行（patch {@code spec.status}，**不删对象** —— 删了历史就没了）。 */
    public void cancelRun(String clusterId, String tenantId, String namespace, String name) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("tenantId", tenantId);
        params.put("clusterId", clusterId);
        params.put("namespace", namespace);
        gateway.exchange(HttpMethod.POST, "/pipelineruns/" + name + "/cancel", params, null,
                gateway.responseType(Void.class));
    }

    /**
     * 这个集群的 Tekton 调度配置（{@code coschedule}）—— 构建缓存的**前提**。
     *
     * <p>⚠️ 与 StorageClass 那条同类：**租户 SA 读不到** {@code tekton-pipelines} 里的配置，
     * 所以走 k8s-server 的 UPSTREAM 窄投影（它用 admin 凭据读，并缓存 60s）。
     */
    public TektonSchedulingDTO tektonScheduling(String clusterId) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("clusterId", clusterId);
        return gateway.exchange(HttpMethod.POST, "/tekton/coschedule", params, null,
                gateway.responseType(TektonSchedulingDTO.class));
    }

    /**
     * 构建缓存的状态（每命名空间一块长期卷）。
     *
     * <p>⚠️ 随 {@code /pipeline/capability} 一起回给前端 —— 「能不能开缓存」与「能不能跑 CI」
     * 是同一类问题（环境给不给得了），页面本来就为后者发一次请求。
     */
    public PipelineCacheStatusDTO cacheStatus(String clusterId, String tenantId, String namespace) {
        return gateway.exchange(HttpMethod.POST, "/pipelineruns/cache/status",
                cacheParams(clusterId, tenantId, namespace), null,
                gateway.responseType(PipelineCacheStatusDTO.class));
    }

    /** 清掉这个命名空间的构建缓存（删卷）。 */
    public void clearCache(String clusterId, String tenantId, String namespace) {
        gateway.exchange(HttpMethod.POST, "/pipelineruns/cache/clear",
                cacheParams(clusterId, tenantId, namespace), null, gateway.responseType(Void.class));
    }

    private static Map<String, String> cacheParams(String clusterId, String tenantId, String namespace) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("tenantId", tenantId);
        params.put("clusterId", clusterId);
        params.put("namespace", namespace);
        return params;
    }

    /**
     * 一次运行里每个节点在集群上的落点（Pod 与容器）。
     *
     * <p>⚠️ 解析在 k8s-server 侧 —— platform-api 不该知道 Tekton 的 run→TaskRun→Pod 结构。
     */
    public List<PipelineStepRefDTO> steps(String clusterId, String tenantId, String namespace, String name) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("tenantId", tenantId);
        params.put("clusterId", clusterId);
        params.put("namespace", namespace);
        return gateway.exchange(HttpMethod.GET, "/pipelineruns/" + name + "/steps", params, null,
                gateway.listResponseType(PipelineStepRefDTO.class));
    }

    /**
     * 读一个 Pod 里某个容器的日志，**整体读进内存并截到 cap**。
     *
     * <p>走的是**既有的** Pod 日志通道（spec §4.4：不用新写日志通道）。它本身是流式的，
     * 这里用一个**会丢弃超出部分**的输出流接住 —— 抓归档日志时不需要把 100 MB 都读进来，
     * 但也不必为此给 k8s-server 加参数。
     *
     * <p>⚠️ 对端**恒带 k8s 时间戳前缀**（{@code usingTimestamps()}，每行 {@code <RFC3339Nano>\t}）——
     * 那是增量游标的依据，所以内容里**带着它**回来，剥前缀是调用方的事
     * （{@code PipelineRunService} 剥掉之后才给人看）。
     *
     * @param sinceTime 只看这个时刻之后的新行（增量轮询的游标）；为空 = 从头全量
     * @return 日志内容 + **是否被截断**；对端不可达或容器不存在时是空内容
     *         （归档要「抓到多少存多少」，不整条丢）
     */
    public CappedLog podLog(String clusterId, String tenantId, String namespace,
                            String podName, String container, int cap, String sinceTime) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("tenantId", tenantId);
        params.put("clusterId", clusterId);
        params.put("namespace", namespace);
        if (container != null && !container.isBlank()) {
            params.put("container", container);
        }
        if (sinceTime != null && !sinceTime.isBlank()) {
            params.put("sinceTime", sinceTime);
        }
        CappedBuffer out = new CappedBuffer(cap);
        try {
            gateway.streamGet("/pods/" + podName + "/logs", params, out);
        } catch (Exception e) {
            // ⚠️ 竞态：抓到一半 Pod 没了 / 容器还没起来 —— 返回已经抓到的部分，不整条丢
            return out.result();
        }
        return out.result();
    }

    /** 抓到的一段日志：内容 + 有没有被上限截掉 + 实际看到过多少字节。 */
    public record CappedLog(String content, boolean truncated, int sizeBytes) {
    }

    /**
     * 一个上限之后**静默丢弃**的缓冲：内存有界，且不需要给 k8s-server 加参数。
     *
     * <p>⚠️ 它**同时记下看到过多少字节** —— 只截不留总数的话，「这条日志被截断了」这个事实
     * 就丢了，UI 上用户会以为日志本来就这么短。
     */
    static final class CappedBuffer extends OutputStream {
        private final ByteArrayOutputStream buf = new ByteArrayOutputStream();
        private final int cap;
        private long total;

        CappedBuffer(int cap) {
            this.cap = cap;
        }

        @Override
        public void write(int b) {
            total++;
            if (buf.size() < cap) {
                buf.write(b);
            }
        }

        @Override
        public void write(byte[] b, int off, int len) {
            total += len;
            int room = cap - buf.size();
            if (room > 0) {
                buf.write(b, off, Math.min(room, len));
            }
        }

        CappedLog result() {
            String text = buf.toString(StandardCharsets.UTF_8);
            return new CappedLog(text, total > cap, (int) Math.min(total, Integer.MAX_VALUE));
        }
    }
}
