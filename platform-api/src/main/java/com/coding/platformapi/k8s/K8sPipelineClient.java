package com.coding.platformapi.k8s;

import com.coding.common.models.k8s.dto.PipelineRunDTO;
import com.coding.common.models.pipeline.PipelineCanvasRequest;
import com.coding.common.models.pipeline.PipelineStepRefDTO;
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
        return gateway.exchange(HttpMethod.POST, "/pipelineruns/render", null,
                new PipelineCanvasRequest(canvasJson), gateway.responseType(String.class));
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
     * @return 日志内容 + **是否被截断**；对端不可达或容器不存在时是空内容
     *         （归档要「抓到多少存多少」，不整条丢）
     */
    public CappedLog podLog(String clusterId, String tenantId, String namespace,
                            String podName, String container, int cap) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("tenantId", tenantId);
        params.put("clusterId", clusterId);
        params.put("namespace", namespace);
        if (container != null && !container.isBlank()) {
            params.put("container", container);
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
