package com.coding.platformapi.controllers;

import com.coding.common.models.k8s.dto.ListenerSetDTO;
import com.coding.common.models.system.ResponseData;
import com.coding.platformapi.services.ListenerSetService;
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
 * 资源管理 - ListenerSet（gateway.networking.k8s.io/v1 CRD，租户域，<b>只读</b>）。
 * <p>只有 list / list-all / get / yaml：平台维护的 {@code platform-ingress} 由应用发布按端口分配表整份重算，
 * 用户自建的平台只展示。归属补全在 {@link ListenerSetService}。
 * <p>{@code /list-all} 是平台侧全局视图（独立码 {@code platform:listenerset:list-all}），同 ConfigMap。
 */
@Tag(name = "资源管理-ListenerSet", description = "命名空间内 ListenerSet（租户域，只读）")
@RestController
@RequestMapping("/listener-sets")
@RequiredArgsConstructor
public class ListenerSetController {

    private final ListenerSetService listenerSetService;

    @PostMapping("/list")
    @Operation(summary = "列出 ListenerSet")
    public ResponseData<List<ListenerSetDTO>> list(@RequestBody ListenerSetDTO body) {
        return new ResponseData<>(listenerSetService.list(body));
    }

    @PostMapping("/list-all")
    @Operation(summary = "跨全部命名空间列出 ListenerSet（平台侧）")
    public ResponseData<List<ListenerSetDTO>> listAll(@RequestBody ListenerSetDTO body) {
        return new ResponseData<>(listenerSetService.listAll(body));
    }

    @GetMapping("/{name}")
    @Operation(summary = "查询 ListenerSet")
    public ResponseData<ListenerSetDTO> get(@PathVariable String name,
                                            @RequestParam String tenantId,
                                            @RequestParam String clusterId,
                                            @RequestParam String namespace) {
        return new ResponseData<>(listenerSetService.get(name, tenantId, clusterId, namespace));
    }

    @GetMapping("/{name}/yaml")
    @Operation(summary = "查询 ListenerSet YAML（只读）")
    public ResponseData<String> yaml(@PathVariable String name,
                                     @RequestParam String tenantId,
                                     @RequestParam String clusterId,
                                     @RequestParam String namespace) {
        return new ResponseData<>(listenerSetService.yaml(name, tenantId, clusterId, namespace));
    }
}
