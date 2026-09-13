package com.coding.platformapi.controllers.resource;

import com.coding.common.models.k8s.dto.StorageClassDTO;
import com.coding.common.models.system.ResponseData;
import com.coding.platformapi.k8s.K8sResourceClient;
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
 * 资源管理 - StorageClass（集群级，只读）。
 * <p>
 * 供工作负载编辑器下拉选择 storageClassName；存储类本身由集群/基础设施管理，本层不暴露写操作。
 * 方法级透传：platform-api → k8s-server /resources/storageclasses（K8sResourceClient DTO 驱动）。
 */
@Tag(name = "资源管理-StorageClass", description = "集群级存储类（只读）")
@RestController
@RequestMapping("/resource/storageclasses")
@RequiredArgsConstructor
public class StorageClassController {

    private final K8sResourceClient k8s;

    @PostMapping("/list")
    @Operation(summary = "列出 StorageClass")
    public ResponseData<List<StorageClassDTO>> list(@RequestBody StorageClassDTO body) {
        //body 携带 tenantId/clusterId（集群级，无 namespace），与 k8s-server wire 同构，原样透传
        return new ResponseData<>(k8s.list(body));
    }

    @GetMapping("/{name}")
    @Operation(summary = "查询 StorageClass")
    public ResponseData<StorageClassDTO> get(@PathVariable String name,
                                             @RequestParam String tenantId,
                                             @RequestParam String clusterId) {
        return new ResponseData<>(k8s.get(dto(name, tenantId, clusterId)));
    }

    @GetMapping("/{name}/yaml")
    @Operation(summary = "查询 StorageClass YAML（只读展示）")
    public ResponseData<String> yaml(@PathVariable String name,
                                     @RequestParam String tenantId,
                                     @RequestParam String clusterId) {
        return new ResponseData<>(k8s.yaml(dto(name, tenantId, clusterId)));
    }

    /**查询 DTO：apiPath 内置于 DTO（集群级，无 namespace） */
    private StorageClassDTO dto(String name, String tenantId, String clusterId) {
        StorageClassDTO d = new StorageClassDTO();
        d.setName(name);
        d.setTenantId(tenantId);
        d.setClusterId(clusterId);
        return d;
    }

}
