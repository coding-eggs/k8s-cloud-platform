package com.coding.k8score.pipeline;

import java.util.List;
import java.util.Optional;

/**
 * 语言预设：**数据，且只是默认值**（用户 2026-10-15 明确要求「预设节点只是预设，
 * 用户组建流水线的时候可以更改，如镜像，命令等」）。
 *
 * <p>所以没有预设的语言（Rust / PHP / Scala…）→ 选 {@code custom} 或任意一项、改掉镜像与命令即可，
 * <b>平台零代码改动</b>。「加一种语言」= 加**一行**。
 */
public record BuildPreset(String key, String label, String image, String command) {

    private static final List<BuildPreset> ALL = List.of(
            new BuildPreset("java-build", "Java · 构建", "maven:3.9-eclipse-temurin-21", "mvn -B -DskipTests package"),
            new BuildPreset("java-test", "Java · 测试", "maven:3.9-eclipse-temurin-21", "mvn -B test"),
            new BuildPreset("go-build", "Go · 构建", "golang:1.22", "go build ./..."),
            new BuildPreset("go-test", "Go · 测试", "golang:1.22", "go test ./..."),
            new BuildPreset("node-build", "Node · 构建", "node:20", "npm ci && npm run build"),
            new BuildPreset("node-test", "Node · 测试", "node:20", "npm test"),
            new BuildPreset("custom", "自定义", "", ""));

    public static List<BuildPreset> all() {
        return ALL;
    }

    /** @return 找不到返回 {@code custom}（而不是抛）—— 预设只是默认值，不是必填 */
    public static BuildPreset of(String key) {
        return ALL.stream().filter(p -> p.key().equals(key)).findFirst()
                .orElse(ALL.get(ALL.size() - 1));
    }

    public static List<NodeField.Option> options() {
        return ALL.stream().map(p -> new NodeField.Option(p.key(), p.label())).toList();
    }

    public Optional<String> imageIfSet() {
        return image.isBlank() ? Optional.empty() : Optional.of(image);
    }

    public Optional<String> commandIfSet() {
        return command.isBlank() ? Optional.empty() : Optional.of(command);
    }
}
