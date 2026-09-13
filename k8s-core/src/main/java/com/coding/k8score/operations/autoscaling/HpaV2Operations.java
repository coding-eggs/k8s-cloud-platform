package com.coding.k8score.operations.autoscaling;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.k8s.dto.HpaDTO;
import com.coding.k8score.converter.CommonConverter;
import com.coding.k8score.operations.NamespacedOperations;
import com.coding.k8score.operations.ServerSideApply;
import io.fabric8.kubernetes.api.model.ListOptions;
import io.fabric8.kubernetes.api.model.autoscaling.v2.HorizontalPodAutoscaler;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.utils.Serialization;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * HPA 操作（autoscaling/v2）。由 {@code KubernetesOperationsFactory.build()} 在集群 capability 含 autoscaling/v2 时构造。
 */
@Slf4j
@RequiredArgsConstructor
public class HpaV2Operations implements NamespacedOperations<HpaDTO> {

    public final static String apiVersion = "autoscaling/v2";

    private final KubernetesClient client;
    private final CommonConverter<HorizontalPodAutoscaler, HpaDTO> converter;

    @Override
    public String apiVersion() {
        return apiVersion;
    }

    @Override
    public List<HpaDTO> list(String namespace, String labelSelector, String fieldSelector) {
        String ns = StringUtils.hasText(namespace) ? namespace : "";
        ListOptions options = new ListOptions();
        if (StringUtils.hasText(labelSelector)) {
            options.setLabelSelector(labelSelector);
        }
        if (StringUtils.hasText(fieldSelector)) {
            options.setFieldSelector(fieldSelector);
        }
        List<HorizontalPodAutoscaler> items = client.autoscaling()
                .v2().horizontalPodAutoscalers()
                .inNamespace(ns).list(options).getItems();
        return items.stream().map(converter::revert).toList();
    }

    @Override
    public HpaDTO get(String namespace, String name) {
        if (!StringUtils.hasText(namespace) || !StringUtils.hasText(name)) {
            throw new CloudPlatformException(EnumResponseType.SEARCH_K8S_REQUIRE_NAME_AND_NAMESPACE);
        }
        HorizontalPodAutoscaler hpa = client.autoscaling().v2().horizontalPodAutoscalers()
                .inNamespace(namespace).withName(name).get();
        return converter.revert(hpa);
    }

    @Override
    public HpaDTO create(HpaDTO dto) {
        String namespace = dto.getNamespace();
        String name = dto.getName();
        if (checkExist(namespace, name)) {
            throw new CloudPlatformException(EnumResponseType.RESOURCE_EXIST);
        }
        HorizontalPodAutoscaler in = converter.convert(dto);
        HorizontalPodAutoscaler created = client.autoscaling().v2().horizontalPodAutoscalers()
                .inNamespace(namespace).resource(in).create();
        return converter.revert(created);
    }

    @Override
    public HpaDTO update(HpaDTO dto) {
        String namespace = dto.getNamespace();
        String name = dto.getName();
        if (!checkExist(namespace, name)) {
            throw new CloudPlatformException(EnumResponseType.RESOURCE_NOT_EXIST);
        }
        HorizontalPodAutoscaler in = converter.convert(dto);
        HorizontalPodAutoscaler updated = client.autoscaling().v2().horizontalPodAutoscalers()
                .inNamespace(namespace).resource(in)
                .fieldManager(ServerSideApply.FIELD_MANAGER).forceConflicts().serverSideApply();
        return converter.revert(updated);
    }

    private boolean checkExist(String namespace, String name) {
        HorizontalPodAutoscaler existing = client.autoscaling().v2().horizontalPodAutoscalers()
                .inNamespace(namespace).withName(name).get();
        return existing != null;
    }

    @Override
    public void delete(String namespace, String name) {
        if (!checkExist(namespace, name)) {
            throw new CloudPlatformException(EnumResponseType.RESOURCE_NOT_EXIST);
        }
        client.autoscaling().v2().horizontalPodAutoscalers()
                .inNamespace(namespace).withName(name).delete();
    }

    @Override
    public String yaml(String namespace, String name) {
        if (!StringUtils.hasText(namespace) || !StringUtils.hasText(name)) {
            throw new CloudPlatformException(EnumResponseType.SEARCH_K8S_REQUIRE_NAME_AND_NAMESPACE);
        }
        HorizontalPodAutoscaler hpa = client.autoscaling().v2().horizontalPodAutoscalers()
                .inNamespace(namespace).withName(name).get();
        if (hpa != null && hpa.getMetadata() != null) {
            hpa.getMetadata().setManagedFields(null);
        }
        return Serialization.asYaml(hpa);
    }

}
