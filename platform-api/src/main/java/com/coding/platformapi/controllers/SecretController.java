package com.coding.platformapi.controllers;

import com.coding.common.models.k8s.dto.SecretDTO;
import com.coding.common.models.system.ResponseData;
import com.coding.platformapi.services.SecretService;
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
 * 资源管理 - Secret。本层只做 HTTP 绑定与 {@link ResponseData} 包装，业务与 k8s-server 调用在
 * {@link SecretService}（分层约定见 docs/development/backend-layering.md）。
 */
@Tag(name = "资源管理-Secret", description = "命名空间内 Secret")
@RestController
@RequestMapping("/secrets")
@RequiredArgsConstructor
public class SecretController {

    private final SecretService secretService;

    @PostMapping("/list")
    @Operation(summary = "列出 Secret")
    public ResponseData<List<SecretDTO>> list(@RequestBody SecretDTO body) {
        return new ResponseData<>(secretService.list(body));
    }

    @GetMapping("/{name}")
    @Operation(summary = "查询 Secret")
    public ResponseData<SecretDTO> get(@PathVariable String name,
                                       @RequestParam String tenantId,
                                       @RequestParam String clusterId,
                                       @RequestParam String namespace) {
        return new ResponseData<>(secretService.get(name, tenantId, clusterId, namespace));
    }

    @GetMapping("/{name}/yaml")
    @Operation(summary = "查询 Secret YAML（只读展示）")
    public ResponseData<String> yaml(@PathVariable String name,
                                     @RequestParam String tenantId,
                                     @RequestParam String clusterId,
                                     @RequestParam String namespace) {
        return new ResponseData<>(secretService.yaml(name, tenantId, clusterId, namespace));
    }

    @PostMapping
    @Operation(summary = "创建 Secret")
    public ResponseData<SecretDTO> create(@RequestBody SecretDTO body) {
        return new ResponseData<>(secretService.create(body));
    }

    @PutMapping("/{name}")
    @Operation(summary = "更新 Secret")
    public ResponseData<SecretDTO> update(@PathVariable String name, @RequestBody SecretDTO body) {
        body.setName(name);
        return new ResponseData<>(secretService.update(body));
    }

    @DeleteMapping("/{name}")
    @Operation(summary = "删除 Secret")
    public ResponseData<Void> delete(@PathVariable String name,
                                     @RequestParam String tenantId,
                                     @RequestParam String clusterId,
                                     @RequestParam String namespace) {
        secretService.delete(name, tenantId, clusterId, namespace);
        return new ResponseData<>();
    }

}
