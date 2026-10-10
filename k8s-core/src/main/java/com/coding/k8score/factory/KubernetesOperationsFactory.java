package com.coding.k8score.factory;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.k8s.ResourceType;
import com.coding.data.mapper.k8s.K8sClusterMapper;
import com.coding.data.models.k8s.K8sCluster;
import com.coding.k8score.converter.impl.autoscaling.HpaV1Converter;
import com.coding.k8score.converter.impl.autoscaling.HpaV2Converter;
import com.coding.k8score.converter.impl.calico.BgpConfigurationConverter;
import com.coding.k8score.converter.impl.calico.BgpFilterConverter;
import com.coding.k8score.converter.impl.calico.BgpPeerConverter;
import com.coding.k8score.converter.impl.calico.IppoolConverter;
import com.coding.k8score.converter.impl.calico.IpReservationConverter;
import com.coding.k8score.converter.impl.core.CoreV1ConfigMapConverter;
import com.coding.k8score.converter.impl.core.CoreV1NamespaceConverter;
import com.coding.k8score.converter.impl.core.CoreV1NodeConverter;
import com.coding.k8score.converter.impl.core.CoreV1PodConverter;
import com.coding.k8score.converter.impl.core.CoreV1LimitRangeConverter;
import com.coding.k8score.converter.impl.core.CoreV1PvcConverter;
import com.coding.k8score.converter.impl.core.CoreV1ResourceQuotaConverter;
import com.coding.k8score.converter.impl.core.CoreV1PersistentVolumeConverter;
import com.coding.k8score.converter.impl.core.CoreV1SecretConverter;
import com.coding.k8score.converter.impl.core.CoreV1ServiceConverter;
import com.coding.k8score.converter.impl.core.CoreV1StorageClassConverter;
import com.coding.k8score.converter.impl.gateway.GatewayClassConverter;
import com.coding.k8score.converter.impl.gateway.GatewayConverter;
import com.coding.k8score.converter.impl.gateway.GrpcRouteConverter;
import com.coding.k8score.converter.impl.gateway.HttpRouteConverter;
import com.coding.k8score.converter.impl.gateway.TcpRouteConverter;
import com.coding.k8score.converter.impl.gateway.TlsRouteConverter;
import com.coding.k8score.converter.impl.gateway.UdpRouteConverter;
import com.coding.k8score.converter.impl.monitoring.PodMonitorConverter;
import com.coding.k8score.converter.impl.monitoring.ServiceMonitorConverter;
import com.coding.k8score.converter.impl.rbac.CoreV1ServiceAccountConverter;
import com.coding.k8score.converter.impl.rbac.RbacV1ClusterRoleConverter;
import com.coding.k8score.converter.impl.rbac.RbacV1RoleBindingConverter;
import com.coding.k8score.converter.impl.workload.AppsV1ReplicaSetConverter;
import com.coding.k8score.converter.impl.workload.WorkloadConverter;
import com.coding.k8score.operations.ClusterOperations;
import com.coding.k8score.operations.NamespacedOperations;
import com.coding.k8score.operations.calico.BgpConfigurationOperations;
import com.coding.k8score.operations.calico.BgpFilterOperations;
import com.coding.k8score.operations.calico.BgpPeerOperations;
import com.coding.k8score.operations.calico.CalicoFormOptionOperations;
import com.coding.k8score.operations.calico.CalicoIpamOperations;
import com.coding.k8score.operations.calico.IppoolOperations;
import com.coding.k8score.operations.calico.IpReservationOperations;
import com.coding.k8score.operations.autoscaling.HpaV1Operations;
import com.coding.k8score.operations.autoscaling.HpaV2Operations;
import com.coding.k8score.operations.core.ClusterAggregationOperations;
import com.coding.k8score.operations.core.CoreV1ConfigMapOperations;
import com.coding.k8score.operations.core.CoreV1LimitRangeOperations;
import com.coding.k8score.operations.core.CoreV1NamespaceOperations;
import com.coding.k8score.operations.core.CoreV1PersistentVolumeClaimOperations;
import com.coding.k8score.operations.core.CoreV1PersistentVolumeOperations;
import com.coding.k8score.operations.core.CoreV1NodeOperations;
import com.coding.k8score.operations.core.CoreV1PodOperations;
import com.coding.k8score.operations.core.CoreV1SecretOperations;
import com.coding.k8score.operations.core.CoreV1ServiceOperations;
import com.coding.k8score.operations.core.CoreV1ResourceQuotaOperations;
import com.coding.k8score.operations.core.CoreV1StorageClassOperations;
import com.coding.k8score.operations.gateway.GatewayClassOperations;
import com.coding.k8score.operations.gateway.GatewayOperations;
import com.coding.k8score.operations.gateway.GrpcRouteOperations;
import com.coding.k8score.operations.gateway.HttpRouteOperations;
import com.coding.k8score.operations.gateway.MeshOperations;
import com.coding.k8score.operations.gateway.TcpRouteOperations;
import com.coding.k8score.operations.gateway.TlsRouteOperations;
import com.coding.k8score.operations.gateway.UdpRouteOperations;
import com.coding.k8score.operations.monitoring.PodMonitorOperations;
import com.coding.k8score.operations.monitoring.ServiceMonitorOperations;
import com.coding.k8score.operations.rbac.CoreV1ServiceAccountOperations;
import com.coding.k8score.operations.rbac.RbacV1ClusterRoleOperations;
import com.coding.k8score.operations.rbac.RbacV1RoleBindingOperations;
import com.coding.k8score.operations.workload.AppsV1ReplicaSetOperations;
import com.coding.k8score.operations.workload.WorkloadOperations;
import com.fasterxml.jackson.core.type.TypeReference;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.utils.Serialization;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;

