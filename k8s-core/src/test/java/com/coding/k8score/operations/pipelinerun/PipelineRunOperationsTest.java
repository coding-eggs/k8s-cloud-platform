package com.coding.k8score.operations.pipelinerun;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.models.pipeline.PipelineStepRefDTO;
import com.coding.k8score.pipeline.PipelineNodeRegistry;
import io.fabric8.tekton.v1.StepState;
import io.fabric8.tekton.v1.TaskRun;
import io.fabric8.tekton.v1.TaskRunStatus;
import io.fabric8.tekton.v1.PipelineRun;
import io.fabric8.tekton.v1.WorkspaceBinding;
import com.coding.k8score.converter.impl.pipeline.PipelineRunConverter;
import com.coding.common.models.k8s.dto.PipelineRunDTO;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 标签过滤是**纯字符串规则**，所以抽成静态方法单测；真正的 K8s 调用靠部署后冒烟。
 */
class PipelineRunOperationsTest {

    @Test
    void theManagedSelectorIsExactlyOneLabelEqualsOneValue() {
        assertThat(PipelineRunOperations.MANAGED_SELECTOR)
                .isEqualTo("app.kubernetes.io/managed-by=platform-system");
    }

    @Test
    void aForeignRunIsRefusedByTheGuard() {
        // ⭐ Review Focus #3：集群里别人手工建的 PipelineRun（没有平台标签）
        // —— 归档不碰、**绝不删**。这条守卫是唯一的落点
        assertThatThrownBy(() -> PipelineRunOperations.assertManaged(Map.of("foo", "bar"), "r1"))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("不是平台创建");
    }

    @Test
    void aManagedRunPassesTheGuard() {
        PipelineRunOperations.assertManaged(
                Map.of("app.kubernetes.io/managed-by", "platform-system"), "r1");
    }

    @Test
    void nullLabelsAreRefusedNotTreatedAsManaged() {
        // 缺标签当成"是平台的"是最危险的默认值 —— 必须拒绝
        assertThatThrownBy(() -> PipelineRunOperations.assertManaged(null, "r1"))
                .isInstanceOf(CloudPlatformException.class);
    }

    @Test
    void createManagedAlwaysStampsTheLabelEvenIfCallerForgot() {
        Map<String, String> labels = PipelineRunOperations.managedLabels(Map.of("k", "v"));
        assertThat(labels)
                .containsEntry("app.kubernetes.io/managed-by", "platform-system")
                .containsEntry("k", "v");
    }

    @Test
    void managedLabelsOverwriteACallerSuppliedManagedBy() {
        // 调用方（平台自己的渲染层）不可能把标签写成别的值
        Map<String, String> labels = PipelineRunOperations.managedLabels(
                Map.of("app.kubernetes.io/managed-by", "someone-else"));
        assertThat(labels).containsEntry("app.kubernetes.io/managed-by", "platform-system");
    }

    // ---- 节点落点（归档要按它去取日志）----

    private static TaskRun taskRun(String podName, String container, String condStatus, String reason) {
        TaskRun tr = new TaskRun();
        tr.setStatus(new TaskRunStatus());
        tr.getStatus().setPodName(podName);
        StepState st = new StepState();
        st.setName("run");
        st.setContainer(container);
        tr.getStatus().setSteps(podName == null ? List.of() : List.of(st));
        if (condStatus != null) {
            io.fabric8.knative.pkg.apis.Condition c = new io.fabric8.knative.pkg.apis.Condition();
            c.setType("Succeeded");
            c.setStatus(condStatus);
            c.setReason(reason);
            tr.getStatus().setConditions(List.of(c));
        }
        return tr;
    }

