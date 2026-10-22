package com.coding.k8sserver.middleware;

import com.coding.common.models.middleware.MiddlewareCreateRequestDTO;
import com.coding.common.models.middleware.MiddlewareScaleRequestDTO;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext;

import java.util.List;

/**
 * 一款中间件的 CR 适配器：表单规格 → CR，以及状态判定所需的 selector。
 *
 * <p>按既有原则**不抽公共基类**：每款中间件的 CRD、字段、状态语义都不一样，
 * 这里只约定最小的接口面（接口用于按 code 分派，不是实现继承）。
 */
public interface MiddlewareAdapter {

    String code();

    /** 创建实例需要落下的全部 CR（哨兵是 RedisReplication + RedisSentinel 两个）。 */
    List<CrSpec> buildCreate(MiddlewareCreateRequestDTO req);

    /** 本适配器可能创建的 CRD（删除/状态/扩容时按名字逐个找）。 */
    List<CustomResourceDefinitionContext> contexts();

    /** 扩容：对线上 CR 就地改副本/资源；不支持的形态直接拒绝。 */
    void applyScale(GenericKubernetesResource live, CustomResourceDefinitionContext context,
                    MiddlewareScaleRequestDTO req);

    /** 实例产生的 Pod 的 label selector（状态判定用）。 */
    String statusSelector(String name);

    /**
     * 是否由平台生成并写入口令 Secret。
     * <p>默认 true（Redis/Nacos 这类需要初始口令）；Percona MySQL 由 Operator 自己生成
     * {@code <cr>-secrets}，平台写一个没人引用的 Secret 只会造成误导。
     */
    default boolean usesPlatformPassword() {
        return true;
    }

    /**
     * 删除实例且 keepPvc=false 时，用来找该实例 PVC 的 label selector。
     * <p>默认按平台标签（适配器在 volumeClaimTemplate 里自己打的）；
     * Operator 创建 PVC 的中间件（MySQL/Kafka/ES）覆写成各自 Operator 的标签。
     */
    default String pvcSelector(String name, String instanceId) {
        return "platform.coding/middleware-instance=" + instanceId;
    }

    /** 一个待创建的对象：CRD 上下文 + 资源体。 */
    record CrSpec(CustomResourceDefinitionContext context, GenericKubernetesResource resource) {
    }
}
