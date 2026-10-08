package com.coding.k8sserver.controllers.base;

import com.coding.common.models.k8s.BaseResources;
import com.coding.common.models.k8s.ResourceType;
import com.coding.common.models.system.ResponseData;
import com.coding.k8score.factory.KubernetesOperationsFactory;
import com.coding.k8score.operations.NamespacedOperations;
import com.coding.k8sserver.components.AccessBoundary;
import com.coding.k8sserver.components.AccessBoundaryAware;
import com.coding.k8sserver.components.ResourceAccessResolver;
import com.coding.k8sserver.components.ResourceAccessResolver.AccessContext;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * 命名空间域资源 controller 统一基类：6 个标准端点（list/get/yaml/create/update/delete）
 * 与访问流程（身份解析 → 边界校验 → client 选择）只在此写一次。
 * <p>
 * 具体 controller 只提供 URL 前缀（{@code @RequestMapping}）与 {@link #resourceType()}；
 * 端点形态不一致的需求（如 Pod 日志流式）在子类中以新增方法扩展，create/update 可按产品语义覆写拒绝。
 * <p>
 * list 走 POST /list + body（clusterId/namespace/labelSelector），其余端点保持 query 参数形态。
 *
 * <h2>访问边界（{@link AccessBoundary}）</h2>
 * 本基类默认声明 {@link AccessBoundary#TENANT}：租户 token 的 tenantId 以 JWT claim 为唯一来源，
 * admin token（token 无 {@code tenantInfo}）必须显式传 tenantId 代操作；两者都要过分配表三元组
 * (tenantId, clusterId, namespace) 校验，并据此选 tenant client（最小权限，K8s RBAC 第二道闸）或 admin client。
 * <p>
 * 命名空间自身的约束资源覆写为 {@link AccessBoundary#PLATFORM}：
 * 它们由平台在「尚未分配给任何租户」的命名空间上创建，走租户边界会被分配表直接拒
 * （见 {@code ResourceQuotaController} / {@code LimitRangeController}）。
 * 该取值由 {@code BoundaryAuthorizationManager} 在请求期要求 {@code PLATFORM_SCOPE}，
 * 因此"免分配表校验"的路径不可能被租户触达 —— 不需要再靠挂载位置（前缀）来保证。
 */
public abstract class AbstractNamespacedResourceController<T extends BaseResources> implements AccessBoundaryAware {

    protected final KubernetesOperationsFactory operationsFactory;

    protected final ResourceAccessResolver accessResolver;

    protected AbstractNamespacedResourceController(KubernetesOperationsFactory operationsFactory,
                                                   ResourceAccessResolver accessResolver) {
        this.operationsFactory = operationsFactory;
        this.accessResolver = accessResolver;
    }

    /**本 controller 管理的资源类型（决定 operations 实现与 client 能力校验） */
    protected abstract ResourceType resourceType();

    /**本资源适用的访问边界；默认租户边界（分配表三元组校验）。命名空间自身的约束资源覆写为 PLATFORM。 */
    @Override
    public AccessBoundary accessBoundary() {
        return AccessBoundary.TENANT;
    }

    /**
     * 跨全部命名空间列举（平台侧）。与 {@link #list} 的分工：
     * <ul>
     *   <li>{@code /list}：namespace 必填。租户调用方走分配表三元组，平台调用方代操作需显式 tenantId。</li>
     *   <li>{@code /list-all}：<b>不接受租户调用方</b>（{@code resolvePlatformNamespacedAccess} 里的平台侧身份
     *       校验会拒），也<b>不校验分配表</b> —— 它按定义就要覆盖"不属于任何租户的命名空间"（kube-system 等）。</li>
     * </ul>
     * 端点对<b>所有</b>命名空间级资源都存在，闸门只有两个，且都不在这里：
     * "本资源支不支持"由 {@link NamespacedOperations#listAll} 的实现决定（未实现 → OPERATION_NOT_SUPPORTED），
     * "要不要对外暴露"由 platform-api 的权限表决定（没配码就没有可达路径）。
     * <p>返回项各自带自己的 namespace（converter 从对象 metadata 取），<b>不</b>统一回填。
     */
    @PostMapping("/list-all")
    @Operation(summary = "跨全部命名空间列举（平台侧）")
    public ResponseData<List<T>> listAll(@RequestBody T body) {
        String clusterId = body.getClusterId();
        //平台侧身份校验在这里：租户 token 不持 PLATFORM_SCOPE → 直接拒（fail-closed）
        AccessContext ctx = accessResolver.resolvePlatformNamespacedAccess(clusterId);
        //adminMode=true ⇒ ops() 取的是 admin client（租户 SA 无集群范围 list 权限，只能用 admin）
        NamespacedOperations<T> adminOps = ops(ctx, clusterId);
        List<T> items = adminOps.listAll(body.getLabelSelector(), body.getFieldSelector());
        items.forEach(item -> stampCluster(item, clusterId));
        return new ResponseData<>(items);
    }

    @PostMapping("/list")
    @Operation(summary = "列出命名空间内资源（body 传 clusterId/namespace/labelSelector；租户模式 tenantId 以 JWT claim 为准）")
    public ResponseData<List<T>> list(@RequestBody T body) {
        String clusterId = body.getClusterId();
        String namespace = body.getNamespace();
        AccessContext ctx = resolve(body.getTenantId(), clusterId, namespace);
        List<T> items = ops(ctx, clusterId).list(namespace, body.getLabelSelector(), body.getFieldSelector());
        items.forEach(item -> stamp(item, clusterId, namespace, ctx.tenantId()));
        return new ResponseData<>(items);
    }

    @GetMapping("/{name}")
    @Operation(summary = "查询单个资源")
    public ResponseData<T> get(@PathVariable String name,
                               @RequestParam(required = false) String tenantId,
                               @RequestParam String clusterId,
                               @RequestParam String namespace) {
        AccessContext ctx = resolve(tenantId, clusterId, namespace);
        T item = ops(ctx, clusterId).get(namespace, name);
        stamp(item, clusterId, namespace, ctx.tenantId());
        return new ResponseData<>(item);
    }

    @GetMapping("/{name}/yaml")
    @Operation(summary = "查询资源 YAML（只读展示）")
    public ResponseData<String> yaml(@PathVariable String name,
                                     @RequestParam(required = false) String tenantId,
                                     @RequestParam String clusterId,
                                     @RequestParam String namespace) {
        AccessContext ctx = resolve(tenantId, clusterId, namespace);
        return new ResponseData<>(ops(ctx, clusterId).yaml(namespace, name));
    }

    @PostMapping
    @Operation(summary = "创建资源（namespace 取 body）")
    public ResponseData<T> create(@RequestParam(required = false) String tenantId,
                                  @RequestParam String clusterId,
                                  @RequestBody T body) {
        AccessContext ctx = resolve(tenantId, clusterId, body.getNamespace());
        T item = ops(ctx, clusterId).create(body);
        stamp(item, clusterId, body.getNamespace(), ctx.tenantId());
        return new ResponseData<>(item);
    }

    @PutMapping("/{name}")
    @Operation(summary = "更新资源（namespace 取 body）")
    public ResponseData<T> update(@PathVariable String name,
                                  @RequestParam(required = false) String tenantId,
                                  @RequestParam String clusterId,
                                  @RequestBody T body) {
        AccessContext ctx = resolve(tenantId, clusterId, body.getNamespace());
        T item = ops(ctx, clusterId).update(body);
        stamp(item, clusterId, body.getNamespace(), ctx.tenantId());
        return new ResponseData<>(item);
    }

    @DeleteMapping("/{name}")
    @Operation(summary = "删除资源")
    public ResponseData<Void> delete(@PathVariable String name,
                                     @RequestParam(required = false) String tenantId,
                                     @RequestParam String clusterId,
                                     @RequestParam String namespace) {
        AccessContext ctx = resolve(tenantId, clusterId, namespace);
        ops(ctx, clusterId).delete(namespace, name);
        return new ResponseData<>();
    }

    /**按 {@link #accessBoundary()} 选择解析入口：TENANT → 分配表边界；PLATFORM → 仅集群可达性 */
    protected AccessContext resolve(String explicitTenantId, String clusterId, String namespace) {
        return accessBoundary() == AccessBoundary.PLATFORM
                ? accessResolver.resolvePlatformNamespacedAccess(clusterId)
                : accessResolver.resolveNamespacedAccess(explicitTenantId, clusterId, namespace);
    }

    /**租户模式→tenant client（最小权限）；admin 模式（含 PLATFORM 边界）→admin client */
    protected NamespacedOperations<T> ops(AccessContext ctx, String clusterId) {
        return ctx.adminMode()
                ? operationsFactory.getAdminNamespacedOperation(resourceType(), clusterId)
                : operationsFactory.getNamespacedOperation(resourceType(), clusterId, ctx.tenantId());
    }

    /**D5 回填：请求上下文回写 item，使列表/查询拿到的对象可原样发回后续 update/delete。
     *  PLATFORM 边界下 tenantId 为 null（无租户身份），等价于不回填。 */
    private void stamp(T item, String clusterId, String namespace, String tenantId) {
        if (item != null) {
            item.setClusterId(clusterId);
            item.setNamespace(namespace);
            item.setTenantId(tenantId);
        }
    }

    /**{@link #listAll} 专用回填：只回写 clusterId，<b>不碰</b> namespace —— 跨命名空间时每项 namespace 各不相同，
     *  必须保留 converter 从 metadata 取到的值（用 {@link #stamp} 会把它们全写成同一个）。 */
    private void stampCluster(T item, String clusterId) {
        if (item != null) {
            item.setClusterId(clusterId);
        }
    }
}
