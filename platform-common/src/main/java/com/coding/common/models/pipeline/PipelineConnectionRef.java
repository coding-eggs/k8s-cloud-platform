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
public record PipelineConnectionRef(String id, String endpoint, String username, String secret) {

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
