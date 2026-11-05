package com.coding.auth.internal;

import com.coding.auth.config.InternalEndpointCrossCheckRunner;
import com.coding.common.components.permission.PermissionCrossCheck.Endpoint;
import com.coding.data.models.auth.PlatformPermission;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** auth 启动自检：每个 {@code /internal/**} 端点都要有 service=platform-auth 的权限行，否则拒启（网关 spec §8.5）。 */
class InternalEndpointCrossCheckRunnerTest {

    private static final List<Endpoint> ENDPOINTS = List.of(
            new Endpoint("POST", "/internal/clients/list"),
            new Endpoint("POST", "/internal/clients/delete"),
            new Endpoint("POST", "/internal/jwk/generate"),
            // 非 /internal/** 的端点（登录、授权服务器自己的）不归本校验管
            new Endpoint("POST", "/user/login"),
            new Endpoint("*", "/session/logout"));

    private static PlatformPermission row(String service, String path) {
        PlatformPermission p = new PlatformPermission();
        p.setDomain("API");
        p.setService(service);
        p.setAction("POST");
        p.setResource(path);
        p.setCode("x:y");
        return p;
    }

    private static List<PlatformPermission> fullRows() {
        return new ArrayList<>(List.of(
                row("platform-auth", "/internal/clients/list"),
                row("platform-auth", "/internal/clients/delete"),
                row("platform-auth", "/internal/jwk/generate")));
    }

    @Test
    void completeRowsPass() {
        assertThatCode(() -> InternalEndpointCrossCheckRunner.verify(ENDPOINTS, fullRows())).doesNotThrowAnyException();
    }

    @Test
    void missingRowAbortsStartupNamingTheEndpoint() {
        List<PlatformPermission> rows = fullRows();
        rows.removeIf(p -> p.getResource().equals("/internal/clients/delete"));

        assertThatThrownBy(() -> InternalEndpointCrossCheckRunner.verify(ENDPOINTS, rows))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("/internal/clients/delete");
    }

    /** 同路径的 api 行不算数：service 不同就是不同端点 */
    @Test
    void rowOfAnotherServiceDoesNotCover() {
        List<PlatformPermission> rows = fullRows();
        rows.removeIf(p -> p.getResource().equals("/internal/clients/delete"));
        rows.add(row("platform-api", "/internal/clients/delete"));

        assertThatThrownBy(() -> InternalEndpointCrossCheckRunner.verify(ENDPOINTS, rows))
                .isInstanceOf(IllegalStateException.class);
    }
}
