package com.coding.common.models.image;

import java.util.Locale;
import java.util.Set;

/**
 * 镜像纳管的**排除名单**：这些 chart 的镜像必须能**先于镜像仓库可用**，所以固定走上游官方源。
 *
 * <p>名单只有一个来源，三处都读它：k8s-server 决定改写时跳过、platform-api 决定要不要把仓库地址
 * 传给渲染方、前端决定表单里还给不给「平台 Harbor」这个选项。
 *
 * <p>目前只有 calico（{@code tigera-operator}）：2026-10-29 实测，把它指到平台 Harbor 后，
 * 改 {@code Installation.spec.registry} 会让 operator 一次性滚动重建 calico-node / typha /
 * kube-controllers；单节点集群上 CNI 一断，新 Pod 的沙箱全卡住，新来的 operator Pod 自己也要 CNI，
 * {@code helm --wait} 900s 超时 → 平台 {@code --rollback-on-failure} 把改动原样撤回。
 * 本质是自举问题：换 CNI 的镜像，得先有 CNI。
 *
 * <p>Harbor 组件本身永远走官方是另一条规则（按组件 code 判断，见 {@code ComponentInstallService}）——
 * 那是镜像仓库自举，与这里按 chart 判的名单互不相干。
 */
public final class ImageMirroring {

    private static final Set<String> OFFICIAL_ONLY_CHARTS = Set.of("tigera-operator");

    private ImageMirroring() {
    }

    /** 这个 chart 的镜像是否固定走官方（不参与改写） */
    public static boolean officialOnlyChart(String chartCode) {
        return chartCode != null && OFFICIAL_ONLY_CHARTS.contains(chartCode.trim().toLowerCase(Locale.ROOT));
    }
}
