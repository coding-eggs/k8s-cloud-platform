package com.coding.k8score.operations.pipelinerun;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.models.pipeline.PipelineStepRefDTO;
import com.coding.k8score.pipeline.PipelineNodeRegistry;
import io.fabric8.tekton.v1.ChildStatusReference;
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
        return taskRun(podName, container, condStatus, reason, null);
    }

    private static TaskRun taskRun(String podName, String container, String condStatus, String reason,
                                   String message) {
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
            c.setMessage(message);
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

    // ---- 「为什么失败」得跟着快照一起落库（2026-10-15：真跑失败后历史里只有一句 Failed）----

    @Test
    void aFailedStepCarriesTektonsOwnWords() {
        // ⭐ 那次真跑失败后，运行历史里只有 Tekton 顶层 condition 的 reason（`Failed`）——
        //    真因在**失败那个节点**的 TaskRun condition message 上：
        //    [User error] declared workspace "ws" is required but has not been bound
        //    对象一归档就被删，所以这句话只有抓快照这一刻能拿到，必须带下来。
        PipelineStepRefDTO ref = PipelineRunOperations.toStepRef("clone",
                taskRun("clone-pod", "step-clone", "False", "Failed",
                        "[User error] declared workspace \"ws\" is required but has not been bound"));

        assertThat(ref.status()).isEqualTo("FAILED");
        assertThat(ref.message())
                .contains("declared workspace \"ws\" is required but has not been bound");
    }

    @Test
    void theRunsReasonIsTheFailingNodesOwnWordsNotTheGenericFailed() {
        // 顶层 reason（"Failed"）是统计口径，对排障没有信息量；能拿到具体报错就用具体的
        List<PipelineStepRefDTO> steps = List.of(
                new PipelineStepRefDTO("clone", "FAILED", "p", List.of(), "boom：具体报错"),
                new PipelineStepRefDTO("build", "SUCCEEDED", "p2", List.of(), null));

        assertThat(PipelineRunOperations.failureReasonOf(steps, "Failed")).isEqualTo("boom：具体报错");
    }

    @Test
    void theRunsReasonFallsBackToTektonsWordWhenNoNodeExplainedItself() {
        // 节点没给出原因（比如 Pod 都没起、或运行是被取消的）→ 退回 Tekton 的 reason，
        // 绝不编一个出来
        assertThat(PipelineRunOperations.failureReasonOf(
                List.of(new PipelineStepRefDTO("clone", "FAILED", null, List.of(), null)), "Failed"))
                .isEqualTo("Failed");
        assertThat(PipelineRunOperations.failureReasonOf(List.of(), "Succeeded"))
                .isEqualTo("Succeeded");
    }

    // ---- 「跳过的节点」也得看得见（2026-10-16：运行详情里节点凭空不见了）----
    //
    // ⚠️ Tekton 对**没跑起来**的节点**不建 TaskRun**（上游失败级联跳过、finally 的执行时机
    //    不满足，都会这样），它们只出现在 PipelineRun 的 `status.skippedTasks[]` 里。
    //    只照 childReferences 找 TaskRun 的话，画布上那个节点在运行详情里连状态都没有。

    @Test
    void aSkippedNodeShowsUpEvenThoughItNeverRan() {
        PipelineStepRefDTO ref =
                PipelineRunOperations.toSkippedStepRef(skippedTask("notify", "Parent Tasks had failed"));

        assertThat(ref.nodeName()).isEqualTo("notify");           // PipelineTask 名 = 画布节点 id
        assertThat(ref.status()).isEqualTo("SKIPPED");
        assertThat(ref.podName()).isNull();                       // 没跑就没有 Pod（抓日志那条路自然跳过它）
        assertThat(ref.containers()).isEmpty();
        assertThat(ref.message()).isEqualTo("Parent Tasks had failed");   // Tekton 的原话，不翻译
    }

    @Test
    void skippedNodesAreAppendedAfterTheOnesThatRan() {
        List<PipelineStepRefDTO> out = PipelineRunOperations.appendSkipped(
                List.of(stepRef("clone", "FAILED", "clone-pod", "boom")),
                List.of(skippedTask("build", "Parent Tasks had failed"),
                        skippedTask("notify", "When Expressions evaluated to false")));

        assertThat(out).extracting(PipelineStepRefDTO::nodeName)
                .containsExactly("clone", "build", "notify");
        assertThat(out.get(0).status()).isEqualTo("FAILED");      // 跑过的那条原样不动
        assertThat(out.get(1).status()).isEqualTo("SKIPPED");
    }

    @Test
    void aNodeListedAsSkippedButAlsoHavingATaskRunIsNotDuplicated() {
        // 真跑过就以 TaskRun 为准 —— 同一个节点在界面上出现两次是最糟的形态
        List<PipelineStepRefDTO> out = PipelineRunOperations.appendSkipped(
                List.of(stepRef("build", "SUCCEEDED", "build-pod", null)),
                List.of(skippedTask("build", "Parent Tasks had failed")));

        assertThat(out).hasSize(1);
        assertThat(out.get(0).status()).isEqualTo("SUCCEEDED");
    }

    @Test
    void aSkippedEntryWithoutANameIsIgnored() {
        assertThat(PipelineRunOperations.appendSkipped(List.of(), List.of(skippedTask(null, "x"))))
                .isEmpty();
        assertThat(PipelineRunOperations.appendSkipped(List.of(), null)).isEmpty();
    }

    @Test
    void aSkippedNodesReasonIsNeverMistakenForTheRunsFailure() {
        // ⚠️ 这里曾经会错：failureReasonOf 用的是「不是 SUCCEEDED / RUNNING」这个**反向**条件，
        //    加上 SKIPPED 之后，排在它前面的跳过原因会把真失败原因顶掉 ——
        //    而「Parent Tasks had failed」是**跳过**的原因，不是失败的原因。
        List<PipelineStepRefDTO> ordered = List.of(
                new PipelineStepRefDTO("notify", "SKIPPED", null, List.of(), "Parent Tasks had failed"),
                stepRef("build", "FAILED", "build-pod", "boom：真因"));
        assertThat(PipelineRunOperations.failureReasonOf(ordered, "Failed")).isEqualTo("boom：真因");

        // 只剩跳过原因时退回 Tekton 自己的 reason —— 绝不把跳过原因冒充成失败原因
        assertThat(PipelineRunOperations.failureReasonOf(
                List.of(new PipelineStepRefDTO("notify", "SKIPPED", null, List.of(),
                        "Parent Tasks had failed")), "Failed"))
                .isEqualTo("Failed");
    }

    // ---- 节点名必须是**画布节点 id**，不是 TaskRun 名 ----
    //
    // ⭐ 2026-10-16 用户报「节点日志只能在『全部』里看到」。根因就在这里：
    //    Tekton 的子对象名是 `<pipelineRun 名>-<pipelineTask 名>`，而整个下游 —— 日志行的键、
    //    stepsJson 的键、运行详情的 DAG 着色、前端传的过滤键 —— 一律用**画布节点 id**。
    //    库里那条运行的真数据：
    //      log.node_name = k8s-cloud-platform-20261016123954-1kzt-git-quo31
    //      画布节点 id   = git-quo31
    //    按 id 过滤 → 一条都匹配不上（于是只剩「全部」能用）；按 id 着色 → 所有节点都没状态。

    @Test
    void aNodeIsNamedByThePipelineTaskNotByItsTaskRun() {
        ChildStatusReference ref = childRef("git-quo31", "k8s-cloud-platform-1kzt-git-quo31");

        assertThat(PipelineRunOperations.nodeNameOf(ref)).isEqualTo("git-quo31");
    }

    @Test
    void aChildReferenceWithoutAPipelineTaskNameFallsBackToTheObjectName() {
        // 理论上不会缺（Tekton 一定写 pipelineTaskName），但缺了也不能变成 null ——
        // 那会让那个节点在运行详情里凭空消失
        assertThat(PipelineRunOperations.nodeNameOf(childRef(null, "run-git-quo31")))
                .isEqualTo("run-git-quo31");
        assertThat(PipelineRunOperations.nodeNameOf(childRef("  ", "run-git-quo31")))
                .isEqualTo("run-git-quo31");
        assertThat(PipelineRunOperations.nodeNameOf(null)).isNull();
    }

    private static ChildStatusReference childRef(String pipelineTaskName, String name) {
        ChildStatusReference ref = new ChildStatusReference();
        ref.setPipelineTaskName(pipelineTaskName);
        ref.setName(name);
        return ref;
    }

    private static io.fabric8.tekton.v1.SkippedTask skippedTask(String name, String reason) {
        io.fabric8.tekton.v1.SkippedTask sk = new io.fabric8.tekton.v1.SkippedTask();
        sk.setName(name);
        sk.setReason(reason);
        return sk;
    }

    private static PipelineStepRefDTO stepRef(String node, String status, String pod, String message) {
        return new PipelineStepRefDTO(node, status, pod, List.of(), message);
    }

    @Test
    void theCacheWorkspaceBindsTheLongLivedPvcWhenItIsAvailable() {
        // ⭐ 依赖缓存的地基：cache workspace 绑的不是「每次现开一块」，而是
        //    **命名空间里那块长期保留的卷** —— 否则每次运行都是空卷，"不重下 jar"无从谈起。
        String canvas = """
                {"nodes":[{"id":"build","type":"shell","config":{"image":"alpine:3.20",
                   "command":"echo hi","cache":"true"}}],"edges":[]}""";

        PipelineRun pr = opsForBuild().buildRun(runOf(canvas), PipelineRunOperations.CACHE_PVC);

        WorkspaceBinding cache = bindingOf(pr, "cache");
        assertThat(cache.getPersistentVolumeClaim().getClaimName())
                .isEqualTo(PipelineRunOperations.CACHE_PVC);
        assertThat(cache.getEmptyDir()).isNull();
    }

    @Test
    void theCacheWorkspaceFallsBackToAnEmptyDirWhenNoSharedVolumeExists() {
        // ⭐ 降级（用户 2026-10-15 定）：集群给不了共享卷就**不共享**，而不是卡住或判失败。
        //    空目录让 /cache 照样存在、工具照写 —— 只是这次运行的东西跑完就没了。
        String canvas = """
                {"nodes":[{"id":"build","type":"shell","config":{"image":"alpine:3.20",
                   "command":"echo hi","cache":"true"}}],"edges":[]}""";

        PipelineRun pr = opsForBuild().buildRun(runOf(canvas), null);

        WorkspaceBinding cache = bindingOf(pr, "cache");
        assertThat(cache.getEmptyDir()).as("退化成本次运行自己的空目录").isNotNull();
        assertThat(cache.getPersistentVolumeClaim()).isNull();
    }

    @Test
    void aRunThatDoesNotAskForCacheCarriesNoCacheWorkspaceAtAll() {
        // 不用缓存的运行不该为它付出任何代价：不声明 workspace、不建卷、不发那次 GET
        String canvas = """
                {"nodes":[{"id":"build","type":"shell","config":{"image":"alpine:3.20",
                   "command":"echo hi"}}],"edges":[]}""";

        PipelineRun pr = opsForBuild().buildRun(runOf(canvas), PipelineRunOperations.CACHE_PVC);

        assertThat(pr.getSpec().getPipelineSpec().getWorkspaces())
                .extracting(io.fabric8.tekton.v1.PipelineWorkspaceDeclaration::getName)
                .containsExactly("ws");
    }

    /** 找某个 workspace 的绑定（找不到就抛 —— 断言失败要说清楚是"没绑"）。 */
    private static WorkspaceBinding bindingOf(PipelineRun pr, String name) {
        return pr.getSpec().getWorkspaces().stream()
                .filter(b -> name.equals(b.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("没有 " + name + " 的绑定：" + pr.getSpec().getWorkspaces()));
    }

    // ---- 创建出来的 PipelineRun 的形状 ----

    private static PipelineRunOperations opsForBuild() {
        PipelineNodeRegistry registry = com.coding.k8score.pipeline.nodes.AllNodes.registry();
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
        dto.setWorkspacePvc("platform-pipeline-ws-" + dto.getName());
        return dto;
    }

    @Test
    void everyRunBindsThePlatformOwnedWorkspacePvc() {
        // ⭐ 不绑就等于没跑：Tekton 对非 optional 的 workspace 要求 PipelineRun 给出绑定，
        //    缺了创建会成功、reconciler 立刻判 Failed，Pod 都不起（连日志都没有）。
        //    现在绑的是**平台自建的具名 PVC**（不再是 volumeClaimTemplate）—— 这样运行删了卷还在，
        //    失败后才有「从断点继续」的余地。
        String canvas = """
                {"nodes":[{"id":"clone","type":"git","config":{"repo":"https://g/r.git"}}],"edges":[]}""";

        PipelineRun pr = opsForBuild().buildRun(runOf(canvas));

        assertThat(pr.getSpec().getPipelineSpec().getWorkspaces()).hasSize(1);
        WorkspaceBinding binding = pr.getSpec().getWorkspaces().get(0);
        assertThat(binding.getName()).isEqualTo("ws");
        assertThat(binding.getPersistentVolumeClaim()).isNotNull();
        assertThat(binding.getPersistentVolumeClaim().getClaimName())
                .isEqualTo("platform-pipeline-ws-my-app-20261015-abcd");
        assertThat(binding.getVolumeClaimTemplate()).as("不再用模板").isNull();
    }

    @Test
    void aRunWithoutAWorkspaceClaimIsRefusedBeforeAnythingIsBuilt() {
        String canvas = """
                {"nodes":[{"id":"clone","type":"git","config":{"repo":"https://g/r.git"}}],"edges":[]}""";
        PipelineRunDTO dto = runOf(canvas);
        dto.setWorkspacePvc(null);

        assertThatThrownBy(() -> opsForBuild().buildRun(dto))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("工作区");
    }

    @Test
    void theWorkspaceClaimNameIsReadFromTheWsBinding() {
        String canvas = """
                {"nodes":[{"id":"clone","type":"git","config":{"repo":"https://g/r.git"}}],"edges":[]}""";
        PipelineRun pr = opsForBuild().buildRun(runOf(canvas));

        assertThat(PipelineRunOperations.workspaceClaimNameOf(pr))
                .isEqualTo("platform-pipeline-ws-my-app-20261015-abcd");
        assertThat(PipelineRunOperations.workspaceClaimNameOf(new PipelineRun())).isNull();
    }

    @Test
    void onlyPvcsCarryingTheWorkspaceRoleAreOursToDelete() {
        io.fabric8.kubernetes.api.model.PersistentVolumeClaim ours =
                new io.fabric8.kubernetes.api.model.PersistentVolumeClaim();
        ours.setMetadata(new io.fabric8.kubernetes.api.model.ObjectMeta());
        ours.getMetadata().setLabels(Map.of(PipelineRunOperations.WORKSPACE_ROLE_LABEL,
                PipelineRunOperations.WORKSPACE_ROLE_VALUE));
        assertThat(PipelineRunOperations.isWorkspacePvc(ours)).isTrue();

        io.fabric8.kubernetes.api.model.PersistentVolumeClaim foreign =
                new io.fabric8.kubernetes.api.model.PersistentVolumeClaim();
        foreign.setMetadata(new io.fabric8.kubernetes.api.model.ObjectMeta());
        foreign.getMetadata().setLabels(Map.of("app", "someone-else"));
        assertThat(PipelineRunOperations.isWorkspacePvc(foreign)).isFalse();

        io.fabric8.kubernetes.api.model.PersistentVolumeClaim noLabels =
                new io.fabric8.kubernetes.api.model.PersistentVolumeClaim();
        noLabels.setMetadata(new io.fabric8.kubernetes.api.model.ObjectMeta());
        assertThat(PipelineRunOperations.isWorkspacePvc(noLabels)).isFalse();
        assertThat(PipelineRunOperations.isWorkspacePvc(null)).isFalse();
    }

    @Test
    void reusingAMissingWorkspaceIsRefusedInsteadOfSowingAnEmptyOne() {
        // ⭐ 沿用而卷没了 = 工作区过期。重建一块空的会让重试「成功」却产物全缺 ——
        //    这是本批最隐蔽的一种假成功。
        assertThatThrownBy(() -> PipelineRunOperations.assertWorkspaceUsable(false, true, "ws-x"))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("工作区")
                .hasMessageContaining("过期");

        // 首跑缺卷是正常的（本次要新建）；沿用且卷在：放行
        PipelineRunOperations.assertWorkspaceUsable(false, false, "ws-x");
        PipelineRunOperations.assertWorkspaceUsable(true, true, "ws-x");
    }

    @Test
    void aFailedCreateReleasesTheCredsSecretEvenWhenNoPipelineRunWasCreated() {
        // ⭐ 凭据 Secret 里是可解的 git token / registry 认证。创建失败时它已经建好了，
        //    不回收就会每失败一次攒一份 —— spec §6.3 说「跑完没有任何留着的理由」，
        //    而这里连「跑」都没发生。
        PipelineRunOperations ops = org.mockito.Mockito.spy(
                new PipelineRunOperations(null, new PipelineRunConverter(), null));
        org.mockito.Mockito.doNothing().when(ops).deleteCredsSecret(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());

        ops.cleanupFailedCreate(runOf("{}"), false);

        org.mockito.Mockito.verify(ops)
                .deleteCredsSecret("ns1", "my-app-20261015-abcd");
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

    @Test
    void theApiServersOwnWordsAreUsedWhenTheClusterRejectsTheCreate() {
        // 2026-10-15：以前这条失败会塌成 k8s-server 兜底的那句「服务端异常」，
        // 用户在页面上看不到任何线索（真环境撞到过）。现在把 K8s 的原话带出来
        io.fabric8.kubernetes.client.KubernetesClientException e =
                new io.fabric8.kubernetes.client.KubernetesClientException("boom", 403,
                        new io.fabric8.kubernetes.api.model.StatusBuilder()
                                .withMessage("pipelineruns.tekton.dev \"x\" is forbidden: "
                                        + "User \"system:serviceaccount:demo:sa\" cannot create resource")
                                .build());

        assertThat(PipelineRunOperations.apiMessage(e))
                .isEqualTo("pipelineruns.tekton.dev \"x\" is forbidden: "
                        + "User \"system:serviceaccount:demo:sa\" cannot create resource");
    }

    @Test
    void aClientExceptionWithoutStatusFallsBackToItsOwnMessage() {
        assertThat(PipelineRunOperations.apiMessage(
                new io.fabric8.kubernetes.client.KubernetesClientException("socket closed")))
                .isEqualTo("socket closed");
    }
}
