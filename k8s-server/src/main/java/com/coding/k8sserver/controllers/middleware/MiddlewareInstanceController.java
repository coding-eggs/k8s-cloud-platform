package com.coding.k8sserver.controllers.middleware;

import com.coding.common.models.middleware.MiddlewareCreateRequestDTO;
import com.coding.common.models.middleware.MiddlewareDeleteRequestDTO;
import com.coding.common.models.middleware.MiddlewareScaleRequestDTO;
import com.coding.common.models.middleware.MiddlewareDetailRequestDTO;
import com.coding.common.models.middleware.MiddlewareInstanceLiveDTO;
import com.coding.common.models.middleware.MiddlewarePasswordRequestDTO;
import com.coding.common.models.middleware.MiddlewareStatusRequestDTO;
import com.coding.common.models.middleware.MiddlewareStatusResultDTO;
import com.coding.common.models.middleware.MiddlewareUserCreateRequestDTO;
import com.coding.common.models.middleware.MiddlewareUserCredentialDTO;
import com.coding.common.models.middleware.MiddlewareUserDeleteRequestDTO;
import com.coding.common.models.middleware.MiddlewareUserListRequestDTO;
import com.coding.common.models.middleware.MiddlewareUserPasswordRequestDTO;
import com.coding.common.models.middleware.MiddlewareUserView;
import com.coding.common.models.system.ResponseData;
import com.coding.k8sserver.components.AccessBoundary;
import com.coding.k8sserver.components.AccessBoundaryAware;
import com.coding.k8sserver.middleware.MiddlewareInstanceService;
import com.coding.k8sserver.middleware.MiddlewareUserService;
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
    private final MiddlewareUserService users;

    public MiddlewareInstanceController(MiddlewareInstanceService service, MiddlewareUserService users) {
        this.service = service;
        this.users = users;
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

    @PostMapping("/{code}/instances/password")
    @Operation(summary = "改实例的鉴权口令（Milvus）",
            description = "旧口令由 k8s-server 从鉴权 Secret 读；先调 Milvus 的 update_password，成功后写回 Secret")
    public ResponseData<Boolean> changePassword(@PathVariable("code") String code,
                                                @RequestBody MiddlewarePasswordRequestDTO request) {
        request.setCode(code);
        service.changePassword(request);
        return new ResponseData<>(true);
    }

    @PostMapping("/{code}/instances/users")
    @Operation(summary = "实例的账号列表（平台建的内置账号 + 平台建的普通账号）")
    public ResponseData<List<MiddlewareUserView>> listUsers(@PathVariable("code") String code,
                                                            @RequestBody MiddlewareUserListRequestDTO request) {
        return new ResponseData<>(users.list(code, request));
    }

    @PostMapping("/{code}/instances/users/create")
    @Operation(summary = "新增实例账号（返回一次性明文口令）")
    public ResponseData<MiddlewareUserCredentialDTO> createUser(@PathVariable("code") String code,
                                                                @RequestBody MiddlewareUserCreateRequestDTO request) {
        return new ResponseData<>(users.create(code, request));
    }

    @PostMapping("/{code}/instances/users/password")
    @Operation(summary = "改实例账号口令（返回一次性明文口令）")
    public ResponseData<MiddlewareUserCredentialDTO> changeUserPassword(
            @PathVariable("code") String code, @RequestBody MiddlewareUserPasswordRequestDTO request) {
        return new ResponseData<>(users.changePassword(code, request));
    }

    @PostMapping("/{code}/instances/users/delete")
    @Operation(summary = "删除实例账号（内置账号拒绝）")
    public ResponseData<Boolean> deleteUser(@PathVariable("code") String code,
                                            @RequestBody MiddlewareUserDeleteRequestDTO request) {
        users.drop(code, request);
        return new ResponseData<>(true);
    }

    @PostMapping("/{code}/detail")
    @Operation(summary = "实例实时对象（CR + YAML + Pod/PVC/Service，详情页用）")
    public ResponseData<MiddlewareInstanceLiveDTO> detail(@PathVariable("code") String code,
                                                          @RequestBody MiddlewareDetailRequestDTO request) {
        request.setCode(code);
        return new ResponseData<>(service.detail(request));
    }

    @PostMapping("/{code}/status")
    @Operation(summary = "批量读实例实时状态")
    public ResponseData<List<MiddlewareStatusResultDTO>> status(@PathVariable("code") String code,
                                                                @RequestBody MiddlewareStatusRequestDTO request) {
        request.setCode(code);
        return new ResponseData<>(service.status(request));
    }
}
