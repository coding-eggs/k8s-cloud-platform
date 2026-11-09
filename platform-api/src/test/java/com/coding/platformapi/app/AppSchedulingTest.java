package com.coding.platformapi.app;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AppSchedulingTest {

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Object o) {
        return (Map<String, Object>) o;
    }

    @Test
    @SuppressWarnings("unchecked")
    void nodeAffinityIsConvertedAndEmptyTermsDropped() {
        Map<String, Object> compact = Map.of("nodeAffinity", Map.of(
                "required", Map.of("nodeSelectorTerms", List.of(
                        Map.of("matchExpressions", List.of(Map.of("key", "disktype", "operator", "In",
                                "values", List.of("ssd")))),
                        Map.of())),
                "preferred", List.of(
                        Map.of("weight", 80, "preference", Map.of("matchFields",
                                List.of(Map.of("key", "metadata.name", "operator", "In", "values", List.of("n1"))))))));

        Map<String, Object> out = AppScheduling.k8sAffinity(compact);

        Map<String, Object> node = cast(out.get("nodeAffinity"));
        Map<String, Object> required = cast(node.get("requiredDuringSchedulingIgnoredDuringExecution"));
        List<Map<String, Object>> terms = (List<Map<String, Object>>) required.get("nodeSelectorTerms");
        assertThat(terms).hasSize(1);   // 空 term 丢掉，否则 OR 语义被废掉
        assertThat((List<Map<String, Object>>) terms.get(0).get("matchExpressions")).containsExactly(
                Map.of("key", "disktype", "operator", "In", "values", List.of("ssd")));

        List<Map<String, Object>> preferred =
                (List<Map<String, Object>>) node.get("preferredDuringSchedulingIgnoredDuringExecution");
        assertThat(preferred).hasSize(1);
        assertThat(preferred.get(0)).containsEntry("weight", 80);
        assertThat((List<Map<String, Object>>) cast(preferred.get(0).get("preference")).get("matchFields"))
                .hasSize(1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void podAffinityUsesLabelSelectorAndDropsTermsWithoutTopologyKey() {
        Map<String, Object> compact = Map.of(
                "podAffinity", Map.of("required", List.of(
                        Map.of("topologyKey", "kubernetes.io/hostname", "matchLabels", Map.of("app", "web"),
                                "namespaces", List.of("prod")),
                        Map.of("matchLabels", Map.of("app", "ignored")))),
                "podAntiAffinity", Map.of("preferred", List.of(
                        Map.of("weight", 50, "podAffinityTerm",
                                Map.of("topologyKey", "kubernetes.io/hostname")))));

        Map<String, Object> out = AppScheduling.k8sAffinity(compact);

        Map<String, Object> pod = cast(out.get("podAffinity"));
        List<Map<String, Object>> required =
                (List<Map<String, Object>>) pod.get("requiredDuringSchedulingIgnoredDuringExecution");
        assertThat(required).hasSize(1);
        assertThat(required.get(0)).containsEntry("topologyKey", "kubernetes.io/hostname");
        assertThat(cast(required.get(0).get("labelSelector"))).containsEntry("matchLabels", Map.of("app", "web"));
        assertThat(required.get(0)).containsEntry("namespaces", List.of("prod"));

        List<Map<String, Object>> preferred = (List<Map<String, Object>>)
                cast(out.get("podAntiAffinity")).get("preferredDuringSchedulingIgnoredDuringExecution");
        assertThat(preferred).hasSize(1);
        assertThat(preferred.get(0)).containsEntry("weight", 50);
    }

    @Test
    void emptyAffinityRendersNothing() {
        assertThat(AppScheduling.k8sAffinity(null)).isNull();
        assertThat(AppScheduling.k8sAffinity(Map.of())).isNull();
        assertThat(AppScheduling.k8sAffinity(Map.of("nodeAffinity", Map.of()))).isNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void volumesBecomeK8sSources() {
        List<Map<String, Object>> volumes = List.of(
                Map.of("name", "cache", "type", "emptyDir",
                        "emptyDir", Map.of("medium", "Memory", "sizeLimit", 1073741824L)),
                Map.of("name", "cfg", "type", "configMap", "configMap", Map.of("name", "my-cm",
                        "items", List.of(Map.of("key", "a", "path", "a.conf", "mode", 384)))),
                Map.of("name", "sec", "type", "secret", "secret", Map.of("secretName", "my-secret")),
                Map.of("name", "data", "type", "persistentVolumeClaim",
                        "persistentVolumeClaim", Map.of("claimName", "my-pvc")),
                Map.of("name", "host", "type", "hostPath",
                        "hostPath", Map.of("path", "/var/log", "type", "Directory")),
                Map.of("name", "nfs", "type", "nfs",
                        "nfs", Map.of("server", "10.0.0.1", "path", "/export", "readOnly", true)));

        List<Map<String, Object>> out = AppScheduling.k8sVolumes(volumes);

        assertThat(out).hasSize(6);
        assertThat(cast(out.get(0).get("emptyDir"))).containsEntry("medium", "Memory")
                .containsEntry("sizeLimit", "1Gi");
        assertThat(cast(out.get(1).get("configMap"))).containsEntry("name", "my-cm")
                .containsEntry("items", List.of(Map.of("key", "a", "path", "a.conf", "mode", 384)));
        assertThat(cast(out.get(2).get("secret"))).containsEntry("secretName", "my-secret");
        assertThat(cast(out.get(3).get("persistentVolumeClaim"))).containsEntry("claimName", "my-pvc");
        assertThat(cast(out.get(4).get("hostPath"))).containsEntry("path", "/var/log")
                .containsEntry("type", "Directory");
        assertThat(cast(out.get(5).get("nfs"))).containsEntry("server", "10.0.0.1").containsEntry("readOnly", true);
        // 平台侧的类型名与子对象不外泄
        assertThat(out.get(0)).doesNotContainKey("type").doesNotContainKey("emptyDirSource");
    }

    @Test
    @SuppressWarnings("unchecked")
    void passthroughVolumesDropNullsAndEmptyCollections() {
        Map<String, Object> projected = new LinkedHashMap<>();
        projected.put("defaultMode", 420);
        projected.put("sources", List.of(Map.of("serviceAccountToken",
                Map.of("path", "token", "expirationSeconds", 3600))));
        Map<String, Object> csi = new LinkedHashMap<>();
        csi.put("driver", "ebs.csi.aws.com");
        csi.put("fsType", null);
        csi.put("nodePublishSecretRef", Map.of("name", ""));
        csi.put("volumeAttributes", Map.of());

        List<Map<String, Object>> out = AppScheduling.k8sVolumes(List.of(
                Map.of("name", "tok", "type", "projected", "projected", projected),
                Map.of("name", "ebs", "type", "csi", "csi", csi)));

        Map<String, Object> proj = cast(out.get(0).get("projected"));
        assertThat(proj).containsEntry("defaultMode", 420).doesNotContainKey("other");
        assertThat((List<Object>) proj.get("sources")).hasSize(1);
        Map<String, Object> c = cast(out.get(1).get("csi"));
        assertThat(c).containsEntry("driver", "ebs.csi.aws.com")
                .doesNotContainKey("fsType").doesNotContainKey("volumeMounts");
    }

    @Test
    void mountsPassThroughAndOnlyMarkReadOnlyWhenTrue() {
        List<Map<String, Object>> out = AppScheduling.k8sVolumeMounts(List.of(
                Map.of("name", "cache", "mountPath", "/cache", "readOnly", false),
                Map.of("name", "cfg", "mountPath", "/etc/extra", "readOnly", true, "subPath", "a/b")));

        assertThat(out.get(0)).containsEntry("mountPath", "/cache").doesNotContainKey("readOnly");
        assertThat(out.get(1)).containsEntry("readOnly", true).containsEntry("subPath", "a/b");
    }

    @Test
    void sizeLimitIsRenderedAsAQuantity() {
        assertThat(AppScheduling.quantity(2L << 30)).isEqualTo("2Gi");
        assertThat(AppScheduling.quantity(512L << 20)).isEqualTo("512Mi");
        assertThat(AppScheduling.quantity(1L << 10)).isEqualTo("1Ki");
        assertThat(AppScheduling.quantity(1234L)).isEqualTo("1234");
    }
}
