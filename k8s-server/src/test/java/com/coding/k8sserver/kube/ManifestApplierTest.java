package com.coding.k8sserver.kube;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 清单解析必须与 kubectl 同口径（YAML 1.1）。这些用例都是 2026-11-06 实测出来的差异点：
 * fabric8/Jackson 自带的 YAML 1.2 解析在这里会全部给出不同的值。
 */
class ManifestApplierTest {

    @SuppressWarnings("unchecked")
    private static Object at(GenericKubernetesResource r, String... path) {
        Object cur = r.getAdditionalProperties();
        for (String p : path) {
            if (cur instanceof List<?> l) {
                cur = l.get(Integer.parseInt(p));
            } else {
                cur = ((Map<String, Object>) cur).get(p);
            }
        }
        return cur;
    }

    @Test
    void octalFileModeIsParsedAsOctalLikeKubectl() {
        // YAML 1.2 会得到字符串 "0644" → apiserver 拒绝；kubectl（YAML 1.1）得到 420
        List<GenericKubernetesResource> objs = ManifestApplier.parse("""
                apiVersion: apps/v1
                kind: Deployment
                metadata: {name: d, namespace: ns}
                spec:
                  template:
                    spec:
                      volumes:
                      - name: v
                        configMap: {name: c, defaultMode: 0644}
                """);

        assertThat(at(objs.get(0), "spec", "template", "spec", "volumes", "0", "configMap", "defaultMode"))
                .isEqualTo(420);
    }

    @Test
    void yesNoOnOffAreBooleansLikeKubectl() {
        GenericKubernetesResource r = ManifestApplier.parse("""
                apiVersion: crd.projectcalico.org/v1
                kind: FelixConfiguration
                metadata: {name: default}
                spec: {a: yes, b: off, c: "on"}
                """).get(0);

        assertThat(at(r, "spec", "a")).isEqualTo(true);
        assertThat(at(r, "spec", "b")).isEqualTo(false);
        // 加了引号就是字符串
        assertThat(at(r, "spec", "c")).isEqualTo("on");
    }

    @Test
    void timestampsStayStrings() {
        // kubectl 不把时间戳转成日期对象；snakeyaml 默认会转成 java.util.Date
        GenericKubernetesResource r = ManifestApplier.parse("""
                apiVersion: v1
                kind: ConfigMap
                metadata: {name: c, namespace: ns, annotations: {built: 2026-11-06T10:00:00Z}}
                """).get(0);

        assertThat(r.getMetadata().getAnnotations()).containsEntry("built", "2026-11-06T10:00:00Z");
    }

    @Test
    void integersInCrdSchemasAreKeptVerbatim() {
        // 类型化 CRD 模型会把 2147483647 改写成 2.147483647E9；通用模型必须原样保留
        GenericKubernetesResource r = ManifestApplier.parse("""
                apiVersion: apiextensions.k8s.io/v1
                kind: CustomResourceDefinition
                metadata: {name: installations.operator.tigera.io}
                spec:
                  versions:
                  - name: v1
                    schema:
                      openAPIV3Schema:
                        properties:
                          x: {type: integer, maximum: 2147483647, minimum: 0}
                """).get(0);

        Object max = at(r, "spec", "versions", "0", "schema", "openAPIV3Schema", "properties", "x", "maximum");
        assertThat(max).isEqualTo(2147483647);
        assertThat(at(r, "spec", "versions", "0", "schema", "openAPIV3Schema", "properties", "x", "minimum"))
                .isEqualTo(0);
    }

    @Test
    void emptyAndCommentOnlyDocumentsAreSkippedAndListsAreExpanded() {
        List<GenericKubernetesResource> objs = ManifestApplier.parse("""
                # Source: chart/templates/empty.yaml
                ---
                # only a comment
                ---
                apiVersion: v1
                kind: ConfigMap
                metadata: {name: a, namespace: ns}
                ---
                apiVersion: v1
                kind: List
                items:
                - apiVersion: v1
                  kind: ConfigMap
                  metadata: {name: b, namespace: ns}
                - apiVersion: v1
                  kind: Secret
                  metadata: {name: c, namespace: ns}
                ---
                """);

        assertThat(objs).extracting(o -> o.getKind() + "/" + o.getMetadata().getName())
                .containsExactly("ConfigMap/a", "ConfigMap/b", "Secret/c");
    }

    @Test
    void manifestsLargerThanSnakeyamlsDefaultLimitStillParse() {
        // snakeyaml 默认上限 3MB；Calico 的 CRD 清单渲染出来约 3.5MB
        StringBuilder big = new StringBuilder();
        String filler = "x".repeat(1000);
        for (int i = 0; i < 4000; i++) {
            big.append("---\napiVersion: v1\nkind: ConfigMap\nmetadata: {name: c").append(i)
                    .append(", namespace: ns}\ndata: {v: ").append(filler).append("}\n");
        }
        assertThat(big.length()).isGreaterThan(4 * 1024 * 1024);

        assertThat(ManifestApplier.parse(big.toString())).hasSize(4000);
    }

    @Test
    void unknownFieldsAreNotDropped() {
        GenericKubernetesResource r = ManifestApplier.parse("""
                apiVersion: apps/v1
                kind: Deployment
                metadata: {name: d, namespace: ns}
                spec: {someFieldFromAFutureKubernetes: 1}
                """).get(0);

        assertThat(at(r, "spec", "someFieldFromAFutureKubernetes")).isEqualTo(1);
    }

    @Test
    void unknownKindErrorIsReadableInsteadOfAnObjectDump() {
        GenericKubernetesResource obj = ManifestApplier.parse("""
                apiVersion: example.com/v1
                kind: Nope
                metadata: {name: x, namespace: ns}
                """).get(0);
        // fabric8 的原文（2026-11-06 真集群实测）
        RuntimeException raw = new io.fabric8.kubernetes.client.KubernetesClientException(
                "Could not find a registered handler for item: [GenericKubernetesResource(apiVersion=example.com/v1, ...)].");

        assertThat(ManifestApplier.message(raw, obj))
                .isEqualTo("集群里没有这种资源类型 example.com/v1 Nope（对应的 CRD 可能还没装，或该 API 版本已被集群移除）");
    }

    @Test
    void blankManifestYieldsNothing() {
        assertThat(ManifestApplier.parse("")).isEmpty();
        assertThat(ManifestApplier.parse(null)).isEmpty();
    }
}
