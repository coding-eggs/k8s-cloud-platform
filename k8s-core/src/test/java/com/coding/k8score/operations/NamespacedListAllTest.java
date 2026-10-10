package com.coding.k8score.operations;

import com.coding.common.models.k8s.dto.GatewayDTO;
import com.coding.common.models.k8s.dto.GrpcRouteDTO;
import com.coding.common.models.k8s.dto.HttpRouteDTO;
import com.coding.common.models.k8s.dto.TcpRouteDTO;
import com.coding.common.models.k8s.dto.TlsRouteDTO;
import com.coding.common.models.k8s.dto.UdpRouteDTO;
import com.coding.common.models.k8s.dto.HpaDTO;
import com.coding.k8score.converter.impl.gateway.GatewayConverter;
import com.coding.k8score.converter.impl.gateway.GrpcRouteConverter;
import com.coding.k8score.converter.impl.gateway.HttpRouteConverter;
import com.coding.k8score.converter.impl.gateway.TcpRouteConverter;
import com.coding.k8score.converter.impl.gateway.TlsRouteConverter;
import com.coding.k8score.converter.impl.gateway.UdpRouteConverter;
import com.coding.k8score.converter.impl.autoscaling.HpaV1Converter;
import com.coding.k8score.converter.impl.autoscaling.HpaV2Converter;
import com.coding.k8score.operations.gateway.GatewayOperations;
import com.coding.k8score.operations.gateway.GrpcRouteOperations;
import com.coding.k8score.operations.gateway.HttpRouteOperations;
import com.coding.k8score.operations.gateway.TcpRouteOperations;
import com.coding.k8score.operations.gateway.TlsRouteOperations;
import com.coding.k8score.operations.gateway.UdpRouteOperations;
import com.coding.k8score.operations.autoscaling.HpaV1Operations;
import com.coding.k8score.operations.autoscaling.HpaV2Operations;
import io.fabric8.kubernetes.api.model.autoscaling.v1.HorizontalPodAutoscaler;
import io.fabric8.kubernetes.api.model.autoscaling.v1.HorizontalPodAutoscalerBuilder;
import io.fabric8.kubernetes.api.model.autoscaling.v1.HorizontalPodAutoscalerList;
import io.fabric8.kubernetes.api.model.autoscaling.v1.HorizontalPodAutoscalerListBuilder;
import com.coding.common.models.k8s.dto.LimitRangeDTO;
import com.coding.common.models.k8s.dto.PersistentVolumeClaimDTO;
import com.coding.common.models.k8s.dto.PodMonitorDTO;
import com.coding.common.models.k8s.dto.ReplicaSetDTO;
import com.coding.common.models.k8s.dto.ResourceQuotaDTO;
import com.coding.common.models.k8s.dto.RoleBindingDTO;
import com.coding.common.models.k8s.dto.ServiceAccountDTO;
import com.coding.common.models.k8s.dto.ServiceDTO;
import com.coding.common.models.k8s.dto.ServiceMonitorDTO;
import com.coding.common.models.k8s.dto.WorkloadDTO;
import com.coding.k8score.converter.impl.core.CoreV1LimitRangeConverter;
import com.coding.k8score.converter.impl.core.CoreV1PvcConverter;
import com.coding.k8score.converter.impl.core.CoreV1ResourceQuotaConverter;
import com.coding.k8score.converter.impl.core.CoreV1ServiceConverter;
import com.coding.k8score.converter.impl.monitoring.PodMonitorConverter;
import com.coding.k8score.converter.impl.monitoring.ServiceMonitorConverter;
import com.coding.k8score.converter.impl.rbac.CoreV1ServiceAccountConverter;
import com.coding.k8score.converter.impl.rbac.RbacV1RoleBindingConverter;
import com.coding.k8score.converter.impl.workload.AppsV1ReplicaSetConverter;
import com.coding.k8score.converter.impl.workload.WorkloadConverter;
import com.coding.k8score.operations.core.CoreV1LimitRangeOperations;
import com.coding.k8score.operations.core.CoreV1PersistentVolumeClaimOperations;
import com.coding.k8score.operations.core.CoreV1ResourceQuotaOperations;
import com.coding.k8score.operations.core.CoreV1ServiceOperations;
import com.coding.k8score.operations.monitoring.PodMonitorOperations;
import com.coding.k8score.operations.monitoring.ServiceMonitorOperations;
import com.coding.k8score.operations.rbac.CoreV1ServiceAccountOperations;
import com.coding.k8score.operations.rbac.RbacV1RoleBindingOperations;
import com.coding.k8score.operations.workload.AppsV1ReplicaSetOperations;
import com.coding.k8score.operations.workload.WorkloadOperations;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.GenericKubernetesResourceList;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.KubernetesResourceList;
import io.fabric8.kubernetes.api.model.LimitRange;
import io.fabric8.kubernetes.api.model.LimitRangeBuilder;
import io.fabric8.kubernetes.api.model.LimitRangeList;
import io.fabric8.kubernetes.api.model.LimitRangeListBuilder;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaim;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaimBuilder;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaimList;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaimListBuilder;
import io.fabric8.kubernetes.api.model.ResourceQuota;
import io.fabric8.kubernetes.api.model.ResourceQuotaBuilder;
import io.fabric8.kubernetes.api.model.ResourceQuotaList;
import io.fabric8.kubernetes.api.model.ResourceQuotaListBuilder;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServiceAccount;
import io.fabric8.kubernetes.api.model.ServiceAccountBuilder;
import io.fabric8.kubernetes.api.model.ServiceAccountList;
import io.fabric8.kubernetes.api.model.ServiceAccountListBuilder;
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
import io.fabric8.kubernetes.api.model.apps.ReplicaSet;
import io.fabric8.kubernetes.api.model.apps.ReplicaSetBuilder;
import io.fabric8.kubernetes.api.model.apps.ReplicaSetListBuilder;
import io.fabric8.kubernetes.api.model.apps.StatefulSet;
import io.fabric8.kubernetes.api.model.apps.StatefulSetBuilder;
import io.fabric8.kubernetes.api.model.apps.StatefulSetList;
import io.fabric8.kubernetes.api.model.apps.StatefulSetListBuilder;
import io.fabric8.kubernetes.api.model.rbac.RoleBinding;
import io.fabric8.kubernetes.api.model.rbac.RoleBindingBuilder;
import io.fabric8.kubernetes.api.model.rbac.RoleBindingList;
import io.fabric8.kubernetes.api.model.rbac.RoleBindingListBuilder;
import io.fabric8.kubernetes.client.V1AutoscalingAPIGroupDSL;
import io.fabric8.kubernetes.client.V2AutoscalingAPIGroupDSL;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.AutoscalingAPIGroupDSL;
import io.fabric8.kubernetes.client.dsl.AppsAPIGroupDSL;
import io.fabric8.kubernetes.client.dsl.AnyNamespaceOperation;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.RbacAPIGroupDSL;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.fabric8.kubernetes.client.dsl.RollableScalableResource;
import io.fabric8.kubernetes.client.dsl.ServiceAccountResource;
import io.fabric8.kubernetes.client.dsl.ServiceResource;
import io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * listAll 覆盖测试：每个命名空间级资源一条用例。
 * <p>抓的是「复制粘贴错误」——接错 client / 忘了把 inNamespace(ns) 换成 inAnyNamespace()。
 * 故每条用例只 stub 本资源自己的 client 链：抄错 getter 会得到未 stub 的 null → NPE（响亮失败），
 * 而不是安静地返回空列表。
 * <p>Secret 有意没有 listAll（2026-10-10 决定，见 CoreV1SecretOperations 的注释），故此处没有它的用例。
 */
