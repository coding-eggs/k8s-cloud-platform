package com.coding.platformapi.controllers;

import com.coding.common.models.k8s.dto.PersistentVolumeClaimDTO;
import com.coding.common.models.system.ResponseData;
import com.coding.platformapi.models.PvcResizeRequest;
import com.coding.platformapi.services.PvcService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 资源管理 - PVC（标签随时可改；**容量只能往大改**，那是独立的 {@link PvcService#resize}（不跟标签混在一个入口））。本层只做 HTTP 绑定与 {@link ResponseData}
 * 包装，业务与 k8s-server 调用在 {@link PvcService}（分层约定见 docs/development/backend-layering.md）。
 */
@Tag(name = "资源管理-PVC", description = "命名空间内 PVC")
@RestController
@RequestMapping("/pvcs")
@RequiredArgsConstructor
public class PvcController {

    private final PvcService pvcService;

    @PostMapping("/list")
    @Operation(summary = "列出 PVC")
    public ResponseData<List<PersistentVolumeClaimDTO>> list(@RequestBody PersistentVolumeClaimDTO body) {
        return new ResponseData<>(pvcService.list(body));
    }

    @GetMapping("/{name}")
    @Operation(summary = "查询 PVC")
    public ResponseData<PersistentVolumeClaimDTO> get(@PathVariable String name,
                                                      @RequestParam String tenantId,
                                                      @RequestParam String clusterId,
                                                      @RequestParam String namespace) {
        return new ResponseData<>(pvcService.get(name, tenantId, clusterId, namespace));
    }

    @GetMapping("/{name}/yaml")
    @Operation(summary = "查询 PVC YAML（只读展示）")
    public ResponseData<String> yaml(@PathVariable String name,
                                     @RequestParam String tenantId,
                                     @RequestParam String clusterId,
                                     @RequestParam String namespace) {
        return new ResponseData<>(pvcService.yaml(name, tenantId, clusterId, namespace));
    }

    @PostMapping
    @Operation(summary = "创建 PVC")
    public ResponseData<PersistentVolumeClaimDTO> create(@RequestBody PersistentVolumeClaimDTO body) {
        return new ResponseData<>(pvcService.create(body));
    }

    @PutMapping("/{name}")
    @Operation(summary = "更新 PVC（**仅同步标签**）",
            description = "容量改不了 —— 带上变化的 storage 会明确报错并指向 /{name}/resize。"
                    + "其余字段取线上现值回写（未建模字段不丢）。")
    public ResponseData<PersistentVolumeClaimDTO> update(@PathVariable String name,
                                                         @RequestBody PersistentVolumeClaimDTO body) {
        body.setName(name);
        return new ResponseData<>(pvcService.update(body));
    }

    @PostMapping("/{name}/resize")
    @Operation(summary = "扩容 PVC",
            description = "在线扩容量：**只能往大改**（相等也拒 —— 那等于没改），"
                    + "且存储类得开了 allowVolumeExpansion。扩容是异步的，"
                    + "中间态在 status.conditions（Resizing / FileSystemResizePending），不在 phase 上。"
                    + "⚠️ 与 PUT /{name} 分开成两个端点：那个同步标签，这个是动存储后端、不可逆的操作 —— "
                    + "合成一个入口会让权限与审计都说不清（与工作负载改镜像/暂停单独成端点同一个理由）。")
    public ResponseData<PersistentVolumeClaimDTO> resize(@PathVariable String name,
                                                         @RequestBody PvcResizeRequest body) {
        return new ResponseData<>(pvcService.resize(name, body));
    }

    @DeleteMapping("/{name}")
    @Operation(summary = "删除 PVC")
    public ResponseData<Void> delete(@PathVariable String name,
                                     @RequestParam String tenantId,
                                     @RequestParam String clusterId,
                                     @RequestParam String namespace) {
        pvcService.delete(name, tenantId, clusterId, namespace);
        return new ResponseData<>();
    }

}
