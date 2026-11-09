package com.coding.k8sserver.app;

import io.fabric8.kubernetes.api.model.ContainerStatusBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.PodConditionBuilder;
import io.fabric8.kubernetes.api.model.apps.ReplicaSet;
import io.fabric8.kubernetes.api.model.apps.ReplicaSetBuilder;
import io.fabric8.kubernetes.api.model.apps.ReplicaSetConditionBuilder;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AppPodDiagnosisTest {

    private static PodBuilder pod(String phase) {
        return new PodBuilder().withNewMetadata().withName("p").endMetadata()
                .withNewStatus().withPhase(phase).endStatus();
    }

    @Test
    void unschedulablePodSaysWhy() {
        Pod p = pod("Pending").editStatus().withConditions(new PodConditionBuilder().withType("PodScheduled")
                .withStatus("False").withReason("Unschedulable").withMessage("0/3 nodes are available").build())
                .endStatus().build();

        assertThat(AppPodDiagnosis.describe(p)).contains("调度失败").contains("Unschedulable").contains("0/3 nodes");
    }

    @Test
    void imagePullBackOffIsNamed() {
        Pod p = pod("Pending").editStatus().withContainerStatuses(new ContainerStatusBuilder().withName("web")
                .withNewState().withNewWaiting().withReason("ImagePullBackOff").withMessage("not found")
                .endWaiting().endState().build()).endStatus().build();

        assertThat(AppPodDiagnosis.describe(p)).isEqualTo("容器 web 拉取镜像失败（ImagePullBackOff）：not found");
    }

    @Test
    void crashLoopIncludesLastExitCode() {
        Pod p = pod("Running").editStatus().withContainerStatuses(new ContainerStatusBuilder().withName("web")
                .withRestartCount(4)
                .withNewState().withNewWaiting().withReason("CrashLoopBackOff").endWaiting().endState()
                .withNewLastState().withNewTerminated().withExitCode(1).withReason("Error").endTerminated().endLastState()
                .build()).endStatus().build();

        assertThat(AppPodDiagnosis.describe(p)).contains("反复崩溃").contains("上次退出码 1（Error）").contains("已重启 4 次");
    }

    @Test
    void runningButNotReadyBlamesReadinessProbe() {
        Pod p = pod("Running").editStatus().withContainerStatuses(new ContainerStatusBuilder().withName("web")
                .withReady(false).withNewState().withNewRunning().endRunning().endState().build()).endStatus().build();

        assertThat(AppPodDiagnosis.describe(p)).contains("就绪探针未通过");
    }

    @Test
    void finishedInitContainersAreSkippedAndRunningOneIsReported() {
        Pod p = pod("Pending").editStatus().withInitContainerStatuses(
                new ContainerStatusBuilder().withName("a").withNewState().withNewTerminated().withExitCode(0)
                        .endTerminated().endState().build(),
                new ContainerStatusBuilder().withName("b").withNewState().withNewRunning().endRunning().endState().build())
                .endStatus().build();

        assertThat(AppPodDiagnosis.describe(p)).isEqualTo("初始化容器 b 运行中（主容器要等它完成）");
    }

    private static ReplicaSet rs(String name, String created, String conditionStatus, String message) {
        ReplicaSetBuilder b = new ReplicaSetBuilder()
                .withNewMetadata().withName(name).withCreationTimestamp(created).endMetadata();
        if (conditionStatus != null) {
            b = b.withNewStatus().withConditions(new ReplicaSetConditionBuilder().withType("ReplicaFailure")
                    .withStatus(conditionStatus).withReason("FailedCreate").withMessage(message)
                    .build()).endStatus();
        }
        return b.build();
    }

    /**
     * Pod 根本没被创建出来时，只有 ReplicaSet 的 FailedCreate 说得出原因；
     * 否则界面只剩一句「可用副本 0/1」，看着像配置没生效。
     */
    @Test
    void replicaSetThatCannotCreatePodsReportsQuota() {
        String out = AppPodDiagnosis.replicaSetProblem(List.of(rs("web-6f65", "2026-11-09T14:10:13Z", "True",
                "pods \"web-6f65-x\" is forbidden: exceeded quota: default, requested: limits.cpu=2, "
                        + "used: limits.cpu=6500m, limited: limits.cpu=8")));

        assertThat(out).contains("ReplicaSet web-6f65 创建 Pod 失败").contains("exceeded quota")
                .contains("命名空间配额不足");
    }

    @Test
    void newestFailingReplicaSetWinsAndNonQuotaFailuresKeepTheirOwnText() {
        String out = AppPodDiagnosis.replicaSetProblem(List.of(
                rs("web-old", "2026-11-09T13:00:00Z", "True", "exceeded quota: old"),
                rs("web-new", "2026-11-09T14:10:13Z", "True", "pods \"x\" is forbidden: no PriorityClass"),
                rs("web-ok", "2026-11-09T15:00:00Z", null, null)));

        assertThat(out).contains("ReplicaSet web-new").contains("no PriorityClass");
        assertThat(out).doesNotContain("命名空间配额不足");   // 不是配额问题就别硬套
    }

    @Test
    void healthyReplicaSetsAddNothing() {
        assertThat(AppPodDiagnosis.replicaSetProblem(List.of(
                rs("web-a", "2026-11-09T14:10:13Z", "False", "ignored"),
                rs("web-b", "2026-11-09T14:20:00Z", null, null)))).isNull();
        assertThat(AppPodDiagnosis.replicaSetProblem(List.of())).isNull();
    }
}
