package com.coding.platformapi.registry;

import com.coding.data.mapper.system.PlatformComponentOperationMapper;
import com.coding.data.models.k8s.K8sCluster;
import com.coding.data.models.system.PlatformComponentOperation;
import com.coding.platformapi.services.K8sProvisioningService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 「Harbor 装在哪些集群、地址是什么」只有这一个真相源。 */
class HarborInstallLocatorTest {

    PlatformComponentOperationMapper operations = mock(PlatformComponentOperationMapper.class);
    K8sProvisioningService provisioning = mock(K8sProvisioningService.class);
    HarborInstallLocator locator = new HarborInstallLocator(operations, provisioning);

    private static K8sCluster cluster(String id, String name) {
        K8sCluster c = new K8sCluster();
        c.setClusterId(id);
        c.setClusterName(name);
        return c;
    }

    private static PlatformComponentOperation op(String snapshot) {
        PlatformComponentOperation o = new PlatformComponentOperation();
        o.setValuesSnapshot(snapshot);
        return o;
    }

    @Test
    void listsOnlyClustersWithASuccessfulHarborInstall() {
        when(provisioning.enabledClusters())
                .thenReturn(List.of(cluster("c1", "主集群"), cluster("c2", "业务集群")));
        when(operations.selectLatestSuccess("c1", "harbor"))
                .thenReturn(op("{\"__platform\":{\"harbor\":"
                        + "{\"host\":\"harbor.ackcp.cloud\",\"https\":true,\"exposeMode\":\"route\"}}}"));
        when(operations.selectLatestSuccess("c2", "harbor")).thenReturn(null);

        List<HarborInstallLocator.InstalledHarbor> all = locator.all();

        assertThat(all).hasSize(1);
        assertThat(all.get(0).clusterId()).isEqualTo("c1");
        assertThat(all.get(0).clusterName()).isEqualTo("主集群");
        assertThat(all.get(0).access().host()).isEqualTo("harbor.ackcp.cloud");
        assertThat(all.get(0).access().https()).isTrue();
        assertThat(all.get(0).access().baseUrl()).isEqualTo("https://harbor.ackcp.cloud");
        assertThat(all.get(0).exposeMode()).isEqualTo("route");
    }

    @Test
    void fallsBackToHostWhenExternalHostIsMissing() {
        when(provisioning.enabledClusters()).thenReturn(List.of(cluster("c1", "主集群")));
        when(operations.selectLatestSuccess("c1", "harbor"))
                .thenReturn(op("{\"__platform\":{\"harbor\":{\"host\":\"192.168.31.80:30002\"}}}"));

        assertThat(locator.all().get(0).access().host()).isEqualTo("192.168.31.80:30002");
        assertThat(locator.all().get(0).access().https()).isFalse();
    }

    @Test
    void unparsableSnapshotIsTreatedAsNotInstalled() {
        when(provisioning.enabledClusters()).thenReturn(List.of(cluster("c1", "主集群")));
        when(operations.selectLatestSuccess("c1", "harbor")).thenReturn(op("not json"));

        assertThat(locator.all()).isEmpty();
        assertThat(locator.access("c1")).isNull();
    }

    @Test
    void isPlatformHostIgnoresSchemePathAndCase() {
        when(provisioning.enabledClusters()).thenReturn(List.of(cluster("c1", "主集群")));
        when(operations.selectLatestSuccess("c1", "harbor"))
                .thenReturn(op("{\"__platform\":{\"harbor\":{\"host\":\"harbor.ackcp.cloud\"}}}"));

        assertThat(locator.isPlatformHost("https://harbor.ackcp.cloud/v2/")).isTrue();
        assertThat(locator.isPlatformHost("HARBOR.ACKCP.CLOUD")).isTrue();
        assertThat(locator.isPlatformHost("harbor.other.corp")).isFalse();
        assertThat(locator.isPlatformHost(null)).isFalse();
    }

    @Test
    void hostOfStripsSchemeAndPath() {
        assertThat(HarborInstallLocator.hostOf("https://harbor.x:8443/a/b")).isEqualTo("harbor.x:8443");
        assertThat(HarborInstallLocator.hostOf("harbor.x")).isEqualTo("harbor.x");
        assertThat(HarborInstallLocator.hostOf(null)).isEmpty();
    }
}
