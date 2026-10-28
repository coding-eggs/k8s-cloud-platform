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
    /** 默认：项目留空 = 一上游一项目（项目名就是上游主机） */
    private static final String PROJECT = "";

    @SuppressWarnings("unchecked")
    private Map<String, Object> rewrite(Map<String, Object> values) {
        return (Map<String, Object>) ImageRewriter.rewrite(values, HOST, PROJECT);
    }

    @Test
    void rewritesAWholeImageString() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("image", "quay.io/opstree/redis:v7.0.15");

        assertThat(rewrite(values)).containsEntry("image",
                "harbor.ackcp.cloud/quay.io/opstree/redis:v7.0.15");
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
                        "harbor.ackcp.cloud/docker.elastic.co/elasticsearch/elasticsearch")
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

        assertThat(rewritten).containsEntry("registry", "harbor.ackcp.cloud")
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
                .contains("harbor.ackcp.cloud/quay.io/prometheuscommunity/elasticsearch-exporter:v1.7.0");
    }

    @Test
    void rewriteIsIdempotent() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("image", "quay.io/opstree/redis:v7.0.15");

        Map<String, Object> once = rewrite(values);
        assertThat(rewrite(once)).isEqualTo(once);
    }

    /**
     * tigera-operator 的写法：{@code registry} 与 {@code image} 是**兄弟键**，
     * 模板拼 {@code {{registry}}/{{image}}:{{version}}}。只改 image 会拼出
     * {@code quay.io/harbor.ackcp.cloud/...} 这种拉不到的地址。
     */
    @Test
    void rewritesSiblingRegistryAndImageAsAPair() {
        Map<String, Object> operator = new LinkedHashMap<>();
        operator.put("image", "tigera/operator");
        operator.put("version", "v1.44.0");
        operator.put("registry", "quay.io");
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("tigeraOperator", operator);

        @SuppressWarnings("unchecked")
        Map<String, Object> rewritten = (Map<String, Object>) rewrite(values).get("tigeraOperator");

        assertThat(rewritten).containsEntry("registry", "harbor.ackcp.cloud")
                .containsEntry("image", "quay.io/tigera/operator")
                .containsEntry("version", "v1.44.0");
        assertThat(rewrite(values).toString()).doesNotContain("quay.io/harbor.ackcp.cloud");
    }

    /** cert-manager：{@code imageRegistry} + {@code imageNamespace} + 每个组件的 {@code image.name}。 */
    @Test
    void rewritesImageRegistryAndNamespaceAsAPair() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("imageRegistry", "quay.io");
        values.put("imageNamespace", "jetstack");
        values.put("image", Map.of("name", "cert-manager-controller", "repository", ""));

        Map<String, Object> rewritten = rewrite(values);

        assertThat(rewritten).containsEntry("imageRegistry", "harbor.ackcp.cloud")
                .containsEntry("imageNamespace", "quay.io/jetstack");
    }

    /** Strimzi：{@code defaultImageRegistry} + {@code defaultImageRepository}（operator + broker 都走它）。 */
    @Test
    void rewritesDefaultImageRegistryAndRepositoryAsAPair() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("defaultImageRegistry", "quay.io");
        values.put("defaultImageRepository", "strimzi");
        values.put("image", Map.of("name", "operator"));

        Map<String, Object> rewritten = rewrite(values);

        assertThat(rewritten).containsEntry("defaultImageRegistry", "harbor.ackcp.cloud")
                .containsEntry("defaultImageRepository", "quay.io/strimzi");
    }

    @Test
    void compositeRulesAreIdempotent() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("imageRegistry", "quay.io");
        values.put("imageNamespace", "jetstack");
        values.put("tigeraOperator", Map.of("image", "tigera/operator", "registry", "quay.io"));

        Map<String, Object> once = rewrite(values);
        assertThat(rewrite(once)).isEqualTo(once);
    }

    /**
     * 显式给了 project 就退回旧布局（多一段前缀）。这种布局 Harbor 的复制规则落不了，
     * 只适合手工搬运 —— 留着是为了不改配置也能回到「单项目」。
     */
    @Test
    void explicitProjectKeepsTheOlderLayout() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("image", "quay.io/opstree/redis:v7.0.15");

        @SuppressWarnings("unchecked")
        Map<String, Object> rewritten =
                (Map<String, Object>) ImageRewriter.rewrite(values, HOST, "platform");

        assertThat(rewritten).containsEntry("image",
                "harbor.ackcp.cloud/platform/quay.io/opstree/redis:v7.0.15");
    }
}