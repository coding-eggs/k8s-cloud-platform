package com.coding.k8sserver.app;

import com.coding.common.models.app.AppObjectRefDTO;
import com.coding.common.models.app.AppObjectStatusDTO;
import com.coding.common.models.app.AppStatusRequestDTO;
import com.coding.common.models.app.AppStatusResultDTO;
import com.coding.k8score.factory.KubernetesClientFactory;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.StatefulSet;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 现读被跟踪对象的状态；不做缓存（部署期几秒一次，量也小）。
 *
 * <p>状态只回答「现在能不能算就绪」，不判断业务健康 —— 那是探针与监控的事。
 */
@Service
public class AppStatusService {

    private static final ResourceDefinitionContext SERVICE_MONITOR = new ResourceDefinitionContext.Builder()
            .withGroup("monitoring.coreos.com")
            .withVersion("v1")
            .withKind("ServiceMonitor")
            .withPlural("servicemonitors")
            .withNamespaced(true)
            .build();

    private final KubernetesClientFactory clients;

    public AppStatusService(KubernetesClientFactory clients) {
        this.clients = clients;
    }

    public AppStatusResultDTO status(AppStatusRequestDTO req) {
        List<AppObjectStatusDTO> out = new ArrayList<>();
        KubernetesClient client = clients.getAdminClient(req.getClusterId());
        for (AppObjectRefDTO ref : req.getObjects()) {
            out.add(stateOf(client, ref));
        }
        AppStatusResultDTO r = new AppStatusResultDTO();
        r.setObjects(out);
        return r;
    }

    private AppObjectStatusDTO stateOf(KubernetesClient client, AppObjectRefDTO ref) {
        AppObjectStatusDTO s = new AppObjectStatusDTO();
        s.setKind(ref.getKind());
        s.setName(ref.getName());
        switch (ref.getKind()) {
            case "Deployment" -> {
                Deployment d = client.apps().deployments()
                        .inNamespace(ref.getNamespace()).withName(ref.getName()).get();
                if (d == null) {
                    s.setState("MISSING");
                    return s;
                }
                int want = d.getSpec().getReplicas() == null ? 1 : d.getSpec().getReplicas();
                int have = d.getStatus() == null || d.getStatus().getAvailableReplicas() == null
                        ? 0 : d.getStatus().getAvailableReplicas();
                boolean observed = d.getStatus() != null
                        && d.getStatus().getObservedGeneration() != null
                        && d.getMetadata().getGeneration() != null
                        && d.getStatus().getObservedGeneration() >= d.getMetadata().getGeneration();
                int updated = d.getStatus() == null || d.getStatus().getUpdatedReplicas() == null
                        ? 0 : d.getStatus().getUpdatedReplicas();
                int total = d.getStatus() == null || d.getStatus().getReplicas() == null
                        ? 0 : d.getStatus().getReplicas();
                s.setState(deploymentState(want, have, updated, total, observed));
                s.setMessage("可用副本 " + have + "/" + want + "，已更新 " + updated + "/" + want);
                if (!"READY".equals(s.getState())) {
                    s.setPodIssues(AppPodDiagnosis.diagnose(client, ref.getNamespace(), d.getSpec().getSelector()));
                }
            }
            case "StatefulSet" -> {
                StatefulSet sts = client.apps().statefulSets()
                        .inNamespace(ref.getNamespace()).withName(ref.getName()).get();
                if (sts == null) {
                    s.setState("MISSING");
                    return s;
                }
                int want = sts.getSpec().getReplicas() == null ? 1 : sts.getSpec().getReplicas();
                int have = sts.getStatus() == null || sts.getStatus().getReadyReplicas() == null
                        ? 0 : sts.getStatus().getReadyReplicas();
                int updated = sts.getStatus() == null || sts.getStatus().getUpdatedReplicas() == null
                        ? 0 : sts.getStatus().getUpdatedReplicas();
                boolean observed = sts.getStatus() != null
                        && sts.getStatus().getObservedGeneration() != null
                        && sts.getMetadata().getGeneration() != null
                        && sts.getStatus().getObservedGeneration() >= sts.getMetadata().getGeneration();
                boolean settled = sts.getStatus() != null && revisionSettled(
                        sts.getStatus().getCurrentRevision(), sts.getStatus().getUpdateRevision(), updated, want);
                s.setState(statefulSetState(want, have, settled, observed));
                s.setMessage("就绪副本 " + have + "/" + want + "，已更新 " + updated + "/" + want);
                if (!"READY".equals(s.getState())) {
                    s.setPodIssues(AppPodDiagnosis.diagnose(client, ref.getNamespace(), sts.getSpec().getSelector()));
                }
            }
            default -> s.setState(exists(client, ref));
        }
        return s;
    }

    /**
     * Deployment：控制器已观测到这一代 spec、副本数够、且全部副本都已更新到新模板。
     * 只看 available 会在滚动中被旧副本蒙混过关（旧副本仍满员）。
     */
    static String deploymentState(int want, int available, int updated, int total, boolean observed) {
        // total == updated：没有旧副本残留（新 Pod 起不来时旧 Pod 仍 available，total > updated）
        return observed && available >= want && updated >= want && total == updated ? "READY" : "PENDING";
    }

    static String statefulSetState(int want, int ready, boolean revisionSettled, boolean observed) {
        return observed && revisionSettled && ready >= want ? "READY" : "PENDING";
    }

    /**
     * currentRevision 等于 updateRevision 即收敛；或 updatedReplicas 已达期望数
     * （部分 K8s 版本滚动完成后不更新 currentRevision，不能只比较 revision）。
     */
    static boolean revisionSettled(String currentRevision, String updateRevision, int updatedReplicas, int want) {
        if (updatedReplicas >= want) {
            return true;
        }
        return currentRevision != null && currentRevision.equals(updateRevision);
    }

    /**
     * 我们生成的其余对象都只有「存在/不存在」两种有意义的状态。
     *
     * <p>不认识的 kind 返回 {@code UNKNOWN}（不是「存在」也不是「不存在」）：CONFLICT 阶段会忽略
     * UNKNOWN，HEALTH 阶段按未就绪处理。返回「存在」会让新加的节点类型一律被误判成别人的对象。
     */
    private static String exists(KubernetesClient client, AppObjectRefDTO ref) {
        Boolean present = switch (ref.getKind()) {
            case "Service" -> client.services()
                    .inNamespace(ref.getNamespace()).withName(ref.getName()).get() != null;
            case "ConfigMap" -> client.configMaps()
                    .inNamespace(ref.getNamespace()).withName(ref.getName()).get() != null;
            case "Secret" -> client.secrets()
                    .inNamespace(ref.getNamespace()).withName(ref.getName()).get() != null;
            case "HorizontalPodAutoscaler" -> client.autoscaling().v2().horizontalPodAutoscalers()
                    .inNamespace(ref.getNamespace()).withName(ref.getName()).get() != null;
            case "ServiceMonitor" -> client.genericKubernetesResources(SERVICE_MONITOR)
                    .inNamespace(ref.getNamespace()).withName(ref.getName()).get() != null;
            // 不认识的 kind：既不敢说存在也不敢说不存在
            default -> null;
        };
        if (present == null) {
            return "UNKNOWN";
        }
        return present ? "READY" : "MISSING";
    }
}
