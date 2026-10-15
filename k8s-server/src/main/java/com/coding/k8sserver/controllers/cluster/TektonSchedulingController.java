package com.coding.k8sserver.controllers.cluster;

import com.coding.common.models.pipeline.TektonSchedulingDTO;
import com.coding.common.models.system.ResponseData;
import com.coding.k8sserver.components.AccessBoundary;
import com.coding.k8sserver.components.AccessBoundaryAware;
import com.coding.k8sserver.components.ResourceAccessResolver;
import com.coding.k8sserver.pipeline.TektonSchedulingProbe;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 集群域 - Tekton 调度配置窄投影（{@code POST /tekton/coschedule}）：只回 {@code coschedule} 的值，
 * 以及「它允不允许一个 TaskRun 挂两块 PVC 类 workspace」。
 *
 * <p>消费方是 CI 的能力探针 —— 构建缓存能不能开就取决于它
 * （清单见 {@code docs/development/cluster-prerequisites.md}）。
 *
 * <h2>为什么独立成一个 controller 类</h2>
 * {@code BoundaryAuthorizationManager} 读的是<b>handler 所属类</b>声明的 {@link AccessBoundary}，
 * 而本端点**租户必须能到**（流水线页面是租户在用）。故单开一个类声明 {@code UPSTREAM}，
 * 与 {@link StorageClassProvisionerController}、{@link NodeAddressController} 完全同形；
 * 用 POST 而不是 GET，是为了不落在 {@code AbstractClusterResourceController} 的
 * {@code GET /{name}} 模板上（那条是 PLATFORM 边界）。
 *
 * <h2>边界：UPSTREAM —— 与 PV / 节点 IP / StorageClass 那三条同类</h2>
 * 本层不做边界判定，收窄在上游 platform-api（{@code POST /pipeline/capability} 挂
 * {@code tenant:pipeline:read}）。<b>已知并显式接受的残留</b>：持任意已认证 token 直连
 * k8s-server 即可读到这个集群的 Tekton 调度配置。之所以接受：①这份数据就一个枚举值，
 * 不含任何租户/命名空间的对象；②它不随租户变化，没有"越权看到别人对象"的语义。
 * 闭合它需要"只接受 platform-api 调用"的内部凭证，与那三条一并留给下一步。
 */
@Tag(name = "集群域-Tekton", description = "Tekton 调度配置窄投影（CI 缓存前提探针，UPSTREAM 边界）")
@RestController
@RequestMapping("/tekton")
public class TektonSchedulingController implements AccessBoundaryAware {

    private final TektonSchedulingProbe probe;
    private final ResourceAccessResolver accessResolver;

    public TektonSchedulingController(TektonSchedulingProbe probe, ResourceAccessResolver accessResolver) {
        this.probe = probe;
        this.accessResolver = accessResolver;
    }

    /** 上游收窄：platform-api 侧按权限码判"谁能问 CI 能不能开缓存"，本层只保证集群已登记 */
    @Override
    public AccessBoundary accessBoundary() {
        return AccessBoundary.UPSTREAM;
    }

    @PostMapping("/coschedule")
    @Operation(summary = "Tekton 调度配置窄投影",
            description = "只回 coschedule 的值与「允不允许一个 TaskRun 挂两块 PVC workspace」；"
                    + "构建缓存能不能开取决于它。读取用 admin 凭据，结果进程内缓存 60s")
    public ResponseData<TektonSchedulingDTO> coschedule(@RequestParam String clusterId) {
        accessResolver.assertClusterAccess(clusterId);
        return new ResponseData<>(probe.of(clusterId));
    }
}
