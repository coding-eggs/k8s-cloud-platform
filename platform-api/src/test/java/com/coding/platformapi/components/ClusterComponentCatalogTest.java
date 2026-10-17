package com.coding.platformapi.components;

import com.coding.common.constants.ProbeCrds;
import com.coding.common.constants.ProbeNamespaces;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 组件目录的完整性约束。目录是这批的<b>唯一出处</b>，所以它的约束也都在这里钉住：
 * 判据不能缺、功能映射不能缺、<b>候选命名空间必须落在 adapter 的探测清单内</b>。
 */
class ClusterComponentCatalogTest {

    private final ClusterComponentCatalog catalog = new ClusterComponentCatalog();

    @Test
    void firstBatchIsExactlyTheElevenAgreedOnes() {
        assertThat(catalog.all()).extracting(ComponentDef::code).containsExactly(
                "metrics-server", "prometheus-operator", "gateway-api", "istio", "istio-ambient",
                "tekton", "tekton-operator", "calico", "tigera-operator", "cert-manager", "otel-operator");
    }

    @Test
    void everyComponentHasAtLeastOneJudge() {
        // 两个判据都没有的组件永远答不出"装没装"，是不完整的定义
        for (ComponentDef d : catalog.all()) {
            assertThat(d.apiGroup() != null || !d.probes().isEmpty())
                    .as("组件 %s 没有任何判据", d.code()).isTrue();
        }
    }

    @Test
    void everyComponentSaysWhichPlatformFeaturesItSupports() {
        for (ComponentDef d : catalog.all()) {
            assertThat(d.features()).as("组件 %s 没说支撑什么功能", d.code()).isNotEmpty();
        }
    }

    @Test
    void everyProbeNamespaceIsAllowedByTheAdapter() {
        // ⭐ 目录与 k8s-server 清单必须一致。不一致的症状是"页面上写着未安装" ——
        //    因为越界的探测被 400 拒了，而上游把被拒渲染成没装。靠这条测试盯住，不靠人记得改两处。
        for (ComponentDef d : catalog.all()) {
            for (ComponentProbe p : d.probes()) {
                if ("crd".equals(p.kind())) {
                    // CRD 是集群级资源：走 CRD 清单，且不该带命名空间
                    assertThat(ProbeCrds.DEFAULT)
                            .as("组件 %s 的 CRD %s 不在 adapter 的探测清单里", d.code(), p.name())
                            .contains(p.name());
                    assertThat(p.namespace()).as("CRD 判据不该带命名空间").isNull();
                } else {
                    assertThat(ProbeNamespaces.DEFAULT)
                            .as("组件 %s 的候选命名空间 %s 不在 adapter 的探测清单里", d.code(), p.namespace())
                            .contains(p.namespace());
                }
            }
        }
    }

    @Test
    void everyProbeHasAnImageRepoOrAName() {
        // 两个都没有的候选会把整个命名空间的全部工作负载都当成"命中"
        for (ComponentDef d : catalog.all()) {
            for (ComponentProbe p : d.probes()) {
                assertThat((p.imageRepo() != null && !p.imageRepo().isBlank())
                        || (p.name() != null && !p.name().isBlank()))
                        .as("组件 %s 的候选既没有 imageRepo 也没有 name", d.code()).isTrue();
            }
        }
    }

    @Test
    void featureToComponentReverseLookupIsDerivedNotHandWritten() {
        // 依赖关系只写一遍（在组件上写"我支撑哪些功能"），反查是算出来的
        assertThat(catalog.componentsOf("ci")).extracting(ComponentDef::code).contains("tekton");
        assertThat(catalog.componentsOf("service-mesh")).extracting(ComponentDef::code)
                .contains("istio", "gateway-api");
        assertThat(catalog.componentsOf("hpa-resource-metrics")).extracting(ComponentDef::code)
                .containsExactly("metrics-server");
    }

    @Test
    void anUnknownFeatureCodeHasNoComponents() {
        assertThat(catalog.componentsOf("no-such-feature")).isEmpty();
    }
}
