package com.coding.k8score.converter.impl.workload;

import com.coding.common.models.k8s.dto.*;
import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.api.model.apps.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * WorkloadDTO ⇄ Deployment / StatefulSet / DaemonSet（一个 DTO 三种 kind）。
 * 全字段 1:1 映射（纯适配器，无业务校验）：podTemplate 承载完整 PodSpec，
 * strategy/volumeClaimTemplates/serviceName 按 kind 感知。
 */
public class WorkloadConverter {

    public static final String KIND_DEPLOYMENT = "deployment";
    public static final String KIND_STATEFULSET = "statefulset";
    public static final String KIND_DAEMONSET = "daemonset";

    // ==================== revert（K8s 对象 → DTO） ====================

    public WorkloadDTO revert(Deployment d) {
        WorkloadDTO dto = base(d.getMetadata(), KIND_DEPLOYMENT, d.getSpec() != null ? d.getSpec().getTemplate() : null);
        if (d.getSpec() != null) {
            dto.setReplicas(d.getSpec().getReplicas());
            dto.setStrategy(fromDeploymentStrategy(d.getSpec().getStrategy()));
        }
        if (d.getStatus() != null) dto.setReadyReplicas(d.getStatus().getReadyReplicas());
        return dto;
    }

    public WorkloadDTO revert(StatefulSet s) {
        WorkloadDTO dto = base(s.getMetadata(), KIND_STATEFULSET, s.getSpec() != null ? s.getSpec().getTemplate() : null);
        if (s.getSpec() != null) {
            dto.setReplicas(s.getSpec().getReplicas());
            dto.setServiceName(s.getSpec().getServiceName());
            dto.setStrategy(fromStatefulSetUpdateStrategy(s.getSpec().getUpdateStrategy()));
            dto.setVolumeClaimTemplates(fromPvcTemplates(s.getSpec().getVolumeClaimTemplates()));
        }
        if (s.getStatus() != null) dto.setReadyReplicas(s.getStatus().getReadyReplicas());
        return dto;
    }

    public WorkloadDTO revert(DaemonSet ds) {
        WorkloadDTO dto = base(ds.getMetadata(), KIND_DAEMONSET, ds.getSpec() != null ? ds.getSpec().getTemplate() : null);
        if (ds.getSpec() != null) dto.setStrategy(fromDaemonSetUpdateStrategy(ds.getSpec().getUpdateStrategy()));
        if (ds.getStatus() != null && ds.getStatus().getNumberReady() != null)
            dto.setReadyReplicas(ds.getStatus().getNumberReady());
        return dto;
    }

    private WorkloadDTO base(io.fabric8.kubernetes.api.model.ObjectMeta meta, String kind, PodTemplateSpec template) {
        WorkloadDTO dto = new WorkloadDTO();
        if (meta != null) {
            dto.setName(meta.getName());
            dto.setNamespace(meta.getNamespace());
            dto.setLabels(meta.getLabels());
            if (meta.getAnnotations() != null && meta.getAnnotations().get("description") != null)
                dto.setDescription(String.valueOf(meta.getAnnotations().get("description")));
            if (meta.getCreationTimestamp() != null) dto.setCreationTime(meta.getCreationTimestamp().toString());
        }
        dto.setKind(kind);
        if (template != null && template.getSpec() != null) {
            // images[] 摘要（list 用）
            if (template.getSpec().getContainers() != null)
                dto.setImages(template.getSpec().getContainers().stream().map(Container::getImage).toList());
            PodTemplateDTO pt = new PodTemplateDTO();
            if (template.getMetadata() != null) {
                pt.setLabels(template.getMetadata().getLabels());
                // DTO annotations 为 Map<String,Object>，fabric8 为 Map<String,String>（泛型不变性），拷贝适配
                if (template.getMetadata().getAnnotations() != null)
                    pt.setAnnotations(new java.util.HashMap<>(template.getMetadata().getAnnotations()));
            }
            pt.setSpec(fromPodSpec(template.getSpec()));
            dto.setPodTemplate(pt);
        }
        return dto;
    }

    private PodSpecDTO fromPodSpec(io.fabric8.kubernetes.api.model.PodSpec s) {
        PodSpecDTO d = new PodSpecDTO();
        if (s.getContainers() != null) d.setContainers(s.getContainers().stream().map(this::fromContainer).toList());
        if (s.getInitContainers() != null) d.setInitContainers(s.getInitContainers().stream().map(this::fromContainer).toList());
        d.setRestartPolicy(s.getRestartPolicy());
        d.setServiceAccountName(s.getServiceAccountName());
        d.setNodeName(s.getNodeName());
        d.setNodeSelector(s.getNodeSelector());
        if (s.getAffinity() != null) d.setAffinity(fromAffinity(s.getAffinity()));
        if (s.getTolerations() != null) d.setTolerations(s.getTolerations().stream().map(this::fromToleration).toList());
        if (s.getVolumes() != null) d.setVolumes(s.getVolumes().stream().map(this::fromVolume).toList());
        if (s.getImagePullSecrets() != null)
            d.setImagePullSecrets(s.getImagePullSecrets().stream()
                .map(r -> { ImagePullSecretRefDTO x = new ImagePullSecretRefDTO(); x.setName(r.getName()); return x; }).toList());
        return d;
    }

