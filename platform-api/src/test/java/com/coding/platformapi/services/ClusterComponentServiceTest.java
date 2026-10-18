package com.coding.platformapi.services;

import com.coding.common.models.k8s.dto.WorkloadProbeResultDTO;
import com.coding.common.models.k8s.dto.WorkloadProbeTargetDTO;
import com.coding.data.mapper.system.PlatformComponentOperationMapper;
import com.coding.data.models.system.PlatformComponentOperation;
import com.coding.platformapi.cache.RedisJsonCache;
import com.coding.platformapi.components.ClusterComponentCatalog;
import com.coding.platformapi.components.ComponentProbe;
import com.coding.platformapi.k8s.K8sComponentClient;
import com.coding.platformapi.models.ComponentFlagDTO;
import com.coding.platformapi.models.ComponentStatusDTO;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Objects;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 四态合成的口径 + 两投影 + 缓存。
 *
 * <p>最要紧的一条：<b>探测失败是 UNKNOWN，不是 NOT_INSTALLED</b>。把"不知道"说成"没装"
 * 是本仓修过一次的坑（{@code MeshStatusDTO}「未探测」）。
 */
class ClusterComponentServiceTest {

    /** 默认能力组：非空（否则全 UNKNOWN），但**不含** metrics.k8s.io / gateway group —— 让"没命中"真的是没命中 */
    private static final Map<String, List<String>> DEFAULT_GROUPS = Map.of("networking.istio.io", List.of("v1"));

    @Test
    void aMissingComponentIsNotInstalledButStillSaysWhereWeLooked() {
        // Review Focus #4：候选位置之外**没去看**，所以措辞是"在平台已知的位置没找到"，
        // 而不是断言"这个集群没装"
        ClusterComponentService svc = svc(List.of(probe("kube-system", false, null, null, null)));

        ComponentStatusDTO s = find(svc.status("c1"), "metrics-server");

        assertThat(s.getState()).isEqualTo("NOT_INSTALLED");
        assertThat(s.getDetail()).contains("没有找到");
    }

    @Test
    void zeroDesiredReplicasIsNotHealthy() {
        // Review Focus #2：被 scale 到 0 的 Deployment，0/0 判成"健康"是错的 —— 它根本没在跑
        ClusterComponentService svc = svc(List.of(probe("kube-system", true, 0, 0, "v0.7.2")));

        assertThat(find(svc.status("c1"), "metrics-server").getState()).isEqualTo("INSTALLED_UNHEALTHY");
    }

    @Test
    void readyBelowDesiredIsUnhealthyAndTheDetailSaysByHowMuch() {
        ClusterComponentService svc = svc(List.of(probe("istio-system", true, 1, 2, "1.30.3")));

        ComponentStatusDTO s = find(svc.status("c1"), "istio");

        assertThat(s.getState()).isEqualTo("INSTALLED_UNHEALTHY");
        assertThat(s.getDetail()).contains("1/2");
    }

    @Test
    void aProbeFailureIsUnknownNotMissing() {
        // ⭐ Review Focus #1：集群断开时页面必须说"探测不到"，而不是"没装"
        ClusterComponentService svc = svc(List.of(probe("kube-system", false, null, null, null, "连接被拒")));

        assertThat(find(svc.status("c1"), "metrics-server").getState()).isEqualTo("UNKNOWN");
    }

    @Test
    void aWholeProbeThatThrowsLeavesEveryProbedComponentUnknown() {
        // spec §11：集群断开时页面照常打开。**只要有判据失败**就 UNKNOWN（保守，不猜）——
        // gateway-api 也有 CRD 判据，CRD 读不到就是 UNKNOWN；它"装没装"由 MeshService 用
        // capability 快照回退兜住（那边有专门用例）。
        ClusterComponentService svc = svcThrowingWithGroups(new RuntimeException("k8s-server 不可达"),
                Map.of("gateway.networking.k8s.io", List.of("v1")));

        List<ComponentStatusDTO> all = svc.status("c1");

        assertThat(find(all, "metrics-server").getState()).isEqualTo("UNKNOWN");
        assertThat(find(all, "istio").getState()).isEqualTo("UNKNOWN");
        assertThat(find(all, "gateway-api").getState()).isEqualTo("UNKNOWN");
    }

