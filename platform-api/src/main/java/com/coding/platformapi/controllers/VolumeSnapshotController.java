package com.coding.platformapi.controllers;

import com.coding.common.models.k8s.dto.VolumeSnapshotDTO;
import com.coding.common.models.system.ResponseData;
import com.coding.common.audit.AuditAction;
import com.coding.common.audit.Auditable;
import com.coding.platformapi.models.VolumeSnapshotCapability;
import com.coding.platformapi.services.VolumeSnapshotService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 资源管理 - 卷快照（S4.3）。没有更新：快照的 spec 创建后不可变。
 * 从快照恢复不在这里，走 {@code POST /pvcs} 带 dataSourceRef（{@link PvcController}）。
 * 本层只做 HTTP 绑定与 {@link ResponseData} 包装，门控与校验在 {@link VolumeSnapshotService}。
 */
@Tag(name = "资源管理-卷快照", description = "命名空间内卷快照")
@RestController
@RequestMapping("/volumesnapshots")
@RequiredArgsConstructor
public class VolumeSnapshotController {

    private final VolumeSnapshotService volumeSnapshotService;

    @PostMapping("/capability")
    @Operation(summary = "卷快照能力探测",
            description = "活探测：CRD 是否已装、有哪些快照类、每个存储类能用哪些快照类。探测失败直接报错，不当成不支持。")
    public ResponseData<VolumeSnapshotCapability> capability(@RequestBody VolumeSnapshotDTO body) {
        return new ResponseData<>(volumeSnapshotService.capability(body));
    }

    @PostMapping("/list")
    @Operation(summary = "列出卷快照")
    public ResponseData<List<VolumeSnapshotDTO>> list(@RequestBody VolumeSnapshotDTO body) {
        return new ResponseData<>(volumeSnapshotService.list(body));
    }

    @GetMapping("/{name}")
    @Operation(summary = "查询卷快照")
    public ResponseData<VolumeSnapshotDTO> get(@PathVariable String name,
                                               @RequestParam String tenantId,
                                               @RequestParam String clusterId,
                                               @RequestParam String namespace) {
        return new ResponseData<>(volumeSnapshotService.get(name, tenantId, clusterId, namespace));
    }

    @GetMapping("/{name}/yaml")
    @Operation(summary = "查询卷快照 YAML（只读展示）")
    public ResponseData<String> yaml(@PathVariable String name,
                                     @RequestParam String tenantId,
                                     @RequestParam String clusterId,
                                     @RequestParam String namespace) {
        return new ResponseData<>(volumeSnapshotService.yaml(name, tenantId, clusterId, namespace));
    }

    @PostMapping
    @Operation(summary = "创建卷快照",
            description = "来源 PVC 必须已绑定；没传快照类时取该驱动的默认类，否则取唯一匹配，再不行要求选择。")
    @Auditable(action = AuditAction.VOLUME_SNAPSHOT_CREATE)
    public ResponseData<VolumeSnapshotDTO> create(@RequestBody VolumeSnapshotDTO body) {
        return new ResponseData<>(volumeSnapshotService.create(body));
    }

    @DeleteMapping("/{name}")
    @Operation(summary = "删除卷快照",
            description = "快照类 deletionPolicy=Delete 时会连带删除存储后端的快照数据。")
    @Auditable(action = AuditAction.VOLUME_SNAPSHOT_DELETE)
    public ResponseData<Void> delete(@PathVariable String name,
                                     @RequestParam String tenantId,
                                     @RequestParam String clusterId,
                                     @RequestParam String namespace) {
        volumeSnapshotService.delete(name, tenantId, clusterId, namespace);
        return new ResponseData<>();
    }

}