    private ContainerDTO fromContainer(Container c) {
        ContainerDTO d = new ContainerDTO();
        d.setName(c.getName()); d.setImage(c.getImage());
        d.setCommand(c.getCommand()); d.setArgs(c.getArgs());
        d.setWorkingDir(c.getWorkingDir()); d.setImagePullPolicy(c.getImagePullPolicy());
        if (c.getEnv() != null) d.setEnvs(c.getEnv().stream().map(this::fromEnv).toList());
        if (c.getEnvFrom() != null) d.setEnvFrom(c.getEnvFrom().stream().map(this::fromEnvFrom).toList());
        if (c.getPorts() != null) d.setPorts(c.getPorts().stream().map(this::fromPort).toList());
        if (c.getResources() != null) d.setResources(fromResources(c.getResources()));
        if (c.getLifecycle() != null) d.setLifecycle(fromLifecycle(c.getLifecycle()));
        if (c.getLivenessProbe() != null) d.setLivenessProbe(fromProbe(c.getLivenessProbe()));
        if (c.getReadinessProbe() != null) d.setReadinessProbe(fromProbe(c.getReadinessProbe()));
        if (c.getStartupProbe() != null) d.setStartupProbe(fromProbe(c.getStartupProbe()));
        if (c.getVolumeMounts() != null) d.setVolumeMounts(c.getVolumeMounts().stream().map(this::fromVolumeMount).toList());
        return d;
    }

    private EnvDTO fromEnv(EnvVar e) {
        EnvDTO d = new EnvDTO(); d.setName(e.getName()); d.setValue(e.getValue());
        if (e.getValueFrom() != null) d.setValueFrom(fromValueFrom(e.getValueFrom()));
        return d;
    }

    private ValueFromDTO fromValueFrom(EnvVarSource v) {
        ValueFromDTO d = new ValueFromDTO();
        if (v.getConfigMapKeyRef() != null) { ConfigMapKeySelectorDTO x = new ConfigMapKeySelectorDTO();
            x.setName(v.getConfigMapKeyRef().getName()); x.setKey(v.getConfigMapKeyRef().getKey());
            x.setOptional(v.getConfigMapKeyRef().getOptional()); d.setConfigMapKeyRef(x); }
        if (v.getSecretKeyRef() != null) { SecretKeySelectorDTO x = new SecretKeySelectorDTO();
            x.setName(v.getSecretKeyRef().getName()); x.setKey(v.getSecretKeyRef().getKey());
            x.setOptional(v.getSecretKeyRef().getOptional()); d.setSecretKeyRef(x); }
        if (v.getFieldRef() != null) { ObjectFieldSelectorDTO x = new ObjectFieldSelectorDTO();
            x.setApiVersion(v.getFieldRef().getApiVersion()); x.setFieldPath(v.getFieldRef().getFieldPath()); d.setFieldRef(x); }
        return d;
    }

    private EnvFromDTO fromEnvFrom(EnvFromSource f) {
        EnvFromDTO d = new EnvFromDTO(); d.setPrefix(f.getPrefix());
        if (f.getConfigMapRef() != null) { ConfigMapRefDTO x = new ConfigMapRefDTO();
            x.setName(f.getConfigMapRef().getName()); x.setOptional(f.getConfigMapRef().getOptional()); d.setConfigMapRef(x); }
        if (f.getSecretRef() != null) { SecretRefDTO x = new SecretRefDTO();
            x.setName(f.getSecretRef().getName()); x.setOptional(f.getSecretRef().getOptional()); d.setSecretRef(x); }
        return d;
    }

    private PortDTO fromPort(ContainerPort p) {
        PortDTO d = new PortDTO(); d.setContainerPort(p.getContainerPort());
        d.setProtocol(p.getProtocol()); d.setName(p.getName()); return d;
    }

    private ResourcesDTO fromResources(ResourceRequirements r) {
        ResourcesDTO d = new ResourcesDTO(); d.setLimits(fromQuantities(r.getLimits())); d.setRequests(fromQuantities(r.getRequests())); return d;
    }

    private LifecycleDTO fromLifecycle(Lifecycle l) {
        LifecycleDTO d = new LifecycleDTO();
        if (l.getPostStart() != null) d.setPostStart(fromHandler(l.getPostStart()));
        if (l.getPreStop() != null) d.setPreStop(fromHandler(l.getPreStop()));
        return d;
    }

    private HandlerDTO fromHandler(LifecycleHandler h) {
        HandlerDTO d = new HandlerDTO();
        if (h.getExec() != null) { ExecActionDTO x = new ExecActionDTO(); x.setCommand(h.getExec().getCommand()); d.setExec(x); }
        if (h.getHttpGet() != null) { HttpGetActionDTO x = new HttpGetActionDTO();
            x.setPort(h.getHttpGet().getPort() != null ? h.getHttpGet().getPort().toString() : null);
            x.setPath(h.getHttpGet().getPath()); x.setScheme(h.getHttpGet().getScheme()); d.setHttpGet(x); }
        if (h.getSleep() != null) { SleepActionDTO x = new SleepActionDTO(); x.setSeconds(h.getSleep().getSeconds()); d.setSleep(x); }
        return d;
    }

