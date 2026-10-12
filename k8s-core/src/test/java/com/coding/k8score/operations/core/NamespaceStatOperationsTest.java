package com.coding.k8score.operations.core;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.models.k8s.dto.NamespaceSnapshotDTO;
import com.coding.k8score.operations.core.ClusterAggregationOperations.ClusterObjects;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaimList;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.kubernetes.api.model.ResourceQuotaList;
import io.fabric8.kubernetes.api.model.ServiceList;
import io.fabric8.kubernetes.api.model.apps.DaemonSetList;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.api.model.apps.DeploymentList;
import io.fabric8.kubernetes.api.model.apps.StatefulSetList;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.AppsAPIGroupDSL;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 单命名空间采样（{@link NamespaceStatOperations}）。
 * <p>本测试钉住 spec §4 的不变式：<b>{@code row(ns)} 装配出来的行，必须与集群聚合同一 ns 的切片逐字段相同</b>
 * —— 由实现里 {@code row(...)} 直接复用 {@link ClusterAggregationOperations#namespaceSnapshots} 保证，
 * 不是靠人工对齐两段代码。
 *
 * <p><b>为什么必须真的调用 {@code row(...)}</b>：若只是构造两次 {@code namespaceSnapshots} 再互比，
 * 那是恒真断言 —— {@code row()} 的真实逻辑（7 族 {@code inNamespace().list()} 的接线 +
 * {@code getOrDefault → emptySnapshot} 兜底 + 空 ns 名守卫）零覆盖，将来有人把 {@code row()} 换成
 * 另一条采样实现，测试会静默放行。故这里把同一批对象喂进 client 的 fluent 链（逐族打桩），
 * 再拿 {@code row(...)} 的输出与"权威装配函数"的输出比对：取错族 / 取错 ns / 兜底被改坏，断言都会变红。
 *
 * <p><b>为什么不用 {@code Mockito.RETURNS_DEEP_STUBS}</b>：fabric8 的 DSL 是自引用泛型
 * （{@code MixedOperation<T,L,R extends Resource<T>>}），deep stub 无法解析 {@code inNamespace} 的返回类型，
 * 会在运行时回 {@code null} 而不是 stub。故改为显式构造各层 mock（raw 类型 + {@code @SuppressWarnings}），
 * 这与 fabric8 具体版本无关 —— 只依赖稳定的方法名。
 */
class NamespaceStatOperationsTest {

    /** 固定采样基准（unix 秒）：期望值一侧用它。 */
    private static final long NOW = 1_700_000_000L;

    private static final String NS = "team-a";

    private Pod pod() {
        // 不带 creationTimestamp：异常 Pod 的 ageSeconds 两侧恒为 null，不随时间漂移
        return new PodBuilder().withNewMetadata().withNamespace(NS).withName("p1").endMetadata()
                .withNewStatus().withPhase("Pending").endStatus().build();
    }

    private Deployment deployment() {
        return new DeploymentBuilder().withNewMetadata().withNamespace(NS).withName("web").endMetadata()
                .withNewSpec().withReplicas(2).endSpec()
                .withNewStatus().withReplicas(2).withReadyReplicas(1).endStatus().build();
    }

    /** 7 族 fluent 链逐族打桩：pods/deployments 是"集群里真实存在"的那批，其余 5 族返回空。 */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private KubernetesClient clientServing(List<Pod> pods, List<Deployment> deployments) {
        KubernetesClient client = mock(KubernetesClient.class);

        MixedOperation podsOp = mock(MixedOperation.class);
        stubNamespace(podsOp, podList(pods));
        when(client.pods()).thenReturn(podsOp);

        AppsAPIGroupDSL apps = mock(AppsAPIGroupDSL.class);
        MixedOperation deploymentsOp = mock(MixedOperation.class);
        stubNamespace(deploymentsOp, deploymentList(deployments));
        when(apps.deployments()).thenReturn(deploymentsOp);

        MixedOperation statefulSetsOp = mock(MixedOperation.class);
        stubNamespace(statefulSetsOp, new StatefulSetList());
        when(apps.statefulSets()).thenReturn(statefulSetsOp);

        MixedOperation daemonSetsOp = mock(MixedOperation.class);
        stubNamespace(daemonSetsOp, new DaemonSetList());
        when(apps.daemonSets()).thenReturn(daemonSetsOp);
        when(client.apps()).thenReturn(apps);

        MixedOperation servicesOp = mock(MixedOperation.class);
        stubNamespace(servicesOp, new ServiceList());
        when(client.services()).thenReturn(servicesOp);

        MixedOperation quotasOp = mock(MixedOperation.class);
        stubNamespace(quotasOp, new ResourceQuotaList());
        when(client.resourceQuotas()).thenReturn(quotasOp);

        MixedOperation pvcsOp = mock(MixedOperation.class);
        stubNamespace(pvcsOp, new PersistentVolumeClaimList());
        when(client.persistentVolumeClaims()).thenReturn(pvcsOp);

        return client;
    }

    /** 把 {@code op.inNamespace(NS).list()} 接到给定 list 对象（fabric8 DSL 的一跳名字固定，逐族复用）。 */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void stubNamespace(MixedOperation op, Object list) {
        NonNamespaceOperation nsOp = mock(NonNamespaceOperation.class);
        when(nsOp.list()).thenReturn(list);
        when(op.inNamespace(NS)).thenReturn(nsOp);
    }

    private static PodList podList(List<Pod> pods) {
        PodList list = new PodList();
        list.setItems(pods);
        return list;
    }

    private static DeploymentList deploymentList(List<Deployment> deployments) {
        DeploymentList list = new DeploymentList();
        list.setItems(deployments);
        return list;
    }

    /**
     * 两条采样路径产出的切片逐字段相同 —— <b>这里真的调用了 {@code row(...)}</b>。
     * <p><b>忽略 {@code sampledAt} 是口径选择，不是漏字段</b>：两条路径各自在自己的时刻采样
     * （{@code row()} 用 {@code Instant.now()}，期望值一侧用固定的 {@link #NOW}），这个时间戳本来就
     * <b>应该</b>不同；其余字段（计数 / requests / quota / 异常名单与 reason 分组 / 不健康清单）都是
     * 同批对象的纯函数，必须相同。
     */
    @Test
    void row_equals_the_cluster_paths_slice_for_the_same_objects() {
        Pod p = pod();
        Deployment d = deployment();

        NamespaceStatOperations ops = new NamespaceStatOperations(clientServing(List.of(p), List.of(d)));

        NamespaceSnapshotDTO expected = ClusterAggregationOperations.namespaceSnapshots(
                new ClusterObjects(List.of(p), List.of(d), List.of(), List.of(), List.of(), List.of(),
                        List.of(), List.of(), List.of()),
                NOW).get(NS);

        assertThat(ops.row(NS))
                .usingRecursiveComparison()
                .ignoringFields("sampledAt")
                .isEqualTo(expected);
    }

    /** 该 ns 一个对象都没有：{@code row(...)} 走 {@code getOrDefault} 兜底，回全零行（配额 null），不是 null。 */
    @Test
    void row_of_an_empty_namespace_equals_the_zero_snapshot() {
        NamespaceStatOperations ops = new NamespaceStatOperations(clientServing(List.of(), List.of()));

        NamespaceSnapshotDTO actual = ops.row(NS);

        assertThat(actual)
                .usingRecursiveComparison()
                .ignoringFields("sampledAt")
                .isEqualTo(NamespaceStatOperations.emptySnapshot(NS, 0L));
        assertThat(actual.getStat().getNamespace()).isEqualTo(NS);
        assertThat(actual.getStat().getQuotaCpuHard()).isNull();
        assertThat(actual.getAbnormalPodCount()).isZero();
    }

    /** 空 ns 名不是"回全零行"，而是参数错误（同既有 ops 的写法）。 */
    @Test
    void blank_namespace_is_rejected() {
        NamespaceStatOperations ops = new NamespaceStatOperations(mock(KubernetesClient.class));

        assertThat(catchThrowable(() -> ops.row("  "))).isInstanceOf(CloudPlatformException.class);
    }
}
