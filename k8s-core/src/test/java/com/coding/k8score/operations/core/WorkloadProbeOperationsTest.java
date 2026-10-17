package com.coding.k8score.operations.core;

import com.coding.common.models.k8s.dto.WorkloadProbeResultDTO;
import com.coding.common.models.k8s.dto.WorkloadProbeTargetDTO;
import io.fabric8.kubernetes.api.model.apps.DaemonSet;
import io.fabric8.kubernetes.api.model.apps.DaemonSetBuilder;
import io.fabric8.kubernetes.api.model.apps.DaemonSetList;
import io.fabric8.kubernetes.api.model.apps.DaemonSetListBuilder;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.api.model.apps.DeploymentList;
import io.fabric8.kubernetes.api.model.apps.DeploymentListBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.AppsAPIGroupDSL;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.fabric8.kubernetes.client.dsl.RollableScalableResource;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 工作负载探测的三件事：健康字段按 kind 分支、版本提取的四种形态、成本固定（每 (ns,kind) 一次 list）。
 *
 * <p>每条断言都对着真集群实测过的事实（spec §9），不是照文档猜的：
 * DaemonSet 的 {@code readyReplicas} 是空的、工作负载名带 Helm release 前缀、镜像带 digest。
 */
class WorkloadProbeOperationsTest {

    // ⚠️ 断言前必须 clearInvocations：stubbing 期的调用会混进 verify
    //    （照 ClusterAggregationOperationsTest.Fixture 的注释）

    @Test
    void daemonSetHealthUsesNumberReadyNotReadyReplicas() {
        // ⭐ 真集群实测：calico-node / ztunnel / kube-proxy 的 status.readyReplicas 是空的，
        //    真正填的是 numberReady / desiredNumberScheduled。用错字段会一律显示 "0/0 不就绪"。
        DaemonSet ds = new DaemonSetBuilder()
                .withNewMetadata().withName("calico-node").withNamespace("calico-system").endMetadata()
                .withNewSpec().withNewTemplate().withNewSpec()
                    .addNewContainer().withName("calico-node")
                        .withImage("docker.io/calico/node:v3.30.3").endContainer()
                .endSpec().endTemplate().endSpec()
                .withNewStatus().withNumberReady(3).withDesiredNumberScheduled(3).endStatus()
                .build();
        // 故意把 readyReplicas 留空 —— 这正是真集群的样子
        WorkloadProbeOperations ops = opsWithDaemonSets(List.of(ds));

        List<WorkloadProbeResultDTO> r = ops.probe(List.of(target("calico-system", "daemonset", "calico/node", null)));

        assertThat(r).hasSize(1);
        assertThat(r.get(0).isFound()).isTrue();
        assertThat(r.get(0).getReady()).isEqualTo(3);
        assertThat(r.get(0).getDesired()).isEqualTo(3);
        assertThat(r.get(0).getDetail()).contains("3/3");
    }

    @Test
    void deploymentHealthUsesReadyReplicas() {
        Deployment d = new DeploymentBuilder()
                .withNewMetadata().withName("metrics-server").withNamespace("kube-system").endMetadata()
                .withNewSpec().withNewTemplate().withNewSpec()
                    .addNewContainer().withName("metrics-server")
                        .withImage("registry.k8s.io/metrics-server/metrics-server:v0.7.2").endContainer()
                .endSpec().endTemplate().endSpec()
                .withNewStatus().withReadyReplicas(1).withReplicas(1).endStatus()
                .build();

        List<WorkloadProbeResultDTO> r = opsWithDeployments(List.of(d))
                .probe(List.of(target("kube-system", "deployment", "metrics-server", null)));

        assertThat(r.get(0).getReady()).isEqualTo(1);
        assertThat(r.get(0).getDesired()).isEqualTo(1);
    }

    @Test
    void versionPrefersTheAppKubernetesIoVersionLabel() {
        Deployment d = new DeploymentBuilder()
                .withNewMetadata().withName("istiod").withNamespace("istio-system")
                    .addToLabels("app.kubernetes.io/version", "1.30.3").endMetadata()
                .withNewSpec().withNewTemplate().withNewSpec()
                    .addNewContainer().withName("discovery")
                        .withImage("registry.istio.io/release/pilot:1.30.3-distroless").endContainer()
                .endSpec().endTemplate().endSpec()
                .build();

        List<WorkloadProbeResultDTO> r = opsWithDeployments(List.of(d))
                .probe(List.of(target("istio-system", "deployment", "release/pilot", null)));

        assertThat(r.get(0).getVersion()).isEqualTo("1.30.3");
    }

