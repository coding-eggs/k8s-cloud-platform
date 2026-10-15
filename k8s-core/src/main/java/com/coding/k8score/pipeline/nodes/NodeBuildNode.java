package com.coding.k8score.pipeline.nodes;

import com.coding.k8score.pipeline.NodeContext;
import com.coding.k8score.pipeline.NodeSchema;
import com.coding.k8score.pipeline.PipelineNode;
import com.coding.k8score.pipeline.RenderedStep;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Node / npm 构建。依赖缓存默认开着（{@code npm_config_cache=/cache/npm}）。
 *
 * <p>默认命令用 {@code npm ci}：它按 lockfile 装、可重现，是 CI 该用的那个
 * （{@code npm install} 会改 lockfile，在 CI 里是反模式）。
 */
@Component
public class NodeBuildNode implements PipelineNode {

    private static final String DEFAULT_IMAGE = "node:20-alpine";
    private static final String DEFAULT_COMMAND = "npm ci && npm run build";

    @Override
    public String type() {
        return "node";
    }

    @Override
    public String label() {
        return "Node 构建";
    }

    @Override
    public String description() {
        return "npm 安装与构建，依赖缓存在共享卷里跨运行复用";
    }

    @Override
    public String icon() {
        return "Box";
    }

    @Override
    public String accent() {
        return "success";
    }

    @Override
    public List<String> summaryFields() {
        return List.of("command");
    }

    @Override
    public NodeSchema formSchema() {
        return BuildStep.schema(DEFAULT_IMAGE, DEFAULT_COMMAND, "构建命令",
                "默认 npm ci && npm run build（ci 按 lockfile 装，可重现）。\n"
                        + "例：\n"
                        + "  npm ci && npm run build\n"
                        + "  pnpm install --frozen-lockfile && pnpm build（换成 pnpm 时镜像也要换）",
                List.of());
    }

    @Override
    public RenderedStep render(NodeContext ctx) {
        return BuildStep.render(ctx, DEFAULT_IMAGE, DEFAULT_COMMAND, List.of());
    }
}