/**
 * operations 总工厂：按 {@link ResourceType} 直接 new 出对应 operation；client（tenant/admin）由三个 getter 的语义决定。
 * <p>历史上每个资源一个 per-resource factory，再靠「集群 capability 探测 + apiVersion」协商该用哪个 converter。
 * 但当前所有资源的 DTO 形状都稳定在单一 apiVersion 上，那条分支从未真正生效——不匹配时工厂返回 null，下游直接 NPE。
 * 故收敛为一处 switch 直接构造：少 14 个类、去掉每次调用前的 capability 查询（此前还带 10min 缓存污染风险）。
 * <p>跨版本字段发散的资源（HPA/Ingress/CRD/CronJob/PDB，见 {@code docs/resource-version-handling.md}）在 {@link #build}
 * 里按集群 capability 分派 converter——目前仅 HPA 落地（{@link #buildHpa}），其余待各自落地时同样处理。
 */
@Slf4j
@Component
public class KubernetesOperationsFactory {

    /** Gateway API 的 API group（capability 分派用；L4 路由版本 + GRPCRoute v1 门禁） */
    private static final String GATEWAY_API_GROUP = "gateway.networking.k8s.io";

    private final KubernetesClientFactory clientFactory;
    private final K8sClusterMapper k8sClusterMapper;

    public KubernetesOperationsFactory(KubernetesClientFactory clientFactory, K8sClusterMapper k8sClusterMapper) {
        this.clientFactory = clientFactory;
        this.k8sClusterMapper = k8sClusterMapper;
    }

    /** 命名空间级资源 operation（Deployment、RoleBinding、ServiceAccount 等），用 tenant client 实现租户隔离。 */
    @SuppressWarnings("unchecked")
    public <T> NamespacedOperations<T> getNamespacedOperation(ResourceType resourceType, String clusterId, String tenantId) {
        KubernetesClient client = clientFactory.getTenantClient(clusterId, tenantId);
        return (NamespacedOperations<T>) build(resourceType, client, clusterId);
    }

    /** 命名空间级资源 operation（admin 权限）：平台侧开通/清理流程使用，租户身份尚未就绪不能走 tenant client。 */
    @SuppressWarnings("unchecked")
    public <T> NamespacedOperations<T> getAdminNamespacedOperation(ResourceType resourceType, String clusterId) {
        KubernetesClient client = clientFactory.getAdminClient(clusterId);
        return (NamespacedOperations<T>) build(resourceType, client, clusterId);
    }

    /** 集群级资源 operation（ClusterRole 等），用 admin client 操作集群级别资源。 */
    @SuppressWarnings("unchecked")
    public <T> ClusterOperations<T> getClusterOperation(ResourceType resourceType, String clusterId) {
        KubernetesClient client = clientFactory.getAdminClient(clusterId);
        return (ClusterOperations<T>) build(resourceType, client, clusterId);
    }

    /** 节点操作（集群级，admin client）。节点非标准 CRUD（无 create/delete），故独立返回具体类型而非 ClusterOperations。 */
    public CoreV1NodeOperations getNodeOperation(String clusterId) {
        KubernetesClient client = clientFactory.getAdminClient(clusterId);
        return new CoreV1NodeOperations(client, new CoreV1NodeConverter());
    }

    /**
     * 集群概览的资源聚合操作（集群级，admin client）：跨命名空间一次 pass 出总量 + per-ns 明细 + 健康/存储。
     * 非资源 CRUD，独立返回具体类型（同 {@link #getNodeOperation}）。
     */
    public ClusterAggregationOperations getClusterAggregationOperation(String clusterId) {
        return new ClusterAggregationOperations(clientFactory.getAdminClient(clusterId));
    }

