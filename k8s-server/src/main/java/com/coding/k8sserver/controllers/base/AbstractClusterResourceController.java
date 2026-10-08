package com.coding.k8sserver.controllers.base;

import com.coding.common.models.k8s.BaseResources;
import com.coding.common.models.k8s.ResourceType;
import com.coding.common.models.system.ResponseData;
import com.coding.k8score.factory.KubernetesOperationsFactory;
import com.coding.k8score.operations.ClusterOperations;
import com.coding.k8sserver.components.AccessBoundary;
import com.coding.k8sserver.components.AccessBoundaryAware;
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
 * 不透传租户上下文，一律 admin client —— <b>本基类没有租户维度</b>，返回的是该集群的全量对象。
 *
 * <h2>访问控制：默认声明 {@link AccessBoundary#PLATFORM}（务必读完再新增子类）</h2>
 * 本基类声明 {@code PLATFORM}，由 {@code BoundaryAuthorizationManager} 在请求期要求 token 持有
 * {@code PLATFORM_SCOPE}（= {@code data.platformRoles} 非空，即持有任意 PLATFORM-scope 角色）——
 * <b>与挂载路径无关</b>，也因此不再硬编码 "admin" 这个角色名。
 *
 * <h2>历史坑（本批修掉，勿回归）</h2>
 * 本基类的闸门曾经取决于 {@code @RequestMapping} 挂在哪个前缀下：挂 {@code /admin/**} 的由
 * SecurityFilterChain 要求 {@code PLATFORM:admin}；挂 {@code /resources/**} 的
 * <b>只要求 {@code authenticated()}，任何已登录用户（含租户成员）直连即可读写</b>。
 * 于是 {@code NodeController}(/nodes)、{@code PersistentVolumeController}、
 * {@code StorageClassController}、{@code ClusterRoleController} 这些集群级端点全都裸奔，
 * 而它们的 javadoc 却写着"PLATFORM:admin，平台管理员专属"。现在边界是 controller 自己的声明。
 *
 * <p><b>新增子类的唯一要求</b>：确认它确实只给平台侧用。若某个集群级资源必须对租户可见
 * （如 PersistentVolume —— 跨租户收窄在上游 platform-api 的 {@code PersistentVolumeService} 里按
 * {@code claimRef.namespace} 做），<b>覆写 {@link #accessBoundary()} 为 {@link AccessBoundary#UPSTREAM}</b>
 * 并在类注释写明理由：让例外可被一眼搜出，而不是靠"挂在某个前缀下"隐式豁免。
 */
public abstract class AbstractClusterResourceController<T extends BaseResources> implements AccessBoundaryAware {

    protected final KubernetesOperationsFactory operationsFactory;

    protected final ResourceAccessResolver accessResolver;

    protected AbstractClusterResourceController(KubernetesOperationsFactory operationsFactory,
                                                ResourceAccessResolver accessResolver) {
        this.operationsFactory = operationsFactory;
        this.accessResolver = accessResolver;
    }

    /**本 controller 管理的集群级资源类型 */
    protected abstract ResourceType resourceType();

    /**集群级资源默认只给平台侧；租户可见的集群级资源（当前仅 PV）覆写为 {@link AccessBoundary#UPSTREAM}。 */
    @Override
    public AccessBoundary accessBoundary() {
        return AccessBoundary.PLATFORM;
    }

    @PostMapping("/list")
    @Operation(summary = "列出集群级资源（body 传 clusterId/labelSelector）")
    public ResponseData<List<T>> list(@RequestBody T body) {
        String clusterId = body.getClusterId();
        accessResolver.assertClusterAccess(clusterId);
        List<T> items = ops(clusterId).list(body.getLabelSelector(), body.getFieldSelector());
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
        return operationsFactory.getClusterOperation(resourceType(), clusterId);
    }

    /**D5 回填：请求上下文回写 item，使返回对象可原样发回后续 update/delete */
    private void stamp(T item, String clusterId) {
        if (item != null) {
            item.setClusterId(clusterId);
        }
    }

}
