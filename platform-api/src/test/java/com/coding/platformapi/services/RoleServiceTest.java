package com.coding.platformapi.services;

import com.coding.common.exception.CloudPlatformException;
import com.coding.data.mapper.auth.PlatformPermissionMapper;
import com.coding.data.mapper.auth.PlatformRoleMapper;
import com.coding.data.mapper.auth.PlatformRolePermissionMapper;
import com.coding.data.models.auth.PlatformPermission;
import com.coding.data.models.auth.PlatformRole;
import com.coding.data.models.auth.PlatformRolePermission;
import com.coding.platformapi.models.RoleCreateRequest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RoleServiceTest {
    PlatformRoleMapper roleMapper = mock(PlatformRoleMapper.class);
    PlatformPermissionMapper permMapper = mock(PlatformPermissionMapper.class);
    PlatformRolePermissionMapper rpMapper = mock(PlatformRolePermissionMapper.class);
    RoleService svc = new RoleService(roleMapper, permMapper, rpMapper);

    private PlatformRole role(String code, String scope, byte built) {
        var r = new PlatformRole();
        r.setId("r1");
        r.setCode(code);
        r.setScope(scope);
        r.setBuiltIn(built);
        return r;
    }

    private PlatformPermission perm(String id, String code) {
        var p = new PlatformPermission();
        p.setId(id);
        p.setCode(code);
        return p;
    }

    @Test
    void tenant_role_rejects_platform_perm() {
        when(roleMapper.selectByPrimaryKey("r1")).thenReturn(role("tenant-admin", "TENANT", (byte) 1));
        when(permMapper.selectAllByCode("platform:user:manage"))
                .thenReturn(List.of(perm("p1", "platform:user:manage")));
        assertThatThrownBy(() -> svc.savePermissions("r1", List.of("platform:user:manage")))
                .isInstanceOf(CloudPlatformException.class);
        // scope 不变量必须在任何写操作（delete）之前校验
        verify(rpMapper, never()).deleteByRoleId(any());
        verify(rpMapper, never()).insert(any());
    }

    @Test
    void platform_role_may_hold_tenant_perm() {
        // 2026-10-08 起 PLATFORM 角色不限码族（平台族在运行时本就是租户族的超集），
        // 原先的「PLATFORM 只能 platform:」+「豁免 code=admin」两条已一并删除。
        when(roleMapper.selectByPrimaryKey("r1")).thenReturn(role("platform-ops", "PLATFORM", (byte) 0));
        when(permMapper.selectAllByCode("tenant:member:manage"))
                .thenReturn(List.of(perm("p1", "tenant:member:manage")));
        svc.savePermissions("r1", List.of("tenant:member:manage"));
        verify(rpMapper).deleteByRoleId("r1");
        verify(rpMapper).insert(any());
    }

    @Test
    void assignable_for_tenant_role_is_tenant_family_only() {
        when(roleMapper.selectByPrimaryKey("r1")).thenReturn(role("tenant-admin", "TENANT", (byte) 1));
        when(permMapper.selectAllActive()).thenReturn(List.of(
                perm("p_t1", "tenant:workload:list"),
                perm("p_t2", "tenant:page:workload.list"),
                perm("p_p1", "platform:user:manage"),
                perm("p_p2", "platform:page:user")));
        assertThat(svc.assignablePermissions("r1")).extracting(PlatformPermission::getCode)
                .containsExactly("tenant:workload:list", "tenant:page:workload.list");
    }

    @Test
    void assignable_for_platform_role_is_everything() {
        // 内置 admin 不再需要特例：PLATFORM 族一律全量（含资源域 tenant:* 码）
        when(roleMapper.selectByPrimaryKey("r1")).thenReturn(role("admin", "PLATFORM", (byte) 1));
        when(roleMapper.selectByPrimaryKey("r2")).thenReturn(role("platform-audit", "PLATFORM", (byte) 0));
        when(permMapper.selectAllActive()).thenReturn(List.of(
                perm("p_t1", "tenant:workload:list"),
                perm("p_p1", "platform:user:manage")));
        assertThat(svc.assignablePermissions("r1")).extracting(PlatformPermission::getCode)
                .containsExactly("tenant:workload:list", "platform:user:manage");
        assertThat(svc.assignablePermissions("r2")).extracting(PlatformPermission::getCode)
                .containsExactly("tenant:workload:list", "platform:user:manage");
    }

    @Test
    void unknown_perm_code_rejected_before_any_delete() {
        when(roleMapper.selectByPrimaryKey("r1")).thenReturn(role("tenant-admin", "TENANT", (byte) 1));
        when(permMapper.selectAllByCode("tenant:nope:manage")).thenReturn(List.of());
        assertThatThrownBy(() -> svc.savePermissions("r1", List.of("tenant:nope:manage")))
                .isInstanceOf(CloudPlatformException.class);
        verify(rpMapper, never()).deleteByRoleId(any());
    }

    @Test
    void builtin_role_delete_rejected() {
        when(roleMapper.selectByPrimaryKey("r1")).thenReturn(role("admin", "PLATFORM", (byte) 1));
        assertThatThrownBy(() -> svc.delete("r1")).isInstanceOf(CloudPlatformException.class);
        verify(roleMapper, never()).deleteByPrimaryKey(any());
    }

    @Test
    void custom_role_delete_removes_role_and_links() {
        when(roleMapper.selectByPrimaryKey("r1")).thenReturn(role("my-role", "TENANT", (byte) 0));
        svc.delete("r1");
        verify(roleMapper).deleteByPrimaryKey("r1");
        verify(rpMapper).deleteByRoleId("r1");
    }

    @Test
    void valid_tenant_perm_saves_every_row_of_code() {
        // Carry 1：code 不再唯一 —— tenant:member:manage 有 4 行（多 URL），必须逐行落关联
        when(roleMapper.selectByPrimaryKey("r1")).thenReturn(role("tenant-admin", "TENANT", (byte) 1));
        when(permMapper.selectAllByCode("tenant:member:manage")).thenReturn(List.of(
                perm("p_add", "tenant:member:manage"),
                perm("p_rm", "tenant:member:manage"),
                perm("p_grant", "tenant:member:manage"),
                perm("p_revoke", "tenant:member:manage")));
        svc.savePermissions("r1", List.of("tenant:member:manage"));
        verify(rpMapper).deleteByRoleId("r1");
        ArgumentCaptor<PlatformRolePermission> cap = ArgumentCaptor.forClass(PlatformRolePermission.class);
        verify(rpMapper, times(4)).insert(cap.capture());
        assertThat(cap.getAllValues().stream()
                .map(PlatformRolePermission::getPermissionId)
                .collect(Collectors.toList()))
                .containsExactlyInAnyOrder("p_add", "p_rm", "p_grant", "p_revoke");
        assertThat(cap.getAllValues()).allSatisfy(rp -> {
            assertThat(rp.getRoleId()).isEqualTo("r1");
            assertThat(rp.getId()).isNotBlank();
            assertThat(rp.getCreatedAt()).isNotNull();
        });
    }

    @Test
    void duplicate_codes_in_request_insert_only_once() {
        when(roleMapper.selectByPrimaryKey("r1")).thenReturn(role("tenant-admin", "TENANT", (byte) 0));
        when(permMapper.selectAllByCode("tenant:member:manage")).thenReturn(List.of(perm("p1", "tenant:member:manage")));
        svc.savePermissions("r1", List.of("tenant:member:manage", "tenant:member:manage"));
        verify(rpMapper, times(1)).insert(any());
    }

    @Test
    void empty_code_list_clears_links_only() {
        when(roleMapper.selectByPrimaryKey("r1")).thenReturn(role("my-role", "TENANT", (byte) 0));
        svc.savePermissions("r1", List.of());
        verify(rpMapper).deleteByRoleId("r1");
        verify(rpMapper, never()).insert(any());
        verifyNoInteractions(permMapper);
    }

    @Test
    void create_rejects_invalid_scope() {
        RoleCreateRequest req = new RoleCreateRequest();
        req.setName("r");
        req.setCode("r");
        req.setScope("GLOBAL");
        assertThatThrownBy(() -> svc.create(req)).isInstanceOf(CloudPlatformException.class);
        verify(roleMapper, never()).insert(any());
    }

    @Test
    void create_rejects_duplicate_code() {
        when(roleMapper.selectByCode("dup")).thenReturn(role("dup", "TENANT", (byte) 0));
        assertThatThrownBy(() -> svc.create(req("dup", "TENANT")))
                .isInstanceOf(CloudPlatformException.class);
        verify(roleMapper, never()).insert(any());
    }

    @Test
    void create_sets_builtin_zero_and_status() {
        when(roleMapper.selectByCode("new-role")).thenReturn(null);
        PlatformRole r = svc.create(req("new-role", "TENANT"));
        assertThat(r.getBuiltIn()).isEqualTo((byte) 0);
        assertThat(r.getStatus()).isEqualTo((byte) 1);
        assertThat(r.getId()).isNotBlank();
        verify(roleMapper).insert(r);
    }

    @Test
    void permission_codes_of_delegates_to_mapper() {
        when(rpMapper.selectPermissionCodesByRoleId("r1")).thenReturn(List.of("tenant:member:manage"));
        assertThat(svc.permissionCodesOf("r1")).containsExactly("tenant:member:manage");
    }

    private RoleCreateRequest req(String code, String scope) {
        var r = new RoleCreateRequest();
        r.setName(code);
        r.setCode(code);
        r.setScope(scope);
        return r;
    }
}
