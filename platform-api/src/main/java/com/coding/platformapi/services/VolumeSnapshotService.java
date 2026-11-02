package com.coding.platformapi.services;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.k8s.dto.PersistentVolumeClaimDTO;
import com.coding.common.models.k8s.dto.StorageClassProvisionerDTO;
import com.coding.common.models.k8s.dto.VolumeSnapshotClassOptionDTO;
import com.coding.common.models.k8s.dto.VolumeSnapshotClassOptionsDTO;
import com.coding.common.models.k8s.dto.VolumeSnapshotDTO;
import com.coding.platformapi.k8s.K8sClient;
import com.coding.platformapi.k8s.VolumeSnapshotClassClient;
import com.coding.platformapi.models.VolumeSnapshotCapability;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

/**
 * 卷快照业务层：门控 + 创建校验（spec §5.3）。分层约定见 docs/development/backend-layering.md ——
 * 「哪个快照类能给哪个 PVC 用」是产品判断，放这里；k8s-server 只给集群事实。
 *
 * <p>快照类与存储类都走 UPSTREAM 窄投影：租户读不了完整的集群级对象，而创建快照正是租户的事。
 */
@Service
@RequiredArgsConstructor
public class VolumeSnapshotService {

    static final String UNSUPPORTED = "该 PVC 所在的存储类不支持快照";

    private final K8sClient k8s;
    private final VolumeSnapshotClassClient classClient;
    private final StorageClassService storageClasses;

    /**
     * 门控 + 表单候选。活探测：不读 {@code k8s_cluster.capability}，不读组件状态。
     * <p>⚠️ 探测调用失败就让它抛出去 —— 显示成「不支持」的话，集群一抖入口就消失了。
     */
    public VolumeSnapshotCapability capability(VolumeSnapshotDTO query) {
        VolumeSnapshotClassOptionsDTO options = classClient.options(query.getClusterId());
        List<VolumeSnapshotClassOptionDTO> classes = options.getClasses() == null ? List.of() : options.getClasses();
        if (!options.isCrdInstalled()) {
            return new VolumeSnapshotCapability(false, VolumeSnapshotCapability.NO_CRD, List.of(), List.of());
        }
        List<VolumeSnapshotCapability.StorageClassEntry> entries = storageClasses.provisioners(query.getClusterId())
                .stream()
                .map(sc -> new VolumeSnapshotCapability.StorageClassEntry(sc.getName(), sc.getProvisioner(),
                        classes.stream()
                                .filter(c -> sc.getProvisioner() != null && sc.getProvisioner().equals(c.getDriver()))
                                .map(VolumeSnapshotClassOptionDTO::getName)
                                .toList()))
                .toList();
        if (classes.isEmpty()) {
            return new VolumeSnapshotCapability(false, VolumeSnapshotCapability.NO_CLASS, classes, entries);
        }
        return new VolumeSnapshotCapability(true, null, classes, entries);
    }

    public List<VolumeSnapshotDTO> list(VolumeSnapshotDTO query) {
        return k8s.list(query);
    }

    public VolumeSnapshotDTO get(String name, String tenantId, String clusterId, String namespace) {
        return k8s.get(dto(name, tenantId, clusterId, namespace));
    }

    public String yaml(String name, String tenantId, String clusterId, String namespace) {
        return k8s.yaml(dto(name, tenantId, clusterId, namespace));
    }

