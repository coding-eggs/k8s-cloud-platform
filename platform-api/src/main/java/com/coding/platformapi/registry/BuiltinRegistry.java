package com.coding.platformapi.registry;

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
 * <p>⚠️ <b>2026-10-15：同一个 Docker Hub 可以同时有内置档与凭据行</b> ——
 * 内置档（这一份）匿名、只答公共镜像；凭据行（{@code type=DOCKERHUB}，用户名 + PAT）
 * 只答"我名下"的仓库。两者并存不是重复：方言按这条 ref **有没有凭据**决定自己答哪一半。
 * （2026-10-14 曾靠 {@code isBuiltinType} 把同类型凭据行整条挡掉，那个方法已随这次改定删除。）
 *
 * <p>⚠️ <b>开关不是在这里，是配置</b>：{@code platform.registry.dockerhub.enabled}（默认 true）。
 * 关掉它，{@code allSources()} 就不会把它算进来 —— 隔离内网里没人希望每次按键都朝一个
 * 不可达的地址打一发。
 */
public final class BuiltinRegistry {

    /** 唯一的那个。三个值都是常量：Docker Hub 没有"用户自己的地址"，这一份也**只允许匿名**。 */
    public static final BuiltinRegistry DOCKER_HUB =
            new BuiltinRegistry("builtin:dockerhub", "Docker Hub", "DOCKERHUB", "docker.io");

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
}
