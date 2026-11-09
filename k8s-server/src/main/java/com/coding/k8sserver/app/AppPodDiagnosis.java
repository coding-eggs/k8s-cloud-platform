package com.coding.k8sserver.app;

import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.Event;
import io.fabric8.kubernetes.api.model.LabelSelector;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodCondition;
import io.fabric8.kubernetes.api.model.apps.ReplicaSet;
import io.fabric8.kubernetes.api.model.apps.ReplicaSetCondition;
import io.fabric8.kubernetes.client.KubernetesClient;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 工作负载未就绪时下钻到 Pod：告诉用户「卡在哪」—— 调度不上、拉不到镜像、容器崩溃、探针没过……
 *
 * <p>只看未就绪的 Pod（滚动更新时旧 Pod 还就绪，不必列），最多 {@link #MAX_PODS} 个；
 * 状态本身说不清时（容器在跑但没就绪 / 原因只是 ContainerCreating）再查一次该 Pod 最近的 Warning 事件。
 * 任何异常都吞掉返回空：诊断是锦上添花，不能让状态查询失败。
 *
 * <p>Deployment 另看一眼它新一代的 ReplicaSet：Pod 建不出来时（最常见是命名空间配额不足）
 * 集合里根本看不到那个 Pod，只报「可用副本 0/1」会让人以为是配置没生效。
 */
@Slf4j
final class AppPodDiagnosis {

    static final int MAX_PODS = 3;
    private static final int TEXT_MAX = 300;

    private AppPodDiagnosis() {
    }

    static List<String> diagnose(KubernetesClient client, String namespace, LabelSelector selector) {
        return diagnose(client, namespace, selector, null);
    }

    /**
     * @param deployment Deployment 名：给了就顺带看它的 ReplicaSet 有没有 FailedCreate（配额 / 准入拦下）
     */
    static List<String> diagnose(KubernetesClient client, String namespace, LabelSelector selector,
                                 String deployment) {
        try {
            Map<String, String> labels = selector == null ? null : selector.getMatchLabels();
            if (labels == null || labels.isEmpty()) {
                return List.of();
            }
            List<Pod> pods = client.pods().inNamespace(namespace).withLabels(labels).list().getItems();
            List<String> out = new ArrayList<>();
            String failed = deployment == null ? null : replicaSetProblem(client, namespace, labels, deployment);
            if (pods.isEmpty()) {
                out.add(failed != null ? failed
                        : "还没有 Pod（控制器尚未创建，或被配额 / 准入拦下，可在工作负载事件里查看）");
                return out;
            }
            if (failed != null) {
                out.add(failed);   // 新副本建不出来时，这比旧 Pod 的崩溃更该先说
            }
            pods.stream()
                    .filter(p -> !ready(p) && p.getMetadata().getDeletionTimestamp() == null)
                    .sorted(Comparator.comparing((Pod p) -> p.getMetadata().getCreationTimestamp(),
                            Comparator.nullsLast(Comparator.reverseOrder())))
                    .limit(MAX_PODS)
                    .forEach(p -> {
                        String issue = describe(p);
                        if (issue.isEmpty() || needsEvent(p)) {
                            String event = latestWarning(client, namespace, p.getMetadata().getName());
                            if (event != null) {
                                issue = issue.isEmpty() ? event : issue + "；" + event;
                            }
                        }
                        out.add(clip("Pod " + p.getMetadata().getName() + "：" + (issue.isEmpty() ? "启动中" : issue)));
                    });
            return out;
        } catch (RuntimeException e) {
            log.debug("Pod 诊断失败 {}：{}", namespace, e.getMessage());
            return List.of();
        }
    }

    static boolean ready(Pod p) {
        return p.getStatus() != null && p.getStatus().getConditions() != null
                && p.getStatus().getConditions().stream()
                .anyMatch(c -> "Ready".equals(c.getType()) && "True".equals(c.getStatus()));
    }

    /**
     * 这个 Deployment 的 ReplicaSet 里报 FailedCreate 的（最新一个）：Pod 压根没被创建出来时，
     * 只有这里说得出原因（配额不足、准入拒绝）。查不到返回 null。
     */
    private static String replicaSetProblem(KubernetesClient client, String namespace,
                                            Map<String, String> labels, String deployment) {
        try {
            List<ReplicaSet> sets = client.apps().replicaSets().inNamespace(namespace).withLabels(labels)
                    .list().getItems().stream()
                    .filter(rs -> ownedBy(rs, deployment))
                    .toList();
            return replicaSetProblem(sets);
        } catch (RuntimeException e) {
            log.debug("ReplicaSet 诊断失败 {}/{}：{}", namespace, deployment, e.getMessage());
            return null;
        }
    }

    /** 纯函数：给一组 ReplicaSet，说出最新的那条创建失败（没有则 null）。 */
    static String replicaSetProblem(List<ReplicaSet> sets) {
        ReplicaSet newest = null;
        String message = null;
        for (ReplicaSet rs : sets) {
            String failure = failureMessage(rs);
            if (failure == null) {
                continue;
            }
            String created = String.valueOf(rs.getMetadata().getCreationTimestamp());
            if (newest == null || created.compareTo(String.valueOf(newest.getMetadata().getCreationTimestamp())) > 0) {
                newest = rs;
                message = failure;
            }
        }
        if (newest == null) {
            return null;
        }
        String hint = message.contains("exceeded quota")
                ? "（命名空间配额不足：调高配额或释放已用资源后控制器会自己重试，最长约 15 分钟）"
                : "";
        return clip("ReplicaSet " + newest.getMetadata().getName() + " 创建 Pod 失败：" + message + hint);
    }

    private static String failureMessage(ReplicaSet rs) {
        if (rs.getStatus() == null || rs.getStatus().getConditions() == null) {
            return null;
        }
        for (ReplicaSetCondition c : rs.getStatus().getConditions()) {
            if ("ReplicaFailure".equals(c.getType()) && "True".equals(c.getStatus())
                    && c.getMessage() != null && !c.getMessage().isBlank()) {
                return c.getMessage().trim();
            }
        }
        return null;
    }

    private static boolean ownedBy(ReplicaSet rs, String deployment) {
        if (rs.getMetadata() == null || rs.getMetadata().getOwnerReferences() == null) {
            return false;
        }
        return rs.getMetadata().getOwnerReferences().stream()
                .anyMatch(o -> "Deployment".equals(o.getKind()) && deployment.equals(o.getName()));
    }

    /** 一个未就绪 Pod 的现状（纯函数，单测直接断言）；说不清返回空串。 */
    static String describe(Pod p) {
        if (p.getStatus() == null) {
            return "";
        }
        String phase = p.getStatus().getPhase();
        if ("Pending".equals(phase)) {
            PodCondition scheduled = condition(p, "PodScheduled");
            if (scheduled != null && "False".equals(scheduled.getStatus())) {
                return "调度失败（" + nz(scheduled.getReason()) + "）：" + nz(scheduled.getMessage());
            }
        }
        if ("Failed".equals(phase)) {
            return "Pod 已失败：" + nz(p.getStatus().getReason()) + " " + nz(p.getStatus().getMessage());
        }
        String init = containers(p.getStatus().getInitContainerStatuses(), true);
        if (!init.isEmpty()) {
            return init;
        }
        String main = containers(p.getStatus().getContainerStatuses(), false);
        if (!main.isEmpty()) {
            return main;
        }
        if ("Pending".equals(phase)) {
            return "等待调度 / 创建容器";
        }
        return "";
    }

    private static String containers(List<ContainerStatus> statuses, boolean init) {
        if (statuses == null) {
            return "";
        }
        String kind = init ? "初始化容器 " : "容器 ";
        for (ContainerStatus c : statuses) {
            if (init && c.getState() != null && c.getState().getTerminated() != null
                    && Objects.equals(c.getState().getTerminated().getExitCode(), 0)) {
                continue;   // 已成功的初始化容器
            }
            if (c.getState() != null && c.getState().getWaiting() != null) {
                String reason = nz(c.getState().getWaiting().getReason());
                String msg = nz(c.getState().getWaiting().getMessage());
                String last = lastTermination(c);
                return kind + c.getName() + " " + waitingText(reason) + (msg.isEmpty() ? "" : "：" + msg) + last;
            }
            if (c.getState() != null && c.getState().getTerminated() != null) {
                var t = c.getState().getTerminated();
                return kind + c.getName() + " 已退出（退出码 " + t.getExitCode() + "，" + nz(t.getReason()) + "）"
                        + (nz(t.getMessage()).isEmpty() ? "" : "：" + t.getMessage());
            }
            if (init && c.getState() != null && c.getState().getRunning() != null) {
                return kind + c.getName() + " 运行中（主容器要等它完成）";
            }
            if (!init && c.getState() != null && c.getState().getRunning() != null && !Boolean.TRUE.equals(c.getReady())) {
                return kind + c.getName() + " 已启动但未就绪（就绪探针未通过）"
                        + (c.getRestartCount() != null && c.getRestartCount() > 0 ? "，已重启 " + c.getRestartCount() + " 次" : "");
            }
        }
        return "";
    }

    private static String waitingText(String reason) {
        return switch (reason) {
            case "ContainerCreating" -> "创建中（ContainerCreating）";
            case "PodInitializing" -> "等待初始化（PodInitializing）";
            case "ErrImagePull", "ImagePullBackOff" -> "拉取镜像失败（" + reason + "）";
            case "InvalidImageName" -> "镜像名不合法（InvalidImageName）";
            case "CrashLoopBackOff" -> "反复崩溃（CrashLoopBackOff）";
            case "CreateContainerConfigError" -> "容器配置错误（CreateContainerConfigError，常见于引用的 Secret / ConfigMap 不存在）";
            case "CreateContainerError" -> "创建容器失败（CreateContainerError）";
            default -> "等待中（" + reason + "）";
        };
    }

    private static String lastTermination(ContainerStatus c) {
        if (c.getLastState() == null || c.getLastState().getTerminated() == null) {
            return "";
        }
        var t = c.getLastState().getTerminated();
        return "；上次退出码 " + t.getExitCode() + "（" + nz(t.getReason()) + "），已重启 " + c.getRestartCount() + " 次";
    }

    /** 容器在跑却没就绪、或只是在创建中：原因要看事件（探针失败、挂卷失败等） */
    private static boolean needsEvent(Pod p) {
        String d = describe(p);
        return d.contains("未就绪") || d.contains("ContainerCreating") || d.contains("等待调度");
    }

    private static String latestWarning(KubernetesClient client, String namespace, String pod) {
        try {
            List<Event> events = client.v1().events().inNamespace(namespace)
                    .withField("involvedObject.name", pod).list().getItems();
            return events.stream()
                    .filter(e -> "Warning".equals(e.getType()))
                    .max(Comparator.comparing(AppPodDiagnosis::eventTime, Comparator.nullsFirst(Comparator.naturalOrder())))
                    .map(e -> "最近事件 " + nz(e.getReason()) + "：" + nz(e.getMessage()).trim())
                    .orElse(null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String eventTime(Event e) {
        if (e.getLastTimestamp() != null) {
            return e.getLastTimestamp();
        }
        return e.getEventTime() != null ? e.getEventTime().getTime() : e.getMetadata().getCreationTimestamp();
    }

    private static PodCondition condition(Pod p, String type) {
        return p.getStatus().getConditions() == null ? null : p.getStatus().getConditions().stream()
                .filter(c -> type.equals(c.getType())).findFirst().orElse(null);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String clip(String s) {
        return s.length() <= TEXT_MAX ? s : s.substring(0, TEXT_MAX - 1) + "…";
    }
}
