package com.coding.auth.service;

import com.coding.common.exception.CloudPlatformException;
import com.coding.data.mapper.auth.PlatformUserMapper;
import com.coding.data.models.auth.PlatformUser;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 自助改密 / 改资料。
 *
 * <p>本模块此前**零测试**，而这两条恰恰是有分支、且错法的后果不对称的逻辑
 * （「当前这台留着、别的踢掉」写反了，用户会被自己改密码给踢下线）。
 */
class AccountServiceTest {

    private final PlatformUserMapper users = mock(PlatformUserMapper.class);
    private final PasswordEncoder encoder = new BCryptPasswordEncoder();

    @SuppressWarnings("unchecked")
    private final FindByIndexNameSessionRepository<Session> sessions =
            mock(FindByIndexNameSessionRepository.class);

    private final AccountService svc = new AccountService(users, encoder, sessions);

    private PlatformUser alice(String plainPassword) {
        PlatformUser u = new PlatformUser();
        u.setId("u1");
        u.setUsername("alice");
        u.setPassword(encoder.encode(plainPassword));
        u.setDisplayName("Alice");
        u.setEmail("alice@example.com");
        return u;
    }

    /** 三个会话：s2 当作"当前这台"。 */
    private void aliceHasThreeSessions() {
        Map<String, Session> found = new LinkedHashMap<>();
        for (String id : new String[]{"s1", "s2", "s3"}) {
            Session s = mock(Session.class);
            when(s.getId()).thenReturn(id);
            found.put(id, s);
        }
        when(sessions.findByPrincipalName("alice")).thenReturn(found);
    }

    // ---- 改密码 ----

    @Test
    void aWrongCurrentPasswordIsRefusedAndNothingIsTouched() {
        when(users.selectByUsername("alice")).thenReturn(alice("old-password"));

        assertThatThrownBy(() -> svc.changePassword("alice", "猜的", "brand-new-pass", "s2"))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("原密码");

        // ⚠️ 拒绝就要**真拒绝**：既不改库，也不踢任何会话
        verify(users, never()).updateByPrimaryKey(any());
        verify(sessions, never()).deleteById(anyString());
    }

    @Test
    void changingThePasswordKicksEveryOtherSessionButKeepsThisOne() {
        when(users.selectByUsername("alice")).thenReturn(alice("old-password"));
        aliceHasThreeSessions();

        AccountChangeResult r = svc.changePassword("alice", "old-password", "brand-new-pass", "s2");

        assertThat(r.sessionsKilled()).isEqualTo(2);
        // 改密码 **不**把人自己踢下线 —— 密码不在 token 里，没有"必须重登"的理由
        assertThat(r.reloginRequired()).isFalse();
        verify(sessions, never()).deleteById("s2");
        verify(sessions).deleteById("s1");
        verify(sessions).deleteById("s3");

        ArgumentCaptor<PlatformUser> saved = ArgumentCaptor.forClass(PlatformUser.class);
        verify(users).updateByPrimaryKey(saved.capture());
        assertThat(encoder.matches("brand-new-pass", saved.getValue().getPassword()))
                .as("存进去的必须是新密码的 BCrypt 哈希").isTrue();
        assertThat(saved.getValue().getUpdatedAt()).as("顺手更新时间戳").isNotNull();
    }

    @Test
    void aTooShortNewPasswordIsRefused() {
        when(users.selectByUsername("alice")).thenReturn(alice("old-password"));

        assertThatThrownBy(() -> svc.changePassword("alice", "old-password", "1234567", "s2"))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("至少 8 位");
        verify(users, never()).updateByPrimaryKey(any());
    }

    @Test
    void theNewPasswordMustDifferFromTheCurrentOne() {
        // 「改了」其实没改 —— 用户以为换掉了，实际没换，不如直接说
        when(users.selectByUsername("alice")).thenReturn(alice("old-password"));

        assertThatThrownBy(() -> svc.changePassword("alice", "old-password", "old-password", "s2"))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("不能和原密码相同");
    }

    @Test
    void anUnknownUserIsRefused() {
        when(users.selectByUsername("nobody")).thenReturn(null);

        assertThatThrownBy(() -> svc.changePassword("nobody", "x", "brand-new-pass", "s2"))
                .isInstanceOf(CloudPlatformException.class);
    }

    // ---- 改资料 ----

    @Test
    void changingTheProfileKicksEverySessionIncludingThisOne() {
        // ⚠️ 与改密码相反：displayName / email 是**装在 token 的 data claim 里**的，
        //    不重新登录就会一直显示旧值 —— 所以这一趟必须把人整个请出去
        when(users.selectByUsername("alice")).thenReturn(alice("old-password"));
        aliceHasThreeSessions();

        AccountChangeResult r = svc.updateProfile("alice", "新的名字", "new@example.com", "s2");

        assertThat(r.reloginRequired()).isTrue();
        assertThat(r.sessionsKilled()).isEqualTo(3);
        verify(sessions).deleteById("s1");
        verify(sessions).deleteById("s2");   // ⭐ 当前这台也踢
        verify(sessions).deleteById("s3");

        ArgumentCaptor<PlatformUser> saved = ArgumentCaptor.forClass(PlatformUser.class);
        verify(users).updateByPrimaryKey(saved.capture());
        assertThat(saved.getValue().getDisplayName()).isEqualTo("新的名字");
        assertThat(saved.getValue().getEmail()).isEqualTo("new@example.com");
    }

    @Test
    void aTooLongDisplayNameIsRefusedBeforeAnythingIsWritten() {
        // 库里的列是 varchar(64) —— 超了会在 DB 层炸成一句看不懂的话，这里先拦
        when(users.selectByUsername("alice")).thenReturn(alice("old-password"));

        assertThatThrownBy(() -> svc.updateProfile("alice", "名".repeat(65), "", "s2"))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("64");
        verify(users, never()).updateByPrimaryKey(any());
        verify(sessions, never()).deleteById(anyString());
    }

    @Test
    void aBlankDisplayNameIsAllowedAndBecomesNull() {
        // 允许清掉昵称：界面会回落到用户名，比强行塞一个占位串好
        when(users.selectByUsername("alice")).thenReturn(alice("old-password"));

        svc.updateProfile("alice", "   ", "  ", "s2");

        ArgumentCaptor<PlatformUser> saved = ArgumentCaptor.forClass(PlatformUser.class);
        verify(users).updateByPrimaryKey(saved.capture());
        assertThat(saved.getValue().getDisplayName()).isNull();
        assertThat(saved.getValue().getEmail()).isNull();
    }
}
