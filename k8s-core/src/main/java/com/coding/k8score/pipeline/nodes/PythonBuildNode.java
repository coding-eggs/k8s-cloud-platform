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
 * Python 构建。依赖缓存默认开着（{@code PIP_CACHE_DIR=/cache/pip} —— 缓存的是下载下来的包，
 * 不是装好的环境，所以每个运行的虚拟环境仍然是干净的）。
 *
 * <p>⚠️ 安装是**装进容器**、不落共享卷：要是把 site-packages 也指到 {@code /cache}，
 * 两条流水线并发时会互相踩环境。要可复现的环境，正经做法是在这里跑
 * {@code pip install -r requirements.txt}（或 poetry）之后再做构建/打包。
 */
@Component
public class PythonBuildNode implements PipelineNode {

    private static final String DEFAULT_IMAGE = PipelineBaseImages.PYTHON;
    private static final String DEFAULT_COMMAND = "pip install -r requirements.txt";

    @Override
    public String type() {
        return "python";
    }

    @Override
    public String label() {
        return "Python 构建";
    }

    @Override
    public String description() {
        return "装依赖并构建，下载缓存跨运行复用";
    }

    @Override
    public String icon() {
        return "DataLine";
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
                "例：\n"
                        + "  pip install -r requirements.txt\n"
                        + "  pip install -r requirements.txt && python -m build\n"
                        + "  用 poetry 时：pip install poetry && poetry install --no-interaction",
                List.of(NodeField.advancedText("indexUrl", "pip 源（高级）",
                        "留空 = 官方 PyPI（实测从 Pod 里可达）。内网有 devpi / Nexus 就填它的 simple 地址。")));
    }

    @Override
    public RenderedStep render(NodeContext ctx) {
        String indexUrl = ctx.value("indexUrl");
        return BuildStep.render(ctx, DEFAULT_IMAGE, DEFAULT_COMMAND,
                indexUrl.isEmpty() ? List.of() : List.of(BuildStep.env("PIP_INDEX_URL", indexUrl)));
    }
}
