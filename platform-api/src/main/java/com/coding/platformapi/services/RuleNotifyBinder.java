package com.coding.platformapi.services;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.k8s.dto.PrometheusRuleDTO;
import com.coding.data.mapper.alert.RuleNotifyMapper;
import com.coding.data.models.alert.NotificationChannel;
import com.coding.data.models.alert.RuleNotify;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * 把「这条规则发给谁、用哪个通知方式」写进 {@code platform_rule_notify}（**CR 级**绑定）。
 *
 * <p>为什么绑定进表而不进 K8s label：label 一个值只能放一个字符串（≤63），放不下用户列表；
 * 让每个收件人占一个 label 又会随人数膨胀，且**收件人一变就改标签集 → 告警 fingerprint 变 →
 * 同一次故障重新触发**。label 里只放渠道 code（只为展示与告警自带）。
 *
 * <p>规则改名/删除后绑定成为孤儿：告警里带着旧 code 时按默认渠道处理，不阻断保存。
 */
@Service
@RequiredArgsConstructor
public class RuleNotifyBinder {

    private final NotificationChannelService channels;
    private final RuleNotifyMapper mapper;
    private final JsonMapper json = JsonMapper.builder().build();

    /**
     * 写了渠道才写绑定（没选 = 走默认，不落行）。
     *
     * @throws CloudPlatformException 渠道 code 在库中不存在
     */
    public void bind(PrometheusRuleDTO body) {
        String code = body.getNotifyChannel();
        if (!StringUtils.hasText(code)) {
            return;
        }
        NotificationChannel channel = channels.resolve(code);
        if (channel == null) {
            throw new CloudPlatformException(EnumResponseType.NOTIFY_CHANNEL_INVALID,
                    "通知方式不存在: " + code);
        }
        RuleNotify r = new RuleNotify();
        r.setId(UUID.randomUUID().toString().replace("-", ""));
        r.setClusterId(body.getClusterId());
        r.setNamespace(body.getNamespace());
        r.setRuleName(body.getName());
        r.setChannelCode(code);
        List<String> userIds = body.getNotifyUserIds() == null ? List.of() : body.getNotifyUserIds();
        r.setUserIds(json.writeValueAsString(userIds));
        Date now = new Date();
        r.setCreatedAt(now);
        r.setUpdatedAt(now);
        mapper.upsert(r);
    }

    /**
     * 读：把已存的绑定回填到 DTO 上（详情页回填用）。
     *
     * <p>为什么必须在服务端做：{@code notifyChannel}/{@code notifyUserIds} **不在 CR 里**，
     * 集群侧对象读回来永远是 null。前端若拿不到，编辑一次规则就会以为「用户取消了通知方式」，
     * 而 {@code stampNamespace} 也不会再打 label —— 绑定表还在、label 却没了，两边开始打架。
     */
    public void fill(PrometheusRuleDTO dto) {
        if (dto == null || !StringUtils.hasText(dto.getClusterId())
                || !StringUtils.hasText(dto.getNamespace()) || !StringUtils.hasText(dto.getName())) {
            return;
        }
        RuleNotify r = mapper.selectByRule(dto.getClusterId(), dto.getNamespace(), dto.getName());
        if (r == null) {
            return;
        }
        dto.setNotifyChannel(r.getChannelCode());
        dto.setNotifyUserIds(parseIds(r.getUserIds()));
    }

    /** user_ids 是 JSON 数组字符串；空/坏值一律当空列表（坏数据不该让详情页 500）。 */
    private List<String> parseIds(String jsonIds) {
        if (!StringUtils.hasText(jsonIds)) {
            return List.of();
        }
        try {
            return json.readValue(jsonIds, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }
}