class NamespacedListAllTest {

    /**
     * 挂一条 {@code inAnyNamespace().list()} 链并返回 (mixed, any) 两个 mock 供 verify。
     * <p>不用 RETURNS_DEEP_STUBS：{@code inAnyNamespace()} 的返回类型带泛型变量，deep stubs 解不出来会返回 null
     * （MeshOperationsTest 踩过）。
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <T extends HasMetadata, L extends KubernetesResourceList<T>> MixedOperation<T, L, ?> stubAny(
            MixedOperation<T, L, ?> op, L list) {
        AnyNamespaceOperation any = mock(AnyNamespaceOperation.class);
        when(((MixedOperation) op).inAnyNamespace()).thenReturn(any);
        when(any.list(any(io.fabric8.kubernetes.api.model.ListOptions.class))).thenReturn(list);
        clearInvocations(op);
        return op;
    }

    /** 每个用例末尾都调它：一次 inAnyNamespace、从不 inNamespace(具体 ns)。 */
    private static void assertCrossNamespace(MixedOperation<?, ?, ?> op) {
        verify(op, times(1)).inAnyNamespace();
        verify(op, never()).inNamespace(anyString());
    }

    // ---------------------------------------------------------------- corev1 / apps

    @Test
    void service_listAll_lists_across_namespaces() {
        KubernetesClient client = mock(KubernetesClient.class);
        MixedOperation<Service, ServiceList, ServiceResource<Service>> services = mock(MixedOperation.class);
        when(client.services()).thenReturn(services);
        ServiceList list = new ServiceListBuilder().withItems(
                new ServiceBuilder().withNewMetadata().withNamespace("ns-a").withName("svc-a").endMetadata().build(),
                new ServiceBuilder().withNewMetadata().withNamespace("kube-system").withName("svc-b").endMetadata().build()
        ).build();
        stubAny(services, list);

        CoreV1ServiceOperations ops = new CoreV1ServiceOperations(client, new CoreV1ServiceConverter());
        List<ServiceDTO> out = ops.listAll(null, null);

        assertThat(out).extracting(ServiceDTO::getName).containsExactly("svc-a", "svc-b");
        assertThat(out).extracting(ServiceDTO::getNamespace).containsExactly("ns-a", "kube-system");
        assertCrossNamespace(services);
    }

