package com.coding.platformapi.k8s;

import com.coding.common.models.pipeline.PipelineCanvasRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.Map;

/**
 * k8s-server 的**画布专用**端点 client：节点元数据与渲染（{@code /pipelineruns/node-types}、{@code /render}）。
 *
 * <p>为什么不并入 {@link K8sClient}：那两个不是六端点标准形态 —— 一个是纯元数据、
 * 一个的入参是**平台自己的画布 JSON**（不是 K8s 对象）。按「公共行为一个 client，
 * 专用端点各自独立」的约定单列（与 {@link K8sPodClient} 同一条判断）。
 *
 * <p>⚠️ <b>本 client 的存在本身就是那条边界的形状</b>（spec §7 的改定块）：
 * platform-api **不依赖 k8s-core/fabric8**，所以「画布 → PipelineSpec/YAML」必须由 k8s-server 做。
 * 这里只发画布、收回结果，一个 Tekton 类型都不碰。
 */
@Component
@RequiredArgsConstructor
public class K8sPipelineClient {

    private final K8sServerGateway gateway;

    /**
     * 内置节点的元数据（表单 schema + 语言预设）。
     *
     * <p>⚠️ **原样透传，不解析成类型**：解析就得把 k8s-core 的 {@code NodeField} / {@code BuildPreset}
     * 拖进 platform-api 的 classpath —— 那正是这条边界要避免的。前端本来就是照 schema 渲染的，
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
}
