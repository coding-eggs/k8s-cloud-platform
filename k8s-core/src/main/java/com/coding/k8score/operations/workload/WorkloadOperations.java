package com.coding.k8score.operations.workload;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.k8s.dto.WorkloadDTO;
import com.coding.k8score.converter.impl.workload.WorkloadConverter;
import com.coding.k8score.operations.NamespacedOperations;
import io.fabric8.kubernetes.api.model.ListOptions;
import io.fabric8.kubernetes.api.model.apps.DaemonSet;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.StatefulSet;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.utils.Serialization;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 工作负载统一操作：Deployment / StatefulSet / DaemonSet 共用 WorkloadDTO（kind 区分）。
 * list 返回三种 kind 合并结果；get/delete/yaml 按名跨 kind 查找（deployment → statefulset → daemonset）。
 */
@Slf4j
@RequiredArgsConstructor
public class WorkloadOperations implements NamespacedOperations<WorkloadDTO> {

    public final static String apiVersion = "apps/v1";

    private final KubernetesClient client;

    private final WorkloadConverter converter;

    @Override
    public String apiVersion() {
        return apiVersion;
    }

    @Override
    public List<WorkloadDTO> list(String namespace, String labelSelector) {
        String ns = StringUtils.hasText(namespace) ? namespace : "";
        ListOptions options = new ListOptions();
        if (StringUtils.hasText(labelSelector)) {
            options.setLabelSelector(labelSelector);
        }

        List<WorkloadDTO> result = new ArrayList<>();
        client.apps().deployments().inNamespace(ns).list(options).getItems()
                .forEach(d -> result.add(converter.revert(d)));
        client.apps().statefulSets().inNamespace(ns).list(options).getItems()
                .forEach(s -> result.add(converter.revert(s)));
        client.apps().daemonSets().inNamespace(ns).list(options).getItems()
                .forEach(ds -> result.add(converter.revert(ds)));
        return result;
    }

    @Override
    public WorkloadDTO get(String namespace, String name) {
        if (!StringUtils.hasText(namespace) || !StringUtils.hasText(name)) {
            throw new CloudPlatformException(EnumResponseType.SEARCH_K8S_REQUIRE_NAME_AND_NAMESPACE);
        }
        Resolved resolved = resolve(namespace, name);
        return switch (resolved.kind()) {
            case WorkloadConverter.KIND_DEPLOYMENT -> converter.revert((Deployment) resolved.object());
            case WorkloadConverter.KIND_STATEFULSET -> converter.revert((StatefulSet) resolved.object());
            default -> converter.revert((DaemonSet) resolved.object());
        };
    }

    @Override
    public WorkloadDTO create(WorkloadDTO workload) {
        String namespace = workload.getNamespace();
        String name = workload.getName();
        String kind = normalizeKind(workload.getKind());
        switch (kind) {
            case WorkloadConverter.KIND_DEPLOYMENT -> {
                if (client.apps().deployments().inNamespace(namespace).withName(name).get() != null) {
                    throw new CloudPlatformException(EnumResponseType.RESOURCE_EXIST);
                }
                Deployment created = client.apps().deployments()
                        .inNamespace(namespace)
                        .resource(converter.convertDeployment(workload))
                        .create();
                return converter.revert(created);
            }
            case WorkloadConverter.KIND_STATEFULSET -> {
                if (client.apps().statefulSets().inNamespace(namespace).withName(name).get() != null) {
                    throw new CloudPlatformException(EnumResponseType.RESOURCE_EXIST);
                }
                StatefulSet created = client.apps().statefulSets()
                        .inNamespace(namespace)
                        .resource(converter.convertStatefulSet(workload))
                        .create();
                return converter.revert(created);
            }
            default -> {
                if (client.apps().daemonSets().inNamespace(namespace).withName(name).get() != null) {
                    throw new CloudPlatformException(EnumResponseType.RESOURCE_EXIST);
                }
                DaemonSet created = client.apps().daemonSets()
                        .inNamespace(namespace)
                        .resource(converter.convertDaemonSet(workload))
                        .create();
                return converter.revert(created);
            }
        }
    }