    /** Calico IPAM 派生视图操作（集群级，admin client）。非标准 CRUD，独立返回具体类型。 */
    public CalicoIpamOperations getCalicoIpamOperation(String clusterId) {
        KubernetesClient client = clientFactory.getAdminClient(clusterId);
        return new CalicoIpamOperations(client, new IppoolConverter());
    }

    /** BGP 编辑器下拉候选（集群级，admin client，只读）。非标准 CRUD，独立返回具体类型。 */
    public CalicoFormOptionOperations getCalicoFormOptionOperation(String clusterId) {
        KubernetesClient client = clientFactory.getAdminClient(clusterId);
        return new CalicoFormOptionOperations(client);
    }

    /**
     * 服务网格探测操作（集群级，admin client；只读）。非标准 CRUD，独立返回具体类型。
     * <p><b>不再读 DB</b>（2026-10-10）：capability 快照的派生已上移 platform-api 的
     * {@code ClusterCapabilityService}，本方法只构造"探测 ztunnel"这一半 —— 适配器不派生业务 flag。
     * 仍在用 {@link #readCapability} 的只剩 HPA / L4 路由的版本分派，那是 K8s 语义，正当。
     */
    public MeshOperations getMeshOperation(String clusterId) {
        KubernetesClient client = clientFactory.getAdminClient(clusterId);
        return new MeshOperations(client);
    }

    /** 按资源类型构造 operation。单版本资源直接 new；HPA 等跨版本发散资源在此按集群 capability 分派 converter。 */
    private Object build(ResourceType type, KubernetesClient client, String clusterId) {
        return switch (type) {
            case WORKLOAD -> new WorkloadOperations(client, new WorkloadConverter());
            case REPLICA_SET -> new AppsV1ReplicaSetOperations(client, new AppsV1ReplicaSetConverter());
            case POD -> new CoreV1PodOperations(client, new CoreV1PodConverter());
            case SERVICE -> new CoreV1ServiceOperations(client, new CoreV1ServiceConverter());
            case CONFIGMAP -> new CoreV1ConfigMapOperations(client, new CoreV1ConfigMapConverter());
            case SECRET -> new CoreV1SecretOperations(client, new CoreV1SecretConverter());
            case PERSISTENT_VOLUME_CLAIM -> new CoreV1PersistentVolumeClaimOperations(client, new CoreV1PvcConverter());
            case PERSISTENT_VOLUME -> new CoreV1PersistentVolumeOperations(client, new CoreV1PersistentVolumeConverter());
            case NAMESPACE -> new CoreV1NamespaceOperations(client, new CoreV1NamespaceConverter());
            case RESOURCE_QUOTA -> new CoreV1ResourceQuotaOperations(client, new CoreV1ResourceQuotaConverter());
            case LIMIT_RANGE -> new CoreV1LimitRangeOperations(client, new CoreV1LimitRangeConverter());
            case STORAGE_CLASS -> new CoreV1StorageClassOperations(client, new CoreV1StorageClassConverter());
            case SERVICE_ACCOUNT -> new CoreV1ServiceAccountOperations(client, new CoreV1ServiceAccountConverter());
            case ROLE_BINDING -> new RbacV1RoleBindingOperations(client, new RbacV1RoleBindingConverter());
            case CLUSTER_ROLE -> new RbacV1ClusterRoleOperations(client, new RbacV1ClusterRoleConverter());
            case SERVICE_MONITOR -> new ServiceMonitorOperations(client, new ServiceMonitorConverter());
            case POD_MONITOR -> new PodMonitorOperations(client, new PodMonitorConverter());
            case IP_POOL -> new IppoolOperations(client, new IppoolConverter());
            case IP_RESERVATION -> new IpReservationOperations(client, new IpReservationConverter());
            case BGP_CONFIGURATION -> new BgpConfigurationOperations(client, new BgpConfigurationConverter());
            case BGP_PEER -> new BgpPeerOperations(client, new BgpPeerConverter());
            case BGP_FILTER -> new BgpFilterOperations(client, new BgpFilterConverter());
            // Gateway API（B6）：GatewayClass / Gateway / HTTPRoute 三类都自 v1 起 GA → 单版本。
            case GATEWAY_CLASS -> new GatewayClassOperations(client, new GatewayClassConverter());
            case GATEWAY -> new GatewayOperations(client, new GatewayConverter());
            case HTTP_ROUTE -> new HttpRouteOperations(client, new HttpRouteConverter());
            // GRPCRoute 自 v1.1 GA（单挂 v1，不回退 v1alpha2 —— pre-v1.1 的 v1alpha2 结构不同）；
            // TCP/TLS/UDPRoute 自 v1.6 起 GA 于 v1，更早的集群只有 v1alpha2 → 按 capability 分派版本。
            case GRPC_ROUTE -> buildGrpcRoute(client, clusterId);
            case TCP_ROUTE -> new TcpRouteOperations(client, new TcpRouteConverter(resolveL4Version(clusterId)));
            case TLS_ROUTE -> new TlsRouteOperations(client, new TlsRouteConverter(resolveL4Version(clusterId)));
            case UDP_ROUTE -> new UdpRouteOperations(client, new UdpRouteConverter(resolveL4Version(clusterId)));
            case HPA -> buildHpa(client, clusterId);
            default -> throw new CloudPlatformException(EnumResponseType.NON_RESOURCE);
        };
    }

