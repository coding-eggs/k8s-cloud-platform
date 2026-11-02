package com.coding.platformapi.services;

import com.coding.data.mapper.system.PlatformCredentialMapper;
import com.coding.data.mapper.system.PlatformRegistryProjectMapper;
import com.coding.data.models.system.PlatformCredential;
import com.coding.data.models.system.PlatformRegistryProject;
import com.coding.platformapi.cache.RedisJsonCache;
import com.coding.platformapi.credential.CredentialCipher;
import com.coding.platformapi.registry.BrowseResult;
import com.coding.platformapi.registry.CredentialRef;
import com.coding.platformapi.registry.HarborInstallLocator;
import com.coding.platformapi.registry.ProbeSpec;
import com.coding.platformapi.registry.RegistryDialect;
import com.coding.platformapi.registry.RegistryDialectRegistry;
import com.coding.platformapi.registry.RegistrySuggestExecutor;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link RegistryService#newerTag}：新版本提示挑哪条连接去问。
 *
 * <p>钉三件事：只用调用者可见的连接（租户帽）、项目级 robot 只问它自己的项目、
 * 非 Harbor / 无 host 的镜像零请求。
 */
class RegistryNewerTagTest {

    private final PlatformCredentialMapper mapper = mock(PlatformCredentialMapper.class);
    private final PlatformRegistryProjectMapper projectMapper = mock(PlatformRegistryProjectMapper.class);
    private final CredentialCipher cipher = new CredentialCipher("test-only-master-key-0123456789abcdef");
    private final List<String> calls = Collections.synchronizedList(new ArrayList<>());
    /** 要抛异常的凭据 id；其余返回 answer */
    private final Set<String> failing = new HashSet<>();
    private String answer = "1.1.0";

    private final RegistryDialect harbor = new RegistryDialect() {
        @Override public String type() { return "HARBOR"; }
        @Override public Set<String> authTypes() { return Set.of("BASIC", "NONE"); }
        @Override public ProbeSpec probeSpec(CredentialRef c) { return new ProbeSpec("x", "x"); }
        @Override public List<String> suggest(CredentialRef c, String p) { return List.of(); }
        @Override public BrowseResult browse(CredentialRef c, List<String> p) {
            return new BrowseResult(List.of(), List.of());
        }
        @Override public Optional<String> newerTag(CredentialRef c, String repository, String liveTag) {
            calls.add(c.id() + ":" + repository + ":" + liveTag);
            if (failing.contains(c.id())) {
                throw new IllegalStateException("harbor down");
            }
            return Optional.ofNullable(answer);
        }
    };

    private final RegistryService svc = new RegistryService(mapper, projectMapper, cipher,
            new RegistryDialectRegistry(List.of(harbor)), mock(RedisJsonCache.class),
            new RegistrySuggestExecutor(), true, mock(HarborInstallLocator.class));

    private PlatformCredential cred(String id, String endpoint, String tenantId) {
        PlatformCredential c = new PlatformCredential();
        c.setId(id);
        c.setName(id);
        c.setType("HARBOR");
        c.setEndpoint(endpoint);
        c.setAuthType("BASIC");
        c.setEnabled(1);
        c.setTenantId(tenantId);
        c.setSecretEncrypted(cipher.encrypt("{\"username\":\"u\",\"secret\":\"p\"}"));
        return c;
    }

    private static PlatformRegistryProject ledger(String credentialId, String project) {
        PlatformRegistryProject p = new PlatformRegistryProject();
        p.setCredentialId(credentialId);
        p.setProjectName(project);
        return p;
    }

    @Test
    void tenantOnlyAsksItsOwnProjectRobotForItsOwnProject() {
        when(mapper.selectAll()).thenReturn(List.of(
                cred("mine", "harbor.x", "t1"), cred("theirs", "harbor.x", "t2")));
        when(projectMapper.selectAll()).thenReturn(List.of(ledger("mine", "t-one"), ledger("theirs", "t-two")));

        assertThat(svc.newerTag("harbor.x/t-one/app", "1.0.0", "t1")).isEqualTo("1.1.0");
        assertThat(calls).containsExactly("mine:t-one/app:1.0.0");
    }

    @Test
    void tenantNeverBorrowsAnotherTenantsRobotOrReachesOtherProjects() {
        when(mapper.selectAll()).thenReturn(List.of(
                cred("mine", "harbor.x", "t1"), cred("theirs", "harbor.x", "t2")));
        when(projectMapper.selectAll()).thenReturn(List.of(ledger("mine", "t-one"), ledger("theirs", "t-two")));

        // 别家的项目：t1 可见的连接里没有一条能问 -> 零请求
        assertThat(svc.newerTag("harbor.x/t-two/app", "1.0.0", "t1")).isNull();
        assertThat(calls).isEmpty();
    }

    @Test
    void adminWithAManualNonProjectConnectionCanAskAnyProject() {
        when(mapper.selectAll()).thenReturn(List.of(cred("manual", "https://harbor.x/", null)));
        when(projectMapper.selectAll()).thenReturn(List.of());

        assertThat(svc.newerTag("harbor.x/anything/app", "1", null)).isEqualTo("1.1.0");
        assertThat(calls).containsExactly("manual:anything/app:1");
    }

    @Test
    void imageWithoutHostOrWithUnknownHostMakesNoRequest() {
        when(mapper.selectAll()).thenReturn(List.of(cred("manual", "harbor.x", null)));
        when(projectMapper.selectAll()).thenReturn(List.of());

        assertThat(svc.newerTag("nginx", "1", null)).isNull();
        assertThat(svc.newerTag("myteam/app", "1", null)).isNull();
        assertThat(svc.newerTag("other.registry/p/app", "1", null)).isNull();
        assertThat(calls).isEmpty();
    }

    @Test
    void secondCandidateIsTriedWhenTheFirstFails() {
        when(mapper.selectAll()).thenReturn(List.of(
                cred("a", "harbor.x", null), cred("b", "harbor.x", null)));
        when(projectMapper.selectAll()).thenReturn(List.of());
        failing.add("a");

        assertThat(svc.newerTag("harbor.x/p/app", "1", null)).isEqualTo("1.1.0");
        assertThat(calls).containsExactly("a:p/app:1", "b:p/app:1");
    }

    @Test
    void failureIsReportedWhenEveryCandidateFails() {
        when(mapper.selectAll()).thenReturn(List.of(cred("a", "harbor.x", null)));
        when(projectMapper.selectAll()).thenReturn(List.of());
        failing.add("a");

        assertThatThrownBy(() -> svc.newerTag("harbor.x/p/app", "1", null))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void nothingNewerIsNull() {
        when(mapper.selectAll()).thenReturn(List.of(cred("a", "harbor.x", null)));
        when(projectMapper.selectAll()).thenReturn(List.of());
        answer = null;

        assertThat(svc.newerTag("harbor.x/p/app", "1", null)).isNull();
    }
}
