package com.coding.platformapi.services;

import com.coding.common.models.k8s.dto.ChartApplyResultDTO;
import com.coding.data.mapper.system.PlatformAppMapper;
import com.coding.data.mapper.system.PlatformAppOperationMapper;
import com.coding.data.models.system.PlatformApp;
import com.coding.data.models.system.PlatformAppOperation;
import com.coding.platformapi.k8s.K8sChartClient;
import com.coding.platformapi.models.AppInstallRequest;
import com.coding.platformapi.models.AppUninstallRequest;
import com.coding.platformapi.models.AppUpgradeRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AppInstallServiceTest {

    private final PlatformAppMapper apps = mock(PlatformAppMapper.class);
    private final PlatformAppOperationMapper ops = mock(PlatformAppOperationMapper.class);
    private final K8sChartClient client = mock(K8sChartClient.class);

    private final AppInstallService service = new AppInstallService(apps, ops, client);

    @BeforeEach
    void insertReturnsIds() {
        doAnswer(inv -> {
            inv.getArgument(0, PlatformAppOperation.class).setId(1L);
            return 1;
        }).when(ops).insert(any());
    }

    private AppInstallRequest request() {
        AppInstallRequest r = new AppInstallRequest();
        r.setClusterId("c1");
        r.setNamespace("demo");
        r.setDisplayName("Web");
        r.setChartRef(ociChart());
        r.setValues("replicas: 1\n");
        return r;
    }

    private com.coding.common.models.k8s.dto.ChartRefDTO ociChart() {
        var c = new com.coding.common.models.k8s.dto.ChartRefDTO();
        c.setSource("HARBOR");
        c.setRef("oci://harbor/demo/web");
        c.setChartName("web");
        c.setVersion("1.0.0");
        return c;
    }

    @Test
    void installGeneratesReleaseNameAndRecordsSuccess() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        when(client.apply(any())).thenAnswer(inv -> {
            ChartApplyResultDTO ok = new ChartApplyResultDTO();
            ok.setSuccess(true);
            ok.setMessage("ok");
            return ok;
        });
        doAnswer(inv -> {
            done.countDown();
            return 1;
        }).when(apps).updateById(any());

        Long opId = service.install(request(), "admin");

        assertThat(opId).isEqualTo(1L);
        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();

        ArgumentCaptor<PlatformApp> appCaptor = ArgumentCaptor.forClass(PlatformApp.class);
        verify(apps).insert(appCaptor.capture());
        assertThat(appCaptor.getValue().getReleaseName()).startsWith("app-");

        // insert 传的是同一个对象引用，异步线程随后把它改成 RUNNING —— 用 updateById 的快照断言终态
        ArgumentCaptor<PlatformApp> updated = ArgumentCaptor.forClass(PlatformApp.class);
        verify(apps, atLeastOnce()).updateById(updated.capture());
        assertThat(updated.getAllValues()).anyMatch(a -> "RUNNING".equals(a.getStatus()));

        ArgumentCaptor<PlatformAppOperation> opCaptor = ArgumentCaptor.forClass(PlatformAppOperation.class);
        verify(ops, atLeastOnce()).updateById(opCaptor.capture());
        assertThat(opCaptor.getAllValues()).anyMatch(o -> "SUCCESS".equals(o.getStatus()));
    }

    @Test
    void installMarksFailureWhenHelmFails() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        when(client.apply(any())).thenReturn(failedApply());
        doAnswer(inv -> {
            done.countDown();
            return 1;
        }).when(apps).updateById(any());

        service.install(request(), "admin");
        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();

        ArgumentCaptor<PlatformApp> appCaptor = ArgumentCaptor.forClass(PlatformApp.class);
        verify(apps, atLeastOnce()).updateById(appCaptor.capture());
        assertThat(appCaptor.getAllValues()).anyMatch(a -> "FAILED".equals(a.getStatus()));
    }

    @Test
    void concurrentOperationsOnSameAppAreRejected() {
        // running_lock = cluster_id|app_id 的唯一键：同一个应用不能同时有两个进行中的操作
        // ⚠️ 必须用 doThrow：when(mock.insert(any())) 会真的调用一次 insert(null)，撞上 @BeforeEach 的 answer
        doThrow(new DuplicateKeyException("running_lock")).when(ops).insert(any());

        assertThatThrownBy(() -> service.install(request(), "admin"))
                .hasMessageContaining("正在安装");
    }

    @Test
    void upgradeRequiresAPlatformOwnedApp() {
        AppUpgradeRequest r = new AppUpgradeRequest();
        r.setAppId("app-missing");

        assertThatThrownBy(() -> service.upgrade(r, "admin"))
                .hasMessageContaining("不是平台安装");
    }

    @Test
    void uninstallRequiresAPlatformOwnedApp() {
        AppUninstallRequest r = new AppUninstallRequest();
        r.setAppId("app-missing");

        assertThatThrownBy(() -> service.uninstall(r, "admin"))
                .hasMessageContaining("不是平台安装");
    }

    private static ChartApplyResultDTO failedApply() {
        ChartApplyResultDTO bad = new ChartApplyResultDTO();
        bad.setSuccess(false);
        bad.setMessage("helm failed");
        return bad;
    }
}
