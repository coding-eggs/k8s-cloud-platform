package com.coding.platformapi.controllers;

import com.coding.common.models.k8s.dto.PersistentVolumeDTO;
import com.coding.common.models.system.ResponseData;
import com.coding.platformapi.services.PersistentVolumeService;
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
 * 资源管理 - PersistentVolume（集群级，只读）。
 * <p>
 * 供 PVC 详情查看绑定的 PV；PV 本身由集群/存储供应方管理，本层不暴露写操作。
 * <b>租户收窄</b>（按 {@code claimRef.namespace} 过滤到本租户已分配命名空间）在
 * {@link PersistentVolumeService}，本层只做 HTTP 绑定与 {@link ResponseData} 包装。
 */
@Tag(name = "资源管理-PersistentVolume", description = "集群级持久卷（只读）")
@RestController
@RequestMapping("/persistentvolumes")
@RequiredArgsConstructor
public class PersistentVolumeController {

    private final PersistentVolumeService persistentVolumeService;

    @PostMapping("/list")
    @Operation(summary = "列出 PV", description = "租户帽下只返回绑定到本租户已分配命名空间的 PV；平台管理员返回全量")
    public ResponseData<List<PersistentVolumeDTO>> list(@RequestBody PersistentVolumeDTO body) {
        //body 携带 tenantId/clusterId（集群级，无 namespace），与 k8s-server wire 同构，原样透传
        return new ResponseData<>(persistentVolumeService.list(body));
    }

    @GetMapping("/{name}")
    @Operation(summary = "查询 PV", description = "非本租户绑定的 PV 返回 not-found（不泄露存在性）")
    public ResponseData<PersistentVolumeDTO> get(@PathVariable String name,
                                                 @RequestParam String tenantId,
                                                 @RequestParam String clusterId) {
        return new ResponseData<>(persistentVolumeService.get(name, tenantId, clusterId));
    }

    @GetMapping("/{name}/yaml")
    @Operation(summary = "查询 PV YAML（只读展示）")
    public ResponseData<String> yaml(@PathVariable String name,
                                     @RequestParam String tenantId,
                                     @RequestParam String clusterId) {
        return new ResponseData<>(persistentVolumeService.yaml(name, tenantId, clusterId));
    }

}