    @Test
    @SuppressWarnings("unchecked")
    void replicaSet_listAll_lists_across_namespaces() {
        KubernetesClient client = mock(KubernetesClient.class);
        MixedOperation<ReplicaSet, KubernetesResourceList<ReplicaSet>, Resource<ReplicaSet>> rs =
                mock(MixedOperation.class);
        when(client.resources(ReplicaSet.class)).thenReturn(rs);
        stubAny(rs, new ReplicaSetListBuilder().withItems(
                new ReplicaSetBuilder().withNewMetadata().withNamespace("ns-a").withName("rs-a").endMetadata().build(),
                new ReplicaSetBuilder().withNewMetadata().withNamespace("kube-system").withName("rs-b").endMetadata().build()
        ).build());

        AppsV1ReplicaSetOperations ops = new AppsV1ReplicaSetOperations(client, new AppsV1ReplicaSetConverter());
        List<ReplicaSetDTO> out = ops.listAll(null, null);

        assertThat(out).extracting(ReplicaSetDTO::getName).containsExactly("rs-a", "rs-b");
        assertThat(out).extracting(ReplicaSetDTO::getNamespace).containsExactly("ns-a", "kube-system");
        assertCrossNamespace(rs);
    }

    @Test
    void pvc_listAll_lists_across_namespaces() {
        KubernetesClient client = mock(KubernetesClient.class);
        MixedOperation<PersistentVolumeClaim, PersistentVolumeClaimList, Resource<PersistentVolumeClaim>> pvcs =
                mock(MixedOperation.class);
        when(client.persistentVolumeClaims()).thenReturn(pvcs);
        stubAny(pvcs, new PersistentVolumeClaimListBuilder().withItems(
                new PersistentVolumeClaimBuilder().withNewMetadata().withNamespace("ns-a").withName("pvc-a").endMetadata().build(),
                new PersistentVolumeClaimBuilder().withNewMetadata().withNamespace("kube-system").withName("pvc-b").endMetadata().build()
        ).build());

        CoreV1PersistentVolumeClaimOperations ops =
                new CoreV1PersistentVolumeClaimOperations(client, new CoreV1PvcConverter());
        List<PersistentVolumeClaimDTO> out = ops.listAll(null, null);

        assertThat(out).extracting(PersistentVolumeClaimDTO::getName).containsExactly("pvc-a", "pvc-b");
        assertThat(out).extracting(PersistentVolumeClaimDTO::getNamespace).containsExactly("ns-a", "kube-system");
        assertCrossNamespace(pvcs);
    }

