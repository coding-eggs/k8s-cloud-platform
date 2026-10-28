package com.coding.common.models.image;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * values 里的镜像改写：只认键名 + 值形状，其它一律不动。
 */
class ImageRewriterTest {

    private static final String HOST = "harbor.ackcp.cloud";
    private static final String PROJECT = "platform";

    @SuppressWarnings("unchecked")
    private Map<String, Object> rewrite(Map<String, Object> values) {
        return (Map<String, Object>) ImageRewriter.rewrite(values, HOST, PROJECT);
    }

    @Test
    void rewritesAWholeImageString() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("image", "quay.io/opstree/redis:v7.0.15");

        assertThat(rewrite(values)).containsEntry("image",
                "harbor.ackcp.cloud/platform/quay.io/opstree/redis:v7.0.15");
        // 不改调用方持有的对象
        assertThat(values).containsEntry("image", "quay.io/opstree/redis:v7.0.15");
    }

    @Test
    void rewritesRepositoryPlusTag() {
        Map<String, Object> image = new LinkedHashMap<>();
        image.put("repository", "docker.elastic.co/elasticsearch/elasticsearch");
        image.put("tag", "9.5.0");
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("image", image);

        @SuppressWarnings("unchecked")
        Map<String, Object> rewritten = (Map<String, Object>) rewrite(values).get("image");

        assertThat(rewritten).containsEntry("repository",
                        "harbor.ackcp.cloud/platform/docker.elastic.co/elasticsearch/elasticsearch")
                .containsEntry("tag", "9.5.0");
    }

    @Test
    void splitsRegistryAndRepositoryApart() {
        // harbor / grafana 这类 chart：registry(主机) + repository(路径) + tag
        Map<String, Object> image = new LinkedHashMap<>();
        image.put("registry", "docker.io");
        image.put("repository", "grafana/grafana");
        image.put("tag", "12.0.0");
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("image", image);

        @SuppressWarnings("unchecked")
        Map<String, Object> rewritten = (Map<String, Object>) rewrite(values).get("image");

        assertThat(rewritten).containsEntry("registry", "harbor.ackcp.cloud/platform")
                .containsEntry("repository", "docker.io/grafana/grafana")
                .containsEntry("tag", "12.0.0");
    }

    @Test
    void leavesNonImageValuesAlone() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("imagePullPolicy", "IfNotPresent");
        values.put("gitRepo", "https://gitee.com/x/y.git");
        values.put("path", "/var/tempo/traces");
        values.put("tag", "9.5.0");

        assertThat(rewrite(values)).isEqualTo(values);
    }

    @Test
    void rewritesInsideListsAndNestedMaps() {
        Map<String, Object> sidecar = new LinkedHashMap<>();
        sidecar.put("name", "exporter");
        sidecar.put("image", "quay.io/prometheuscommunity/elasticsearch-exporter:v1.7.0");
        Map<String, Object> podSpec = new LinkedHashMap<>();
        podSpec.put("containers", List.of(sidecar));
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("podTemplate", Map.of("spec", podSpec));

        assertThat(rewrite(values).toString())
                .contains("harbor.ackcp.cloud/platform/quay.io/prometheuscommunity/elasticsearch-exporter:v1.7.0");
    }

    @Test
    void rewriteIsIdempotent() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("image", "quay.io/opstree/redis:v7.0.15");

        Map<String, Object> once = rewrite(values);
        assertThat(rewrite(once)).isEqualTo(once);
    }
}
