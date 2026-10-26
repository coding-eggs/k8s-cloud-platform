package com.coding.k8score.converter.impl.workload;

import com.coding.common.models.k8s.dto.WorkloadDTO;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.PodSpec;
import io.fabric8.kubernetes.api.model.PodTemplateSpec;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentCondition;
import io.fabric8.kubernetes.api.model.apps.DeploymentSpec;
import io.fabric8.kubernetes.api.model.apps.DeploymentStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deployment 状态条件的投影：哪些条件要变成人读的 {@code statusReason}。
 */
class WorkloadConverterStatusTest {

    @Test
    void aReplicaFailureIsReportedEvenThoughItsConditionStatusIsTrue() {
        // Deployment 的 ReplicaFailure 是"出问题时 status=True"（与 Available/Progressing 的方向相反）。
        // 旧实现一律跳过 status=True 的条件，于是配额/准入拒绝新建 Pod 时平台什么都不显示 ——
        // 用户只看到「镜像改了但 Pod 没重建」，完全不知道是 quota 拒的（2026-10-26 实报：movie-server）。
        Deployment d = deployment();
        d.setStatus(new DeploymentStatus());
        d.getStatus().setConditions(List.of(
                condition("Available", "True", "MinimumReplicasAvailable", "Deployment has minimum availability."),
                condition("Progressing", "True", "NewReplicaSetCreated", "Created new replica set \"web-2\""),
                condition("ReplicaFailure", "True", "FailedCreate",
                        "pods \"web-2-x\" is forbidden: exceeded quota: default")));

        WorkloadDTO dto = new WorkloadConverter().revert(d);

        assertThat(dto.getStatusReason())
                .contains("ReplicaFailure")
                .contains("FailedCreate")
                .contains("exceeded quota");
    }

    @Test
    void aHealthyDeploymentReportsNoStatusReason() {
        // 反向条件不能被误伤：Available/Progressing 的 True 仍然是"健康"，不该产出一行原因
        Deployment d = deployment();
        d.setStatus(new DeploymentStatus());
        d.getStatus().setConditions(List.of(
                condition("Available", "True", "MinimumReplicasAvailable", "Deployment has minimum availability."),
                condition("Progressing", "True", "NewReplicaSetCreated", "Created new replica set \"web-2\"")));

        assertThat(new WorkloadConverter().revert(d).getStatusReason()).isNull();
    }

    @Test
    void aFalseConditionStillUsesTheConditionLine() {
        Deployment d = deployment();
        d.setStatus(new DeploymentStatus());
        d.getStatus().setConditions(List.of(
                condition("Progressing", "False", "ProgressDeadlineExceeded",
                        "ReplicaSet \"web-2\" has timed out progressing.")));

        assertThat(new WorkloadConverter().revert(d).getStatusReason())
                .contains("ProgressDeadlineExceeded");
    }

    // ==================== 夹具 ====================

    private static DeploymentCondition condition(String type, String status, String reason, String message) {
        DeploymentCondition c = new DeploymentCondition();
        c.setType(type);
        c.setStatus(status);
        c.setReason(reason);
        c.setMessage(message);
        return c;
    }

    private static Deployment deployment() {
        Container c = new Container();
        c.setName("app");
        c.setImage("example/app:1");
        PodSpec podSpec = new PodSpec();
        podSpec.setContainers(List.of(c));
        PodTemplateSpec template = new PodTemplateSpec();
        template.setSpec(podSpec);
        DeploymentSpec spec = new DeploymentSpec();
        spec.setTemplate(template);
        ObjectMeta meta = new ObjectMeta();
        meta.setName("web");
        meta.setNamespace("movie");
        Deployment d = new Deployment();
        d.setMetadata(meta);
        d.setSpec(spec);
        return d;
    }
}
