package com.coding.k8score.operations.core;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.k8s.dto.PersistentVolumeClaimDTO;
import com.coding.common.models.k8s.dto.StorageClassDTO;
import com.coding.k8score.converter.CommonConverter;
import com.coding.k8score.operations.NamespacedOperations;
import com.coding.k8score.operations.ServerSideApply;
import io.fabric8.kubernetes.api.model.ListOptions;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaim;
import io.fabric8.kubernetes.api.model.storage.StorageClass;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.utils.Serialization;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * corev1 PersistentVolumeClaim 操作。
 * 规格创建后不可变：update 仅同步标签，保留原 spec。
 */
@Slf4j
@RequiredArgsConstructor
public class CoreV1PersistentVolumeClaimOperations implements NamespacedOperations<PersistentVolumeClaimDTO> {

    public final static String apiVersion = "v1";

    private final KubernetesClient client;

    private final CommonConverter<PersistentVolumeClaim, PersistentVolumeClaimDTO> converter;

    @Override
    public String apiVersion() {
        return apiVersion;
    }

    @Override
    public List<PersistentVolumeClaimDTO> list(String namespace, String labelSelector, String fieldSelector) {
        String ns = StringUtils.hasText(namespace) ? namespace : "";
        ListOptions options = new ListOptions();
        if (StringUtils.hasText(labelSelector)) {
            options.setLabelSelector(labelSelector);
        }
        if (StringUtils.hasText(fieldSelector)) {
            options.setFieldSelector(fieldSelector);
        }
        List<PersistentVolumeClaim> items = client.persistentVolumeClaims()
                .inNamespace(ns).list(options).getItems();
        return enrich(items);
    }

    /**
     * 跨全部命名空间列举（平台侧；本实例必须是 admin client，见 {@link NamespacedOperations#listAll}）。
     * <p>返回的每个 item 自带其所在 namespace —— converter 从 metadata 取，本方法<b>不做</b>统一回填。
     */
    @Override
    public List<PersistentVolumeClaimDTO> listAll(String labelSelector, String fieldSelector) {
        ListOptions options = new ListOptions();
        if (StringUtils.hasText(labelSelector)) {
            options.setLabelSelector(labelSelector);
        }
        if (StringUtils.hasText(fieldSelector)) {
            options.setFieldSelector(fieldSelector);
        }
        List<PersistentVolumeClaim> items = client.persistentVolumeClaims()
                .inAnyNamespace().list(options).getItems();
        return enrich(items);
    }

    @Override
    public PersistentVolumeClaimDTO get(String namespace, String name) {
        if (!StringUtils.hasText(namespace) || !StringUtils.hasText(name)) {
            throw new CloudPlatformException(EnumResponseType.SEARCH_K8S_REQUIRE_NAME_AND_NAMESPACE);
        }
        PersistentVolumeClaim pvc = client.persistentVolumeClaims()
                .inNamespace(namespace)
                .withName(name)
                .get();
        return enrich(List.of(pvc)).stream().findFirst().orElse(null);
    }

    /**
     * 给每条补上「这块卷**真能不能**在线扩容」—— 列表页拿它决定「调整容量」能不能点。
     *
     * <p>⚠️ 判据（{@code allowVolumeExpansion} + provisioner 是否在 {@code CSIDriver} 名单里）
     * **只有一处算法**（{@link CoreV1StorageClassOperations#fillExpansion}），这里只是按
     * {@code storageClassName} 查表。前端自己猜不出来：{@code CSIDriver} 名单它拿不到，
     * 而只看 {@code allowVolumeExpansion} 是假绿灯 —— 2026-10-16 用户就是这么撞上的
     * （两个 SC 都写着 true、两个都做不到）。
     *
     * <p>整批只读**一次**存储类（不是每条 PVC 读一次）。读不到就留 {@code null} ——
     * UI 那边把 null 当"不许扩"处理（fail-closed），与后端同一个口径。
     */
    private List<PersistentVolumeClaimDTO> enrich(List<PersistentVolumeClaim> items) {
        Map<String, String> byStorageClass = expansionByStorageClass();
        return items.stream()
                .map(converter::revert)
                .filter(Objects::nonNull)
                .peek(dto -> dto.setExpansion(dto.getStorageClassName() == null ? null
                        : byStorageClass.get(dto.getStorageClassName())))
                .toList();
    }

