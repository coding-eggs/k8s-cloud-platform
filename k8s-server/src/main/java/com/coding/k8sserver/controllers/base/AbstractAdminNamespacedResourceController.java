package com.coding.k8sserver.controllers.base;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.k8s.BaseResources;
import com.coding.common.models.k8s.ResourceType;
import com.coding.common.models.system.ResponseData;
import com.coding.k8score.factory.KubernetesOperationsFactory;
import com.coding.k8score.operations.NamespacedOperations;
import com.coding.k8sserver.components.ResourceAccessResolver;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * 平台级（admin）命名空间域资源 controller 统一基类：6 个标准端点 + 集群边界（平台已注册该集群）校验。
 * <p>
 * 与 {@link AbstractNamespacedResourceController} 的区别只在身份层：本基类**不做分配表校验**，
 * namespace 是透传参数（调用方为平台管理员，非租户），故一律 admin client。
 * 用于命名空间自身的约束资源（ResourceQuota / LimitRange）等平台管理流程 —— 这些对象由平台在
 * 「尚未分配给任何租户」的命名空间上创建，走租户边界会被 ResourceAccessResolver 直接拒。
 * <p>
 * 刻意不与双模基类合并：两者的安全语义不同（一个必须校验分配表、一个必须不校验），
 * 合并会把「跳过校验」变成可配置项 —— 那是更危险的设计。
 * 仅 PLATFORM:admin 可访问（SecurityFilterChain 对 /admin/** 统一要求）。
 */
public abstract class AbstractAdminNamespacedResourceController<T extends BaseResources> {

    protected final KubernetesOperationsFactory operationsFactory;

    protected final ResourceAccessResolver accessResolver;

    protected AbstractAdminNamespacedResourceController(KubernetesOperationsFactory operationsFactory,
                                                        ResourceAccessResolver accessResolver) {
        this.operationsFactory = operationsFactory;
        this.accessResolver = accessResolver;
    }

    /**本 controller 管理的资源类型 */
    protected abstract ResourceType resourceType();

    @PostMapping("/list")
    @Operation(summary = "列出命名空间内资源（body 传 clusterId/namespace/labelSelector）")
    public ResponseData<List<T>> list(@RequestBody T body) {
        String clusterId = body.getClusterId();
        String namespace = requireNamespace(body.getNamespace());
        accessResolver.assertClusterAccess(clusterId);
        List<T> items = ops(clusterId).list(namespace, body.getLabelSelector(), body.getFieldSelector());
        items.forEach(item -> stamp(item, clusterId, namespace));
        return new ResponseData<>(items);
    }

    @GetMapping("/{name}")
    @Operation(summary = "查询单个资源")
    public ResponseData<T> get(@PathVariable String name,
                               @RequestParam String clusterId,
                               @RequestParam String namespace) {
        String ns = requireNamespace(namespace);
        accessResolver.assertClusterAccess(clusterId);
        T item = ops(clusterId).get(ns, name);
        stamp(item, clusterId, ns);
        return new ResponseData<>(item);
    }

    @GetMapping("/{name}/yaml")
    @Operation(summary = "查询资源 YAML（只读展示）")
    public ResponseData<String> yaml(@PathVariable String name,
                                     @RequestParam String clusterId,
                                     @RequestParam String namespace) {
        String ns = requireNamespace(namespace);
        accessResolver.assertClusterAccess(clusterId);
        return new ResponseData<>(ops(clusterId).yaml(ns, name));
    }

    @PostMapping
    @Operation(summary = "创建资源（namespace 取 body）")
    public ResponseData<T> create(@RequestParam String clusterId,
                                  @RequestBody T body) {
        String ns = requireNamespace(body.getNamespace());
        accessResolver.assertClusterAccess(clusterId);
        T item = ops(clusterId).create(body);
        stamp(item, clusterId, ns);
        return new ResponseData<>(item);
    }

    @PutMapping("/{name}")
    @Operation(summary = "更新资源（namespace 取 body）")
    public ResponseData<T> update(@PathVariable String name,
                                  @RequestParam String clusterId,
                                  @RequestBody T body) {
        String ns = requireNamespace(body.getNamespace());
        accessResolver.assertClusterAccess(clusterId);
        T item = ops(clusterId).update(body);
        stamp(item, clusterId, ns);
        return new ResponseData<>(item);
    }

    @DeleteMapping("/{name}")
    @Operation(summary = "删除资源")
    public ResponseData<Void> delete(@PathVariable String name,
                                     @RequestParam String clusterId,
                                     @RequestParam String namespace) {
        String ns = requireNamespace(namespace);
        accessResolver.assertClusterAccess(clusterId);
        ops(clusterId).delete(ns, name);
        return new ResponseData<>();
    }

    /**平台流程：一律 admin client（无租户身份可用） */
    protected NamespacedOperations<T> ops(String clusterId) {
        return operationsFactory.getAdminNamespacedOperation(resourceType(), clusterId);
    }

    /**命名空间是本基类的必带定位参数（区别于租户基类：那里由分配表隐含约束） */
    private String requireNamespace(String namespace) {
        if (!StringUtils.hasText(namespace)) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "缺少集群/命名空间参数");
        }
        return namespace;
    }

    /**回填请求上下文，使列表/查询拿到的对象可原样发回后续 update/delete；tenantId 刻意不回填 */
    private void stamp(T item, String clusterId, String namespace) {
        if (item != null) {
            item.setClusterId(clusterId);
            item.setNamespace(namespace);
        }
    }
}
