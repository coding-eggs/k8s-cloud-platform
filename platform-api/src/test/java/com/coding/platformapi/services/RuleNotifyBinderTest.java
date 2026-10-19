package com.coding.platformapi.services;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.models.k8s.dto.PrometheusRuleDTO;
import com.coding.data.mapper.alert.RuleNotifyMapper;
import com.coding.data.mapper.auth.PlatformUserTenantMapper;
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
    private PlatformUserTenantMapper members;
    private RuleNotifyBinder binder;

    @BeforeEach
    void setUp() {
        channels = mock(NotificationChannelService.class);
        mapper = mock(RuleNotifyMapper.class);
        members = mock(PlatformUserTenantMapper.class);
        binder = new RuleNotifyBinder(channels, mapper, members);
        when(members.exists(any(), any())).thenReturn(true);
    }

    private void channelExists(String code) {
        NotificationChannel c = new NotificationChannel();
        c.setCode(code);
        when(channels.resolve(code)).thenReturn(c);
    }

    private static PrometheusRuleDTO rule(String ns, String name) {
        PrometheusRuleDTO d = new PrometheusRuleDTO();
        d.setClusterId("c1");
        d.setTenantId("t1");
        d.setNamespace(ns);
        d.setName(name);
        return d;
    }

    /** 写了渠道 → upsert 一行绑定，user_ids 是 JSON。 */
    @Test
    void a_selected_channel_is_persisted() {
        channelExists("ops-mail");

        binder.bind("c1", "t1", "mon-app", "app-rules", "ops-mail", List.of("u1", "u2"));

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
        channelExists("ops-mail");

        binder.bind("c1", "t1", "ns", "r", "ops-mail", null);

        ArgumentCaptor<RuleNotify> cap = ArgumentCaptor.forClass(RuleNotify.class);
        verify(mapper).upsert(cap.capture());
        assertThat(cap.getValue().getUserIds()).isEqualTo("[]");
    }

    /** 渠道不存在 → 拒绝（不能把规则存进一个没人认识的 code）。 */
    @Test
    void an_unknown_channel_is_rejected() {
        when(channels.resolve("nope")).thenReturn(null);

        assertThatThrownBy(() -> binder.bind("c1", "t1", "ns", "r", "nope", List.of()))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("通知方式");

        verify(mapper, never()).upsert(any());
    }

    /**
     * ⚠️ 收件人必须是**该租户的成员**。前端下拉只列本租户用户，裸 API 挡不住 ——
     * 不校验的话，租户可以把告警内容发到任意平台用户的邮箱里。
     */
    @Test
    void a_recipient_outside_the_tenant_is_rejected() {
        channelExists("ops-mail");
        when(members.exists("stranger", "t1")).thenReturn(false);

        assertThatThrownBy(() -> binder.bind("c1", "t1", "ns", "r", "ops-mail", List.of("u1", "stranger")))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("不是租户 t1 的成员");

        verify(mapper, never()).upsert(any());
    }

    /** 选「不通知」= 清空 → 删掉旧绑定（不清就是「以为改了、实际没改」）。 */
    @Test
    void clearing_the_channel_deletes_the_binding() {
        binder.bind("c1", "t1", "ns", "r", null, List.of());

        verify(mapper).deleteByRule("c1", "ns", "r");
        verify(mapper, never()).upsert(any());
    }

    /** 定位不完备（没有 clusterId 等）→ 什么都不做，别写一条查不回来的行。 */
    @Test
    void an_incomplete_key_writes_nothing() {
        binder.bind(null, "t1", "ns", "r", "ops-mail", List.of());

        verifyNoInteractions(mapper);
    }

    /** 详情回填：绑定表里的渠道与收件人补回 DTO（它们在 CR 里不存在）。 */
    @Test
    void fill_backfills_the_binding_onto_the_dto() {
        RuleNotify stored = new RuleNotify();
        stored.setChannelCode("ops-mail");
        stored.setUserIds("[\"u1\",\"u2\"]");
        when(mapper.selectByRule("c1", "mon-app", "app-rules")).thenReturn(stored);

        PrometheusRuleDTO dto = rule("mon-app", "app-rules");
        binder.fill(dto);

        assertThat(dto.getNotifyChannel()).isEqualTo("ops-mail");
        assertThat(dto.getNotifyUserIds()).containsExactly("u1", "u2");
    }

    /** 没有绑定 → 两个字段保持 null（前端据此显示「走默认」）。 */
    @Test
    void fill_leaves_the_dto_alone_when_there_is_no_binding() {
        when(mapper.selectByRule("c1", "mon-app", "app-rules")).thenReturn(null);

        PrometheusRuleDTO dto = rule("mon-app", "app-rules");
        binder.fill(dto);

        assertThat(dto.getNotifyChannel()).isNull();
        assertThat(dto.getNotifyUserIds()).isNull();
    }
}
