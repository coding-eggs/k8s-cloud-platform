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
import static org.mockito.ArgumentMatchers.eq;
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
    private ApplicationDeployCredentials credentials;
    private ApplicationDeployService service;

    @BeforeEach
    void setUp() {
        apps = mock(PlatformApplicationMapper.class);
        revisions = mock(PlatformApplicationRevisionMapper.class);
        operations = mock(PlatformApplicationOperationMapper.class);
        resources = mock(PlatformApplicationResourceMapper.class);
        client = mock(K8sAppClient.class);
        credentials = mock(ApplicationDeployCredentials.class);
        service = new ApplicationDeployService(apps, revisions, operations, resources, client, credentials);
    }

    private static PlatformApplication app() {
        PlatformApplication a = new PlatformApplication();
        a.setId("app_1");
        a.setTenantId("t1");
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

    private static PlatformApplicationRevision revision(int number) {
        PlatformApplicationRevision r = new PlatformApplicationRevision();
        r.setAppId("app_1");
        r.setRevision(number);
        r.setSpecJson("""
                {"namespace":"demo","nodes":[{"key":"web","kind":"SERVICE","params":{"image":"nginx"}}],"edges":[]}
                """);
        r.setObjectsJson("""
                [{"apiVersion":"v1","kind":"ConfigMap","namespace":"demo","name":"demo-web"}]
                """);
        r.setManifest("""
                apiVersion: v1
                kind: ConfigMap
                metadata:
                  name: demo-web
                  namespace: demo
                """);
        r.setStatus("RENDERED");
        return r;
    }

    private static PlatformApplicationOperation opAt(String step) {
        PlatformApplicationOperation op = new PlatformApplicationOperation();
        op.setId(7L);
        op.setAppId("app_1");
        op.setRevision(2);
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

        service.start("app_1", null, "admin", "t1");

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
        when(operations.selectActiveByApp("app_1")).thenReturn(opAt("APPLY"));

        assertThatThrownBy(() -> service.start("app_1", null, "admin", "t1"))
                .hasMessageContaining("已有进行中的部署");
        verify(operations, never()).insert(any());
    }

    @Test
    void refusesToDeployAnotherTenantsApplication() {
        when(apps.selectById("app_1")).thenReturn(app());

        assertThatThrownBy(() -> service.start("app_1", null, "admin", "t2"))
                .hasMessageContaining("不存在或已删除");
        verify(operations, never()).insert(any());
    }

    /** 回滚 = 复制历史版本的 spec 建一个**新** revision，版本历史里能看出来。 */
    @Test
    void rollbackCreatesNewRevisionCopyingOldSpec() {
        when(apps.selectById("app_1")).thenReturn(app());
        when(revisions.selectByRevision("app_1", 1)).thenReturn(revision(1));
        when(revisions.selectMaxRevision("app_1")).thenReturn(5);
        when(operations.selectActiveByApp("app_1")).thenReturn(null);

        service.start("app_1", 1, "admin", "t1");

        ArgumentCaptor<PlatformApplicationRevision> revCap =
                ArgumentCaptor.forClass(PlatformApplicationRevision.class);
        verify(revisions).insert(revCap.capture());
        assertThat(revCap.getValue().getRevision()).isEqualTo(6);
        assertThat(revCap.getValue().getSpecJson()).contains("\"web\"");
        assertThat(revCap.getValue().getMessage()).contains("回滚自 rev 1");

        ArgumentCaptor<PlatformApplicationOperation> opCap =
                ArgumentCaptor.forClass(PlatformApplicationOperation.class);
        verify(operations).insert(opCap.capture());
        assertThat(opCap.getValue().getAction()).isEqualTo("ROLLBACK");
        assertThat(opCap.getValue().getRevision()).isEqualTo(6);
    }

    @Test
    void resumesFromPersistedStepWithoutReRendering() {
        when(apps.selectById("app_1")).thenReturn(app());
        when(revisions.selectByRevision("app_1", 2)).thenReturn(revision(2));
        when(client.status(any())).thenReturn(new AppStatusResultDTO());

        PlatformApplicationOperation op = opAt("APPLY");
        service.advance(op);

        verify(client, never()).render(any());
        verify(client).apply(any());
        assertThat(op.getStatus()).isEqualTo("SUCCESS");
    }

    @Test
    void refusesToAdoptExistingForeignObject() {
        when(apps.selectById("app_1")).thenReturn(app());
        when(revisions.selectByRevision("app_1", 2)).thenReturn(revision(2));
        AppStatusResultDTO existing = new AppStatusResultDTO();
        AppObjectStatusDTO s = new AppObjectStatusDTO();
        s.setKind("ConfigMap");
        s.setName("demo-web");
        s.setState("READY");
        existing.setObjects(List.of(s));
        when(client.status(any())).thenReturn(existing);
        when(resources.selectByApp("app_1")).thenReturn(List.of());

        PlatformApplicationOperation op = opAt("CONFLICT");
        service.advance(op);

        assertThat(op.getStatus()).isEqualTo("FAILED");
        assertThat(op.getMessage()).contains("不属于本应用");
        verify(client, never()).apply(any());
    }

    /** PRUNE 的基线是上次成功部署的版本，不是 rev-1（中间失败的版本没有台账行）。 */
    @Test
    void pruneUsesLastSuccessfulRevisionAsBaseline() {
        PlatformApplication a = app();
        a.setCurrentRevision(2);
        when(apps.selectById("app_1")).thenReturn(a);
        when(revisions.selectByRevision("app_1", 5)).thenReturn(revision(5));
        when(resources.selectByRevision("app_1", 2)).thenReturn(List.of());

        PlatformApplicationOperation op = opAt("PRUNE");
        op.setRevision(5);
        service.advance(op);

        verify(resources).selectByRevision("app_1", 2);
        verify(resources, never()).selectByRevision("app_1", 4);
    }

    @Test
    void operationRejectsAnotherTenant() {
        PlatformApplicationOperation op = opAt("APPLY");
        when(operations.selectById(7L)).thenReturn(op);
        when(apps.selectById("app_1")).thenReturn(app());

        assertThatThrownBy(() -> service.operation(7L, "t2")).hasMessageContaining("不存在或已删除");
        assertThat(service.operation(7L, "t1")).isSameAs(op);
    }

    @Test
    void reportsNodeKeyFromObjectName() {
        assertThat(ApplicationDeployService.nodeKeyOf("demo-web", "demo-")).isEqualTo("web");
        assertThat(ApplicationDeployService.nodeKeyOf("demo-web-headless", "demo-")).isEqualTo("web");
        assertThat(ApplicationDeployService.nodeKeyOf("demo-web-env", "demo-")).isEqualTo("web");
    }

    @Test
    void emptyGraphCannotBeDeployed() {
        PlatformApplication a = app();
        a.setSpecJson("{\"namespace\":\"demo\",\"nodes\":[],\"edges\":[]}");
        when(apps.selectById("app_1")).thenReturn(a);
        PlatformApplicationRevision r = revision(2);
        r.setSpecJson("{\"namespace\":\"demo\",\"nodes\":[],\"edges\":[]}");
        when(revisions.selectByRevision("app_1", 2)).thenReturn(r);

        PlatformApplicationOperation op = opAt("PRECHECK");
        service.advance(op);

        assertThat(op.getStatus()).isEqualTo("FAILED");
        assertThat(op.getMessage()).contains("至少要有一个节点");
    }

    @Test
    void finishMarksRevisionStatus() {
        when(apps.selectById("app_1")).thenReturn(app());
        PlatformApplicationRevision rev = revision(2);
        when(revisions.selectByRevision("app_1", 2)).thenReturn(rev);
        when(client.status(any())).thenReturn(new AppStatusResultDTO());

        service.advance(opAt("APPLY"));

        // updateById 会被调用两次（apply 写 APPLIED、finish 写 DEPLOYED），断言对象终态即可
        assertThat(rev.getStatus()).isEqualTo("DEPLOYED");
        verify(credentials).forget(eq(7L));
    }
}
