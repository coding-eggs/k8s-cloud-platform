package com.coding.k8score.operations.core;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.k8s.dto.StorageClassDTO;
import com.coding.common.models.k8s.dto.VolumeExpansion;
import com.coding.k8score.converter.CommonConverter;
import com.coding.k8score.converter.impl.core.CoreV1StorageClassConverter;
import com.coding.k8score.operations.ClusterOperations;
import com.coding.k8score.operations.ServerSideApply;
import io.fabric8.kubernetes.api.model.ListOptions;
import io.fabric8.kubernetes.api.model.storage.StorageClass;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.base.PatchContext;
import io.fabric8.kubernetes.client.dsl.base.PatchType;
import io.fabric8.kubernetes.client.utils.Serialization;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * StorageClass 集群级资源操作（storage/v1）。平台侧主要用 list/get/yaml 供下拉选择；写操作同样透传。
 */
@Slf4j
@RequiredArgsConstructor
public class CoreV1StorageClassOperations implements ClusterOperations<StorageClassDTO> {

    public final static String apiVersion = "storage.k8s.io/v1";

    private final KubernetesClient client;
    private final CommonConverter<StorageClass, StorageClassDTO> converter;

    /**
     * 「真能不能在线扩容」的**唯一判据**。两个输入都是集群事实，不嗅探名字：
     * <ol>
     *   <li>{@code allowVolumeExpansion}（"允许"）</li>
     *   <li>{@code provisioner} 是否在 {@code CSIDriver} 名单里（"有人执行"）——
     *       CSI 驱动的 SC 里 {@code provisioner} 就是驱动名，而驱动必须注册一个同名的
     *       {@code CSIDriver} 对象</li>
     * </ol>
     *
     * <p>⚠️ 「是 CSI」也只是**大概率能扩**：驱动理论上可以不带 {@code external-resizer}，
     * 那个平台探测不到。所以 UI 的措辞是「可在线扩容（CSI）」，不打包票。
     */
    public static void fillExpansion(StorageClassDTO dto, Set<String> csiDriverNames) {
        if (dto == null) {
            return;
        }
        if (!Boolean.TRUE.equals(dto.getAllowVolumeExpansion())) {
            dto.setExpansion(VolumeExpansion.UNSUPPORTED);
        } else {
            dto.setExpansion(csiDriverNames.contains(dto.getProvisioner()) ? VolumeExpansion.CSI : VolumeExpansion.UNBACKED);
        }
    }

