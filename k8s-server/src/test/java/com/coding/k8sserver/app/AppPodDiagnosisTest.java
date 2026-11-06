package com.coding.k8sserver.app;

import io.fabric8.kubernetes.api.model.ContainerStatusBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.PodConditionBuilder;
import org.junit.jupiter.api.Test;

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
}