    private ProbeDTO fromProbe(Probe p) {
        ProbeDTO d = new ProbeDTO();
        if (p.getHttpGet() != null) { HttpGetActionDTO x = new HttpGetActionDTO();
            x.setPort(p.getHttpGet().getPort() != null ? p.getHttpGet().getPort().toString() : null);
            x.setPath(p.getHttpGet().getPath()); x.setScheme(p.getHttpGet().getScheme()); d.setHttpGet(x); }
        if (p.getTcpSocket() != null) { TCPSocketActionDTO x = new TCPSocketActionDTO();
            x.setPort(p.getTcpSocket().getPort() != null ? p.getTcpSocket().getPort().toString() : null); d.setTcpSocket(x); }
        if (p.getExec() != null) { ExecActionDTO x = new ExecActionDTO(); x.setCommand(p.getExec().getCommand()); d.setExec(x); }
        d.setInitialDelaySeconds(p.getInitialDelaySeconds()); d.setPeriodSeconds(p.getPeriodSeconds());
        d.setTimeoutSeconds(p.getTimeoutSeconds()); d.setSuccessThreshold(p.getSuccessThreshold());
        d.setFailureThreshold(p.getFailureThreshold());
        return d;
    }

    private VolumeMountDTO fromVolumeMount(VolumeMount m) {
        VolumeMountDTO d = new VolumeMountDTO();
        d.setName(m.getName()); d.setMountPath(m.getMountPath());
        d.setReadOnly(m.getReadOnly()); d.setSubPath(m.getSubPath()); return d;
    }

    private AffinityDTO fromAffinity(Affinity a) {
        AffinityDTO d = new AffinityDTO();
        if (a.getNodeAffinity() != null) d.setNodeAffinity(fromNodeAffinity(a.getNodeAffinity()));
        if (a.getPodAntiAffinity() != null) d.setPodAntiAffinity(fromPodAntiAffinity(a.getPodAntiAffinity()));
        return d;
    }

    private NodeAffinityDTO fromNodeAffinity(NodeAffinity n) {
        NodeAffinityDTO d = new NodeAffinityDTO();
        if (n.getRequiredDuringSchedulingIgnoredDuringExecution() != null) {
            NodeSelectorDTO req = new NodeSelectorDTO();
            req.setNodeSelectorTerms(n.getRequiredDuringSchedulingIgnoredDuringExecution().getNodeSelectorTerms()
                .stream().map(this::fromNodeSelectorTerm).toList());
            d.setRequired(req);
        }
        if (n.getPreferredDuringSchedulingIgnoredDuringExecution() != null)
            d.setPreferred(n.getPreferredDuringSchedulingIgnoredDuringExecution().stream().map(p -> {
                PreferredSchedulingTermDTO x = new PreferredSchedulingTermDTO();
                x.setWeight(p.getWeight()); x.setPreference(fromNodeSelectorTerm(p.getPreference())); return x; }).toList());
        return d;
    }

    private NodeSelectorTermDTO fromNodeSelectorTerm(NodeSelectorTerm t) {
        NodeSelectorTermDTO d = new NodeSelectorTermDTO();
        if (t.getMatchExpressions() != null) d.setMatchExpressions(t.getMatchExpressions().stream().map(this::fromRequirement).toList());
        if (t.getMatchFields() != null) d.setMatchFields(t.getMatchFields().stream().map(this::fromRequirement).toList());
        return d;
    }

    private NodeSelectorRequirementDTO fromRequirement(NodeSelectorRequirement r) {
        NodeSelectorRequirementDTO d = new NodeSelectorRequirementDTO();
        d.setKey(r.getKey());
        d.setOperator(r.getOperator());
        d.setValues(r.getValues()); return d;
    }

    private PodAntiAffinityDTO fromPodAntiAffinity(PodAntiAffinity p) {
        PodAntiAffinityDTO d = new PodAntiAffinityDTO();
        if (p.getRequiredDuringSchedulingIgnoredDuringExecution() != null)
            d.setRequired(p.getRequiredDuringSchedulingIgnoredDuringExecution().stream().map(this::fromPodAffinityTerm).toList());
        if (p.getPreferredDuringSchedulingIgnoredDuringExecution() != null)
            d.setPreferred(p.getPreferredDuringSchedulingIgnoredDuringExecution().stream().map(w -> {
                WeightedPodAffinityTermDTO x = new WeightedPodAffinityTermDTO();
                x.setWeight(w.getWeight()); x.setPodAffinityTerm(fromPodAffinityTerm(w.getPodAffinityTerm())); return x; }).toList());
        return d;
    }

    private PodAffinityTermDTO fromPodAffinityTerm(PodAffinityTerm t) {
        PodAffinityTermDTO d = new PodAffinityTermDTO();
        d.setNamespaces(t.getNamespaces()); d.setTopologyKey(t.getTopologyKey());
        if (t.getLabelSelector() != null && t.getLabelSelector().getMatchLabels() != null)
            d.setMatchLabels(t.getLabelSelector().getMatchLabels());
        return d;
    }

    private TolerationDTO fromToleration(Toleration t) {
        TolerationDTO d = new TolerationDTO();
        d.setKey(t.getKey());
        d.setOperator(t.getOperator());
        d.setValue(t.getValue()); d.setEffect(t.getEffect()); d.setTolerationSeconds(t.getTolerationSeconds());
        return d;
    }

