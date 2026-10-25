package com.coding.platformapi.services;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.models.app.AppNodeDTO;
import com.coding.common.models.app.AppPruneRequestDTO;
import com.coding.common.models.app.AppSpecDTO;
import com.coding.data.mapper.system.PlatformApplicationMapper;
import com.coding.data.mapper.system.PlatformApplicationOperationMapper;
import com.coding.data.mapper.system.PlatformApplicationResourceMapper;
import com.coding.data.mapper.system.PlatformApplicationRevisionMapper;
import com.coding.data.mapper.auth.PlatformTenantNamespaceMapper;
import com.coding.data.models.system.PlatformApplication;
import com.coding.data.models.system.PlatformApplicationResource;
import com.coding.platformapi.k8s.K8sAppClient;
import com.coding.platformapi.models.ApplicationCreateRequest;
import com.coding.platformapi.models.ApplicationDeleteRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApplicationServiceTest {

    private PlatformApplicationMapper apps;
    private PlatformApplicationRevisionMapper revisions;
    private PlatformApplicationOperationMapper operations;
    private PlatformApplicationResourceMapper resources;
    private PlatformTenantNamespaceMapper allocations;
    private NamespaceAccessGuard namespaceGuard;
    private K8sAppClient client;
    private ApplicationService service;

    @BeforeEach
    void setUp() {
        apps = mock(PlatformApplicationMapper.class);
        revisions = mock(PlatformApplicationRevisionMapper.class);
        operations = mock(PlatformApplicationOperationMapper.class);
        resources = mock(PlatformApplicationResourceMapper.class);
        allocations = mock(PlatformTenantNamespaceMapper.class);
        namespaceGuard = mock(NamespaceAccessGuard.class);
        client = mock(K8sAppClient.class);
        service = new ApplicationService(apps, revisions, operations, resources, allocations,
                namespaceGuard, client);
    }

    private static ApplicationCreateRequest req(AppSpecDTO spec) {
        ApplicationCreateRequest r = new ApplicationCreateRequest();
        r.setClusterId("c1");
        r.setNamespace("demo");
        r.setName("Demo App");
        r.setSpec(spec);
        return r;
    }

    private static AppSpecDTO spec() {
        AppNodeDTO n = new AppNodeDTO();
        n.setKey("web");
        n.setKind("SERVICE");
        n.setParams(Map.of("image", "nginx"));
        AppSpecDTO s = new AppSpecDTO();
        s.setNamespace("demo");
        s.setNodes(List.of(n));
        s.setEdges(List.of());
        return s;
    }

    private static PlatformApplication row() {
        PlatformApplication a = new PlatformApplication();
        a.setId("app_1");
        a.setTenantId("t1");
        a.setClusterId("c1");
        a.setNamespace("demo");
        a.setName("Demo App");
        a.setSlug("demo-app");
        a.setStatus("READY");
        a.setCurrentRevision(1);
        a.setSpecJson("""
                {"namespace":"demo","nodes":[{"key":"web","kind":"SERVICE","params":{"image":"nginx"}}],"edges":[]}
                """);
        return a;
    }

    private static PlatformApplicationResource resource(String kind, String name) {
        PlatformApplicationResource r = new PlatformApplicationResource();
        r.setAppId("app_1");
        r.setRevision(1);
        r.setApiVersion("v1");
        r.setKind(kind);
        r.setNamespace("demo");
        r.setName(name);
        return r;
    }

    @Test
    void createRejectsInvalidGraphBeforeTouchingDb() {
        AppSpecDTO bad = spec();
        bad.getNodes().get(0).setKind("CRONJOB");

        assertThatThrownBy(() -> service.create(req(bad), "admin", "t1"))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("不支持的节点类型");
        verify(apps, never()).insert(any());
    }

    @Test
    void createPersistsSlugAndSpec() {
        service.create(req(spec()), "admin", "t1");

        ArgumentCaptor<PlatformApplication> cap = ArgumentCaptor.forClass(PlatformApplication.class);
        verify(apps).insert(cap.capture());
        PlatformApplication saved = cap.getValue();
        assertThat(saved.getSlug()).isEqualTo("demo-app");
        assertThat(saved.getStatus()).isEqualTo("DRAFT");
        assertThat(saved.getSpecJson()).contains("\"web\"");
        assertThat(saved.getTenantId()).isEqualTo("t1");
    }

    @Test
    void namespaceMismatchBetweenSpecAndRequestIsRejected() {
        ApplicationCreateRequest r = req(spec());
        r.setNamespace("other");

        assertThatThrownBy(() -> service.create(r, "admin", "t1"))
                .hasMessageContaining("命名空间不一致");
    }

    @Test
    void createAllowsEmptyGraphForCanvasDraft() {
        AppSpecDTO empty = new AppSpecDTO();
        empty.setNamespace("demo");
        empty.setNodes(List.of());
        empty.setEdges(List.of());

        service.create(req(empty), "admin", "t1");

        verify(apps).insert(any());
        verify(namespaceGuard).assertAccessible("c1", "demo");
    }

    @Test
    void createRequiresTenantEvenForAdmin() {
        assertThatThrownBy(() -> service.create(req(spec()), "admin", null))
                .hasMessageContaining("请先切换到某个租户");
        verify(apps, never()).insert(any());
    }

    @Test
    void getHidesOtherTenantsApplications() {
        when(apps.selectById("app_1")).thenReturn(row());

        assertThatThrownBy(() -> service.get("app_1", "someone-else"))
                .hasMessageContaining("不存在或已删除");
    }

    @Test
    void deletePrunesOwnedObjectsButKeepsData() {
        when(apps.selectById("app_1")).thenReturn(row());
        when(operations.selectActiveByApp("app_1")).thenReturn(null);
        when(resources.selectByApp("app_1")).thenReturn(List.of(
                resource("Service", "demo-app-web"),
                resource("PersistentVolumeClaim", "demo-app-web-data")));

        ApplicationDeleteRequest req = new ApplicationDeleteRequest();
        req.setAppId("app_1");
        service.delete(req, "admin", null);

        ArgumentCaptor<AppPruneRequestDTO> cap = ArgumentCaptor.forClass(AppPruneRequestDTO.class);
        verify(client).prune(cap.capture());
        assertThat(cap.getValue().isDeletePvc()).isFalse();
        assertThat(cap.getValue().getObjects()).hasSize(2);
        verify(apps).softDelete(eq("app_1"), any());
    }

    @Test
    void deleteRefusesWhileDeploying() {
        when(apps.selectById("app_1")).thenReturn(row());
        when(operations.selectActiveByApp("app_1"))
                .thenReturn(new com.coding.data.models.system.PlatformApplicationOperation());

        ApplicationDeleteRequest req = new ApplicationDeleteRequest();
        req.setAppId("app_1");

        assertThatThrownBy(() -> service.delete(req, "admin", null)).hasMessageContaining("正在部署");
        verify(apps, never()).softDelete(any(), any());
    }
}
