package com.coding.k8score.operations.core;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.k8s.dto.ServiceDTO;
import com.coding.k8score.converter.CommonConverter;
import com.coding.k8score.operations.NamespacedOperations;
import com.coding.k8score.operations.ServerSideApply;
import io.fabric8.kubernetes.api.model.ListOptions;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServicePort;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.base.PatchContext;
import io.fabric8.kubernetes.client.dsl.base.PatchType;
import io.fabric8.kubernetes.client.utils.Serialization;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * corev1 Service 操作（模式同 CoreV1ConfigMapOperations）
 */
@Slf4j
@RequiredArgsConstructor
public class CoreV1ServiceOperations implements NamespacedOperations<ServiceDTO> {

    public final static String apiVersion = "v1";

    private final KubernetesClient client;

    private final CommonConverter<Service, ServiceDTO> converter;

    @Override
    public String apiVersion() {
        return apiVersion;
    }

    @Override
    public List<ServiceDTO> list(String namespace, String labelSelector, String fieldSelector) {
        String ns = StringUtils.hasText(namespace) ? namespace : "";
        ListOptions options = new ListOptions();
        if (StringUtils.hasText(labelSelector)) {
            options.setLabelSelector(labelSelector);
        }
        if (StringUtils.hasText(fieldSelector)) {
            options.setFieldSelector(fieldSelector);
        }
        List<Service> items = client.services().inNamespace(ns).list(options).getItems();
        return items.stream().map(converter::revert).toList();
    }

    /**
     * 跨全部命名空间列举（平台侧；本实例必须是 admin client，见 {@link NamespacedOperations#listAll}）。
     * <p>返回的每个 item 自带其所在 namespace —— converter 从 metadata 取，本方法<b>不做</b>统一回填。
     */
    @Override
    public List<ServiceDTO> listAll(String labelSelector, String fieldSelector) {
        ListOptions options = new ListOptions();
        if (StringUtils.hasText(labelSelector)) {
            options.setLabelSelector(labelSelector);
        }
        if (StringUtils.hasText(fieldSelector)) {
            options.setFieldSelector(fieldSelector);
        }
        List<Service> items = client.services().inAnyNamespace().list(options).getItems();
        return items.stream().map(converter::revert).toList();
    }

    @Override
    public ServiceDTO get(String namespace, String name) {
        if (!StringUtils.hasText(namespace) || !StringUtils.hasText(name)) {
            throw new CloudPlatformException(EnumResponseType.SEARCH_K8S_REQUIRE_NAME_AND_NAMESPACE);
        }
        Service service = client.services()
                .inNamespace(namespace)
                .withName(name)
                .get();
        return converter.revert(service);
    }

    @Override
    public ServiceDTO create(ServiceDTO service) {
        String namespace = service.getNamespace();
        String name = service.getName();
        if (checkExist(namespace, name)) {
            throw new CloudPlatformException(EnumResponseType.RESOURCE_EXIST);
        }
        Service in = converter.convert(service);

        Service created = client.services()
                .inNamespace(namespace)
                .resource(in)
                .create();
        return converter.revert(created);
    }

    @Override
    public ServiceDTO update(ServiceDTO service) {
        String namespace = service.getNamespace();
        String name = service.getName();
        if (!checkExist(namespace, name)) {
            throw new CloudPlatformException(EnumResponseType.RESOURCE_NOT_EXIST);
        }
        Service in = converter.convert(service);
        replaceStalePortsIfAny(namespace, name, in.getSpec() == null ? null : in.getSpec().getPorts());
        Service updated = client.services()
                .inNamespace(namespace)
                .resource(in)
                .fieldManager(ServerSideApply.FIELD_MANAGER).forceConflicts().serverSideApply();
        return converter.revert(updated);
    }

