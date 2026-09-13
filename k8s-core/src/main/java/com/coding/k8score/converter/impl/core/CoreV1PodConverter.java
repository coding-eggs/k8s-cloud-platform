package com.coding.k8score.converter.impl.core;

import com.coding.common.models.k8s.dto.*;
import com.coding.k8score.converter.CommonConverter;
import io.fabric8.kubernetes.api.model.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pod ⇄ PodDTO（只读：列表/查询返回；Pod 不支持平台侧创建/更新）。
 * revert 额外产出 {@code containerDetails}：把 spec 容器定义与 status 运行态按名合并，供详情页展示。
 */
public class CoreV1PodConverter implements CommonConverter<Pod, PodDTO> {

    @Override
    public Pod convert(PodDTO in) {
        throw new UnsupportedOperationException("Pod 不支持平台侧创建");
    }

    @Override
    public PodDTO revert(Pod pod) {
        PodDTO dto = new PodDTO();
        if (pod.getMetadata() != null) {
            dto.setName(pod.getMetadata().getName());
            dto.setNamespace(pod.getMetadata().getNamespace());
            dto.setLabels(pod.getMetadata().getLabels());
            if (pod.getMetadata().getAnnotations() != null && !pod.getMetadata().getAnnotations().isEmpty()) {
                Map<String, Object> ann = new HashMap<>(pod.getMetadata().getAnnotations());
                dto.setAnnotations(ann);
            }
            if (pod.getMetadata().getCreationTimestamp() != null) {
                dto.setCreationTime(pod.getMetadata().getCreationTimestamp());
            }
        }
        dto.setNodeName(pod.getSpec().getNodeName());
        dto.setServiceAccountName(pod.getSpec().getServiceAccountName());

        Map<String, ContainerStatus> regStatus = new HashMap<>();
        Map<String, ContainerStatus> initStatus = new HashMap<>();
        if (pod.getStatus() != null) {
            if (pod.getStatus().getContainerStatuses() != null)
                for (ContainerStatus cs : pod.getStatus().getContainerStatuses()) {
                    regStatus.put(cs.getName(), cs);
                }
            if (pod.getStatus().getInitContainerStatuses() != null)
                for (ContainerStatus cs : pod.getStatus().getInitContainerStatuses()) {
                    initStatus.put(cs.getName(), cs);
                }
        }

        List<PodContainerDTO> details = new ArrayList<>();
        if (pod.getSpec() != null) {
            if (pod.getSpec().getContainers() != null) {
                dto.setContainers(pod.getSpec().getContainers().stream().map(Container::getName).toList());
                for (Container c : pod.getSpec().getContainers()) {
                    details.add(toDetail(c, regStatus.get(c.getName()), false));
                }
            }
            if (pod.getSpec().getInitContainers() != null) {
                for (Container c : pod.getSpec().getInitContainers()) {
                    details.add(toDetail(c, initStatus.get(c.getName()), true));
                }
            }
        }
        dto.setContainerDetails(details);

        if (pod.getStatus() != null) {
            dto.setPhase(pod.getStatus().getPhase());
            dto.setPodIp(pod.getStatus().getPodIP());
            dto.setHostIp(pod.getStatus().getHostIP());
            int restarts = 0;
            for (PodContainerDTO d : details) restarts += (d.getRestartCount() != null ? d.getRestartCount() : 0);
            dto.setRestarts(restarts);
            dto.setStatusReason(buildStatusReason(pod, details));
        }
        return dto;
    }

    /**
     * 汇总 Pod「为什么不成功」：非 True 的 pod 条件（调度/就绪）+ 非 Running 容器的 reason/message。
     * 健康时（条件全 True、容器全 Running）返回 null → 前端不显示悬浮提示。
     */
    private String buildStatusReason(Pod pod, List<PodContainerDTO> details) {
        List<String> lines = new ArrayList<>();
        if (pod.getStatus() != null && pod.getStatus().getConditions() != null) {
            Map<String, String> hasKey = new HashMap<>();
            for (PodCondition c : pod.getStatus().getConditions()) {
                if ("True".equals(c.getStatus())) continue;
                if (hasKey.containsKey(c.getReason() + c.getMessage())) continue;
                String line = condLine(c.getType(), c.getReason(), c.getMessage());
                if (line != null) {
                    lines.add(line);
                    hasKey.put (c.getReason() + c.getMessage(), line);
                }
            }
        }
        for (PodContainerDTO d : details) {
            if (!"Running".equals(d.getState())  && !"Completed".equals(d.getReason())) {
                StringBuilder sb = new StringBuilder();
                if (hasText(d.getName())) sb.append('[').append(d.getName()).append("] ");
                sb.append(d.getReason());
                if (hasText(d.getMessage())) sb.append(": ").append(d.getMessage());
                lines.add(sb.toString());
            }
        }
        return lines.isEmpty() ? null : String.join("\n", lines);
    }

