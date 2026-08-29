package com.coding.platformapi.controllers;

import com.coding.common.models.system.ResponseData;
import com.coding.platformapi.models.RbacTemplateVO;
import com.coding.platformapi.models.TemplateCreateRequest;
import com.coding.platformapi.models.TemplateKeyRequest;
import com.coding.platformapi.models.TemplateUpdateRequest;
import com.coding.platformapi.services.RbacTemplateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "RBAC 模板管理")
@RestController
@RequestMapping("/rbacTemplate")
public class RbacTemplateController {

    @Autowired
    private RbacTemplateService templateService;

    @PostMapping("/create")
    @Operation(summary = "创建模板", description = "校验命名与规则后入库，并同步 ClusterRole（tn-tpl-<name>）到各启用集群")
    public ResponseData<RbacTemplateVO> create(@RequestBody TemplateCreateRequest request) {
        return new ResponseData<>(templateService.create(request));
    }

    @PostMapping("/list")
    @Operation(summary = "模板列表", description = "rules 已解析为结构化规则")
    public ResponseData<List<RbacTemplateVO>> list() {
        return new ResponseData<>(templateService.list());
    }

    @PostMapping("/get")
    @Operation(summary = "模板详情")
    public ResponseData<RbacTemplateVO> get(@RequestBody TemplateKeyRequest request) {
        return new ResponseData<>(templateService.get(request));
    }

    @PostMapping("/update")
    @Operation(summary = "更新模板", description = "描述/规则；内置模板不可改；规则变更即时同步各集群 ClusterRole")
    public ResponseData<RbacTemplateVO> update(@RequestBody TemplateUpdateRequest request) {
        return new ResponseData<>(templateService.update(request));
    }

    @PostMapping("/delete")
    @Operation(summary = "删除模板", description = "内置保护 + 被分配引用时禁删；软删后清理各集群 ClusterRole")
    public ResponseData<Void> delete(@RequestBody TemplateKeyRequest request) {
        templateService.delete(request);
        return new ResponseData<>();
    }
}
