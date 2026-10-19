package com.coding.platformapi.services;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.utils.AESUtils;
import com.coding.data.mapper.alert.NotificationChannelMapper;
import com.coding.data.mapper.alert.RuleNotifyMapper;
import com.coding.data.models.alert.NotificationChannel;
import com.coding.platformapi.models.NotificationChannelRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationChannelServiceTest {

    private static final String KEY = "test-master-key";

    private NotificationChannelMapper mapper;
    private RuleNotifyMapper bindings;
    private NotificationChannelService svc;

    @BeforeEach
    void setUp() {
        mapper = mock(NotificationChannelMapper.class);
        bindings = mock(RuleNotifyMapper.class);
        svc = new NotificationChannelService(mapper, bindings, KEY);
    }

    private static NotificationChannelRequest req(String code, String name) {
        NotificationChannelRequest r = new NotificationChannelRequest();
        r.setCode(code);
        r.setName(name);
        r.setType("EMAIL");
        r.setTenantId(null);
        r.setEnabled(true);
        r.setIsDefault(false);
        r.setConfig(new java.util.LinkedHashMap<>(java.util.Map.of(
                "host", "smtp.example.com", "port", "465", "from", "a@example.com",
                "username", "a@example.com", "password", "secret")));
        return r;
    }

    /** code 必须是 K8s label 合法值（它会被写进 label）。 */
    @Test
    void an_invalid_code_is_rejected() {
        assertThatThrownBy(() -> svc.create(req("运维邮件", "运维")))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("code");
    }

    /** 密码必须加密后再落库，且列表返回时脱敏。 */
    @Test
    void the_password_is_encrypted_at_rest_and_masked_on_read() {
        svc.create(req("ops-mail", "运维邮件"));

        ArgumentCaptor<NotificationChannel> cap = ArgumentCaptor.forClass(NotificationChannel.class);
        verify(mapper).insert(cap.capture());
        NotificationChannel stored = cap.getValue();
        assertThat(stored.getConfig()).doesNotContain("secret").contains("passwordEnc");

        when(mapper.selectAll()).thenReturn(List.of(stored));
        assertThat(svc.list().get(0).getConfig()).doesNotContain("passwordEnc");
    }

    /** 设成默认时，先把同租户维度（null=全局）的旧默认清掉。 */
    @Test
    void setting_default_clears_the_previous_default_in_the_same_scope() {
        NotificationChannelRequest r = req("ops-mail", "运维邮件");
        r.setIsDefault(true);

        svc.create(r);

        verify(mapper).clearDefault(null);
    }

    /** 有规则绑定时禁止删除。 */
    @Test
    void a_channel_that_is_still_bound_cannot_be_deleted() {
        when(bindings.countByChannel("ops-mail")).thenReturn(2);

        assertThatThrownBy(() -> svc.delete("ops-mail"))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("仍被");

        verify(mapper, never()).deleteByCode(any());
    }

    /** 默认渠道回落：租户默认优先，其次全局默认，都没有返回 null。 */
    @Test
    void default_falls_back_from_tenant_to_global() {
        NotificationChannel tenantDefault = new NotificationChannel();
        tenantDefault.setCode("t1-mail");
        when(mapper.selectTenantDefault("t1")).thenReturn(tenantDefault);

        assertThat(svc.defaultFor("t1").getCode()).isEqualTo("t1-mail");

        when(mapper.selectTenantDefault("t2")).thenReturn(null);
        NotificationChannel global = new NotificationChannel();
        global.setCode("global-mail");
        when(mapper.selectGlobalDefault()).thenReturn(global);

        assertThat(svc.defaultFor("t2").getCode()).isEqualTo("global-mail");

        when(mapper.selectGlobalDefault()).thenReturn(null);
        assertThat(svc.defaultFor("t2")).isNull();
    }

    /** 解出来的配置里，password 是明文（发送时用），且不含 passwordEnc。 */
    @Test
    void decrypted_config_exposes_the_plain_password_for_sending() {
        NotificationChannel ch = new NotificationChannel();
        ch.setCode("ops-mail");
        ch.setConfig("{\"host\":\"smtp.example.com\",\"passwordEnc\":\""
                + AESUtils.encrypt("secret", KEY) + "\"}");

        assertThat(svc.decryptedConfig(ch))
                .containsEntry("host", "smtp.example.com")
                .containsEntry("password", "secret")
                .doesNotContainKey("passwordEnc");
    }

    /**
     * 编辑时不回填密码（前端留空 = 不修改）—— **必须沿用库里已存的密文**，
     * 否则改一次名称就把线上 SMTP 密码抹掉了。
     */
    @Test
    void update_keeps_the_stored_password_when_the_field_is_left_blank() {
        NotificationChannel existing = new NotificationChannel();
        existing.setId("id-1");
        existing.setCode("ops-mail");
        existing.setConfig("{\"host\":\"smtp.example.com\",\"passwordEnc\":\""
                + AESUtils.encrypt("old-secret", KEY) + "\"}");
        when(mapper.selectByCode("ops-mail")).thenReturn(existing);

        NotificationChannelRequest r = req("ops-mail", "运维邮件");
        r.getConfig().remove("password");

        svc.update("ops-mail", r);

        ArgumentCaptor<NotificationChannel> cap = ArgumentCaptor.forClass(NotificationChannel.class);
        verify(mapper).updateByCode(cap.capture());
        assertThat(svc.decryptedConfig(cap.getValue()))
                .containsEntry("password", "old-secret");
    }
}