    private VolumeDTO fromVolume(Volume v) {
        VolumeDTO d = new VolumeDTO(); d.setName(v.getName());
        if (v.getEmptyDir() != null) { EmptyDirVolumeDTO x = new EmptyDirVolumeDTO();
            x.setMedium(v.getEmptyDir().getMedium());
            x.setSizeLimit(v.getEmptyDir().getSizeLimit() != null ? v.getEmptyDir().getSizeLimit().toString() : null);
            d.setType("emptyDir"); d.setEmptyDir(x); }
        else if (v.getConfigMap() != null) { ConfigMapVolumeDTO x = new ConfigMapVolumeDTO();
            x.setName(v.getConfigMap().getName());
            if (v.getConfigMap().getItems() != null) x.setItems(v.getConfigMap().getItems().stream().map(this::fromKeyToPath).toList());
            d.setType("configMap"); d.setConfigMap(x); }
        else if (v.getSecret() != null) { SecretVolumeDTO x = new SecretVolumeDTO();
            x.setSecretName(v.getSecret().getSecretName());
            if (v.getSecret().getItems() != null) x.setItems(v.getSecret().getItems().stream().map(this::fromKeyToPath).toList());
            d.setType("secret"); d.setSecret(x); }
        else if (v.getPersistentVolumeClaim() != null) { PvcVolumeDTO x = new PvcVolumeDTO();
            x.setClaimName(v.getPersistentVolumeClaim().getClaimName()); d.setType("persistentVolumeClaim"); d.setPersistentVolumeClaim(x); }
        else if (v.getHostPath() != null) { HostPathVolumeDTO x = new HostPathVolumeDTO();
            x.setPath(v.getHostPath().getPath()); x.setType(v.getHostPath().getType()); d.setType("hostPath"); d.setHostPath(x); }
        return d;
    }

    private KeyToPathDTO fromKeyToPath(KeyToPath i) {
        KeyToPathDTO d = new KeyToPathDTO(); d.setKey(i.getKey()); d.setPath(i.getPath()); d.setMode(i.getMode()); return d;
    }

    private StrategyDTO fromDeploymentStrategy(DeploymentStrategy s) {
        if (s == null) return null;
        StrategyDTO d = new StrategyDTO(); d.setType(s.getType());
        if (s.getRollingUpdate() != null) { RollingUpdateDTO r = new RollingUpdateDTO();
            r.setMaxSurge(s.getRollingUpdate().getMaxSurge() != null ? s.getRollingUpdate().getMaxSurge().toString() : null);
            r.setMaxUnavailable(s.getRollingUpdate().getMaxUnavailable() != null ? s.getRollingUpdate().getMaxUnavailable().toString() : null);
            d.setRollingUpdate(r); }
        return d;
    }

    private StrategyDTO fromStatefulSetUpdateStrategy(StatefulSetUpdateStrategy s) {
        if (s == null) return null;
        StrategyDTO d = new StrategyDTO(); d.setType(s.getType());
        if (s.getRollingUpdate() != null && s.getRollingUpdate().getPartition() != null) {
            RollingUpdateDTO r = new RollingUpdateDTO(); r.setPartition(s.getRollingUpdate().getPartition()); d.setRollingUpdate(r); }
        return d;
    }

    private StrategyDTO fromDaemonSetUpdateStrategy(DaemonSetUpdateStrategy s) {
        if (s == null) return null;
        StrategyDTO d = new StrategyDTO(); d.setType(s.getType());
        if (s.getRollingUpdate() != null && s.getRollingUpdate().getMaxUnavailable() != null) {
            RollingUpdateDTO r = new RollingUpdateDTO();
            r.setMaxUnavailable(s.getRollingUpdate().getMaxUnavailable().toString()); d.setRollingUpdate(r); }
        return d;
    }

    private java.util.List<PvcTemplateDTO> fromPvcTemplates(java.util.List<PersistentVolumeClaim> list) {
        if (list == null || list.isEmpty()) return null;
        return list.stream().map(t -> { PvcTemplateDTO d = new PvcTemplateDTO();
            d.setName(t.getMetadata() != null ? t.getMetadata().getName() : null);
            if (t.getSpec() != null) {
                d.setAccessModes(t.getSpec().getAccessModes());
                d.setStorageClassName(t.getSpec().getStorageClassName());
                d.setVolumeMode(t.getSpec().getVolumeMode());
                if (t.getSpec().getResources() != null && t.getSpec().getResources().getRequests() != null
                        && t.getSpec().getResources().getRequests().get("storage") != null)
                    d.setStorage(t.getSpec().getResources().getRequests().get("storage").toString());
            } return d; }).toList();
    }

    // ==================== convert（DTO → K8s 对象，按 kind 分发） ====================

    public Deployment convertDeployment(WorkloadDTO dto) {
        return new DeploymentBuilder()
            .withNewMetadata().withName(dto.getName()).withNamespace(dto.getNamespace())
                .withLabels(effectiveLabels(dto)).endMetadata()
            .withNewSpec()
                .withReplicas(dto.getReplicas() != null ? dto.getReplicas() : 1)
                .withSelector(new LabelSelectorBuilder().withMatchLabels(selectorLabels(dto)).build())
                .withStrategy(toDeploymentStrategy(dto.getStrategy()))
                .withTemplate(buildTemplate(dto))
            .endSpec().build();
    }

