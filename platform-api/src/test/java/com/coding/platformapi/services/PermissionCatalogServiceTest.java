package com.coding.platformapi.services;

import com.coding.data.mapper.auth.PlatformPermissionMapper;
import com.coding.data.models.auth.PlatformPermission;
import com.coding.platformapi.models.PermissionGroup;
import com.coding.platformapi.models.PermissionRuleView;
import com.coding.platformapi.security.PermissionRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 目录接口（/permission/catalog）：一行 = 一条规则，响应按 code 分组。
 *
 * <p>顺序是契约的一部分：组按 domain → code 升序，组内规则按 resource → action 升序。
 * 前端依赖它做稳定的折叠列表，不能交给 DB 的隐式行序。
 */
class PermissionCatalogServiceTest {

    PlatformPermissionMapper mapper = mock(PlatformPermissionMapper.class);
    PermissionService svc = new PermissionService(
            mapper, mock(PermissionRowWriter.class), new PermissionRegistry(List.of()),
            mock(RequestMappingHandlerMapping.class));

    @BeforeEach
    void setUp() {
        when(mapper.selectAllActive()).thenReturn(List.of(
                row("r3", "API", "/role/list", "POST", "platform:role:read", null),
                row("r1", "API", "/workloads/{name}", "GET", "tenant:workload:get", "查看详情"),
                row("r2", "API", "/workloads/{name}/yaml", "GET", "tenant:workload:get", "查看 YAML"),
                row("r4", "API", "/workloads/{name}", "DELETE", "tenant:workload:get", "删除"),
                row("p1", "Page", "/resources/workloads", "VIEW", "tenant:page:workload.list", "页面")
        ));
    }

    @Test
    void groups_rows_by_code_and_orders_groups_by_domain_then_code() {
        List<PermissionGroup> groups = svc.catalog();

        assertThat(groups).extracting(PermissionGroup::code)
                .containsExactly("platform:role:read", "tenant:workload:get", "tenant:page:workload.list");
        assertThat(groups).extracting(PermissionGroup::domain)
                .containsExactly("API", "API", "Page");
    }

    @Test
    void keeps_every_rule_of_a_code_and_sorts_by_resource_then_action() {
        PermissionGroup workload = svc.catalog().get(1);

        assertThat(workload.rules()).extracting(PermissionRuleView::resource, PermissionRuleView::action)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("/workloads/{name}", "DELETE"),
                        org.assertj.core.groups.Tuple.tuple("/workloads/{name}", "GET"),
                        org.assertj.core.groups.Tuple.tuple("/workloads/{name}/yaml", "GET"));
        assertThat(workload.rules()).extracting(PermissionRuleView::id)
                .containsExactly("r4", "r1", "r2");
    }

    @Test
    void keeps_row_id_and_description_for_editing() {
        PermissionGroup workload = svc.catalog().get(1);

        PermissionRuleView detail = workload.rules().stream()
                .filter(r -> r.id().equals("r1")).findFirst().orElseThrow();
        assertThat(detail.description()).isEqualTo("查看详情");
        assertThat(detail.action()).isEqualTo("GET");
    }

    @Test
    void null_description_is_preserved_as_null() {
        PermissionGroup role = svc.catalog().get(0);
        assertThat(role.rules().get(0).description()).isNull();
    }

    private static PlatformPermission row(String id, String domain, String resource, String action,
                                          String code, String description) {
        var p = new PlatformPermission();
        p.setId(id);
        p.setDomain(domain);
        p.setResource(resource);
        p.setAction(action);
        p.setCode(code);
        p.setDescription(description);
        return p;
    }
}
