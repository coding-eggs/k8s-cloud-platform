package com.coding.k8sserver.app;

import com.coding.common.models.app.AppApplyRequestDTO;
import com.coding.common.models.app.AppObjectRefDTO;
import com.coding.common.models.app.AppPruneRequestDTO;
import com.coding.k8sserver.kube.ManifestApplier;
import com.coding.k8score.factory.KubernetesClientFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AppApplyServiceTest {

    private static ManifestApplier helm(boolean ok) {
        return new ManifestApplier() {
            @Override
            public com.coding.k8sserver.helm.HelmResult applyServerSide(String kubeconfig, String manifest,
                                                                        String defaultNamespace) {
                return new com.coding.k8sserver.helm.HelmResult(ok, ok ? 0 : 1, ok ? "applied" : "boom");
            }

            @Override
            public com.coding.k8sserver.helm.HelmResult deleteManifest(String kubeconfig, String manifest,
                                                                       String defaultNamespace) {
                return new com.coding.k8sserver.helm.HelmResult(true, 0, "");
            }
        };
    }

    private static AppObjectRefDTO ref(String kind, String name) {
        AppObjectRefDTO r = new AppObjectRefDTO();
        r.setApiVersion("v1");
        r.setKind(kind);
        r.setNamespace("demo");
        r.setName(name);
        return r;
    }

    @Test
    void applyFailsLoudlyOnHelmError() {
        AppApplyService svc = new AppApplyService(helm(false), null);
        AppApplyRequestDTO req = new AppApplyRequestDTO();
        req.setClusterId("c1");
        req.setManifest("apiVersion: v1\nkind: ConfigMap\n");

        assertThatThrownBy(() -> svc.apply(req))
                .hasMessageContaining("boom");
    }

    @Test
    void pruneSkipsPvcUnlessExplicitlyAllowed() {
        AppPruneRequestDTO req = new AppPruneRequestDTO();
        req.setClusterId("c1");
        req.setDeletePvc(false);
        req.setObjects(List.of(ref("PersistentVolumeClaim", "demo-web-data"), ref("Service", "demo-old")));

        RecordingAppApplyService svc = new RecordingAppApplyService();
        int deleted = svc.prune(req);

        assertThat(deleted).isEqualTo(1);
        assertThat(svc.deleted).containsExactly("Service/demo-old");
    }

    @Test
    void pruneDeletesPvcWhenExplicitlyAllowed() {
        AppPruneRequestDTO req = new AppPruneRequestDTO();
        req.setClusterId("c1");
        req.setDeletePvc(true);
        req.setObjects(List.of(ref("PersistentVolumeClaim", "demo-web-data")));

        RecordingAppApplyService svc = new RecordingAppApplyService();

        assertThat(svc.prune(req)).isEqualTo(1);
        assertThat(svc.deleted).containsExactly("PersistentVolumeClaim/demo-web-data");
    }

    /** 把「真正删对象」换成一个可断言的记录器。 */
    static class RecordingAppApplyService extends AppApplyService {

        final List<String> deleted = new ArrayList<>();

        RecordingAppApplyService() {
            super(null, null);
        }

        @Override
        void deleteObject(String clusterId, AppObjectRefDTO ref) {
            deleted.add(ref.getKind() + "/" + ref.getName());
        }
    }

    /** 记录调用顺序的假 applier：live 副本按 kind/name 给 */
    private static ManifestApplier recording(List<String> calls, Map<String, Integer> live) {
        return new ManifestApplier() {
            @Override
            public com.coding.k8sserver.helm.HelmResult applyServerSide(String kubeconfig, String manifest,
                                                                        String defaultNamespace) {
                calls.add("apply:" + manifest.length());
                return new com.coding.k8sserver.helm.HelmResult(true, 0, "applied");
            }

            @Override
            public com.coding.k8sserver.helm.HelmResult applyServerSide(String kubeconfig, String manifest,
                                                                        String defaultNamespace, String fieldManager) {
                calls.add("handover:" + fieldManager + ":" + manifest.replace("\n", " ").trim());
                return new com.coding.k8sserver.helm.HelmResult(true, 0, "applied");
            }

            @Override
            public Integer liveReplicas(String kubeconfig, String apiVersion, String kind, String namespace, String name) {
                return live.get(kind + "/" + name);
            }
        };
    }

    private static final String HPA_MANIFEST = """
            apiVersion: apps/v1
            kind: StatefulSet
            metadata:
              name: shop-db
              namespace: demo
            spec:
              serviceName: shop-db-headless
            ---
            apiVersion: apps/v1
            kind: Deployment
            metadata:
              name: shop-api
              namespace: demo
            spec:
              replicas: 2
            ---
            apiVersion: apps/v1
            kind: Deployment
            metadata:
              name: shop-new
              namespace: demo
            spec: {}
            """;

    @Test
    void workloadWithoutReplicasIsHandedOverAtLiveCountBeforeApply() {
        List<String> calls = new ArrayList<>();
        AppApplyService svc = new AppApplyService(recording(calls, Map.of("StatefulSet/shop-db", 3)), null);
        AppApplyRequestDTO req = new AppApplyRequestDTO();
        req.setClusterId("c1");
        req.setManifest(HPA_MANIFEST);

        svc.apply(req);

        // shop-db：清单不写 replicas、线上有 3 → 先由交接 manager 认领 3；shop-api 写了 replicas、shop-new 线上不存在 → 不交接
        assertThat(calls).hasSize(2);
        assertThat(calls.get(0)).startsWith("handover:" + AppApplyService.REPLICAS_HANDOVER_MANAGER + ":")
                .contains("kind: StatefulSet").contains("name: shop-db").contains("namespace: demo").contains("replicas: 3");
        assertThat(calls.get(1)).startsWith("apply:");
    }

    @Test
    void manifestWithReplicasSkipsHandover() {
        List<String> calls = new ArrayList<>();
        AppApplyService svc = new AppApplyService(recording(calls, Map.of("Deployment/shop-api", 5)), null);
        AppApplyRequestDTO req = new AppApplyRequestDTO();
        req.setClusterId("c1");
        req.setManifest("apiVersion: apps/v1\nkind: Deployment\nmetadata:\n  name: shop-api\n  namespace: demo\nspec:\n  replicas: 2\n");

        svc.apply(req);

        assertThat(calls).singleElement().asString().startsWith("apply:");
    }

    // ---------- 按 kind 过滤（ROUTE 阶段，2026-11-09） ----------

    private static final String MIXED = """
            apiVersion: v1
            kind: Service
            metadata:
              name: shop-api
              namespace: demo
            ---
            apiVersion: gateway.networking.k8s.io/v1
            kind: HTTPRoute
            metadata:
              name: shop-api
              namespace: demo
            spec:
              rules:
                - backendRefs:
                    - name: shop-api
                      port: 80
            """;

    private static ManifestApplier recording(List<String> sent) {
        return new ManifestApplier() {
            @Override
            public com.coding.k8sserver.helm.HelmResult applyServerSide(String kubeconfig, String manifest,
                                                                        String defaultNamespace) {
                sent.add(manifest);
                return new com.coding.k8sserver.helm.HelmResult(true, 0, "applied");
            }
        };
    }

    private static AppApplyRequestDTO mixed() {
        AppApplyRequestDTO req = new AppApplyRequestDTO();
        req.setClusterId("c1");
        req.setManifest(MIXED);
        return req;
    }

    @Test
    void skipKindsDropsRoutesFromTheManifest() {
        List<String> sent = new ArrayList<>();
        AppApplyRequestDTO req = mixed();
        req.setSkipKinds(java.util.Set.of("HTTPRoute", "TCPRoute"));
        new AppApplyService(recording(sent), null).apply(req);

        assertThat(sent).hasSize(1);
        assertThat(sent.get(0)).contains("kind: Service").doesNotContain("HTTPRoute");
    }

    @Test
    void onlyKindsKeepsOnlyRoutes() {
        List<String> sent = new ArrayList<>();
        AppApplyRequestDTO req = mixed();
        req.setOnlyKinds(java.util.Set.of("HTTPRoute", "TCPRoute"));
        new AppApplyService(recording(sent), null).apply(req);

        assertThat(sent).hasSize(1);
        assertThat(sent.get(0)).contains("kind: HTTPRoute").contains("backendRefs").doesNotContain("kind: Service");
        assertThat(ManifestApplier.parse(sent.get(0))).hasSize(1);
    }

    @Test
    void emptyAfterFilterIsANoop() {
        List<String> sent = new ArrayList<>();
        AppApplyRequestDTO req = mixed();
        req.setOnlyKinds(java.util.Set.of("TCPRoute"));
        new AppApplyService(recording(sent), null).apply(req);

        assertThat(sent).isEmpty();
    }

    @Test
    void noFilterSendsTheManifestVerbatim() {
        List<String> sent = new ArrayList<>();
        new AppApplyService(recording(sent), null).apply(mixed());

        assertThat(sent).containsExactly(MIXED);
    }
}