    /**
     * 创建。三条校验在下发前做完：PVC 存在且已绑定、存储类有匹配的快照类、快照类能唯一确定。
     * <p>解析出的类名写进 spec，不依赖集群默认值 —— 详情页和审计都能看到实际用了哪个类。
     */
    public VolumeSnapshotDTO create(VolumeSnapshotDTO body) {
        String pvcName = body.getSourcePvcName();
        if (pvcName == null || pvcName.isBlank()) {
            throw invalid("没选来源 PVC。");
        }
        PersistentVolumeClaimDTO query = new PersistentVolumeClaimDTO();
        query.setName(pvcName);
        query.setTenantId(body.getTenantId());
        query.setClusterId(body.getClusterId());
        query.setNamespace(body.getNamespace());
        PersistentVolumeClaimDTO pvc = k8s.get(query);
        if (pvc == null) {
            throw invalid("PVC 不存在：" + pvcName);
        }
        if (!"Bound".equals(pvc.getPhase())) {
            throw invalid("PVC " + pvcName + " 未绑定（当前 " + pvc.getPhase() + "），还没有数据可快照。");
        }
        body.setVolumeSnapshotClassName(resolveClassFor(pvc, body.getVolumeSnapshotClassName(), body.getClusterId()));
        return k8s.create(body);
    }

    /**
     * 确定给这个 PVC 用哪个快照类：指定了就校验 driver；没指定就取该驱动的默认类，再不行取唯一匹配，
     * 都不成立就要求用户选。存储类读不到 provisioner（没写存储类 / 静态盘）一律按不支持处理。
     */
    String resolveClassFor(PersistentVolumeClaimDTO pvc, String requestedClass, String clusterId) {
        String scName = pvc.getStorageClassName();
        if (scName == null || scName.isBlank()) {
            throw invalid(UNSUPPORTED + "（这个 PVC 没写存储类）。");
        }
        String provisioner = storageClasses.provisioners(clusterId).stream()
                .filter(sc -> scName.equals(sc.getName()))
                .map(StorageClassProvisionerDTO::getProvisioner)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
        if (provisioner == null) {
            throw invalid(UNSUPPORTED + "（存储类 " + scName + " 不存在或没有 provisioner）。");
        }
        VolumeSnapshotClassOptionsDTO options = classClient.options(clusterId);
        if (!options.isCrdInstalled()) {
            throw invalid("集群没有安装卷快照 CRD，不能创建快照。");
        }
        List<VolumeSnapshotClassOptionDTO> all = options.getClasses() == null ? List.of() : options.getClasses();
        if (requestedClass != null && !requestedClass.isBlank()) {
            VolumeSnapshotClassOptionDTO chosen = all.stream()
                    .filter(c -> requestedClass.equals(c.getName()))
                    .findFirst()
                    .orElseThrow(() -> invalid("快照类不存在：" + requestedClass));
            if (!provisioner.equals(chosen.getDriver())) {
                throw invalid(UNSUPPORTED + "（快照类 " + requestedClass + " 的驱动是 " + chosen.getDriver()
                        + "，存储类 " + scName + " 的是 " + provisioner + "）。");
            }
            return requestedClass;
        }
        List<VolumeSnapshotClassOptionDTO> matching = all.stream()
                .filter(c -> provisioner.equals(c.getDriver()))
                .toList();
        if (matching.isEmpty()) {
            throw invalid(UNSUPPORTED + "（没有驱动为 " + provisioner + " 的快照类）。");
        }
        List<VolumeSnapshotClassOptionDTO> defaults = matching.stream()
                .filter(c -> Boolean.TRUE.equals(c.getIsDefault()))
                .toList();
        if (defaults.size() == 1) {
            return defaults.get(0).getName();
        }
        if (matching.size() == 1) {
            return matching.get(0).getName();
        }
        throw invalid("存储类 " + scName + " 有多个可用的快照类，请选择快照类。");
    }

    public void delete(String name, String tenantId, String clusterId, String namespace) {
        k8s.delete(dto(name, tenantId, clusterId, namespace));
    }

    private static CloudPlatformException invalid(String msg) {
        return new CloudPlatformException(EnumResponseType.VOLUME_SNAPSHOT_INVALID, msg);
    }

    private static VolumeSnapshotDTO dto(String name, String tenantId, String clusterId, String namespace) {
        VolumeSnapshotDTO d = new VolumeSnapshotDTO();
        d.setName(name);
        d.setTenantId(tenantId);
        d.setClusterId(clusterId);
        d.setNamespace(namespace);
        return d;
    }
}
