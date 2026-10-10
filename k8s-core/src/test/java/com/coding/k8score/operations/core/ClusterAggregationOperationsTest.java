package com.coding.k8score.operations.core;

import com.coding.common.models.k8s.dto.AbnormalPodDTO;
import com.coding.common.models.k8s.dto.ClusterAggregateDTO;
import com.coding.common.models.k8s.dto.NamespaceStatDTO;
import com.coding.common.models.k8s.dto.NodeHealthDTO;
import com.coding.common.models.k8s.dto.UnhealthyWorkloadDTO;
import com.coding.k8score.operations.core.ClusterAggregationOperations.ClusterObjects;
import io.fabric8.kubernetes.api.model.ContainerBuilder;
import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.ContainerStatusBuilder;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.KubernetesResourceList;
import io.fabric8.kubernetes.api.model.Node;
import io.fabric8.kubernetes.api.model.NodeBuilder;
import io.fabric8.kubernetes.api.model.NodeList;
import io.fabric8.kubernetes.api.model.NodeListBuilder;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.PersistentVolume;
import io.fabric8.kubernetes.api.model.PersistentVolumeBuilder;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaim;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaimBuilder;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaimList;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaimListBuilder;
import io.fabric8.kubernetes.api.model.PersistentVolumeList;
import io.fabric8.kubernetes.api.model.PersistentVolumeListBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.kubernetes.api.model.PodListBuilder;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.ResourceQuota;
import io.fabric8.kubernetes.api.model.ResourceQuotaBuilder;
import io.fabric8.kubernetes.api.model.ResourceQuotaList;
import io.fabric8.kubernetes.api.model.ResourceQuotaListBuilder;
import io.fabric8.kubernetes.api.model.ResourceRequirementsBuilder;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServiceBuilder;
import io.fabric8.kubernetes.api.model.ServiceList;
import io.fabric8.kubernetes.api.model.ServiceListBuilder;
import io.fabric8.kubernetes.api.model.apps.DaemonSet;
import io.fabric8.kubernetes.api.model.apps.DaemonSetBuilder;
import io.fabric8.kubernetes.api.model.apps.DaemonSetList;
import io.fabric8.kubernetes.api.model.apps.DaemonSetListBuilder;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.api.model.apps.DeploymentList;
import io.fabric8.kubernetes.api.model.apps.DeploymentListBuilder;
import io.fabric8.kubernetes.api.model.apps.StatefulSet;
import io.fabric8.kubernetes.api.model.apps.StatefulSetBuilder;
import io.fabric8.kubernetes.api.model.apps.StatefulSetList;
import io.fabric8.kubernetes.api.model.apps.StatefulSetListBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.AnyNamespaceOperation;
import io.fabric8.kubernetes.client.dsl.AppsAPIGroupDSL;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation;
import io.fabric8.kubernetes.client.dsl.PodResource;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.fabric8.kubernetes.client.dsl.RollableScalableResource;
import io.fabric8.kubernetes.client.dsl.ServiceResource;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
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
 * 集群聚合的口径与性能承诺。三段独立可测：
 * <ol>
 *   <li><b>聚合正确性</b> —— 喂对象列表，断言总量/per-ns 计数、requests·limits 求和、存储与配额。</li>
 *   <li><b>异常 Pod 判定</b>（口径 §1）—— 尤其是边界：<b>Running 但容器 CrashLoopBackOff 也要算异常</b>、
 *       Succeeded + Completed 不算、一个 pod 命中多个时取最严重的。</li>
 *   <li><b>性能承诺</b> —— {@code aggregate()} 固定 9 次 {@code inAnyNamespace().list()}，
 *       <b>绝不按命名空间 list</b>（大集群上是 O(ns) 次 round trip）。</li>
 * </ol>
 * 纯聚合方法用 mock client 只为满足构造器；不 mock 任何链（见 {@code ops(objects)}）。
 */
class ClusterAggregationOperationsTest {

    /** 计算 ageSeconds 的基准时刻 */
    private static final long NOW = 1_760_054_400L; // 2025-10-10T00:00:00Z

    private final ClusterAggregationOperations ops =
            new ClusterAggregationOperations(mock(KubernetesClient.class));

    // ==================== 1) 聚合正确性 ====================

