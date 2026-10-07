package com.coding.k8score.operations.calico;

import com.coding.common.models.k8s.dto.CalicoFormOptionDTO;
import com.coding.common.models.k8s.dto.SecretRefOptionDTO;
import com.coding.common.models.k8s.dto.WorkloadOptionDTO;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.client.KubernetesClient;
import lombok.RequiredArgsConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * BGP 编辑器下拉候选（集群级，admin client，只读）。
 * <p>级联查询：{@link #listOptions()} 一次取命名空间 + 工作负载（小集合）；Secret 按所选命名空间
 * {@link #listSecrets(String)} 按需查（含 data keys），不拉全集群。只回名字与 key 列表，不返回任何 Secret 数据值；
 * 失败（如 RBAC 未覆盖 secrets list）由上层异常体系透出，前端降级为空下拉。
 */
@RequiredArgsConstructor
public class CalicoFormOptionOperations {

    private final KubernetesClient client;

    public CalicoFormOptionDTO listOptions() {
        CalicoFormOptionDTO out = new CalicoFormOptionDTO();

        out.setNamespaces(client.namespaces().list().getItems().stream()
                .map(ns -> ns.getMetadata().getName())
                .sorted()
                .toList());

        List<WorkloadOptionDTO> workloads = new ArrayList<>();
        for (var d : client.apps().deployments().inAnyNamespace().list().getItems()) {
            workloads.add(option(d.getMetadata(), "Deployment"));
        }
        for (var st : client.apps().statefulSets().inAnyNamespace().list().getItems()) {
            workloads.add(option(st.getMetadata(), "StatefulSet"));
        }
        out.setWorkloads(workloads);
        return out;
    }

    /** 某命名空间下的 Secret 引用候选（name + data keys，不含数据值）；namespace 为空返回空列表。 */
    public List<SecretRefOptionDTO> listSecrets(String namespace) {
        if (namespace == null || namespace.isBlank()) {
            return List.of();
        }
        return client.secrets().inNamespace(namespace).list().getItems().stream()
                .map(s -> {
                    SecretRefOptionDTO o = new SecretRefOptionDTO();
                    o.setNamespace(s.getMetadata().getNamespace());
                    o.setName(s.getMetadata().getName());
                    Map<String, String> data = s.getData();
                    o.setKeys(data == null ? List.of() : data.keySet().stream().sorted().toList());
                    return o;
                })
                .sorted(java.util.Comparator.comparing(SecretRefOptionDTO::getName))
                .toList();
    }

    private WorkloadOptionDTO option(ObjectMeta meta, String kind) {
        WorkloadOptionDTO o = new WorkloadOptionDTO();
        o.setNamespace(meta.getNamespace());
        o.setKind(kind);
        o.setName(meta.getName());
        return o;
    }

}
