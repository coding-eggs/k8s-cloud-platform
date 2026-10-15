package com.coding.k8score.pipeline;

import com.coding.common.models.pipeline.PipelineConnectionRef;
import io.fabric8.kubernetes.client.utils.Serialization;
import io.fabric8.tekton.v1.PipelineSpec;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 画布相关的动作：**节点元数据**与**渲染**。全是纯函数（无 IO、无集群状态）。
 *
 * <p>⚠️ <b>它为什么在 k8s-server 一侧被调用</b>（2026-10-15 用户裁定，见 spec §7 的改定块）：
 * `platform-api` 明确不依赖 k8s-core/fabric8（`platform-api/pom.xml` 的注释），
 * 所以「画布 → PipelineSpec/YAML」这一步只能由 k8s-server 做，platform-api 发的是**画布 JSON**。
 * 代价是 `k8s-server 零业务逻辑` 在**渲染**这一处被打破 —— 但渲染既然是纯函数，
 * 放在哪一层都不会让 k8s-server 开始做业务决策。
 *
 * <p>⚠️ {@link #WORKSPACE} 是**隐式共享 workspace**的名字，用户不感知（spec §3.3）：
 * 平台把它注入到所有节点，节点里的工作目录都相对它。
 */
@Component
@RequiredArgsConstructor
public class PipelineCanvasOperations {

    /** 隐式共享 workspace 的名字。**用户不感知** —— 平台注入到所有节点（spec §3.3）。 */
    public static final String WORKSPACE = "ws";

    private final PipelineNodeRegistry nodes;
    private final PipelineRenderer renderer;

    /** 前端渲染属性面板用。**这是「加一个节点不用改前端」的落点。** */
    public List<PipelineNodeTypeView> nodeTypes() {
        return nodes.all().stream()
                .sorted(Comparator.comparing(PipelineNode::type))
                .map(n -> new PipelineNodeTypeView(n.type(), n.label(), n.description(),
                        n.formSchema().fields(), BuildPreset.all(),
                        n.icon(), n.accent(), n.summaryFields()))
                .toList();
    }

    /**
     * 画布 JSON → {@code PipelineSpec}。
     *
     * <p>⚠️ 它是**保存时的校验入口**：带环、未知节点类型都会在这里抛业务异常，
     * 于是「保存时就拒掉带环的画布」不需要在 platform-api 里再写一遍规则。
     */
    public PipelineSpec render(String canvasJson) {
        return renderer.render(PipelineCanvas.fromJson(canvasJson), WORKSPACE);
    }

    /**
     * 渲染，并带上本次运行可用的连接（**已授权、已解密**）。
     *
     * <p>⚠️ <b>2026-10-15 收窄</b>：原来只有创建运行那条路带连接，预览与保存校验用不带连接的重载 ——
     * 理由写的是「那两种路径产出的东西会给用户看，不该让凭据参与其中」。但**带镜像节点的画布
     * 离开连接就渲染不出来**（目标镜像地址 = 连接 endpoint + 仓库路径 + tag），于是「保存一条
     * 含构建节点的流水线」必然报「目标仓库连接不存在或未授权在流水线里使用」。
     * 所以那条边界收窄成「**不带凭据内容**」：这两条路径也带连接，但只带 endpoint
     * （platform-api 侧的 {@code CredentialService.endpointOnlyRef}，不解密）。
     */
    public PipelineSpec render(String canvasJson, Map<String, PipelineConnectionRef> connections) {
        return renderer.render(PipelineCanvas.fromJson(canvasJson), WORKSPACE,
                connections == null ? Map.of() : connections);
    }

    /**
     * 画布 JSON → YAML，给前端预览。**它同时是渲染器的主要自证**：
     * 平台渲染出来的东西用户能看见（spec §6）。
     */
    public String renderYaml(String canvasJson) {
        return Serialization.asYaml(render(canvasJson));
    }

    /**
     * 画布 JSON → YAML，**带上本次可用的连接**（预览 / 保存校验走这条）。
     *
     * <p>⚠️ 为什么预览与保存也要给连接：**带镜像节点的画布离开连接就渲染不出来** ——
     * 目标镜像地址 = 连接的 endpoint + 仓库路径 + tag，没有连接就没有地址，那个节点
     * 有意拒绝产出一个必然失败的镜像引用。所以「预览路径不给凭据」这条边界要收窄成
     * 「**不给凭据内容**」：连接以 endpoint 形式参与渲染（明文仍然只走运行那条路）。
     */
    public String renderYaml(String canvasJson, Map<String, PipelineConnectionRef> connections) {
        return Serialization.asYaml(render(canvasJson, connections));
    }

    /**
     * 连接列表 → 按 id 索引的 map（{@link PipelineRenderer} 要的形状）。
     * 同一个 id 出现多次只留一份；null / 空列表 → 空 map。
     */
    public static Map<String, PipelineConnectionRef> byId(List<PipelineConnectionRef> connections) {
        if (connections == null || connections.isEmpty()) {
            return Map.of();
        }
        Map<String, PipelineConnectionRef> out = new LinkedHashMap<>();
        for (PipelineConnectionRef c : connections) {
            if (c != null && c.id() != null) {
                out.put(c.id(), c);
            }
        }
        return out;
    }
}
