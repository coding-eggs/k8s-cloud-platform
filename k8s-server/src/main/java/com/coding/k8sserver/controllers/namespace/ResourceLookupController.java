package com.coding.k8sserver.controllers.namespace;

import com.coding.common.models.k8s.dto.ResourceSearch.*;
import com.coding.common.models.system.ResponseData;
import com.coding.k8score.factory.KubernetesClientFactory;
import com.coding.k8sserver.components.*;
import io.fabric8.kubernetes.api.model.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import static com.coding.common.models.k8s.dto.ResourceSearch.order;

@RestController
@RequestMapping("/resource-lookup")
@RequiredArgsConstructor
public class ResourceLookupController implements AccessBoundaryAware {
    private final ResourceAccessResolver access;
    private final KubernetesClientFactory clients;
    public AccessBoundary accessBoundary() { return AccessBoundary.TENANT; }

    @PostMapping("/names")
    public ResponseData<Results> names(@RequestBody Query q) {
        String term = q.term();
        var scope = access.resolveNamespacedAccess(q.tenantId(), q.clusterId(), q.namespace());
        var client = scope.adminMode() ? clients.getAdminClient(q.clusterId()) : clients.getTenantClient(q.clusterId(), scope.tenantId());
        var limit = new ListOptionsBuilder().withLimit(250L).build();
        List<KubernetesResourceList<? extends HasMetadata>> pages = switch (q.kind()) {
            case "Pod" -> List.of(client.pods().inNamespace(q.namespace()).list(limit));
            case "Workload" -> List.of(client.apps().deployments().inNamespace(q.namespace()).list(limit),
                    client.apps().statefulSets().inNamespace(q.namespace()).list(limit),
                    client.apps().daemonSets().inNamespace(q.namespace()).list(limit));
            default -> throw new IllegalArgumentException("未知资源类型");
        };
        boolean truncated = false;
        List<Hit> hits = new ArrayList<>();
        for (var page : pages) {
            truncated |= page.getMetadata().getContinue() != null && !page.getMetadata().getContinue().isEmpty();
            for (var item : page.getItems()) {
                String name = item.getMetadata().getName();
                if (name.toLowerCase(Locale.ROOT).contains(term)) hits.add(new Hit(name,item.getKind(),q.namespace(),null));
            }
        }
        return new ResponseData<>(new Results(hits.stream().sorted(order(term)).limit(20).toList(), truncated || hits.size()>20,false));
    }
}
