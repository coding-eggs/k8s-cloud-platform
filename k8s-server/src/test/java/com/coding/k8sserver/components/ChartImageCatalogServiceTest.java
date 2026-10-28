package com.coding.k8sserver.components;

import com.coding.k8sserver.helm.ChartBundle;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 镜像清单：遍历内置 chart，把上游镜像与平台 Harbor 地址都列出来。
 *
 * <p>清单与改写共用一套识别口径，所以「列出来的」就是「会被改写的」。
 */
class ChartImageCatalogServiceTest {

    private final ChartImageCatalogService service =
            new ChartImageCatalogService(new ChartBundle("src/main/resources/charts"));

    @Test
    void listsImagesWithTheirMirroredAddress() {
        List<com.coding.common.models.k8s.dto.ImageListItemDTO> items =
                service.list("harbor.ackcp.cloud", "platform");

        assertThat(items).as("内置 chart 至少有一批镜像").hasSizeGreaterThan(20);
        assertThat(items).allSatisfy(item -> {
            assertThat(item.getChart()).doesNotContain("/harbor/");   // harbor 自己永远官方
            assertThat(item.getMirrored()).startsWith("harbor.ackcp.cloud/platform/");
        });
        assertThat(items.stream().map(com.coding.common.models.k8s.dto.ImageListItemDTO::getUpstream))
                .anyMatch(u -> u.contains("registry.k8s.io/metrics-server"));
    }

    @Test
    void withoutARegistryMirroredEqualsUpstream() {
        List<com.coding.common.models.k8s.dto.ImageListItemDTO> items = service.list(null, null);

        assertThat(items).isNotEmpty().allSatisfy(item ->
                assertThat(item.getMirrored()).isEqualTo(item.getUpstream()));
    }
}
