package com.coding.common.models.k8s.dto;

/**
 * 「这块存储类**真能不能**在线扩容」的三态取值。
 *
 * <p>⚠️ 与 {@code StorageClassDTO.allowVolumeExpansion} 不是一回事：那个只表示「**允许**扩」，
 * 而真正执行扩容的是 CSI 驱动的 {@code external-resizer}。非 CSI 的 provisioner
 * （nfs-subdir、local-path 这类）改了也只会把请求改大，{@code status.capacity} 一动不动。
 * 2026-10-16 真撞到：集群两个 SC 都写着 {@code allowVolumeExpansion: true}，两个都做不到。
 *
 * <p>取值放在 platform-common 而不是算它们的地方（k8s-core）：判据要跨模块用 ——
 * k8s-core 算它、platform-api 拿它当扩容的门控。字符串常量各写一份必然会漂。
 * **唯一的算法**在 {@code CoreV1StorageClassOperations.fillExpansion}。
 */
public final class VolumeExpansion {

    /** {@code allowVolumeExpansion=false}：明确不支持。 */
    public static final String UNSUPPORTED = "unsupported";

    /** 允许 + provisioner 是已注册的 CSI 驱动 → 正常情况下有人会执行扩容。 */
    public static final String CSI = "csi";

    /** 允许，但 provisioner 不是 CSI 驱动 → **没人会执行**（最反直觉、也最该被说出来的那一态）。 */
    public static final String UNBACKED = "unbacked";

    private VolumeExpansion() {
    }
}