    @Test
    void aggregate_sums_requests_limits_and_counts_per_namespace() {
        ClusterAggregateDTO agg = ops.aggregate(new ClusterObjects(
                List.of(pod("a", "p1", "Running", "200m", "128Mi", "500m", "256Mi"),
                        pod("a", "p2", "Running", "1", "1Gi", null, null),
                        pod("b", "p3", "Running", null, null, null, null)),
                List.of(deployment("a", "d1"), deployment("b", "d2")),
                List.of(statefulSet("a", "s1")),
                List.of(daemonSet("a", "ds1"), daemonSet("c", "ds2")),
                List.of(service("a", "svc1")),
                List.of(),
                List.of(),
                List.of(),
                List.of()), NOW);

        assertThat(agg.getNamespaces()).extracting(NamespaceStatDTO::getNamespace)
                .containsExactly("a", "b", "c"); // TreeMap → 升序

        NamespaceStatDTO a = row(agg, "a");
        assertThat(a.getPodCount()).isEqualTo(2);
        assertThat(a.getDeployCount()).isEqualTo(1);
        assertThat(a.getStsCount()).isEqualTo(1);
        assertThat(a.getDsCount()).isEqualTo(1);
        assertThat(a.getSvcCount()).isEqualTo(1);
        assertThat(a.getCpuRequest()).isEqualByComparingTo("1.2");            // 200m + 1
        assertThat(a.getMemRequest()).isEqualByComparingTo("1207959552");     // 128Mi + 1Gi
        assertThat(a.getCpuLimit()).isEqualByComparingTo("0.5");
        assertThat(a.getMemLimit()).isEqualByComparingTo("268435456");

        NamespaceStatDTO b = row(agg, "b");
        assertThat(b.getPodCount()).isEqualTo(1);
        assertThat(b.getCpuRequest()).isEqualByComparingTo("0"); // 无 requests 的 pod 计 0，不是 null

        // c 只有 DaemonSet：per-ns 行按对象并集出现（不是只按 pod 出现）
        NamespaceStatDTO c = row(agg, "c");
        assertThat(c.getPodCount()).isZero();
        assertThat(c.getDsCount()).isEqualTo(1);

        assertThat(agg.getTotal().getPodCount()).isEqualTo(3);
        assertThat(agg.getTotal().getDeploymentCount()).isEqualTo(2);
        assertThat(agg.getTotal().getStatefulsetCount()).isEqualTo(1);
        assertThat(agg.getTotal().getDaemonsetCount()).isEqualTo(2);
        assertThat(agg.getTotal().getServiceCount()).isEqualTo(1);
        assertThat(agg.getTotal().getNamespaceCount()).isEqualTo(3);
        assertThat(agg.getTotal().getCpuRequest()).isEqualByComparingTo("1.2");
    }

    @Test
    void aggregate_fills_quota_from_resourcequota_spec_and_status() {
        // K8s 的真实形态：hard 在 spec（期望值），used 在 status（控制器算出的实际占用）
        ResourceQuota quota = new ResourceQuotaBuilder()
                .withNewMetadata().withNamespace("a").withName("q").endMetadata()
                .withNewSpec()
                .addToHard("requests.cpu", Quantity.parse("2"))
                .addToHard("requests.memory", Quantity.parse("2Gi"))
                .endSpec()
                .withNewStatus()
                .addToUsed("requests.cpu", Quantity.parse("700m"))
                .addToUsed("requests.memory", Quantity.parse("512Mi"))
                .endStatus()
                .build();

        ClusterAggregateDTO agg = ops.aggregate(new ClusterObjects(
                List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(quota), List.of(), List.of(), List.of()), NOW);

        NamespaceStatDTO a = row(agg, "a");
        assertThat(a.getQuotaCpuHard()).isEqualByComparingTo("2");
        assertThat(a.getQuotaMemHard()).isEqualByComparingTo("2147483648");
        assertThat(a.getQuotaCpuUsed()).isEqualByComparingTo("0.7");
        assertThat(a.getQuotaMemUsed()).isEqualByComparingTo("536870912");
    }

    @Test
    void quota_without_cpu_key_stays_null_not_zero() {
        // 「只限 pod 数、没限 CPU」的配额：CPU 列必须是「—」而不是 0（0 读起来像「CPU 全被禁」）
        ResourceQuota podOnly = new ResourceQuotaBuilder()
                .withNewMetadata().withNamespace("a").withName("q").endMetadata()
                .withNewSpec().addToHard("pods", Quantity.parse("10")).endSpec()
                .build();

        ClusterAggregateDTO agg = ops.aggregate(new ClusterObjects(
                List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(podOnly), List.of(), List.of(), List.of()), NOW);

        assertThat(row(agg, "a").getQuotaCpuHard()).isNull();
        assertThat(row(agg, "a").getQuotaCpuUsed()).isNull();
    }

