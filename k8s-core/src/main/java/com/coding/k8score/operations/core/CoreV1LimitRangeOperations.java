package com.coding.k8score.operations.core;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.k8s.dto.LimitRangeDTO;
import com.coding.k8score.converter.impl.core.CoreV1LimitRangeConverter;
import com.coding.k8score.operations.NamespacedOperations;
import com.coding.k8score.operations.ServerSideApply;
import io.fabric8.kubernetes.api.model.LimitRange;
import io.fabric8.kubernetes.api.model.ListOptions;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.utils.Serialization;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * core/v1 LimitRange 操作。
 * <p>update 走 fetch-overlay（{@link CoreV1LimitRangeConverter#convertForUpdate}）：spec.limits 是
 * SSA 下的 atomic list（无 patchMergeKey），省略某项即删；先以线上对象为底、<b>按 type 对齐</b>做
 * 字段级增删改，才能让未建模类型（ContainerFixed 等）存活。
 * <p>LimitRange 无 status 子资源，本类任何路径都不产出 status。
 */
@Slf4j
@RequiredArgsConstructor
public class CoreV1LimitRangeOperations implements NamespacedOperations<LimitRangeDTO> {

    public final static String apiVersion = "v1";

    private final KubernetesClient client;

    private final CoreV1LimitRangeConverter converter;

    @Override
    public String apiVersion() {
        return apiVersion;
    }

    @Override
    public List<LimitRangeDTO> list(String namespace, String labelSelector, String fieldSelector) {
        String ns = StringUtils.hasText(namespace) ? namespace : "";
        ListOptions options = new ListOptions();
        if (StringUtils.hasText(labelSelector)) {
            options.setLabelSelector(labelSelector);
        }
        if (StringUtils.hasText(fieldSelector)) {
            options.setFieldSelector(fieldSelector);
        }
        List<LimitRange> items = client.limitRanges()
                .inNamespace(ns).list(options).getItems();
        return items.stream().map(converter::revert).toList();
    }

    /**
     * 跨全部命名空间列举（平台侧；本实例必须是 admin client，见 {@link NamespacedOperations#listAll}）。
     * <p>返回的每个 item 自带其所在 namespace —— converter 从 metadata 取，本方法<b>不做</b>统一回填。
     */
    @Override
    public List<LimitRangeDTO> listAll(String labelSelector, String fieldSelector) {
        ListOptions options = new ListOptions();
        if (StringUtils.hasText(labelSelector)) {
            options.setLabelSelector(labelSelector);
        }
        if (StringUtils.hasText(fieldSelector)) {
            options.setFieldSelector(fieldSelector);
        }
        List<LimitRange> items = client.limitRanges()
                .inAnyNamespace().list(options).getItems();
        return items.stream().map(converter::revert).toList();
    }

    @Override
    public LimitRangeDTO get(String namespace, String name) {
        if (!StringUtils.hasText(namespace) || !StringUtils.hasText(name)) {
            throw new CloudPlatformException(EnumResponseType.SEARCH_K8S_REQUIRE_NAME_AND_NAMESPACE);
        }
        LimitRange lr = client.limitRanges()
                .inNamespace(namespace)
                .withName(name)
                .get();
        return converter.revert(lr);
    }

    @Override
    public LimitRangeDTO create(LimitRangeDTO dto) {
        String namespace = dto.getNamespace();
        String name = dto.getName();
        if (checkExist(namespace, name)) {
            throw new CloudPlatformException(EnumResponseType.RESOURCE_EXIST);
        }
        LimitRange in = converter.convert(dto);

        LimitRange created = client.limitRanges()
                .inNamespace(namespace)
                .resource(in)
                .create();
        return converter.revert(created);
    }

    @Override
    public LimitRangeDTO update(LimitRangeDTO dto) {
        String namespace = dto.getNamespace();
        String name = dto.getName();
        // fetch-overlay：先取线上对象，converter 以它为底按 type 对齐做 limits 的字段级 overlay
        LimitRange live = client.limitRanges()
                .inNamespace(namespace)
                .withName(name)
                .get();
        if (live == null) {
            throw new CloudPlatformException(EnumResponseType.RESOURCE_NOT_EXIST);
        }
        LimitRange in = converter.convertForUpdate(dto, live);
        LimitRange updated = client.limitRanges()
                .inNamespace(namespace)
                .resource(in)
                .fieldManager(ServerSideApply.FIELD_MANAGER).forceConflicts().serverSideApply();
        return converter.revert(updated);
    }

    public boolean checkExist(String namespace, String name) {
        LimitRange existing = client.limitRanges().inNamespace(namespace).withName(name).get();
        return existing != null;
    }

    @Override
    public void delete(String namespace, String name) {
        if (!checkExist(namespace, name)) {
            throw new CloudPlatformException(EnumResponseType.RESOURCE_NOT_EXIST);
        }
        client.limitRanges().inNamespace(namespace)
                .withName(name)
                .delete();
    }

    @Override
    public String yaml(String namespace, String name) {
        if (!StringUtils.hasText(namespace) || !StringUtils.hasText(name)) {
            throw new CloudPlatformException(EnumResponseType.SEARCH_K8S_REQUIRE_NAME_AND_NAMESPACE);
        }
        LimitRange lr = client.limitRanges()
                .inNamespace(namespace)
                .withName(name)
                .get();
        if (lr != null && lr.getMetadata() != null) {
            lr.getMetadata().setManagedFields(null);
        }
        return Serialization.asYaml(lr);
    }

}
