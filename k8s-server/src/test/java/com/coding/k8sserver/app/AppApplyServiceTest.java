package com.coding.k8sserver.app;

import com.coding.common.models.app.AppApplyRequestDTO;
import com.coding.common.models.app.AppObjectRefDTO;
import com.coding.common.models.app.AppPruneRequestDTO;
import com.coding.k8sserver.helm.HelmRunner;
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

    private static HelmRunner helm(boolean ok) {
        return new HelmRunner("true", List.of(), Map.of(), Path.of(System.getProperty("java.io.tmpdir")),
                Duration.ofSeconds(5)) {
            @Override
            public com.coding.k8sserver.helm.HelmResult applyServerSide(String kubeconfig, String manifest) {
                return new com.coding.k8sserver.helm.HelmResult(ok, ok ? 0 : 1, ok ? "applied" : "boom");
            }

            @Override
            public com.coding.k8sserver.helm.HelmResult deleteManifest(String kubeconfig, String manifest) {
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
}