    @Test
    void aCrdOnlyComponentIsHealthyWhenTheCrdIsFoundAndCarriesItsVersion() {
        // ⭐ gateway-api 是纯 CRD：没有副本语义，找到即"已装"，版本来自 CRD 的 bundle-version 标签
        ClusterComponentService svc = svcWithGroups(
                List.of(probeCrd("gateways.gateway.networking.k8s.io", "v1.6.2")),
                Map.of("gateway.networking.k8s.io", List.of("v1")));

        ComponentStatusDTO s = find(svc.status("c1"), "gateway-api");

        assertThat(s.getState()).isEqualTo("INSTALLED_HEALTHY");
        assertThat(s.getVersion()).isEqualTo("v1.6.2");
        assertThat(s.getReady()).isNull();
        assertThat(s.getDesired()).isNull();
    }

    @Test
    void theCacheExpiringCausesAFreshProbe() {
        // spec §8：TTL 过期后重探（store 清空 = 过期）
        K8sComponentClient client = mock(K8sComponentClient.class);
        when(client.probe(anyString(), any())).thenReturn(aligned(List.of()));
        Map<String, String> store = new HashMap<>();
        ClusterComponentService svc = new ClusterComponentService(
                new ClusterComponentCatalog(), capability(DEFAULT_GROUPS), client, cacheOver(store),
                profileService(), new com.coding.platformapi.components.ComponentRecipeCatalog(), operationMapper());

        svc.status("c1");
        store.clear(); // 45s 到期
        svc.status("c1");

        verify(client, times(2)).probe(eq("c1"), any());
    }

    @Test
    void theCrdVersionLabelFromTheCatalogReachesTheAdapter() {
        // catalog 里 gateway-api 指定读 gateway.networking.k8s.io/bundle-version —— 这个配置必须传到
        // k8s-core，否则只能靠"任意 *bundle-version"兜底（显式配置形同虚设）
        K8sComponentClient client = mock(K8sComponentClient.class);
        when(client.probe(anyString(), any())).thenReturn(aligned(List.of()));
        ClusterComponentService svc = new ClusterComponentService(
                new ClusterComponentCatalog(), capability(DEFAULT_GROUPS), client, cache(),
                profileService(), new com.coding.platformapi.components.ComponentRecipeCatalog(), operationMapper());

        svc.status("c1");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<WorkloadProbeTargetDTO>> captor = ArgumentCaptor.forClass(List.class);
        verify(client).probe(eq("c1"), captor.capture());
        WorkloadProbeTargetDTO crd = captor.getValue().stream()
                .filter(t -> "crd".equals(t.getKind())).findFirst().orElseThrow();
        assertThat(crd.getVersionLabel()).isEqualTo("gateway.networking.k8s.io/bundle-version");
        assertThat(crd.getName()).isEqualTo("gateways.gateway.networking.k8s.io");
    }

    @Test
    void theDetailFromTheAdapterIsCarriedIntoTheStatus() {
        // Review Focus #5：多命中时"命中 2 个（a, b），取 a"必须原样到前端 ——
        // 否则健康组件（升级残留/新旧两版并存通常都是健康的）的详情永远不会被看到。
        ClusterComponentService svc = svc(List.of(probeWithDetail("istio-system", "命中 2 个（istiod-a, istiod-b），取 istiod-a")));

        assertThat(find(svc.status("c1"), "istio").getDetail())
                .isEqualTo("命中 2 个（istiod-a, istiod-b），取 istiod-a");
    }

    @Test
    void aStaleApiGroupDoesNotMakeACrdOnlyComponentLookInstalled() {
        // 真实事故（2026-10-18 手动验证）：Helm 卸载不删 CRD，capability 快照也不会自己刷新 ——
        // group 还在**不能**当"已装"。有活判据时它是唯一权威，版本列空着才是诚实的。
        ClusterComponentService svc = svcWithGroups(List.of(), Map.of("gateway.networking.k8s.io", List.of("v1")));

        ComponentStatusDTO s = find(svc.status("c1"), "gateway-api");

        assertThat(s.getState()).isEqualTo("NOT_INSTALLED");
        assertThat(s.getVersion()).isNull();
        assertThat(s.getDetail()).contains("没有找到 CRD");
    }

    @Test
    void anUninstalledWorkloadIsNotInstalledEvenWhenTheApiGroupRemains() {
        // 2026-10-18 手动验证：`helm uninstall` 旧 Prometheus 后 monitor 下已无 Deployment，
        // 但 monitoring.coreos.com 的 CRD（10 个）与 capability 快照都还在 ——
        // 以前会显示「已安装且健康」，让用户以为卸载没生效。
        ClusterComponentService svc = svcWithGroups(
                List.of(probe("monitor", false, null, null, null)),
                Map.of("monitoring.coreos.com", List.of("v1")));

        ComponentStatusDTO s = find(svc.status("c1"), "prometheus-operator");

        assertThat(s.getState()).isEqualTo("NOT_INSTALLED");
        // 详情要点出"group 还在是因为 CRD 残留"，否则排障时看不出为什么它以前显示已安装
        assertThat(s.getDetail()).contains("monitoring.coreos.com").contains("CRD");
    }

