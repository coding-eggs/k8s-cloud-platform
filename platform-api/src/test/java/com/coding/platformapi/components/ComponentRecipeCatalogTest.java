package com.coding.platformapi.components;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ComponentRecipeCatalogTest {

    private final ComponentRecipeCatalog catalog = new ComponentRecipeCatalog();

    @Test
    void metricsServerHasRecipesForBothProfiles() {
        for (String profile : List.of("BUSINESS", "MANAGEMENT")) {
            ComponentRecipe r = catalog.recipeOf("metrics-server", profile);
            assertThat(r).as("profile %s", profile).isNotNull();
            assertThat(r.releases()).hasSize(1);
        }
    }

    @Test
    void metricsServerRecipeCarriesTheKubeletInsecureTlsContract() {
        // 契约值：自签 kubelet 证书的集群不加这个参数，metrics-server 永远 NotReady
        ReleaseSpec spec = catalog.recipeOf("metrics-server", "BUSINESS").releases().get(0);
        assertThat(String.valueOf(spec.contractValues().get("args")))
                .contains("--kubelet-insecure-tls");
    }

    @Test
    void releaseNameAndNamespaceAreFixed() {
        ReleaseSpec spec = catalog.recipeOf("metrics-server", "BUSINESS").releases().get(0);
        assertThat(spec.releaseName()).isEqualTo("platform-metrics-server");
        assertThat(spec.namespace()).isEqualTo("kube-system");
    }

    @Test
    void theContractPinsTheDeploymentNameTheProbeLooksFor() {
        // 官方 chart 默认用 release 名做 fullname（platform-metrics-server），
        // 而 ClusterComponentCatalog 探测的是 deployment `metrics-server` —— 名字对不上就永远"未安装"
        ReleaseSpec spec = catalog.recipeOf("metrics-server", "BUSINESS").releases().get(0);
        assertThat(spec.contractValues().get("fullnameOverride")).isEqualTo("metrics-server");
    }

    @Test
    void theChartVersionExistsInTheAdapterBundle() throws Exception {
        // ⭐ 跨模块一致性：recipe 指向的版本必须真的在 k8s-server 的内置 chart 里
        ReleaseSpec spec = catalog.recipeOf("metrics-server", "BUSINESS").releases().get(0);
        Path chart = Path.of("../k8s-server/src/main/resources/charts/components/" + spec.chartCode() + "/"
                + spec.chartVersion() + "/chart/Chart.yaml");
        assertThat(Files.exists(chart)).as("缺少内置 chart：%s", chart).isTrue();
    }

    @Test
    void anUnknownComponentOrProfileHasNoRecipe() {
        assertThat(catalog.recipeOf("no-such", "BUSINESS")).isNull();
        assertThat(catalog.supports("metrics-server")).isTrue();
    }
}
