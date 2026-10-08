package com.coding.platformapi.controllers;

import com.coding.common.models.k8s.dto.StorageClassDTO;
import com.coding.common.models.system.ResponseData;
import com.coding.platformapi.services.StorageClassService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 资源管理 - StorageClass（集群级，只读，<b>仅平台管理员</b>）。
 * <p>
 * 供工作负载编辑器下拉选择 storageClassName；存储类本身由集群/基础设施管理，本层不暴露写操作。
 * <p>
 * <b>访问面</b>：StorageClass 无命名空间维度，无法按租户收窄，故三个端点的权限码
 * （V2026_10_07_3）由 {@code tenant:storageclass:*} 收为 {@code platform:cluster:manage}。
 * 业务与 k8s-server 调用在 {@link StorageClassService}（分层约定见 docs/development/backend-layering.md）。
 */
@Tag(name = "资源管理-StorageClass", description = "集群级存储类（只读，平台管理员）")
@RestController
@RequestMapping("/storageclasses")
@RequiredArgsConstructor
public class StorageClassController {

    private final StorageClassService storageClassService;

    @PostMapping("/list")
    @Operation(summary = "列出 StorageClass")
    public ResponseData<List<StorageClassDTO>> list(@RequestBody StorageClassDTO body) {
        //body 携带 tenantId/clusterId（集群级，无 namespace），与 k8s-server wire 同构，原样透传
        return new ResponseData<>(storageClassService.list(body));
    }

    @GetMapping("/{name}")
    @Operation(summary = "查询 StorageClass")
    public ResponseData<StorageClassDTO> get(@PathVariable String name,
                                             @RequestParam String tenantId,
                                             @RequestParam String clusterId) {
        return new ResponseData<>(storageClassService.get(name, tenantId, clusterId));
    }

    @GetMapping("/{name}/yaml")
    @Operation(summary = "查询 StorageClass YAML（只读展示）")
    public ResponseData<String> yaml(@PathVariable String name,
                                     @RequestParam String tenantId,
                                     @RequestParam String clusterId) {
        return new ResponseData<>(storageClassService.yaml(name, tenantId, clusterId));
    }

}
