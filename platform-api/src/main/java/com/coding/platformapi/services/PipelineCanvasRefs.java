package com.coding.platformapi.services;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 画布上「引用了平台连接」的地方 —— **跨模块契约的唯一落点**。
 *
 * <p>⚠️ 那两个字段名定义在 k8s-core 的节点类里（{@code GitCloneNode.connectionId} /
 * {@code ImagePushNode.registryConnectionId}），而本模块读不到那些类型。改名必须同时改两边 ——
 * k8s-core 的 {@code BuiltinNodesTest} 有一条护栏测试钉住节点侧。
 *
 * <p>两种消费方式，**都要先过授权**（{@code CredentialService} 自己校）：
 * <ul>
 *   <li><b>触发运行</b>：解密拿明文，交给 k8s-server 去建 Secret（{@code PipelineRunService}）；</li>
 *   <li><b>预览 / 保存校验</b>：**只取 endpoint、不解密**（{@code PipelineService}）——
 *       渲染要 endpoint 才拼得出镜像地址，而那条路径产出的东西是给用户看的。</li>
 * </ul>
 */
final class PipelineCanvasRefs {

    /** 画布里「引用一条平台连接」的字段名。 */
    static final List<String> CONNECTION_KEYS = List.of("connectionId", "registryConnectionId");

    /** 这些 key 要的是 **git** 连接；其余只要求「是一条被授权的镜像仓库连接」（不校验方言）。 */
    private static final List<String> GIT_KEYS = List.of("connectionId");

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private PipelineCanvasRefs() {
    }

    /** 画布引用了哪些连接。**按出现顺序、同一个 id 只出现一次**（去重后按第一个遇到的 key 定类型）。 */
    static List<Ref> scan(String canvasJson) {
        if (canvasJson == null || canvasJson.isBlank()) {
            return List.of();
        }
        JsonNode nodes;
        try {
            nodes = JSON.readTree(canvasJson).path("nodes");
        } catch (Exception e) {
            throw new CloudPlatformException(EnumResponseType.PIPELINE_CANVAS_INVALID,
                    "画布内容无法解析");
        }
        if (!nodes.isArray()) {
            return List.of();
        }
        List<Ref> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (JsonNode node : nodes) {
            JsonNode config = node.path("config");
            for (String key : CONNECTION_KEYS) {
                String id = config.path(key).asString(null);
                if (id == null || id.isBlank() || !seen.add(id.trim())) {
                    continue;
                }
                out.add(new Ref(id.trim(), GIT_KEYS.contains(key) ? "GIT" : null));
            }
        }
        return out;
    }

    /**
     * 一处连接引用。
     *
     * @param expectedType 这一处要的是哪种连接；{@code null} = 不校验类型
     *                     （镜像节点只要求「是一条被授权的镜像仓库连接」，它不关心对方是 Harbor 还是别的方言）
     */
    record Ref(String id, String expectedType) {
    }
}