    @Test
    void anUndetectedClusterIsUnknownEverywhere() {
        // capability 列为空 = 未探测（既有口径）→ 所有组件都 UNKNOWN，不是 NOT_INSTALLED
        ClusterComponentService svc = svcWithGroups(List.of(), Map.of());

        assertThat(svc.status("c1")).allSatisfy(s -> assertThat(s.getState()).isEqualTo("UNKNOWN"));
    }

    @Test
    void flagsCarryNoVersionOrNamespace() {
        // 租户侧投影有意砍掉版本与命名空间 —— 租户不需要知道 istiod 装在哪儿
        ClusterComponentService svc = svc(List.of(probe("tekton-pipelines", true, 1, 1, "v1.15.3")));

        Map<String, ?> flags = svc.flags("c1");

        assertThat(flags).containsKey("ci");
        assertThat(flags.get("ci").toString()).doesNotContain("tekton-pipelines").doesNotContain("v1.15.3");
    }

    @Test
    void aFeatureIsAvailableOnlyWhenAllItsComponentsAreHealthy() {
        // service-mesh 依赖 istio 与 gateway-api —— istio 在跑、gateway group 没有 → 整块不可用
        ClusterComponentService svc = svc(List.of(probe("istio-system", true, 1, 1, "1.30.3")));

        Map<String, ComponentFlagDTO> flags = svc.flags("c1");
        assertThat(flags.get("service-mesh").available()).isFalse();
        assertThat(flags.get("service-mesh").missing()).contains("Gateway API");
    }

    @Test
    void ciDoesNotDependOnTheTektonOperator() {
        // ⭐ operator 是安装来源，不是 CI 的功能前提：官方 release 清单装的 Tekton 没有 operator，照样能跑
        ClusterComponentService svc = svc(List.of(probe("tekton-pipelines", true, 1, 1, "v1.15.3")));

        assertThat(svc.flags("c1").get("ci").available()).isTrue();
        assertThat(new ClusterComponentCatalog().componentsOf("ci"))
                .extracting(d -> d.code()).containsExactly("tekton");
    }

    @Test
    void aComponentWithARecipeCarriesTheAvailableChartVersions() {
        // 前端据此决定「安装/升级」是否给按钮、以及升级时列出哪些版本
        ClusterComponentService svc = svc(List.of(probe("kube-system", false, null, null, null)));

        assertThat(find(svc.status("c1"), "metrics-server").getAvailableVersions()).containsExactly("3.14.0");
    }

    @Test
    void editableFieldsCarryKeyLabelTypeAndDefault() {
        // 前端据此渲染表单；契约值（🔒）不在其中
        ClusterComponentService svc = svc(List.of(probe("kube-system", false, null, null, null)));

        var fields = find(svc.status("c1"), "metrics-server").getEditableFields();

        assertThat(fields).hasSize(1);
        assertThat(fields.get(0).getKey()).isEqualTo("replicas");
        assertThat(fields.get(0).getType()).isEqualTo("number");
        assertThat(fields.get(0).getDefaultValue()).isEqualTo(1);
        assertThat(fields.get(0).getLabel()).isEqualTo("副本数");
    }

    @Test
    void prometheusExposesStorageAsStorageClassAndSizeFields() {
        // 存储不做 JSON 文本域：SC 走集群下拉（storageClass），容量按带单位的输入框（size）。
        // 顺序即表单顺序（recipe 用 LinkedHashMap 固定）
        ClusterComponentService svc = svc(List.of());

        var fields = find(svc.status("c1"), "prometheus-operator").getEditableFields();

        assertThat(fields).extracting(com.coding.platformapi.models.EditableFieldDTO::getKey)
                .containsExactly(
                        "prometheus.prometheusSpec.retention",
                        "prometheus.prometheusSpec.replicas",
                        "prometheus.prometheusSpec.storageSpec.volumeClaimTemplate.spec.storageClassName",
                        "prometheus.prometheusSpec.storageSpec.volumeClaimTemplate.spec.resources.requests.storage",
                        "alertmanager.alertmanagerSpec.storage.volumeClaimTemplate.spec.storageClassName",
                        "alertmanager.alertmanagerSpec.storage.volumeClaimTemplate.spec.resources.requests.storage",
                        "grafana.enabled");
        assertThat(fields).filteredOn(f -> f.getKey().endsWith(".storageClassName"))
                .hasSize(2).allSatisfy(f -> assertThat(f.getType()).isEqualTo("storageClass"));
        assertThat(fields).filteredOn(f -> f.getKey().endsWith(".requests.storage"))
                .hasSize(2).allSatisfy(f -> assertThat(f.getType()).isEqualTo("size"));
        assertThat(fields).filteredOn(f -> f.getKey().endsWith(".requests.storage"))
                .extracting(com.coding.platformapi.models.EditableFieldDTO::getDefaultValue)
                .containsExactly("50Gi", "10Gi");
    }

