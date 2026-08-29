package com.coding.platformapi.controllers;

import com.coding.common.models.system.ResponseData;
import com.coding.data.models.k8s.K8sCluster;
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

@Tag(name = "集群管理", description = "集群新增 / 列表 / 详情 / 更新 / 启停 / 删除 / 重新开通")
@RestController
@RequestMapping("/cluster")
public class ClusterController {

    @Autowired
    private ClusterService clusterService;

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
}
