package com.coding.k8sserver.services;

import com.coding.common.models.constants.SystemConstant;
import com.coding.common.utils.K8sNaming;
import com.coding.data.mapper.auth.PlatformTenantMapper;
import com.coding.data.mapper.auth.PlatformTenantNamespaceMapper;
import com.coding.data.mapper.k8s.PlatformRbacTemplateMapper;
import com.coding.data.models.auth.PlatformTenantNamespace;
import com.coding.k8score.factory.KubernetesClientFactory;
import io.fabric8.kubernetes.api.model.Namespace;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.ResourceQuotaBuilder;
import io.fabric8.kubernetes.api.model.rbac.RoleBindingBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class NamespaceRecoveryWorker {
    private static final String MARKER = "platform.coding.com/recovery-allocation";
    private static final String QUOTA = "platform-recovery";
    private final PlatformTenantNamespaceMapper allocations;
    private final PlatformTenantMapper tenants;
    private final PlatformRbacTemplateMapper templates;
    private final KubernetesClientFactory clients;
    private final TransactionTemplate tx;

    @Scheduled(fixedDelayString = "${platform.namespace.recovery-interval-ms:30000}")
    public void reconcile() {
        for (String id : allocations.due()) {
            try {
                tx.executeWithoutResult(ignored -> {
                    var row = allocations.lock(id);
                    if (row == null) return;
                    try {
                        advance(row);
                    } catch (RuntimeException e) {
                        throw new ReconcileFailure(row.getLifecycle(), row.getExpiresAt(), e);
                    }
                });
            } catch (ReconcileFailure failure) {
                String reason = Objects.toString(failure.getCause().getMessage(), "集群操作失败");
                tx.executeWithoutResult(ignored -> {
                    var row = allocations.lock(id);
                    if (row != null && failure.state.equals(row.getLifecycle()) && Objects.equals(failure.expiresAt, row.getExpiresAt()))
                        allocations.transition(id, row.getLifecycle(), reason.substring(0, Math.min(990, reason.length())));
                });
                log.warn("空间生命周期操作待重试 allocation={}", id, failure.getCause());
            }
        }
    }

    private static final class ReconcileFailure extends RuntimeException {
        private final String state;
        private final Long expiresAt;
        ReconcileFailure(String state, Long expiresAt, RuntimeException cause) {
            super(cause);
            this.state = state;
            this.expiresAt = expiresAt;
        }
    }

    void advance(PlatformTenantNamespace row) {
        String state = row.getLifecycle();
        if ("ACTIVE".equals(state) && (row.getExpiresAt() == null || row.getExpiresAt() > System.currentTimeMillis())) return;
        if (!Set.of("ACTIVE", "STOPPING", "RESTORING", "DELETING").contains(state)) return;
        String ns = row.getNamespace();
        var client = clients.getAdminClient(row.getClusterId());
        Namespace live = client.namespaces().withName(ns).get();
        if (live == null && "DELETING".equals(state)) {
            checkExclusive(row);
            allocations.retireApplications(row);
            allocations.retireMiddleware(row);
            allocations.deleteByPrimaryKey(row.getId());
            return;
        }
        if (live == null || row.getNamespaceUid() == null || !row.getNamespaceUid().equals(live.getMetadata().getUid()))
            throw new IllegalStateException("原空间不存在或 UID 已改变，请核对");
        var tenant = tenants.selectByPrimaryKey(row.getTenantId());
        String sa = K8sNaming.tenantServiceAccount(tenant.getServiceAccount());
        switch (state) {
            case "ACTIVE" -> {
                var binding = client.rbac().roleBindings().inNamespace(ns).withName(sa).get();
                if (binding != null) {
                    client.resource(binding).lockResourceVersion(binding.getMetadata().getResourceVersion()).delete();
                    return;
                }
                allocations.transition(row.getId(), "EXPIRED", null);
            }
            case "STOPPING" -> {
                checkExclusive(row);
                stopPods(row, client, ns);
            }
            case "DELETING" -> {
                checkExclusive(row);
                if (live.getMetadata().getDeletionTimestamp() == null)
                    client.resource(live).lockResourceVersion(live.getMetadata().getResourceVersion()).delete();
            }
            case "RESTORING" -> {
                if (row.getExpiresAt() != null && row.getExpiresAt() <= System.currentTimeMillis()) {
                    allocations.transition(row.getId(), "ACTIVE", "续期时间已过，等待撤销访问");
                    return;
                }
                var quota = client.resourceQuotas().inNamespace(ns).withName(QUOTA).get();
                if (quota != null) {
                    if (!row.getId().equals(quota.getMetadata().getLabels().get(MARKER))) throw new IllegalStateException("回收配额归属不符");
                    client.resource(quota).lockResourceVersion(quota.getMetadata().getResourceVersion()).delete();
                    return;
                }
                for (var cron : client.batch().v1().cronjobs().inNamespace(ns).list().getItems()) {
                    if (row.getId().equals(cron.getMetadata().getAnnotations().get(MARKER))) {
                        cron.getMetadata().getAnnotations().remove(MARKER);
                        cron.getSpec().setSuspend(false);
                        client.resource(cron).lockResourceVersion().update();
                    }
                }
                var resource = client.rbac().roleBindings().inNamespace(ns).withName(sa);
                var template = templates.selectByPrimaryKey(row.getRoleTemplateId());
                var role = K8sNaming.templateClusterRole(template.getName());
                var existing = resource.get();
                if (existing != null && (!role.equals(existing.getRoleRef().getName()) || existing.getSubjects().stream()
                        .noneMatch(s -> sa.equals(s.getName()) && "ServiceAccount".equals(s.getKind()) && SystemConstant.SYSTEM_NAMESPACE.equals(s.getNamespace()))))
                    throw new IllegalStateException("现有 RoleBinding 与分配不一致");
                if (existing == null) client.rbac().roleBindings().inNamespace(ns).resource(new RoleBindingBuilder()
                        .withNewMetadata().withName(sa).withNamespace(ns).endMetadata()
                        .withNewRoleRef().withApiGroup("rbac.authorization.k8s.io").withKind("ClusterRole").withName(role).endRoleRef()
                        .addNewSubject().withKind("ServiceAccount").withName(sa).withNamespace(SystemConstant.SYSTEM_NAMESPACE).endSubject()
                        .build()).create();
                allocations.transition(row.getId(), "ACTIVE", null);
            }
            default -> throw new IllegalStateException(state);
        }
    }

    private void stopPods(PlatformTenantNamespace row, KubernetesClient client, String ns) {
        var quota = client.resourceQuotas().inNamespace(ns).withName(QUOTA).get();
        if (quota == null) {
            client.resourceQuotas().inNamespace(ns).resource(new ResourceQuotaBuilder()
                    .withNewMetadata().withName(QUOTA).withNamespace(ns).addToLabels(MARKER, row.getId()).endMetadata()
                    .withNewSpec().withHard(Map.of("count/pods", new Quantity("0"))).endSpec().build()).create();
            return;
        }
        if (!row.getId().equals(quota.getMetadata().getLabels().get(MARKER)))
            throw new IllegalStateException("存在同名回收配额，归属不符");
        if (quota.getSpec() == null || quota.getSpec().getHard() == null
                || !new Quantity("0").equals(quota.getSpec().getHard().get("count/pods"))
                || (quota.getSpec().getScopes() != null && !quota.getSpec().getScopes().isEmpty())
                || quota.getSpec().getScopeSelector() != null || quota.getStatus() == null || quota.getStatus().getHard() == null
                || !new Quantity("0").equals(quota.getStatus().getHard().get("count/pods")))
            throw new IllegalStateException("正在等待 Pod 数量配额生效");
        verifyPodAdmission(client, ns);
        var pods = client.pods().inNamespace(ns).list().getItems();
        var jobs = client.batch().v1().cronjobs().inNamespace(ns).list().getItems();
        if (!row.getNamespaceUid().equals(client.namespaces().withName(ns).require().getMetadata().getUid()))
            throw new IllegalStateException("空间 UID 已改变");
        if (pods.stream().anyMatch(p -> p.getSpec().getVolumes() != null && p.getSpec().getVolumes().stream().anyMatch(v -> v.getEphemeral() != null)))
            throw new IllegalStateException("有 Pod 使用临时 PVC，需先迁移数据");
        var podUids = pods.stream().map(p -> p.getMetadata().getUid()).collect(java.util.stream.Collectors.toSet());
        if (client.persistentVolumeClaims().inNamespace(ns).list().getItems().stream().anyMatch(pvc -> pvc.getMetadata()
                .getOwnerReferences().stream().anyMatch(owner -> "Pod".equals(owner.getKind()) && podUids.contains(owner.getUid()))))
            throw new IllegalStateException("存在由 Pod 持有的 PVC，需先迁移数据");
        for (var cron : jobs) {
            if (!Boolean.TRUE.equals(cron.getSpec().getSuspend())) {
                cron.getMetadata().getAnnotations().put(MARKER, row.getId());
                cron.getSpec().setSuspend(true);
                client.resource(cron).lockResourceVersion().update();
            }
        }
        for (var pod : pods) {
            if (pod.getMetadata().getDeletionTimestamp() == null)
                client.resource(pod).lockResourceVersion(pod.getMetadata().getResourceVersion()).delete();
        }
        if (pods.isEmpty()) allocations.transition(row.getId(), "STOPPED", null);
    }

    private void checkExclusive(PlatformTenantNamespace row) {
        if (allocations.selectByClusterAndNs(row.getClusterId(), row.getNamespace()).stream()
                .anyMatch(other -> !row.getId().equals(other.getId())))
            throw new IllegalStateException("空间还有其他分配");
    }

    void verifyPodAdmission(KubernetesClient client, String ns) {
        var probe = new PodBuilder()
                .withNewMetadata().withNamespace(ns).withGenerateName("recovery-check-").endMetadata()
                .withNewSpec().addNewContainer().withName("check").withImage("registry.k8s.io/pause:3.10")
                .withNewResources().addToRequests("cpu", new Quantity("1m")).addToLimits("cpu", new Quantity("1m"))
                .addToRequests("memory", new Quantity("1Mi")).addToLimits("memory", new Quantity("1Mi"))
                .endResources().endContainer().endSpec().build();
        try {
            client.resource(probe).dryRun().create();
        } catch (KubernetesClientException e) {
            String reason = Objects.toString(e.getMessage(), "");
            if (e.getCode() == 403 && reason.contains("exceeded quota: " + QUOTA + ",") && reason.contains("count/pods")) return;
            throw new IllegalStateException("无法验证回收配额的准入结果：" + reason, e);
        }
        throw new IllegalStateException("集群仍允许创建 Pod，等待回收配额生效");
    }
}