    /** {@code 存储类名 → 扩容结论}；读不到就空表（调用方据此留 null）。 */
    private Map<String, String> expansionByStorageClass() {
        Map<String, String> out = new HashMap<>();
        try {
            Set<String> csiDrivers = CoreV1StorageClassOperations.csiDriverNames(client);
            for (StorageClass sc : client.storage().v1().storageClasses().list().getItems()) {
                if (sc.getMetadata() == null) {
                    continue;
                }
                StorageClassDTO probe = new StorageClassDTO();
                probe.setProvisioner(sc.getProvisioner());
                probe.setAllowVolumeExpansion(sc.getAllowVolumeExpansion());
                CoreV1StorageClassOperations.fillExpansion(probe, csiDrivers);
                out.put(sc.getMetadata().getName(), probe.getExpansion());
            }
        } catch (Exception e) {
            // ⚠️ 读不到就整批留空 → 前端把 null 当"不许扩"。有意的 fail-closed：
            //    宁可少给一个入口，也别让用户对着一个不动的容量猜
            log.warn("读存储类失败 —— 本次 PVC 列表不带「能不能扩」：{}", e.getMessage());
        }
        return out;
    }

    @Override
    public PersistentVolumeClaimDTO create(PersistentVolumeClaimDTO pvc) {
        String namespace = pvc.getNamespace();
        String name = pvc.getName();
        if (checkExist(namespace, name)) {
            throw new CloudPlatformException(EnumResponseType.RESOURCE_EXIST);
        }
        PersistentVolumeClaim in = converter.convert(pvc);

        PersistentVolumeClaim created = client.persistentVolumeClaims()
                .inNamespace(namespace)
                .resource(in)
                .fieldManager(ServerSideApply.FIELD_MANAGER).forceConflicts().serverSideApply();
        return converter.revert(created);
    }

    /**
     * 更新 PVC：同步标签（以及平台侧算好的容量 —— 扩容，见 platform-api 的 PvcService）。
     *
     * <p>⚠️⚠️ **只发平台管的字段（apply config），不要发"从集群读回来的整个对象"。**
     * 2026-10-16 真撞到：
     * <pre>
     * PATCH …/persistentvolumeclaims/platform-pipeline-cache?fieldManager=platform-system&force=true
     * Message: metadata.managedFields must be nil. Received status: Status(code=400 …)
     * </pre>
     * 读回来的那份带着 {@code metadata.managedFields}，而 SSA **明确要求它是 nil**；
     * 而且发整个对象还会把平台标成**所有字段**的 owner（连 kube-controller-manager 写的
     * {@code spec.volumeName} 一起）。{@link ServerSideApply} 的约定本来就是
     * 「converter 只产出 apply config」。
     *
     * <p>这个 bug 一直是潜伏的：{@code PUT /resource/pvcs/{name}} 在 2026-10-16 之前
     * **没有任何调用方**（PVC 页没有编辑入口），所以这段代码从没真跑过。
     */
    @Override
    public PersistentVolumeClaimDTO update(PersistentVolumeClaimDTO pvc) {
        // ⚠️ SSA 对不存在的对象是**会创建**的 —— 所以"不存在"得自己先拦，别让 PUT 变成隐式 create
        if (!checkExist(pvc.getNamespace(), pvc.getName())) {
            throw new CloudPlatformException(EnumResponseType.RESOURCE_NOT_EXIST);
        }
        PersistentVolumeClaim apply = converter.convert(pvc);
        apply.getMetadata().setName(pvc.getName());
        apply.getMetadata().setNamespace(pvc.getNamespace());
        PersistentVolumeClaim updated = client.persistentVolumeClaims()
                .inNamespace(pvc.getNamespace())
                .resource(apply)
                .fieldManager(ServerSideApply.FIELD_MANAGER).forceConflicts().serverSideApply();
        return converter.revert(updated);
    }

    public boolean checkExist(String namespace, String name) {
        PersistentVolumeClaim existing = client.persistentVolumeClaims().inNamespace(namespace).withName(name).get();
        return existing != null;
    }

    @Override
    public void delete(String namespace, String name) {
        if (!checkExist(namespace, name)) {
            throw new CloudPlatformException(EnumResponseType.RESOURCE_NOT_EXIST);
        }
        client.persistentVolumeClaims().inNamespace(namespace)
                .withName(name)
                .delete();
    }

    @Override
    public String yaml(String namespace, String name) {
        if (!StringUtils.hasText(namespace) || !StringUtils.hasText(name)) {
            throw new CloudPlatformException(EnumResponseType.SEARCH_K8S_REQUIRE_NAME_AND_NAMESPACE);
        }
        PersistentVolumeClaim pvc = client.persistentVolumeClaims()
                .inNamespace(namespace)
                .withName(name)
                .get();
        if (pvc != null && pvc.getMetadata() != null) {
            pvc.getMetadata().setManagedFields(null);
        }
        return Serialization.asYaml(pvc);
    }

}