    @Test
    void aStepRefCarriesThePodAndContainersSoLogsCanBeFetched() {
        // 归档只需拿着 podName + containers 去走既有的 Pod 日志通道 —— 它不该知道 Tekton 的结构
        PipelineStepRefDTO ref = PipelineRunOperations.toStepRef("clone",
                taskRun("clone-pod", "step-run", "True", "Succeeded"));

        assertThat(ref.nodeName()).isEqualTo("clone");
        assertThat(ref.status()).isEqualTo("SUCCEEDED");
        assertThat(ref.podName()).isEqualTo("clone-pod");
        assertThat(ref.containers()).containsExactly("step-run");
    }

    @Test
    void aStepThatHasNotStartedHasNoPodNameAndNoContainers() {
        PipelineStepRefDTO ref = PipelineRunOperations.toStepRef("build", taskRun(null, null, null, null));

        assertThat(ref.status()).isEqualTo("RUNNING");
        assertThat(ref.podName()).isNull();
        assertThat(ref.containers()).isEmpty();
    }

    // ---- 创建出来的 PipelineRun 的形状 ----

    private static PipelineRunOperations opsForBuild() {
        PipelineNodeRegistry registry = new PipelineNodeRegistry(
                List.of(new com.coding.k8score.pipeline.nodes.GitCloneNode(),
                        new com.coding.k8score.pipeline.nodes.ShellNode(),
                        new com.coding.k8score.pipeline.nodes.ImagePushNode()));
        return new PipelineRunOperations(null, new PipelineRunConverter(),
                new com.coding.k8score.pipeline.PipelineCanvasOperations(
                        registry, new com.coding.k8score.pipeline.PipelineRenderer(registry)));
    }

    private static PipelineRunDTO runOf(String canvasJson) {
        PipelineRunDTO dto = new PipelineRunDTO();
        dto.setClusterId("c1");
        dto.setNamespace("ns1");
        dto.setName("my-app-20261015-abcd");
        dto.setPipelineCanvasJson(canvasJson);
        return dto;
    }

    @Test
    void everyRunBindsTheSharedWorkspaceWithAPvcTemplate() {
        // ⭐ 没有这一步，Tekton 会对每个非 optional 的 pipeline workspace 报
        //    "pipeline requires workspace ... be provided by pipelinerun"，
        //    创建会成功、reconciler 立刻把这条运行标成 Failed，Pod 不创建、日志也没有。
        //    ——「跑得起来」的全部前提就在这一个 setter 上。
        String canvas = """
                {"nodes":[{"id":"clone","type":"git","config":{"repo":"https://g/r.git"}}],"edges":[]}""";

        PipelineRun pr = opsForBuild().buildRun(runOf(canvas));

        assertThat(pr.getSpec().getPipelineSpec().getWorkspaces()).hasSize(1);
        WorkspaceBinding binding = pr.getSpec().getWorkspaces().get(0);
        assertThat(binding.getName())
                .isEqualTo(pr.getSpec().getPipelineSpec().getWorkspaces().get(0).getName());
        assertThat(binding.getVolumeClaimTemplate()).as("必须是 PVC —— emptyDir 不跨 TaskRun 共享").isNotNull();
        assertThat(binding.getVolumeClaimTemplate().getSpec().getAccessModes()).isNotEmpty();
        assertThat(binding.getVolumeClaimTemplate().getSpec().getResources().getRequests())
                .containsKey("storage");
    }

    @Test
    void theRunCarriesThePlatformLabelAndNoTimeouts() {
        String canvas = """
                {"nodes":[{"id":"clone","type":"git","config":{"repo":"https://g/r.git"}}],"edges":[]}""";

        PipelineRun pr = opsForBuild().buildRun(runOf(canvas));

        assertThat(pr.getMetadata().getLabels())
                .containsEntry(PipelineRunOperations.MANAGED_LABEL, PipelineRunOperations.MANAGED_VALUE);
        // 有意不设 spec.timeouts —— 让 Tekton 自己超时会删 Pod，那批运行的日志必然丢失
        assertThat(pr.getSpec().getTimeouts()).isNull();
        assertThat(pr.getMetadata().getNamespace()).isEqualTo("ns1");
    }
}
