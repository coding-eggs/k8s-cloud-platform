package com.coding.k8sserver.controllers.cluster;

import com.coding.common.models.k8s.ResourceType;
import com.coding.common.models.k8s.dto.VolumeSnapshotClassDTO;
import com.coding.common.models.k8s.dto.VolumeSnapshotClassOptionDTO;
import com.coding.common.models.k8s.dto.VolumeSnapshotClassOptionsDTO;
import com.coding.common.models.system.ResponseData;
import com.coding.k8score.factory.KubernetesOperationsFactory;
import com.coding.k8score.operations.ClusterOperations;
import com.coding.k8sserver.components.AccessBoundary;
import com.coding.k8sserver.components.AccessBoundaryAware;
import com.coding.k8sserver.components.ResourceAccessResolver;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 集群域 - VolumeSnapshotClass 窄投影（{@code POST /volumesnapshotclasses/options}）。
 * 与 {@link StorageClassProvisionerController} 完全同形，是又一个 UPSTREAM 例外。
 *
 * <h2>为什么独立成一个类</h2>
 * {@link VolumeSnapshotClassController} 声明 PLATFORM，而本端点租户必须能到（创建快照要选快照类、
 * 恢复要校验 driver）。边界按 handler 所属类读，所以单开一个类声明 UPSTREAM。
 *
 * <h2>已知并显式接受的残留</h2>
 * 持任意已认证 token 直连 k8s-server 即可读到该集群的快照类名、driver、删除策略、是否默认。
 * 接受理由同 StorageClass 那条：{@code parameters} 不回；数据不随租户变化，没有越权看到别人对象的语义。
 *
 * <h2>CRD 缺失</h2>
 * 只把 404 翻译成 {@code crdInstalled=false}；其它失败原样抛出，免得「探测失败」被当成「没装」。
 */
@Tag(name = "集群域-VolumeSnapshotClass", description = "快照类窄投影（UPSTREAM 边界）")
@RestController
@RequestMapping("/volumesnapshotclasses")
public class VolumeSnapshotClassOptionsController implements AccessBoundaryAware {

    private final KubernetesOperationsFactory operationsFactory;
    private final ResourceAccessResolver accessResolver;

    public VolumeSnapshotClassOptionsController(KubernetesOperationsFactory operationsFactory,
                                                ResourceAccessResolver accessResolver) {
        this.operationsFactory = operationsFactory;
        this.accessResolver = accessResolver;
    }

    /** 上游收窄：platform-api 侧 {@code /volumesnapshots/capability} 挂 tenant:volumesnapshot:read */
    @Override
    public AccessBoundary accessBoundary() {
        return AccessBoundary.UPSTREAM;
    }

    @PostMapping("/options")
    @Operation(summary = "快照类窄投影",
            description = "回 crdInstalled + 每个快照类的 name / driver / deletionPolicy / isDefault（不回 parameters）。")
    public ResponseData<VolumeSnapshotClassOptionsDTO> options(@RequestBody VolumeSnapshotClassDTO body) {
        accessResolver.assertClusterAccess(body.getClusterId());
        ClusterOperations<VolumeSnapshotClassDTO> ops =
                operationsFactory.getClusterOperation(ResourceType.VOLUME_SNAPSHOT_CLASS, body.getClusterId());
        VolumeSnapshotClassOptionsDTO out = new VolumeSnapshotClassOptionsDTO();
        List<VolumeSnapshotClassDTO> classes;
        try {
            classes = ops.list(null, null);
        } catch (KubernetesClientException e) {
            if (e.getCode() == 404) {
                out.setCrdInstalled(false);
                return new ResponseData<>(out);
            }
            throw e;
        }
        out.setCrdInstalled(true);
        out.setClasses(classes.stream().map(c -> {
            VolumeSnapshotClassOptionDTO view = new VolumeSnapshotClassOptionDTO();
            view.setName(c.getName());
            view.setDriver(c.getDriver());
            view.setDeletionPolicy(c.getDeletionPolicy());
            view.setIsDefault(c.getIsDefault());
            return view;
        }).toList());
        return new ResponseData<>(out);
    }
}
