package com.coding.platformapi.controllers;

import com.coding.common.models.k8s.dto.NamespaceDTO;
import com.coding.common.models.system.ResponseData;
import com.coding.platformapi.models.ClusterKeyRequest;
import com.coding.platformapi.models.NamespaceKeyRequest;
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
}
