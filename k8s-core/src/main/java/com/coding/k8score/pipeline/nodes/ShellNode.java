package com.coding.k8score.pipeline.nodes;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.k8score.pipeline.*;
import io.fabric8.tekton.v1.Step;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 通用命令节点。**所有字段都可改** —— 预设只负责预填默认值。
 *
 * <p>「跑测试」就是一个预设；**「跳过测试」= 不连那个节点**（自由 DAG 天然支持，不做开关）。
 */
@Component
public class ShellNode implements PipelineNode {

    /** 见 {@link GitCloneNode#WS} —— 与 Tekton 的默认 workspace 挂载点对齐。 */
    static final String WS = "/workspace";

    @Override
    public String type() {
        return "shell";
    }

    @Override
    public String label() {
        return "命令";
    }

    @Override
    public String description() {
        return "在任意镜像里跑一段命令（跑测试就是它的一个预设）";
    }

    @Override
    public String icon() {
        return "Tools";
    }

    @Override
    public String accent() {
        return "accent";
    }

    @Override
    public List<String> summaryFields() {
        return List.of("image");
    }

    @Override
    public NodeSchema formSchema() {
        return new NodeSchema(List.of(
                NodeField.select("preset", "预设（只是默认值，下面都能改）", BuildPreset.options(), "custom"),
                NodeField.image("image", "镜像"),
                NodeField.text("command", "命令（可多行）", true,
                        "在共享 workspace 里按顺序执行；任一行失败就中止（Tekton 默认 set -e）。\n"
                                + "前面 git 克隆放下的代码就在工作目录里。\n"
                                + "例：mvn -B -DskipTests package"),
                NodeField.text("workDir", "工作目录（相对 workspace）", false,
                        "留空 = 共享 workspace 的根目录。\n"
                                + "例：多模块仓库里只跑前端 → 填 frontend"),
                NodeField.advancedCheckbox("cache", "复用依赖缓存",
                        "把 Maven / npm / Go / pip 的缓存目录指到 /cache —— 跨运行保留，\n"
                                + "第二次构建就不用再下一遍依赖（10Gi 的共享卷，按命名空间一块）。\n"
                                + "没跑到的工具会忽略自己那几个变量，所以选了 java 预设也不影响。\n"
                                + "⚠️ 这个集群的存储若不支持共享卷，缓存会自动关掉（照样能跑，只是每次重下）")));
    }

    @Override
    public RenderedStep render(NodeContext ctx) {
        BuildPreset preset = BuildPreset.of(ctx.value("preset"));
        // ⚠️ 用户填了就用用户的；没填才回落到预设 —— 这是「预设只是默认值」的落点
        String image = ctx.value("image");
        if (image.isEmpty()) {
            image = preset.imageIfSet().orElseThrow(() -> new CloudPlatformException(
                    EnumResponseType.PIPELINE_INVALID, "镜像必填（选一个预设，或自己填一个）"));
        }
        String script = ctx.value("command");
        if (script.isEmpty()) {
            script = preset.commandIfSet().orElseThrow(() -> new CloudPlatformException(
                    EnumResponseType.PIPELINE_INVALID, "命令必填（选一个预设，或自己填一段）"));
        }

        Step step = new Step();
        step.setName("run");
        step.setImage(image);
        step.setWorkingDir(resolveWorkDir(ctx.value("workDir")));
        step.setScript(script);
        // 依赖缓存（可选）：环境变量 + 一个 cache workspace，挂载与卷的来源由渲染/绑定层决定
        if ("true".equals(ctx.value("cache"))) {
            step.setEnv(CacheEnv.of());
            return new RenderedStep(List.of(step), List.of(new RenderedStep.WorkspaceRef(
                    PipelineRenderer.CACHE_WORKSPACE, PipelineRenderer.CACHE_MOUNT)));
        }
        return new RenderedStep(List.of(step));
    }

    /**
     * 把用户填的工作目录解析成绝对路径。
     *
     * <p>空 / {@code .} / {@code ./} 都归到 workspace 根 —— 不然会渲染出 {@code /workspace/.}
     * 这种路径：Tekton 收得下，但日志里看一眼就让人怀疑是不是配错了。
     */
    private static String resolveWorkDir(String raw) {
        String dir = raw == null ? "" : raw.trim();
        while (dir.startsWith("./")) {
            dir = dir.substring(2);
        }
        while (dir.endsWith("/")) {
            dir = dir.substring(0, dir.length() - 1);
        }
        return dir.isEmpty() || ".".equals(dir) ? WS : WS + "/" + dir;
    }
}
