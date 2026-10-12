package com.coding.k8score.operations.workload;

import com.coding.common.models.k8s.dto.WorkloadRevisionDTO;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerBuilder;
import io.fabric8.kubernetes.api.model.EnvVarBuilder;
import io.fabric8.kubernetes.api.model.PodSpec;
import io.fabric8.kubernetes.api.model.PodSpecBuilder;
import io.fabric8.kubernetes.api.model.PodTemplateSpec;
import io.fabric8.kubernetes.api.model.PodTemplateSpecBuilder;
import io.fabric8.kubernetes.api.model.apps.ControllerRevision;
import io.fabric8.kubernetes.api.model.apps.ControllerRevisionBuilder;
import io.fabric8.kubernetes.api.model.apps.DaemonSet;
import io.fabric8.kubernetes.api.model.apps.DaemonSetBuilder;
import io.fabric8.kubernetes.api.model.apps.ReplicaSet;
import io.fabric8.kubernetes.api.model.apps.ReplicaSetBuilder;
import io.fabric8.kubernetes.api.model.apps.StatefulSetBuilder;
import io.fabric8.kubernetes.client.utils.Serialization;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 列表页三个行内动作的纯逻辑：镜像替换、历史版本归一、目标版本模板定位。
 * K8s I/O（list/SSA 写回）不在这里 —— 这里钉的是"选哪些对象、改哪个字段、版本怎么归"。
 */
class WorkloadOperationsActionsTest {

    // ==================== 镜像：只改命中容器的那一个字段 ====================

    private Container container(String name, String image) {
        return new ContainerBuilder()
                .withName(name).withImage(image)
                .withEnv(new EnvVarBuilder().withName("K").withValue("V").build())
                .build();
    }

    @Test
    void set_container_image_replaces_only_the_target_container() {
        PodSpec spec = new PodSpecBuilder().withContainers(
                container("app", "nginx:1.25"),
                container("sidecar", "envoy:v1")).build();

        boolean hit = WorkloadOperations.setContainerImage(spec, "app", "nginx:1.26");

        assertThat(hit).isTrue();
        assertThat(spec.getContainers().get(0).getImage()).isEqualTo("nginx:1.26");
        // 同一容器的其它字段不能被一起重写（env 之类不在这次改动范围内）
        assertThat(spec.getContainers().get(0).getEnv()).hasSize(1);
        assertThat(spec.getContainers().get(1).getImage()).isEqualTo("envoy:v1");
    }

    @Test
    void set_container_image_reports_miss_without_touching_anything() {
        PodSpec spec = new PodSpecBuilder().withContainers(container("app", "nginx:1.25")).build();

        boolean hit = WorkloadOperations.setContainerImage(spec, "nope", "nginx:1.26");

        assertThat(hit).isFalse();
        assertThat(spec.getContainers().get(0).getImage()).isEqualTo("nginx:1.25");
    }

    @Test
    void set_container_image_ignores_init_containers() {
        PodSpec spec = new PodSpecBuilder()
                .withContainers(container("app", "nginx:1.25"))
                .withInitContainers(container("init", "busybox:1.36"))
                .build();

        boolean hit = WorkloadOperations.setContainerImage(spec, "init", "busybox:1.37");

        assertThat(hit).isFalse();
        assertThat(spec.getInitContainers().get(0).getImage()).isEqualTo("busybox:1.36");
    }

    // ==================== 历史版本：ReplicaSet（Deployment） ====================

    private ReplicaSet rs(String name, String revision, String ownerKind, String ownerName, String image) {
        ReplicaSetBuilder b = new ReplicaSetBuilder()
                .withNewMetadata().withName(name).withNamespace("ns")
                .addToAnnotations("deployment.kubernetes.io/revision", revision)
                .addToAnnotations("kubernetes.io/change-cause", "cause-" + revision)
                .withCreationTimestamp("2026-10-0" + revision + "T00:00:00Z")
                .endMetadata()
                .withNewSpec().withNewTemplate().withNewSpec()
                .addToContainers(container("app", image))
                .endSpec().endTemplate().endSpec();
        if (ownerKind != null) {
            b.editMetadata().addNewOwnerReference()
                    .withKind(ownerKind).withName(ownerName).withController(true).endOwnerReference().endMetadata();
        }
        return b.build();
    }

