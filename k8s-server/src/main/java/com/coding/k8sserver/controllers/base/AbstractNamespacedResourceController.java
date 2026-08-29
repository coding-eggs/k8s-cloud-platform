package com.coding.k8sserver.controllers.base;

import com.coding.common.models.k8s.BaseResources;
import com.coding.common.models.k8s.ResourceType;
import com.coding.common.models.system.ResponseData;
import com.coding.k8score.factory.KubernetesOperationsFactory;
import com.coding.k8score.operations.NamespacedOperations;
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
 * 与双模访问流程（身份解析 → 分配表边界校验 → client 选择）只在此写一次。
 * <p>
 * 具体 controller 只提供 URL 前缀（{@code @RequestMapping}）与 {@link #resourceType()}；
 * 端点形态不一致的需求（如 Pod 日志流式）在子类中以新增方法扩展，create/update 可按产品语义覆写拒绝。
 * <p>
 * 双模访问：租户 token（tenantId 取 JWT claim，参数可省）/ admin token（显式 tenantId 代操作），
 * 两模统一经 {@link ResourceAccessResolver} 校验分配表边界；可选参数永不扩大权限。
 * list 走 POST /list + body（clusterId/namespace/labelSelector），其余端点保持 query 参数形态。
 */
public abstract class AbstractNamespacedResourceController<T extends BaseResources> {

    protected final KubernetesOperationsFactory operationsFactory;

    protected final ResourceAccessResolver accessResolver;

    protected AbstractNamespacedResourceController(KubernetesOperationsFactory operationsFactory,
                                                   ResourceAccessResolver accessResolver) {
        this.operationsFactory = operationsFactory;
        this.accessResolver = accessResolver;
    }

    /**本 controller 管理的资源类型（决定 operations 实现与 client 能力校验） */
    protected abstract ResourceType resourceType();

    @PostMapping("/list")
    @Operation(summary = "列出命名空间内资源（body 传 clusterId/namespace/labelSelector；租户模式 tenantId 以 JWT claim 为准）")
    public ResponseData<List<T>> list(@RequestBody T body) {
        String clusterId = body.getClusterId();
        String namespace = body.getNamespace();
        AccessContext ctx = accessResolver.resolveNamespacedAccess(body.getTenantId(), clusterId, namespace);
        List<T> items = ops(ctx, clusterId).list(namespace, body.getLabelSelector());
        items.forEach(item -> stamp(item, clusterId, namespace, ctx.tenantId()));
        return new ResponseData<>(items);
    }

    @GetMapping("/{name}")
    @Operation(summary = "查询单个资源")
    public ResponseData<T> get(@PathVariable String name,
                               @RequestParam(required = false) String tenantId,
                               @RequestParam String clusterId,
                               @RequestParam String namespace) {
        AccessContext ctx = accessResolver.resolveNamespacedAccess(tenantId, clusterId, namespace);
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
        AccessContext ctx = accessResolver.resolveNamespacedAccess(tenantId, clusterId, namespace);
        return new ResponseData<>(ops(ctx, clusterId).yaml(namespace, name));
    }

    @PostMapping
    @Operation(summary = "创建资源（namespace 取 body）")
    public ResponseData<T> create(@RequestParam(required = false) String tenantId,
                                  @RequestParam String clusterId,
                                  @RequestBody T body) {
        AccessContext ctx = accessResolver.resolveNamespacedAccess(tenantId, clusterId, body.getNamespace());
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
        AccessContext ctx = accessResolver.resolveNamespacedAccess(tenantId, clusterId, body.getNamespace());
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
        AccessContext ctx = accessResolver.resolveNamespacedAccess(tenantId, clusterId, namespace);
        ops(ctx, clusterId).delete(namespace, name);
        return new ResponseData<>();
    }

    /**租户模式→tenant client（最小权限）；admin 模式→admin client */
    protected NamespacedOperations<T> ops(AccessContext ctx, String clusterId) {
        return ctx.adminMode()
                ? operationsFactory.getAdminNamespacedOperation(resourceType(), clusterId, null)
                : operationsFactory.getNamespacedOperation(resourceType(), clusterId, ctx.tenantId(), null);
    }

    /**D5 回填：请求上下文回写 item，使列表/查询拿到的对象可原样发回后续 update/delete */
    private void stamp(T item, String clusterId, String namespace, String tenantId) {
        if (item != null) {
            item.setClusterId(clusterId);
            item.setNamespace(namespace);
            item.setTenantId(tenantId);
        }
    }

}