    @Test
    void resourceQuota_listAll_lists_across_namespaces() {
        KubernetesClient client = mock(KubernetesClient.class);
        MixedOperation<ResourceQuota, ResourceQuotaList, Resource<ResourceQuota>> quotas = mock(MixedOperation.class);
        when(client.resourceQuotas()).thenReturn(quotas);
        stubAny(quotas, new ResourceQuotaListBuilder().withItems(
                new ResourceQuotaBuilder().withNewMetadata().withNamespace("ns-a").withName("rq-a").endMetadata().build(),
                new ResourceQuotaBuilder().withNewMetadata().withNamespace("kube-system").withName("rq-b").endMetadata().build()
        ).build());

        CoreV1ResourceQuotaOperations ops =
                new CoreV1ResourceQuotaOperations(client, new CoreV1ResourceQuotaConverter());
        List<ResourceQuotaDTO> out = ops.listAll(null, null);

        assertThat(out).extracting(ResourceQuotaDTO::getName).containsExactly("rq-a", "rq-b");
        assertThat(out).extracting(ResourceQuotaDTO::getNamespace).containsExactly("ns-a", "kube-system");
        assertCrossNamespace(quotas);
    }

    @Test
    void limitRange_listAll_lists_across_namespaces() {
        KubernetesClient client = mock(KubernetesClient.class);
        MixedOperation<LimitRange, LimitRangeList, Resource<LimitRange>> limits = mock(MixedOperation.class);
        when(client.limitRanges()).thenReturn(limits);
        stubAny(limits, new LimitRangeListBuilder().withItems(
                new LimitRangeBuilder().withNewMetadata().withNamespace("ns-a").withName("lr-a").endMetadata().build(),
                new LimitRangeBuilder().withNewMetadata().withNamespace("kube-system").withName("lr-b").endMetadata().build()
        ).build());

        CoreV1LimitRangeOperations ops = new CoreV1LimitRangeOperations(client, new CoreV1LimitRangeConverter());
        List<LimitRangeDTO> out = ops.listAll(null, null);

        assertThat(out).extracting(LimitRangeDTO::getName).containsExactly("lr-a", "lr-b");
        assertThat(out).extracting(LimitRangeDTO::getNamespace).containsExactly("ns-a", "kube-system");
        assertCrossNamespace(limits);
    }

    // ---------------------------------------------------------------- rbac

    @Test
    void serviceAccount_listAll_lists_across_namespaces() {
        KubernetesClient client = mock(KubernetesClient.class);
        MixedOperation<ServiceAccount, ServiceAccountList, ServiceAccountResource> sas = mock(MixedOperation.class);
        when(client.serviceAccounts()).thenReturn(sas);
        stubAny(sas, new ServiceAccountListBuilder().withItems(
                new ServiceAccountBuilder().withNewMetadata().withNamespace("ns-a").withName("sa-a").endMetadata().build(),
                new ServiceAccountBuilder().withNewMetadata().withNamespace("kube-system").withName("sa-b").endMetadata().build()
        ).build());

        CoreV1ServiceAccountOperations ops = new CoreV1ServiceAccountOperations(client, new CoreV1ServiceAccountConverter());
        List<ServiceAccountDTO> out = ops.listAll(null, null);

        assertThat(out).extracting(ServiceAccountDTO::getName).containsExactly("sa-a", "sa-b");
        assertThat(out).extracting(ServiceAccountDTO::getNamespace).containsExactly("ns-a", "kube-system");
        assertCrossNamespace(sas);
    }