    /**
     * {@code spec.ports} 在 apiserver 里是按 {@code port} 合并的 map list
     * （{@code x-kubernetes-list-type: map, listMapKeys: [port]}）。SSA 的「省略即删除」
     * <b>只对本 manager 拥有的条目生效</b> —— 别的 manager 写下的条目不会被删掉。
     *
     * <p>而本平台的 {@link #create} 走的是普通 POST（fabric8 的 {@code create()} 不会在 URL 上带
     * {@code fieldManager}，见 {@code OperationSupport.handleCreate}），manager 落成
     * {@code fabric8-kubernetes-client}；Helm / kubectl 建的 Service 同理。于是把端口的
     * {@code port} 改号时，旧条目留在原地、新条目被加上，同一个 Service 出现两条同 nodePort、
     * 同名的端口，apiserver 报 {@code duplicate nodePort: {TCP 25358}} 或
     * {@code spec.ports[1].name: Duplicate value: "redis"}（2026-10-31 / 11-01 实测）。
     *
     * <p><b>不能靠「先按线上值 apply 一次把条目收归自己」</b>——试过了，无效：SSA 的 force 只在
     * <b>值冲突</b>时才转移归属；apply 的值和线上完全一样时对方仍然拥有该条目，条目就不会被后续
     * apply 删掉（本集群实测 managedFields 里 {@code fabric8-kubernetes-client} 依旧持有
     * {@code k:{"port":3306}}）。
     *
     * <p>所以改用 JSON merge patch：<b>merge patch 对数组是原子替换</b>，不看 field manager，
     * 一次就把 ports 换成期望的那几条。只替换 {@code spec.ports}，spec 里其它字段（含本 DTO 没建模的）
     * 与 metadata 原样保留。只在确实存在陈旧条目时才多打这一次，日常编辑没有额外开销。
     */
    private void replaceStalePortsIfAny(String namespace, String name, List<ServicePort> desired) {
        if (desired == null || desired.isEmpty()) {
            return; // ExternalName 等无端口场景：不碰线上端口
        }
        Service live = client.services().inNamespace(namespace).withName(name).get();
        if (live == null || live.getSpec() == null) {
            return;
        }
        List<ServicePort> livePorts = live.getSpec().getPorts();
        if (livePorts == null || livePorts.isEmpty() || !hasStalePorts(livePorts, desired)) {
            return;
        }
        Map<String, Object> patch = Map.of("spec", Map.of("ports", desired));
        client.services().inNamespace(namespace).withName(name)
                .patch(PatchContext.of(PatchType.JSON_MERGE), Serialization.asJson(patch));
    }

    /**
     * 线上端口里是否存在本次不再提交的条目。map list 的合并键是 {@code port} 本身
     * （不含协议），所以按端口号比。
     */
    static boolean hasStalePorts(List<ServicePort> live, List<ServicePort> desired) {
        Set<Integer> wanted = new HashSet<>();
        if (desired != null) {
            desired.forEach(p -> wanted.add(p.getPort()));
        }
        return live.stream().anyMatch(p -> !wanted.contains(p.getPort()));
    }

    public boolean checkExist(String namespace, String name) {
        Service existing = client.services().inNamespace(namespace).withName(name).get();
        return existing != null;
    }

    @Override
    public void delete(String namespace, String name) {
        if (!checkExist(namespace, name)) {
            throw new CloudPlatformException(EnumResponseType.RESOURCE_NOT_EXIST);
        }
        client.services().inNamespace(namespace)
                .withName(name)
                .delete();
    }

    @Override
    public String yaml(String namespace, String name) {
        if (!StringUtils.hasText(namespace) || !StringUtils.hasText(name)) {
            throw new CloudPlatformException(EnumResponseType.SEARCH_K8S_REQUIRE_NAME_AND_NAMESPACE);
        }
        Service service = client.services()
                .inNamespace(namespace)
                .withName(name)
                .get();
        if (service != null && service.getMetadata() != null) {
            service.getMetadata().setManagedFields(null);
        }
        return Serialization.asYaml(service);
    }

}
