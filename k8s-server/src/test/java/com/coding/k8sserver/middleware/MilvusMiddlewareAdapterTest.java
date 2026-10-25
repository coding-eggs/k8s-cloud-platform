package com.coding.k8sserver.middleware;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.models.middleware.MiddlewareCreateRequestDTO;
import com.coding.common.models.middleware.MiddlewareScaleRequestDTO;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MilvusMiddlewareAdapterTest {

    private final MilvusMiddlewareAdapter adapter = new MilvusMiddlewareAdapter();

    private MiddlewareCreateRequestDTO req(String mode) {
        MiddlewareCreateRequestDTO r = new MiddlewareCreateRequestDTO();
        r.setInstanceId("mwi_1");
        r.setClusterId("c1");
        r.setCode("milvus");
        r.setMode(mode);
        r.setNamespace("demo");
        r.setName("mw-milvus-abc12345");
        r.setReplicas(1);
        r.setVersion("2.6.25");
        r.setCpuRequest("500m");
        r.setCpuLimit("2");
        r.setMemoryRequest("2Gi");
        r.setMemoryLimit("4Gi");
        r.setStorageSize("20Gi");
        r.setLocalStorageSize("10Gi");
        r.setObjectStorageType("CUSTOM");
        r.setObjectStorageEndpoint("s3.example.com:9000");
        r.setObjectStorageBucket("milvus-bucket");
        r.setObjectStorageAccessKey("AK");
        r.setObjectStorageSecretKey("SK");
        r.setObjectStorageUseSsl(false);
        return r;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> spec(GenericKubernetesResource res) {
        return (Map<String, Object>) res.getAdditionalProperties().get("spec");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o) {
        return (Map<String, Object>) o;
    }

    private static Map<String, Object> etcdValues(GenericKubernetesResource res) {
        Map<String, Object> etcd = map(map(spec(res).get("dependencies")).get("etcd"));
        return map(map(etcd.get("inCluster")).get("values"));
    }

    @Test
    void standaloneBuildsStandaloneModeWithLocalVolume() {
        var res = adapter.buildCreate(req("STANDALONE")).get(0).resource();
        Map<String, Object> spec = spec(res);

        assertThat(res.getApiVersion()).isEqualTo("milvus.io/v1beta1");
        assertThat(res.getKind()).isEqualTo("Milvus");
        assertThat(spec).containsEntry("mode", "standalone");
        assertThat(map(spec.get("components"))).containsEntry("image", "milvusdb/milvus:v2.6.25");
        assertThat(map(map(spec.get("components")).get("resources")).get("limits"))
                .isEqualTo(Map.of("cpu", "2", "memory", "4Gi"));

        Map<String, Object> pvc = map(map(spec.get("persistence")).get("persistentVolumeClaim"));
        assertThat(pvc.get("labels")).isEqualTo(Map.of("platform.coding/middleware-instance", "mwi_1"));
        assertThat(map(map(pvc.get("spec")).get("resources")).get("requests"))
                .isEqualTo(Map.of("storage", "10Gi"));
    }

    @Test
    void standaloneEtcdGetsResourcesAndPlatformPvcLabel() {
        var res = adapter.buildCreate(req("STANDALONE")).get(0).resource();
        Map<String, Object> deps = map(spec(res).get("dependencies"));

        assertThat(deps).doesNotContainKey("msgStreamType");
        Map<String, Object> etcd = etcdValues(res);
        assertThat(etcd).containsEntry("replicaCount", 1);
        assertThat(map(etcd.get("persistence")).get("labels"))
                .isEqualTo(Map.of("platform.coding/middleware-instance", "mwi_1"));
        assertThat(map(etcd.get("resources")).get("limits"))
                .isEqualTo(Map.of("cpu", "500m", "memory", "512Mi"));
    }

    @Test
    void storageIsAlwaysExternalS3WithSecretRef() {
        var res = adapter.buildCreate(req("STANDALONE")).get(0).resource();
        Map<String, Object> storage = map(map(spec(res).get("dependencies")).get("storage"));

        assertThat(storage).containsEntry("external", true)
                .containsEntry("type", "S3")
                .containsEntry("endpoint", "s3.example.com:9000")
                .containsEntry("secretRef", "mw-milvus-abc12345-milvus-storage");
        assertThat(map(spec(res).get("config")).toString())
                .contains("bucketName=milvus-bucket")
                .contains("useVirtualHost=false");
        // operator 把 spec.config 直接并进 milvus.yaml：多包一层 milvus 就等于没配
        assertThat(map(spec(res).get("config"))).containsKey("minio").doesNotContainKey("milvus");
    }

    @Test
    void clusterUsesWoodpeckerAndQueryNodeReplicas() {
        var r = req("CLUSTER");
        r.setReplicas(3);
        var res = adapter.buildCreate(r).get(0).resource();
        var spec = spec(res);

        assertThat(spec).containsEntry("mode", "cluster");
        assertThat(spec).doesNotContainKey("persistence");
        assertThat(map(spec.get("dependencies"))).containsEntry("msgStreamType", "woodpecker");
        assertThat(map(map(spec.get("components")).get("queryNode"))).containsEntry("replicas", 3);
        // 集群形态默认 3 个 etcd
        assertThat(etcdValues(res)).containsEntry("replicaCount", 3);
    }

    @Test
    void tlsWritesSslFlagInBothPlaces() {
        var r = req("STANDALONE");
        r.setObjectStorageEndpoint("https://s3.example.com:9443/");
        r.setObjectStorageUseSsl(true);
        var spec = spec(adapter.buildCreate(r).get(0).resource());

        Map<String, Object> storage = map(map(spec.get("dependencies")).get("storage"));
        assertThat(storage).containsEntry("endpoint", "s3.example.com:9443");
        assertThat(map(storage.get("ssl"))).containsEntry("enabled", true);
        assertThat(map(spec.get("config")).toString()).contains("useSSL=true");
    }

    @Test
    void hostPortKeepsOnlyHostAndPort() {
        assertThat(MilvusMiddlewareAdapter.hostPort("http://platform-seaweedfs-s3.platform-storage:8333"))
                .isEqualTo("platform-seaweedfs-s3.platform-storage:8333");
        assertThat(MilvusMiddlewareAdapter.hostPort("s3.example.com:9000")).isEqualTo("s3.example.com:9000");
        assertThat(MilvusMiddlewareAdapter.hostPort("https://s3.example.com/x/y")).isEqualTo("s3.example.com");
    }

    @Test
    void rejectsEtcdReplicasOutsideRange() {
        var r = req("CLUSTER");
        r.setEtcdReplicas(4);
        assertThatThrownBy(() -> adapter.buildCreate(r))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("etcd");
    }

    @Test
    void rejectsMissingObjectStorageParameters() {
        var r = req("STANDALONE");
        r.setObjectStorageBucket("  ");
        assertThatThrownBy(() -> adapter.buildCreate(r))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("对象存储");
    }

    @Test
    void extraSecretUsesOperatorKeyNames() {
        assertThat(adapter.extraSecretName("mw-milvus-abc12345"))
                .isEqualTo("mw-milvus-abc12345-milvus-storage");
        assertThat(adapter.extraSecretData(req("STANDALONE")))
                .isEqualTo(Map.of("accesskey", "AK", "secretkey", "SK"));
        var missing = req("STANDALONE");
        missing.setObjectStorageSecretKey("");
        assertThat(adapter.extraSecretData(missing)).isEmpty();
    }

    @Test
    void statusSelectorAndMetricsMatchOperatorLabels() {
        assertThat(adapter.statusSelector("mw-milvus-abc12345", "Milvus"))
                .isEqualTo("app.kubernetes.io/instance=mw-milvus-abc12345,app.kubernetes.io/name=milvus");
        var metrics = adapter.metricsSpec("mw-milvus-abc12345", "Milvus", "mwi_1");
        assertThat(metrics.port()).isEqualTo(9091);
        assertThat(metrics.path()).isEqualTo("/metrics");
        assertThat(metrics.scheme()).isEqualTo("http");
    }

    @Test
    void desiredPodsSumsComponentReplicas() {
        GenericKubernetesResource live = new GenericKubernetesResource();
        Map<String, Object> qn = new LinkedHashMap<>();
        qn.put("replicas", 3);
        Map<String, Object> components = new LinkedHashMap<>();
        components.put("image", "milvusdb/milvus:v2.6.25");
        components.put("proxy", Map.of("replicas", 1));
        components.put("mixcoord", Map.of("replicas", 1));
        components.put("datanode", Map.of("replicas", 1));
        components.put("queryNode", qn);
        components.put("streamingnode", Map.of("replicas", 1));
        components.put("standalone", Map.of("replicas", 0));
        live.setAdditionalProperty("spec", Map.of("components", components));

        assertThat(adapter.desiredPods(live, "Milvus")).isEqualTo(7);
    }

    @Test
    void applyScaleRejectsStandaloneReplicaChange() {
        GenericKubernetesResource live = new GenericKubernetesResource();
        live.setAdditionalProperty("spec", new LinkedHashMap<>(Map.of("mode", "standalone")));
        var req = new MiddlewareScaleRequestDTO();
        req.setMode("STANDALONE");
        req.setReplicas(2);

        assertThatThrownBy(() -> adapter.applyScale(live, MilvusMiddlewareAdapter.CRD, req))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("单节点");
    }

    @Test
    void applyScaleUpdatesQueryNodeAndResources() {
        GenericKubernetesResource live = new GenericKubernetesResource();
        Map<String, Object> components = new LinkedHashMap<>();
        components.put("queryNode", new LinkedHashMap<>(Map.of("replicas", 1)));
        live.setAdditionalProperty("spec",
                new LinkedHashMap<>(Map.of("mode", "cluster", "components", components)));

        var req = new MiddlewareScaleRequestDTO();
        req.setMode("CLUSTER");
        req.setReplicas(4);
        req.setCpuRequest("1");
        req.setCpuLimit("4");
        req.setMemoryRequest("4Gi");
        req.setMemoryLimit("8Gi");
        adapter.applyScale(live, MilvusMiddlewareAdapter.CRD, req);

        Map<String, Object> after = map(spec(live).get("components"));
        assertThat(map(after.get("queryNode"))).containsEntry("replicas", 4);
        assertThat(map(after.get("resources")).get("limits"))
                .isEqualTo(Map.of("cpu", "4", "memory", "8Gi"));
    }

    @Test
    void crProblemSurfacesStorageConditionMessage() {
        GenericKubernetesResource live = new GenericKubernetesResource();
        live.setAdditionalProperty("status", Map.of(
                "status", "Unhealthy",
                "conditions", List.of(
                        Map.of("type", "StorageReady", "status", "False",
                                "message", "no such bucket: milvus-bucket"))));

        assertThat(adapter.crProblem(live)).isEqualTo("StorageReady：no such bucket: milvus-bucket");
    }

    @Test
    void crProblemIgnoresEmptyMessagesAndHealthyStatus() {
        GenericKubernetesResource live = new GenericKubernetesResource();
        live.setAdditionalProperty("status", Map.of(
                "status", "Healthy",
                "conditions", List.of(Map.of("type", "MilvusReady", "status", "False", "message", ""))));

        assertThat(adapter.crProblem(live)).isNull();
    }
}
