package com.coding.platformapi.controllers;

import com.coding.common.models.k8s.dto.NamespaceStatEntry;
import com.coding.common.models.k8s.dto.TenantOverviewDTO;
import com.coding.common.models.system.ResponseData;
import com.coding.platformapi.metrics.MetricsService;
import com.coding.platformapi.metrics.dto.MetricSeriesResponse;
import com.coding.platformapi.models.TenantOverviewRequest;
import com.coding.platformapi.security.AuthContext;
import com.coding.platformapi.security.TenantContextResolver;
import com.coding.platformapi.services.TenantOverviewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 租户概览（一页一 controller：租户概览页 ↔ 本类）。
 * <p><b>范围</b>取自分配表（{@link TenantOverviewService}），<b>事实</b>取自各命名空间切片；曲线走
 * {@link MetricsService}（与 {@code ClusterController} 对集群指标的做法一致 —— controller 同时持有
 * 快照服务与指标服务）。
 *
 * <p><b>生效租户</b>：每个方法首行用 {@link TenantContextResolver#requireContext} 裁决 ——
 * 自管轨锁定 token 租户（传别人的 tenantId 直接 {@code TENANT_MISMATCH}），代管轨（平台管理员）必须显式传。
 * 授权由 seed 行的 ANY-of 放行：{@code tenant:overview:view}（租户族）/ {@code platform:allocation:list}（平台族）。
 */
@Tag(name = "租户概览", description = "租户概览首屏快照 / 资源明细 / CPU·内存用量曲线")
@RestController
@RequestMapping("/tenant/overview")
@RequiredArgsConstructor
public class TenantOverviewController {

    private final TenantOverviewService overviewService;
    private final MetricsService metricsService;
    private final TenantContextResolver ctx;
    private final AuthContext auth;

    @PostMapping
    @Operation(summary = "租户概览快照",
            description = "合计 + 逐集群小计 + 异常 Pod / 不健康工作负载名单 + 配额三档；逐集群降级（某集群取不到数只标记、不整页失败）")
    public ResponseData<TenantOverviewDTO> overview(@RequestBody TenantOverviewRequest req) {
        String tid = ctx.requireContext(tokenTenantId(), req.getTenantId());
        return new ResponseData<>(overviewService.overview(tid));
    }

    @PostMapping("/breakdown")
    @Operation(summary = "租户概览资源明细（per-namespace）",
            description = "每行回带 clusterId + quotaState（配额三档判定在后端一处）；与 /tenant/overview 同源，两边数字一致")
    public ResponseData<List<NamespaceStatEntry>> breakdown(@RequestBody TenantOverviewRequest req) {
        String tid = ctx.requireContext(tokenTenantId(), req.getTenantId());
        return new ResponseData<>(overviewService.breakdown(tid));
    }

    @PostMapping("/metrics/cpu")
    @Operation(summary = "租户 CPU 用量曲线（核）",
            description = "按租户命名空间集合聚合的**一条**「租户合计」曲线；clusterId 缺省=租户全部集群，传则限定到该集群")
    public ResponseData<MetricSeriesResponse> cpuMetrics(@RequestBody TenantOverviewRequest req) {
        String tid = ctx.requireContext(tokenTenantId(), req.getTenantId());
        return new ResponseData<>(metricsService.tenantCpu(tid, scope(tid, req.getClusterId()), req.getStart(), req.getEnd()));
    }

    @PostMapping("/metrics/memory")
    @Operation(summary = "租户内存用量曲线（字节）",
            description = "同 /metrics/cpu，单位为字节")
    public ResponseData<MetricSeriesResponse> memoryMetrics(@RequestBody TenantOverviewRequest req) {
        String tid = ctx.requireContext(tokenTenantId(), req.getTenantId());
        return new ResponseData<>(metricsService.tenantMemory(tid, scope(tid, req.getClusterId()), req.getStart(), req.getEnd()));
    }

    /**
     * 曲线的取数作用域：租户的 (集群 → 该集群下本租户的命名空间)，只在这里查一次（走 {@link TenantOverviewService}
     * 的分配表读法，与快照/明细同源，不在两处各查一次）。**成对**给出而非两个扁平列表 —— 逐集群按各自的 ns 集合过滤，
     * 避免同名 ns 归属别的租户时被并进来（R12）。{@code clusterId} 非空 → 只取该集群。
     */
    private Map<String, List<String>> scope(String tenantId, String clusterId) {
        Map<String, List<String>> byCluster = overviewService.allocationsByCluster(tenantId);
        if (StringUtils.hasText(clusterId)) {
            return Map.of(clusterId, byCluster.getOrDefault(clusterId, List.of()));
        }
        return byCluster;
    }

    /** token 内租户身份（自管用户）；admin/base token 无 tenantInfo → null（代管）。写法同 {@code TenantController}。 */
    private String tokenTenantId() {
        var info = auth.current();
        return (info != null && info.getTenantInfo() != null) ? info.getTenantInfo().getTenantId() : null;
    }
}