    public StatefulSet convertStatefulSet(WorkloadDTO dto) {
        return new StatefulSetBuilder()
            .withNewMetadata().withName(dto.getName()).withNamespace(dto.getNamespace())
                .withLabels(effectiveLabels(dto)).endMetadata()
            .withNewSpec()
                .withReplicas(dto.getReplicas() != null ? dto.getReplicas() : 1)
                .withServiceName(hasText(dto.getServiceName()) ? dto.getServiceName() : dto.getName())
                .withSelector(new LabelSelectorBuilder().withMatchLabels(selectorLabels(dto)).build())
                .withUpdateStrategy(toStatefulSetUpdateStrategy(dto.getStrategy()))
                .withTemplate(buildTemplate(dto))
                .withVolumeClaimTemplates(toPvcTemplates(dto.getVolumeClaimTemplates()))
            .endSpec().build();
    }

    public DaemonSet convertDaemonSet(WorkloadDTO dto) {
        return new DaemonSetBuilder()
            .withNewMetadata().withName(dto.getName()).withNamespace(dto.getNamespace())
                .withLabels(effectiveLabels(dto)).endMetadata()
            .withNewSpec()
                .withSelector(new LabelSelectorBuilder().withMatchLabels(selectorLabels(dto)).build())
                .withUpdateStrategy(toDaemonSetUpdateStrategy(dto.getStrategy()))
                .withTemplate(buildTemplate(dto))
            .endSpec().build();
    }

    private PodTemplateSpec buildTemplate(WorkloadDTO dto) {
        PodTemplateDTO pt = dto.getPodTemplate() != null ? dto.getPodTemplate() : new PodTemplateDTO();
        Map<String, String> tplLabels = effectiveLabels(dto); // app=name + 用户 labels
        if (pt.getLabels() != null) tplLabels.putAll(pt.getLabels());
        return new PodTemplateSpecBuilder()
            .withNewMetadata().withLabels(tplLabels).endMetadata()
            .withSpec(buildPodSpec(pt.getSpec() != null ? pt.getSpec() : new PodSpecDTO()))
            .build();
    }

    private PodSpec buildPodSpec(PodSpecDTO s) {
        PodSpecBuilder b = new PodSpecBuilder();
        if (notEmpty(s.getContainers())) b.withContainers(s.getContainers().stream().map(this::toContainer).toList());
        if (notEmpty(s.getInitContainers())) b.withInitContainers(s.getInitContainers().stream().map(this::toContainer).toList());
        if (hasText(s.getRestartPolicy())) b.withRestartPolicy(s.getRestartPolicy());
        if (hasText(s.getServiceAccountName())) b.withServiceAccountName(s.getServiceAccountName());
        if (hasText(s.getNodeName())) b.withNodeName(s.getNodeName());
        if (s.getNodeSelector() != null && !s.getNodeSelector().isEmpty()) b.withNodeSelector(s.getNodeSelector());
        if (s.getAffinity() != null) b.withAffinity(toAffinity(s.getAffinity()));
        if (notEmpty(s.getTolerations())) b.withTolerations(s.getTolerations().stream().map(this::toToleration).toList());
        if (notEmpty(s.getVolumes())) b.withVolumes(s.getVolumes().stream().map(this::toVolume).toList());
        if (notEmpty(s.getImagePullSecrets()))
            b.withImagePullSecrets(s.getImagePullSecrets().stream()
                .map(r -> new LocalObjectReferenceBuilder().withName(r.getName()).build()).toList());
        return b.build();
    }

    private Container toContainer(ContainerDTO c) {
        ContainerBuilder b = new ContainerBuilder();
        if (hasText(c.getName())) b.withName(c.getName());
        if (hasText(c.getImage())) b.withImage(c.getImage());
        if (notEmpty(c.getCommand())) b.withCommand(c.getCommand());
        if (notEmpty(c.getArgs())) b.withArgs(c.getArgs());
        if (hasText(c.getWorkingDir())) b.withWorkingDir(c.getWorkingDir());
        if (hasText(c.getImagePullPolicy())) b.withImagePullPolicy(c.getImagePullPolicy());
        if (notEmpty(c.getEnvs())) b.withEnv(c.getEnvs().stream().map(this::toEnv).toList());
        if (notEmpty(c.getEnvFrom())) b.withEnvFrom(c.getEnvFrom().stream().map(this::toEnvFrom).toList());
        if (notEmpty(c.getPorts())) b.withPorts(c.getPorts().stream().map(this::toPort).toList());
        if (c.getResources() != null) b.withResources(toResources(c.getResources()));
        if (c.getLifecycle() != null) b.withLifecycle(toLifecycle(c.getLifecycle()));
        if (c.getLivenessProbe() != null) b.withLivenessProbe(toProbe(c.getLivenessProbe()));
        if (c.getReadinessProbe() != null) b.withReadinessProbe(toProbe(c.getReadinessProbe()));
        if (c.getStartupProbe() != null) b.withStartupProbe(toProbe(c.getStartupProbe()));
        if (notEmpty(c.getVolumeMounts())) b.withVolumeMounts(c.getVolumeMounts().stream().map(this::toVolumeMount).toList());
        return b.build();
    }

    private EnvVar toEnv(EnvDTO e) {
        EnvVarBuilder b = new EnvVarBuilder();
        if (hasText(e.getName())) b.withName(e.getName());
        if (e.getValue() != null) b.withValue(e.getValue());
        if (e.getValueFrom() != null) b.withValueFrom(toValueFrom(e.getValueFrom()));
        return b.build();
    }

