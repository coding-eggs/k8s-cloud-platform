package com.coding.platformapi.services;

import com.coding.common.models.app.AppObjectStatusDTO;
import com.coding.common.models.app.AppStatusResultDTO;
import com.coding.data.mapper.system.PlatformApplicationMapper;
import com.coding.data.mapper.system.PlatformApplicationOperationMapper;
import com.coding.data.mapper.system.PlatformApplicationResourceMapper;
import com.coding.data.mapper.system.PlatformApplicationRevisionMapper;
import com.coding.data.models.system.PlatformApplication;
import com.coding.data.models.system.PlatformApplicationOperation;
import com.coding.data.models.system.PlatformApplicationRevision;
import com.coding.platformapi.k8s.K8sAppClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApplicationDeployServiceTest {

    private PlatformApplicationMapper apps;
    private PlatformApplicationRevisionMapper revisions;
    private PlatformApplicationOperationMapper operations;
    private PlatformApplicationResourceMapper resources;
    private K8sAppClient client;
    private ApplicationDeployService service;

    @BeforeEach
    void setUp() {
        apps = mock(PlatformApplicationMapper.class);
        revisions = mock(PlatformApplicationRevisionMapper.class);
        operations = mock(PlatformApplicationOperationMapper.class);
        resources = mock(PlatformApplicationResourceMapper.class);
        client = mock(K8sAppClient.class);
        service = new ApplicationDeployService(apps, revisions, operations, resources, client);
    }

    private static PlatformApplication app() {
        PlatformApplication a = new PlatformApplication();
        a.setId("app_1");
        a.setClusterId("c1");
        a.setNamespace("demo");
        a.setSlug("demo");
        a.setStatus("DRAFT");
        a.setCurrentRevision(0);
        a.setSpecJson("""
                {"namespace":"demo","nodes":[{"key":"web","kind":"SERVICE","params":{"image":"nginx"}}],"edges":[]}
                """);
        return a;
    }

    private static PlatformApplicationRevision revision() {
        PlatformApplicationRevision r = new PlatformApplicationRevision();
        r.setAppId("app_1");
        r.setRevision(1);
        r.setManifest("""
                apiVersion: v1
                kind: ConfigMap
                metadata:
                  name: demo-web
                  namespace: demo
                """);
        r.setObjectsJson("""
                [{"apiVersion":"v1","kind":"ConfigMap","namespace":"demo","name":"demo-web"}]
                """);
        r.setStatus("RENDERED");
        return r;
    }

    private static PlatformApplicationOperation opAt(String step) {
        PlatformApplicationOperation op = new PlatformApplicationOperation();
        op.setId(7L);
        op.setAppId("app_1");
        op.setRevision(1);
        op.setStatus("RUNNING");
        op.setCurrentStep(step);
        op.setStartedAt(new Date());
        return op;
    }

    @Test
    void startCreatesRevisionAndPendingOperation() {
        when(apps.selectById("app_1")).thenReturn(app());
        when(revisions.selectMaxRevision("app_1")).thenReturn(0);
        when(operations.selectActiveByApp("app_1")).thenReturn(null);

        service.start("app_1", null, "admin");

        ArgumentCaptor<PlatformApplicationOperation> cap =
                ArgumentCaptor.forClass(PlatformApplicationOperation.class);
        verify(operations).insert(cap.capture());
        assertThat(cap.getValue().getStatus()).isEqualTo("PENDING");
        assertThat(cap.getValue().getCurrentStep()).isEqualTo("PRECHECK");
        assertThat(cap.getValue().getRevision()).isEqualTo(1);
        assertThat(cap.getValue().getAction()).isEqualTo("DEPLOY");
        verify(revisions).insert(any());
    }

    @Test
    void refusesSecondConcurrentOperation() {
        when(apps.selectById("app_1")).thenReturn(app());
        PlatformApplicationOperation running = opAt("APPLY");
        when(operations.selectActiveByApp("app_1")).thenReturn(running);

        assertThatThrownBy(() -> service.start("app_1", null, "admin"))
                .hasMessageContaining("已有进行中的部署");
        verify(operations, never()).insert(any());
    }

    @Test
    void resumesFromPersistedStepWithoutReRendering() {
        when(apps.selectById("app_1")).thenReturn(app());
        when(revisions.selectByRevision("app_1", 1)).thenReturn(revision());
        when(client.status(any())).thenReturn(readyStatus());

        PlatformApplicationOperation op = opAt("APPLY");
        service.advance(op);

        verify(client, never()).render(any());   // 续跑不重跑 RENDER
        verify(client).apply(any());
        assertThat(op.getStatus()).isEqualTo("SUCCESS");
    }

    @Test
    void refusesToAdoptExistingForeignObject() {
        when(apps.selectById("app_1")).thenReturn(app());
        when(revisions.selectByRevision("app_1", 1)).thenReturn(revision());
        AppStatusResultDTO existing = new AppStatusResultDTO();
        AppObjectStatusDTO objectStatus = new AppObjectStatusDTO();
        objectStatus.setKind("ConfigMap");
        objectStatus.setName("demo-web");
        objectStatus.setState("READY");
        existing.setObjects(List.of(objectStatus));
        when(client.status(any())).thenReturn(existing);
        when(resources.selectByApp("app_1")).thenReturn(List.of());

        PlatformApplicationOperation op = opAt("CONFLICT");
        service.advance(op);

        assertThat(op.getStatus()).isEqualTo("FAILED");
        assertThat(op.getMessage()).contains("不属于本应用");
        verify(client, never()).apply(any());
    }

    @Test
    void reportsNodeKeyFromObjectName() {
        assertThat(ApplicationDeployService.nodeKeyOf("demo-web", "demo-")).isEqualTo("web");
        assertThat(ApplicationDeployService.nodeKeyOf("demo-web-headless", "demo-")).isEqualTo("web");
        assertThat(ApplicationDeployService.nodeKeyOf("demo-web-env", "demo-")).isEqualTo("web");
    }

    private static AppStatusResultDTO readyStatus() {
        AppStatusResultDTO r = new AppStatusResultDTO();
        r.setObjects(List.of());
        return r;
    }
}