    @Test
    void roleBinding_listAll_lists_across_namespaces() {
        KubernetesClient client = mock(KubernetesClient.class);
        RbacAPIGroupDSL rbac = mock(RbacAPIGroupDSL.class);
        MixedOperation<RoleBinding, RoleBindingList, Resource<RoleBinding>> roleBindings = mock(MixedOperation.class);
        when(client.rbac()).thenReturn(rbac);
        when(rbac.roleBindings()).thenReturn(roleBindings);
        stubAny(roleBindings, new RoleBindingListBuilder().withItems(
                new RoleBindingBuilder().withNewMetadata().withNamespace("ns-a").withName("rb-a").endMetadata().build(),
                new RoleBindingBuilder().withNewMetadata().withNamespace("kube-system").withName("rb-b").endMetadata().build()
        ).build());

        RbacV1RoleBindingOperations ops = new RbacV1RoleBindingOperations(client, new RbacV1RoleBindingConverter());
        List<RoleBindingDTO> out = ops.listAll(null, null);

        assertThat(out).extracting(RoleBindingDTO::getName).containsExactly("rb-a", "rb-b");
        assertThat(out).extracting(RoleBindingDTO::getNamespace).containsExactly("ns-a", "kube-system");
        assertCrossNamespace(roleBindings);
    }

    // ---------------------------------------------------------------- CRD 形态（monitoring.coreos.com）

    /** CRD 形态的对象（converter 从 metadata 取 namespace/name） */
    private static GenericKubernetesResource generic(String ns, String name) {
        GenericKubernetesResource r = new GenericKubernetesResource();
        r.setMetadata(new ObjectMetaBuilder().withNamespace(ns).withName(name).build());
        return r;
    }

    /** GenericKubernetesResourceList 没有 builder（fabric8 未生成），只能造出来再 setItems。 */
    private static GenericKubernetesResourceList genericList(GenericKubernetesResource... items) {
        GenericKubernetesResourceList list = new GenericKubernetesResourceList();
        list.setItems(List.of(items));
        return list;
    }

    @Test
    @SuppressWarnings("unchecked")
    void serviceMonitor_listAll_lists_across_namespaces() {
        KubernetesClient client = mock(KubernetesClient.class);
        MixedOperation<GenericKubernetesResource, GenericKubernetesResourceList,
                Resource<GenericKubernetesResource>> sms = mock(MixedOperation.class);
        when(client.genericKubernetesResources(any(ResourceDefinitionContext.class))).thenReturn(sms);
        GenericKubernetesResource a = generic("ns-a", "sm-a");
        GenericKubernetesResource b = generic("kube-system", "sm-b");
        stubAny(sms, genericList(a, b));

        ServiceMonitorOperations ops = new ServiceMonitorOperations(client, new ServiceMonitorConverter());
        List<ServiceMonitorDTO> out = ops.listAll(null, null);

        assertThat(out).extracting(ServiceMonitorDTO::getName).containsExactly("sm-a", "sm-b");
        assertThat(out).extracting(ServiceMonitorDTO::getNamespace).containsExactly("ns-a", "kube-system");
        assertCrossNamespace(sms);
    }

    @Test
    @SuppressWarnings("unchecked")
    void podMonitor_listAll_lists_across_namespaces() {
        KubernetesClient client = mock(KubernetesClient.class);
        MixedOperation<GenericKubernetesResource, GenericKubernetesResourceList,
                Resource<GenericKubernetesResource>> pms = mock(MixedOperation.class);
        when(client.genericKubernetesResources(any(ResourceDefinitionContext.class))).thenReturn(pms);
        GenericKubernetesResource a = generic("ns-a", "pm-a");
        GenericKubernetesResource b = generic("kube-system", "pm-b");
        stubAny(pms, genericList(a, b));

        PodMonitorOperations ops = new PodMonitorOperations(client, new PodMonitorConverter());
        List<PodMonitorDTO> out = ops.listAll(null, null);

        assertThat(out).extracting(PodMonitorDTO::getName).containsExactly("pm-a", "pm-b");
        assertThat(out).extracting(PodMonitorDTO::getNamespace).containsExactly("ns-a", "kube-system");
        assertCrossNamespace(pms);
    }

    // ---------------------------------------------------------------- autoscaling（v1 / v2 各一份实现）

