package com.coding.platformapi.services;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.k8s.dto.PrometheusRuleDTO;
import com.coding.data.mapper.alert.RuleNotifyMapper;
import com.coding.data.mapper.auth.PlatformUserTenantMapper;
import com.coding.data.models.alert.RuleNotify;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
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
@Slf4j
@Service
@RequiredArgsConstructor
public class RuleNotifyBinder {

    private final NotificationChannelService channels;
    private final RuleNotifyMapper mapper;
    private final PlatformUserTenantMapper members;
    private final JsonMapper json = JsonMapper.builder().build();

    /**
     * **无副作用**的前置校验：渠道存在、收件人是本租户成员。
     * 在写 K8s 对象**之前**调它 —— 参数不对就该在什么都不改的前提下被拒。
     */
    public void validate(String tenantId, String channelCode, List<String> userIds) {
        if (!StringUtils.hasText(channelCode)) {
            return;
        }
        if (channels.resolve(channelCode) == null) {
            throw new CloudPlatformException(EnumResponseType.NOTIFY_CHANNEL_INVALID,
                    "通知方式不存在: " + channelCode);
        }
        requireTenantMembers(tenantId, userIds);
    }

    /**
     * 写入（或清除）绑定。**必须在 K8s 对象写成功之后调用** ——
     * 否则一次注定被拒的写入（租户越界、校验失败）也会留下绑定的副作用。
     *
     * <p>渠道 code 为空 = 用户**主动清空**（「留空 = 用默认」），此时删掉旧绑定。
     * 不清就等于「用户以为改了、实际还在按旧收件人发」。
     *
     * @throws CloudPlatformException 渠道不存在，或收件人不属于该租户
     */
    public void bind(String clusterId, String tenantId, String namespace, String ruleName,
                     String channelCode, List<String> userIds) {
        if (!StringUtils.hasText(clusterId) || !StringUtils.hasText(namespace)
                || !StringUtils.hasText(ruleName)) {
            return;                                  // 不完整的定位：不是可绑定的对象
        }
        if (!StringUtils.hasText(channelCode)) {
            mapper.deleteByRule(clusterId, namespace, ruleName);
            return;
        }

        if (channels.resolve(channelCode) == null) {
            throw new CloudPlatformException(EnumResponseType.NOTIFY_CHANNEL_INVALID,
                    "通知方式不存在: " + channelCode);
        }
        List<String> checked = requireTenantMembers(tenantId, userIds);

        RuleNotify r = new RuleNotify();
        r.setId(UUID.randomUUID().toString().replace("-", ""));
        r.setClusterId(clusterId);
        r.setNamespace(namespace);
        r.setRuleName(ruleName);
        r.setChannelCode(channelCode);
        r.setUserIds(json.writeValueAsString(checked));
        Date now = new Date();
        r.setCreatedAt(now);
        r.setUpdatedAt(now);
        mapper.upsert(r);
    }

    /**
     * 收件人必须是**该租户的成员**。前端下拉只列本租户用户，但裸 API 挡不住 ——
     * 不校验的话，租户可以把告警内容发到任意平台用户的邮箱里。
     * <p>没有租户上下文（平台管理员直接操作）时跳过校验：管理员本来就是可信方。
     */
    private List<String> requireTenantMembers(String tenantId, List<String> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return List.of();
        }
        if (!StringUtils.hasText(tenantId)) {
            return userIds;
        }
        List<String> out = new ArrayList<>();
        for (String uid : userIds) {
            if (!StringUtils.hasText(uid)) {
                continue;
            }
            if (!members.exists(uid, tenantId)) {
                throw new CloudPlatformException(EnumResponseType.NOTIFY_CHANNEL_INVALID,
                        "收件人 " + uid + " 不是租户 " + tenantId + " 的成员");
            }
            out.add(uid);
        }
        return out;
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