    private EnvVarSource toValueFrom(ValueFromDTO v) {
        EnvVarSourceBuilder b = new EnvVarSourceBuilder();
        if (v.getConfigMapKeyRef() != null)
            b.withConfigMapKeyRef(new ConfigMapKeySelectorBuilder().withName(v.getConfigMapKeyRef().getName())
                .withKey(v.getConfigMapKeyRef().getKey()).withOptional(v.getConfigMapKeyRef().getOptional()).build());
        if (v.getSecretKeyRef() != null)
            b.withSecretKeyRef(new SecretKeySelectorBuilder().withName(v.getSecretKeyRef().getName())
                .withKey(v.getSecretKeyRef().getKey()).withOptional(v.getSecretKeyRef().getOptional()).build());
        if (v.getFieldRef() != null)
            b.withFieldRef(new ObjectFieldSelectorBuilder().withApiVersion(v.getFieldRef().getApiVersion())
                .withFieldPath(v.getFieldRef().getFieldPath()).build());
        return b.build();
    }

    private EnvFromSource toEnvFrom(EnvFromDTO f) {
        EnvFromSourceBuilder b = new EnvFromSourceBuilder();
        if (hasText(f.getPrefix())) b.withPrefix(f.getPrefix());
        if (f.getConfigMapRef() != null)
            b.withConfigMapRef(new ConfigMapEnvSourceBuilder().withName(f.getConfigMapRef().getName())
                .withOptional(f.getConfigMapRef().getOptional()).build());
        if (f.getSecretRef() != null)
            b.withSecretRef(new SecretEnvSourceBuilder().withName(f.getSecretRef().getName())
                .withOptional(f.getSecretRef().getOptional()).build());
        return b.build();
    }

    private ContainerPort toPort(PortDTO p) {
        ContainerPortBuilder b = new ContainerPortBuilder();
        if (p.getContainerPort() != null) b.withContainerPort(p.getContainerPort());
        if (hasText(p.getProtocol())) b.withProtocol(p.getProtocol());
        if (hasText(p.getName())) b.withName(p.getName());
        return b.build();
    }

    private ResourceRequirements toResources(ResourcesDTO r) {
        ResourceRequirementsBuilder b = new ResourceRequirementsBuilder();
        if (r.getLimits() != null && !r.getLimits().isEmpty()) b.withLimits(toQuantities(r.getLimits()));
        if (r.getRequests() != null && !r.getRequests().isEmpty()) b.withRequests(toQuantities(r.getRequests()));
        return b.build();
    }

    /**DTO 资源值为 String，fabric8 7.x 为 Quantity，逐值转换 */
    private Map<String, Quantity> toQuantities(Map<String, String> m) {
        Map<String, Quantity> q = new LinkedHashMap<>();
        if (m != null) {
            for (Map.Entry<String, String> e : m.entrySet()) q.put(e.getKey(), new Quantity(e.getValue()));
        }
        return q;
    }

    /**fabric8 7.x Quantity → DTO String（amount+format 原样回写） */
    private Map<String, String> fromQuantities(Map<String, Quantity> m) {
        if (m == null) return null;
        Map<String, String> r = new LinkedHashMap<>();
        for (Map.Entry<String, Quantity> e : m.entrySet())
            r.put(e.getKey(), e.getValue() != null ? e.getValue().toString() : null);
        return r;
    }

    private Lifecycle toLifecycle(LifecycleDTO l) {
        LifecycleBuilder b = new LifecycleBuilder();
        if (l.getPostStart() != null) b.withPostStart(toHandler(l.getPostStart()));
        if (l.getPreStop() != null) b.withPreStop(toHandler(l.getPreStop()));
        return b.build();
    }

    private LifecycleHandler toHandler(HandlerDTO h) {
        LifecycleHandlerBuilder b = new LifecycleHandlerBuilder();
        if (h.getExec() != null) b.withExec(new ExecActionBuilder().withCommand(h.getExec().getCommand()).build());
        if (h.getHttpGet() != null) b.withHttpGet(toHttpGet(h.getHttpGet()));
        if (h.getSleep() != null) b.withSleep(new SleepActionBuilder().withSeconds(h.getSleep().getSeconds()).build());
        return b.build();
    }

    private Probe toProbe(ProbeDTO p) {
        ProbeBuilder b = new ProbeBuilder();
        if (p.getHttpGet() != null) b.withHttpGet(toHttpGet(p.getHttpGet()));
        if (p.getTcpSocket() != null) b.withTcpSocket(new TCPSocketActionBuilder().withPort(new IntOrString(p.getTcpSocket().getPort())).build());
        if (p.getExec() != null) b.withExec(new ExecActionBuilder().withCommand(p.getExec().getCommand()).build());
        if (p.getInitialDelaySeconds() != null) b.withInitialDelaySeconds(p.getInitialDelaySeconds());
        if (p.getPeriodSeconds() != null) b.withPeriodSeconds(p.getPeriodSeconds());
        if (p.getTimeoutSeconds() != null) b.withTimeoutSeconds(p.getTimeoutSeconds());
        if (p.getSuccessThreshold() != null) b.withSuccessThreshold(p.getSuccessThreshold());
        if (p.getFailureThreshold() != null) b.withFailureThreshold(p.getFailureThreshold());
        return b.build();
    }

