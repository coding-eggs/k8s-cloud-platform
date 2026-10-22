package com.coding.k8sserver.middleware;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.middleware.MiddlewareCreateRequestDTO;
import com.coding.common.models.middleware.MiddlewareDeleteRequestDTO;
import com.coding.common.models.middleware.MiddlewareScaleRequestDTO;
import com.coding.common.models.middleware.MiddlewareStatusRequestDTO;
import com.coding.common.models.middleware.MiddlewareStatusResultDTO;
import com.coding.k8score.factory.KubernetesClientFactory;
import com.coding.k8score.operations.ServerSideApply;
import com.coding.k8sserver.components.ResourceAccessResolver;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.ResourceQuota;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 中间件实例的执行层：创建/扩容/删除 CR，写凭据 Secret，读实时状态。
 *
 * <p><b>身份</b>：{@link ResourceAccessResolver#resolveNamespacedAccessOrPlatform} ——
 * 租户 token 用租户 client（K8s RBAC 第二道闸）+ 分配表；管理员不带租户时用 admin client（不受分配表限制）。
 *
 * <p><b>配额预检用 admin client 读 ResourceQuota</b>：租户 RBAC 模板里没有 `resourcequotas`
 * （2026-10-22 复审实锤：`kubectl auth can-i list resourcequotas` 对租户 SA 是 no），
 * 用租户 client 读会先 403，用户看到的是权限错误而不是「配额不足」。
 */
@Service
public class MiddlewareInstanceService {

    private final ResourceAccessResolver accessResolver;
    private final KubernetesClientFactory clients;
    private final NacosChartInstaller nacos;
    private final Map<String, MiddlewareAdapter> adapters;

    public MiddlewareInstanceService(ResourceAccessResolver accessResolver,
                                     KubernetesClientFactory clients,
                                     NacosChartInstaller nacos,
                                     List<MiddlewareAdapter> adapters) {
        this.accessResolver = accessResolver;
        this.clients = clients;
        this.nacos = nacos;
        this.adapters = adapters.stream()
                .collect(Collectors.toMap(MiddlewareAdapter::code, Function.identity()));
    }

    public void create(MiddlewareCreateRequestDTO req) {
        var ctx = accessResolver.resolveNamespacedAccessOrPlatform(
                req.getTenantId(), req.getClusterId(), req.getNamespace());
        KubernetesClient client = clientOf(req.getClusterId(), ctx);
        assertQuota(req);

        if (isNacos(req.getCode())) {
            nacos.create(req);
            return;
        }

        MiddlewareAdapter adapter = adapter(req.getCode());
        List<MiddlewareAdapter.CrSpec> created = new ArrayList<>();
        try {
            if (adapter.usesPlatformPassword()) {
                writeSecret(client, req);
            }
            for (MiddlewareAdapter.CrSpec spec : adapter.buildCreate(req)) {
                client.genericKubernetesResources(spec.context())
                        .inNamespace(req.getNamespace())
                        .resource(spec.resource())
                        .create();
                created.add(spec);
            }
        } catch (RuntimeException e) {
            // 部分失败回滚：SENTINEL 是两个 CR，第二个失败时不能把第一个和 Secret 留在集群里
            created.forEach(s -> deleteQuietly(client, s.context(), req.getNamespace(),
                    s.resource().getMetadata().getName()));
            if (adapter.usesPlatformPassword()) {
                deleteSecretQuietly(client, req.getNamespace(), secretName(req));
            }
            throw e;
        }
    }

    public void scale(MiddlewareScaleRequestDTO req) {
        if (req.getStorageSize() != null && !req.getStorageSize().isBlank()) {
            throw new CloudPlatformException(EnumResponseType.ERROR,
                    "暂不支持扩容存储（PVC 扩容依赖各中间件的 Operator 能力），请重建实例或手工扩卷");
        }
        var ctx = accessResolver.resolveNamespacedAccessOrPlatform(
                req.getTenantId(), req.getClusterId(), req.getNamespace());
        KubernetesClient client = clientOf(req.getClusterId(), ctx);
        if (isNacos(req.getCode())) {
            nacos.scale(req.getClusterId(), req.getCode());   // 直接抛出"暂不支持"
            return;
        }
        MiddlewareAdapter adapter = adapter(req.getCode());

        boolean applied = false;
        for (CustomResourceDefinitionContext crd : adapter.contexts()) {
            var live = client.genericKubernetesResources(crd)
                    .inNamespace(req.getNamespace())
                    .withName(req.getName())
                    .get();
            if (live == null) {
                continue;
            }
            adapter.applyScale(live, crd, req);
            // 与仓内其它 CR 更新一致：SSA + forceConflicts（fetch-overlay 已保留未建模字段）
            client.genericKubernetesResources(crd)
                    .inNamespace(req.getNamespace())
                    .resource(live)
                    .fieldManager(ServerSideApply.FIELD_MANAGER)
                    .forceConflicts()
                    .serverSideApply();
            applied = true;
        }
        if (!applied) {
            throw new CloudPlatformException(EnumResponseType.RESOURCE_NOT_EXIST);
        }
    }

    public void delete(MiddlewareDeleteRequestDTO req) {
        var ctx = accessResolver.resolveNamespacedAccessOrPlatform(
                req.getTenantId(), req.getClusterId(), req.getNamespace());
        KubernetesClient client = clientOf(req.getClusterId(), ctx);

        if (isNacos(req.getCode())) {
            nacos.delete(req.getClusterId(), req.getNamespace(), req.getName());
            if (!req.isKeepPvc()) {
                // chart 的 PVC 没有 labels（volumeClaimTemplates 只写了 name），只能按名字前缀找：
                // STS 卷名 = <claimName>-<stsName>-<ordinal>，claimName 固定是 data
                String prefix = "data-" + req.getName() + "-";
                client.persistentVolumeClaims().inNamespace(req.getNamespace()).list().getItems().stream()
                        .filter(p -> p.getMetadata() != null && p.getMetadata().getName() != null
                                && p.getMetadata().getName().startsWith(prefix))
                        .forEach(p -> client.persistentVolumeClaims().inNamespace(req.getNamespace())
                                .withName(p.getMetadata().getName()).delete());
            }
            return;
        }

        MiddlewareAdapter adapter = adapter(req.getCode());
        for (CustomResourceDefinitionContext crd : adapter.contexts()) {
            deleteQuietly(client, crd, req.getNamespace(), req.getName());
        }
        if (!req.isKeepPvc()) {
            // PVC 的标签由各适配器决定：Redis 用平台标签，MySQL/Kafka/ES 用各自 Operator 的标签
            client.persistentVolumeClaims().inNamespace(req.getNamespace())
                    .withLabel(adapter.pvcSelector(req.getName(), req.getInstanceId()))
                    .delete();
            if (adapter.usesPlatformPassword()) {
                deleteSecretQuietly(client, req.getNamespace(), secretName(req));
            }
        }
        // keepPvc=true 时保留 Secret：数据卷还在，凭据也还给它留着
    }

    public List<MiddlewareStatusResultDTO> status(MiddlewareStatusRequestDTO req) {
        var ctx = accessResolver.resolveNamespacedAccessOrPlatform(
                req.getTenantId(), req.getClusterId(), req.getNamespace());
        KubernetesClient client = clientOf(req.getClusterId(), ctx);

        if (isNacos(req.getCode())) {
            return nacosStatus(client, req);
        }

        MiddlewareAdapter adapter = adapter(req.getCode());

        List<MiddlewareStatusResultDTO> out = new ArrayList<>();
        for (String name : req.getNames()) {
            GenericKubernetesResource live = null;
            for (CustomResourceDefinitionContext crd : adapter.contexts()) {
                var found = client.genericKubernetesResources(crd)
                        .inNamespace(req.getNamespace()).withName(name).get();
                if (found != null) {
                    live = found;
                    break;
                }
            }
            MiddlewareStatusResultDTO dto = new MiddlewareStatusResultDTO();
            dto.setName(name);
            if (live == null) {
                dto.setStatus("FAILED");
                dto.setMessage("实例对象不存在（可能已被删除）");
                out.add(dto);
                continue;
            }
            List<Pod> pods = client.pods().inNamespace(req.getNamespace())
                    .withLabel(adapter.statusSelector(name)).list().getItems();
            long ready = pods.stream().filter(MiddlewareInstanceService::podReady).count();
            dto.setReplicas(pods.size());
            dto.setReadyReplicas((int) ready);
            if (pods.isEmpty()) {
                // Operator 拒绝了规格（版本不支持、PVC 不可调度等）时只会写 condition，不建 Pod：
                // 不读 CR status 就会永远显示「创建中」
                String failure = crFailureMessage(live);
                if (failure != null) {
                    dto.setStatus("FAILED");
                    dto.setMessage(failure);
                } else {
                    dto.setStatus("CREATING");
                    dto.setMessage("等待 Pod 创建");
                }
            } else if (ready == pods.size()) {
                dto.setStatus("READY");
                dto.setMessage("运行中");
            } else {
                dto.setStatus("DEGRADED");
                dto.setMessage(ready + "/" + pods.size() + " 就绪");
            }
            out.add(dto);
        }
        return out;
    }

    /** 从 CR 的 status 里读硬失败：ECK 的 health=red、以及各类 Operator 的 Ready/Available=False condition。 */
    private static String crFailureMessage(GenericKubernetesResource res) {
        Object status = res.getAdditionalProperties().get("status");
        if (!(status instanceof Map<?, ?> st)) {
            return null;
        }
        if ("red".equals(st.get("health"))) {
            return "Operator 报告健康状况为 red";
        }
        if (st.get("conditions") instanceof List<?> conditions) {
            for (Object c : conditions) {
                if (!(c instanceof Map<?, ?> cm)) {
                    continue;
                }
                boolean bad = "False".equals(cm.get("status"))
                        && ("Ready".equals(cm.get("type")) || "Available".equals(cm.get("type")));
                if (bad) {
                    Object msg = cm.get("message");
                    Object reason = cm.get("reason");
                    return msg != null ? String.valueOf(msg) : String.valueOf(reason);
                }
            }
        }
        return null;
    }

    // ==================== 内部 ====================

    private static boolean isNacos(String code) {
        return "nacos".equals(code);
    }

    /** Nacos 不是 CR：有没有对象看 Service 在不在，就绪看 Pod。 */
    private List<MiddlewareStatusResultDTO> nacosStatus(KubernetesClient client, MiddlewareStatusRequestDTO req) {
        List<MiddlewareStatusResultDTO> out = new ArrayList<>();
        for (String name : req.getNames()) {
            MiddlewareStatusResultDTO dto = new MiddlewareStatusResultDTO();
            dto.setName(name);
            // 不能按 Service 名判断：chart 的 Service 固定叫 nacos-cs，与 release 名无关
            boolean exists = nacos.exists(req.getClusterId(), req.getNamespace(), name);
            if (!exists) {
                dto.setStatus("FAILED");
                dto.setMessage("实例不存在（release 可能已被删除）");
                out.add(dto);
                continue;
            }
            List<Pod> pods = client.pods().inNamespace(req.getNamespace())
                    .withLabel("app.kubernetes.io/instance=" + name).list().getItems();
            long ready = pods.stream().filter(MiddlewareInstanceService::podReady).count();
            dto.setReplicas(pods.size());
            dto.setReadyReplicas((int) ready);
            if (pods.isEmpty()) {
                dto.setStatus("CREATING");
                dto.setMessage("等待 Pod 创建");
            } else if (ready == pods.size()) {
                dto.setStatus("READY");
                dto.setMessage("运行中");
            } else {
                dto.setStatus("DEGRADED");
                dto.setMessage(ready + "/" + pods.size() + " 就绪");
            }
            out.add(dto);
        }
        return out;
    }

    private KubernetesClient clientOf(String clusterId, ResourceAccessResolver.AccessContext ctx) {
        KubernetesClient client = ctx.adminMode()
                ? clients.getAdminClient(clusterId)
                : clients.getTenantClient(clusterId, ctx.tenantId());
        if (client == null) {
            throw new CloudPlatformException(EnumResponseType.ERROR,
                    "租户上下文不可用（租户可能已停用），请联系管理员");
        }
        return client;
    }

    private MiddlewareAdapter adapter(String code) {
        MiddlewareAdapter a = adapters.get(code);
        if (a == null) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "不支持的中间件：" + code);
        }
        return a;
    }

    private void writeSecret(KubernetesClient client, MiddlewareCreateRequestDTO req) {
        if (req.getPassword() == null || req.getPassword().isBlank()) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "缺少初始密码");
        }
        Secret secret = new SecretBuilder()
                .withNewMetadata()
                .withName(secretName(req))
                .withNamespace(req.getNamespace())
                .addToLabels("platform.coding/managed-by", "middleware")
                .addToLabels("platform.coding/middleware-instance", req.getInstanceId())
                .endMetadata()
                .addToStringData("password", req.getPassword())
                .build();
        client.secrets().inNamespace(req.getNamespace()).resource(secret).createOrReplace();
    }

    private void deleteSecretQuietly(KubernetesClient client, String namespace, String name) {
        try {
            client.secrets().inNamespace(namespace).withName(name).delete();
        } catch (RuntimeException ignored) {
            // 清理是尽力而为：Secret 不存在或已删都不影响主流程
        }
    }

    private void deleteQuietly(KubernetesClient client, CustomResourceDefinitionContext crd,
                               String namespace, String name) {
        try {
            client.genericKubernetesResources(crd).inNamespace(namespace).withName(name).delete();
        } catch (RuntimeException ignored) {
            // 同上
        }
    }

    private static String secretName(MiddlewareCreateRequestDTO req) {
        return req.getName() + "-credentials";
    }

    private static String secretName(MiddlewareDeleteRequestDTO req) {
        return req.getName() + "-credentials";
    }

    /** 配额预检：admin client 读 ResourceQuota（租户 SA 没有 resourcequotas 权限）。 */
    private void assertQuota(MiddlewareCreateRequestDTO req) {
        KubernetesClient admin = clients.getAdminClient(req.getClusterId());
        List<ResourceQuota> quotas = admin.resourceQuotas()
                .inNamespace(req.getNamespace()).list().getItems();
        if (quotas.isEmpty()) {
            return;
        }
        int n = quotaPodCount(req);
        BigDecimal needCpu = amount(req.getCpuRequest()).multiply(BigDecimal.valueOf(n));
        BigDecimal needMem = amount(req.getMemoryRequest()).multiply(BigDecimal.valueOf(n));

        for (ResourceQuota q : quotas) {
            if (q.getSpec() == null || q.getSpec().getHard() == null) {
                continue;
            }
            Map<String, Quantity> used = q.getStatus() == null || q.getStatus().getUsed() == null
                    ? Map.of() : q.getStatus().getUsed();
            checkQuota(q.getSpec().getHard().get("requests.cpu"), used.get("requests.cpu"), needCpu, "CPU");
            checkQuota(q.getSpec().getHard().get("requests.memory"), used.get("requests.memory"), needMem, "内存");
            // limits 配额也要看：只配 limits 的命名空间，requests 检查会放行
            BigDecimal needCpuLimit = amount(req.getCpuLimit()).multiply(BigDecimal.valueOf(n));
            BigDecimal needMemLimit = amount(req.getMemoryLimit()).multiply(BigDecimal.valueOf(n));
            checkQuota(q.getSpec().getHard().get("limits.cpu"), used.get("limits.cpu"), needCpuLimit, "CPU 上限");
            checkQuota(q.getSpec().getHard().get("limits.memory"), used.get("limits.memory"), needMemLimit, "内存上限");
        }
    }

    /** 实际要起的 Pod 数：MySQL 每个数据节点还带一个 HAProxy；哨兵另加固定 3 个哨兵。 */
    private static int quotaPodCount(MiddlewareCreateRequestDTO req) {
        int base = replicas(req);
        if ("SENTINEL".equals(req.getMode())) {
            return base + 3;
        }
        if ("mysql".equals(req.getCode())) {
            return base * 2;
        }
        return base;
    }

    private static void checkQuota(Quantity hard, Quantity used, BigDecimal need, String label) {
        if (hard == null || need.signum() <= 0) {
            return;
        }
        BigDecimal remaining = hard.getNumericalAmount().subtract(amount(used));
        if (need.compareTo(remaining) > 0) {
            throw new CloudPlatformException(EnumResponseType.ERROR,
                    "命名空间配额不足：" + label + " 需要 " + need.toPlainString()
                            + "，剩余 " + remaining.toPlainString());
        }
    }

    private static BigDecimal amount(Quantity q) {
        return q == null ? BigDecimal.ZERO : q.getNumericalAmount();
    }

    private static BigDecimal amount(String q) {
        return q == null || q.isBlank() ? BigDecimal.ZERO : new Quantity(q).getNumericalAmount();
    }

    private static int replicas(MiddlewareCreateRequestDTO req) {
        return req.getReplicas() == null ? 1 : Math.max(1, req.getReplicas());
    }

    private static boolean podReady(Pod pod) {
        if (pod.getStatus() == null || pod.getStatus().getConditions() == null) {
            return false;
        }
        return pod.getStatus().getConditions().stream()
                .anyMatch(c -> "Ready".equals(c.getType()) && "True".equals(c.getStatus()))
                && Objects.equals(pod.getStatus().getPhase(), "Running");
    }
}
