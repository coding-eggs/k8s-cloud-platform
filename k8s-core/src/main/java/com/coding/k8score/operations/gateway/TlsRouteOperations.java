package com.coding.k8score.operations.gateway;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.k8s.dto.TlsRouteDTO;
import com.coding.k8score.converter.impl.gateway.TlsRouteConverter;
import com.coding.k8score.operations.NamespacedOperations;
import com.coding.k8score.operations.ServerSideApply;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.ListOptions;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext;
import io.fabric8.kubernetes.client.utils.Serialization;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * TLSRoute 操作（gateway.networking.k8s.io/<b>v1 或 v1alpha2</b> CRD，命名空间级，fabric8 通用 CRD API）。
 * <p>租户域资源，边界同 Gateway（分配表三元组）。
 * <p>本类与 {@link TcpRouteOperations} 同构（CRUD 骨架一致），差别只在 CRD kind/plural 与 converter 多出的
 * {@code spec.hostnames}（SNI 匹配，见 {@link TlsRouteConverter}）。
 *
 * <h2>CRD 版本从 converter 派生（唯一来源）</h2>
 * L4 路由在 Gateway API v1.6 才 GA 到 {@code v1}，更早的集群只有 {@code v1alpha2}
 * （两者结构相同，见 {@link TlsRouteConverter}）。版本由工厂按集群 capability 解析后交给 converter，
 * 本类<b>从 converter 读版本</b>来建 CRD context —— 不在两处各写一份版本号，避免"converter 写 v1alpha2
 * 而 context 还是 v1"这种错配（那会 404 或写错版本）。
 */
@Slf4j
public class TlsRouteOperations implements NamespacedOperations<TlsRouteDTO> {

    private final KubernetesClient client;

    private final TlsRouteConverter converter;

    private final CustomResourceDefinitionContext crd;

    public TlsRouteOperations(KubernetesClient client, TlsRouteConverter converter) {
        this.client = client;
        this.converter = converter;
        this.crd = new CustomResourceDefinitionContext.Builder()
                .withGroup(TlsRouteConverter.GROUP)
                .withVersion(converter.crdVersion())
                .withKind(TlsRouteConverter.KIND)
                .withPlural("tlsroutes")
                .withScope("Namespaced")
                .build();
    }

    @Override
    public String apiVersion() {
        return converter.apiVersion();
    }

    @Override
    public List<TlsRouteDTO> list(String namespace, String labelSelector, String fieldSelector) {
        String ns = StringUtils.hasText(namespace) ? namespace : "";
        ListOptions options = new ListOptions();
        if (StringUtils.hasText(labelSelector)) {
            options.setLabelSelector(labelSelector);
        }
        if (StringUtils.hasText(fieldSelector)) {
            options.setFieldSelector(fieldSelector);
        }
        List<GenericKubernetesResource> items = client.genericKubernetesResources(crd)
                .inNamespace(ns).list(options).getItems();
        return items.stream().map(converter::revert).toList();
    }

    @Override
    public TlsRouteDTO get(String namespace, String name) {
        if (!StringUtils.hasText(namespace) || !StringUtils.hasText(name)) {
            throw new CloudPlatformException(EnumResponseType.SEARCH_K8S_REQUIRE_NAME_AND_NAMESPACE);
        }
        GenericKubernetesResource res = client.genericKubernetesResources(crd)
                .inNamespace(namespace)
                .withName(name)
                .get();
        return converter.revert(res);
    }

    @Override
    public TlsRouteDTO create(TlsRouteDTO route) {
        String namespace = route.getNamespace();
        String name = route.getName();
        if (checkExist(namespace, name)) {
            throw new CloudPlatformException(EnumResponseType.RESOURCE_EXIST);
        }
        GenericKubernetesResource in = converter.convert(route);
        GenericKubernetesResource created = client.genericKubernetesResources(crd)
                .inNamespace(namespace)
                .resource(in)
                .create();
        return converter.revert(created);
    }

    @Override
    public TlsRouteDTO update(TlsRouteDTO route) {
        String namespace = route.getNamespace();
        String name = route.getName();
        // fetch-overlay：保住 spec 级未建模键（如 v1.6 的 useDefaultGateways）
        GenericKubernetesResource live = client.genericKubernetesResources(crd)
                .inNamespace(namespace)
                .withName(name)
                .get();
        if (live == null) {
            throw new CloudPlatformException(EnumResponseType.RESOURCE_NOT_EXIST);
        }
        GenericKubernetesResource in = converter.convertForUpdate(route, live);
        GenericKubernetesResource updated = client.genericKubernetesResources(crd)
                .inNamespace(namespace)
                .resource(in)
                .fieldManager(ServerSideApply.FIELD_MANAGER).forceConflicts().serverSideApply();
        return converter.revert(updated);
    }

    public boolean checkExist(String namespace, String name) {
        GenericKubernetesResource existing = client.genericKubernetesResources(crd)
                .inNamespace(namespace)
                .withName(name)
                .get();
        return existing != null;
    }

    @Override
    public void delete(String namespace, String name) {
        if (!checkExist(namespace, name)) {
            throw new CloudPlatformException(EnumResponseType.RESOURCE_NOT_EXIST);
        }
        client.genericKubernetesResources(crd).inNamespace(namespace)
                .withName(name)
                .delete();
    }

    @Override
    public String yaml(String namespace, String name) {
        if (!StringUtils.hasText(namespace) || !StringUtils.hasText(name)) {
            throw new CloudPlatformException(EnumResponseType.SEARCH_K8S_REQUIRE_NAME_AND_NAMESPACE);
        }
        GenericKubernetesResource res = client.genericKubernetesResources(crd)
                .inNamespace(namespace)
                .withName(name)
                .get();
        if (res != null && res.getMetadata() != null) {
            res.getMetadata().setManagedFields(null);
        }
        return Serialization.asYaml(res);
    }

}