    private HTTPGetAction toHttpGet(HttpGetActionDTO h) {
        HTTPGetActionBuilder b = new HTTPGetActionBuilder();
        if (hasText(h.getPort())) b.withPort(new IntOrString(h.getPort()));
        if (hasText(h.getPath())) b.withPath(h.getPath());
        if (hasText(h.getScheme())) b.withScheme(h.getScheme());
        return b.build();
    }

    private VolumeMount toVolumeMount(VolumeMountDTO m) {
        VolumeMountBuilder b = new VolumeMountBuilder();
        if (hasText(m.getName())) b.withName(m.getName());
        if (hasText(m.getMountPath())) b.withMountPath(m.getMountPath());
        if (m.getReadOnly() != null) b.withReadOnly(m.getReadOnly());
        if (hasText(m.getSubPath())) b.withSubPath(m.getSubPath());
        return b.build();
    }

    private Affinity toAffinity(AffinityDTO a) {
        AffinityBuilder b = new AffinityBuilder();
        if (a.getNodeAffinity() != null) b.withNodeAffinity(toNodeAffinity(a.getNodeAffinity()));
        if (a.getPodAntiAffinity() != null) b.withPodAntiAffinity(toPodAntiAffinity(a.getPodAntiAffinity()));
        return b.build();
    }

    private NodeAffinity toNodeAffinity(NodeAffinityDTO n) {
        NodeAffinityBuilder b = new NodeAffinityBuilder();
        if (n.getRequired() != null && notEmpty(n.getRequired().getNodeSelectorTerms()))
            b.withNewRequiredDuringSchedulingIgnoredDuringExecution()
                .withNodeSelectorTerms(n.getRequired().getNodeSelectorTerms().stream().map(this::toNodeSelectorTerm).toList())
                .endRequiredDuringSchedulingIgnoredDuringExecution();
        if (notEmpty(n.getPreferred()))
            b.withPreferredDuringSchedulingIgnoredDuringExecution(n.getPreferred().stream()
                .map(p -> new PreferredSchedulingTermBuilder().withWeight(p.getWeight())
                    .withPreference(toNodeSelectorTerm(p.getPreference())).build()).toList());
        return b.build();
    }

    private NodeSelectorTerm toNodeSelectorTerm(NodeSelectorTermDTO t) {
        NodeSelectorTermBuilder b = new NodeSelectorTermBuilder();
        if (notEmpty(t.getMatchExpressions())) b.withMatchExpressions(t.getMatchExpressions().stream().map(this::toRequirement).toList());
        if (notEmpty(t.getMatchFields())) b.withMatchFields(t.getMatchFields().stream().map(this::toRequirement).toList());
        return b.build();
    }

    private NodeSelectorRequirement toRequirement(NodeSelectorRequirementDTO r) {
        NodeSelectorRequirementBuilder b = new NodeSelectorRequirementBuilder();
        if (hasText(r.getKey())) b.withKey(r.getKey());
        if (hasText(r.getOperator())) b.withOperator(r.getOperator());
        if (r.getValues() != null) b.withValues(r.getValues());
        return b.build();
    }

    private PodAntiAffinity toPodAntiAffinity(PodAntiAffinityDTO p) {
        PodAntiAffinityBuilder b = new PodAntiAffinityBuilder();
        if (notEmpty(p.getRequired()))
            b.withRequiredDuringSchedulingIgnoredDuringExecution(p.getRequired().stream().map(this::toPodAffinityTerm).toList());
        if (notEmpty(p.getPreferred()))
            b.withPreferredDuringSchedulingIgnoredDuringExecution(p.getPreferred().stream()
                .map(w -> new WeightedPodAffinityTermBuilder().withWeight(w.getWeight())
                    .withPodAffinityTerm(toPodAffinityTerm(w.getPodAffinityTerm())).build()).toList());
        return b.build();
    }

    private PodAffinityTerm toPodAffinityTerm(PodAffinityTermDTO t) {
        PodAffinityTermBuilder b = new PodAffinityTermBuilder();
        if (notEmpty(t.getNamespaces())) b.withNamespaces(t.getNamespaces());
        if (hasText(t.getTopologyKey())) b.withTopologyKey(t.getTopologyKey());
        if (t.getMatchLabels() != null && !t.getMatchLabels().isEmpty())
            b.withLabelSelector(new LabelSelectorBuilder().withMatchLabels(t.getMatchLabels()).build());
        return b.build();
    }

    private Toleration toToleration(TolerationDTO t) {
        TolerationBuilder b = new TolerationBuilder();
        if (hasText(t.getKey())) b.withKey(t.getKey());
        if (hasText(t.getOperator())) b.withOperator(t.getOperator());
        if (t.getValue() != null) b.withValue(t.getValue());
        if (hasText(t.getEffect())) b.withEffect(t.getEffect());
        if (t.getTolerationSeconds() != null) b.withTolerationSeconds(t.getTolerationSeconds());
        return b.build();
    }