    @Test
    void hpaV1_listAll_lists_across_namespaces() {
        KubernetesClient client = mock(KubernetesClient.class);
        AutoscalingAPIGroupDSL autoscaling = mock(AutoscalingAPIGroupDSL.class);
        V1AutoscalingAPIGroupDSL v1 = mock(V1AutoscalingAPIGroupDSL.class);
        MixedOperation<HorizontalPodAutoscaler, HorizontalPodAutoscalerList,
                Resource<HorizontalPodAutoscaler>> hpas = mock(MixedOperation.class);
        when(client.autoscaling()).thenReturn(autoscaling);
        when(autoscaling.v1()).thenReturn(v1);
        when(v1.horizontalPodAutoscalers()).thenReturn(hpas);
        stubAny(hpas, new HorizontalPodAutoscalerListBuilder().withItems(
                new HorizontalPodAutoscalerBuilder()
                        .withNewMetadata().withNamespace("ns-a").withName("hpa-a").endMetadata().build(),
                new HorizontalPodAutoscalerBuilder()
                        .withNewMetadata().withNamespace("kube-system").withName("hpa-b").endMetadata().build()
        ).build());

        HpaV1Operations ops = new HpaV1Operations(client, new HpaV1Converter());
        List<HpaDTO> out = ops.listAll(null, null);

        assertThat(out).extracting(HpaDTO::getName).containsExactly("hpa-a", "hpa-b");
        assertThat(out).extracting(HpaDTO::getNamespace).containsExactly("ns-a", "kube-system");
        assertCrossNamespace(hpas);
    }

    /** v2 的同名模型类与 v1 重名，故本用例用全限定名（只 import v1 的）。 */
    @Test
    @SuppressWarnings("unchecked")
    void hpaV2_listAll_lists_across_namespaces() {
        KubernetesClient client = mock(KubernetesClient.class);
        AutoscalingAPIGroupDSL autoscaling = mock(AutoscalingAPIGroupDSL.class);
        V2AutoscalingAPIGroupDSL v2 = mock(V2AutoscalingAPIGroupDSL.class);
        MixedOperation<io.fabric8.kubernetes.api.model.autoscaling.v2.HorizontalPodAutoscaler,
                io.fabric8.kubernetes.api.model.autoscaling.v2.HorizontalPodAutoscalerList,
                Resource<io.fabric8.kubernetes.api.model.autoscaling.v2.HorizontalPodAutoscaler>> hpas =
                mock(MixedOperation.class);
        when(client.autoscaling()).thenReturn(autoscaling);
        when(autoscaling.v2()).thenReturn(v2);
        when(v2.horizontalPodAutoscalers()).thenReturn(hpas);
        stubAny(hpas, new io.fabric8.kubernetes.api.model.autoscaling.v2.HorizontalPodAutoscalerListBuilder().withItems(
                new io.fabric8.kubernetes.api.model.autoscaling.v2.HorizontalPodAutoscalerBuilder()
                        .withNewMetadata().withNamespace("ns-a").withName("hpa-v2-a").endMetadata().build(),
                new io.fabric8.kubernetes.api.model.autoscaling.v2.HorizontalPodAutoscalerBuilder()
                        .withNewMetadata().withNamespace("kube-system").withName("hpa-v2-b").endMetadata().build()
        ).build());

        HpaV2Operations ops = new HpaV2Operations(client, new HpaV2Converter());
        List<HpaDTO> out = ops.listAll(null, null);

        assertThat(out).extracting(HpaDTO::getName).containsExactly("hpa-v2-a", "hpa-v2-b");
        assertThat(out).extracting(HpaDTO::getNamespace).containsExactly("ns-a", "kube-system");
        assertCrossNamespace(hpas);
    }

    // ---------------------------------------------------------------- gateway.networking.k8s.io（7 类，CRD 形态）

    /**
     * CRD 形态的 ops 共用同一条 client 链（{@code client.genericKubernetesResources(CRD)}）。只造 mock，
     * 不替用例做 stub —— 每条用例仍只 stub 本资源自己的链（抄错 getter → 拿到未 stub 的 null → NPE）。
     */
    @SuppressWarnings("unchecked")
    private static MixedOperation<GenericKubernetesResource, GenericKubernetesResourceList,
            Resource<GenericKubernetesResource>> crdOperation() {
        return mock(MixedOperation.class);
    }