    /** 条件行：Type reason: message（缺哪段省哪段，全空返回 null） */
    private static String condLine(String type, String reason, String message) {
        StringBuilder sb = new StringBuilder();
        if (hasText(type)) sb.append(type);
        if (hasText(reason)) {
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(reason);
        }
        if (hasText(message)) {
            if (!sb.isEmpty()) sb.append(": ");
            sb.append(message);
        }
        return sb.isEmpty() ? null : sb.toString();
    }

    private static boolean hasText(String s) {
        return s != null && !s.trim().isEmpty();
    }

    // ==================== spec + status → PodContainerDTO ====================

    private PodContainerDTO toDetail(Container c, ContainerStatus st, boolean init) {
        PodContainerDTO d = new PodContainerDTO();
        d.setName(c.getName());
        d.setImage(c.getImage());
        d.setCommand(c.getCommand());
        d.setArgs(c.getArgs());
        d.setWorkingDir(c.getWorkingDir());
        d.setImagePullPolicy(c.getImagePullPolicy());
        if (c.getEnv() != null) d.setEnvs(c.getEnv().stream().map(this::fromEnv).toList());
        if (c.getEnvFrom() != null) d.setEnvFrom(c.getEnvFrom().stream().map(this::fromEnvFrom).toList());
        if (c.getPorts() != null) d.setPorts(c.getPorts().stream().map(this::fromPort).toList());
        if (c.getResources() != null) d.setResources(fromResources(c.getResources()));
        if (c.getLifecycle() != null) d.setLifecycle(fromLifecycle(c.getLifecycle()));
        if (c.getLivenessProbe() != null) d.setLivenessProbe(fromProbe(c.getLivenessProbe()));
        if (c.getReadinessProbe() != null) d.setReadinessProbe(fromProbe(c.getReadinessProbe()));
        if (c.getStartupProbe() != null) d.setStartupProbe(fromProbe(c.getStartupProbe()));
        if (c.getVolumeMounts() != null)
            d.setVolumeMounts(c.getVolumeMounts().stream().map(this::fromVolumeMount).toList());

        d.setInit(init);
        if (st != null) {
            d.setReady(st.getReady());
            d.setRestartCount(st.getRestartCount() != null ? st.getRestartCount() : 0);
            ContainerState state = st.getState();
            if (state != null) {
                if (state.getRunning() != null) {
                    d.setState("Running");
                    d.setStartedAt(state.getRunning().getStartedAt());
                } else if (state.getWaiting() != null) {
                    d.setState("Waiting");
                    d.setReason(state.getWaiting().getReason());
                    d.setMessage(state.getWaiting().getMessage());
                } else if (state.getTerminated() != null) {
                    d.setState("Terminated");
                    d.setReason(state.getTerminated().getReason());
                    d.setMessage(state.getTerminated().getMessage());
                }
            }
        }
        return d;
    }

    private EnvDTO fromEnv(EnvVar e) {
        EnvDTO d = new EnvDTO();
        d.setName(e.getName());
        d.setValue(e.getValue());
        if (e.getValueFrom() != null) d.setValueFrom(fromValueFrom(e.getValueFrom()));
        return d;
    }

    private ValueFromDTO fromValueFrom(EnvVarSource v) {
        ValueFromDTO d = new ValueFromDTO();
        if (v.getConfigMapKeyRef() != null) {
            ConfigMapKeySelectorDTO x = new ConfigMapKeySelectorDTO();
            x.setName(v.getConfigMapKeyRef().getName());
            x.setKey(v.getConfigMapKeyRef().getKey());
            x.setOptional(v.getConfigMapKeyRef().getOptional());
            d.setConfigMapKeyRef(x);
        }
        if (v.getSecretKeyRef() != null) {
            SecretKeySelectorDTO x = new SecretKeySelectorDTO();
            x.setName(v.getSecretKeyRef().getName());
            x.setKey(v.getSecretKeyRef().getKey());
            x.setOptional(v.getSecretKeyRef().getOptional());
            d.setSecretKeyRef(x);
        }
        if (v.getFieldRef() != null) {
            ObjectFieldSelectorDTO x = new ObjectFieldSelectorDTO();
            x.setApiVersion(v.getFieldRef().getApiVersion());
            x.setFieldPath(v.getFieldRef().getFieldPath());
            d.setFieldRef(x);
        }

        if (v.getFileKeyRef() != null) {
            FileKeySelectorDTO x = new FileKeySelectorDTO();
            x.setKey(v.getFileKeyRef().getKey());
            x.setOptional(v.getFileKeyRef().getOptional());
            x.setPath(v.getFileKeyRef().getPath());
            x.setVolumeName(v.getFileKeyRef().getVolumeName());
            d.setFileKeyRef(x);
        }

        if (v.getResourceFieldRef() != null) {
            ResourceFieldSelectorDTO x = new ResourceFieldSelectorDTO();
            x.setContainerName(v.getResourceFieldRef().getContainerName());
            x.setResource(v.getResourceFieldRef().getResource());
            x.setDivisor(v.getResourceFieldRef().getDivisor().toString());
            d.setResourceFieldRef(x);
        }

        return d;
    }

