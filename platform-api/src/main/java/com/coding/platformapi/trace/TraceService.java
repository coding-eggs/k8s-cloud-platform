package com.coding.platformapi.trace;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.data.mapper.auth.PlatformTenantNamespaceMapper;
import com.coding.data.models.auth.PlatformTenantNamespace;
import com.coding.platformapi.services.ClusterService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 链路的租户边界。
 *
 * <p>trace 整体没有命名空间，但**每条 span 有**：{@code resource.cluster.name} 由 gateway 统一盖，
 * {@code resource.k8s.namespace.name} 由 k8sattributes 从 Pod 提取。所以租户可见范围 =
 * 分配表里 (集群, 命名空间) 的集合：
 * <ul>
 *   <li>列表 / 服务候选：把集合拼进 TraceQL，由 Tempo 服务端过滤；</li>
 *   <li>详情：Tempo 的 trace-by-ID 只按 ID 返回整条 trace、没有 span 级投影，所以拉回后
 *       在平台侧按 span 的 (cluster, namespace) 过滤；过滤后为空按「不存在」处理，
 *       不暴露「这条 trace 存在但你看不到」。</li>
 * </ul>
 *
 * <p>平台管理员（base token，{@code forcedTenantId == null}）不加任何条件，看全量。
 * 管理员戴上租户帽时与租户用户同规则（与日志查询一致）。
 */
@Service
public class TraceService {

    private final TempoTraceClient tempo;
    private final PlatformTenantNamespaceMapper allocations;
    private final ClusterService clusters;

    public TraceService(TempoTraceClient tempo, PlatformTenantNamespaceMapper allocations,
                        ClusterService clusters) {
        this.tempo = tempo;
        this.allocations = allocations;
        this.clusters = clusters;
    }

    public List<TraceSummaryDTO> search(TraceSearchRequest req, String forcedTenantId) {
        TraceSearchRequest r = req == null ? new TraceSearchRequest() : req;
        return tempo.search(r, scopes(StringUtils.hasText(forcedTenantId) ? forcedTenantId : r.getTenantId()));
    }

    public List<String> serviceNames(String clusterName, String namespace, Long start, Long end,
                                     String requestedTenantId, String forcedTenantId) {
        String tenant = StringUtils.hasText(forcedTenantId) ? forcedTenantId : requestedTenantId;
        return tempo.serviceNames(clusterName, namespace, start, end, scopes(tenant));
    }

    public TraceDetailDTO get(String traceId, String forcedTenantId) {
        TraceDetailDTO detail = tempo.get(traceId);
        List<TraceScope> scopes = scopes(forcedTenantId);
        if (scopes.isEmpty() || detail.getSpans() == null) {
            return detail;
        }
        List<SpanDTO> visible = detail.getSpans().stream()
                .filter(span -> inScope(scopes, span))
                .toList();
        if (visible.isEmpty()) {
            throw new CloudPlatformException(EnumResponseType.ERROR,
                    "trace 不存在，或无权查看（不在你被分配的命名空间内）");
        }
        return new TraceDetailDTO(detail.getTraceId(), visible);
    }

    private static boolean inScope(List<TraceScope> scopes, SpanDTO span) {
        if (span.getClusterName() == null || span.getNamespace() == null) {
            return false; // 缺资源属性的 span（istio/waypoint 等）对租户不可见
        }
        for (TraceScope scope : scopes) {
            if (scope.clusterName().equals(span.getClusterName())
                    && scope.namespace().equals(span.getNamespace())) {
                return true;
            }
        }
        return false;
    }

    /** 空列表 = 平台管理员（不过滤）；租户没有可用分配时直接拒绝（fail closed）。 */
    private List<TraceScope> scopes(String tenantId) {
        if (!StringUtils.hasText(tenantId)) {
            return List.of();
        }
        List<PlatformTenantNamespace> rows = allocations.listByTenant(tenantId);
        if (rows == null || rows.isEmpty()) {
            throw new CloudPlatformException(EnumResponseType.ERROR,
                    "该租户没有分配任何命名空间，无法查询链路");
        }
        Set<TraceScope> out = new LinkedHashSet<>();
        for (PlatformTenantNamespace row : rows) {
            try {
                out.add(new TraceScope(clusters.requireName(row.getClusterId()), row.getNamespace()));
            } catch (Exception e) {
                // 集群已删除：分配行还在，跳过
            }
        }
        if (out.isEmpty()) {
            throw new CloudPlatformException(EnumResponseType.ERROR,
                    "该租户的集群都不存在，无法查询链路");
        }
        return new ArrayList<>(out);
    }
}
