package com.coding.platformapi.services;

import com.coding.data.mapper.k8s.PlatformRbacTemplateMapper;
import com.coding.data.mapper.auth.PlatformTenantNamespaceMapper;
import com.coding.data.models.k8s.PlatformRbacTemplate;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 模板重下发：把 DB 里全部模板逐个推到所有启用集群。
 *
 * <p>为什么需要它：平台只在"保存模板"时 sync。迁移改了模板 rules（本批给租户 SA 补 controllerrevisions 读权限）
 * 不会自动落到各集群已有的 tn-tpl-* ClusterRole 上 —— 没有重下发，租户回滚 StatefulSet/DaemonSet 会 403。
 */
class RbacTemplateServiceSyncAllTest {

    private final PlatformRbacTemplateMapper templateMapper = mock(PlatformRbacTemplateMapper.class);
    private final PlatformTenantNamespaceMapper allocationMapper = mock(PlatformTenantNamespaceMapper.class);
    private final K8sProvisioningService provisioning = mock(K8sProvisioningService.class);
    private final RbacTemplateService svc = new RbacTemplateService(templateMapper, allocationMapper, provisioning,
            JsonMapper.builder().build());

    private PlatformRbacTemplate template(String id, String name) {
        PlatformRbacTemplate t = new PlatformRbacTemplate();
        t.setId(id);
        t.setName(name);
        return t;
    }

    @Test
    void sync_all_pushes_every_template() {
        PlatformRbacTemplate a = template("t-a", "tenant-namespace-admin");
        PlatformRbacTemplate b = template("t-b", "only-read");
        when(templateMapper.listAll()).thenReturn(List.of(a, b));

        svc.syncAll();

        verify(provisioning).syncTemplateToAllEnabledClusters(a);
        verify(provisioning).syncTemplateToAllEnabledClusters(b);
    }

    /** 没有任何模板时不该报错（空库 / 全删场景照样能点这个按钮） */
    @Test
    void sync_all_with_no_templates_is_a_noop() {
        when(templateMapper.listAll()).thenReturn(List.of());

        svc.syncAll();

        verify(provisioning, times(0)).syncTemplateToAllEnabledClusters(org.mockito.ArgumentMatchers.any());
    }
}