    private EnvFromDTO fromEnvFrom(EnvFromSource f) {
        EnvFromDTO d = new EnvFromDTO();
        d.setPrefix(f.getPrefix());
        if (f.getConfigMapRef() != null) {
            ConfigMapRefDTO x = new ConfigMapRefDTO();
            x.setName(f.getConfigMapRef().getName());
            x.setOptional(f.getConfigMapRef().getOptional());
            d.setConfigMapRef(x);
        }
        if (f.getSecretRef() != null) {
            SecretRefDTO x = new SecretRefDTO();
            x.setName(f.getSecretRef().getName());
            x.setOptional(f.getSecretRef().getOptional());
            d.setSecretRef(x);
        }
        return d;
    }

    private PortDTO fromPort(ContainerPort p) {
        PortDTO d = new PortDTO();
        d.setContainerPort(p.getContainerPort());
        d.setProtocol(p.getProtocol());
        d.setName(p.getName());
        return d;
    }

    private ResourcesDTO fromResources(ResourceRequirements r) {
        ResourcesDTO d = new ResourcesDTO();
        d.setLimits(fromQuantities(r.getLimits()));
        d.setRequests(fromQuantities(r.getRequests()));
        return d;
    }

    private Map<String, String> fromQuantities(Map<String, Quantity> m) {
        if (m == null) return null;
        Map<String, String> r = new LinkedHashMap<>();
        for (Map.Entry<String, Quantity> e : m.entrySet())
            r.put(e.getKey(), e.getValue() != null ? e.getValue().toString() : null);
        return r;
    }

    private LifecycleDTO fromLifecycle(Lifecycle l) {
        LifecycleDTO d = new LifecycleDTO();
        if (l.getPostStart() != null) d.setPostStart(fromHandler(l.getPostStart()));
        if (l.getPreStop() != null) d.setPreStop(fromHandler(l.getPreStop()));
        return d;
    }

    private HandlerDTO fromHandler(LifecycleHandler h) {
        HandlerDTO d = new HandlerDTO();
        if (h.getExec() != null) {
            ExecActionDTO x = new ExecActionDTO();
            x.setCommand(h.getExec().getCommand());
            d.setExec(x);
        }
        if (h.getHttpGet() != null) {
            HttpGetActionDTO x = new HttpGetActionDTO();
            x.setPort(h.getHttpGet().getPort() != null ? h.getHttpGet().getPort().getStrVal() : null);
            x.setPath(h.getHttpGet().getPath());
            x.setScheme(h.getHttpGet().getScheme());
            d.setHttpGet(x);
        }
        if (h.getSleep() != null) {
            SleepActionDTO x = new SleepActionDTO();
            x.setSeconds(h.getSleep().getSeconds());
            d.setSleep(x);
        }
        return d;
    }

    private ProbeDTO fromProbe(Probe p) {
        ProbeDTO d = new ProbeDTO();
        if (p.getHttpGet() != null) {
            HttpGetActionDTO x = new HttpGetActionDTO();
            x.setPort(p.getHttpGet().getPort() != null ? p.getHttpGet().getPort().getStrVal(): null);
            x.setPath(p.getHttpGet().getPath());
            x.setScheme(p.getHttpGet().getScheme());
            d.setHttpGet(x);
        }
        if (p.getTcpSocket() != null) {
            TCPSocketActionDTO x = new TCPSocketActionDTO();
            x.setPort(p.getTcpSocket().getPort() != null ? p.getTcpSocket().getPort().getStrVal() : null);
            d.setTcpSocket(x);
        }
        if (p.getExec() != null) {
            ExecActionDTO x = new ExecActionDTO();
            x.setCommand(p.getExec().getCommand());
            d.setExec(x);
        }
        d.setInitialDelaySeconds(p.getInitialDelaySeconds());
        d.setPeriodSeconds(p.getPeriodSeconds());
        d.setTimeoutSeconds(p.getTimeoutSeconds());
        d.setSuccessThreshold(p.getSuccessThreshold());
        d.setFailureThreshold(p.getFailureThreshold());
        return d;
    }

    private VolumeMountDTO fromVolumeMount(VolumeMount m) {
        VolumeMountDTO d = new VolumeMountDTO();
        d.setName(m.getName());
        d.setMountPath(m.getMountPath());
        d.setReadOnly(m.getReadOnly());
        d.setSubPath(m.getSubPath());
        return d;
    }

}