    private Volume toVolume(VolumeDTO v) {
        VolumeBuilder b = new VolumeBuilder().withName(v.getName());
        if (v.getEmptyDir() != null) {
            EmptyDirVolumeSourceBuilder e = new EmptyDirVolumeSourceBuilder();
            if (hasText(v.getEmptyDir().getMedium())) e.withMedium(v.getEmptyDir().getMedium());
            if (hasText(v.getEmptyDir().getSizeLimit())) e.withSizeLimit(new Quantity(v.getEmptyDir().getSizeLimit()));
            b.withEmptyDir(e.build());
        } else if (v.getConfigMap() != null) {
            ConfigMapVolumeSourceBuilder c = new ConfigMapVolumeSourceBuilder().withName(v.getConfigMap().getName());
            if (notEmpty(v.getConfigMap().getItems())) c.withItems(toKeyToPath(v.getConfigMap().getItems()));
            b.withConfigMap(c.build());
        } else if (v.getSecret() != null) {
            SecretVolumeSourceBuilder s = new SecretVolumeSourceBuilder().withSecretName(v.getSecret().getSecretName());
            if (notEmpty(v.getSecret().getItems())) s.withItems(toKeyToPath(v.getSecret().getItems()));
            b.withSecret(s.build());
        } else if (v.getPersistentVolumeClaim() != null) {
            b.withPersistentVolumeClaim(new PersistentVolumeClaimVolumeSourceBuilder()
                .withClaimName(v.getPersistentVolumeClaim().getClaimName()).build());
        } else if (v.getHostPath() != null) {
            HostPathVolumeSourceBuilder h = new HostPathVolumeSourceBuilder().withPath(v.getHostPath().getPath());
            if (hasText(v.getHostPath().getType())) h.withType(v.getHostPath().getType());
            b.withHostPath(h.build());
        }
        return b.build();
    }

    private java.util.List<KeyToPath> toKeyToPath(java.util.List<KeyToPathDTO> items) {
        return items.stream().map(i -> new KeyToPathBuilder()
            .withKey(i.getKey()).withPath(i.getPath()).withMode(i.getMode()).build()).toList();
    }

    private DeploymentStrategy toDeploymentStrategy(StrategyDTO s) {
        if (s == null) return null;
        DeploymentStrategyBuilder b = new DeploymentStrategyBuilder();
        if (hasText(s.getType())) b.withType(s.getType());
        RollingUpdateDTO ru = s.getRollingUpdate();
        if (ru != null && (hasText(ru.getMaxSurge()) || hasText(ru.getMaxUnavailable()))) {
            RollingUpdateDeploymentBuilder r = new RollingUpdateDeploymentBuilder();
            if (hasText(ru.getMaxSurge())) r.withMaxSurge(new IntOrString(ru.getMaxSurge()));
            if (hasText(ru.getMaxUnavailable())) r.withMaxUnavailable(new IntOrString(ru.getMaxUnavailable()));
            b.withRollingUpdate(r.build());
        }
        return b.build();
    }

    private StatefulSetUpdateStrategy toStatefulSetUpdateStrategy(StrategyDTO s) {
        if (s == null) return null;
        StatefulSetUpdateStrategyBuilder b = new StatefulSetUpdateStrategyBuilder();
        if (hasText(s.getType())) b.withType(s.getType());
        RollingUpdateDTO ru = s.getRollingUpdate();
        if (ru != null && ru.getPartition() != null)
            b.withRollingUpdate(new RollingUpdateStatefulSetStrategyBuilder().withPartition(ru.getPartition()).build());
        return b.build();
    }

    private DaemonSetUpdateStrategy toDaemonSetUpdateStrategy(StrategyDTO s) {
        if (s == null) return null;
        DaemonSetUpdateStrategyBuilder b = new DaemonSetUpdateStrategyBuilder();
        if (hasText(s.getType())) b.withType(s.getType());
        RollingUpdateDTO ru = s.getRollingUpdate();
        if (ru != null && hasText(ru.getMaxUnavailable()))
            b.withRollingUpdate(new RollingUpdateDaemonSetBuilder()
                .withMaxUnavailable(new IntOrString(ru.getMaxUnavailable())).build());
        return b.build();
    }

    private java.util.List<PersistentVolumeClaim> toPvcTemplates(java.util.List<PvcTemplateDTO> list) {
        if (list == null || list.isEmpty()) return null;
        return list.stream().map(t -> {
            // storage 缺省时不挂 resources（Map.of 不容忍 null 值）
            VolumeResourceRequirements res = hasText(t.getStorage())
                ? new VolumeResourceRequirementsBuilder()
                    .withRequests(java.util.Map.of("storage", new Quantity(t.getStorage()))).build()
                : null;
            return new PersistentVolumeClaimBuilder()
                .withNewMetadata().withName(t.getName()).endMetadata()
                .withNewSpec()
                    .withAccessModes(t.getAccessModes())
                    .withStorageClassName(t.getStorageClassName())
                    .withVolumeMode(t.getVolumeMode())
                    .withResources(res)
                .endSpec().build(); }).toList();
    }

    // ==================== selector/labels 约定（适配器正确性，非业务） ====================

    /**selector 固定 app=name，保证是模板标签的子集 */
    private Map<String, String> selectorLabels(WorkloadDTO dto) {
        return Map.of("app", dto.getName());
    }

    /**模板标签 = 用户标签 + app=name（覆盖同名键，保证 selector 可匹配） */
    private Map<String, String> effectiveLabels(WorkloadDTO dto) {
        Map<String, String> labels = new LinkedHashMap<>();
        if (dto.getLabels() != null) {
            labels.putAll(dto.getLabels());
        }
        labels.put("app", dto.getName());
        return labels;
    }

    // ==================== 判空工具 ====================

    private static boolean hasText(String s) {
        return s != null && !s.trim().isEmpty();
    }

    private static boolean notEmpty(java.util.Collection<?> c) {
        return c != null && !c.isEmpty();
    }

}
