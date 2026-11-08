package com.coding.k8sserver.app;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AppStatusServiceTest {

    @Test
    void deploymentRequiresObservedGenerationAndEnoughReplicas() {
        assertThat(AppStatusService.deploymentState(2, 2, 2, 2, true)).isEqualTo("READY");
        assertThat(AppStatusService.deploymentState(2, 1, 2, 2, true)).isEqualTo("PENDING");
        // 代际还没被控制器观测到：即便副本数暂时对得上也不能算就绪
        assertThat(AppStatusService.deploymentState(2, 2, 2, 2, false)).isEqualTo("PENDING");
    }

    @Test
    void deploymentIsPendingWhileRollingEvenIfOldReplicasAreAvailable() {
        // 滚动中：旧副本仍 available=3，但只有 1 个已更新到新模板
        assertThat(AppStatusService.deploymentState(3, 3, 1, 3, true)).isEqualTo("PENDING");
        assertThat(AppStatusService.deploymentState(3, 3, 3, 3, true)).isEqualTo("READY");
    }

    @Test
    void singleReplicaBadImageStaysPending() {
        // 旧 Pod 仍 available，新 Pod ImagePullBackOff：available=1, updated=1, total=2
        assertThat(AppStatusService.deploymentState(1, 1, 1, 2, true)).isEqualTo("PENDING");
    }

    @Test
    void deploymentReadyWhenAllReplicasUpdatedAndAvailable() {
        assertThat(AppStatusService.deploymentState(3, 3, 3, 3, true)).isEqualTo("READY");
    }

    @Test
    void statefulSetRequiresReadyReplicasAndSettledRevision() {
        assertThat(AppStatusService.statefulSetState(3, 3, true, true)).isEqualTo("READY");
        assertThat(AppStatusService.statefulSetState(3, 2, true, true)).isEqualTo("PENDING");
        assertThat(AppStatusService.statefulSetState(1, 0, true, true)).isEqualTo("PENDING");
        // revision 未收敛（滚动更新中）
        assertThat(AppStatusService.statefulSetState(3, 3, false, true)).isEqualTo("PENDING");
        // 代际未观测
        assertThat(AppStatusService.statefulSetState(3, 3, true, false)).isEqualTo("PENDING");
    }

    @Test
    void statefulSetRevisionSettledRule() {
        assertThat(AppStatusService.revisionSettled("a", "a", 0, 3)).isTrue();
        assertThat(AppStatusService.revisionSettled("a", "b", 1, 3)).isFalse();
        // 部分 K8s 版本滚动完成后不更新 currentRevision，以 updatedReplicas 为准
        assertThat(AppStatusService.revisionSettled("a", "b", 3, 3)).isTrue();
        assertThat(AppStatusService.revisionSettled(null, null, 0, 3)).isFalse();
    }

    @Test
    void gatewayRoutesAreKnownKinds() {
        // 应用 chart 会生成 HTTPRoute / TCPRoute（网关端口池暴露）：不认识的 kind 会让 HEALTH 永远等下去
        assertThat(AppStatusService.customKind("HTTPRoute").getGroup()).isEqualTo("gateway.networking.k8s.io");
        assertThat(AppStatusService.customKind("HTTPRoute").getVersion()).isEqualTo("v1");
        assertThat(AppStatusService.customKind("TCPRoute").getPlural()).isEqualTo("tcproutes");
        assertThat(AppStatusService.customKind("ServiceMonitor").getPlural()).isEqualTo("servicemonitors");
        assertThat(AppStatusService.customKind("Foo")).isNull();
    }
}