    @Test
    void availableVersionsOnlyCarryTheComponentsOwnChart() {
        // 管理集群的 Prometheus recipe 里含随附的 platform-thanos 0.47.2。
        // 版本列表只能报组件本体（platform-kps 92.10.1）—— 混进 thanos 的版本会让 UI 出现
        // 「已装 92.10.1，可升级到 0.47.2」这种荒唐提示（2026-10-18 实锤）
        K8sComponentClient client = mock(K8sComponentClient.class);
        when(client.probe(anyString(), any())).thenReturn(aligned(List.of()));
        ClusterService management = mock(ClusterService.class);
        when(management.requireProfile("c1")).thenReturn("MANAGEMENT");
        ClusterComponentService svc = new ClusterComponentService(new ClusterComponentCatalog(),
                capability(DEFAULT_GROUPS), client, cache(), management,
                new com.coding.platformapi.components.ComponentRecipeCatalog(), operationMapper());

        assertThat(find(svc.status("c1"), "prometheus-operator").getAvailableVersions())
                .containsExactly("92.10.1");
        // Thanos Receive 地址进表单（管理集群预填集群内 receive，端口是 chart 的 remote-write 10908）
        ComponentStatusDTO prom = find(svc.status("c1"), "prometheus-operator");
        assertThat(prom.isThanosRemoteWrite()).isTrue();
        assertThat(prom.getDefaultThanosReceiveUrl())
                .isEqualTo("http://platform-thanos-receive.monitor.svc:10908/api/v1/receive");
    }

    @Test
    void installedComponentsCarryTheEffectivePlatformUrls() {
        // 编辑表单要预填**当前生效**的地址（上次成功操作的 __platform 快照），不是平台默认值 ——
        // 否则用户改完再打开编辑，看到的还是默认值，分不清哪个在生效
        PlatformComponentOperation op = new PlatformComponentOperation();
        op.setComponentCode("prometheus-operator");
        op.setStatus("SUCCESS");
        op.setValuesSnapshot("{\"platform-kps\":{\"prometheus\":{\"prometheusSpec\":"
                + "{\"retention\":\"30d\"}}},"
                + "\"__platform\":{"
                + "\"alertWebhookUrl\":\"http://now:8081/alert/webhook\","
                + "\"thanosReceiveUrl\":\"http://now-receive:10908/api/v1/receive\","
                + "\"thanosInstall\":{\"namespace\":\"monitor\",\"secretName\":\"minio-creds\","
                + "\"endpoint\":\"minio.default.svc:9000\",\"bucket\":\"thanos\",\"insecureSkipVerify\":true}}}");
        PlatformComponentOperationMapper ops = mock(PlatformComponentOperationMapper.class);
        PlatformComponentOperation tektonOp = new PlatformComponentOperation();
        tektonOp.setComponentCode("tekton");
        tektonOp.setStatus("SUCCESS");
        tektonOp.setValuesSnapshot("{\"__platform\":{\"tektonProfile\":\"basic\"}}");
        when(ops.selectLatestSuccesses("c1")).thenReturn(List.of(op, tektonOp));

        K8sComponentClient client = mock(K8sComponentClient.class);
        when(client.probe(anyString(), any())).thenReturn(aligned(List.of()));
        ClusterService management = mock(ClusterService.class);
        when(management.requireProfile("c1")).thenReturn("MANAGEMENT");
        ClusterComponentService svc = new ClusterComponentService(new ClusterComponentCatalog(),
                capability(DEFAULT_GROUPS), client, cache(), management,
                new com.coding.platformapi.components.ComponentRecipeCatalog(), ops);

        ComponentStatusDTO prom = find(svc.status("c1"), "prometheus-operator");

        assertThat(prom.getAlertWebhookUrl()).isEqualTo("http://now:8081/alert/webhook");
        assertThat(prom.getThanosReceiveUrl()).isEqualTo("http://now-receive:10908/api/v1/receive");
        // 可编辑字段也要带当前生效值：改完 retention 再打开编辑，不能还显示 recipe 默认的 15d
        assertThat(prom.getEditableFields())
                .filteredOn(f -> f.getKey().endsWith("prometheusSpec.retention"))
                .extracting(com.coding.platformapi.models.EditableFieldDTO::getCurrentValue)
                .containsExactly("30d");
        // Thanos 安装参数同样要能回填（Secret / MinIO / 跳过 HTTPS 校验都允许装完后改）
        assertThat(prom.getThanosInstallCurrent().getSecretName()).isEqualTo("minio-creds");
        assertThat(prom.getThanosInstallCurrent().getEndpoint()).isEqualTo("minio.default.svc:9000");
        assertThat(prom.getThanosInstallCurrent().getBucket()).isEqualTo("thanos");
        assertThat(prom.getThanosInstallCurrent().isInsecureSkipVerify()).isTrue();
        // Tekton profile：表单可选的默认值与当前生效值
        ComponentStatusDTO tekton = find(svc.status("c1"), "tekton");
        assertThat(tekton.isTektonProfile()).isTrue();
        assertThat(tekton.getDefaultTektonProfile()).isEqualTo("all");
        assertThat(tekton.getTektonProfileCurrent()).isEqualTo("basic");
    }