    @Test
    void versionFallsBackToImageTagAndStripsTheDigest() {
        // 真集群实测：tekton 的镜像是 `...controller-<hash>:v1.15.3@sha256:...`，digest 必须剥掉
        Deployment d = withImage("tekton-pipelines-controller", "tekton-pipelines",
                "ghcr.io/tektoncd/pipeline/controller-10a3e32:v1.15.3@sha256:b37f1ab9");

        List<WorkloadProbeResultDTO> r = opsWithDeployments(List.of(d))
                .probe(List.of(target("tekton-pipelines", "deployment", "pipeline/controller-", null)));

        assertThat(r.get(0).getVersion()).isEqualTo("v1.15.3");
    }

    @Test
    void versionKeepsSuffixesLikeDistroless() {
        // 不猜后缀：`1.30.3-distroless` 原样显示，不去猜 -distroless 能不能丢
        Deployment d = withImage("ztunnel", "istio-system", "registry.istio.io/release/ztunnel:1.30.3-distroless");

        List<WorkloadProbeResultDTO> r = opsWithDeployments(List.of(d))
                .probe(List.of(target("istio-system", "deployment", "release/ztunnel", null)));

        assertThat(r.get(0).getVersion()).isEqualTo("1.30.3-distroless");
    }

    @Test
    void aRegistryPortIsNotATag() {
        // `registry.internal:5000/foo` 里的冒号是端口不是 tag —— 取最后一个冒号前要看它有没有越过最后一个 /
        Deployment d = withImage("x", "kube-system", "registry.internal:5000/foo/bar");

        List<WorkloadProbeResultDTO> r = opsWithDeployments(List.of(d))
                .probe(List.of(target("kube-system", "deployment", "foo/bar", null)));

        assertThat(r.get(0).getVersion()).isNull();
    }

    @Test
    void matchingByImageRepoFindsReleasePrefixedNames() {
        // ⭐ 真集群实测：`ps-kube-prometheus-stack-operator` —— 按名字写死就找不到，
        //    换个 Helm release 名（monitoring-）同样能找到，这是决策 4 的全部理由
        Deployment d = withImage("ps-kube-prometheus-stack-operator", "monitor",
                "quay.io/prometheus-operator/prometheus-operator:v0.93.1");

        List<WorkloadProbeResultDTO> r = opsWithDeployments(List.of(d))
                .probe(List.of(target("monitor", "deployment", "prometheus-operator/prometheus-operator", null)));

        assertThat(r.get(0).isFound()).isTrue();
        assertThat(r.get(0).getName()).isEqualTo("ps-kube-prometheus-stack-operator");
    }

    @Test
    void multipleHitsPickTheNameSortedFirstAndSaySoInDetail() {
        // Review Focus #5：升级残留 / 新旧两版并存时静默挑一个会让人对着不知道是哪台的版本号发愁
        Deployment a = withImage("calico-typha-b", "calico-system", "docker.io/calico/typha:v3.30.3");
        Deployment b = withImage("calico-typha-a", "calico-system", "docker.io/calico/typha:v3.29.0");

        List<WorkloadProbeResultDTO> r = opsWithDeployments(List.of(a, b))
                .probe(List.of(target("calico-system", "deployment", "calico/typha", null)));

        assertThat(r.get(0).getName()).isEqualTo("calico-typha-a"); // 名字序第一个
        assertThat(r.get(0).getDetail()).contains("命中 2 个");
    }

    @Test
    void oneListPerNamespaceAndKindRegardlessOfHowManyTargets() {
        // ⭐ 成本约束：三个候选共用 (namespace, kind) → 只 list 一次
        MixedOperation<Deployment, DeploymentList, RollableScalableResource<Deployment>> chain =
                deployChain(List.of());
        KubernetesClient client = clientWithDeployments(chain);
        List<WorkloadProbeTargetDTO> targets = List.of(
                target("kube-system", "deployment", "a/b", null),
                target("kube-system", "deployment", "c/d", null),
                target("kube-system", "deployment", null, "metrics-server"));
        clearInvocations(chain);

        new WorkloadProbeOperations(client).probe(targets);

        verify(chain, times(1)).inNamespace("kube-system");
        verify(chain, never()).inNamespace("istio-system");
        verify(chain, never()).inAnyNamespace();
    }

