package com.coding.platformapi.controllers;

import com.coding.common.models.k8s.dto.ClusterOverviewDTO;
import com.coding.common.models.k8s.dto.NamespaceStatDTO;
import com.coding.common.models.system.ResponseData;
import com.coding.data.models.k8s.K8sCluster;
import com.coding.platformapi.metrics.MetricsService;
import com.coding.platformapi.metrics.dto.ClusterMetricsRequest;
import com.coding.platformapi.metrics.dto.MetricSeriesResponse;
import com.coding.platformapi.models.ClusterCreateRequest;
import com.coding.platformapi.models.ClusterKeyRequest;
import com.coding.platformapi.models.ClusterToggleRequest;
import com.coding.platformapi.models.ClusterUpdateRequest;
import com.coding.platformapi.services.ClusterService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@Tag(name = "集群管理", description = "集群新增 / 列表 / 详情 / 更新 / 启停 / 删除 / 重新开通 / 刷新 API 能力 / 概览（总量·健康·存储·明细·指标）")
@RestController
@RequestMapping("/cluster")
public class ClusterController {

    @Autowired
    private ClusterService clusterService;

    @Autowired
    private MetricsService metricsService;

    @PostMapping("/create")
    @Operation(summary = "新增集群", description = "校验 kubeconfig 连通性后加密入库，并执行 K8s 侧开通（platform-system、租户 SA、模板 ClusterRole）")
    public ResponseData<K8sCluster> create(@RequestBody ClusterCreateRequest request) {
        return new ResponseData<>(clusterService.create(request));
    }

    @PostMapping("/list")
    @Operation(summary = "集群列表", description = "返回全部未删除集群（kubeconfig 不下发）")
    public ResponseData<List<K8sCluster>> list() {
        return new ResponseData<>(clusterService.list());
    }

    @PostMapping("/options")
    @Operation(summary = "集群下拉选项（窄投影）",
            description = "只返回 clusterId/clusterName/enabled，供命名空间管理等页面做集群选择框；"
                    + "鉴权 platform:allocation:list（与 /cluster/list 的 platform:cluster:manage 区分粒度）")
    public ResponseData<List<ClusterService.ClusterOption>> options() {
        return new ResponseData<>(clusterService.options());
    }

    @PostMapping("/get")
    @Operation(summary = "集群详情")
    public ResponseData<K8sCluster> get(@RequestBody ClusterKeyRequest request) {
        return new ResponseData<>(clusterService.get(request));
    }

    @PostMapping("/update")
    @Operation(summary = "更新集群", description = "仅名称/描述")
    public ResponseData<K8sCluster> update(@RequestBody ClusterUpdateRequest request) {
        return new ResponseData<>(clusterService.update(request));
    }

    @PostMapping("/toggleEnabled")
    @Operation(summary = "启用/禁用集群")
    public ResponseData<K8sCluster> toggleEnabled(@RequestBody ClusterToggleRequest request) {
        return new ResponseData<>(clusterService.toggleEnabled(request));
    }

    @PostMapping("/delete")
    @Operation(summary = "删除集群", description = "软删除；K8s 侧对象保留，彻底清理由运维处理")
    public ResponseData<Void> delete(@RequestBody ClusterKeyRequest request) {
        clusterService.delete(request);
        return new ResponseData<>();
    }

    @PostMapping("/provision")
    @Operation(summary = "重新开通", description = "幂等重跑 K8s 侧开通：创建失败重试、集群数据面重建后恢复")
    public ResponseData<K8sCluster> provision(@RequestBody ClusterKeyRequest request) {
        return new ResponseData<>(clusterService.provision(request));
    }

    @PostMapping("/capability/refresh")
    @Operation(summary = "刷新集群 API 能力", description = "运行时 discovery 探测（getApiGroups）并持久化到 k8s_cluster.capability；失败透出错误")
    public ResponseData<Void> refreshCapability(@RequestBody ClusterKeyRequest request) {
        clusterService.refreshCapabilityStrict(request.getClusterId());
        return new ResponseData<>();
    }

