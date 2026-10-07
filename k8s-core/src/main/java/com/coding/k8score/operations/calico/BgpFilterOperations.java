package com.coding.k8score.operations.calico;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.k8s.dto.BgpFilterDTO;
import com.coding.k8score.converter.impl.calico.BgpFilterConverter;
import com.coding.k8score.operations.ClusterOperations;
import com.coding.k8score.operations.ServerSideApply;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.ListOptions;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext;
import io.fabric8.kubernetes.client.utils.Serialization;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * BGPFilter 操作（projectcalico.org/v3 CRD，集群级，CRUD；update 走 Server-Side Apply）。
 * <p>全 cluster-scoped：无 namespace 维度。集群未装 Calico 时 apiserver 返回 404，由上层异常体系透出（前端降级「—」）。
 * <p><b>部署侧待确认（RBAC）</b>：admin client（platform-system SA）ClusterRole 需覆盖 projectcalico.org/v3
 * bgpfilters 的 get/list/watch/create/update/patch/delete（读写）。未覆盖时调用被 apiserver 拒绝（403），由上层异常透出。
 */
@RequiredArgsConstructor
public class BgpFilterOperations implements ClusterOperations<BgpFilterDTO> {

    public final static String apiVersion = "projectcalico.org/v3";

    private static final CustomResourceDefinitionContext CRD = new CustomResourceDefinitionContext.Builder()
            .withGroup("projectcalico.org")
            .withVersion("v3")
            .withKind("BGPFilter")
            .withPlural("bgpfilters")
            .withScope("Cluster")
            .build();

    private final KubernetesClient client;

    private final BgpFilterConverter converter;

    @Override
    public String apiVersion() {
        return apiVersion;
    }

    @Override
    public List<BgpFilterDTO> list(String labelSelector, String fieldSelector) {
        ListOptions options = new ListOptions();
        if (StringUtils.hasText(labelSelector)) {
            options.setLabelSelector(labelSelector);
        }
        if (StringUtils.hasText(fieldSelector)) {
            options.setFieldSelector(fieldSelector);
        }
        List<GenericKubernetesResource> items = client.genericKubernetesResources(CRD)
                .list(options).getItems();
        return items.stream().map(converter::revert).toList();
    }

    @Override
    public BgpFilterDTO get(String name) {
        if (!StringUtils.hasText(name)) {
            throw new CloudPlatformException(EnumResponseType.SEARCH_K8S_REQUIRE_NAME_AND_NAMESPACE);
        }
        GenericKubernetesResource res = client.genericKubernetesResources(CRD)
                .withName(name)
                .get();
        return converter.revert(res);
    }

    @Override
    public String yaml(String name) {
        if (!StringUtils.hasText(name)) {
            throw new CloudPlatformException(EnumResponseType.SEARCH_K8S_REQUIRE_NAME_AND_NAMESPACE);
        }
        GenericKubernetesResource res = client.genericKubernetesResources(CRD)
                .withName(name)
                .get();
        if (res != null && res.getMetadata() != null) {
            res.getMetadata().setManagedFields(null);
        }
        return Serialization.asYaml(res);
    }

    @Override
    public BgpFilterDTO create(BgpFilterDTO resource) {
        String name = resource.getName();
        if (checkExist(name)) {
            throw new CloudPlatformException(EnumResponseType.RESOURCE_EXIST);
        }
        GenericKubernetesResource in = converter.convert(resource);
        GenericKubernetesResource created = client.genericKubernetesResources(CRD)
                .resource(in)
                .create();
        return converter.revert(created);
    }

    @Override
    public BgpFilterDTO update(BgpFilterDTO resource) {
        String name = resource.getName();
        // fetch-overlay：先取线上对象，converter 以它为底覆盖建模字段，保留未建模的 spec 外部字段
        GenericKubernetesResource live = client.genericKubernetesResources(CRD)
                .withName(name)
                .get();
        if (live == null) {
            throw new CloudPlatformException(EnumResponseType.RESOURCE_NOT_EXIST);
        }
        GenericKubernetesResource in = converter.convertForUpdate(resource, live);
        GenericKubernetesResource updated = client.genericKubernetesResources(CRD)
                .resource(in)
                .fieldManager(ServerSideApply.FIELD_MANAGER).forceConflicts().serverSideApply();
        return converter.revert(updated);
    }

    public boolean checkExist(String name) {
        GenericKubernetesResource existing = client.genericKubernetesResources(CRD)
                .withName(name)
                .get();
        return existing != null;
    }

    @Override
    public void delete(String name) {
        if (!checkExist(name)) {
            throw new CloudPlatformException(EnumResponseType.RESOURCE_NOT_EXIST);
        }
        client.genericKubernetesResources(CRD)
                .withName(name)
                .delete();
    }

}
