package com.coding.k8sserver.app;

import com.coding.common.models.app.AppEventDTO;
import com.coding.common.models.app.AppEventsRequestDTO;
import com.coding.k8score.factory.KubernetesClientFactory;
import io.fabric8.kubernetes.api.model.Event;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 应用页「事件」页签：把归属这个应用的 Kubernetes 事件收出来。
 *
 * <p>归属口径 = 平台自己的标签 {@code platform.coding/app=<appId>}（应用 chart 给工作负载、Pod、Service
 * 都打了这个标签）。Deployment 建出来的 ReplicaSet / Pod 名字都是「工作负载名-」开头，
 * 所以工作负载只记前缀、不逐个列举。
 *
 * <p>只读、不缓存：事件在 apiserver 里默认只留 1 小时，缓存没有意义。
 */
@Service
public class AppEventsService {

    /** 应用 chart 的归属标签（见 charts/app/workload 的 _helpers.tpl） */
    static final String APP_LABEL = "platform.coding/app";

    private static final int DEFAULT_LIMIT = 200;
    private static final int MAX_LIMIT = 500;

    private final KubernetesClientFactory clients;

    public AppEventsService(KubernetesClientFactory clients) {
        this.clients = clients;
    }

    public List<AppEventDTO> events(AppEventsRequestDTO req) {
        KubernetesClient client = clients.getAdminClient(req.getClusterId());
        String ns = req.getNamespace();
        var selector = java.util.Map.of(APP_LABEL, req.getAppId());

        // 精确命中的对象：Pod / Service（它们各自的 Pod 名就是最终要匹配的那个名字）
        Set<String> exact = new HashSet<>();
        client.pods().inNamespace(ns).withLabels(selector).list().getItems()
                .forEach(p -> exact.add(p.getMetadata().getName()));
        client.services().inNamespace(ns).withLabels(selector).list().getItems()
                .forEach(s -> exact.add(s.getMetadata().getName()));

        // 工作负载：ReplicaSet、Pod 都叫「工作负载名-xxx」，按前缀收
        List<String> prefixes = new ArrayList<>();
        client.apps().deployments().inNamespace(ns).withLabels(selector).list().getItems()
                .forEach(d -> prefixes.add(d.getMetadata().getName()));
        client.apps().statefulSets().inNamespace(ns).withLabels(selector).list().getItems()
                .forEach(s -> prefixes.add(s.getMetadata().getName()));

        int limit = req.getLimit() == null ? DEFAULT_LIMIT : Math.max(1, Math.min(MAX_LIMIT, req.getLimit()));
        return client.v1().events().inNamespace(ns).list().getItems().stream()
                .filter(e -> e.getInvolvedObject() != null)
                .filter(e -> belongs(e.getInvolvedObject().getName(), exact, prefixes))
                .sorted((a, b) -> stampOf(b).compareTo(stampOf(a)))
                .limit(limit)
                .map(AppEventsService::viewOf)
                .toList();
    }

    /** 事件是否属于这个应用：名字精确命中，或以某个工作负载名 + "-" 开头。 */
    static boolean belongs(String name, Set<String> exact, List<String> prefixes) {
        if (name == null || name.isBlank()) {
            return false;
        }
        if (exact.contains(name)) {
            return true;
        }
        for (String prefix : prefixes) {
            if (name.startsWith(prefix + "-")) {
                return true;
            }
        }
        return false;
    }

    /** 排序/展示时间：core v1 的 lastTimestamp 可能为空，退到 metadata.creationTimestamp。 */
    static String stampOf(Event e) {
        if (e.getLastTimestamp() != null && !e.getLastTimestamp().isBlank()) {
            return e.getLastTimestamp();
        }
        if (e.getMetadata() != null && e.getMetadata().getCreationTimestamp() != null) {
            return e.getMetadata().getCreationTimestamp();
        }
        return "";
    }

    private static AppEventDTO viewOf(Event e) {
        AppEventDTO v = new AppEventDTO();
        v.setType(e.getType());
        v.setReason(e.getReason());
        v.setMessage(e.getMessage());
        v.setObjectKind(e.getInvolvedObject() == null ? null : e.getInvolvedObject().getKind());
        v.setObjectName(e.getInvolvedObject() == null ? null : e.getInvolvedObject().getName());
        v.setCount(e.getCount());
        v.setLastTimestamp(stampOf(e));
        v.setFirstTimestamp(e.getFirstTimestamp());
        return v;
    }
}
