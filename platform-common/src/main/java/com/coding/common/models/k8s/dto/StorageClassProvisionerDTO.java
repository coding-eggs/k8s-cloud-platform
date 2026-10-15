package com.coding.common.models.k8s.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * StorageClass **窄投影**：只回名字与 provisioner。
 *
 * <p><b>为什么需要它</b>：CI 的能力探针要问「这个集群有没有一块能**动态供应**的 StorageClass」
 * （作品的隐式共享 workspace 用 {@code volumeClaimTemplate}，静态盘会让每次运行卡在 PVC 绑定等待）。
 * 而列集群级 StorageClass 有两道墙：
 * <ol>
 *   <li>租户 SA 的 K8s RBAC 里没有 {@code storageclasses}，**必须用 admin client**；</li>
 *   <li>通用端点 {@code /storageclasses/list} 的边界是 {@code PLATFORM}（V2026_10_07_3 有意收成
 *       平台管理员专属），租户调用方一律 403。</li>
 * </ol>
 * 于是与 {@link NodeAddressDTO} 同形：**单开一个窄投影端点**（{@code POST /storageclasses/provisioners}，
 * {@code UPSTREAM} 边界 —— 本层不做边界判定，收窄在上游 platform-api 的 {@code tenant:pipeline:read}）。
 *
 * <p><b>为什么不直接回 {@link StorageClassDTO}</b>：那个 DTO 带 {@code parameters}，里面可能有
 * NFS server 地址、密钥名之类的东西 —— 而本端点的消费者只需要判断"有没有一块动态的"。
 *
 * <p>非 {@code BaseResources} 子类：没有 apiPath，不是六端点资源 CRUD 的载体。
 */
@Data
@Schema(description = "StorageClass 窄投影（name + provisioner；CI 能力探针用）")
public class StorageClassProvisionerDTO {

    @Schema(description = "StorageClass 名")
    private String name;

    /** 空 / {@code kubernetes.io/no-provisioner} = 静态盘，跑 CI 会在 PVC 绑定上卡住 */
    @Schema(description = "provisioner")
    private String provisioner;
}
