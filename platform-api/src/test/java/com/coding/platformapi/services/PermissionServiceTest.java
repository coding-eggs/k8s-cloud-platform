package com.coding.platformapi.services;

import com.coding.common.exception.CloudPlatformException;
import com.coding.data.mapper.auth.PlatformPermissionMapper;
import com.coding.data.models.auth.PlatformPermission;
import com.coding.platformapi.models.PermissionPointRequest;
import com.coding.platformapi.security.PermissionRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 权限点管理（CRUD + 热加载）：字段校验、裸端点保护（写前拒绝、零写入）、提交后重读换表口径。
 * mapper mock 用 db 列表模拟 DB 状态：selectAllActive 返回快照、写方法改 db —— 近似"写后重读到已提交数据"。
 */
class PermissionServiceTest {

    PlatformPermissionMapper mapper = mock(PlatformPermissionMapper.class);
    RequestMappingHandlerMapping handlerMapping = mock(RequestMappingHandlerMapping.class);
    List<PlatformPermission> db = new ArrayList<>();
    PermissionRegistry registry;
    PermissionService svc;

    @BeforeEach
    void setUp() {
        when(mapper.selectAllActive()).thenAnswer(inv -> new ArrayList<>(db));
        doAnswer(inv -> { db.add(inv.getArgument(0)); return 1; }).when(mapper).insert(any());
        doAnswer(inv -> {
            PlatformPermission u = inv.getArgument(0);
            for (int i = 0; i < db.size(); i++)
                if (db.get(i).getId().equals(u.getId())) db.set(i, u);
            return 1;
        }).when(mapper).updateByPrimaryKeySelective(any());
        doAnswer(inv -> {
            String id = inv.getArgument(0);
            db.stream().filter(p -> p.getId().equals(id)).forEach(p -> p.setDeletedAt(new Date()));
            return 1;
        }).when(mapper).softDeleteById(any());
        when(mapper.selectByPrimaryKey(anyString())).thenAnswer(inv -> db.stream()
                .filter(p -> p.getId().equals(inv.getArgument(0)) && p.getDeletedAt() == null)
                .findFirst().orElse(null));

        // 活端点：两个业务端点 + 一个豁免端点（/user/me —— 验证与启动 Runner 共享同一豁免语义）
        when(handlerMapping.getHandlerMethods()).thenReturn(Map.of(
                info("POST", "/tenant/create"), DUMMY_HANDLER,
                info("POST", "/role/list"), DUMMY_HANDLER,
                info("GET", "/user/me"), DUMMY_HANDLER));

        db.add(row("p1", "API", "/tenant/create", "POST", "platform:tenant:provision"));
        db.add(row("p2", "API", "/role/list", "POST", "platform:role:read"));
        registry = new PermissionRegistry(rulesOf(db)); // 模拟启动加载的表
        svc = new PermissionService(mapper, new PermissionRowWriter(mapper), registry, handlerMapping);
    }

    // ---------- 字段校验（先于任何写入） ----------

    @Test
    void create_rejects_code_without_scope_prefix() {
        assertThatThrownBy(() -> svc.create(req("API", "/tenant/create", "POST", "nope")))
                .isInstanceOfSatisfying(CloudPlatformException.class, e -> assertThat(e.getCode()).isEqualTo(1004));
        verify(mapper, never()).insert(any());
    }

    @Test
    void create_rejects_unknown_domain() {
        assertThatThrownBy(() -> svc.create(req("Web", "/x", "GET", "platform:x")))
                .isInstanceOfSatisfying(CloudPlatformException.class, e -> assertThat(e.getCode()).isEqualTo(1004));
        verify(mapper, never()).insert(any());
    }

    @Test
    void create_rejects_api_resource_without_leading_slash() {
        assertThatThrownBy(() -> svc.create(req("API", "tenant/create", "POST", "platform:x")))
                .isInstanceOfSatisfying(CloudPlatformException.class, e -> assertThat(e.getCode()).isEqualTo(1004));
        verify(mapper, never()).insert(any());
    }

    @Test
    void create_rejects_unknown_action() {
        assertThatThrownBy(() -> svc.create(req("API", "/x", "FETCH", "platform:x")))
                .isInstanceOfSatisfying(CloudPlatformException.class, e -> assertThat(e.getCode()).isEqualTo(1004));
        verify(mapper, never()).insert(any());
    }

    // ---------- 裸端点保护（写前拒绝，零写入） ----------