    @Test
    void aggregate_leaves_quota_null_when_namespace_has_none() {
        ClusterAggregateDTO agg = ops.aggregate(new ClusterObjects(
                List.of(pod("a", "p", "Running", null, null, null, null)),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of()), NOW);

        NamespaceStatDTO a = row(agg, "a");
        // null（不是 0）—— 前端据此显示「—」，0 会被读成「配额耗尽」
        assertThat(a.getQuotaCpuHard()).isNull();
        assertThat(a.getQuotaMemUsed()).isNull();
    }

    @Test
    void aggregate_counts_pv_capacity_and_pvc_phases() {
        ClusterAggregateDTO agg = ops.aggregate(new ClusterObjects(
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(pvc("a", "pvc1", "Bound"), pvc("a", "pvc2", "Pending"), pvc("b", "pvc3", "Lost")),
                List.of(pv("pv1", "10Gi", "Bound"), pv("pv2", "5Gi", "Available"), pv("pv3", "1Gi", "Bound")),
                List.of()), NOW);

        assertThat(agg.getStorage().getPvCount()).isEqualTo(3);
        assertThat(agg.getStorage().getPvCapacityBytes()).isEqualByComparingTo("17179869184"); // 16Gi
        assertThat(agg.getStorage().getPvBoundBytes()).isEqualByComparingTo("11811160064");    // 11Gi
        assertThat(agg.getStorage().getPvcBound()).isEqualTo(1);
        assertThat(agg.getStorage().getPvcPending()).isEqualTo(1);
        assertThat(agg.getStorage().getPvcLost()).isEqualTo(1);

        assertThat(agg.getTotal().getPvcCount()).isEqualTo(3);
        assertThat(agg.getTotal().getPvCount()).isEqualTo(3);
        assertThat(row(agg, "a").getPvcCount()).isEqualTo(2);
        assertThat(row(agg, "b").getPvcCount()).isEqualTo(1);
    }

    @Test
    void aggregate_reports_node_health_capacity_and_pressures() {
        ClusterAggregateDTO agg = ops.aggregate(new ClusterObjects(
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(node("n1", "True", List.of()),
                        node("n2", "False", List.of("MemoryPressure", "DiskPressure")),
                        node("n3", "True", List.of("DiskPressure")))), NOW);

        assertThat(agg.getNodeHealth()).extracting(NodeHealthDTO::getName).containsExactly("n1", "n2", "n3");
        assertThat(health(agg, "n1").isReady()).isTrue();
        assertThat(health(agg, "n2").isReady()).isFalse();
        assertThat(health(agg, "n2").getPressures()).containsExactly("MemoryPressure", "DiskPressure");
        // Ready 但带 pressure 的节点也要把 pressure 交出来（首屏要把它列进「该看哪里」）
        assertThat(health(agg, "n3").isReady()).isTrue();
        assertThat(health(agg, "n3").getPressures()).containsExactly("DiskPressure");
        assertThat(health(agg, "n1").getVersion()).isEqualTo("v1.30.0");

        // allocatable 是「三个节点之和」，不是 capacity
        assertThat(agg.getNodeCapacity().getCpuAllocatable()).isEqualByComparingTo("9");  // 2+4+3（capacity 是 4+8+6）
        assertThat(agg.getNodeCapacity().getMemoryAllocatable()).isEqualByComparingTo("12884901888"); // 12Gi
    }

    @Test
    void aggregate_treats_missing_or_unknown_ready_condition_as_not_ready() {
        Node noConditions = new NodeBuilder().withNewMetadata().withName("n-bar").endMetadata().build();
        Node unknown = node("n-unknown", "Unknown", List.of());

        ClusterAggregateDTO agg = ops.aggregate(new ClusterObjects(
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(noConditions, unknown)), NOW);

        assertThat(health(agg, "n-bar").isReady()).isFalse();
        assertThat(health(agg, "n-bar").getPressures()).isEmpty();
        assertThat(health(agg, "n-unknown").isReady()).isFalse(); // Unknown 也算不可调度
    }

    // ==================== 2) 异常 Pod 判定（口径 §1）====================

    @Test
    void abnormal_running_pod_with_crashloopbackoff_is_abnormal() {
        // 本批最容易被漏的边界：phase=Running 的 pod 看着"正常"，容器却在崩溃重启
        Pod p = withContainerStatus(pod("a", "p1", "Running", null, null, null, null),
                waiting("CrashLoopBackOff", 7));

        AbnormalPodDTO ab = ClusterAggregationOperations.abnormalOf(p, NOW);

        assertThat(ab).isNotNull();
        assertThat(ab.getReason()).isEqualTo("CrashLoopBackOff");
        assertThat(ab.getRestarts()).isEqualTo(7);
        assertThat(ab.getPhase()).isEqualTo("Running");
    }