    @Test
    void onlyPrometheusExposesPlatformAlerting() {
        // 表单据此显示"告警回调地址"输入框
        ClusterComponentService svc = svc(List.of());

        assertThat(find(svc.status("c1"), "prometheus-operator").isPlatformAlerting()).isTrue();
        assertThat(find(svc.status("c1"), "istio").isPlatformAlerting()).isFalse();
    }

    @Test
    void whenTheProfileCannotBeReadTheVersionListIsEmpty() {
        // 画像读不到 → 取不到 recipe → 版本列表为空（探测本身照常，不因此整体失败）
        ClusterService failing = mock(ClusterService.class);
        when(failing.requireProfile("c1")).thenThrow(new RuntimeException("db down"));
        K8sComponentClient client = mock(K8sComponentClient.class);
        when(client.probe(anyString(), any())).thenReturn(aligned(List.of()));
        ClusterComponentService svc = new ClusterComponentService(
                new ClusterComponentCatalog(), capability(DEFAULT_GROUPS), client, cache(),
                failing, new com.coding.platformapi.components.ComponentRecipeCatalog(), operationMapper());

        assertThat(find(svc.status("c1"), "metrics-server").getAvailableVersions()).isEmpty();
    }

    @Test
    void theSecondCallWithinTtlDoesNotProbeAgain() {
        // 门控收口后每个租户进 CI 页/网格页都会触发 —— 不挡就是几百次集群调用
        K8sComponentClient client = mock(K8sComponentClient.class);
        when(client.probe(anyString(), any())).thenReturn(aligned(List.of()));
        ClusterComponentService svc = svcWith(client, DEFAULT_GROUPS);

        svc.status("c1");
        svc.status("c1");

        verify(client, times(1)).probe(eq("c1"), any());
    }

    // ==================== 夹具 ====================

    private static ClusterComponentService svc(List<WorkloadProbeResultDTO> overrides) {
        return svcWithGroups(overrides, DEFAULT_GROUPS);
    }

    private static ClusterComponentService svcWithGroups(List<WorkloadProbeResultDTO> overrides,
                                                         Map<String, List<String>> groups) {
        K8sComponentClient client = mock(K8sComponentClient.class);
        when(client.probe(anyString(), any())).thenReturn(aligned(overrides));
        return svcWith(client, groups);
    }

    private static ClusterComponentService svcThrowing(RuntimeException e) {
        return svcThrowingWithGroups(e, DEFAULT_GROUPS);
    }

    private static ClusterComponentService svcThrowingWithGroups(RuntimeException e, Map<String, List<String>> groups) {
        K8sComponentClient client = mock(K8sComponentClient.class);
        when(client.probe(anyString(), any())).thenThrow(e);
        return svcWith(client, groups);
    }

    private static ClusterComponentService svcWith(K8sComponentClient client, Map<String, List<String>> groups) {
        return new ClusterComponentService(new ClusterComponentCatalog(), capability(groups), client, cache(),
                profileService(), new com.coding.platformapi.components.ComponentRecipeCatalog(), operationMapper());
    }

