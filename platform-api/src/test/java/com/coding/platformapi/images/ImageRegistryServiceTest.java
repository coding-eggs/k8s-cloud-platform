package com.coding.platformapi.images;

import com.coding.platformapi.services.ClusterService;
import com.coding.platformapi.services.HarborRegistryService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 镜像来源解析：地址取「集群列 &gt; Harbor 安装记录 &gt; 平台默认」，开关关掉就完全退回官方。
 */
class ImageRegistryServiceTest {

    private final ClusterService cluster = mock(ClusterService.class);
    private final HarborRegistryService harbor = mock(HarborRegistryService.class);

    private ImageRegistryService service(String defaultHost, String defaultProject, boolean enabled) {
        ImageRegistryService svc = new ImageRegistryService(cluster, harbor);
        svc.defaultHost = defaultHost;
        svc.defaultProject = defaultProject;
        svc.enabled = enabled;
        return svc;
    }

    @Test
    void clusterColumnsWinOverEverythingElse() {
        when(cluster.imageRegistryHost("c1")).thenReturn("10.0.0.9:4443");
        when(cluster.imageRegistryProject("c1")).thenReturn(null);   // 空 → 用默认项目名

        ImageRegistry registry = service("h:443", "platform", true).resolve("c1");

        assertThat(registry.host()).isEqualTo("10.0.0.9:4443");
        assertThat(registry.project()).isEqualTo("platform");
    }

    @Test
    void fallsBackToTheHarborInstallRecord() {
        when(cluster.imageRegistryHost("c1")).thenReturn(null);
        when(harbor.harborHost("c1")).thenReturn("harbor.ackcp.cloud");

        assertThat(service("", "platform", true).resolve("c1").host())
                .isEqualTo("harbor.ackcp.cloud");
    }

    @Test
    void disabledOrUnconfiguredMeansNoRewrite() {
        when(cluster.imageRegistryHost("c1")).thenReturn(null);
        when(harbor.harborHost("c1")).thenReturn(null);

        ImageRegistryService off = service("", "platform", false);
        assertThat(off.resolve("c1")).isEqualTo(ImageRegistry.OFFICIAL);
        assertThat(off.rewrite("c1", "redis:7", ImageSource.PLATFORM)).isEqualTo("redis:7");

        ImageRegistryService unset = service("", "platform", true);
        assertThat(unset.resolve("c1")).isEqualTo(ImageRegistry.OFFICIAL);
        assertThat(unset.rewrite("c1", "redis:7", ImageSource.PLATFORM)).isEqualTo("redis:7");
    }

    @Test
    void platformSourceRewritesAndOfficialLeavesAsIs() {
        when(cluster.imageRegistryHost("c1")).thenReturn(null);
        when(harbor.harborHost("c1")).thenReturn("harbor.ackcp.cloud");
        ImageRegistryService svc = service("", "platform", true);

        assertThat(svc.rewrite("c1", "redis:7", ImageSource.PLATFORM))
                .isEqualTo("harbor.ackcp.cloud/platform/docker.io/library/redis:7");
        assertThat(svc.rewrite("c1", "redis:7", ImageSource.OFFICIAL)).isEqualTo("redis:7");
        assertThat(svc.rewrite("c1", null, ImageSource.PLATFORM)).isNull();
    }

    @Test
    void emptyProjectMeansOneProjectPerUpstream() {
        when(cluster.imageRegistryHost("c1")).thenReturn(null);
        when(harbor.harborHost("c1")).thenReturn("harbor.ackcp.cloud");

        assertThat(service("", "", true).rewrite("c1", "quay.io/opstree/redis:v7.0.15", ImageSource.PLATFORM))
                .isEqualTo("harbor.ackcp.cloud/quay.io/opstree/redis:v7.0.15");
    }

    @Test
    void httpSchemeOnTheRecordIsStripped() {
        when(cluster.imageRegistryHost("c1")).thenReturn("https://harbor.ackcp.cloud/");
        when(cluster.imageRegistryProject("c1")).thenReturn(null);

        assertThat(service("", "platform", true).resolve("c1").host()).isEqualTo("harbor.ackcp.cloud");
    }
}
