package com.coding.common.constants;

import java.util.List;

/**
 * 允许被探测的 CRD 清单（纯 CRD 组件的版本探测用）。
 *
 * <h2>语义</h2>
 * CRD 是<b>集群级</b>资源，不受 {@code platform.k8s.probe-namespaces} 的命名空间清单约束，
 * 所以另开一份清单：把一个 CRD 名加进来 = 允许任何已认证用户探测它是否存在、它的版本标签是什么。
 *
 * <h2>为什么是另一个清单而不是复用命名空间那个</h2>
 * 两者是不同的边界维度（一个是命名空间，一个是集群级对象名），混在一起会让人以为
 * "加个命名空间就顺带放开了 CRD"。清单越小越好。
 *
 * <p>⚠️ 与 {@link ProbeNamespaces} 同样：{@code DEFAULT_CSV} 是**字面常量**（{@code String.join}
 * 不是编译期常量，进不了 {@code @Value} 默认值），{@link #DEFAULT} 由它派生。
 */
public final class ProbeCrds {

    /** Gateway API 的入口 CRD —— 它带着 {@code gateway.networking.k8s.io/bundle-version} 标签 */
    public static final String DEFAULT_CSV = "gateways.gateway.networking.k8s.io";

    public static final List<String> DEFAULT = List.of(DEFAULT_CSV.split(","));

    private ProbeCrds() {
    }
}