    @Test
    void deployment_revisions_keep_only_this_deployments_replicasets_newest_first() {
        List<ReplicaSet> rsList = List.of(
                rs("web-1", "1", "Deployment", "web", "nginx:1.24"),
                rs("web-2", "2", "Deployment", "web", "nginx:1.25"),
                rs("other-9", "9", "Deployment", "other", "nginx:9.9"),   // 别的工作负载的
                rs("stray-3", "3", null, null, "nginx:3.3"));             // 没有 ownerReference 的野 RS

        List<WorkloadRevisionDTO> out = WorkloadOperations.deploymentRevisions(rsList, "web", "1");

        assertThat(out).extracting(WorkloadRevisionDTO::getRevision).containsExactly(2L, 1L);
        assertThat(out.get(0).getImages()).containsExactly("nginx:1.25");
        assertThat(out.get(0).getChangeCause()).isEqualTo("cause-2");
        assertThat(out.get(0).getCreatedAt()).isEqualTo("2026-10-02T00:00:00Z");
        // current = Deployment 自身 revision 注解指向的那条（不是最大号 —— 回滚后号会继续涨）
        assertThat(out).extracting(WorkloadRevisionDTO::getCurrent).containsExactly(false, true);
    }

    @Test
    void deployment_revisions_of_workload_without_history_is_empty() {
        assertThat(WorkloadOperations.deploymentRevisions(new ArrayList<>(), "web", "1")).isEmpty();
    }

    // ==================== 历史版本：ControllerRevision（STS/DS） ====================

    private PodTemplateSpec template(String image) {
        return new PodTemplateSpecBuilder().withNewSpec()
                .addToContainers(container("app", image))
                .endSpec().build();
    }

    private ControllerRevision cr(String name, long revision, String ownerKind, String ownerName, PodTemplateSpec tpl) {
        Object data = "StatefulSet".equals(ownerKind)
                ? new StatefulSetBuilder().withNewSpec().withTemplate(tpl).endSpec().build()
                : new DaemonSetBuilder().withNewSpec().withTemplate(tpl).endSpec().build();
        return new ControllerRevisionBuilder()
                .withNewMetadata().withName(name).withNamespace("ns")
                .withCreationTimestamp("2026-10-03T00:00:00Z")
                .addNewOwnerReference().withKind(ownerKind).withName(ownerName).withController(true).endOwnerReference()
                .endMetadata()
                .withRevision(revision)
                .withData(data)
                .build();
    }

    @Test
    void workload_revisions_mark_the_one_matching_live_template_as_current() {
        PodTemplateSpec live = template("nginx:1.25");
        List<ControllerRevision> crs = List.of(
                cr("web-1", 1, "StatefulSet", "web", template("nginx:1.24")),
                cr("web-2", 2, "StatefulSet", "web", live),
                cr("other-5", 5, "StatefulSet", "other", template("nginx:9.9")));

        List<WorkloadRevisionDTO> out = WorkloadOperations.workloadRevisions(crs, "web", live);

        assertThat(out).extracting(WorkloadRevisionDTO::getRevision).containsExactly(2L, 1L);
        assertThat(out).extracting(WorkloadRevisionDTO::getCurrent).containsExactly(true, false);
        assertThat(out.get(0).getImages()).containsExactly("nginx:1.25");
    }

    // ==================== 回滚：定位目标版本的 pod 模板 ====================

    @Test
    void deployment_revision_template_finds_target_replicaset() {
        List<ReplicaSet> rsList = List.of(
                rs("web-1", "1", "Deployment", "web", "nginx:1.24"),
                rs("web-2", "2", "Deployment", "web", "nginx:1.25"));

        PodTemplateSpec tpl = WorkloadOperations.deploymentRevisionTemplate(rsList, "web", 1L);

        assertThat(tpl.getSpec().getContainers().get(0).getImage()).isEqualTo("nginx:1.24");
    }

    @Test
    void deployment_revision_template_is_null_when_revision_gone() {
        List<ReplicaSet> rsList = List.of(rs("web-1", "1", "Deployment", "web", "nginx:1.24"));

        assertThat(WorkloadOperations.deploymentRevisionTemplate(rsList, "web", 7L)).isNull();
    }

