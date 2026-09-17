package com.coding.k8score.factory;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.k8s.ResourceType;
import com.coding.data.mapper.k8s.K8sClusterMapper;
import com.coding.data.models.k8s.K8sCluster;
import com.coding.k8score.converter.impl.autoscaling.HpaV1Converter;
import com.coding.k8score.converter.impl.autoscaling.HpaV2Converter;
import com.coding.k8score.converter.impl.core.CoreV1ConfigMapConverter;
import com.coding.k8score.converter.impl.core.CoreV1NodeConverter;
import com.coding.k8score.converter.impl.core.CoreV1PodConverter;
import com.coding.k8score.converter.impl.core.CoreV1PvcConverter;
import com.coding.k8score.converter.impl.core.CoreV1PersistentVolumeConverter;
import com.coding.k8score.converter.impl.core.CoreV1SecretConverter;
import com.coding.k8score.converter.impl.core.CoreV1ServiceConverter;
import com.coding.k8score.converter.impl.core.CoreV1StorageClassConverter;
import com.coding.k8score.converter.impl.monitoring.PodMonitorConverter;
import com.coding.k8score.converter.impl.monitoring.ServiceMonitorConverter;
import com.coding.k8score.converter.impl.rbac.CoreV1ServiceAccountConverter;
import com.coding.k8score.converter.impl.rbac.RbacV1ClusterRoleConverter;
import com.coding.k8score.converter.impl.rbac.RbacV1RoleBindingConverter;
import com.coding.k8score.converter.impl.workload.AppsV1ReplicaSetConverter;
import com.coding.k8score.converter.impl.workload.WorkloadConverter;
import com.coding.k8score.operations.ClusterOperations;
import com.coding.k8score.operations.NamespacedOperations;
import com.coding.k8score.operations.autoscaling.HpaV1Operations;
import com.coding.k8score.operations.autoscaling.HpaV2Operations;
import com.coding.k8score.operations.core.CoreV1ConfigMapOperations;
import com.coding.k8score.operations.core.CoreV1PersistentVolumeClaimOperations;
import com.coding.k8score.operations.core.CoreV1PersistentVolumeOperations;
import com.coding.k8score.operations.core.CoreV1NodeOperations;
import com.coding.k8score.operations.core.CoreV1PodOperations;
import com.coding.k8score.operations.core.CoreV1SecretOperations;
import com.coding.k8score.operations.core.CoreV1ServiceOperations;
import com.coding.k8score.operations.core.CoreV1StorageClassOperations;
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
            case STORAGE_CLASS -> new CoreV1StorageClassOperations(client, new CoreV1StorageClassConverter());
            case SERVICE_ACCOUNT -> new CoreV1ServiceAccountOperations(client, new CoreV1ServiceAccountConverter());
            case ROLE_BINDING -> new RbacV1RoleBindingOperations(client, new RbacV1RoleBindingConverter());
            case CLUSTER_ROLE -> new RbacV1ClusterRoleOperations(client, new RbacV1ClusterRoleConverter());
            case SERVICE_MONITOR -> new ServiceMonitorOperations(client, new ServiceMonitorConverter());
            case POD_MONITOR -> new PodMonitorOperations(client, new PodMonitorConverter());
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

    /** 读 {@code k8s_cluster.capability}（JSON：group→versions[]）取某 group 的 versions；无行/空列/解析失败返回 null。 */
    private List<String> readApiVersions(String clusterId, String group) {
        K8sCluster cluster = k8sClusterMapper.selectByPrimaryKey(clusterId);
        if (cluster == null || !StringUtils.hasText(cluster.getCapability())) {
            return null;
        }
        try {
            Map<String, List<String>> capability = Serialization.jsonMapper()
                    .readValue(cluster.getCapability(), new TypeReference<Map<String, List<String>>>() {
                    });
            return capability.get(group);
        } catch (Exception e) {
            log.warn("解析集群 {} capability 失败，按默认版本处理：{}", clusterId, e.getMessage());
            return null;
        }
    }

}