    @Test
    void abnormal_covers_pending_failed_unknown_phases() {
        assertThat(reasonOf(pod("a", "p", "Pending", null, null, null, null))).isEqualTo("Pending");
        assertThat(reasonOf(pod("a", "p", "Failed", null, null, null, null))).isEqualTo("Failed");
        assertThat(reasonOf(pod("a", "p", "Unknown", null, null, null, null))).isEqualTo("Unknown");
    }

    @Test
    void abnormal_covers_image_pull_and_oomkilled() {
        Pod pulling = withContainerStatus(pod("a", "p1", "Pending", null, null, null, null),
                waiting("ImagePullBackOff", 0));
        Pod oom = withContainerStatus(pod("a", "p2", "Running", null, null, null, null),
                terminated("OOMKilled", 3));

        assertThat(reasonOf(pulling)).isEqualTo("ImagePullBackOff");
        assertThat(reasonOf(oom)).isEqualTo("OOMKilled");
    }

    @Test
    void abnormal_init_container_crashloop_is_reported() {
        // 卡在 Init:CrashLoopBackOff 的 pod 其 phase 也是 Pending；不查 init 就只能笼统归到「Pending」
        Pod p = new PodBuilder(pod("a", "p1", "Pending", null, null, null, null))
                .editOrNewStatus()
                .withInitContainerStatuses(waiting("CrashLoopBackOff", 4))
                .endStatus()
                .build();

        AbnormalPodDTO ab = ClusterAggregationOperations.abnormalOf(p, NOW);

        assertThat(ab.getReason()).isEqualTo("CrashLoopBackOff");
        assertThat(ab.getRestarts()).isEqualTo(4);
    }

    @Test
    void abnormal_picks_the_most_severe_reason() {
        // phase=Pending（最轻）但容器 OOMKilled（较重）→ 取 OOMKilled
        Pod p = new PodBuilder(pod("a", "p1", "Pending", null, null, null, null))
                .editOrNewStatus()
                .withContainerStatuses(waiting("ImagePullBackOff", 0), terminated("OOMKilled", 1))
                .endStatus()
                .build();

        assertThat(reasonOf(p)).isEqualTo("OOMKilled");
    }

    @Test
    void healthy_pods_are_not_abnormal() {
        // Running + 容器 running 中
        Pod running = withContainerStatus(pod("a", "p1", "Running", null, null, null, null),
                new ContainerStatusBuilder().withName("c").withRestartCount(0)
                        .withNewState().withNewRunning().endRunning().endState()
                        .build());
        // Succeeded + 正常退出（Job 的成功态，不是异常）
        Pod completed = withContainerStatus(pod("a", "p2", "Succeeded", null, null, null, null),
                terminated("Completed", 0));

        assertThat(ClusterAggregationOperations.abnormalOf(running, NOW)).isNull();
        assertThat(ClusterAggregationOperations.abnormalOf(completed, NOW)).isNull();
    }

    @Test
    void abnormal_carries_node_and_age() {
        Pod p = new PodBuilder(pod("a", "p1", "Pending", null, null, null, null))
                .editMetadata().withCreationTimestamp("2025-10-09T23:50:00Z").endMetadata()
                .editSpec().withNodeName("n1").endSpec()
                .build();

        AbnormalPodDTO ab = ClusterAggregationOperations.abnormalOf(p, NOW);

        assertThat(ab.getNode()).isEqualTo("n1");
        assertThat(ab.getAgeSeconds()).isEqualTo(600);
    }

    @Test
    void aggregate_caps_abnormal_list_but_reports_full_total() {
        List<Pod> pods = new ArrayList<>();
        for (int i = 0; i < ClusterAggregationOperations.ABNORMAL_POD_CAP + 25; i++) {
            pods.add(pod("a", "p" + i, "Pending", null, null, null, null));
        }

        ClusterAggregateDTO agg = ops.aggregate(new ClusterObjects(
                pods, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of()), NOW);

        assertThat(agg.getAbnormalPods()).hasSize(ClusterAggregationOperations.ABNORMAL_POD_CAP);
        assertThat(agg.getAbnormalPodTotal()).isEqualTo(ClusterAggregationOperations.ABNORMAL_POD_CAP + 25);
        // 封顶的是明细，不是计数 —— total 仍等于全部 pod 数
        assertThat(agg.getTotal().getPodCount()).isEqualTo(ClusterAggregationOperations.ABNORMAL_POD_CAP + 25);
    }

