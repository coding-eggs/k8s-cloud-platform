package com.coding.common.models.pipeline;

/**
 * 一条**被授权、且已解密**的平台连接，随画布一起交给渲染方。
 *
 * <p>⚠️ <b>它带明文凭据，所以有两件事绝不能发生</b>：
 * <ol>
 *   <li><b>不能进渲染结果</b> —— 凭据一旦写进 step 的 script，就会出现在 PipelineRun 的 spec 里，
 *       而 {@code /pipeline/preview} 会把渲染结果**原样给用户看**。所以脚本只读**挂进来的文件**，
 *       凭据本身只用于在集群里建一个 Secret。</li>
 *   <li><b>不能进日志与预览路径</b> —— {@code render} 与 {@code renderYaml} 的入参**不带**连接；
 *       只有「创建运行」那条路径才带。</li>
 * </ol>
 *
 * <p>它在**同机回环**上走一趟（platform-api → k8s-server），用的是已有那条带共享密钥之外的服务内通道；
 * 不落盘、不缓存。
 */
public record PipelineConnectionRef(String id, String endpoint, String username, String secret,
                                    /**
                                     * 这条连接**固定的镜像项目**（Harbor 自动建的凭据才有，如 {@code platform}
                                     * / {@code t-<sa>}）；null = 没有这个概念（git 连接、手建凭据、
                                     * Docker Hub 这类扁平仓库）。
                                     *
                                     * <p>为什么给渲染方：Harbor 的镜像名是 {@code <host>/<项目>/<仓库>}，
                                     * 项目是平台自己建出来的，让用户再手打一遍只会打错
                                     * （2026-10-29 实报：漏了项目段 → {@code harbor.x/k8s-server}，
                                     * Harbor 把它当成一个叫 {@code k8s-server} 的项目，直接拒）。
                                     */
                                    String project) {

    /** 老形状（无项目）：测试与手建连接的兼容构造。 */
    public PipelineConnectionRef(String id, String endpoint, String username, String secret) {
        this(id, endpoint, username, secret, null);
    }

    /**
     * 镜像引用里的项目段：{@code <host>} 后要接的那个名字。
     * <p>没有项目、或用户已经自己写了项目段（{@code platform/xxx}）时返回 null ——
     * 让调用方知道"不用再加"。
     *
     * @param repo 用户填的仓库路径（可能已带项目段）
     */
    public String missingProjectFor(String repo) {
        String p = project == null ? "" : project.trim();
        String r = repo == null ? "" : repo.trim();
        if (p.isEmpty()) {
            return null;
        }
        return r.equals(p) || r.startsWith(p + "/") ? null : p;
    }

    /**
     * 这条连接对应的**主机名**（去掉 scheme 与路径）：镜像引用、{@code .netrc} 的 machine、
     * docker {@code config.json} 的 auths 键要的都是它。
     *
     * <p>⚠️ <b>别把 endpoint 直接拼进镜像地址</b>：endpoint 是 URL（Harbor 那条现在是
     * {@code https://harbor.ackcp.cloud}），拼出来是 {@code https://…/proj/app:tag}，
     * buildah/docker 一律报 {@code invalid reference format}（2026-10-29 真跑撞到）。
     * 镜像引用里只允许 {@code host[:port]/repo[:tag]}，没有 scheme 这一层。
     */
    public String host() {
        return hostOf(endpoint);
    }

    /** endpoint → 主机名：{@code https://harbor.x:8443/path} → {@code harbor.x:8443}，裸 host 原样返回。 */
    public static String hostOf(String endpoint) {
        String e = endpoint == null ? "" : endpoint.trim();
        int scheme = e.indexOf("://");
        if (scheme >= 0) {
            e = e.substring(scheme + 3);
        }
        int slash = e.indexOf('/');
        return slash >= 0 ? e.substring(0, slash) : e;
    }
}