    /** 把 {@code client.genericKubernetesResources(CRD)} 接到给定 mock（CRD 常量是各类私有的，故按类型放宽匹配）。 */
    private static void stubClientCrd(KubernetesClient client,
                                      MixedOperation<GenericKubernetesResource, GenericKubernetesResourceList,
                                              Resource<GenericKubernetesResource>> op) {
        when(client.genericKubernetesResources(any(ResourceDefinitionContext.class))).thenReturn(op);
    }

    @Test
    void gateway_listAll_lists_across_namespaces() {
        KubernetesClient client = mock(KubernetesClient.class);
        MixedOperation<GenericKubernetesResource, GenericKubernetesResourceList,
                Resource<GenericKubernetesResource>> gws = crdOperation();
        stubClientCrd(client, gws);
        stubAny(gws, genericList(generic("ns-a", "gw-a"), generic("kube-system", "gw-b")));

        GatewayOperations ops = new GatewayOperations(client, new GatewayConverter());
        List<GatewayDTO> out = ops.listAll(null, null);

        assertThat(out).extracting(GatewayDTO::getName).containsExactly("gw-a", "gw-b");
        assertThat(out).extracting(GatewayDTO::getNamespace).containsExactly("ns-a", "kube-system");
        assertCrossNamespace(gws);
    }

    @Test
    void httpRoute_listAll_lists_across_namespaces() {
        KubernetesClient client = mock(KubernetesClient.class);
        MixedOperation<GenericKubernetesResource, GenericKubernetesResourceList,
                Resource<GenericKubernetesResource>> routes = crdOperation();
        stubClientCrd(client, routes);
        stubAny(routes, genericList(generic("ns-a", "hr-a"), generic("kube-system", "hr-b")));

        HttpRouteOperations ops = new HttpRouteOperations(client, new HttpRouteConverter());
        List<HttpRouteDTO> out = ops.listAll(null, null);

        assertThat(out).extracting(HttpRouteDTO::getName).containsExactly("hr-a", "hr-b");
        assertThat(out).extracting(HttpRouteDTO::getNamespace).containsExactly("ns-a", "kube-system");
        assertCrossNamespace(routes);
    }

    @Test
    void grpcRoute_listAll_lists_across_namespaces() {
        KubernetesClient client = mock(KubernetesClient.class);
        MixedOperation<GenericKubernetesResource, GenericKubernetesResourceList,
                Resource<GenericKubernetesResource>> routes = crdOperation();
        stubClientCrd(client, routes);
        stubAny(routes, genericList(generic("ns-a", "gr-a"), generic("kube-system", "gr-b")));

        GrpcRouteOperations ops = new GrpcRouteOperations(client, new GrpcRouteConverter());
        List<GrpcRouteDTO> out = ops.listAll(null, null);

        assertThat(out).extracting(GrpcRouteDTO::getName).containsExactly("gr-a", "gr-b");
        assertThat(out).extracting(GrpcRouteDTO::getNamespace).containsExactly("ns-a", "kube-system");
        assertCrossNamespace(routes);
    }

    // L4 三族：converter 构造器带版本参数（本平台集群跑 Gateway API v1.6.x → v1），照工厂的传法构造。

    @Test
    void tcpRoute_listAll_lists_across_namespaces() {
        KubernetesClient client = mock(KubernetesClient.class);
        MixedOperation<GenericKubernetesResource, GenericKubernetesResourceList,
                Resource<GenericKubernetesResource>> routes = crdOperation();
        stubClientCrd(client, routes);
        stubAny(routes, genericList(generic("ns-a", "tr-a"), generic("kube-system", "tr-b")));

        TcpRouteOperations ops = new TcpRouteOperations(client, new TcpRouteConverter("v1"));
        List<TcpRouteDTO> out = ops.listAll(null, null);

        assertThat(out).extracting(TcpRouteDTO::getName).containsExactly("tr-a", "tr-b");
        assertThat(out).extracting(TcpRouteDTO::getNamespace).containsExactly("ns-a", "kube-system");
        assertCrossNamespace(routes);
    }

