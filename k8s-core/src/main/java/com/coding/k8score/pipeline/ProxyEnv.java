package com.coding.k8score.pipeline;

import io.fabric8.kubernetes.api.model.EnvVar;

import java.util.List;

/**
 * 代理相关的**环境变量**。节点里填了代理就走这里渲染成 step 的 env。
 *
 * <h2>⚠️ 为什么不写进命令而是写进 env</h2>
 * 一个 step 里干活的不止一个工具：git 走 libcurl、buildah 走 Go 的 net/http、
 * 将来的 mvn/npm/go 各有各的下载器 —— 它们**都**认这几个环境变量，而命令行参数各写各的。
 * env 是一处写、全体生效。
 *
 * <h2>⚠️ 两个大小写都写</h2>
 * git / curl 认小写（`https_proxy`），Go 的 net/http 认大写（`HTTPS_PROXY`）——
 * 只写一份总有一半工具看不见。
 *
 * <p>⚠️ <b>有意不支持「带账号密码的代理」</b>：那属于凭据，写进节点配置就会跟着画布
 * 进 CanvasNode、进预览 YAML、进运行快照 —— 与「凭据只以文件形式进 Pod」直接冲突。
 * 真需要认证代理时，该给它开一条凭据类型，而不是在这里塞 URL。
 */
public final class ProxyEnv {

    /**
     * 直连名单。**必须带上集群内网段与 {@code .svc}** —— 否则 K8s API、内网 gitlab/harbor
     * 都会绕到代理上去，轻则变慢，重则（代理在外网）直接不通。
     */
    static final String NO_PROXY =
            "127.0.0.1,localhost,.svc,.cluster.local,10.0.0.0/8,172.16.0.0/12,192.168.0.0/16";

    private ProxyEnv() {
    }

    /** 代理地址为空 = 直连：**一个变量都不加**（不是加个空值）。 */
    public static List<EnvVar> of(String proxy) {
        if (proxy == null || proxy.isBlank()) {
            return List.of();
        }
        String p = proxy.trim();
        return List.of(
                env("HTTP_PROXY", p), env("HTTPS_PROXY", p),
                env("http_proxy", p), env("https_proxy", p),
                env("NO_PROXY", NO_PROXY), env("no_proxy", NO_PROXY));
    }

    private static EnvVar env(String name, String value) {
        return new EnvVar(name, value, null);
    }
}
