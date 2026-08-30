package com.coding.platformapi.services.validation;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.k8s.dto.*;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**工作负载业务校验（§5 硬约束，kind 感知）。失败抛 CloudPlatformException(中文)，不打到 k8s-server */
@Component
public class WorkloadValidator {

    private static final String KIND_DEPLOYMENT = "deployment";
    private static final String KIND_STATEFULSET = "statefulset";
    private static final String KIND_DAEMONSET = "daemonset";
    private static final Set<String> VALID_KINDS = Set.of(KIND_DEPLOYMENT, KIND_STATEFULSET, KIND_DAEMONSET);

    public void validate(WorkloadDTO dto) {
        if (dto == null) throw err("工作负载不能为空");
        String kind = dto.getKind() != null ? dto.getKind().trim().toLowerCase() : "";
        if (!VALID_KINDS.contains(kind)) throw err("不支持的工作负载类型: " + dto.getKind());
        PodSpecDTO spec = podSpecOf(dto);

        validateA(spec, kind, dto);   // A 互斥/条件（含忽略类字段就地置 null）
        validateB(spec, kind, dto);   // B 取值约束（含 B4 严格 quantity）
        validateC(spec, kind, dto);   // C 唯一性/引用完整性
    }

    private PodSpecDTO podSpecOf(WorkloadDTO dto) {
        if (dto.getPodTemplate() == null || dto.getPodTemplate().getSpec() == null)
            throw err("缺少 Pod 模板（至少需要一个容器）");
        return dto.getPodTemplate().getSpec();
    }

    // ---------- A 互斥 / 条件字段 ----------
    private void validateA(PodSpecDTO spec, String kind, WorkloadDTO dto) {
        // A1 init 容器禁止 lifecycle / 三种探针（任一非空即报错）
        for (ContainerDTO c : initContainers(spec)) {
            String ctx = "初始化容器「" + c.getName() + "」";
            if (c.getLifecycle() != null || c.getLivenessProbe() != null
                    || c.getReadinessProbe() != null || c.getStartupProbe() != null)
                throw err(ctx + " 不允许配置生命周期或探针");
        }
        // A2/A3 仅主容器（init 已被 A1 禁止这些字段）
        for (ContainerDTO c : mainContainers(spec)) {
            String ctx = "容器「" + c.getName() + "」";
            checkExactlyOneHandler(c.getLivenessProbe(), ctx + " livenessProbe");
            checkExactlyOneHandler(c.getReadinessProbe(), ctx + " readinessProbe");
            checkExactlyOneHandler(c.getStartupProbe(), ctx + " startupProbe");
            if (c.getLifecycle() != null) {
                checkExactlyOneHook(c.getLifecycle().getPostStart(), ctx + " postStart");
                checkExactlyOneHook(c.getLifecycle().getPreStop(), ctx + " preStop");
            }
        }
        // A4 strategy 非滚动更新时丢弃 rollingUpdate；A5 daemonset 丢弃 maxSurge
        StrategyDTO st = dto.getStrategy();
        if (st != null && st.getRollingUpdate() != null) {
            boolean nonRolling = "Recreate".equals(st.getType()) || "OnDelete".equals(st.getType());
            if (nonRolling) { st.setRollingUpdate(null); return; }
            if (KIND_DAEMONSET.equals(kind)) st.getRollingUpdate().setMaxSurge(null);
        }
    }

    private void checkExactlyOneHandler(ProbeDTO p, String ctx) {
        if (p == null) return;
        int n = (p.getHttpGet() != null ? 1 : 0) + (p.getTcpSocket() != null ? 1 : 0) + (p.getExec() != null ? 1 : 0);
        if (n != 1) throw err(ctx + " 必须且只能配置一种探测方式（httpGet/tcpSocket/exec）");
    }

    private void checkExactlyOneHook(HandlerDTO h, String ctx) {
        if (h == null) return;
        int n = (h.getExec() != null ? 1 : 0) + (h.getHttpGet() != null ? 1 : 0) + (h.getSleep() != null ? 1 : 0);
        if (n != 1) throw err(ctx + " 必须且只能配置一种动作（exec/httpGet/sleep）");
    }

