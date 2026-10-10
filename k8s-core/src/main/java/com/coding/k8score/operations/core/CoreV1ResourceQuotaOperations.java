package com.coding.k8score.operations.core;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.k8s.dto.ResourceQuotaDTO;
import com.coding.k8score.converter.impl.core.CoreV1ResourceQuotaConverter;
import com.coding.k8score.operations.NamespacedOperations;
import com.coding.k8score.operations.ServerSideApply;
import io.fabric8.kubernetes.api.model.ListOptions;
import io.fabric8.kubernetes.api.model.ResourceQuota;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.utils.Serialization;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * core/v1 ResourceQuota 操作。
 * <p>update 走 fetch-overlay（{@link CoreV1ResourceQuotaConverter#convertForUpdate}）：hard 是 map，
 * SSA 下 platform-system 拥有它，省略的建模键会被当成删除；先以线上 hard 为底做键级增删改，
 * 才能让未建模的 hard 项（count/deployments.apps、services.nodeports 等）存活。
 * <p>status.used 只读（由 quota controller 写），本类任何路径都不产出 status。
 */
@Slf4j
@RequiredArgsConstructor
public class CoreV1ResourceQuotaOperations implements NamespacedOperations<ResourceQuotaDTO> {

    public final static String apiVersion = "v1";

    private final KubernetesClient client;

    private final CoreV1ResourceQuotaConverter converter;

    @Override
    public String apiVersion() {
        return apiVersion;
    }

    @Override
    public List<ResourceQuotaDTO> list(String namespace, String labelSelector, String fieldSelector) {
        String ns = StringUtils.hasText(namespace) ? namespace : "";
        ListOptions options = new ListOptions();
        if (StringUtils.hasText(labelSelector)) {
            options.setLabelSelector(labelSelector);
        }
        if (StringUtils.hasText(fieldSelector)) {
            options.setFieldSelector(fieldSelector);
        }
        List<ResourceQuota> items = client.resourceQuotas()
                .inNamespace(ns).list(options).getItems();
        return items.stream().map(converter::revert).toList();
    }

    /**
     * 跨全部命名空间列举（平台侧；本实例必须是 admin client，见 {@link NamespacedOperations#listAll}）。
     * <p>返回的每个 item 自带其所在 namespace —— converter 从 metadata 取，本方法<b>不做</b>统一回填。
     */
    @Override
    public List<ResourceQuotaDTO> listAll(String labelSelector, String fieldSelector) {
        ListOptions options = new ListOptions();
        if (StringUtils.hasText(labelSelector)) {
            options.setLabelSelector(labelSelector);
        }
        if (StringUtils.hasText(fieldSelector)) {
            options.setFieldSelector(fieldSelector);
        }
        List<ResourceQuota> items = client.resourceQuotas()
                .inAnyNamespace().list(options).getItems();
        return items.stream().map(converter::revert).toList();
    }

    @Override
    public ResourceQuotaDTO get(String namespace, String name) {
        if (!StringUtils.hasText(namespace) || !StringUtils.hasText(name)) {
            throw new CloudPlatformException(EnumResponseType.SEARCH_K8S_REQUIRE_NAME_AND_NAMESPACE);
        }
        ResourceQuota rq = client.resourceQuotas()
                .inNamespace(namespace)
                .withName(name)
                .get();
        return converter.revert(rq);
    }

    @Override
    public ResourceQuotaDTO create(ResourceQuotaDTO dto) {
        String namespace = dto.getNamespace();
        String name = dto.getName();
        if (checkExist(namespace, name)) {
            throw new CloudPlatformException(EnumResponseType.RESOURCE_EXIST);
        }
        ResourceQuota in = converter.convert(dto);

        ResourceQuota created = client.resourceQuotas()
                .inNamespace(namespace)
                .resource(in)
                .create();
        return converter.revert(created);
    }

    @Override
    public ResourceQuotaDTO update(ResourceQuotaDTO dto) {
        String namespace = dto.getNamespace();
        String name = dto.getName();
        // fetch-overlay：先取线上对象，converter 以它为底做 hard 的键级 overlay
        ResourceQuota live = client.resourceQuotas()
                .inNamespace(namespace)
                .withName(name)
                .get();
        if (live == null) {
            throw new CloudPlatformException(EnumResponseType.RESOURCE_NOT_EXIST);
        }
        ResourceQuota in = converter.convertForUpdate(dto, live);
        ResourceQuota updated = client.resourceQuotas()
                .inNamespace(namespace)
                .resource(in)
                .fieldManager(ServerSideApply.FIELD_MANAGER).forceConflicts().serverSideApply();
        return converter.revert(updated);
    }

    public boolean checkExist(String namespace, String name) {
        ResourceQuota existing = client.resourceQuotas().inNamespace(namespace).withName(name).get();
        return existing != null;
    }

    @Override
    public void delete(String namespace, String name) {
        if (!checkExist(namespace, name)) {
            throw new CloudPlatformException(EnumResponseType.RESOURCE_NOT_EXIST);
        }
        client.resourceQuotas().inNamespace(namespace)
                .withName(name)
                .delete();
    }

    @Override
    public String yaml(String namespace, String name) {
        if (!StringUtils.hasText(namespace) || !StringUtils.hasText(name)) {
            throw new CloudPlatformException(EnumResponseType.SEARCH_K8S_REQUIRE_NAME_AND_NAMESPACE);
        }
        ResourceQuota rq = client.resourceQuotas()
                .inNamespace(namespace)
                .withName(name)
                .get();
        if (rq != null && rq.getMetadata() != null) {
            rq.getMetadata().setManagedFields(null);
        }
        return Serialization.asYaml(rq);
    }

}
