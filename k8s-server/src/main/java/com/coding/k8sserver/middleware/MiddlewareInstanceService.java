package com.coding.k8sserver.middleware;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.middleware.MiddlewareCreateRequestDTO;
import com.coding.common.models.middleware.MiddlewareDeleteRequestDTO;
import com.coding.common.models.middleware.MiddlewareScaleRequestDTO;
import com.coding.common.models.middleware.MiddlewareStatusRequestDTO;
import com.coding.common.models.middleware.MiddlewareStatusResultDTO;
import com.coding.k8score.factory.KubernetesClientFactory;
import com.coding.k8sserver.components.ResourceAccessResolver;
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
 * <p><b>身份</b>：一律走 {@link ResourceAccessResolver#resolveNamespacedAccess} —— 租户 token 用租户 client
 * （K8s RBAC 第二道闸），admin 用 admin client；分配表边界由它校验，本类不再重复判断。
 *
 * <p><b>配额预检放在这里</b>（计划 Ruling）：platform-api 没有集群访问，读不到 ResourceQuota。
 */
@Service
public class MiddlewareInstanceService {

    private final ResourceAccessResolver accessResolver;
    private final KubernetesClientFactory clients;
    private final Map<String, MiddlewareAdapter> adapters;

    public MiddlewareInstanceService(ResourceAccessResolver accessResolver,
                                     KubernetesClientFactory clients,
                                     List<MiddlewareAdapter> adapters) {
        this.accessResolver = accessResolver;
        this.clients = clients;
        this.adapters = adapters.stream()
                .collect(Collectors.toMap(MiddlewareAdapter::code, Function.identity()));
    }

    public void create(MiddlewareCreateRequestDTO req) {
        KubernetesClient client = clientFor(req.getClusterId(), req.getNamespace());
        assertQuota(client, req);

        MiddlewareAdapter adapter = adapter(req.getCode());
        writeSecret(client, req.getNamespace(), req.getName(), req.getPassword());
        for (MiddlewareAdapter.CrSpec spec : adapter.buildCreate(req)) {
            client.genericKubernetesResources(spec.context())
                    .inNamespace(req.getNamespace())
                    .resource(spec.resource())
                    .create();
        }
    }

    public void scale(MiddlewareScaleRequestDTO req) {
        KubernetesClient client = clientFor(req.getClusterId(), req.getNamespace());
        MiddlewareAdapter adapter = adapter(req.getCode());

        boolean applied = false;
        for (CustomResourceDefinitionContext ctx : adapter.contexts()) {
            var live = client.genericKubernetesResources(ctx)
                    .inNamespace(req.getNamespace())
                    .withName(req.getName())
                    .get();
            if (live == null) {
                continue;
            }
            adapter.applyScale(live, ctx, req);
            client.genericKubernetesResources(ctx)
                    .inNamespace(req.getNamespace())
                    .resource(live)
                    .update();
            applied = true;
        }
        if (!applied) {
            throw new CloudPlatformException(EnumResponseType.RESOURCE_NOT_EXIST);
        }
    }

    public void delete(MiddlewareDeleteRequestDTO req) {
        KubernetesClient client = clientFor(req.getClusterId(), req.getNamespace());
        MiddlewareAdapter adapter = adapter(req.getCode());

        for (CustomResourceDefinitionContext ctx : adapter.contexts()) {
            client.genericKubernetesResources(ctx)
                    .inNamespace(req.getNamespace())
                    .withName(req.getName())
                    .delete();
        }
        if (!req.isKeepPvc()) {
            // Operator 生成的 PVC 用同一个 app 标签（与 Pod 一致）；keepPvc=false 才清
            client.persistentVolumeClaims().inNamespace(req.getNamespace())
                    .withLabel(adapter.statusSelector(req.getName()))
                    .delete();
        }
        client.secrets().inNamespace(req.getNamespace())
                .withName(RedisMiddlewareAdapter.secretName(req.getName()))
                .delete();
    }

    public List<MiddlewareStatusResultDTO> status(MiddlewareStatusRequestDTO req) {
        KubernetesClient client = clientFor(req.getClusterId(), req.getNamespace());
        MiddlewareAdapter adapter = adapter(req.getCode());

        List<MiddlewareStatusResultDTO> out = new ArrayList<>();
        for (String name : req.getNames()) {
            boolean exists = false;
            for (CustomResourceDefinitionContext ctx : adapter.contexts()) {
                if (client.genericKubernetesResources(ctx)
                        .inNamespace(req.getNamespace()).withName(name).get() != null) {
                    exists = true;
                    break;
                }
            }
            MiddlewareStatusResultDTO dto = new MiddlewareStatusResultDTO();
            dto.setName(name);
            if (!exists) {
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

    // ==================== 内部 ====================

    private KubernetesClient clientFor(String clusterId, String namespace) {
        var ctx = accessResolver.resolveNamespacedAccess(null, clusterId, namespace);
        return ctx.adminMode()
                ? clients.getAdminClient(clusterId)
                : clients.getTenantClient(clusterId, ctx.tenantId());
    }

    private MiddlewareAdapter adapter(String code) {
        MiddlewareAdapter a = adapters.get(code);
        if (a == null) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "不支持的中间件：" + code);
        }
        return a;
    }

    private void writeSecret(KubernetesClient client, String namespace, String name, String password) {
        if (password == null || password.isBlank()) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "缺少初始密码");
        }
        Secret secret = new SecretBuilder()
                .withNewMetadata()
                .withName(RedisMiddlewareAdapter.secretName(name))
                .withNamespace(namespace)
                .addToLabels("platform.coding/managed-by", "middleware")
                .endMetadata()
                .addToStringData("password", password)
                .build();
        client.secrets().inNamespace(namespace).resource(secret).createOrReplace();
    }

    /** 配额预检：命名空间有 ResourceQuota 时，估算 副本数 × requests 是否超限。 */
    private void assertQuota(KubernetesClient client, MiddlewareCreateRequestDTO req) {
        List<ResourceQuota> quotas = client.resourceQuotas()
                .inNamespace(req.getNamespace()).list().getItems();
        if (quotas.isEmpty()) {
            return;
        }
        int n = "SENTINEL".equals(req.getMode()) ? replicas(req) + 3 : replicas(req);
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
        }
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
