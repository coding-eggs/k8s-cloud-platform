package com.coding.gateway.security;

import com.coding.common.components.permission.PermissionRegistry;
import com.coding.data.mapper.auth.PlatformPermissionMapper;
import com.coding.data.models.auth.PlatformPermission;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PermissionRegistryReloaderTest {

    private final PlatformPermissionMapper mapper = mock(PlatformPermissionMapper.class);
    private final PermissionRegistry registry = new PermissionRegistry(List.of());
    private final PermissionRegistryReloader reloader = new PermissionRegistryReloader(mapper, registry);

    private static PlatformPermission row(String domain, String service, String path) {
        PlatformPermission p = new PlatformPermission();
        p.setDomain(domain);
        p.setService(service);
        p.setAction("POST");
        p.setResource(path);
        p.setCode("x:y");
        return p;
    }

    @Test
    void reloadFailureKeepsPreviousRules() {
        when(mapper.selectAllActive())
                .thenReturn(List.of(row("API", "platform-api", "/a"), row("API", "platform-auth", "/internal/b")))
                .thenThrow(new DataAccessResourceFailureException("db down"))
                .thenReturn(List.of(row("API", "platform-api", "/a"), row("API", "platform-api", "/c"),
                        row("API", "platform-auth", "/internal/b")));

        reloader.reload();
        assertThat(registry.rules()).hasSize(2);
        reloader.reload(); // 库抖动：保留旧表，绝不清空（清空 = 全站 403）
        assertThat(registry.rules()).hasSize(2);
        reloader.reload();
        assertThat(registry.rules()).hasSize(3);
    }

    @Test
    void onlyApiDomainRowsOfAllServices() {
        when(mapper.selectAllActive()).thenReturn(List.of(
                row("API", "platform-api", "/a"), row("API", "platform-auth", "/internal/b"), row("Page", null, "x")));

        reloader.reload();

        assertThat(registry.rules()).extracting(PermissionRegistry.Rule::service)
                .containsExactlyInAnyOrder("platform-api", "platform-auth");
    }

    /** 某次重载抛 Error（如 NoClassDefFoundError）：不能让定时订阅终止，之后照常重载 */
    @Test
    void periodicReloadSurvivesError() throws Exception {
        when(mapper.selectAllActive())
                .thenReturn(List.of(row("API", "platform-api", "/a")))
                .thenThrow(new NoClassDefFoundError("boom"))
                .thenReturn(List.of(row("API", "platform-api", "/a"), row("API", "platform-api", "/c")));
        PermissionRegistryReloader periodic = new PermissionRegistryReloader(mapper, registry, Duration.ofMillis(50));
        periodic.afterPropertiesSet();
        try {
            long deadline = System.currentTimeMillis() + 3000;
            while (registry.rules().size() < 2 && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }
            assertThat(registry.rules()).hasSize(2);
        } finally {
            periodic.destroy();
        }
    }

    /** service 写错（对不上任何网关路由）的行永远不生效：照装但 WARN；同一批孤儿行不在每次定时重载里重复刷屏 */
    @Test
    void warnsOnceAboutRowsWhoseServiceHasNoRoute() {
        PlatformPermission typo = row("API", "platfrom-api", "/typo");
        typo.setCode("platform:typo:manage");
        when(mapper.selectAllActive()).thenReturn(List.of(row("API", "platform-api", "/a"), typo));
        PermissionRegistryReloader checked = new PermissionRegistryReloader(mapper, registry, null,
                Set.of("platform-api", "platform-auth"));

        Logger logger = (Logger) LoggerFactory.getLogger(PermissionRegistryReloader.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            checked.afterPropertiesSet();
            checked.reload();

            List<ILoggingEvent> warns = appender.list.stream().filter(e -> e.getLevel() == Level.WARN).toList();
            assertThat(warns).hasSize(1);
            assertThat(warns.get(0).getFormattedMessage())
                    .contains("platfrom-api").contains("platform:typo:manage").contains("/typo");
            assertThat(registry.rules()).hasSize(2); // 照装（fail-closed 本就不会放行），只告警
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void firstLoadFailureAbortsStartup() {
        when(mapper.selectAllActive()).thenThrow(new DataAccessResourceFailureException("db down"));

        assertThatThrownBy(reloader::afterPropertiesSet).isInstanceOf(IllegalStateException.class);
    }
}
