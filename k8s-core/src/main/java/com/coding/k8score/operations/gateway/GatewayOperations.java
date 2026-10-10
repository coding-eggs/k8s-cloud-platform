package com.coding.k8score.operations.gateway;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.k8s.dto.GatewayDTO;
import com.coding.k8score.converter.impl.gateway.GatewayConverter;
import com.coding.k8score.operations.NamespacedOperations;
import com.coding.k8score.operations.ServerSideApply;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.ListOptions;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext;
import io.fabric8.kubernetes.client.utils.Serialization;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * Gateway 操作（gateway.networking.k8s.io/v1 CRD，命名空间级，fabric8 通用 CRD API）。
 * <p>租户域资源：走 tenant client（最小权限）或 admin 代操作，由 k8s-server 的
 * {@code AbstractNamespacedResourceController} 按分配表三元组选择。
 * <p>集群未装 Gateway API 时 apiserver 返回 404，由上层异常体系透出（前端横幅降级「未安装」+ create 禁用）。
 * <p><b>版本</b>：Gateway 自 Gateway API v1 起 GA（v1 之前的 v1alpha2/v1beta1 平台不支持）——
 * 单版本，无需 capability 分派。
 *
 * <p><b>部署侧待确认（K8s RBAC = 授权链的第 4 层）</b>：租户 token 用的是租户 SA，其权限来自命名空间分配时
 * 绑定的 RoleBinding（模板由运维在「RBAC 模板」页维护）。**模板若未覆盖 {@code gateway.networking.k8s.io}
 * 的 gateways 与 5 类 Route，租户侧调用会被 apiserver 拒绝（403）**，而平台管理员不受影响（admin client
 * 是集群 kubeconfig）。本模块的 7 类对象都属这一族，部署时请确认所用模板已包含。B6 新增的权限行只解决
 * 「platform-api 端点的细粒度授权」，管不到这一层。
 */
@Slf4j
@RequiredArgsConstructor
public class GatewayOperations implements NamespacedOperations<GatewayDTO> {

    public final static String apiVersion = "gateway.networking.k8s.io/v1";

    private static final CustomResourceDefinitionContext CRD = new CustomResourceDefinitionContext.Builder()
            .withGroup("gateway.networking.k8s.io")
            .withVersion("v1")
            .withKind("Gateway")
            .withPlural("gateways")
            .withScope("Namespaced")
            .build();

    private final KubernetesClient client;

    private final GatewayConverter converter;

    @Override
    public String apiVersion() {
        return apiVersion;
    }

    @Override
    public List<GatewayDTO> list(String namespace, String labelSelector, String fieldSelector) {
        String ns = StringUtils.hasText(namespace) ? namespace : "";
        ListOptions options = new ListOptions();
        if (StringUtils.hasText(labelSelector)) {
            options.setLabelSelector(labelSelector);
        }
        if (StringUtils.hasText(fieldSelector)) {
            options.setFieldSelector(fieldSelector);
        }
        List<GenericKubernetesResource> items = client.genericKubernetesResources(CRD)
                .inNamespace(ns).list(options).getItems();
        return items.stream().map(converter::revert).toList();
    }

    /**
     * 跨全部命名空间列举（平台侧）。
     * <p><b>2026-10-08 曾"有意不覆写"</b>（理由：Gateway 属租户域，租户要"我全部命名空间的 Gateway"
     * 应由 platform-api 扇出）。<b>2026-10-10 推翻</b>：能力与暴露是两个闸门（backend-layering.md §5.2），
     * 覆写只是补能力 —— 没有权限行就没有可达路径，租户侧的扇出语义不受影响；而平台侧确有"全集群网关视图"
     * 的需求（B6 的平台页）。故与其它命名空间级资源保持一致。
     * <p>返回的每个 item 自带其所在 namespace —— converter 从 metadata 取，本方法<b>不做</b>统一回填。
     */
    @Override
    public List<GatewayDTO> listAll(String labelSelector, String fieldSelector) {
        ListOptions options = new ListOptions();
        if (StringUtils.hasText(labelSelector)) {
            options.setLabelSelector(labelSelector);
        }
        if (StringUtils.hasText(fieldSelector)) {
            options.setFieldSelector(fieldSelector);
        }
        List<GenericKubernetesResource> items = client.genericKubernetesResources(CRD)
                .inAnyNamespace().list(options).getItems();
        return items.stream().map(converter::revert).toList();
    }

    @Override
    public GatewayDTO get(String namespace, String name) {
        if (!StringUtils.hasText(namespace) || !StringUtils.hasText(name)) {
            throw new CloudPlatformException(EnumResponseType.SEARCH_K8S_REQUIRE_NAME_AND_NAMESPACE);
        }
        GenericKubernetesResource res = client.genericKubernetesResources(CRD)
                .inNamespace(namespace)
                .withName(name)
                .get();
        return converter.revert(res);
    }

    @Override
    public GatewayDTO create(GatewayDTO gw) {
        String namespace = gw.getNamespace();
        String name = gw.getName();
        if (checkExist(namespace, name)) {
            throw new CloudPlatformException(EnumResponseType.RESOURCE_EXIST);
        }
        GenericKubernetesResource in = converter.convert(gw);
        GenericKubernetesResource created = client.genericKubernetesResources(CRD)
                .inNamespace(namespace)
                .resource(in)
                .create();
        return converter.revert(created);
    }

    @Override
    public GatewayDTO update(GatewayDTO gw) {
        String namespace = gw.getNamespace();
        String name = gw.getName();
        // fetch-overlay：listeners 未建模子字段（tls.options 等）以线上为底带回，见 GatewayConverter
        GenericKubernetesResource live = client.genericKubernetesResources(CRD)
                .inNamespace(namespace)
                .withName(name)
                .get();
        if (live == null) {
            throw new CloudPlatformException(EnumResponseType.RESOURCE_NOT_EXIST);
        }
        GenericKubernetesResource in = converter.convertForUpdate(gw, live);
        GenericKubernetesResource updated = client.genericKubernetesResources(CRD)
                .inNamespace(namespace)
                .resource(in)
                .fieldManager(ServerSideApply.FIELD_MANAGER).forceConflicts().serverSideApply();
        return converter.revert(updated);
    }

    public boolean checkExist(String namespace, String name) {
        GenericKubernetesResource existing = client.genericKubernetesResources(CRD)
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
        client.genericKubernetesResources(CRD).inNamespace(namespace)
                .withName(name)
                .delete();
    }

    @Override
    public String yaml(String namespace, String name) {
        if (!StringUtils.hasText(namespace) || !StringUtils.hasText(name)) {
            throw new CloudPlatformException(EnumResponseType.SEARCH_K8S_REQUIRE_NAME_AND_NAMESPACE);
        }
        GenericKubernetesResource res = client.genericKubernetesResources(CRD)
                .inNamespace(namespace)
                .withName(name)
                .get();
        if (res != null && res.getMetadata() != null) {
            res.getMetadata().setManagedFields(null);
        }
        return Serialization.asYaml(res);
    }

}
