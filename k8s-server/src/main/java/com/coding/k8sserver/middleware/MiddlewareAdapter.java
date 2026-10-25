package com.coding.k8sserver.middleware;

import com.coding.common.models.middleware.MiddlewareCreateRequestDTO;
import com.coding.common.models.middleware.MiddlewareScaleRequestDTO;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext;

import java.util.List;
import java.util.Map;

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

    /**
     * 实例产生的 Pod 的 label selector（状态判定用）。
     *
     * <p>{@code kind} 是本次判定找到的那个 CR 的 kind（Redis 哨兵形态有两个 CR，
     * 哨兵 Pod 的 app 标签是 {@code <name>-sentinel}，必须按 kind 区分）。
     */
    String statusSelector(String name, String kind);

    /**
     * 带实例 id 的版本：能用平台自己的标签（{@code platform.coding/middleware-instance}）时优先用它。
     * <p>Redis 哨兵形态的数据 Pod 是 {@code app=<name>}、哨兵 Pod 是 {@code app=<name>-sentinel}，
     * 拼集合式 selector 在 fabric8 里只命中前者（真集群验证：5 个 Pod 只数到 2 个）。
     */
    default String statusSelector(String name, String kind, String instanceId) {
        return statusSelector(name, kind);
    }

    /**
     * 是否由平台生成并写入口令 Secret。
     * <p>默认 true（Redis/Nacos 这类需要初始口令）；Percona MySQL 由 Operator 自己生成
     * {@code <cr>-secrets}，平台写一个没人引用的 Secret 只会造成误导。
     */
    default boolean usesPlatformPassword() {
        return true;
    }

    /**
     * 平台要额外写的凭据 Secret 名（不属于"初始口令"；目前只有 Milvus 的对象存储 AK/SK）。
     * <p>null = 本中间件不需要。
     */
    default String extraSecretName(String name) {
        return null;
    }

    /** extraSecretName 非空时写入的键值；返回空 map = 请求缺必填字段（调用方报错）。 */
    default Map<String, String> extraSecretData(MiddlewareCreateRequestDTO req) {
        return Map.of();
    }

    /**
     * 本中间件自己的 CR 异常判定（共享的 crFailureMessage 只认 Ready/Available condition）。
     * <p>返回 null = 没发现问题。
     */
    default String crProblem(GenericKubernetesResource live) {
        return null;
    }

    /**
     * 附属组件的异常（典型：ES 勾选的 Kibana）。返回非空时实例状态降级 ——
     * 主实例的全部 Pod 就绪不代表附属组件可用，不能报「运行中」。
     */
    default String accessoryProblem(KubernetesClient client, String namespace, String name) {
        return null;
    }

    /**
     * 该实例期望的 Pod 数（状态判定用）。返回 null = CR 里推不出来，
     * 此时只能按现存 Pod 是否全就绪判定（Kafka 的期望值在 NodePool 里，见实现注释）。
     */
    default Integer desiredPods(GenericKubernetesResource live, String kind) {
        return null;
    }

    /**
     * CR 里是否真的装了某个可选插件（目前只有 ES 的 IK 分词器）。
     *
     * <p>详情页回显「已安装」以 CR 为准而不是台账：台账 spec_json 只在编辑补装成功时回写，
     * 创建时就勾上的实例库里可能没有标记。{@code null} = 不适用或 CR 没读到（调用方自行兜底）。
     */
    default Boolean ikInstalled(GenericKubernetesResource live) {
        return null;
    }

    /**
     * 业务指标的抓取规格（平台据此建 Service + ServiceMonitor）。
     *
     * @param port        指标端口（容器内数字；Service 的 targetPort 用它，避免依赖 Operator 的容器端口名）
     * @param path        指标路径
     * @param scheme      http / https
     * @param podSelector Service 选 Pod 的 label selector（"k=v,k2=v2"），须只命中真正带 exporter 的 Pod
     */
    record MetricsSpec(int port, String path, String scheme, String podSelector) {
    }

    /** 业务指标规格；null = 该中间件本版不采业务指标（或不归平台建采集资源，如 PG 用 CR 自带开关）。 */
    default MetricsSpec metricsSpec(String name, String kind, String instanceId) {
        return null;
    }

    /**
     * 编辑时补开业务指标：把 exporter / metricsConfig 幂等地写进**线上 CR**（返回 true = 有改动，需要落盘）。
     *
     * <p>默认 false：PG / RabbitMQ 的指标源是 Operator 内置或默认插件，不需要改 CR —— 只建采集资源即可。
     */
    default boolean ensureMetrics(GenericKubernetesResource live, CustomResourceDefinitionContext context,
                                  MiddlewareScaleRequestDTO req) {
        return false;
    }

    /**
     * 采集资源：`<名字>-platform-metrics` 的 Service + ServiceMonitor（实现见 {@link MetricsResources}）。
     *
     * <p>用 Service 而不是 PodMonitor：本集群的 PodMonitor CRD 没有 `targetPort`，只能引用容器端口名，
     * 而各 Operator 的端口命名不归平台管；Service 的端口名由平台定义（固定 `metrics`）。
     */
    default List<CrSpec> buildMetricsResources(MiddlewareCreateRequestDTO req, MetricsSpec spec) {
        return MetricsResources.build(req, spec);
    }

    /** 供单测断言：selector 字符串 → map（Service 的 spec.selector）。 */
    static java.util.Map<String, String> parseSelector(String selector) {
        return MetricsResources.parseSelector(selector);
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
