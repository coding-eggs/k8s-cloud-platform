package com.coding.common.constants;

import java.util.List;

/**
 * 允许被探测的命名空间清单（组件探测用）。
 *
 * <h2>语义（为什么这个清单重要）</h2>
 * 把一个命名空间加进这个清单，等于**允许任何已认证用户探测该命名空间内工作负载的名称、镜像与副本数**。
 * 它是一道可审计的规则，不是随口给的默认值 —— k8s-server 的 {@code /workload-probes}
 * 是唯一一条能按命名空间读工作负载的路径。
 *
 * <h2>两个模块共用同一份默认值</h2>
 * k8s-server 拿它当配置项默认值（{@code platform.k8s.probe-namespaces}），platform-api 的目录
 * 一致性测试拿它断言"目录里的候选都落在清单内"。**不能各写一份** —— 写两份的结果是
 * 平台侧发出越界探测、被 400 拒掉，而症状是页面上写着"未安装"。
 *
 * <h2>为什么是"字面 CSV → split"而不是"List → join"</h2>
 * {@code @Value("${platform.k8s.probe-namespaces:" + DEFAULT_CSV + "}")} 要求 {@code DEFAULT_CSV}
 * 是**编译期常量**；{@code String.join(...)} 是方法调用，不是常量表达式，写进注解编译不过。
 * 因此以 CSV 为唯一字面来源，{@link #DEFAULT} 由它派生（两者不会各说一套）。
 */
public final class ProbeNamespaces {

    /**
     * 目录真正要探的那几个；清单越小越好。
     * <p>⚠️ 改动这里等于改边界：加进来的命名空间即对任何已认证用户可探测。
     */
    public static final String DEFAULT_CSV =
            "kube-system,istio-system,monitor,tekton-pipelines,tekton-operator,"
                    + "cert-manager,calico-system,tigera-operator,opentelemetry-operator-system,"
                    + "platform-storage";

    /** 供代码侧比较用（如 platform-api 的目录一致性测试） */
    public static final List<String> DEFAULT = List.of(DEFAULT_CSV.split(","));

    private ProbeNamespaces() {
    }
}