    @Test
    void delete_only_covering_row_rejected_and_zero_writes() {
        assertThatThrownBy(() -> svc.delete("p1"))
                .isInstanceOfSatisfying(CloudPlatformException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(10031);
                    assertThat(e.getMsg()).contains("/tenant/create");
                });
        verify(mapper, never()).softDeleteById(any());
        // 运行时表未动
        assertThat(registry.requiredCodes("POST", "/tenant/create")).isPresent();
    }

    @Test
    void delete_allowed_when_same_url_still_covered_by_other_row() {
        db.add(row("p3", "API", "/role/list", "POST", "tenant:role:read")); // 同 URL 的第二行（ANY-of）
        svc.delete("p2");
        verify(mapper).softDeleteById("p2");
        // 换表后 /role/list 仍被剩余 code 覆盖
        assertThat(registry.requiredCodes("POST", "/role/list").orElseThrow()).contains("tenant:role:read");
    }

    @Test
    void update_moving_pattern_away_from_endpoint_rejected() {
        var r = req("API", "/tenant/rename", "POST", "platform:tenant:provision"); // 覆盖从 /tenant/create 移走
        r.setId("p1");
        assertThatThrownBy(() -> svc.update(r))
                .isInstanceOfSatisfying(CloudPlatformException.class, e -> assertThat(e.getCode()).isEqualTo(10031));
        verify(mapper, never()).updateByPrimaryKeySelective(any());
    }

    // ---------- 通过校验：写库 + 提交后重读换表 ----------

    @Test
    void create_persists_and_hot_swaps_rules() {
        PlatformPermission created = svc.create(req("API", "/role/list", "POST", "platform:role:read"));
        assertThat(created.getId()).isNotBlank();
        assertThat(created.getCreatedAt()).isNotNull();
        verify(mapper).insert(created);
        // 换表后运行时含新规则（提交后实读）
        assertThat(registry.requiredCodes("POST", "/role/list").orElseThrow()).contains("platform:role:read");
    }

    @Test
    void create_non_api_domain_persists_but_stays_out_of_runtime_rules() {
        // seed 现仅 API 行；K8S/Page 域为占位（不进运行时规则），action 同走 HTTP 方法枚举（B5）
        svc.create(req("K8S", "Deployment", "GET", "tenant:workload:manage"));
        verify(mapper).insert(any());
        // 与 PermissionRegistryFactory 同口径：仅 API 域行进规则
        assertThat(registry.rules()).noneMatch(rule -> rule.pattern().equals("Deployment"));
    }

    @Test
    void update_persists_merged_row_and_hot_swaps_wider_pattern() {
        var r = req("API", "/role/**", "POST", "platform:role:read"); // 放宽 pattern（仍覆盖 /role/list）
        r.setId("p2");
        svc.update(r);
        ArgumentCaptor<PlatformPermission> cap = ArgumentCaptor.forClass(PlatformPermission.class);
        verify(mapper).updateByPrimaryKeySelective(cap.capture());
        assertThat(cap.getValue().getId()).isEqualTo("p2");
        assertThat(cap.getValue().getResource()).isEqualTo("/role/**");
        // 放宽后通配仍命中 /role/list（提交后实读生效）
        assertThat(registry.requiredCodes("POST", "/role/list").orElseThrow()).contains("platform:role:read");
    }

    @Test
    void update_unknown_id_rejected() {
        var r = req("API", "/role/list", "POST", "platform:role:read");
        r.setId("nope");
        assertThatThrownBy(() -> svc.update(r))
                .isInstanceOfSatisfying(CloudPlatformException.class, e -> assertThat(e.getCode()).isEqualTo(10027));
        verify(mapper, never()).updateByPrimaryKeySelective(any());
    }

    @Test
    void delete_unknown_id_rejected() {
        assertThatThrownBy(() -> svc.delete("nope"))
                .isInstanceOfSatisfying(CloudPlatformException.class, e -> assertThat(e.getCode()).isEqualTo(10027));
        verify(mapper, never()).softDeleteById(any());
    }

    // ---------- 显式重载（SQL 直接改库后的对齐入口） ----------

    @Test
    void reload_rereads_db_and_swaps_table() {
        db.add(row("p4", "API", "/tenant/create", "POST", "platform:tenant:audit")); // 模拟 SQL 加行
        svc.reload();
        assertThat(registry.requiredCodes("POST", "/tenant/create").orElseThrow()).contains("platform:tenant:audit");
    }

    @Test
    void reload_rejects_when_db_would_leave_endpoint_bare() {
        db.removeIf(p -> p.getId().equals("p1")); // 模拟 SQL 直删唯一覆盖行
        assertThatThrownBy(() -> svc.reload())
                .isInstanceOfSatisfying(CloudPlatformException.class, e -> assertThat(e.getCode()).isEqualTo(10031));
        // 不换表：旧规则仍生效（fail-closed）
        assertThat(registry.requiredCodes("POST", "/tenant/create").orElseThrow()).contains("platform:tenant:provision");
    }

    // ---------- helpers ----------

    private static final HandlerMethod DUMMY_HANDLER;
    static {
        try {
            DUMMY_HANDLER = new HandlerMethod(new Object() { public void ping() {} }, "ping");
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }

    private static RequestMappingInfo info(String method, String path) {
        return RequestMappingInfo.paths(path).methods(RequestMethod.valueOf(method)).build();
    }

    private static PlatformPermission row(String id, String domain, String resource, String action, String code) {
        var p = new PlatformPermission();
        p.setId(id);
        p.setDomain(domain);
        p.setResource(resource);
        p.setAction(action);
        p.setCode(code);
        return p;
    }

    private static PermissionPointRequest req(String domain, String resource, String action, String code) {
        var r = new PermissionPointRequest();
        r.setDomain(domain);
        r.setResource(resource);
        r.setAction(action);
        r.setCode(code);
        return r;
    }

    /** 与 PermissionRegistryFactory 同口径：仅 API 域行进规则（resource→pattern，action→method）。 */
    private static List<PermissionRegistry.Rule> rulesOf(List<PlatformPermission> rows) {
        return rows.stream()
                .filter(p -> "API".equals(p.getDomain()))
                .map(p -> new PermissionRegistry.Rule(p.getAction(), p.getResource(), p.getCode()))
                .toList();
    }
}
