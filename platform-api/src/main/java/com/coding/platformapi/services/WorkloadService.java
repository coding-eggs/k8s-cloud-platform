package com.coding.platformapi.services;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.k8s.ServiceType;
import com.coding.common.models.k8s.dto.PodDTO;
import com.coding.common.models.k8s.dto.ServiceDTO;
import com.coding.common.models.k8s.dto.ServicePortDTO;
import com.coding.common.models.k8s.dto.WorkloadDTO;
import com.coding.platformapi.k8s.K8sResourceClient;
import com.coding.platformapi.services.validation.WorkloadValidator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**工作负载业务层：create/update 先跑 §5 硬约束，通过再透传 k8s-server；list/get 额外 join 同命名空间 Service，算出对外暴露端口 */
@Service
@RequiredArgsConstructor
public class WorkloadService {

    private final K8sResourceClient k8s;
    private final WorkloadValidator validator;

    /**列出工作负载（三种 kind 合并）并填充各自对外暴露的 NodePort/LB Service 端口 */
    public List<WorkloadDTO> list(WorkloadDTO query) {
        List<WorkloadDTO> workloads = k8s.list(query);

        //查询nodePort 和 loadbalancer 的svc列表
        ServiceDTO svc = new ServiceDTO();
        svc.setTenantId(query.getTenantId());
        svc.setClusterId(query.getClusterId());
        svc.setNamespace(query.getNamespace());
        svc.setFieldSelector("spec.type="+ ServiceType.NodePort.getType());

        List<ServiceDTO> nodePortList = k8s.list(svc);
        svc.setFieldSelector("spec.type="+ ServiceType.LoadBalancer.getType());
        List<ServiceDTO> loadBalancerList = k8s.list(svc);

        //svcList
        List<ServiceDTO> serviceList = Stream.concat(nodePortList.stream(), loadBalancerList.stream())
                .toList();

        for (WorkloadDTO w : workloads) {
            w.setExposedServices(exposeFor(w, serviceList));
        }
        return workloads;
    }

    /**查询单个工作负载（跨 kind）并填充对外暴露端口 */
    public WorkloadDTO get(WorkloadDTO query) {
        WorkloadDTO w = k8s.get(query);
        if (w == null) {
            return null;
        }
        ServiceDTO q = new ServiceDTO();
        q.setTenantId(query.getTenantId());
        q.setClusterId(query.getClusterId());
        q.setNamespace(query.getNamespace());

        w.setExposedServices(exposeFor(w, k8s.list(q)));
        return w;
    }

    /**
     * 计算某工作负载对外暴露的 Service：类型须为 NodePort/LB，且 service.selector 命中该工作负载的任一真实 Pod。
     * Pod 归属 = 工作负载 matchLabels ⊆ pod.labels（等价于按 matchLabels 做 labelSelector 查询），取 Pod 实际 labels 匹配，
     * 而非读 podTemplate。只保留已分配 nodePort 的端口。
     */
    private List<WorkloadDTO.ExposedService> exposeFor(WorkloadDTO w, List<ServiceDTO> services) {
        Map<String, String> matchLabels = w.getSelector();
        if (matchLabels == null || matchLabels.isEmpty() || services == null) {
            return Collections.emptyList();
        }

        List<WorkloadDTO.ExposedService> out = new ArrayList<>();
        for (ServiceDTO s : services) {
            if (!selectorMatches(s.getSelector(), w.getSelector())) {
                continue;
            }
            List<WorkloadDTO.ExposedPort> ports = new ArrayList<>();
            if (s.getPorts() != null) {
                for (ServicePortDTO p : s.getPorts()) {
                    if (p.getPort() != null ) {
                        WorkloadDTO.ExposedPort ep = new WorkloadDTO.ExposedPort();
                        ep.setPort(p.getPort());
                        ep.setNodePort(p.getNodePort());
                        ports.add(ep);
                    }
                }
            }
            if (ports.isEmpty()) {
                continue;
            }
            WorkloadDTO.ExposedService es = new WorkloadDTO.ExposedService();
            es.setName(s.getName());
            es.setType(s.getType());
            es.setPorts(ports);
            out.add(es);
        }
        return out;
    }

    /** required ⊆ actual（每个 k=v 都相等）；空 required 视为不匹配 */
    private boolean selectorMatches(Map<String, String> required, Map<String, String> actual) {
        if (required == null || required.isEmpty()) {
            return false;
        }
        for (Map.Entry<String, String> e : required.entrySet()) {
            if (!e.getValue().equals(actual.get(e.getKey()))) {
                return false;
            }
        }
        return true;
    }



    public WorkloadDTO create(WorkloadDTO dto) {
        validator.validate(dto);
        return k8s.create(dto);
    }

    public WorkloadDTO update(WorkloadDTO dto) {
        if (dto.getPodTemplate() == null) graftExistingSpec(dto); // 仅伸缩快捷（无 Pod 模板）：先取现有规格，否则校验会以「缺少 Pod 模板」拒绝
        validator.validate(dto);
        return k8s.update(dto);
    }

    /**暂停/恢复 Deployment 更新（spec.paused）。编排在本层：取完整对象 → 翻 paused → 走 update（保留 replicas/minReadySeconds 等，不误缩放）。仅 Deployment 支持。 */
    public WorkloadDTO pause(WorkloadDTO query) {
        WorkloadDTO existing = k8s.get(query);
        if (existing == null) {
            throw new CloudPlatformException(EnumResponseType.RESOURCE_NOT_EXIST, "工作负载不存在: " + query.getName());
        }
        if (!"deployment".equalsIgnoreCase(existing.getKind())) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "仅 Deployment 支持暂停/恢复更新");
        }
        existing.setPaused(Boolean.TRUE.equals(query.getPaused()));
        return update(existing);
    }

    /**仅伸缩请求（列表页伸缩快捷：只带 kind/name/namespace/replicas）：取现有资源并把完整规格嫁接到请求上，
     * 保留请求的身份字段与所请求的 replicas；资源不存在则抛 not-found。serviceName 由 k8s-server 侧沿用 existing，无需嫁接 */
    private void graftExistingSpec(WorkloadDTO dto) {
        WorkloadDTO existing = k8s.get(dto);
        if (existing == null) {
            throw new CloudPlatformException(EnumResponseType.RESOURCE_NOT_EXIST, "工作负载不存在: " + dto.getName());
        }
        dto.setPodTemplate(existing.getPodTemplate());
        // strategy / volumeClaimTemplates 同样被校验读取，且 k8s-server 整体替换时缺了会丢：请求未带则沿用 existing
        if (dto.getStrategy() == null) dto.setStrategy(existing.getStrategy());
        if (dto.getVolumeClaimTemplates() == null) dto.setVolumeClaimTemplates(existing.getVolumeClaimTemplates());
    }
}