    /**
     * 读一次 {@code CSIDriver} 名单（集群级，storage.k8s.io/v1）。
     *
     * <p>⚠️ 读不到就返回空集 → 一切被算成 {@code unbacked}（也就是**拒**）。这是**有意的
     * fail-closed**：不敢替用户断言"能扩"。代价是"平台没权限读 CSIDriver"时扩容会不可用 ——
     * 那种情况本来就该先修权限，而不是让用户对着一个不动的容量猜。
     */
    public static Set<String> csiDriverNames(KubernetesClient client) {
        try {
            return client.storage().v1().csiDrivers().list().getItems().stream()
                    .map(d -> d.getMetadata() == null ? null : d.getMetadata().getName())
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());
        } catch (Exception e) {
            log.warn("读 CSIDriver 名单失败 —— 扩容能力一律按「没有执行方」算：{}", e.getMessage());
            return Set.of();
        }
    }

    @Override
    public String apiVersion() {
        return apiVersion;
    }

    /** 查询 StorageClass 列表（集群级资源，不支持 namespace 过滤）；labelSelector 原样透传 */
    @Override
    public List<StorageClassDTO> list(String labelSelector, String fieldSelector) {
        ListOptions options = new ListOptions();
        if (StringUtils.hasText(labelSelector)) {
            options.setLabelSelector(labelSelector);
        }
        if (StringUtils.hasText(fieldSelector)) {
            options.setFieldSelector(fieldSelector);
        }
        // ⚠️ 名单**整批只读一次**（不是每个 SC 读一次）—— 它是集群级的一个小列表
        Set<String> csi = csiDriverNames(client);
        return client.storage().v1().storageClasses().list(options).getItems().stream()
                .map(converter::revert)
                .filter(Objects::nonNull)
                .peek(dto -> fillExpansion(dto, csi))
                .toList();
    }

    @Override
    public StorageClassDTO get(String name) {
        if (!StringUtils.hasText(name)) {
            throw new CloudPlatformException(EnumResponseType.SEARCH_K8S_REQUIRE_NAME_AND_NAMESPACE);
        }
        StorageClassDTO dto = converter.revert(client.storage().v1().storageClasses().withName(name).get());
        fillExpansion(dto, csiDriverNames(client));
        return dto;
    }

    @Override
    public StorageClassDTO create(StorageClassDTO dto) {
        String name = dto.getName();
        if (checkExist(name)) {
            throw new CloudPlatformException(EnumResponseType.RESOURCE_EXIST);
        }
        StorageClass in = converter.convert(dto);
        return converter.revert(client.storage().v1().storageClasses().resource(in).create());
    }

    @Override
    public StorageClassDTO update(StorageClassDTO dto) {
        String name = dto.getName();
        if (!checkExist(name)) {
            throw new CloudPlatformException(EnumResponseType.RESOURCE_NOT_EXIST);
        }
        StorageClass in = converter.convert(dto);
        return converter.revert(client.storage().v1().storageClasses().resource(in)
                .fieldManager(ServerSideApply.FIELD_MANAGER).forceConflicts().serverSideApply());
    }

    /**
     * 设 / 取消集群默认存储类：改注解 {@link CoreV1StorageClassConverter#DEFAULT_CLASS_ANNOTATION}。
     *
     * <p><b>单默认不变式</b>（用户约定）：设为默认时，先把该集群里<b>其它</b>带此注解的 SC 清掉，再给目标打上；
     * 取消只删目标自己。用 JSON merge patch（值 {@code null} = 删 key），<b>不挑 field owner</b> —— 这些注解常是
     * 集群工具（CSI / 云厂商 controller）设的，SSA 删不掉别人拥有的 key。返回重读后的对象，{@code isDefault}
     * 与 {@code expansion} 都是新值。
     */
    public StorageClassDTO setDefault(String name, boolean isDefault) {
        if (!StringUtils.hasText(name)) {
            throw new CloudPlatformException(EnumResponseType.SEARCH_K8S_REQUIRE_NAME_AND_NAMESPACE);
        }
        if (!checkExist(name)) {
            throw new CloudPlatformException(EnumResponseType.RESOURCE_NOT_EXIST);
        }
        if (isDefault) {
            Map<String, Boolean> current = new LinkedHashMap<>();
            for (StorageClass sc : client.storage().v1().storageClasses().list().getItems()) {
                if (sc.getMetadata() == null || sc.getMetadata().getName() == null) {
                    continue;
                }
                current.put(sc.getMetadata().getName(), hasDefaultAnnotation(sc));
            }
            for (String other : othersToClear(name, current)) {
                patchDefaultAnnotation(other, false);
            }
        }
        patchDefaultAnnotation(name, isDefault);
        return get(name);
    }

    /**
     * 纯逻辑（单测判据）：设 {@code targetName} 为默认时，需要<b>清掉</b>注解的其它 SC 名单 ——
     * 当前已默认、且不是目标的那些。保持"一个集群至多一个默认"。取消默认不走这里（只删目标自己）。
     */
    static Set<String> othersToClear(String targetName, Map<String, Boolean> current) {
        Set<String> toClear = new LinkedHashSet<>();
        for (Map.Entry<String, Boolean> e : current.entrySet()) {
            if (Boolean.TRUE.equals(e.getValue()) && !e.getKey().equals(targetName)) {
                toClear.add(e.getKey());
            }
        }
        return toClear;
    }

    private boolean hasDefaultAnnotation(StorageClass sc) {
        Map<String, String> annotations = sc.getMetadata() == null ? null : sc.getMetadata().getAnnotations();
        return annotations != null
                && "true".equalsIgnoreCase(annotations.get(CoreV1StorageClassConverter.DEFAULT_CLASS_ANNOTATION));
    }

    /**
     * merge patch 打 / 删默认注解：{@code isDefault=true} 置 {@code "true"}（无则新建 annotations map），
     * {@code false} 置 {@code null}（删 key）。显式拼 JSON 而非走序列化器 —— 确保删除分支真的产出
     * {@code "key":null}（某些 Jackson 配置会吞掉 null，那样就删不掉、悄悄变成 no-op）。
     */
    private void patchDefaultAnnotation(String name, boolean isDefault) {
        String json = "{\"metadata\":{\"annotations\":{\"" + CoreV1StorageClassConverter.DEFAULT_CLASS_ANNOTATION
                + "\":" + (isDefault ? "\"true\"" : "null") + "}}}";
        client.storage().v1().storageClasses().withName(name)
                .patch(PatchContext.of(PatchType.JSON_MERGE), json);
    }

    @Override
    public void delete(String name) {
        if (!checkExist(name)) {
            throw new CloudPlatformException(EnumResponseType.RESOURCE_NOT_EXIST);
        }
        client.storage().v1().storageClasses().withName(name).delete();
    }

    @Override
    public String yaml(String name) {
        if (!StringUtils.hasText(name)) {
            throw new CloudPlatformException(EnumResponseType.SEARCH_K8S_REQUIRE_NAME_AND_NAMESPACE);
        }
        StorageClass sc = client.storage().v1().storageClasses().withName(name).get();
        if (sc != null && sc.getMetadata() != null) {
            sc.getMetadata().setManagedFields(null);
        }
        return Serialization.asYaml(sc);
    }

    private boolean checkExist(String name) {
        return client.storage().v1().storageClasses().withName(name).get() != null;
    }

}