    /** 画像：测试固定 BUSINESS（metrics-server 的 recipe 两个画像相同，但版本列表要走画像取） */
    private static ClusterService profileService() {
        ClusterService s = mock(ClusterService.class);
        when(s.requireProfile("c1")).thenReturn("BUSINESS");
        return s;
    }

    private static ClusterCapabilityService capability(Map<String, List<String>> groups) {
        ClusterCapabilityService capability = mock(ClusterCapabilityService.class);
        when(capability.groups("c1")).thenReturn(groups);
        return capability;
    }

    /** 默认没有操作记录（Mockito 对 List 返回值默认给空集合） */
    private static PlatformComponentOperationMapper operationMapper() {
        return mock(PlatformComponentOperationMapper.class);
    }

    /** 一条覆盖项：{@code namespace}+deployment。其余组件一律"没找到"。 */
    private static WorkloadProbeResultDTO probe(String ns, boolean found, Integer ready, Integer desired, String version) {
        return probe(ns, found, ready, desired, version, null);
    }

    private static WorkloadProbeResultDTO probe(String ns, boolean found, Integer ready, Integer desired,
                                                String version, String error) {
        WorkloadProbeResultDTO r = new WorkloadProbeResultDTO();
        r.setNamespace(ns);
        r.setKind("deployment");
        r.setFound(found);
        r.setReady(ready);
        r.setDesired(desired);
        r.setVersion(version);
        r.setError(error);
        r.setDetail(found ? ready + "/" + desired + " 就绪" : "在平台已知的位置（" + ns + "）没有找到");
        return r;
    }

    /**
     * 把覆盖项贴回**目录顺序**的完整结果表 —— 服务按目标顺序回收结果，
     * 所以夹具必须回一条对齐的列表（(ns,kind) 在目录里唯一，足以定位）。
     */
    private static List<WorkloadProbeResultDTO> aligned(List<WorkloadProbeResultDTO> overrides) {
        List<WorkloadProbeResultDTO> out = new ArrayList<>();
        for (var def : new ClusterComponentCatalog().all()) {
            for (ComponentProbe p : def.probes()) {
                WorkloadProbeResultDTO hit = overrides.stream()
                        .filter(o -> Objects.equals(o.getNamespace(), p.namespace())
                                && Objects.equals(o.getKind(), p.kind()))
                        .findFirst().orElse(null);
                if (hit != null) {
                    out.add(hit);
                    continue;
                }
                WorkloadProbeResultDTO miss = new WorkloadProbeResultDTO();
                miss.setNamespace(p.namespace());
                miss.setKind(p.kind());
                miss.setFound(false);
                miss.setDetail("crd".equals(p.kind())
                        ? "集群内没有找到 CRD " + p.name()
                        : "在平台已知的位置（" + p.namespace() + "）没有找到");
                out.add(miss);
            }
        }
        return out;
    }

    private static ComponentStatusDTO find(List<ComponentStatusDTO> all, String code) {
        return all.stream().filter(s -> s.getCode().equals(code)).findFirst()
                .orElseThrow(() -> new AssertionError("没有组件 " + code));
    }

    /** 一条 CRD 判据的结果（namespace 为 null） */
    private static WorkloadProbeResultDTO probeCrd(String crdName, String version) {
        WorkloadProbeResultDTO r = new WorkloadProbeResultDTO();
        r.setKind("crd");
        r.setName(crdName);
        r.setFound(true);
        r.setVersion(version);
        r.setDetail("已安装（CRD）");
        return r;
    }

    /** 一条"多命中"结果（detail 原样透传用） */
    private static WorkloadProbeResultDTO probeWithDetail(String ns, String detail) {
        WorkloadProbeResultDTO r = probe(ns, true, 1, 1, "1.30.3");
        r.setDetail(detail);
        return r;
    }

    /** 带存储语义的 RedisJsonCache（mock 的 StringRedisTemplate 自身不存值） */
    private static RedisJsonCache cache() {
        return cacheOver(new HashMap<>());
    }

    private static RedisJsonCache cacheOver(Map<String, String> store) {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get(anyString())).thenAnswer(inv -> store.get(inv.getArgument(0)));
        doAnswer(inv -> {
            store.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(ops).set(anyString(), anyString(), any(Duration.class));
        return new RedisJsonCache(redis, JsonMapper.builder().build());
    }
}