    /**
     * 控制器会往 ReplicaSet 的 pod 模板上写 {@code pod-template-hash} 标签，那是它的内部标识，
     * 不属于 Deployment 自己的模板 —— 带回去会污染用户看到的标签，且下一次 rollout 的哈希把它一起算进去。
     */
    @Test
    void deployment_revision_template_strips_pod_template_hash() {
        ReplicaSet withHash = new ReplicaSetBuilder()
                .withNewMetadata().withName("web-1")
                .addToAnnotations("deployment.kubernetes.io/revision", "1")
                .addNewOwnerReference().withKind("Deployment").withName("web").withController(true).endOwnerReference()
                .endMetadata()
                .withNewSpec().withNewTemplate()
                .withNewMetadata()
                .addToLabels("app", "web")
                .addToLabels("pod-template-hash", "7d9c8b5f4")
                .endMetadata()
                .withNewSpec().addToContainers(container("app", "nginx:1.24")).endSpec()
                .endTemplate().endSpec()
                .build();

        PodTemplateSpec tpl = WorkloadOperations.deploymentRevisionTemplate(List.of(withHash), "web", 1L);

        assertThat(tpl.getMetadata().getLabels()).containsEntry("app", "web").doesNotContainKey("pod-template-hash");
    }

    @Test
    void controller_revision_template_reads_typed_data() {
        List<ControllerRevision> crs = List.of(cr("web-1", 1, "StatefulSet", "web", template("nginx:1.24")));

        PodTemplateSpec tpl = WorkloadOperations.controllerRevisionTemplate(crs, "web", "statefulset", 1L);

        assertThat(tpl.getSpec().getContainers().get(0).getImage()).isEqualTo("nginx:1.24");
    }

    /**
     * data 不一定是类型化对象：不同 fabric8 版本/自定义控制器可能给出 Map（GenericKubernetesResource / raw）。
     * 兜底路径必须也能读出模板，否则"STS/DS 回滚"在某些集群上会静默报"版本不存在"。
     */
    @Test
    void controller_revision_template_falls_back_to_raw_map_data() throws Exception {
        DaemonSet ds = new DaemonSetBuilder().withNewSpec().withTemplate(template("busybox:1.36")).endSpec().build();
        @SuppressWarnings("unchecked")
        Map<String, Object> raw = Serialization.jsonMapper().readValue(Serialization.asJson(ds), Map.class);
        ControllerRevision cr = new ControllerRevisionBuilder()
                .withNewMetadata().withName("ds-1").withNamespace("ns")
                .addNewOwnerReference().withKind("DaemonSet").withName("ds").withController(true).endOwnerReference()
                .endMetadata()
                .withRevision(1L)
                .withData(raw)
                .build();

        PodTemplateSpec tpl = WorkloadOperations.controllerRevisionTemplate(List.of(cr), "ds", "daemonset", 1L);

        assertThat(tpl.getSpec().getContainers().get(0).getImage()).isEqualTo("busybox:1.36");
    }

    @Test
    void controller_revision_template_is_null_when_revision_gone() {
        List<ControllerRevision> crs = List.of(cr("web-1", 1, "StatefulSet", "web", template("nginx:1.24")));

        assertThat(WorkloadOperations.controllerRevisionTemplate(crs, "web", "statefulset", 9L)).isNull();
    }

    /** 变更说明是集群里已有的才回显；平台不写这个注解，故缺失是常态不是异常 */
    @Test
    void change_cause_is_optional() {
        ReplicaSet plain = new ReplicaSetBuilder()
                .withNewMetadata().withName("web-1")
                .addToAnnotations("deployment.kubernetes.io/revision", "1")
                .addNewOwnerReference().withKind("Deployment").withName("web").withController(true).endOwnerReference()
                .endMetadata()
                .withNewSpec().withNewTemplate().withNewSpec().addToContainers(container("app", "nginx:1.24"))
                .endSpec().endTemplate().endSpec()
                .build();

        List<WorkloadRevisionDTO> out = WorkloadOperations.deploymentRevisions(List.of(plain), "web", "1");

        assertThat(out).hasSize(1);
        assertThat(out.get(0).getChangeCause()).isNull();
        assertThat(out.get(0).getImages()).containsExactly("nginx:1.24");
    }

    /** StatefulSet/DaemonSet 的 kind 复原（typed data 是 DaemonSet 时不能按 StatefulSet 解） */
    @Test
    void daemon_set_controller_revision_works_too() {
        ControllerRevision cr = new ControllerRevisionBuilder()
                .withNewMetadata().withName("ds-2").withNamespace("ns")
                .addNewOwnerReference().withKind("DaemonSet").withName("ds").withController(true).endOwnerReference()
                .endMetadata()
                .withRevision(2L)
                .withData(new DaemonSetBuilder().withNewSpec().withTemplate(template("agent:2")).endSpec().build())
                .build();

        PodTemplateSpec tpl = WorkloadOperations.controllerRevisionTemplate(List.of(cr), "ds", "daemonset", 2L);

        assertThat(tpl.getSpec().getContainers().get(0).getImage()).isEqualTo("agent:2");
    }
}
