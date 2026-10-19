package com.coding.platformapi.services;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.models.k8s.dto.PrometheusRuleDTO;
import com.coding.data.mapper.alert.RuleNotifyMapper;
import com.coding.data.models.alert.NotificationChannel;
import com.coding.data.models.alert.RuleNotify;
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
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RuleNotifyBinderTest {

    private NotificationChannelService channels;
    private RuleNotifyMapper mapper;
    private RuleNotifyBinder binder;

    @BeforeEach
    void setUp() {
        channels = mock(NotificationChannelService.class);
        mapper = mock(RuleNotifyMapper.class);
        binder = new RuleNotifyBinder(channels, mapper);
    }

    private static PrometheusRuleDTO rule(String ns, String name, String channel, List<String> users) {
        PrometheusRuleDTO d = new PrometheusRuleDTO();
        d.setClusterId("c1");
        d.setTenantId("t1");
        d.setNamespace(ns);
        d.setName(name);
        d.setNotifyChannel(channel);
        d.setNotifyUserIds(users);
        return d;
    }

    /** 写了渠道 → upsert 一行绑定，user_ids 是 JSON。 */
    @Test
    void a_selected_channel_is_persisted() {
        NotificationChannel c = new NotificationChannel();
        c.setCode("ops-mail");
        when(channels.resolve("ops-mail")).thenReturn(c);

        binder.bind(rule("mon-app", "app-rules", "ops-mail", List.of("u1", "u2")));

        ArgumentCaptor<RuleNotify> cap = ArgumentCaptor.forClass(RuleNotify.class);
        verify(mapper).upsert(cap.capture());
        assertThat(cap.getValue().getClusterId()).isEqualTo("c1");
        assertThat(cap.getValue().getNamespace()).isEqualTo("mon-app");
        assertThat(cap.getValue().getRuleName()).isEqualTo("app-rules");
        assertThat(cap.getValue().getChannelCode()).isEqualTo("ops-mail");
        assertThat(cap.getValue().getUserIds()).contains("u1").contains("u2");
    }

    /** 收件人没选 → 空数组的 JSON，不是 null（投递端直接解析）。 */
    @Test
    void an_empty_recipient_list_is_stored_as_an_empty_array() {
        NotificationChannel c = new NotificationChannel();
        c.setCode("ops-mail");
        when(channels.resolve("ops-mail")).thenReturn(c);

        binder.bind(rule("ns", "r", "ops-mail", null));

        ArgumentCaptor<RuleNotify> cap = ArgumentCaptor.forClass(RuleNotify.class);
        verify(mapper).upsert(cap.capture());
        assertThat(cap.getValue().getUserIds()).isEqualTo("[]");
    }

    /** 渠道不存在 → 拒绝（不能把规则存进一个没人认识的 code）。 */
    @Test
    void an_unknown_channel_is_rejected() {
        when(channels.resolve("nope")).thenReturn(null);

        assertThatThrownBy(() -> binder.bind(rule("ns", "r", "nope", List.of())))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("通知方式");

        verify(mapper, never()).upsert(any());
    }

    /** 没选渠道 → 不写绑定（历史行为：走默认）。 */
    @Test
    void an_empty_channel_writes_nothing() {
        binder.bind(rule("ns", "r", null, List.of()));

        verifyNoInteractions(mapper);
    }
}