    @Test
    void abnormal_list_is_sorted_by_severity_then_restarts() {
        Pod light = pod("a", "light", "Pending", null, null, null, null);
        Pod heavy = withContainerStatus(pod("a", "heavy", "Running", null, null, null, null),
                waiting("CrashLoopBackOff", 9));
        Pod mid = withContainerStatus(pod("a", "mid", "Running", null, null, null, null),
                waiting("CrashLoopBackOff", 2));

        ClusterAggregateDTO agg = ops.aggregate(new ClusterObjects(
                List.of(light, mid, heavy), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of()), NOW);

        assertThat(agg.getAbnormalPods()).extracting(AbnormalPodDTO::getName)
                .containsExactly("heavy", "mid", "light");
    }

    // ==================== 3) 性能承诺 ====================

    @Test
    void aggregate_lists_each_family_once_and_never_per_namespace() {
        // 100 个命名空间：list 次数必须与 1 个命名空间时完全相同（固定 9 次）
        List<Pod> pods = new ArrayList<>();
        List<Deployment> deployments = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            pods.add(pod("ns-" + i, "p" + i, "Running", "100m", "64Mi", null, null));
            deployments.add(deployment("ns-" + i, "d" + i));
        }
        ClusterObjects objects = new ClusterObjects(pods, deployments, List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of());

        Fixture f = Fixture.of(objects).wire();

        ClusterAggregateDTO agg = f.ops().aggregate();

