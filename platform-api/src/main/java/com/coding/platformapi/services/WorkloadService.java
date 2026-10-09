package com.coding.platformapi.services;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.k8s.ServiceType;
import com.coding.common.models.k8s.dto.PodDTO;
import com.coding.common.models.k8s.dto.PodTemplateDTO;
import com.coding.common.models.k8s.dto.ServiceDTO;
import com.coding.common.models.k8s.dto.ServicePortDTO;
import com.coding.common.models.k8s.dto.WorkloadDTO;
import com.coding.platformapi.k8s.K8sClient;
import com.coding.platformapi.models.WorkloadMeshToggleRequest;
import com.coding.platformapi.services.validation.WorkloadValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 工作负载业务层：create/update 先跑 §5 硬约束，通过再透传 k8s-server；list/get 额外 join 同命名空间 Service，算出对外暴露端口。
 *  分层约定见 docs/development/backend-layering.md。
 *
 * <p>{@link #meshToggle} 引用 {@link GatewayService} 只为校验 waypoint 名是否存在（B3 §11），
 * 归属仍是"工作负载域"：Gateway 侧只借一个只读候选查询，不产生跨资源的写编排。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkloadService {

    /** 与 k8s-core CoreV1NamespaceConverter.LABEL_DATAPLANE_MODE / LABEL_USE_WAYPOINT 同值
     *  （platform-api 不依赖 k8s-core，故本地重复声明，改一处需同步） */
    static final String LABEL_DATAPLANE_MODE = "istio.io/dataplane-mode";
    static final String LABEL_USE_WAYPOINT = "istio.io/use-waypoint";
    /** use-waypoint 的"显式不使用"取值（关掉命名空间级 waypoint 的继承） */
    private static final String USE_WAYPOINT_NONE = "none";
    /** ztunnel 认的两个 ambient 取值：ambient 纳入、none 排除 */
    private static final List<String> DATAPLANE_MODES = List.of("ambient", "none");

    private final K8sClient k8s;
    private final WorkloadValidator validator;
    private final GatewayService gatewayService;

    /**查询 YAML（只读展示）：跨 kind 由 k8s-server 按 name 解析，本层纯组装 DTO */
    public String yaml(String name, String tenantId, String clusterId, String namespace) {
        return k8s.yaml(dto(name, tenantId, clusterId, namespace));
    }

    /**删除（跨 kind 查找）：无业务规则，纯组装 DTO 后透传 */
    public void delete(String name, String tenantId, String clusterId, String namespace) {
        k8s.delete(dto(name, tenantId, clusterId, namespace));
    }

    /**查询 DTO：apiPath 内置于 DTO */
    private WorkloadDTO dto(String name, String tenantId, String clusterId, String namespace) {
        WorkloadDTO d = new WorkloadDTO();
        d.setName(name);
        d.setTenantId(tenantId);
        d.setClusterId(clusterId);
        d.setNamespace(namespace);
        return d;
    }

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

    // ==================== ambient 开关（B3 §11.3，列表快捷开关 + 编辑器模块共用） ====================

    /**
     * 只翻转 pod template 的两个 istio 保留 label（{@code istio.io/dataplane-mode} / {@code istio.io/use-waypoint}），
     * 其余字段一律取线上现值回写。三态语义见 {@link WorkloadMeshToggleRequest} 的类注释。
     *
     * <h2>为什么单独开一个端点，而不让前端走整对象 update</h2>
     * 列表页的开关只该改这两个 label，却要先把整个工作负载读回来、再整份提交 —— 任何一个用户看不见的字段
     * 在往返中被改写，都是拿"点一下开关"当借口动了别人的工作负载。放服务端做，读-改-写在一个事务性的调用里，
     * 前端连完整对象都不必持有。
     *
     * <h2>为什么不跑 §5 硬约束校验</h2>
     * {@link WorkloadValidator} 校验的是"工作负载规格该长什么样"，是 create/update 的入口规则。本操作既不改规格
     * 也不改结构，只加/删两个 label —— 让一个集群里已经跑着的、但不符合平台规格约束（例如 kubectl 直接建的）
     * 工作负载因为"开关 ambient"而被拒，是把校验用错了地方。规格本身不受影响：读回来什么样，回写就什么样。
     * <b>副作用是已知且有意的</b>：这类工作负载仍无法通过本平台编辑（update 照旧校验），但能把 ambient 打开。
     *
     * <h2>滚动更新</h2>
     * 改的是 pod template → 控制器会滚一次新 ReplicaSet，存量 Pod 要重建后才带新 label。前端开关旁须提示。
     */
    public WorkloadDTO meshToggle(WorkloadMeshToggleRequest req) {
        if (req.getDataplaneMode() == null && req.getUseWaypoint() == null) {
            throw new CloudPlatformException(EnumResponseType.BEAN_VALIDATION_EXCEPTION,
                    "dataplaneMode 与 useWaypoint 至少要传一个（null = 不动，空串 = 移除该 label）");
        }
        requireDataplaneMode(req.getDataplaneMode());
        requireWaypointExists(req);

        WorkloadDTO query = dto(req.getName(), req.getTenantId(), req.getClusterId(), req.getNamespace());
        query.setKind(req.getKind()); // 可不传：k8s-server 按 name 跨 kind 查找
        WorkloadDTO existing = k8s.get(query);
        if (existing == null) {
            throw new CloudPlatformException(EnumResponseType.RESOURCE_NOT_EXIST, "工作负载不存在: " + req.getName());
        }
        if (existing.getPodTemplate() == null) {
            existing.setPodTemplate(new PodTemplateDTO());
        }
        Map<String, String> labels = existing.getPodTemplate().getLabels() == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(existing.getPodTemplate().getLabels());
        // 规整掉取消开关时留下的空值：pod template 里挂一个空串 label 是无效/无意义的元数据
        labels.values().removeIf(v -> v == null || v.isBlank());
        applyMeshLabel(labels, LABEL_DATAPLANE_MODE, req.getDataplaneMode());
        applyMeshLabel(labels, LABEL_USE_WAYPOINT, req.getUseWaypoint());
        existing.getPodTemplate().setLabels(labels.isEmpty() ? null : labels);
        return k8s.update(existing);
    }

    /** null=不动；空串=移除；有值=覆写（去空格） */
    private static void applyMeshLabel(Map<String, String> labels, String key, String value) {
        if (value == null) return;
        if (value.isBlank()) {
            labels.remove(key);
        } else {
            labels.put(key, value.trim());
        }
    }

    /** dataplane-mode 只认 Istio 的两个取值（null=不动 / 空串=移除 都放行） */
    private static void requireDataplaneMode(String mode) {
        if (mode == null || mode.isBlank()) return;
        if (!DATAPLANE_MODES.contains(mode.trim())) {
            throw new CloudPlatformException(EnumResponseType.BEAN_VALIDATION_EXCEPTION,
                    "dataplane-mode 取值仅支持 " + String.join(" / ", DATAPLANE_MODES) + "，收到: " + mode);
        }
    }

    /**
     * use-waypoint 指定了具体 waypoint 名时，确认该 waypoint Gateway 确实存在于本命名空间 ——
     * 否则标签会被 Istio <b>静默忽略</b>（waypoint 不存在时 ztunnel 直接放行），用户以为 L7 生效了其实没有。
     * <p>{@code none} / 空 / null 不校验。查询本身失败（集群断开、租户 SA 的 K8s RBAC 未覆盖 gateway group）
     * 则<b>放行并记日志</b>：这是一次可随时改回的 label 写入，非破坏性操作，不该因为"查不到候选"而卡死；
     * 前端本就从下拉里选，写错的空间很小。
     */
    private void requireWaypointExists(WorkloadMeshToggleRequest req) {
        String waypoint = req.getUseWaypoint();
        if (waypoint == null || waypoint.isBlank() || USE_WAYPOINT_NONE.equals(waypoint.trim())) {
            return;
        }
        List<String> candidates;
        try {
            candidates = gatewayService.waypointNames(req.getTenantId(), req.getClusterId(), req.getNamespace());
        } catch (Exception e) {
            log.warn("校验 waypoint「{}」存在性失败（放行）：{}", waypoint, e.getMessage());
            return;
        }
        if (!candidates.contains(waypoint.trim())) {
            throw new CloudPlatformException(EnumResponseType.BEAN_VALIDATION_EXCEPTION,
                    "命名空间「" + req.getNamespace() + "」下不存在 waypoint Gateway「" + waypoint
                            + "」" + (candidates.isEmpty() ? "（该命名空间还没有 waypoint Gateway）" : "，现有：" + String.join("、", candidates)));
        }
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
