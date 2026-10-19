package com.coding.platformapi.services;

import com.coding.data.mapper.auth.PlatformUserMapper;
import com.coding.data.mapper.auth.PlatformUserTenantMapper;
import com.coding.data.mapper.auth.PlatformUserTenantRoleMapper;
import com.coding.data.models.auth.PlatformUser;
import com.coding.data.models.auth.PlatformUserTenant;
import com.coding.platformapi.models.NotificationUserView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NotificationUserServiceTest {

    private PlatformUserTenantMapper members;
    private PlatformUserTenantRoleMapper roles;
    private PlatformUserMapper users;
    private NotificationUserService svc;

    @BeforeEach
    void setUp() {
        members = mock(PlatformUserTenantMapper.class);
        roles = mock(PlatformUserTenantRoleMapper.class);
        users = mock(PlatformUserMapper.class);
        svc = new NotificationUserService(members, roles, users);
    }

    private static PlatformUser user(String id, String name, String email) {
        PlatformUser u = new PlatformUser();
        u.setId(id);
        u.setDisplayName(name);
        u.setEmail(email);
        return u;
    }

    private static PlatformUserTenant member(String userId) {
        PlatformUserTenant m = new PlatformUserTenant();
        m.setUserId(userId);
        return m;
    }

    /** 只返回该租户的成员，且带上邮箱（下拉里要显示）。 */
    @Test
    void list_returns_the_tenant_members_with_email() {
        when(members.selectByTenantId("t1")).thenReturn(List.of(member("u1"), member("u2")));
        when(users.selectByIds(List.of("u1", "u2")))
                .thenReturn(List.of(user("u1", "张三", "z@x.com"), user("u2", "李四", null)));

        var out = svc.listByTenant("t1");

        assertThat(out).extracting(NotificationUserView::id).containsExactly("u1", "u2");
        assertThat(out.get(0).email()).isEqualTo("z@x.com");
        assertThat(out.get(1).email()).isNull();
    }

    /** 租户没有成员时返回空，不报错。 */
    @Test
    void an_empty_tenant_yields_an_empty_list() {
        when(members.selectByTenantId("t2")).thenReturn(List.of());
        assertThat(svc.listByTenant("t2")).isEmpty();
    }

    /** 取租户的管理员用户 id（默认收件人用）—— 角色 **code** 是 tenant-admin，不是 admin。 */
    @Test
    void tenant_admin_ids_are_resolved_by_role_code() {
        when(roles.selectUserIdsByTenantAndRoleCode("t1", "tenant-admin")).thenReturn(List.of("u1"));

        assertThat(svc.tenantAdminUserIds("t1")).containsExactly("u1");
    }

    /** 投递用：没有邮箱的用户必须被过滤掉（否则会往空地址发）。 */
    @Test
    void emailsOf_drops_users_without_an_email() {
        when(users.selectByIds(List.of("u1", "u2", "u3")))
                .thenReturn(List.of(user("u1", "张三", "z@x.com"),
                        user("u2", "李四", "  "),
                        user("u3", "王五", "w@x.com")));

        assertThat(svc.emailsOf(List.of("u1", "u2", "u3"))).containsExactly("z@x.com", "w@x.com");
    }

    /** 收件人列表为空时不能去查库（那会生成 `in ()` 语法错误）。 */
    @Test
    void emailsOf_with_no_ids_does_not_query() {
        assertThat(svc.emailsOf(List.of())).isEmpty();
        org.mockito.Mockito.verifyNoInteractions(users);
    }
}