    @Test
    void aFailedListIsReportedAsErrorNotEmptyResult() {
        // 探测失败必须有别于"没装"（四态里的 UNKNOWN 全靠这个 error 字段）
        MixedOperation<Deployment, DeploymentList, RollableScalableResource<Deployment>> chain =
                mock(MixedOperation.class);
        NonNamespaceOperation<Deployment, DeploymentList, RollableScalableResource<Deployment>> ns =
                mock(NonNamespaceOperation.class);
        when(chain.inNamespace(anyString())).thenReturn(ns);
        when(ns.list()).thenThrow(new RuntimeException("连接被拒"));

        List<WorkloadProbeResultDTO> r = new WorkloadProbeOperations(clientWithDeployments(chain))
                .probe(List.of(target("kube-system", "deployment", "a/b", null)));

        assertThat(r.get(0).isFound()).isFalse();
        assertThat(r.get(0).getError()).contains("连接被拒");
    }

    @Test
    void aNamespaceWithNoMatchSaysWhereWeLookedInsteadOfClaimingNotInstalled() {
        // Review Focus #4：候选命名空间之外我们**没去看**，所以措辞只能是"在平台已知的位置没找到"
        List<WorkloadProbeResultDTO> r = opsWithDeployments(List.of())
                .probe(List.of(target("istio-system", "deployment", "release/pilot", "istiod")));

        assertThat(r.get(0).isFound()).isFalse();
        assertThat(r.get(0).getError()).isNull();
        assertThat(r.get(0).getDetail()).contains("没有找到").contains("istio-system");
    }

    // ==================== 夹具 ====================

    private static WorkloadProbeTargetDTO target(String ns, String kind, String imageRepo, String name) {
        WorkloadProbeTargetDTO t = new WorkloadProbeTargetDTO();
        t.setNamespace(ns);
        t.setKind(kind);
        t.setImageRepo(imageRepo);
        t.setName(name);
        return t;
    }

    private static Deployment withImage(String name, String ns, String image) {
        return new DeploymentBuilder()
                .withNewMetadata().withName(name).withNamespace(ns).endMetadata()
                .withNewSpec().withNewTemplate().withNewSpec()
                    .addNewContainer().withName("c").withImage(image).endContainer()
                .endSpec().endTemplate().endSpec().build();
    }

    /** Deployment 的 mock 链：inNamespace(ns).list() → 给定列表 */
    @SuppressWarnings("unchecked")
    private static MixedOperation<Deployment, DeploymentList, RollableScalableResource<Deployment>> deployChain(
            List<Deployment> items) {
        MixedOperation<Deployment, DeploymentList, RollableScalableResource<Deployment>> op =
                mock(MixedOperation.class);
        NonNamespaceOperation<Deployment, DeploymentList, RollableScalableResource<Deployment>> ns =
                mock(NonNamespaceOperation.class);
        when(op.inNamespace(anyString())).thenReturn(ns);
        when(ns.list()).thenReturn(new DeploymentListBuilder().withItems(items).build());
        return op;
    }

    @SuppressWarnings("unchecked")
    private static MixedOperation<DaemonSet, DaemonSetList, Resource<DaemonSet>> daemonSetChain(
            List<DaemonSet> items) {
        MixedOperation<DaemonSet, DaemonSetList, Resource<DaemonSet>> op =
                mock(MixedOperation.class);
        NonNamespaceOperation<DaemonSet, DaemonSetList, Resource<DaemonSet>> ns =
                mock(NonNamespaceOperation.class);
        when(op.inNamespace(anyString())).thenReturn(ns);
        when(ns.list()).thenReturn(new DaemonSetListBuilder().withItems(items).build());
        return op;
    }

    private static KubernetesClient clientWithDeployments(
            MixedOperation<Deployment, DeploymentList, RollableScalableResource<Deployment>> chain) {
        AppsAPIGroupDSL apps = mock(AppsAPIGroupDSL.class);
        when(apps.deployments()).thenReturn(chain);
        KubernetesClient client = mock(KubernetesClient.class);
        when(client.apps()).thenReturn(apps);
        return client;
    }

    private static WorkloadProbeOperations opsWithDeployments(List<Deployment> items) {
        return new WorkloadProbeOperations(clientWithDeployments(deployChain(items)));
    }

    private static WorkloadProbeOperations opsWithDaemonSets(List<DaemonSet> items) {
        // ⚠️ 先建链再 stub：把 `daemonSetChain(items)` 直接塞进 thenReturn(...) 会触发
        //    Mockito 的 UnfinishedStubbing（在一条 stub 的参数里又去 stub 别的 mock）
        MixedOperation<DaemonSet, DaemonSetList, Resource<DaemonSet>> chain = daemonSetChain(items);
        AppsAPIGroupDSL apps = mock(AppsAPIGroupDSL.class);
        when(apps.daemonSets()).thenReturn(chain);
        KubernetesClient client = mock(KubernetesClient.class);
        when(client.apps()).thenReturn(apps);
        return new WorkloadProbeOperations(client);
    }
}