        assertThat(agg.getTotal().getNamespaceCount()).isEqualTo(100);
        assertThat(agg.getTotal().getPodCount()).isEqualTo(100);
        // 每条 fluent 链恰好一次 inAnyNamespace().list()，且从未按命名空间 list
        f.assertFixedListCount();
    }

    // ==================== 2) 不健康工作负载（口径 §3）====================

    @Test
    void unhealthy_workload_when_ready_below_replicas() {
        Deployment bad = deployment("a", "d-bad", 5, 1);      // 缺 4
        Deployment ok = deployment("a", "d-ok", 3, 3);       // 齐
        StatefulSet half = statefulSet("b", "s-half", 2, 0); // 缺 2

        ClusterAggregateDTO agg = ops.aggregate(new ClusterObjects(
                List.of(), List.of(bad, ok), List.of(half), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of()), NOW);

        // 缺得最多的排前面 → 只列不齐的
        assertThat(agg.getUnhealthyWorkloads()).extracting(UnhealthyWorkloadDTO::getName)
                .containsExactly("d-bad", "s-half");
        assertThat(agg.getUnhealthyWorkloadTotal()).isEqualTo(2);
        UnhealthyWorkloadDTO first = agg.getUnhealthyWorkloads().get(0);
        assertThat(first.getKind()).isEqualTo("Deployment");
        assertThat(first.getReplicas()).isEqualTo(5);
        assertThat(first.getReadyReplicas()).isEqualTo(1);
        assertThat(first.getNamespace()).isEqualTo("a");
        // 计数不受影响（顺带出的判定不该改变 per-ns 计数）
        assertThat(agg.getTotal().getDeploymentCount()).isEqualTo(2);
        assertThat(agg.getTotal().getStatefulsetCount()).isEqualTo(1);
    }

    @Test
    void workload_scaled_to_zero_is_not_unhealthy() {
        // replicas=0 是有意缩容到零，不是故障 —— 0 < 0 为假，且 readyReplicas 缺席按 0 计
        ClusterAggregateDTO agg = ops.aggregate(new ClusterObjects(
                List.of(), List.of(deployment("a", "d-zero", 0, null)), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of()), NOW);

        assertThat(agg.getUnhealthyWorkloads()).isEmpty();
        assertThat(agg.getUnhealthyWorkloadTotal()).isZero();
    }

    @Test
    void workload_missing_status_counts_as_unhealthy_with_one_replica_default() {
        // 刚创建、status 还没写：spec/status 全缺 → replicas 按 K8s 默认 1、ready 按 0 → 应进清单
        ClusterAggregateDTO agg = ops.aggregate(new ClusterObjects(
                List.of(), List.of(deployment("a", "d-new")), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of()), NOW);

        assertThat(agg.getUnhealthyWorkloads()).hasSize(1);
        assertThat(agg.getUnhealthyWorkloads().get(0).getReplicas()).isEqualTo(1);
        assertThat(agg.getUnhealthyWorkloads().get(0).getReadyReplicas()).isZero();
    }

    @Test
    void workload_spec_present_but_replicas_omitted_defaults_to_one() {
        UnhealthyWorkloadDTO d = ClusterAggregationOperations.unhealthyOf(
                new DeploymentBuilder().withMetadata(meta("a", "d")).withNewSpec().endSpec().build(), "Deployment");

        assertThat(d).isNotNull();
        assertThat(d.getReplicas()).isEqualTo(1);
    }

    @Test
    void daemonSet_has_no_replicas_semantics_so_never_listed() {
        assertThat(ClusterAggregationOperations.unhealthyOf(daemonSet("a", "ds"), "DaemonSet")).isNull();
    }

    @Test
    void unhealthy_workload_list_is_capped_but_total_is_not() {
        List<Deployment> deployments = new ArrayList<>();
        for (int i = 0; i < ClusterAggregationOperations.UNHEALTHY_WORKLOAD_CAP + 10; i++) {
            deployments.add(deployment("a", "d" + i, 2, 0));
        }

        ClusterAggregateDTO agg = ops.aggregate(new ClusterObjects(
                List.of(), deployments, List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of()), NOW);

        assertThat(agg.getUnhealthyWorkloads()).hasSize(ClusterAggregationOperations.UNHEALTHY_WORKLOAD_CAP);
        assertThat(agg.getUnhealthyWorkloadTotal()).isEqualTo(ClusterAggregationOperations.UNHEALTHY_WORKLOAD_CAP + 10);
    }

    @Test
    void abnormal_reason_counts_cover_the_full_list_not_the_capped_one() {
        // 首屏那个「CrashLoopBackOff 3 · Pending 2」必须是全量数字：集群真有 205 个 Pending 时不能算成 200
        List<Pod> pods = new ArrayList<>();
        for (int i = 0; i < ClusterAggregationOperations.ABNORMAL_POD_CAP + 5; i++) {
            pods.add(pod("a", "p" + i, "Pending", null, null, null, null));
        }
        pods.add(withContainerStatus(pod("a", "crash", "Running", null, null, null, null),
                waiting("CrashLoopBackOff", 3)));

        ClusterAggregateDTO agg = ops.aggregate(new ClusterObjects(
                pods, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of()), NOW);

        assertThat(agg.getAbnormalPods()).hasSize(ClusterAggregationOperations.ABNORMAL_POD_CAP); // 明细封顶
        assertThat(agg.getAbnormalPodTotal()).isEqualTo(ClusterAggregationOperations.ABNORMAL_POD_CAP + 6);
        assertThat(agg.getAbnormalPodReasonCounts())
                .containsEntry("Pending", ClusterAggregationOperations.ABNORMAL_POD_CAP + 5)
                .containsEntry("CrashLoopBackOff", 1);
        // 顺序 = 严重度降序（首屏 chip 的稳定顺序）
        assertThat(agg.getAbnormalPodReasonCounts().keySet()).containsExactly("CrashLoopBackOff", "Pending");
    }

    // ==================== 造对象 / 取行的小工具 ====================

    private static NamespaceStatDTO row(ClusterAggregateDTO agg, String ns) {
        return agg.getNamespaces().stream().filter(r -> ns.equals(r.getNamespace())).findFirst().orElseThrow();
    }

    private static NodeHealthDTO health(ClusterAggregateDTO agg, String name) {
        return agg.getNodeHealth().stream().filter(n -> name.equals(n.getName())).findFirst().orElseThrow();
    }

    private static String reasonOf(Pod pod) {
        return ClusterAggregationOperations.abnormalReason(pod);
    }

    private static Pod pod(String ns, String name, String phase,
                           String cpuReq, String memReq, String cpuLim, String memLim) {
        ResourceRequirementsBuilder rr = new ResourceRequirementsBuilder();
        if (cpuReq != null) {
            rr.addToRequests("cpu", Quantity.parse(cpuReq));
        }
        if (memReq != null) {
            rr.addToRequests("memory", Quantity.parse(memReq));
        }
        if (cpuLim != null) {
            rr.addToLimits("cpu", Quantity.parse(cpuLim));
        }
        if (memLim != null) {
            rr.addToLimits("memory", Quantity.parse(memLim));
        }
        return new PodBuilder()
                .withMetadata(new ObjectMetaBuilder().withNamespace(ns).withName(name).build())
                .withNewSpec().withContainers(new ContainerBuilder().withName("c").withResources(rr.build()).build()).endSpec()
                .withNewStatus().withPhase(phase).endStatus()
                .build();
    }

    private static Pod withContainerStatus(Pod pod, ContainerStatus status) {
        return new PodBuilder(pod).editOrNewStatus().withContainerStatuses(status).endStatus().build();
    }

    private static ContainerStatus waiting(String reason, int restarts) {
        return new ContainerStatusBuilder().withName("c").withRestartCount(restarts)
                .withNewState().withNewWaiting().withReason(reason).endWaiting().endState()
                .build();
    }

    private static ContainerStatus terminated(String reason, int restarts) {
        return new ContainerStatusBuilder().withName("c").withRestartCount(restarts)
                .withNewState().withNewTerminated().withReason(reason).endTerminated().endState()
                .build();
    }

    private static Deployment deployment(String ns, String name) {
        return new DeploymentBuilder().withMetadata(meta(ns, name)).build();
    }

    /** replicas=null → spec 缺（K8s 默认 1）；ready=null → status 缺（ready 按 0） */
    private static Deployment deployment(String ns, String name, Integer replicas, Integer ready) {
        DeploymentBuilder b = new DeploymentBuilder().withMetadata(meta(ns, name));
        if (replicas != null) {
            b = b.editOrNewSpec().withReplicas(replicas).endSpec();
        }
        if (ready != null) {
            b = b.editOrNewStatus().withReadyReplicas(ready).endStatus();
        }
        return b.build();
    }

    private static StatefulSet statefulSet(String ns, String name) {
        return new StatefulSetBuilder().withMetadata(meta(ns, name)).build();
    }

    private static StatefulSet statefulSet(String ns, String name, Integer replicas, Integer ready) {
        StatefulSetBuilder b = new StatefulSetBuilder().withMetadata(meta(ns, name));
        if (replicas != null) {
            b = b.editOrNewSpec().withReplicas(replicas).endSpec();
        }
        if (ready != null) {
            b = b.editOrNewStatus().withReadyReplicas(ready).endStatus();
        }
        return b.build();
    }

    private static DaemonSet daemonSet(String ns, String name) {
        return new DaemonSetBuilder().withMetadata(meta(ns, name)).build();
    }

    private static Service service(String ns, String name) {
        return new ServiceBuilder().withMetadata(meta(ns, name)).build();
    }

    private static PersistentVolumeClaim pvc(String ns, String name, String phase) {
        return new PersistentVolumeClaimBuilder().withMetadata(meta(ns, name))
                .withNewStatus().withPhase(phase).endStatus().build();
    }

    private static PersistentVolume pv(String name, String storage, String phase) {
        return new PersistentVolumeBuilder()
                .withNewMetadata().withName(name).endMetadata()
                .withNewSpec().addToCapacity("storage", Quantity.parse(storage)).endSpec()
                .withNewStatus().withPhase(phase).endStatus()
                .build();
    }

    private static Node node(String name, String readyStatus, List<String> pressures) {
        NodeBuilder b = new NodeBuilder()
                .withNewMetadata().withName(name).endMetadata()
                .withNewStatus()
                .addNewCondition().withType("Ready").withStatus(readyStatus).endCondition()
                .addToAllocatable("cpu", Quantity.parse("3"))
                .addToAllocatable("memory", Quantity.parse("4Gi"))
                .addToCapacity("cpu", Quantity.parse("6"))
                .addToCapacity("memory", Quantity.parse("8Gi"))
                .withNewNodeInfo().withKubeletVersion("v1.30.0").endNodeInfo()
                .endStatus();
        for (String p : pressures) {
            // pressure 条件是 status=True 才算命中（status=False 的那条不列出来）
            b = b.editStatus().addNewCondition().withType(p).withStatus("True").endCondition().endStatus();
        }
        return b.build();
    }

    private static ObjectMeta meta(String ns, String name) {
        return new ObjectMetaBuilder().withNamespace(ns).withName(name).build();
    }

    /**
     * aggregate() 的 client 侧 fixture：为九条 fluent 链各挂一次
     * {@code inAnyNamespace().list()}（cluster-scoped 的两条是裸 {@code list()}）。
     * <p>刻意不用 {@code RETURNS_DEEP_STUBS}：{@code inAnyNamespace()} 的返回类型带泛型变量，
     * deep stubs 解不出来会返回 null（同 {@code MeshOperationsTest} 踩过的坑）。
     */
    private static final class Fixture {

        private final KubernetesClient client = mock(KubernetesClient.class);
        /** inAnyNamespace().list() 的那 7 条链 */
        private final List<MixedOperation<?, ?, ?>> namespacedChains = new ArrayList<>();
        /** cluster-scoped 的 2 条链（PV / Node，裸 list()） */
        private final List<NonNamespaceOperation<?, ?, ?>> clusterChains = new ArrayList<>();

        private ClusterAggregationOperations ops;

        static Fixture of(ClusterObjects objects) {
            return new Fixture(objects);
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        private Fixture(ClusterObjects objects) {
            MixedOperation<Pod, PodList, PodResource> pods = mock(MixedOperation.class);
            when(client.pods()).thenReturn(pods);
            wireAnyNamespace(pods, new PodListBuilder().withItems(objects.pods()).build());

            AppsAPIGroupDSL apps = mock(AppsAPIGroupDSL.class);
            when(client.apps()).thenReturn(apps);
            MixedOperation<Deployment, DeploymentList, RollableScalableResource<Deployment>> deployments =
                    mock(MixedOperation.class);
            when(apps.deployments()).thenReturn(deployments);
            wireAnyNamespace(deployments, new DeploymentListBuilder().withItems(objects.deployments()).build());

            MixedOperation<StatefulSet, StatefulSetList, RollableScalableResource<StatefulSet>> statefulSets =
                    mock(MixedOperation.class);
            when(apps.statefulSets()).thenReturn(statefulSets);
            wireAnyNamespace(statefulSets, new StatefulSetListBuilder().withItems(objects.statefulSets()).build());

            MixedOperation<DaemonSet, DaemonSetList, Resource<DaemonSet>> daemonSets = mock(MixedOperation.class);
            when(apps.daemonSets()).thenReturn(daemonSets);
            wireAnyNamespace(daemonSets, new DaemonSetListBuilder().withItems(objects.daemonSets()).build());

            MixedOperation<Service, ServiceList, ServiceResource<Service>> services = mock(MixedOperation.class);
            when(client.services()).thenReturn(services);
            wireAnyNamespace(services, new ServiceListBuilder().withItems(objects.services()).build());

            MixedOperation<ResourceQuota, ResourceQuotaList, Resource<ResourceQuota>> quotas = mock(MixedOperation.class);
            when(client.resourceQuotas()).thenReturn(quotas);
            wireAnyNamespace(quotas, new ResourceQuotaListBuilder().withItems(objects.quotas()).build());

            MixedOperation<PersistentVolumeClaim, PersistentVolumeClaimList, Resource<PersistentVolumeClaim>> pvcs =
                    mock(MixedOperation.class);
            when(client.persistentVolumeClaims()).thenReturn(pvcs);
            wireAnyNamespace(pvcs, new PersistentVolumeClaimListBuilder().withItems(objects.pvcs()).build());

            NonNamespaceOperation<PersistentVolume, PersistentVolumeList, Resource<PersistentVolume>> pvs =
                    mock(NonNamespaceOperation.class);
            when(client.persistentVolumes()).thenReturn(pvs);
            wireClusterScoped(pvs, new PersistentVolumeListBuilder().withItems(objects.pvs()).build());

            NonNamespaceOperation<Node, NodeList, Resource<Node>> nodes = mock(NonNamespaceOperation.class);
            when(client.nodes()).thenReturn(nodes);
            wireClusterScoped(nodes, new NodeListBuilder().withItems(objects.nodes()).build());
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        private <T extends HasMetadata, L extends KubernetesResourceList<T>> void wireAnyNamespace(
                MixedOperation<T, L, ?> op, L list) {
            AnyNamespaceOperation any = mock(AnyNamespaceOperation.class);
            when(((MixedOperation) op).inAnyNamespace()).thenReturn(any);
            when(any.list()).thenReturn(list);
            namespacedChains.add(op);
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        private <T extends HasMetadata, L extends KubernetesResourceList<T>> void wireClusterScoped(
                NonNamespaceOperation<T, L, ?> op, L list) {
            when(((NonNamespaceOperation) op).list()).thenReturn(list);
            clusterChains.add(op);
        }

        Fixture wire() {
            // 清掉 stubbing 期在 mock 上留下的调用记录，之后 invoke 到的次数才是 aggregate() 的真实次数
            // （不依赖「Mockito 会把 stubbing 调用从 verify 里摘掉」这一细节）
            namespacedChains.forEach(c -> clearInvocations(c));
            clusterChains.forEach(c -> clearInvocations(c));
            this.ops = new ClusterAggregationOperations(client);
            return this;
        }

        ClusterAggregationOperations ops() {
            return ops;
        }

        /** 固定次 list 的断言：每条链恰好一次 list，且从未 inNamespace(具体 ns)。 */
        void assertFixedListCount() {
            assertThat(namespacedChains).hasSize(7); // pods + 3 workload + services + quotas + PVC
            for (MixedOperation<?, ?, ?> chain : namespacedChains) {
                verify(chain, times(1)).inAnyNamespace();
                verify(chain, never()).inNamespace(anyString());
            }
            assertThat(clusterChains).hasSize(2); // PV + Node（cluster-scoped，无 ns 维度）
            for (NonNamespaceOperation<?, ?, ?> chain : clusterChains) {
                verify(chain, times(1)).list();
            }
        }
    }
}
