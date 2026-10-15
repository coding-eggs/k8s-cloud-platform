package com.coding.k8score.pipeline;

import com.coding.common.models.pipeline.PipelineConnectionRef;
import io.fabric8.kubernetes.client.utils.Serialization;
import io.fabric8.tekton.v1.PipelineSpec;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Comparator;
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
                .map(n -> new PipelineNodeTypeView(n.type(), n.formSchema().fields(), BuildPreset.all()))
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
     * <p>⚠️ 只有**创建运行**那条路径调这个重载 —— 预览与保存校验用上面那个不带连接的：
     * 那两种路径产出的东西会给用户看（预览页），不该让凭据参与其中。
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
}
