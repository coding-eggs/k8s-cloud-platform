package com.coding.platformapi.controllers;

import com.coding.common.models.k8s.dto.ResourceSearch.*;
import com.coding.common.models.system.ResponseData;
import com.coding.common.security.AuthContext;
import com.coding.data.mapper.auth.PlatformTenantNamespaceMapper;
import com.coding.platformapi.k8s.K8sServerGateway;
import com.coding.data.mapper.system.PlatformApplicationMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpMethod;
import org.springframework.web.bind.annotation.*;
import java.util.ArrayList;
import java.util.List;

import static com.coding.common.models.k8s.dto.ResourceSearch.order;

@RestController
@RequestMapping("/resource-lookup")
@RequiredArgsConstructor
public class ResourceLookupController {
    private final AuthContext auth;
    private final PlatformTenantNamespaceMapper allocations;
    private final K8sServerGateway cluster;
    private final PlatformApplicationMapper apps;

    @PostMapping("/applications")
    public ResponseData<Results> applications(@RequestBody Query q) { return search(q,"Application"); }
    @PostMapping("/pods")
    public ResponseData<Results> pods(@RequestBody Query q) { return search(q,"Pod"); }
    @PostMapping("/workloads")
    public ResponseData<Results> workloads(@RequestBody Query q) { return search(q,"Workload"); }

    private ResponseData<Results> search(Query q, String kind) {
        String term = q.term();
        String tenant = auth.effectiveTenantId(q.tenantId());
        if (tenant == null || (auth.hatTenantId() == null && !auth.isPlatformSide())) throw new IllegalArgumentException("请选择租户");
        List<Hit> hits = new ArrayList<>();
        boolean partial = false;
        boolean truncated = false;
        if ("Application".equals(kind)) {
            for (var app : apps.searchActive(tenant, q.clusterId(), q.namespace(), term))
                hits.add(new Hit(app.getName(), kind, app.getNamespace(), app.getId()));
        } else {
            var spaces = allocations.listByTenantAndCluster(tenant,q.clusterId()).stream()
                    .map(a -> a.getNamespace()).filter(n -> q.namespace()==null || q.namespace().equals(n)).distinct().sorted().toList();
            long deadline = System.nanoTime() + 3_000_000_000L;
            for (int i=0; i<spaces.size(); i++) {
                if (i >= 12 || System.nanoTime() >= deadline) { truncated = true; break; }
                try {
                    Results result = cluster.exchange(HttpMethod.POST,"/resource-lookup/names",null,
                            new Query(q.clusterId(),tenant,spaces.get(i),term,kind),cluster.responseType(Results.class));
                    hits.addAll(result.hits());
                    truncated |= result.truncated();
                } catch (RuntimeException ex) { partial = true; }
            }
        }
        return new ResponseData<>(new Results(hits.stream().sorted(order(term)).limit(20).toList(),truncated || hits.size()>20,partial));
    }
}
