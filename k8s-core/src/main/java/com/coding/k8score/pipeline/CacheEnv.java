package com.coding.k8score.pipeline;

import io.fabric8.kubernetes.api.model.EnvVar;

import java.util.List;

/**
 * 依赖缓存的**环境变量**：把各语言的缓存目录指到 {@link PipelineRenderer#CACHE_MOUNT}（{@code /cache}）。
 *
 * <h2>⚠️ 为什么一次给全套</h2>
 * 节点（或用户）只知道"我要缓存"，并不知道这一步里会跑 mvn 还是 npm ——
 * 而**没跑的工具会忽略自己那几个变量**（惰性读取），所以一次给全套既省事又不会写错。
 *
 * <p>⚠️ 前提是 {@code /cache} 那个 workspace **挂上了**：挂的是长期 PVC 就跨运行复用；
 * 退化成一个 emptyDir 时它照样存在、工具照写，只是**不跨运行复用**（见 k8s-server 的绑定逻辑）。
 */
public final class CacheEnv {

    private CacheEnv() {
    }

    public static List<EnvVar> of() {
        String c = PipelineRenderer.CACHE_MOUNT;
        return List.of(
                // Java
                env("MAVEN_OPTS", "-Dmaven.repo.local=" + c + "/m2"),
                env("GRADLE_USER_HOME", c + "/gradle"),
                // Node
                env("npm_config_cache", c + "/npm"),
                env("YARN_CACHE_FOLDER", c + "/yarn"),
                // Go（module 模式认 GOMODCACHE；GOCACHE 是编译缓存）
                env("GOMODCACHE", c + "/go-mod"),
                env("GOCACHE", c + "/go-build"),
                // Python
                env("PIP_CACHE_DIR", c + "/pip"));
    }

    private static EnvVar env(String name, String value) {
        return new EnvVar(name, value, null);
    }
}