    // ---------- B 取值约束 ----------
    private void validateB(PodSpecDTO spec, String kind, WorkloadDTO dto) {
        // B1 restartPolicy 仅 Always
        if (StringUtils.hasText(spec.getRestartPolicy()) && !"Always".equals(spec.getRestartPolicy()))
            throw err("工作负载 restartPolicy 仅支持 Always");
        // B2/B3 探针取值约束 —— 仅主容器
        for (ContainerDTO c : mainContainers(spec)) {
            String ctx = "容器「" + c.getName() + "」";
            if (c.getLivenessProbe() != null && c.getLivenessProbe().getSuccessThreshold() != null
                    && c.getLivenessProbe().getSuccessThreshold() != 1)
                throw err(ctx + " livenessProbe.successThreshold 必须为 1");
            if (c.getStartupProbe() != null && c.getStartupProbe().getSuccessThreshold() != null
                    && c.getStartupProbe().getSuccessThreshold() != 1)
                throw err(ctx + " startupProbe.successThreshold 必须为 1");
            for (Map.Entry<String, ProbeDTO> e : probeEntries(c).entrySet()) {
                ProbeDTO p = e.getValue();
                if (p == null) continue;
                if (p.getPeriodSeconds() != null && p.getPeriodSeconds() < 1)
                    throw err(ctx + " " + e.getKey() + ".periodSeconds 最小为 1");
                if (p.getTimeoutSeconds() != null && p.getTimeoutSeconds() < 1)
                    throw err(ctx + " " + e.getKey() + ".timeoutSeconds 最小为 1");
            }
        }
        // B4 resources requests ≤ limits —— 所有容器（init+main）
        for (ContainerDTO c : allContainers(spec)) {
            checkResources("容器「" + c.getName() + "」", c.getResources());
        }
        // B5 deployment maxSurge 与 maxUnavailable 不能同时为 0
        if (KIND_DEPLOYMENT.equals(kind) && dto.getStrategy() != null && "RollingUpdate".equals(dto.getStrategy().getType())) {
            RollingUpdateDTO ru = dto.getStrategy().getRollingUpdate();
            if (ru != null && isZero(ru.getMaxSurge()) && isZero(ru.getMaxUnavailable()))
                throw err("Deployment 滚动更新 maxSurge 与 maxUnavailable 不能同时为 0");
        }
        // B6/B7 toleration
        if (spec.getTolerations() != null) {
            for (TolerationDTO t : spec.getTolerations()) {
                boolean keyBlank = !StringUtils.hasText(t.getKey());
                String op = StringUtils.hasText(t.getOperator()) ? t.getOperator() : "Equal";
                if (keyBlank && !"Exists".equals(op))
                    throw err("toleration 未填 key 时 operator 必须为 Exists");
                if ("Exists".equals(op) && StringUtils.hasText(t.getValue()))
                    throw err("toleration operator=Exists 时不能填 value");
                if (!"Exists".equals(op) && !StringUtils.hasText(t.getValue()))
                    throw err("toleration operator=" + op + " 时 value 必填");
                // B7 tolerationSeconds 仅 NoExecute 有效，否则丢弃
                if (t.getTolerationSeconds() != null && !"NoExecute".equals(t.getEffect()))
                    t.setTolerationSeconds(null);
            }
        }
    }

    private void checkResources(String ctx, ResourcesDTO r) {
        if (r == null || r.getRequests() == null || r.getRequests().isEmpty()) return;
        Map<String, String> lim = r.getLimits();
        for (Map.Entry<String, String> e : r.getRequests().entrySet()) {
            if (lim == null || !lim.containsKey(e.getKey())) continue; // 仅两者都有才比较
            if (K8sQuantity.compare(e.getValue(), lim.get(e.getKey())) > 0)
                throw err(ctx + " 资源「" + e.getKey() + "」的 requests(" + e.getValue()
                        + ") 不能大于 limits(" + lim.get(e.getKey()) + ")");
        }
    }