    @PostMapping("/capability/get")
    @Operation(summary = "读取集群 API 能力", description = "返回 k8s_cluster.capability 持久化的 group→versions 快照；未探测返回空对象")
    public ResponseData<Map<String, List<String>>> getCapability(@RequestBody ClusterKeyRequest request) {
        return new ResponseData<>(clusterService.getCapability(request.getClusterId()));
    }

    // ===== 集群概览（B4）：一页一 controller —— 概览页 ↔ 本类 =====

    @PostMapping("/overview")
    @Operation(summary = "集群概览快照",
            description = "总量 + 节点健康/异常 Pod + 存储 + 能力摘要（含 Pending PVC）。"
                    + "聚合来源不可用时聚合派生段为 null（前端「—」），基本信息与能力摘要不受影响")
    public ResponseData<ClusterOverviewDTO> overview(@RequestBody ClusterKeyRequest request) {
        return new ResponseData<>(clusterService.overview(request.getClusterId()));
    }

    @PostMapping("/resource-breakdown")
    @Operation(summary = "集群资源明细（per-namespace）",
            description = "「资源明细」tab 懒加载用；与 /overview 同源同一份聚合快照，两边数字一致。不可用返回 null")
    public ResponseData<List<NamespaceStatDTO>> resourceBreakdown(@RequestBody ClusterKeyRequest request) {
        return new ResponseData<>(clusterService.resourceBreakdown(request.getClusterId()));
    }

    // ===== 集群级监控指标（委托 MetricsService；按 cluster_name 过滤，多集群不串）=====

    @PostMapping("/metrics/cpu")
    @Operation(summary = "集群 CPU 用量曲线（核）", description = "跨全集群容器聚合，无 namespace 过滤")
    public ResponseData<MetricSeriesResponse> cpuMetrics(@RequestBody ClusterMetricsRequest request) {
        return new ResponseData<>(metricsService.clusterCpu(request));
    }

    @PostMapping("/metrics/memory")
    @Operation(summary = "集群内存用量曲线（字节）", description = "跨全集群容器聚合，无 namespace 过滤")
    public ResponseData<MetricSeriesResponse> memoryMetrics(@RequestBody ClusterMetricsRequest request) {
        return new ResponseData<>(metricsService.clusterMemory(request));
    }

    @PostMapping("/metrics/cpu/by-namespace")
    @Operation(summary = "各命名空间 CPU 用量（核）", description = "sum by(namespace)；一条序列 = 一个命名空间，图例 = 命名空间名")
    public ResponseData<MetricSeriesResponse> cpuByNamespaceMetrics(@RequestBody ClusterMetricsRequest request) {
        return new ResponseData<>(metricsService.clusterCpuByNamespace(request));
    }

    @PostMapping("/metrics/memory/by-namespace")
    @Operation(summary = "各命名空间内存用量（字节）", description = "sum by(namespace)；一条序列 = 一个命名空间，图例 = 命名空间名")
    public ResponseData<MetricSeriesResponse> memoryByNamespaceMetrics(@RequestBody ClusterMetricsRequest request) {
        return new ResponseData<>(metricsService.clusterMemoryByNamespace(request));
    }

    @PostMapping("/metrics/network")
    @Operation(summary = "集群网络 IO 曲线（字节/秒，RX/TX）", description = "跨全集群容器聚合（netns 在 pod 级），无 namespace 过滤")
    public ResponseData<MetricSeriesResponse> networkMetrics(@RequestBody ClusterMetricsRequest request) {
        return new ResponseData<>(metricsService.clusterNetwork(request));
    }

    @PostMapping("/metrics/disk")
    @Operation(summary = "集群磁盘 IO 曲线（字节/秒，读/写）", description = "跨全集群容器聚合，设备过滤沿用 device=~\"/dev/dm-.*\"")
    public ResponseData<MetricSeriesResponse> diskMetrics(@RequestBody ClusterMetricsRequest request) {
        return new ResponseData<>(metricsService.clusterDisk(request));
    }
}
