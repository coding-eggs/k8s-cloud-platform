package com.coding.k8score.operations.workload;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.k8s.dto.WorkloadDTO;
import com.coding.common.models.k8s.dto.WorkloadRevisionDTO;
import com.coding.k8score.converter.impl.workload.WorkloadConverter;
import com.coding.k8score.operations.NamespacedOperations;
import com.coding.k8score.operations.ServerSideApply;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.LabelSelector;
import io.fabric8.kubernetes.api.model.ListOptions;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.OwnerReference;
import io.fabric8.kubernetes.api.model.PodSpec;
import io.fabric8.kubernetes.api.model.PodTemplateSpec;
import io.fabric8.kubernetes.api.model.apps.ControllerRevision;
import io.fabric8.kubernetes.api.model.apps.DaemonSet;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.ReplicaSet;
import io.fabric8.kubernetes.api.model.apps.StatefulSet;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.utils.Serialization;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.NavigableMap;
import java.util.TreeMap;

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
    public List<WorkloadDTO> list(String namespace, String labelSelector, String fieldSelector) {
        String ns = StringUtils.hasText(namespace) ? namespace : "";
        ListOptions options = new ListOptions();
        if (StringUtils.hasText(labelSelector)) {
            options.setLabelSelector(labelSelector);
        }
        if (StringUtils.hasText(fieldSelector)) {
            options.setFieldSelector(fieldSelector);
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

    /**
     * 跨全部命名空间列举（平台侧；本实例必须是 admin client，见 {@link NamespacedOperations#listAll}）。
     * <p>三族（Deployment / StatefulSet / DaemonSet）一次列全，合并成一个 List —— 与 {@link #list} 同形，
     * 只是把 inNamespace(ns) 换成 inAnyNamespace()。
     * <p>返回的每个 item 自带其所在 namespace —— converter 从 metadata 取，本方法<b>不做</b>统一回填。
     */
    @Override
    public List<WorkloadDTO> listAll(String labelSelector, String fieldSelector) {
        ListOptions options = new ListOptions();
        if (StringUtils.hasText(labelSelector)) {
            options.setLabelSelector(labelSelector);
        }
        if (StringUtils.hasText(fieldSelector)) {
            options.setFieldSelector(fieldSelector);
        }
        List<WorkloadDTO> result = new ArrayList<>();
        client.apps().deployments().inAnyNamespace().list(options).getItems()
                .forEach(d -> result.add(converter.revert(d)));
        client.apps().statefulSets().inAnyNamespace().list(options).getItems()
                .forEach(s -> result.add(converter.revert(s)));
        client.apps().daemonSets().inAnyNamespace().list(options).getItems()
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
                        .fieldManager(ServerSideApply.FIELD_MANAGER).forceConflicts().serverSideApply();
                return converter.revert(created);
            }
            case WorkloadConverter.KIND_STATEFULSET -> {
                if (client.apps().statefulSets().inNamespace(namespace).withName(name).get() != null) {
                    throw new CloudPlatformException(EnumResponseType.RESOURCE_EXIST);
                }
                StatefulSet created = client.apps().statefulSets()
                        .inNamespace(namespace)
                        .resource(converter.convertStatefulSet(workload))
                        .fieldManager(ServerSideApply.FIELD_MANAGER).forceConflicts().serverSideApply();
                return converter.revert(created);
            }
            default -> {
                if (client.apps().daemonSets().inNamespace(namespace).withName(name).get() != null) {
                    throw new CloudPlatformException(EnumResponseType.RESOURCE_EXIST);
                }
                DaemonSet created = client.apps().daemonSets()
                        .inNamespace(namespace)
                        .resource(converter.convertDaemonSet(workload))
                        .fieldManager(ServerSideApply.FIELD_MANAGER).forceConflicts().serverSideApply();
                return converter.revert(created);
            }
        }
    }

    /**
     * 更新工作负载：converter 重建完整 spec 后整体替换，保留不可变身份（name/namespace + spec.selector；
     * StatefulSet 另保留 serviceName）。DaemonSet 无副本概念，忽略 replicas。
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
                Deployment updated = converter.convertDeployment(workload);
                LabelSelector existingSelector = existing.getSpec() != null ? existing.getSpec().getSelector() : null;
                //selector 创建后不可变，沿用 existing 的 name/namespace/selector
                updated.getMetadata().setName(existing.getMetadata().getName());
                updated.getMetadata().setNamespace(existing.getMetadata().getNamespace());
                if (existingSelector != null) {
                    updated.getSpec().setSelector(existingSelector);
                }
                return converter.revert(client.apps().deployments().inNamespace(namespace).resource(updated).fieldManager(ServerSideApply.FIELD_MANAGER).forceConflicts().serverSideApply());
            }
            case WorkloadConverter.KIND_STATEFULSET -> {
                StatefulSet existing = client.apps().statefulSets().inNamespace(namespace).withName(name).get();
                if (existing == null) {
                    throw new CloudPlatformException(EnumResponseType.RESOURCE_NOT_EXIST);
                }
                StatefulSet updated = converter.convertStatefulSet(workload);
                LabelSelector existingSelector = existing.getSpec() != null ? existing.getSpec().getSelector() : null;
                //selector 创建后不可变，沿用 existing 的 name/namespace/selector
                updated.getMetadata().setName(existing.getMetadata().getName());
                updated.getMetadata().setNamespace(existing.getMetadata().getNamespace());
                if (existingSelector != null) {
                    updated.getSpec().setSelector(existingSelector);
                }
                //serviceName 不可变：existing 有则沿用，避免用户未改时被重置为 name
                if (existing.getSpec() != null && StringUtils.hasText(existing.getSpec().getServiceName())) {
                    updated.getSpec().setServiceName(existing.getSpec().getServiceName());
                }
                return converter.revert(client.apps().statefulSets().inNamespace(namespace).resource(updated).fieldManager(ServerSideApply.FIELD_MANAGER).forceConflicts().serverSideApply());
            }
            default -> {
                DaemonSet existing = client.apps().daemonSets().inNamespace(namespace).withName(name).get();
                if (existing == null) {
                    throw new CloudPlatformException(EnumResponseType.RESOURCE_NOT_EXIST);
                }
                DaemonSet updated = converter.convertDaemonSet(workload);
                LabelSelector existingSelector = existing.getSpec() != null ? existing.getSpec().getSelector() : null;
                //沿用 existing 的 name/namespace/selector（DaemonSet selector 同样不可变）
                updated.getMetadata().setName(existing.getMetadata().getName());
                updated.getMetadata().setNamespace(existing.getMetadata().getNamespace());
                if (existingSelector != null) {
                    updated.getSpec().setSelector(existingSelector);
                }
                return converter.revert(client.apps().daemonSets().inNamespace(namespace).resource(updated).fieldManager(ServerSideApply.FIELD_MANAGER).forceConflicts().serverSideApply());
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

    // ==================== 列表页行内动作：改镜像 / 历史版本 / 回滚 ====================
    //
    // 这三个动作都走同一条写路径：**读回线上 typed 对象 → 改内存里的字段 → SSA 整体写回**。
    // 为什么不像 update() 那样走 converter 重建：converter 只产出平台建模过的字段（PodSpecDTO 只有 11 个字段），
    // 拿它整份重建会把 securityContext / terminationGracePeriodSeconds 这类未建模字段抹掉 —— 用户点的是
    // 「改镜像」或「回滚到第 3 版」，不是「把这份工作负载按平台的口味重写一遍」。
    // 起点是线上对象，则"没动过的字段"天然等于线上值，SSA 也不存在"省略即删除"的问题。

    /** Deployment 的版本注解（Deployment 自身与它名下的 ReplicaSet 都挂这个键） */
    static final String REVISION_ANNOTATION = "deployment.kubernetes.io/revision";
    /** 变更说明注解。平台<b>不写</b>它，只展示集群里已有的（kubectl --record / 其它工具写的） */
    static final String CHANGE_CAUSE_ANNOTATION = "kubernetes.io/change-cause";

    /**
     * 改一个容器的镜像（列表页「镜像」列的行内编辑）。只改该容器的 {@code image}，同一容器的其它字段
     * 与其余容器原样保留 —— 直接发版：改的是 pod template，控制器会滚一次新版本。
     *
     * @param containerName 容器名（不含 initContainers：那个走编辑器）
     * @throws CloudPlatformException 工作负载不存在，或没有该名字的容器
     */
    public WorkloadDTO updateImage(String namespace, String name, String containerName, String image) {
        Resolved resolved = resolve(namespace, name);
        PodSpec spec = podSpecOf(resolved.object());
        if (!setContainerImage(spec, containerName, image)) {
            throw new CloudPlatformException(EnumResponseType.BEAN_VALIDATION_EXCEPTION,
                    "工作负载「" + name + "」下没有名为「" + containerName + "」的容器"
                            + "（initContainers 的镜像请在编辑器里改）");
        }
        return applyAndRevert(resolved);
    }

    /**
     * 把某个容器的镜像换成 {@code image}，返回是否命中。纯函数（供单测）：只碰 {@code image} 一个字段。
     */
    static boolean setContainerImage(PodSpec spec, String containerName, String image) {
        if (spec == null || spec.getContainers() == null) {
            return false;
        }
        for (Container c : spec.getContainers()) {
            if (containerName.equals(c.getName())) {
                c.setImage(image);
                return true;
            }
        }
        return false;
    }

    /**
     * 历史版本列表（新→旧）。Deployment 取名下 ReplicaSet 的版本注解；StatefulSet/DaemonSet 取
     * ControllerRevision —— 两者都是 K8s 自身的机制，本方法只做归一。
     */
    public List<WorkloadRevisionDTO> revisions(String namespace, String name) {
        Resolved resolved = resolve(namespace, name);
        if (WorkloadConverter.KIND_DEPLOYMENT.equals(resolved.kind())) {
            String current = annotationOf(resolved.object(), REVISION_ANNOTATION);
            return deploymentRevisions(listReplicaSets(namespace), name, current);
        }
        return workloadRevisions(listControllerRevisions(namespace), name, templateOf(resolved.object()));
    }

    /**
     * 回滚到指定版本：取该版本的 pod 模板覆盖到线上对象后写回。**只写工作负载本身** ——
     * 与 {@code kubectl rollout undo} 同语义（Deployment 不去改 RS，新版本号由控制器继续往下发）。
     *
     * @throws CloudPlatformException 版本不存在（被清理了，或本来就没有）
     */
    public WorkloadDTO rollback(String namespace, String name, long revision) {
        Resolved resolved = resolve(namespace, name);
        PodTemplateSpec target = WorkloadConverter.KIND_DEPLOYMENT.equals(resolved.kind())
                ? deploymentRevisionTemplate(listReplicaSets(namespace), name, revision)
                : controllerRevisionTemplate(listControllerRevisions(namespace), name, resolved.kind(), revision);
        if (target == null) {
            throw new CloudPlatformException(EnumResponseType.RESOURCE_NOT_EXIST,
                    "工作负载「" + name + "」没有版本 " + revision + " 的历史记录"
                            + "（可能已被控制器回收，或被清理策略删除）");
        }
        setTemplate(resolved.object(), target);
        return applyAndRevert(resolved);
    }

    /** 本命名空间全部 ReplicaSet（Deployment 版本历史用；归属过滤在纯函数里） */
    private List<ReplicaSet> listReplicaSets(String namespace) {
        return client.apps().replicaSets().inNamespace(namespace).list().getItems();
    }

    /** 本命名空间全部 ControllerRevision（STS/DS 版本历史用） */
    private List<ControllerRevision> listControllerRevisions(String namespace) {
        return client.apps().controllerRevisions().inNamespace(namespace).list().getItems();
    }

    /**
     * Deployment 的历史版本：只认 ownerReference（controller=true, kind=Deployment, name=本工作负载）指向它的
     * ReplicaSet，版本号取 {@code deployment.kubernetes.io/revision} 注解。没有该注解的 RS（手工建的、
     * 或非 rollout 产生的）不计入历史。
     *
     * @param currentRevision Deployment 自身同名注解的值 —— 它才是"当前版本"，不是最大版本号
     *                        （回滚后版本号还会继续涨）
     */
    static List<WorkloadRevisionDTO> deploymentRevisions(List<ReplicaSet> rsList, String deploymentName, String currentRevision) {
        NavigableMap<Long, WorkloadRevisionDTO> byRevision = new TreeMap<>();
        for (ReplicaSet rs : rsList) {
            if (!ownedBy(rs, WorkloadConverter.KIND_DEPLOYMENT, deploymentName)) {
                continue;
            }
            Long rev = parseRevision(annotationOf(rs, REVISION_ANNOTATION));
            if (rev == null) {
                continue;
            }
            byRevision.put(rev, revision(rev, rs.getMetadata().getName(), rs.getMetadata().getCreationTimestamp(),
                    annotationOf(rs, CHANGE_CAUSE_ANNOTATION), templateOf(rs),
                    String.valueOf(rev).equals(currentRevision)));
        }
        return newestFirst(byRevision);
    }

    /**
     * StatefulSet / DaemonSet 的历史版本：ControllerRevision 自带 {@code .revision} 数字，
     * "当前版本"没有注解可依（控制器不写），故取 pod 模板与线上相等的那条。
     */
    static List<WorkloadRevisionDTO> workloadRevisions(List<ControllerRevision> revisions, String workloadName,
                                                       PodTemplateSpec liveTemplate) {
        NavigableMap<Long, WorkloadRevisionDTO> byRevision = new TreeMap<>();
        for (ControllerRevision cr : revisions) {
            String kind = ownerKindOf(cr, workloadName);
            if (kind == null || cr.getRevision() == null) {
                continue;
            }
            PodTemplateSpec tpl = templateFromData(cr, kind);
            byRevision.put(cr.getRevision(), revision(cr.getRevision(), cr.getMetadata().getName(),
                    cr.getMetadata().getCreationTimestamp(), annotationOf(cr, CHANGE_CAUSE_ANNOTATION), tpl,
                    tpl != null && tpl.equals(liveTemplate)));
        }
        return newestFirst(byRevision);
    }

    /** 目标版本的 pod 模板（Deployment 路）：版本不存在返回 null。返回的是副本，调用方可直接写回 */
    static PodTemplateSpec deploymentRevisionTemplate(List<ReplicaSet> rsList, String deploymentName, long revision) {
        for (ReplicaSet rs : rsList) {
            if (ownedBy(rs, WorkloadConverter.KIND_DEPLOYMENT, deploymentName)
                    && parseRevision(annotationOf(rs, REVISION_ANNOTATION)) == revision) {
                return withoutPodTemplateHash(templateOf(rs));
            }
        }
        return null;
    }

    /**
     * 剥掉 {@code pod-template-hash}：那是 Deployment 控制器写给 ReplicaSet 的内部标识，不属于 Deployment
     * 自己的 pod 模板 —— 带回去会污染用户在标签里看到的内容，而且它会被算进下一次 rollout 的模板哈希。
     * 返回副本，不动传进来的对象。
     */
    private static PodTemplateSpec withoutPodTemplateHash(PodTemplateSpec template) {
        if (template == null) {
            return null;
        }
        PodTemplateSpec copy = Serialization.clone(template);
        if (copy.getMetadata() != null && copy.getMetadata().getLabels() != null) {
            copy.getMetadata().getLabels().remove(POD_TEMPLATE_HASH_LABEL);
        }
        return copy;
    }

    /** Deployment 控制器打在 ReplicaSet pod 模板上的内部标签 */
    private static final String POD_TEMPLATE_HASH_LABEL = "pod-template-hash";

    /** 目标版本的 pod 模板（STS/DS 路）：版本不存在返回 null */
    static PodTemplateSpec controllerRevisionTemplate(List<ControllerRevision> revisions, String workloadName,
                                                      String kind, long revision) {
        for (ControllerRevision cr : revisions) {
            if (Long.valueOf(revision).equals(cr.getRevision()) && ownerKindOf(cr, workloadName) != null) {
                return templateFromData(cr, normalizedKind(kind));
            }
        }
        return null;
    }

    /**
     * 从 ControllerRevision 的 {@code .data} 里取 pod 模板。data 由 KubernetesDeserializer 反序列化，
     * 正常情况下已是类型化对象；若拿到的是 Map（不同 fabric8 版本 / 非内置控制器的 CR），退回 JSON 解一遍 ——
     * 这条兜底不做的话，某些集群上 STS/DS 回滚会静默报"版本不存在"。
     */
    private static PodTemplateSpec templateFromData(ControllerRevision cr, String kind) {
        Object data = cr.getData();
        if (data == null) {
            return null;
        }
        if (data instanceof StatefulSet sts) {
            return sts.getSpec() != null ? sts.getSpec().getTemplate() : null;
        }
        if (data instanceof DaemonSet ds) {
            return ds.getSpec() != null ? ds.getSpec().getTemplate() : null;
        }
        try {
            if (WorkloadConverter.KIND_DAEMONSET.equalsIgnoreCase(kind)) {
                DaemonSet ds = Serialization.unmarshal(Serialization.asJson(data), DaemonSet.class);
                return ds.getSpec() != null ? ds.getSpec().getTemplate() : null;
            }
            StatefulSet sts = Serialization.unmarshal(Serialization.asJson(data), StatefulSet.class);
            return sts.getSpec() != null ? sts.getSpec().getTemplate() : null;
        } catch (Exception e) {
            log.warn("ControllerRevision「{}」的 data 解析失败（kind={}）：{}",
                    cr.getMetadata() != null ? cr.getMetadata().getName() : "?", kind, e.getMessage());
            return null;
        }
    }

    /** 该 CR 属于本工作负载则回其 K8s Kind（StatefulSet/DaemonSet），否则 null */
    private static String ownerKindOf(ControllerRevision cr, String workloadName) {
        if (ownedBy(cr, WorkloadConverter.KIND_STATEFULSET, workloadName)) {
            return K8S_KIND_STATEFULSET;
        }
        return ownedBy(cr, WorkloadConverter.KIND_DAEMONSET, workloadName) ? K8S_KIND_DAEMONSET : null;
    }

    private static final String K8S_KIND_STATEFULSET = "StatefulSet";
    private static final String K8S_KIND_DAEMONSET = "DaemonSet";

    private static String normalizedKind(String kind) {
        return WorkloadConverter.KIND_DAEMONSET.equalsIgnoreCase(kind) ? WorkloadConverter.KIND_DAEMONSET
                : WorkloadConverter.KIND_STATEFULSET;
    }

    /** 归属判据：ownerReference 里有一条 controller=true 且 kind/name 都对上（等价于 kubectl 的 ControllerRef 判据） */
    private static boolean ownedBy(HasMetadata obj, String kind, String name) {
        if (obj == null || obj.getMetadata() == null || obj.getMetadata().getOwnerReferences() == null) {
            return false;
        }
        for (OwnerReference o : obj.getMetadata().getOwnerReferences()) {
            if (Boolean.TRUE.equals(o.getController())
                    && kind.equalsIgnoreCase(o.getKind())
                    && name.equals(o.getName())) {
                return true;
            }
        }
        return false;
    }

    private static Long parseRevision(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return null;   // 注解被手工写坏时按"不是历史版本"处理，不让列表整体失败
        }
    }

    private static String annotationOf(HasMetadata obj, String key) {
        if (obj == null || obj.getMetadata() == null || obj.getMetadata().getAnnotations() == null) {
            return null;
        }
        return obj.getMetadata().getAnnotations().get(key);
    }

    private static WorkloadRevisionDTO revision(Long rev, String name, String createdAt, String changeCause,
                                                PodTemplateSpec template, boolean current) {
        WorkloadRevisionDTO dto = new WorkloadRevisionDTO();
        dto.setRevision(rev);
        dto.setName(name);
        dto.setCreatedAt(createdAt);
        dto.setChangeCause(changeCause);
        dto.setCurrent(current);
        List<String> images = new ArrayList<>();
        if (template != null && template.getSpec() != null && template.getSpec().getContainers() != null) {
            template.getSpec().getContainers().forEach(c -> images.add(c.getImage()));
        }
        dto.setImages(images);
        return dto;
    }

    /** 新→旧（对话框默认显示最近的在最上面） */
    private static List<WorkloadRevisionDTO> newestFirst(NavigableMap<Long, WorkloadRevisionDTO> byRevision) {
        return new ArrayList<>(byRevision.descendingMap().values());
    }

    /** pod 模板：工作负载三种 kind 与 ReplicaSet 都有 {@code spec.template}（RS 的模板 = 那一版的历史内容） */
    private static PodTemplateSpec templateOf(Object obj) {
        return switch (obj) {
            case Deployment d -> d.getSpec() != null ? d.getSpec().getTemplate() : null;
            case StatefulSet s -> s.getSpec() != null ? s.getSpec().getTemplate() : null;
            case DaemonSet ds -> ds.getSpec() != null ? ds.getSpec().getTemplate() : null;
            case ReplicaSet rs -> rs.getSpec() != null ? rs.getSpec().getTemplate() : null;
            case null, default -> null;
        };
    }

    private static PodSpec podSpecOf(Object workload) {
        PodTemplateSpec tpl = templateOf(workload);
        return tpl != null ? tpl.getSpec() : null;
    }

    private static void setTemplate(Object workload, PodTemplateSpec template) {
        PodTemplateSpec copy = Serialization.clone(template);
        if (workload instanceof Deployment d) {
            d.getSpec().setTemplate(copy);
        } else if (workload instanceof StatefulSet s) {
            s.getSpec().setTemplate(copy);
        } else {
            ((DaemonSet) workload).getSpec().setTemplate(copy);
        }
    }

    /**
     * 把"读回来改过的线上对象"按 SSA 写回（与 update() 同一个 fieldManager 身份）。
     * <p>为什么整体提交而不是只 patch 改动的字段：SSA 的语义是"提交的 apply config 就是我的全部声明"，
     * 同一个 fieldManager 提交**局部**对象会把它原本拥有的其它字段删掉。起点既然是线上对象，
     * 整体提交与线上值的差异就只有我们改的那一处。
     * <p>status 属于服务端/其它控制器，清掉再提交（避免把读到的旧状态当成我们的声明写回去）。
     */
    private WorkloadDTO applyAndRevert(Resolved resolved) {
        ObjectMeta meta = metaOf(resolved.object());
        meta.setManagedFields(null);
        // resourceVersion/generation 是服务端与其它控制器的字段：带上 resourceVersion 会让这次 SSA 变成
        // 乐观锁（Deployment 控制器只要刷一次 status 就变了版本号 → 我们的写入平白失败）。
        meta.setResourceVersion(null);
        meta.setGeneration(null);
        switch (resolved.kind()) {
            case WorkloadConverter.KIND_DEPLOYMENT -> {
                Deployment d = (Deployment) resolved.object();
                d.setStatus(null);
                return converter.revert(client.apps().deployments()
                        .inNamespace(meta.getNamespace()).resource(d)
                        .fieldManager(ServerSideApply.FIELD_MANAGER).forceConflicts().serverSideApply());
            }
            case WorkloadConverter.KIND_STATEFULSET -> {
                StatefulSet s = (StatefulSet) resolved.object();
                s.setStatus(null);
                return converter.revert(client.apps().statefulSets()
                        .inNamespace(meta.getNamespace()).resource(s)
                        .fieldManager(ServerSideApply.FIELD_MANAGER).forceConflicts().serverSideApply());
            }
            default -> {
                DaemonSet ds = (DaemonSet) resolved.object();
                ds.setStatus(null);
                return converter.revert(client.apps().daemonSets()
                        .inNamespace(meta.getNamespace()).resource(ds)
                        .fieldManager(ServerSideApply.FIELD_MANAGER).forceConflicts().serverSideApply());
            }
        }
    }

    private static ObjectMeta metaOf(Object workload) {
        if (workload instanceof Deployment d) {
            return d.getMetadata();
        }
        if (workload instanceof StatefulSet s) {
            return s.getMetadata();
        }
        return ((DaemonSet) workload).getMetadata();
    }

    @Override
    public String yaml(String namespace, String name) {
        if (!StringUtils.hasText(namespace) || !StringUtils.hasText(name)) {
            throw new CloudPlatformException(EnumResponseType.SEARCH_K8S_REQUIRE_NAME_AND_NAMESPACE);
        }
        Resolved resolve = resolve(namespace, name);


        return Serialization.asYaml(resolve(namespace, name).object());
    }

    /**按名跨 kind 查找：deployment → statefulset → daemonset */
    private Resolved resolve(String namespace, String name) {
        Deployment d = client.apps().deployments().inNamespace(namespace).withName(name).get();
        if (d != null) {
            if ( d.getMetadata() != null) {
                d.getMetadata().setManagedFields(null);
            }
            return new Resolved(WorkloadConverter.KIND_DEPLOYMENT, d);
        }
        StatefulSet s = client.apps().statefulSets().inNamespace(namespace).withName(name).get();
        if (s != null) {
            if ( s.getMetadata() != null) {
                s.getMetadata().setManagedFields(null);
            }
            return new Resolved(WorkloadConverter.KIND_STATEFULSET, s);
        }
        DaemonSet ds = client.apps().daemonSets().inNamespace(namespace).withName(name).get();
        if (ds != null) {
            if ( ds.getMetadata() != null) {
                ds.getMetadata().setManagedFields(null);
            }
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
    private record Resolved(String kind, HasMetadata object) {
    }

}
