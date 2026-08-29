package com.coding.k8sserver.controllers.base;

import com.coding.common.models.k8s.BaseResources;
import com.coding.common.models.k8s.ResourceType;
import com.coding.common.models.system.ResponseData;
import com.coding.k8score.factory.KubernetesOperationsFactory;
import com.coding.k8score.operations.ClusterOperations;
import com.coding.k8sserver.components.ResourceAccessResolver;
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
 * 集群域资源 controller 统一基类：6 个标准端点 + 集群边界（平台已注册该集群）校验。
 * <p>
 * 仅 PLATFORM:admin 可访问（SecurityFilterChain 对 /admin/** 统一要求），不透传租户上下文，
 * 一律 admin client；具体 controller 只提供 URL 前缀与 {@link #resourceType()}。
 */
public abstract class AbstractClusterResourceController<T extends BaseResources> {

    protected final KubernetesOperationsFactory operationsFactory;

    protected final ResourceAccessResolver accessResolver;

    protected AbstractClusterResourceController(KubernetesOperationsFactory operationsFactory,
                                                ResourceAccessResolver accessResolver) {
        this.operationsFactory = operationsFactory;
        this.accessResolver = accessResolver;
    }

    /**本 controller 管理的集群级资源类型 */
    protected abstract ResourceType resourceType();

    @PostMapping("/list")
    @Operation(summary = "列出集群级资源（body 传 clusterId/labelSelector）")
    public ResponseData<List<T>> list(@RequestBody T body) {
        String clusterId = body.getClusterId();
        accessResolver.assertClusterAccess(clusterId);
        List<T> items = ops(clusterId).list(body.getLabelSelector());
        items.forEach(item -> stamp(item, clusterId));
        return new ResponseData<>(items);
    }

    @GetMapping("/{name}")
    @Operation(summary = "查询单个集群级资源")
    public ResponseData<T> get(@PathVariable String name,
                               @RequestParam String clusterId) {
        accessResolver.assertClusterAccess(clusterId);
        T item = ops(clusterId).get(name);
        stamp(item, clusterId);
        return new ResponseData<>(item);
    }

    @GetMapping("/{name}/yaml")
    @Operation(summary = "查询集群级资源 YAML（只读展示）")
    public ResponseData<String> yaml(@PathVariable String name,
                                     @RequestParam String clusterId) {
        accessResolver.assertClusterAccess(clusterId);
        return new ResponseData<>(ops(clusterId).yaml(name));
    }

    @PostMapping
    @Operation(summary = "创建集群级资源")
    public ResponseData<T> create(@RequestParam String clusterId,
                                  @RequestBody T body) {
        accessResolver.assertClusterAccess(clusterId);
        T item = ops(clusterId).create(body);
        stamp(item, clusterId);
        return new ResponseData<>(item);
    }

    @PutMapping("/{name}")
    @Operation(summary = "更新集群级资源")
    public ResponseData<T> update(@PathVariable String name,
                                  @RequestParam String clusterId,
                                  @RequestBody T body) {
        accessResolver.assertClusterAccess(clusterId);
        T item = ops(clusterId).update(body);
        stamp(item, clusterId);
        return new ResponseData<>(item);
    }

    @DeleteMapping("/{name}")
    @Operation(summary = "删除集群级资源")
    public ResponseData<Void> delete(@PathVariable String name,
                                     @RequestParam String clusterId) {
        accessResolver.assertClusterAccess(clusterId);
        ops(clusterId).delete(name);
        return new ResponseData<>();
    }

    protected ClusterOperations<T> ops(String clusterId) {
        return operationsFactory.getClusterOperation(resourceType(), clusterId, null);
    }

    /**D5 回填：请求上下文回写 item，使返回对象可原样发回后续 update/delete */
    private void stamp(T item, String clusterId) {
        if (item != null) {
            item.setClusterId(clusterId);
        }
    }

}
