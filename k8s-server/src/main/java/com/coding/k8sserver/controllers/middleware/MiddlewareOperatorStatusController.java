package com.coding.k8sserver.controllers.middleware;

import com.coding.common.models.middleware.MiddlewareOperatorStatusDTO;
import com.coding.common.models.middleware.MiddlewareOperatorStatusRequestDTO;
import com.coding.common.models.system.ResponseData;
import com.coding.k8sserver.components.AccessBoundary;
import com.coding.k8sserver.components.AccessBoundaryAware;
import com.coding.k8sserver.middleware.MiddlewareOperatorCatalog;
import com.coding.k8sserver.middleware.MiddlewareOperatorInstaller;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * Operator 状态查询（**UPSTREAM 边界**：集群级只读事实，租户与管理员都要看）。
 *
 * <p>为什么不是 PLATFORM：租户的商店页要显示"该中间件可用/需要先装 Operator"，api 侧原样透传
 * 租户 token；若这里要求 PLATFORM_SCOPE，租户打开商店就 403（2026-10-22 复审实锤）。
 * 这属于"集群级但租户可见"的读——与 PersistentVolume 同一档；收窄在 platform-api 的
 * {@code tenant:middleware:read}。安装（写）仍在 {@link MiddlewareOperatorInstallController}，
 * 那是 PLATFORM。
 */
@Tag(name = "中间件-Operator", description = "Operator 状态判定（租户可见的只读事实）")
@RestController
@RequestMapping("/middleware/operators")
public class MiddlewareOperatorStatusController implements AccessBoundaryAware {

    private final MiddlewareOperatorInstaller installer;
    private final MiddlewareOperatorCatalog catalog;

    public MiddlewareOperatorStatusController(MiddlewareOperatorInstaller installer,
                                              MiddlewareOperatorCatalog catalog) {
        this.installer = installer;
        this.catalog = catalog;
    }

    @Override
    public AccessBoundary accessBoundary() {
        return AccessBoundary.UPSTREAM;
    }

    @PostMapping("/status")
    @Operation(summary = "批量判定 Operator 状态（codes 为空 = 全部）")
    public ResponseData<List<MiddlewareOperatorStatusDTO>> status(
            @RequestBody MiddlewareOperatorStatusRequestDTO request) {
        List<String> codes = request.getCodes() == null || request.getCodes().isEmpty()
                ? new ArrayList<>(catalog.codes())
                : request.getCodes();
        List<MiddlewareOperatorStatusDTO> out = new ArrayList<>();
        for (String code : codes) {
            out.add(installer.status(request.getClusterId(), code));
        }
        return new ResponseData<>(out);
    }
}