    /**
     * HPA 是唯一长期共存型的发散资源（autoscaling/v1 至今仍在 served）。按集群 capability 选版本：
     * 含 v2 → V2（v2 client 经服务端版本转换也能读 v1 创建的对象，故读写统一走高版本）；仅 v1 → V1；
     * capability 未探测/为空 → 默认 v2（1.15+ GA，绝大多数集群）。不支持的组合显式抛错，绝不返回 null。
     */
    private Object buildHpa(KubernetesClient client, String clusterId) {
        List<String> autoscaling = readApiVersions(clusterId, "autoscaling");
        if (autoscaling == null || autoscaling.isEmpty()) {
            return new HpaV2Operations(client, new HpaV2Converter());
        }
        if (autoscaling.contains("v2")) {
            return new HpaV2Operations(client, new HpaV2Converter());
        }
        if (autoscaling.contains("v1")) {
            return new HpaV1Operations(client, new HpaV1Converter());
        }
        throw new CloudPlatformException(EnumResponseType.HPA_VERSION_UNSUPPORTED);
    }

    /**
     * GRPCRoute 单挂 v1（自 Gateway API v1.1 GA）。capability <b>已知</b>且没有 v1 时显式报错，
     * 不静默回退到 v1alpha2 —— pre-v1.1 的 v1alpha2 GRPCRoute 结构与 v1 不同（有 queryParams、
     * filter 类型也不同），悄悄切过去会写出结构错误的对象。capability 未探测（null/空）时按 v1 试，
     * 不存在的 CRD 由 apiserver 404、经上层异常体系透出。
     */
    private Object buildGrpcRoute(KubernetesClient client, String clusterId) {
        List<String> versions = readApiVersions(clusterId, GATEWAY_API_GROUP);
        if (versions != null && !versions.isEmpty() && !versions.contains("v1")) {
            throw new CloudPlatformException(EnumResponseType.ERROR,
                    "该集群的 Gateway API 未提供 v1 版本（GRPCRoute 自 Gateway API v1.1 起 GA），无法管理 GRPCRoute");
        }
        return new GrpcRouteOperations(client, new GrpcRouteConverter());
    }

    /**
     * L4 路由（TCP / TLS / UDP）的 CRD 版本：Gateway API v1.6 起 GA 到 {@code v1}，更早的集群只有
     * {@code v1alpha2}（v1.2+ 的 v1alpha2 与 v1 是同一结构，只是版本名不同 —— 见 {@code TcpRouteConverter}）。
     * <p>capability 未探测（null/空）→ 默认 {@code v1}，让 apiserver 用 404 说话而不是猜；
     * capability <b>已知</b>但两个版本都没有 → 显式报错（绝不静默返回一个注定失败的版本）。
     */
    private String resolveL4Version(String clusterId) {
        List<String> versions = readApiVersions(clusterId, GATEWAY_API_GROUP);
        if (versions == null || versions.isEmpty()) {
            return "v1";
        }
        if (versions.contains("v1")) {
            return "v1";
        }
        if (versions.contains("v1alpha2")) {
            return "v1alpha2";
        }
        throw new CloudPlatformException(EnumResponseType.ERROR,
                "该集群的 Gateway API（" + GATEWAY_API_GROUP + "）既无 v1 也无 v1alpha2，无法管理 L4 路由；现有版本：" + versions);
    }

    /** 读 {@code k8s_cluster.capability}（JSON：group→versions[]）取某 group 的 versions；无行/空列/解析失败/无该 group 返回 null。 */
    private List<String> readApiVersions(String clusterId, String group) {
        return readCapability(clusterId).get(group);
    }

    /** 读整个 {@code k8s_cluster.capability} 快照（group → versions[]）；无行/空列/解析失败返回空 Map（"未探测"语义）。 */
    private Map<String, List<String>> readCapability(String clusterId) {
        K8sCluster cluster = k8sClusterMapper.selectByPrimaryKey(clusterId);
        if (cluster == null || !StringUtils.hasText(cluster.getCapability())) {
            return Map.of();
        }
        try {
            return Serialization.jsonMapper()
                    .readValue(cluster.getCapability(), new TypeReference<Map<String, List<String>>>() {
                    });
        } catch (Exception e) {
            log.warn("解析集群 {} capability 失败，按未探测处理：{}", clusterId, e.getMessage());
            return Map.of();
        }
    }

}
