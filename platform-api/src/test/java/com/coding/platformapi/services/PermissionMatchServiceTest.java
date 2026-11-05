package com.coding.platformapi.services;

import com.coding.common.exception.CloudPlatformException;
import com.coding.data.mapper.auth.PlatformPermissionMapper;
import com.coding.data.models.auth.PlatformPermission;
import com.coding.platformapi.models.PermissionGroup;
import com.coding.platformapi.models.PermissionMatchRequest;
import com.coding.platformapi.models.PermissionMatchResponse;
import com.coding.common.components.permission.PermissionRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 按 URL 反查（/permission/match）：给定实际请求方法 + 路径，返回覆盖它的权限行与 code。
 *
 * <p>判据必须与运行时鉴权 {@link PermissionRegistry#requiredCodes} 完全一致 ——
 * 否则排障时看到的结果会和真实 403/放行对不上。最后一条测试专门钉死这一点。
 */
class PermissionMatchServiceTest {

    PlatformPermissionMapper mapper = mock(PlatformPermissionMapper.class);
    List<PlatformPermission> rows = List.of(
            row("r1", "API", "/workloads/{name}", "GET", "tenant:workload:get", "查看详情"),
            row("r2", "API", "/workloads/list", "GET", "tenant:workload:list", "列出工作负载"),
            row("r3", "API", "/workloads/list", "POST", "tenant:workload:create", "创建工作负载"),
            row("r4", "API", "/role/list", "POST", "platform:role:read", null),
            row("p1", "Page", "/resources/workloads", "VIEW", "tenant:page:workload.list", "页面"),
            // 脏数据：Page 域却写了 GET —— 反查只扫 API 域，不能把它算进来
            row("p2", "Page", "/page-only", "GET", "tenant:page:workload.list", null)
    );
    PermissionRegistry registry = new PermissionRegistry(rows.stream()
            .filter(p -> "API".equals(p.getDomain()))
            .map(p -> PermissionRegistry.Rule.from(p))
            .toList());
    PermissionService svc = new PermissionService(
            mapper, mock(PermissionRowWriter.class), registry, mock(RequestMappingHandlerMapping.class));

    @BeforeEach
    void setUp() {
        when(mapper.selectAllActive()).thenReturn(rows);
    }

    @Test
    void matches_exact_and_pattern_rows_for_the_same_request() {
        PermissionMatchResponse resp = svc.match(req("GET", "/workloads/list"));

        assertThat(resp.totalRules()).isEqualTo(2);
        assertThat(resp.matched()).extracting(PermissionGroup::code)
                .containsExactly("tenant:workload:get", "tenant:workload:list");
        assertThat(flat(resp)).containsExactlyInAnyOrder(
                "/workloads/{name} GET", "/workloads/list GET");
    }

    @Test
    void different_method_only_returns_that_method() {
        PermissionMatchResponse resp = svc.match(req("POST", "/workloads/list"));

        assertThat(resp.totalRules()).isEqualTo(1);
        assertThat(flat(resp)).containsExactly("/workloads/list POST");
        assertThat(resp.matched().get(0).code()).isEqualTo("tenant:workload:create");
    }

    @Test
    void method_is_case_insensitive() {
        assertThat(flat(svc.match(req("get", "/workloads/list"))))
                .containsExactlyInAnyOrder("/workloads/{name} GET", "/workloads/list GET");
    }

    @Test
    void no_match_returns_empty_groups() {
        PermissionMatchResponse resp = svc.match(req("PATCH", "/workloads/list"));
        assertThat(resp.totalRules()).isZero();
        assertThat(resp.matched()).isEmpty();
    }

    @Test
    void page_rows_never_participate_in_url_matching() {
        assertThat(svc.match(req("GET", "/page-only")).matched()).isEmpty();
    }

    @Test
    void rejects_unknown_method() {
        assertThatThrownBy(() -> svc.match(req("FETCH", "/workloads/list")))
                .isInstanceOfSatisfying(CloudPlatformException.class, e -> assertThat(e.getCode()).isEqualTo(1004));
    }

    @Test
    void rejects_blank_or_relative_path() {
        assertThatThrownBy(() -> svc.match(req("GET", "  ")))
                .isInstanceOfSatisfying(CloudPlatformException.class, e -> assertThat(e.getCode()).isEqualTo(1004));
        assertThatThrownBy(() -> svc.match(req("GET", "workloads/list")))
                .isInstanceOfSatisfying(CloudPlatformException.class, e -> assertThat(e.getCode()).isEqualTo(1004));
    }

    @Test
    void rejects_overlong_path() {
        assertThatThrownBy(() -> svc.match(req("GET", "/" + "a".repeat(2048))))
                .isInstanceOfSatisfying(CloudPlatformException.class, e -> assertThat(e.getCode()).isEqualTo(1004));
    }

    /** 反查与真实鉴权同口径：同一批 (method,path) 下，命中的 code 集合必须逐一对齐。 */
    @Test
    void matched_codes_are_identical_to_runtime_required_codes() {
        String[][] cases = {
                {"GET", "/workloads/list"},
                {"get", "/workloads/{name}"},
                {"POST", "/workloads/list"},
                {"POST", "/role/list"},
                {"DELETE", "/workloads/list"},
                {"GET", "/not/registered"}
        };
        for (String[] c : cases) {
            Set<String> fromMatch = svc.match(req(c[0], c[1])).matched().stream()
                    .map(PermissionGroup::code).collect(Collectors.toSet());
            Set<String> fromRuntime = registry.requiredCodes("platform-api", c[0], c[1]).orElse(Set.of());
            assertThat(fromMatch).as("match vs requiredCodes for %s %s", c[0], c[1])
                    .isEqualTo(fromRuntime);
        }
    }

    private static List<String> flat(PermissionMatchResponse resp) {
        return resp.matched().stream()
                .flatMap(g -> g.rules().stream().map(r -> r.resource() + " " + r.action()))
                .toList();
    }

    private static PermissionMatchRequest req(String method, String path) {
        var r = new PermissionMatchRequest();
        r.setMethod(method);
        r.setPath(path);
        return r;
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
