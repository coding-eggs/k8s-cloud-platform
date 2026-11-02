package com.coding.platformapi.controllers;

import com.coding.common.models.k8s.dto.VolumeSnapshotClassDTO;
import com.coding.common.models.k8s.dto.VolumeSnapshotContentDTO;
import com.coding.common.models.system.ResponseData;
import com.coding.platformapi.services.VolumeSnapshotClusterService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 平台视图 - 快照类与快照内容（一页两个 tab，所以一个 controller 管两个前缀）。
 * <p>集群级、只读，权限码都是 {@code platform:cluster:manage}（与 StorageClass 同理：无法按租户收窄）。
 * 不提供写入：快照类由组件建，孤儿快照内容的清理会动存储后端，本批不做。
 */
@Tag(name = "平台-卷快照", description = "快照类与快照内容（平台管理员只读）")
@RestController
@RequiredArgsConstructor
public class VolumeSnapshotClusterController {

    private final VolumeSnapshotClusterService service;

    @PostMapping("/volumesnapshotclasses/list")
    @Operation(summary = "列出快照类")
    public ResponseData<List<VolumeSnapshotClassDTO>> listClasses(@RequestBody VolumeSnapshotClassDTO body) {
        return new ResponseData<>(service.listClasses(body));
    }

    @GetMapping("/volumesnapshotclasses/{name}")
    @Operation(summary = "查询快照类")
    public ResponseData<VolumeSnapshotClassDTO> getClass(@PathVariable String name,
                                                         @RequestParam(required = false) String tenantId,
                                                         @RequestParam String clusterId) {
        return new ResponseData<>(service.getClass(name, tenantId, clusterId));
    }

    @PostMapping("/volumesnapshotcontents/list")
    @Operation(summary = "列出快照内容", description = "orphan=true 表示指向的快照已不存在；对照失败时为 null。")
    public ResponseData<List<VolumeSnapshotContentDTO>> listContents(@RequestBody VolumeSnapshotContentDTO body) {
        return new ResponseData<>(service.listContents(body));
    }

    @GetMapping("/volumesnapshotcontents/{name}")
    @Operation(summary = "查询快照内容")
    public ResponseData<VolumeSnapshotContentDTO> getContent(@PathVariable String name,
                                                             @RequestParam(required = false) String tenantId,
                                                             @RequestParam String clusterId) {
        return new ResponseData<>(service.getContent(name, tenantId, clusterId));
    }

}
