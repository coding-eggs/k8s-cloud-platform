package com.coding.k8score.pipeline.nodes;

import com.coding.k8score.pipeline.NodeContext;
import com.coding.k8score.pipeline.PipelineBaseImages;
import com.coding.k8score.pipeline.NodeSchema;
import com.coding.k8score.pipeline.PipelineNode;
import com.coding.k8score.pipeline.RenderedStep;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Java / Maven 构建。**依赖缓存默认开着** —— 这正是它相对「命令」节点的价值：
 * 用户不需要知道 {@code /cache} 的存在，第二次构建就不必再下一遍 jar
 * （缓存卷的来源见 {@code PipelineRunOperations}：长期 PVC，拿不到就退化成空目录）。
 *
 * <p>默认镜像 {@code maven:3.9-eclipse-temurin-21} 由 kubelet 拉取（走节点的镜像源），
 * 所以内网/离线集群只要能拉到它就不必改成自己的 Harbor；要改就在「镜像」里填。
 */
@Component
public class JavaBuildNode implements PipelineNode {

    private static final String DEFAULT_IMAGE = PipelineBaseImages.MAVEN;
    private static final String DEFAULT_COMMAND = "mvn -B -DskipTests package";

    @Override
    public String type() {
        return "java";
    }

    @Override
    public String label() {
        return "Java 构建";
    }

    @Override
    public String description() {
        return "Maven 构建，依赖缓存在共享卷里跨运行复用";
    }

    @Override
    public String icon() {
        return "Coffee";
    }

    @Override
    public String accent() {
        return "warning";
    }

    @Override
    public List<String> summaryFields() {
        return List.of("command");
    }

    @Override
    public NodeSchema formSchema() {
        return BuildStep.schema(DEFAULT_IMAGE, DEFAULT_COMMAND, "构建命令",
                "写完整的 Maven 命令，开头的 mvn 不能省。例：\n"
                        + "  mvn -B -DskipTests package（打 jar，跳过测试）\n"
                        + "  mvn -B clean verify（跑完整校验）\n"
                        + "只写后面的参数（如 clean package）会被当命令去执行 —— 报 clean: not found。",
                List.of());
    }

    @Override
    public RenderedStep render(NodeContext ctx) {
        return BuildStep.render(ctx, DEFAULT_IMAGE, DEFAULT_COMMAND, List.of());
    }
}
