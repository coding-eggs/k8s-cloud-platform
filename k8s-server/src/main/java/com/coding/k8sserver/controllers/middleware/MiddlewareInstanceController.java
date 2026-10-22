package com.coding.k8sserver.controllers.middleware;

import com.coding.common.models.middleware.MiddlewareCreateRequestDTO;
import com.coding.common.models.middleware.MiddlewareDeleteRequestDTO;
import com.coding.common.models.middleware.MiddlewareScaleRequestDTO;
import com.coding.common.models.middleware.MiddlewareStatusRequestDTO;
import com.coding.common.models.middleware.MiddlewareStatusResultDTO;
import com.coding.common.models.system.ResponseData;
import com.coding.k8sserver.components.AccessBoundary;
import com.coding.k8sserver.components.AccessBoundaryAware;
import com.coding.k8sserver.middleware.MiddlewareInstanceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 中间件实例（**TENANT 边界**）：租户与管理员共用，身份与分配表校验在
 * {@code ResourceAccessResolver}（见 {@link MiddlewareInstanceService}）。
 */
@Tag(name = "中间件-实例", description = "创建/扩容/删除/状态")
@RestController
@RequestMapping("/middleware")
public class MiddlewareInstanceController implements AccessBoundaryAware {

    private final MiddlewareInstanceService service;

    public MiddlewareInstanceController(MiddlewareInstanceService service) {
        this.service = service;
    }

    @Override
    public AccessBoundary accessBoundary() {
        return AccessBoundary.TENANT;
    }

    @PostMapping("/{code}/instances")
    @Operation(summary = "创建实例（写凭据 Secret + 建 CR）")
    public ResponseData<Boolean> create(@PathVariable("code") String code,
                                        @RequestBody MiddlewareCreateRequestDTO request) {
        request.setCode(code);
        service.create(request);
        return new ResponseData<>(true);
    }

    @PostMapping("/{code}/instances/scale")
    @Operation(summary = "扩容（节点数/资源）")
    public ResponseData<Boolean> scale(@PathVariable("code") String code,
                                       @RequestBody MiddlewareScaleRequestDTO request) {
        request.setCode(code);
        service.scale(request);
        return new ResponseData<>(true);
    }

    @PostMapping("/{code}/instances/delete")
    @Operation(summary = "删除实例（keepPvc=false 时连同 PVC 一起删）")
    public ResponseData<Boolean> delete(@PathVariable("code") String code,
                                        @RequestBody MiddlewareDeleteRequestDTO request) {
        request.setCode(code);
        service.delete(request);
        return new ResponseData<>(true);
    }

    @PostMapping("/{code}/status")
    @Operation(summary = "批量读实例实时状态")
    public ResponseData<List<MiddlewareStatusResultDTO>> status(@PathVariable("code") String code,
                                                                @RequestBody MiddlewareStatusRequestDTO request) {
        request.setCode(code);
        return new ResponseData<>(service.status(request));
    }
}