    /**
     * 更新工作负载：基础表单仅支持副本数伸缩（DaemonSet 无副本概念，忽略）；标签保持原值
     */
    @Override
    public WorkloadDTO update(WorkloadDTO workload) {
        String namespace = workload.getNamespace();
        String name = workload.getName();
        String kind = normalizeKind(workload.getKind());
        switch (kind) {
            case WorkloadConverter.KIND_DEPLOYMENT -> {
                Deployment existing = client.apps().deployments().inNamespace(namespace).withName(name).get();
                if (existing == null) {
                    throw new CloudPlatformException(EnumResponseType.RESOURCE_NOT_EXIST);
                }
                if (workload.getReplicas() != null) {
                    existing.getSpec().setReplicas(workload.getReplicas());
                }
                return converter.revert(client.apps().deployments().inNamespace(namespace).resource(existing).update());
            }
            case WorkloadConverter.KIND_STATEFULSET -> {
                StatefulSet existing = client.apps().statefulSets().inNamespace(namespace).withName(name).get();
                if (existing == null) {
                    throw new CloudPlatformException(EnumResponseType.RESOURCE_NOT_EXIST);
                }
                if (workload.getReplicas() != null) {
                    existing.getSpec().setReplicas(workload.getReplicas());
                }
                return converter.revert(client.apps().statefulSets().inNamespace(namespace).resource(existing).update());
            }
            default -> {
                DaemonSet existing = client.apps().daemonSets().inNamespace(namespace).withName(name).get();
                if (existing == null) {
                    throw new CloudPlatformException(EnumResponseType.RESOURCE_NOT_EXIST);
                }
                //DaemonSet 无可变副本数，原样返回
                return converter.revert(existing);
            }
        }
    }

    @Override
    public void delete(String namespace, String name) {
        Resolved resolved = resolve(namespace, name);
        switch (resolved.kind()) {
            case WorkloadConverter.KIND_DEPLOYMENT ->
                    client.apps().deployments().inNamespace(namespace).withName(name).delete();
            case WorkloadConverter.KIND_STATEFULSET ->
                    client.apps().statefulSets().inNamespace(namespace).withName(name).delete();
            default ->
                    client.apps().daemonSets().inNamespace(namespace).withName(name).delete();
        }
    }

    @Override
    public String yaml(String namespace, String name) {
        if (!StringUtils.hasText(namespace) || !StringUtils.hasText(name)) {
            throw new CloudPlatformException(EnumResponseType.SEARCH_K8S_REQUIRE_NAME_AND_NAMESPACE);
        }
        return Serialization.asYaml(resolve(namespace, name).object());
    }

    /**按名跨 kind 查找：deployment → statefulset → daemonset */
    private Resolved resolve(String namespace, String name) {
        Deployment d = client.apps().deployments().inNamespace(namespace).withName(name).get();
        if (d != null) {
            return new Resolved(WorkloadConverter.KIND_DEPLOYMENT, d);
        }
        StatefulSet s = client.apps().statefulSets().inNamespace(namespace).withName(name).get();
        if (s != null) {
            return new Resolved(WorkloadConverter.KIND_STATEFULSET, s);
        }
        DaemonSet ds = client.apps().daemonSets().inNamespace(namespace).withName(name).get();
        if (ds != null) {
            return new Resolved(WorkloadConverter.KIND_DAEMONSET, ds);
        }
        throw new CloudPlatformException(EnumResponseType.RESOURCE_NOT_EXIST);
    }

    private String normalizeKind(String kind) {
        if (!StringUtils.hasText(kind)) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "工作负载 kind 不能为空（deployment/statefulset/daemonset）");
        }
        String k = kind.trim().toLowerCase();
        if (k.equals(WorkloadConverter.KIND_DEPLOYMENT)
                || k.equals(WorkloadConverter.KIND_STATEFULSET)
                || k.equals(WorkloadConverter.KIND_DAEMONSET)) {
            return k;
        }
        throw new CloudPlatformException(EnumResponseType.ERROR, "不支持的工作负载类型: " + kind);
    }

    /**kind + 原始 K8s 对象 */
    private record Resolved(String kind, Object object) {
    }

}
