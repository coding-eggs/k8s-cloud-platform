package com.coding.platformapi.registry;

import java.util.List;

/**
 * 平台**自带的**镜像仓库来源 —— <b>不是一条 {@code platform_credential} 记录</b>。
 *
 * <h3>为什么单独有这个东西</h3>
 * Docker Hub 是公共镜像的来源，用户裁定它「不用建连接、不用填任何东西」
 * （2026-10-14）。所以它不走凭据表：没有行、没有 authType、没有密文、没有启停状态。
 *
 * <p>⚠️ <b>它是平台里第一条「不是连接的镜像仓库来源」</b>，是一次模型扩张。
 * 代价用 {@code RegistryService.allSources()} 压住了 —— 「本次要问哪些来源」只有那一个收敛点，
 * 下游（聚合 / 并发 / 排序 / 候选带来源名 / 浏览）全都只认它吐出来的 {@code Source} 形状，
 * 不需要知道有"内置"这回事。
 *
 * <p>⚠️ <b>开关不是在这里，是配置</b>：{@code platform.registry.dockerhub.enabled}（默认 true）。
 * 关掉它，{@code allSources()} 就不会把它算进来 —— 隔离内网里没人希望每次按键都朝一个
 * 不可达的地址打一发。
 */
public final class BuiltinRegistry {

    /** 唯一的那个。三个值都是常量：Docker Hub 没有"用户自己的地址"，也**只允许匿名**。 */
    public static final BuiltinRegistry DOCKER_HUB =
            new BuiltinRegistry("builtin:dockerhub", "Docker Hub", "DOCKERHUB", "docker.io");

    private static final List<BuiltinRegistry> ALL = List.of(DOCKER_HUB);

    private final String id;
    private final String displayName;
    private final String type;
    private final String endpoint;

    private BuiltinRegistry(String id, String displayName, String type, String endpoint) {
        this.id = id;
        this.displayName = displayName;
        this.type = type;
        this.endpoint = endpoint;
    }

    /**
     * 浏览页下拉里的**合成 id**。它不是 {@code platform_credential.id} ——
     * {@code /registry/browse} 要认这个值（那边的实现是拿它去 {@code allSources()} 里找，
     * 不是 {@code mapper.selectById}）。
     */
    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    public String type() {
        return type;
    }

    public String endpoint() {
        return endpoint;
    }

    /**
     * 这个 type 是不是由某个内置档提供（而不是由凭据行提供）。
     *
     * <p>{@code RegistryService} 用它把「库里残留的同类型凭据行」挡掉 ——
     * 否则同一个 Docker Hub 会在下拉里出现两次（一次内置、一次凭据）。
     * 该 type 已从 {@code CredentialService.TYPES} 移除，正常建不出来，但老库里可能有。
     */
    public static boolean isBuiltinType(String type) {
        return ALL.stream().anyMatch(b -> b.type.equals(type));
    }
}