    // ---------- C 唯一性 / 引用完整性 ----------
    private void validateC(PodSpecDTO spec, String kind, WorkloadDTO dto) {
        List<ContainerDTO> main = spec.getContainers() != null ? spec.getContainers() : List.of();
        List<ContainerDTO> init = spec.getInitContainers() != null ? spec.getInitContainers() : List.of();

        // C1 至少一个容器
        if (main.isEmpty()) throw err("至少需要一个容器");

        // C2 所有容器名（init+普通）全局唯一 + DNS_LABEL
        Set<String> seen = new HashSet<>();
        for (ContainerDTO c : main) checkName(c, "容器", seen);
        for (ContainerDTO c : init) checkName(c, "初始化容器", seen);

        // C3 volumeMounts[].name 必须引用已定义 volumes[].name
        Set<String> volNames = new HashSet<>();
        if (spec.getVolumes() != null) for (VolumeDTO v : spec.getVolumes()) if (v.getName() != null) volNames.add(v.getName());
        // R12: STS volumeClaimTemplate 名称也是合法挂载引用（K8s 控制器注入，不能同时进 spec.volumes）
        if (KIND_STATEFULSET.equals(kind) && dto.getVolumeClaimTemplates() != null)
            for (PvcTemplateDTO t : dto.getVolumeClaimTemplates()) if (t.getName() != null) volNames.add(t.getName());
        for (ContainerDTO c : allContainers(spec)) {
            if (c.getVolumeMounts() == null) continue;
            for (VolumeMountDTO m : c.getVolumeMounts()) {
                if (!StringUtils.hasText(m.getName())) throw err("容器「" + c.getName() + "」存在未命名卷挂载");
                if (!volNames.contains(m.getName()))
                    throw err("容器「" + c.getName() + "」挂载的卷「" + m.getName() + "」未在 volumes 中定义");
            }
        }

        // C4 STS：每个 volumeClaimTemplate.name 必须被至少一个容器的 volumeMount 同名引用
        if (KIND_STATEFULSET.equals(kind) && dto.getVolumeClaimTemplates() != null) {
            Set<String> mounted = new HashSet<>();
            for (ContainerDTO c : allContainers(spec)) {
                if (c.getVolumeMounts() == null) continue;
                for (VolumeMountDTO m : c.getVolumeMounts()) if (m.getName() != null) mounted.add(m.getName());
            }
            for (PvcTemplateDTO t : dto.getVolumeClaimTemplates()) {
                if (!mounted.contains(t.getName()))
                    throw err("volumeClaimTemplate「" + t.getName() + "」需至少一个容器以同名 volumeMount 引用");
            }
        }
    }

    private void checkName(ContainerDTO c, String label, Set<String> seen) {
        String n = c.getName();
        if (!StringUtils.hasText(n)) throw err(label + " 名称不能为空");
        if (!n.matches("^[a-z0-9]([-a-z0-9]*[a-z0-9])?$"))
            throw err(label + " 名称「" + n + "」需符合 DNS_LABEL（小写字母/数字/-，字母或数字开头结尾）");
        if (!seen.add(n)) throw err("容器名重复：「" + n + "」（所有容器名须全局唯一）");
    }

    // ---------- 工具 ----------
    private List<ContainerDTO> mainContainers(PodSpecDTO spec) {
        return spec.getContainers() != null ? spec.getContainers() : List.of();
    }

    private List<ContainerDTO> initContainers(PodSpecDTO spec) {
        return spec.getInitContainers() != null ? spec.getInitContainers() : List.of();
    }

    /**init + main，供 B4(resources) 与 C 规则使用*/
    private List<ContainerDTO> allContainers(PodSpecDTO spec) {
        List<ContainerDTO> all = new ArrayList<>();
        all.addAll(initContainers(spec));
        all.addAll(mainContainers(spec));
        return all;
    }

    private Map<String, ProbeDTO> probeEntries(ContainerDTO c) {
        Map<String, ProbeDTO> m = new java.util.LinkedHashMap<>();
        m.put("livenessProbe", c.getLivenessProbe());
        m.put("readinessProbe", c.getReadinessProbe());
        m.put("startupProbe", c.getStartupProbe());
        return m;
    }

    private boolean isZero(String quantity) {
        if (!StringUtils.hasText(quantity)) return false; // 未填不算 0（K8s 有默认值）
        try { return K8sQuantity.parse(quantity).signum() == 0; }
        catch (CloudPlatformException ex) { throw err("无法解析数量: " + quantity); }
    }

    private CloudPlatformException err(String msg) {
        return new CloudPlatformException(EnumResponseType.ERROR, msg);
    }
}
