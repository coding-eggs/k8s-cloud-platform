package com.coding.k8score.pipeline.nodes;

import com.coding.k8score.pipeline.NodeContext;
import com.coding.k8score.pipeline.PipelineBaseImages;
import com.coding.k8score.pipeline.NodeField;
import com.coding.k8score.pipeline.NodeSchema;
import com.coding.k8score.pipeline.PipelineNode;
import com.coding.k8score.pipeline.RenderedStep;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Go 构建。依赖缓存默认开着（{@code GOMODCACHE} / {@code GOCACHE}）。
 *
 * <p>⚠️ <b>GOPROXY 有默认值，而且是必须的</b>：2026-10-15 实测 {@code proxy.golang.org}
 * 从 Pod 里**不通**（解析到 Google 的 IP 后连不上），而 Maven Central / npm / PyPI 都通。
 * 所以这个节点默认走国内的镜像，并在高级里留了改的地方（内网 Athens 之类）。
 */
@Component
public class GoBuildNode implements PipelineNode {

    private static final String DEFAULT_IMAGE = PipelineBaseImages.GOLANG_ALPINE;
    private static final String DEFAULT_COMMAND = "go build ./...";

    /** 见类注释：默认值不是可有可无的美化，是这个集群能不能拉到依赖的分界线。 */
    static final String DEFAULT_GOPROXY = "https://goproxy.cn,direct";

    @Override
    public String type() {
        return "go";
    }

    @Override
    public String label() {
        return "Go 构建";
    }

    @Override
    public String description() {
        return "go build，依赖缓存在共享卷里跨运行复用";
    }

    @Override
    public String icon() {
        return "Lightning";
    }

    @Override
    public String accent() {
        return "info";
    }

    @Override
    public List<String> summaryFields() {
        return List.of("command");
    }

    @Override
    public NodeSchema formSchema() {
        return BuildStep.schema(DEFAULT_IMAGE, DEFAULT_COMMAND, "构建命令",
                "例：\n"
                        + "  go build ./...\n"
                        + "  go test ./... && go build -o /workspace/app ./cmd/server",
                List.of(NodeField.advancedText("goproxy", "GOPROXY（高级）",
                        "Go 的模块代理。留空 = " + DEFAULT_GOPROXY + "\n"
                                + "为什么有默认值：proxy.golang.org 从 Pod 里连不通（实测），\n"
                                + "走 `direct` 又会直连各个仓库、慢且容易被卡。内网有 Athens 之类就填它。")));
    }

    @Override
    public RenderedStep render(NodeContext ctx) {
        String proxy = ctx.value("goproxy");
        return BuildStep.render(ctx, DEFAULT_IMAGE, DEFAULT_COMMAND,
                List.of(BuildStep.env("GOPROXY", proxy.isEmpty() ? DEFAULT_GOPROXY : proxy)));
    }
}
