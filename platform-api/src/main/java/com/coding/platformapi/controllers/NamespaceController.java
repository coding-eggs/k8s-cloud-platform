package com.coding.platformapi.controllers;

import com.coding.common.models.k8s.dto.LimitRangeDTO;
import com.coding.common.models.k8s.dto.NamespaceDTO;
import com.coding.common.models.k8s.dto.ResourceQuotaDTO;
import com.coding.common.models.system.ResponseData;
import com.coding.platformapi.metrics.MetricsService;
import com.coding.platformapi.metrics.dto.MetricSeriesResponse;
import com.coding.platformapi.metrics.dto.NamespaceMetricsRequest;
import com.coding.platformapi.models.ClusterKeyRequest;
import com.coding.platformapi.models.NamespaceKeyRequest;
import com.coding.platformapi.models.NamespaceLimitRangeUpsertRequest;
import com.coding.platformapi.models.NamespaceQuotaUpsertRequest;
import com.coding.platformapi.models.NamespaceUpsertRequest;
import com.coding.platformapi.models.NamespaceView;
import com.coding.platformapi.services.NamespaceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "命名空间管理", description = "集群内 K8s 命名空间视图（含分配信息）/ 创建 / 编辑 / 删除未分配命名空间")
@RestController
@RequestMapping("/namespace")
public class NamespaceController {

    @Autowired
    private NamespaceService namespaceService;

    @Autowired
    private MetricsService metricsService;

    @PostMapping("/list")
    @Operation(summary = "集群命名空间列表", description = "K8s 命名空间 + 分配信息合并视图（名字/状态/创建时间/管理方式/已分配租户/描述/标签）")
    public ResponseData<List<NamespaceView>> list(@RequestBody ClusterKeyRequest request) {
        return new ResponseData<>(namespaceService.list(request.getClusterId()));
    }

    @PostMapping("/get")
    @Operation(summary = "查询单个命名空间", description = "K8s 命名空间 + 分配信息合并视图（与列表同加工）")
    public ResponseData<NamespaceView> get(@RequestBody NamespaceKeyRequest request) {
        return new ResponseData<>(namespaceService.get(request.getClusterId(), request.getNamespace()));
    }

    @PostMapping("/yaml")
    @Operation(summary = "查询命名空间 YAML（只读展示）")
    public ResponseData<String> yaml(@RequestBody NamespaceKeyRequest request) {
        return new ResponseData<>(namespaceService.yaml(request.getClusterId(), request.getNamespace()));
    }

    @PostMapping("/create")
    @Operation(summary = "创建命名空间", description = "名字合法且集群内不存在；managed-by 标签由 k8s-server 侧盖章")
    public ResponseData<NamespaceDTO> create(@RequestBody NamespaceUpsertRequest request) {
        return new ResponseData<>(namespaceService.create(request));
    }

    @PostMapping("/update")
    @Operation(summary = "编辑命名空间", description = "仅平台创建（带 managed-by 标签）可编辑描述与标签")
    public ResponseData<NamespaceDTO> update(@RequestBody NamespaceUpsertRequest request) {
        return new ResponseData<>(namespaceService.update(request));
    }

    @PostMapping("/delete")
    @Operation(summary = "删除命名空间", description = "仅当平台管理且未分配给任何租户时允许")
    public ResponseData<Void> delete(@RequestBody NamespaceKeyRequest request) {
        namespaceService.delete(request.getClusterId(), request.getNamespace());
        return new ResponseData<>();
    }

    @PostMapping("/quota/get")
    @Operation(summary = "查询命名空间配额", description = "未配置返回 null；存在多份时置 multiple=true（平台只管理名为 default 的那份）")
    public ResponseData<ResourceQuotaDTO> quotaGet(@RequestBody NamespaceKeyRequest request) {
        return new ResponseData<>(namespaceService.quotaGet(request.getClusterId(), request.getNamespace()));
    }

    @PostMapping("/quota/upsert")
    @Operation(summary = "创建/更新命名空间配额", description = "对象名固定 default；已存在则更新，否则创建（重复提交安全）；仅平台创建的命名空间可操作")
    public ResponseData<ResourceQuotaDTO> quotaUpsert(@RequestBody NamespaceQuotaUpsertRequest request) {
        return new ResponseData<>(namespaceService.quotaUpsert(request));
    }

    @PostMapping("/quota/delete")
    @Operation(summary = "删除命名空间配额", description = "不存在时为幂等 no-op；仅平台创建的命名空间可操作")
    public ResponseData<Void> quotaDelete(@RequestBody NamespaceKeyRequest request) {
        namespaceService.quotaDelete(request.getClusterId(), request.getNamespace());
        return new ResponseData<>();
    }

    @PostMapping("/limitrange/get")
    @Operation(summary = "查询命名空间限制范围", description = "未配置返回 null；存在多份时置 multiple=true（平台只管理名为 default 的那份）")
    public ResponseData<LimitRangeDTO> limitRangeGet(@RequestBody NamespaceKeyRequest request) {
        return new ResponseData<>(namespaceService.limitRangeGet(request.getClusterId(), request.getNamespace()));
    }

    @PostMapping("/limitrange/upsert")
    @Operation(summary = "创建/更新命名空间限制范围", description = "对象名固定 default；类型仅支持 Container / PersistentVolumeClaim，且校验 max≥min、default≥defaultRequest；仅平台创建的命名空间可操作")
    public ResponseData<LimitRangeDTO> limitRangeUpsert(@RequestBody NamespaceLimitRangeUpsertRequest request) {
        return new ResponseData<>(namespaceService.limitRangeUpsert(request));
    }

    @PostMapping("/limitrange/delete")
    @Operation(summary = "删除命名空间限制范围", description = "不存在时为幂等 no-op；仅平台创建的命名空间可操作")
    public ResponseData<Void> limitRangeDelete(@RequestBody NamespaceKeyRequest request) {
        namespaceService.limitRangeDelete(request.getClusterId(), request.getNamespace());
        return new ResponseData<>();
    }

    // ===== 监控指标（委托 MetricsService；跨该 ns 全部 pod 聚合，集群级无租户）=====

    @PostMapping("/metrics/cpu")
    @Operation(summary = "命名空间 CPU 用量（核）", description = "跨该命名空间全部容器聚合")
    public ResponseData<MetricSeriesResponse> cpuMetrics(@RequestBody NamespaceMetricsRequest request) {
        return new ResponseData<>(metricsService.namespaceCpu(request));
    }

    @PostMapping("/metrics/memory")
    @Operation(summary = "命名空间内存用量（字节）", description = "跨该命名空间全部容器聚合")
    public ResponseData<MetricSeriesResponse> memoryMetrics(@RequestBody NamespaceMetricsRequest request) {
        return new ResponseData<>(metricsService.namespaceMemory(request));
    }

    @PostMapping("/metrics/network")
    @Operation(summary = "命名空间网络 IO（字节/秒，RX/TX）", description = "跨该命名空间全部 pod 聚合")
    public ResponseData<MetricSeriesResponse> networkMetrics(@RequestBody NamespaceMetricsRequest request) {
        return new ResponseData<>(metricsService.namespaceNetwork(request));
    }

    @PostMapping("/metrics/disk")
    @Operation(summary = "命名空间磁盘 IO（字节/秒，读/写）", description = "跨该命名空间全部容器聚合")
    public ResponseData<MetricSeriesResponse> diskMetrics(@RequestBody NamespaceMetricsRequest request) {
        return new ResponseData<>(metricsService.namespaceDisk(request));
    }
}
