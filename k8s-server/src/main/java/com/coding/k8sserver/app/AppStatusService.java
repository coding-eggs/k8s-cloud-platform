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
                s.setState(deploymentState(want, have, observed));
                s.setMessage("可用副本 " + have + "/" + want);
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
                s.setState(statefulSetState(want, have));
                s.setMessage("就绪副本 " + have + "/" + want);
            }
            default -> s.setState(exists(client, ref) ? "READY" : "MISSING");
        }
        return s;
    }

    /**
     * Deployment：既要副本数够，也要控制器已经观测到这一代 spec —— 否则升级刚提交时
     * 旧副本还满员，会被误判成 READY。
     */
    static String deploymentState(int want, int available, boolean observed) {
        return observed && available >= want ? "READY" : "PENDING";
    }

    static String statefulSetState(int want, int ready) {
        return ready >= want ? "READY" : "PENDING";
    }

    /** 我们生成的其余对象都只有「存在/不存在」两种有意义的状态。 */
    private static boolean exists(KubernetesClient client, AppObjectRefDTO ref) {
        return switch (ref.getKind()) {
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
            // 未知 kind 当作存在：宁可让冲突检查保持保守（不误拦），也不谎报对象缺失
            default -> true;
        };
    }
}
