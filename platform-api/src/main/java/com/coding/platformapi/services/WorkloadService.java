package com.coding.platformapi.services;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.k8s.ServiceType;
import com.coding.common.models.k8s.dto.ContainerDTO;
import com.coding.common.models.k8s.dto.EmptyDirVolumeDTO;
import com.coding.common.models.k8s.dto.EnvDTO;
import com.coding.common.models.k8s.dto.PodDTO;
import com.coding.common.models.k8s.dto.PodSpecDTO;
import com.coding.common.models.k8s.dto.PodTemplateDTO;
import com.coding.common.models.k8s.dto.ServiceDTO;
import com.coding.common.models.k8s.dto.ServicePortDTO;
import com.coding.common.models.k8s.dto.VolumeDTO;
import com.coding.common.models.k8s.dto.VolumeMountDTO;
import com.coding.common.models.k8s.dto.WaypointRefDTO;
import com.coding.common.models.k8s.dto.WorkloadDTO;
import com.coding.common.models.k8s.dto.WorkloadRevisionDTO;
import com.coding.platformapi.k8s.K8sClient;
import com.coding.platformapi.k8s.K8sWorkloadClient;
import com.coding.platformapi.models.WorkloadImageRequest;
import com.coding.platformapi.models.WorkloadMeshToggleRequest;
import com.coding.platformapi.models.WorkloadObservabilityRequest;
import com.coding.platformapi.models.WorkloadRollbackRequest;
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

    // ==================== OTel 可观测接入（批次一） ====================

    /** 注入注解前缀；第一批只支持 java / python */
    private static final String OTEL_INJECT_PREFIX = "instrumentation.opentelemetry.io/inject-";
    private static final Map<String, String> OTEL_INJECT_KEYS = Map.of(
            "java", OTEL_INJECT_PREFIX + "java",
            "python", OTEL_INJECT_PREFIX + "python");
    private static final String OTEL_SERVICE_NAME_ENV = "OTEL_SERVICE_NAME";
    /** Instrumentation 所在命名空间（平台固定） */
    private static final String OTEL_NAMESPACE = "monitor";
    /** 文件日志：sidecar 注入注解与平台固定卷名（与 OtelObjects 的 sidecar CR 一致） */
    private static final String OTEL_SIDECAR_ANNOTATION = "sidecar.opentelemetry.io/inject";
    private static final String OTEL_SIDECAR_VALUE = OTEL_NAMESPACE + "/platform-otel-sidecar";
    private static final String OTEL_LOG_VOLUME = "platform-otel-logs";
    private static final String LOG_MODE_FILE = "file";

    private final K8sClient k8s;
    private final K8sWorkloadClient workloadClient;
    private final WorkloadValidator validator;
    private final GatewayService gatewayService;

    // ==================== 列表页行内动作（改镜像 / 历史版本 / 回滚）====================
    //
    // 三个动作的 K8s 语义都在 k8s-core（改一个字段 / 归一版本 / 覆盖 pod 模板），本层只做请求形态校验。
    // 容器是否真的存在由 k8s-server 侧判（它读的是线上对象，才是权威）—— 同一规则不在两处各写一份。

    /**
     * 改一个容器的镜像（直接发版：改的是 pod template，控制器会滚一次）。
     * <p>空白校验在本层：这些请求发出去必然失败（K8s 会拒或拉不到镜像），且都改的是别人的工作负载。
     * <p>有意<b>不</b>跑 §5 校验，理由同 {@link #meshToggle}：这不是规格提交，只改一个字段。
     */
    public WorkloadDTO updateImage(String name, WorkloadImageRequest req) {
        if (!StringUtils.hasText(req.getContainerName())) {
            throw new CloudPlatformException(EnumResponseType.BEAN_VALIDATION_EXCEPTION, "容器名不能为空");
        }
        if (!StringUtils.hasText(req.getImage())) {
            throw new CloudPlatformException(EnumResponseType.BEAN_VALIDATION_EXCEPTION, "镜像不能为空");
        }
        return workloadClient.updateImage(name, req.getTenantId(), req.getClusterId(), req.getNamespace(),
                req.getContainerName().trim(), req.getImage().trim());
    }

    /**历史版本列表（新→旧），纯透传：Deployment 取 ReplicaSet、STS/DS 取 ControllerRevision 的归一在 k8s-core */
    public List<WorkloadRevisionDTO> revisions(String name, String tenantId, String clusterId, String namespace) {
        return workloadClient.revisions(name, tenantId, clusterId, namespace);
    }

    /**回滚到指定版本，纯透传（版本是否存在由 k8s-server 判，它才看得到集群里的历史） */
    public WorkloadDTO rollback(String name, WorkloadRollbackRequest req) {
        if (req.getRevision() == null) {
            throw new CloudPlatformException(EnumResponseType.BEAN_VALIDATION_EXCEPTION, "必须指定要回滚到的版本号");
        }
        return workloadClient.rollback(name, req.getTenantId(), req.getClusterId(), req.getNamespace(), req.getRevision());
    }

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
        requireWaypointTargetable(req);

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

    // ==================== OTel 可观测接入（批次一） ====================

    /**
     * 打开/关闭工作负载的 OTel 接入。只改 PodTemplate 的注入注解与主容器的 {@code OTEL_SERVICE_NAME}，
     * 其余字段取线上现值回写（同 {@link #meshToggle} 的道理：不拿一个开关当借口改动别人的工作负载）。
     *
     * <p>语言/组合 → Instrumentation：只追踪 {@code platform-instrumentation}、
     * 只日志 {@code platform-instrumentation-logs}、两者都要 {@code platform-instrumentation-all}。
     * 切换语言时会先移除所有语言注解，不会残留上一个运行时的注入。
     *
     * <p>关闭（tracing/logs 都为 false）只移除平台管的注入注解；用户自己的
     * {@code OTEL_SERVICE_NAME} 等字段不动。
     */
    public WorkloadDTO observability(WorkloadObservabilityRequest req) {
        boolean fileLogs = req.isLogs() && LOG_MODE_FILE.equalsIgnoreCase(req.getLogMode());
        // 应用日志（agent appender）才需要 Instrumentation；文件日志靠 sidecar 注入
        boolean agentEnabled = req.isTracing() || (req.isLogs() && !fileLogs);
        String language = req.getLanguage() == null ? null : req.getLanguage().trim().toLowerCase();
        if (agentEnabled && !OTEL_INJECT_KEYS.containsKey(language)) {
            throw new CloudPlatformException(EnumResponseType.BEAN_VALIDATION_EXCEPTION,
                    "不支持的应用类型：" + req.getLanguage() + "（第一批只支持 java / python）");
        }
        if (fileLogs) {
            if (!req.isConfirmFileLogs()) {
                throw new CloudPlatformException(EnumResponseType.BEAN_VALIDATION_EXCEPTION,
                        "请勾选“确认应用把日志写入该目录”（sidecar 读不到容器 stdout）");
            }
            if (!StringUtils.hasText(req.getLogDirectory())
                    || !req.getLogDirectory().trim().startsWith("/")) {
                throw new CloudPlatformException(EnumResponseType.BEAN_VALIDATION_EXCEPTION,
                        "日志目录必须是容器内的绝对路径（以 / 开头）");
            }
        }

        WorkloadDTO query = dto(req.getName(), req.getTenantId(), req.getClusterId(), req.getNamespace());
        query.setKind(req.getKind());
        WorkloadDTO existing = k8s.get(query);
        if (existing == null) {
            throw new CloudPlatformException(EnumResponseType.RESOURCE_NOT_EXIST, "工作负载不存在: " + req.getName());
        }
        if (existing.getPodTemplate() == null) {
            existing.setPodTemplate(new PodTemplateDTO());
        }
        Map<String, Object> annotations = existing.getPodTemplate().getAnnotations() == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(existing.getPodTemplate().getAnnotations());
        // 先清掉所有语言的注入注解：切换语言 / 关闭时都不残留
        OTEL_INJECT_KEYS.values().forEach(annotations::remove);
        if (agentEnabled) {
            // 文件日志走 sidecar 的 filelog，不开应用日志 appender；此时即使 logs=true 也只选 tracing 版
            String instrumentation = req.isTracing() && req.isLogs() && !fileLogs
                    ? "platform-instrumentation-all"
                    : req.isTracing() ? "platform-instrumentation" : "platform-instrumentation-logs";
            annotations.put(OTEL_INJECT_KEYS.get(language),
                    OTEL_NAMESPACE + "/" + instrumentation);
        }
        // 文件日志：sidecar 注入注解；切回应用日志/关闭时只移除**平台自己的**注解值
        if (fileLogs) {
            annotations.put(OTEL_SIDECAR_ANNOTATION, OTEL_SIDECAR_VALUE);
        } else if (OTEL_SIDECAR_VALUE.equals(annotations.get(OTEL_SIDECAR_ANNOTATION))) {
            annotations.remove(OTEL_SIDECAR_ANNOTATION);
        }
        existing.getPodTemplate().setAnnotations(annotations.isEmpty() ? null : annotations);

        if (fileLogs) {
            ContainerDTO container = targetContainer(existing, req.getContainerName());
            ensureLogVolume(existing.getPodTemplate());
            ensureLogMount(container, req.getLogDirectory().trim());
        } else {
            removeLogVolumeAndMounts(existing.getPodTemplate());
        }

        if (agentEnabled) {
            String serviceName = StringUtils.hasText(req.getServiceName())
                    ? req.getServiceName().trim() : req.getName();
            setServiceNameEnv(existing, req.getContainerName(), serviceName);
        }
        return k8s.update(existing);
    }

    /** 主容器（containerName 空 = 第一个）；不存在就明确报错 */
    private static ContainerDTO targetContainer(WorkloadDTO workload, String containerName) {
        PodSpecDTO spec = workload.getPodTemplate().getSpec();
        if (spec == null || spec.getContainers() == null || spec.getContainers().isEmpty()) {
            throw new CloudPlatformException(EnumResponseType.BEAN_VALIDATION_EXCEPTION,
                    "工作负载没有容器，无法写入可观测配置");
        }
        if (StringUtils.hasText(containerName)) {
            return spec.getContainers().stream()
                    .filter(c -> containerName.trim().equals(c.getName()))
                    .findFirst()
                    .orElseThrow(() -> new CloudPlatformException(
                            EnumResponseType.BEAN_VALIDATION_EXCEPTION, "容器不存在：" + containerName));
        }
        return spec.getContainers().get(0);
    }

    /** 把 OTEL_SERVICE_NAME 写到指定容器（空 = 第一个）；同名 env 覆盖，不重复追加 */
    private static void setServiceNameEnv(WorkloadDTO workload, String containerName, String value) {
        ContainerDTO container = targetContainer(workload, containerName);
        List<EnvDTO> envs = container.getEnvs() == null
                ? new ArrayList<>() : new ArrayList<>(container.getEnvs());
        EnvDTO target = envs.stream()
                .filter(e -> OTEL_SERVICE_NAME_ENV.equals(e.getName()))
                .findFirst().orElse(null);
        if (target == null) {
            target = new EnvDTO();
            target.setName(OTEL_SERVICE_NAME_ENV);
            envs.add(target);
        }
        // 平台写入的是字面值：若用户原来用 valueFrom 提供同名变量，这里改成字面值
        target.setValue(value);
        target.setValueFrom(null);
        container.setEnvs(envs);
    }

    /** 文件日志的平台卷（emptyDir）；同名卷不是 emptyDir → 拒绝，避免覆盖用户卷 */
    private static void ensureLogVolume(PodTemplateDTO podTemplate) {
        PodSpecDTO spec = podTemplate.getSpec();
        if (spec == null) {
            throw new CloudPlatformException(EnumResponseType.BEAN_VALIDATION_EXCEPTION,
                    "工作负载没有 Pod 规格，无法接入文件日志");
        }
        List<VolumeDTO> volumes = spec.getVolumes() == null
                ? new ArrayList<>() : new ArrayList<>(spec.getVolumes());
        VolumeDTO hit = volumes.stream()
                .filter(v -> OTEL_LOG_VOLUME.equals(v.getName()))
                .findFirst().orElse(null);
        if (hit != null && !"emptyDir".equalsIgnoreCase(hit.getType())) {
            throw new CloudPlatformException(EnumResponseType.BEAN_VALIDATION_EXCEPTION,
                    "工作负载已有同名卷 " + OTEL_LOG_VOLUME + "（不是 emptyDir），无法接入文件日志");
        }
        if (hit == null) {
            VolumeDTO volume = new VolumeDTO();
            volume.setName(OTEL_LOG_VOLUME);
            volume.setType("emptyDir");
            volume.setEmptyDir(new EmptyDirVolumeDTO());
            volumes.add(volume);
        }
        spec.setVolumes(volumes);
    }

    private static void ensureLogMount(ContainerDTO container, String directory) {
        List<VolumeMountDTO> mounts = container.getVolumeMounts() == null
                ? new ArrayList<>() : new ArrayList<>(container.getVolumeMounts());
        VolumeMountDTO hit = mounts.stream()
                .filter(m -> OTEL_LOG_VOLUME.equals(m.getName()))
                .findFirst().orElse(null);
        if (hit == null) {
            hit = new VolumeMountDTO();
            hit.setName(OTEL_LOG_VOLUME);
            mounts.add(hit);
        }
        hit.setMountPath(directory);
        hit.setReadOnly(false);
        container.setVolumeMounts(mounts);
    }

    /** 切回应用日志/关闭：移除平台卷与所有容器上的同名挂载（用户同名卷在 ensure 时会拒绝，不会走到这里） */
    private static void removeLogVolumeAndMounts(PodTemplateDTO podTemplate) {
        PodSpecDTO spec = podTemplate.getSpec();
        if (spec == null) {
            return;
        }
        if (spec.getVolumes() != null) {
            List<VolumeDTO> volumes = spec.getVolumes().stream()
                    .filter(v -> !OTEL_LOG_VOLUME.equals(v.getName())).toList();
            spec.setVolumes(volumes.isEmpty() ? null : volumes);
        }
        List<ContainerDTO> all = new ArrayList<>();
        if (spec.getContainers() != null) {
            all.addAll(spec.getContainers());
        }
        if (spec.getInitContainers() != null) {
            all.addAll(spec.getInitContainers());
        }
        for (ContainerDTO c : all) {
            if (c.getVolumeMounts() == null) {
                continue;
            }
            List<VolumeMountDTO> mounts = c.getVolumeMounts().stream()
                    .filter(m -> !OTEL_LOG_VOLUME.equals(m.getName())).toList();
            c.setVolumeMounts(mounts.isEmpty() ? null : mounts);
        }
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
     * use-waypoint 指定了具体 waypoint 名时，确认它<b>存在</b>且<b>能处理 Pod 直连流量</b> —— 两条都是
     * istio 静默失败的来源，用户以为 L7 生效了其实没有：
     * <ul>
     *   <li><b>不存在</b>：ztunnel 直接放行（官方：「指定的 waypoint 不存在或没有地址」→ 直接路由到目的地）</li>
     *   <li><b>类型不匹配</b>：Pod 上的 {@code use-waypoint} 只影响「最初目标是 Pod/VM IP」的流量，
     *       要求 waypoint 的 {@code istio.io/waypoint-for} 是 {@code workload} 或 {@code all}；
     *       平台建 waypoint 时的默认值恰是 {@code service}（官方默认），所以这个组合最容易踩</li>
     * </ul>
     * <p>{@code none} / 空 / null 不校验。查询本身失败（集群断开、租户 SA 的 K8s RBAC 未覆盖 gateway group）
     * 则<b>放行并记日志</b>：这是一次可随时改回的 label 写入，非破坏性操作，不该因为"查不到候选"而卡死；
     * 前端本就从下拉里选，写错的空间很小。<b>但查得到就必须拦住</b> —— 这两条错误用户从界面上看不出来。
     */
    private void requireWaypointTargetable(WorkloadMeshToggleRequest req) {
        String waypoint = req.getUseWaypoint();
        if (waypoint == null || waypoint.isBlank() || USE_WAYPOINT_NONE.equals(waypoint.trim())) {
            return;
        }
        String name = waypoint.trim();
        List<WaypointRefDTO> candidates;
        try {
            candidates = gatewayService.waypointRefs(req.getTenantId(), req.getClusterId(), req.getNamespace());
        } catch (Exception e) {
            log.warn("校验 waypoint「{}」失败（放行）：{}", name, e.getMessage());
            return;
        }
        WaypointRefDTO hit = candidates.stream().filter(r -> name.equals(r.getName())).findFirst().orElse(null);
        if (hit == null) {
            throw new CloudPlatformException(EnumResponseType.BEAN_VALIDATION_EXCEPTION,
                    "命名空间「" + req.getNamespace() + "」下不存在 waypoint Gateway「" + name + "」"
                            + (candidates.isEmpty() ? "（该命名空间还没有 waypoint Gateway）"
                            : "，现有：" + candidates.stream().map(WaypointRefDTO::getName).collect(Collectors.joining("、"))));
        }
        if (!GatewayService.canHandleWorkload(hit.getWaypointFor())) {
            throw new CloudPlatformException(EnumResponseType.BEAN_VALIDATION_EXCEPTION,
                    "waypoint「" + name + "」的 " + GatewayService.WAYPOINT_FOR_LABEL + " 是「" + hit.getWaypointFor()
                            + "」—— 它只处理发往服务的流量，Pod 上的 use-waypoint 不会生效（istio 会静默放行、"
                            + "L7 策略不执行）。要让工作负载级 L7 生效，该 waypoint 的类型必须是 workload 或 all；"
                            + "若目标是让整个命名空间的服务流量走 L7，请在「命名空间」页设置 use-waypoint。");
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