    @Test
    void tlsRoute_listAll_lists_across_namespaces() {
        KubernetesClient client = mock(KubernetesClient.class);
        MixedOperation<GenericKubernetesResource, GenericKubernetesResourceList,
                Resource<GenericKubernetesResource>> routes = crdOperation();
        stubClientCrd(client, routes);
        stubAny(routes, genericList(generic("ns-a", "tsr-a"), generic("kube-system", "tsr-b")));

        TlsRouteOperations ops = new TlsRouteOperations(client, new TlsRouteConverter("v1"));
        List<TlsRouteDTO> out = ops.listAll(null, null);

        assertThat(out).extracting(TlsRouteDTO::getName).containsExactly("tsr-a", "tsr-b");
        assertThat(out).extracting(TlsRouteDTO::getNamespace).containsExactly("ns-a", "kube-system");
        assertCrossNamespace(routes);
    }

    @Test
    void udpRoute_listAll_lists_across_namespaces() {
        KubernetesClient client = mock(KubernetesClient.class);
        MixedOperation<GenericKubernetesResource, GenericKubernetesResourceList,
                Resource<GenericKubernetesResource>> routes = crdOperation();
        stubClientCrd(client, routes);
        stubAny(routes, genericList(generic("ns-a", "ur-a"), generic("kube-system", "ur-b")));

        UdpRouteOperations ops = new UdpRouteOperations(client, new UdpRouteConverter("v1"));
        List<UdpRouteDTO> out = ops.listAll(null, null);

        assertThat(out).extracting(UdpRouteDTO::getName).containsExactly("ur-a", "ur-b");
        assertThat(out).extracting(UdpRouteDTO::getNamespace).containsExactly("ns-a", "kube-system");
        assertCrossNamespace(routes);
    }

    // ---------------------------------------------------------------- workload（Deployment / StatefulSet / DaemonSet 合并成一个 List）

    @Test
    void workload_listAll_lists_all_three_families_across_namespaces() {
        KubernetesClient client = mock(KubernetesClient.class);
        AppsAPIGroupDSL apps = mock(AppsAPIGroupDSL.class);
        MixedOperation<Deployment, DeploymentList, RollableScalableResource<Deployment>> deployments =
                mock(MixedOperation.class);
        MixedOperation<StatefulSet, StatefulSetList, RollableScalableResource<StatefulSet>> statefulSets =
                mock(MixedOperation.class);
        MixedOperation<DaemonSet, DaemonSetList, Resource<DaemonSet>> daemonSets = mock(MixedOperation.class);
        when(client.apps()).thenReturn(apps);
        when(apps.deployments()).thenReturn(deployments);
        when(apps.statefulSets()).thenReturn(statefulSets);
        when(apps.daemonSets()).thenReturn(daemonSets);
        stubAny(deployments, new DeploymentListBuilder().withItems(
                new DeploymentBuilder().withNewMetadata().withNamespace("ns-a").withName("dep-a").endMetadata().build()
        ).build());
        stubAny(statefulSets, new StatefulSetListBuilder().withItems(
                new StatefulSetBuilder().withNewMetadata().withNamespace("kube-system").withName("sts-b").endMetadata().build()
        ).build());
        stubAny(daemonSets, new DaemonSetListBuilder().withItems(
                new DaemonSetBuilder().withNewMetadata().withNamespace("ns-c").withName("ds-c").endMetadata().build()
        ).build());

        WorkloadOperations ops = new WorkloadOperations(client, new WorkloadConverter());
        List<WorkloadDTO> out = ops.listAll(null, null);

        // 三族一次列全、合并成一个 List，kind 各归各位（get/delete 按 kind 分发，错了就串资源）
        assertThat(out).extracting(WorkloadDTO::getName).containsExactly("dep-a", "sts-b", "ds-c");
        assertThat(out).extracting(WorkloadDTO::getNamespace).containsExactly("ns-a", "kube-system", "ns-c");
        assertThat(out).extracting(WorkloadDTO::getKind)
                .containsExactly("deployment", "statefulset", "daemonset");
        assertCrossNamespace(deployments);
        assertCrossNamespace(statefulSets);
        assertCrossNamespace(daemonSets);
    }

}
